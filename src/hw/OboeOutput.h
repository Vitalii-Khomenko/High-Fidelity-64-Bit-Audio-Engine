#pragma once

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

#include <android/log.h>
#include <oboe/Oboe.h>

#include "../core/RingBuffer.h"

#define ENGINE_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "AudioEngine", __VA_ARGS__)
#define ENGINE_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "AudioEngine", __VA_ARGS__)

namespace audio_engine {
namespace hw {

/**
 * Output stage: ring buffer -> real-time gain/fade/limiter -> Oboe float stream.
 *
 * Threads:
 *   - the decode thread writes PCM with write()/flush();
 *   - the Oboe callback pulls from the ring (lock-free, no allocation);
 *   - control threads call configure()/start()/stop()/setVolume();
 *   - a small worker reopens the stream after a disconnect.
 *
 * Volume and transport fades are applied here, after the buffer, so they are
 * heard immediately instead of after the ~300 ms of decoded look-ahead.
 * Pausing stops consumption at an exact frame: resume continues from the same
 * sample without seeking the decoder.
 */
class OboeOutput : public oboe::AudioStreamDataCallback {
public:
    static constexpr size_t kSpectrumSize = 4096;
    static constexpr double kRingSeconds = 0.75;
    static constexpr double kLimiterCeiling = 0.989;  // -0.1 dBFS

    OboeOutput() : m_worker([this] { reconnectLoop(); }) {}

    ~OboeOutput() override {
        {
            std::lock_guard<std::mutex> lock(m_signal->mutex);
            m_signal->alive = false;
        }
        m_signal->cv.notify_all();
        m_worker.join();
        close();
    }

    OboeOutput(const OboeOutput&) = delete;
    OboeOutput& operator=(const OboeOutput&) = delete;

    /**
     * Opens a stream for the format and sizes the ring. The stream is left
     * stopped; call start(). If a multichannel layout cannot be opened the
     * stream falls back to stereo; check channels() afterwards.
     */
    bool configure(uint32_t sampleRate, int channels) {
        std::lock_guard<std::mutex> lock(m_streamMutex);
        closeLocked();
        m_runRequested.store(false, std::memory_order_release);
        m_sampleRate.store(sampleRate, std::memory_order_release);
        if (!openLocked(channels) && !(channels > 2 && openLocked(2))) {
            m_configured = false;
            return false;
        }
        m_configured = true;
        return true;
    }

    void close() {
        std::lock_guard<std::mutex> lock(m_streamMutex);
        closeLocked();
        m_configured = false;
    }

    /** Starts pulling audio (with a short fade-in). */
    bool start() {
        std::lock_guard<std::mutex> lock(m_streamMutex);
        if (!m_configured) return false;
        m_runRequested.store(true, std::memory_order_release);
        if (!m_stream && !openLocked(channels())) return false;
        if (m_started) return true;
        if (m_stream->requestStart() != oboe::Result::OK) {
            // A stale (disconnected) stream: reopen once on the current device.
            closeStreamLocked();
            if (!openLocked(channels()) || m_stream->requestStart() != oboe::Result::OK) {
                ENGINE_LOGE("Oboe requestStart failed");
                return false;
            }
        }
        m_started = true;
        return true;
    }

    /**
     * Fades out over fadeMs, stops consuming at that frame and pauses the
     * stream so the audio path can power down.
     */
    void stop(int fadeMs) {
        const double frames = std::max(1.0, fadeMs * static_cast<double>(sampleRate()) / 1000.0);
        m_fadeOutStep.store(1.0 / frames, std::memory_order_relaxed);
        m_runRequested.store(false, std::memory_order_release);
        bool started;
        {
            std::lock_guard<std::mutex> lock(m_streamMutex);
            started = m_started;
        }
        if (started) {
            // Wait for the fade and two silent callbacks so the faded tail has
            // left the device buffer before the stream is paused.
            const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(fadeMs + 400);
            while (std::chrono::steady_clock::now() < deadline) {
                if (m_idle.load(std::memory_order_acquire) &&
                    m_silentCallbacks.load(std::memory_order_acquire) >= 2) break;
                std::this_thread::sleep_for(std::chrono::milliseconds(2));
            }
        }
        std::lock_guard<std::mutex> lock(m_streamMutex);
        if (m_stream && m_started && !m_runRequested.load(std::memory_order_acquire)) {
            m_stream->requestPause();
            m_started = false;
        }
    }

    /** Lock-free: queries from the UI must not wait for a stream being opened. */
    bool isConfigured() const { return m_configured.load(std::memory_order_acquire); }
    bool isRunRequested() const { return m_runRequested.load(std::memory_order_acquire); }
    /** True when a lost device could not be reopened; cleared by the next successful open. */
    bool hasFailed() const { return m_failed.load(std::memory_order_acquire); }
    uint32_t sampleRate() const { return m_sampleRate.load(std::memory_order_acquire); }
    int channels() const { return m_channels.load(std::memory_order_acquire); }

    // ── Decode-thread side ───────────────────────────────────────────────────

    size_t writableFrames() const {
        return m_ring ? m_ring->getAvailableWrite() / static_cast<size_t>(channels()) : 0;
    }
    size_t bufferedFrames() const {
        return m_ring ? m_ring->getAvailableRead() / static_cast<size_t>(channels()) : 0;
    }
    size_t capacityFrames() const { return m_capacityFrames; }

    /** Writes whole frames only. Returns frames written. */
    size_t write(const double* interleaved, size_t frames) {
        if (!m_ring || frames == 0) return 0;
        const size_t ch = static_cast<size_t>(channels());
        const size_t room = m_ring->getAvailableWrite() / ch;
        const size_t toWrite = std::min(frames, room);
        if (toWrite == 0) return 0;
        const size_t written = m_ring->write(interleaved, toWrite * ch) / ch;
        m_writtenFrames.fetch_add(written, std::memory_order_acq_rel);
        return written;
    }

    /** Drops queued audio. Safe while the callback runs. */
    void flush() {
        if (!m_ring) return;
        m_ring->clear();
        m_writtenFrames.store(m_consumedFrames.load(std::memory_order_acquire), std::memory_order_release);
    }

    uint64_t consumedFrames() const { return m_consumedFrames.load(std::memory_order_acquire); }
    uint64_t writtenFrames() const { return m_writtenFrames.load(std::memory_order_acquire); }
    uint64_t underrunCount() const { return m_underruns.load(std::memory_order_relaxed); }

    void setVolume(double linear) {
        if (!std::isfinite(linear)) return;
        m_targetGain.store(std::clamp(linear, 0.0, 1.0), std::memory_order_relaxed);
    }

    /** Copies the newest n mono samples (oldest first). Returns samples copied. */
    size_t copySpectrum(float* dst, size_t n) const {
        n = std::min(n, kSpectrumSize);
        const uint32_t end = m_spectrumPos.load(std::memory_order_acquire);
        for (size_t i = 0; i < n; ++i) {
            const uint32_t idx = (end - static_cast<uint32_t>(n) + static_cast<uint32_t>(i)) & (kSpectrumSize - 1);
            dst[i] = m_spectrum[idx].load(std::memory_order_relaxed);
        }
        return n;
    }

    // ── Real-time callback ───────────────────────────────────────────────────

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* audioData, int32_t numFrames) override {
        float* out = static_cast<float*>(audioData);
        const size_t ch = static_cast<size_t>(m_channels.load(std::memory_order_relaxed));
        const size_t total = static_cast<size_t>(std::max<int32_t>(numFrames, 0));
        const bool run = m_runRequested.load(std::memory_order_acquire);

        if ((!run && m_fade <= 0.0) || !m_ring) {
            std::fill(out, out + total * ch, 0.0f);
            m_idle.store(true, std::memory_order_release);
            m_silentCallbacks.fetch_add(1, std::memory_order_acq_rel);
            return oboe::DataCallbackResult::Continue;
        }
        m_idle.store(false, std::memory_order_release);
        m_silentCallbacks.store(0, std::memory_order_release);

        const double targetGain = m_targetGain.load(std::memory_order_relaxed);
        const double fadeOutStep = m_fadeOutStep.load(std::memory_order_relaxed);
        uint32_t specPos = m_spectrumPos.load(std::memory_order_relaxed);
        size_t frame = 0;

        while (frame < total) {
            size_t want = std::min(total - frame, kChunkFrames);
            if (!run) {
                // Consume exactly the frames needed to finish the fade-out.
                const size_t left = static_cast<size_t>(std::ceil(m_fade / fadeOutStep));
                want = std::min(want, std::max<size_t>(left, 1));
            }
            const size_t got = m_ring->read(m_scratch.data(), want * ch) / ch;
            if (got > 0) m_consumedFrames.fetch_add(got, std::memory_order_acq_rel);

            for (size_t f = 0; f < got; ++f) {
                m_fade = run ? std::min(1.0, m_fade + m_fadeInStep) : std::max(0.0, m_fade - fadeOutStep);
                // Linear ramp: a full-scale volume change takes 30 ms and lands exactly.
                const double diff = targetGain - m_gain;
                m_gain = std::fabs(diff) <= m_gainStep ? targetGain : m_gain + std::copysign(m_gainStep, diff);
                const double g = m_fade * m_gain;
                double* s = m_scratch.data() + f * ch;
                double mono = 0.0;
                double peak = 0.0;
                for (size_t c = 0; c < ch; ++c) {
                    if (!std::isfinite(s[c])) s[c] = 0.0;  // last line of defence for the device
                    mono += s[c];
                    s[c] *= g;
                    peak = std::max(peak, std::fabs(s[c]));
                }
                m_spectrum[specPos & (kSpectrumSize - 1)].store(
                    static_cast<float>(mono * m_fade / static_cast<double>(ch)), std::memory_order_relaxed);
                ++specPos;
                // Peak limiter: instant attack, smooth release. Inactive below the ceiling.
                if (peak * m_limiterGain > kLimiterCeiling) m_limiterGain = kLimiterCeiling / peak;
                else m_limiterGain += (1.0 - m_limiterGain) * m_limiterRelease;
                float* o = out + (frame + f) * ch;
                for (size_t c = 0; c < ch; ++c) {
                    const float v = static_cast<float>(s[c] * m_limiterGain);
                    o[c] = v;
                    m_lastOut[c] = v;
                }
            }
            frame += got;

            if (!run && m_fade <= 0.0) break;
            if (got < want) {
                // Nothing left to fade: a stop now completes at once.
                if (!run) m_fade = 0.0;
                // Underrun: ramp the last sample to zero instead of stepping.
                m_underruns.fetch_add(1, std::memory_order_relaxed);
                const size_t ramp = std::min<size_t>(total - frame, 32);
                for (size_t f = 0; f < ramp; ++f) {
                    const float k = static_cast<float>(ramp - f - 1) / static_cast<float>(ramp);
                    for (size_t c = 0; c < ch; ++c) out[(frame + f) * ch + c] = m_lastOut[c] * k;
                }
                frame += ramp;
                std::fill(m_lastOut.begin(), m_lastOut.end(), 0.0f);
                break;
            }
        }
        if (frame < total) std::fill(out + frame * ch, out + total * ch, 0.0f);
        m_spectrumPos.store(specPos, std::memory_order_release);
        return oboe::DataCallbackResult::Continue;
    }

private:
    static constexpr size_t kChunkFrames = 512;
    static constexpr int kReconnectAttempts = 50;  // 200 ms apart: 10 s for a device to settle

    struct ReconnectSignal {
        std::mutex mutex;
        std::condition_variable cv;
        bool alive = true;
        bool pending = false;
    };

    // Holds only the shared signal, never the output itself: Oboe may report
    // an error after the stream (or this object) is gone.
    class ErrorCallback : public oboe::AudioStreamErrorCallback {
    public:
        explicit ErrorCallback(std::shared_ptr<ReconnectSignal> signal) : m_signal(std::move(signal)) {}
        void onErrorAfterClose(oboe::AudioStream*, oboe::Result error) override {
            ENGINE_LOGE("Oboe stream closed after error: %s", oboe::convertToText(error));
            {
                std::lock_guard<std::mutex> lock(m_signal->mutex);
                if (!m_signal->alive) return;
                m_signal->pending = true;
            }
            m_signal->cv.notify_one();
        }
    private:
        std::shared_ptr<ReconnectSignal> m_signal;
    };

    bool openLocked(int channels) {
        closeStreamLocked();
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::None)
            ->setSharingMode(oboe::SharingMode::Shared)
            ->setUsage(oboe::Usage::Media)
            ->setContentType(oboe::ContentType::Music)
            ->setFormat(oboe::AudioFormat::Float)
            ->setFormatConversionAllowed(true)
            ->setChannelCount(channels)
            ->setChannelConversionAllowed(true)
            ->setSampleRate(static_cast<int32_t>(sampleRate()))
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::High)
            ->setDataCallback(this)
            ->setErrorCallback(std::make_shared<ErrorCallback>(m_signal));

        std::shared_ptr<oboe::AudioStream> stream;
        const oboe::Result result = builder.openStream(stream);
        if (result != oboe::Result::OK || !stream) {
            ENGINE_LOGE("Oboe openStream(%u Hz, %d ch) failed: %s",
                        sampleRate(), channels, oboe::convertToText(result));
            return false;
        }
        if (stream->getSampleRate() != static_cast<int32_t>(sampleRate()) ||
            stream->getChannelCount() != channels ||
            stream->getFormat() != oboe::AudioFormat::Float) {
            ENGINE_LOGE("Oboe returned an unexpected format (%d Hz, %d ch)",
                        stream->getSampleRate(), stream->getChannelCount());
            stream->close();
            return false;
        }
        if (channels != this->channels() || !m_ring) {
            // No stream is running here, so callback-side state may change.
            // A reconnect with the same layout keeps the queued audio.
            m_channels.store(channels, std::memory_order_release);
            const size_t samples = static_cast<size_t>(sampleRate() * kRingSeconds) * static_cast<size_t>(channels);
            m_ring = std::make_unique<core::RingBuffer<double>>(samples);
            m_capacityFrames = m_ring->getAvailableWrite() / static_cast<size_t>(channels);
            m_writtenFrames.store(m_consumedFrames.load(std::memory_order_acquire), std::memory_order_release);
        }
        m_scratch.assign(kChunkFrames * static_cast<size_t>(channels), 0.0);
        m_lastOut.assign(static_cast<size_t>(channels), 0.0f);
        const double sr = static_cast<double>(sampleRate());
        m_gainStep = 1.0 / std::max(1.0, 0.03 * sr);
        m_limiterRelease = 1.0 - std::exp(-1.0 / (0.15 * sr));
        m_fadeInStep = 1.0 / std::max(1.0, 0.012 * sr);
        m_fade = 0.0;
        m_gain = m_targetGain.load(std::memory_order_relaxed);
        m_limiterGain = 1.0;
        m_idle.store(true, std::memory_order_release);
        m_stream = std::move(stream);
        m_started = false;
        m_failed.store(false, std::memory_order_release);
        ENGINE_LOGI("Oboe stream opened: %u Hz, %d ch", sampleRate(), channels);
        return true;
    }

    void closeStreamLocked() {
        if (m_stream) {
            m_stream->stop();
            m_stream->close();
            m_stream.reset();
        }
        m_started = false;
    }

    void closeLocked() {
        closeStreamLocked();
        m_ring.reset();
        m_channels.store(0, std::memory_order_release);
        m_capacityFrames = 0;
    }

    void reconnectLoop() {
        std::unique_lock<std::mutex> signalLock(m_signal->mutex);
        while (m_signal->alive) {
            m_signal->cv.wait(signalLock, [this] { return !m_signal->alive || m_signal->pending; });
            if (!m_signal->alive) break;
            m_signal->pending = false;
            signalLock.unlock();
            bool reopened = false;
            for (int attempt = 0; attempt < kReconnectAttempts && !reopened; ++attempt) {
                {
                    std::lock_guard<std::mutex> lock(m_streamMutex);
                    if (!m_configured) { reopened = true; break; }
                    if (m_stream) {
                        const oboe::StreamState state = m_stream->getState();
                        if (state != oboe::StreamState::Closed && state != oboe::StreamState::Disconnected) {
                            reopened = true;  // already replaced by configure()/start()
                            break;
                        }
                    }
                    // Reopen with the same format so pitch and channel layout
                    // stay correct; Oboe resamples if the new device differs.
                    // Recovered only once the stream is open and, if playback
                    // was running, actually started again.
                    if (openLocked(channels())) {
                        if (!m_runRequested.load(std::memory_order_acquire)) {
                            reopened = true;
                        } else if (m_stream->requestStart() == oboe::Result::OK) {
                            m_started = true;
                            reopened = true;
                        } else {
                            closeStreamLocked();
                        }
                    }
                }
                if (!reopened) {
                    std::unique_lock<std::mutex> wait(m_signal->mutex);
                    m_signal->cv.wait_for(wait, std::chrono::milliseconds(200), [this] { return !m_signal->alive; });
                    if (!m_signal->alive) return;
                }
            }
            // Every attempt failed: report it so the player stops claiming to play.
            m_failed.store(!reopened, std::memory_order_release);
            signalLock.lock();
        }
    }

    // Stream ownership (control threads, guarded by m_streamMutex).
    mutable std::mutex m_streamMutex;
    std::shared_ptr<oboe::AudioStream> m_stream;
    std::atomic<bool> m_configured{false};
    bool m_started = false;
    // Written under m_streamMutex while no stream runs; read from any thread.
    std::atomic<uint32_t> m_sampleRate{48000};
    std::atomic<int> m_channels{0};
    size_t m_capacityFrames = 0;

    // Shared with the callback. Changed only while no stream is open.
    std::unique_ptr<core::RingBuffer<double>> m_ring;
    std::vector<double> m_scratch;
    std::vector<float> m_lastOut;
    double m_gainStep = 0.001;
    double m_limiterRelease = 0.0001;
    double m_fadeInStep = 0.001;

    // Callback-private state.
    double m_fade = 0.0;
    double m_gain = 1.0;
    double m_limiterGain = 1.0;

    std::atomic<bool> m_failed{false};
    std::atomic<bool> m_runRequested{false};
    std::atomic<bool> m_idle{true};
    std::atomic<int> m_silentCallbacks{0};
    std::atomic<double> m_targetGain{1.0};
    std::atomic<double> m_fadeOutStep{0.001};
    std::atomic<uint64_t> m_consumedFrames{0};
    std::atomic<uint64_t> m_writtenFrames{0};
    std::atomic<uint64_t> m_underruns{0};
    std::array<std::atomic<float>, kSpectrumSize> m_spectrum{};
    std::atomic<uint32_t> m_spectrumPos{0};

    std::shared_ptr<ReconnectSignal> m_signal = std::make_shared<ReconnectSignal>();
    std::thread m_worker;  // declared last: starts after every member is ready
};

} // namespace hw
} // namespace audio_engine
