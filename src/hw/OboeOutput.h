#pragma once

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstring>
#include <functional>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

#include <android/log.h>
#include <oboe/Oboe.h>

#include "../core/RingBuffer.h"
#include "../dsp/Resampler.h"
#include "PcmEncoder.h"

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
 *
 * Direct (bit-perfect) mode: a resolver set by the app is asked before every
 * open which format the device takes at that rate (after the app has set
 * Android 14's bit-perfect mixer attributes). The stream is then opened at
 * exactly that rate and format with every conversion disabled, so a mismatch
 * fails and falls back to the shared mixer instead of resampling silently.
 *
 * Shared mode opens the stream at the mixer's own rate. When the file's rate
 * differs, the callback converts it here (dsp::PolyphaseResampler, 64-bit) so
 * Android's mixer does not resample. The ring and every frame count stay at
 * the file's rate; only the callback runs at the device rate.
 */
class OboeOutput : public oboe::AudioStreamDataCallback {
public:
    static constexpr size_t kSpectrumSize = 4096;
    static constexpr double kRingSeconds = 0.75;
    static constexpr double kLimiterCeiling = 0.989;  // -0.1 dBFS
    // Direct output only limits real overs: every sample a file can hold passes.
    static constexpr double kDirectLimiterCeiling = 1.0;

    /** Returns the SampleEncoding id the device takes for (rate, channels), or -1 for the shared mixer. */
    using DirectResolver = std::function<int(uint32_t sampleRate, int channels)>;

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
        const double frames = std::max(1.0, fadeMs * static_cast<double>(deviceRate()) / 1000.0);
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

    /**
     * Sets (or clears, with an empty function) the direct-output resolver and
     * reopens a running stream with it, keeping the queued audio and position.
     */
    void setDirectResolver(DirectResolver resolver) {
        {
            std::lock_guard<std::mutex> lock(m_streamMutex);
            m_resolver = std::move(resolver);
        }
        requestReopen();
    }

    /**
     * Closes the stream and lets the worker open it again (resolver asked
     * anew, ring kept). For output-device changes and mode switches.
     */
    void requestReopen() {
        {
            std::lock_guard<std::mutex> lock(m_streamMutex);
            if (!m_configured || !m_stream) return;
            closeStreamLocked();
        }
        {
            std::lock_guard<std::mutex> lock(m_signal->mutex);
            if (!m_signal->alive) return;
            m_signal->pending = true;
        }
        m_signal->cv.notify_one();
    }

    /** Lock-free: queries from the UI must not wait for a stream being opened. */
    bool isConfigured() const { return m_configured.load(std::memory_order_acquire); }
    bool isRunRequested() const { return m_runRequested.load(std::memory_order_acquire); }
    /** True when a lost device could not be reopened; cleared by the next successful open. */
    bool hasFailed() const { return m_failed.load(std::memory_order_acquire); }
    /** The rate of the written audio (the file's rate). */
    uint32_t sampleRate() const { return m_sampleRate.load(std::memory_order_acquire); }
    /** The rate the device stream runs at; differs from sampleRate() while the engine resamples. */
    uint32_t deviceRate() const { return m_deviceRate.load(std::memory_order_acquire); }
    int channels() const { return m_channels.load(std::memory_order_acquire); }
    /** True while the stream runs in direct (bit-perfect) mode. */
    bool isDirect() const { return m_direct.load(std::memory_order_acquire); }
    SampleEncoding encoding() const { return m_encoding.load(std::memory_order_acquire); }
    /**
     * Samples written since creation that differ from what the engine
     * produced before quantisation, i.e. were processed (gain, EQ, ...) and
     * dithered. Fades on start/pause are not counted.
     */
    uint64_t inexactSamples() const { return m_inexact.load(std::memory_order_relaxed); }

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
        m_resetResampler.store(true, std::memory_order_release);
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
        uint8_t* out = static_cast<uint8_t*>(audioData);
        const size_t ch = static_cast<size_t>(m_channels.load(std::memory_order_relaxed));
        const SampleEncoding enc = m_encoding.load(std::memory_order_relaxed);
        const size_t frameBytes = ch * bytesPerSample(enc);
        const size_t total = static_cast<size_t>(std::max<int32_t>(numFrames, 0));
        const bool run = m_runRequested.load(std::memory_order_acquire);
        if (m_resampler.active() && m_resetResampler.exchange(false, std::memory_order_acq_rel)) m_resampler.reset();

        if ((!run && m_fade <= 0.0) || !m_ring) {
            std::memset(out, 0, total * frameBytes);
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
            const size_t got = m_resampler.active() ? m_resampler.process(m_scratch.data(), want, [&](double* dst, size_t n) {
                const size_t read = m_ring->read(dst, n * ch) / ch;
                if (read > 0) m_consumedFrames.fetch_add(read, std::memory_order_acq_rel);
                return read;
            }).produced : readRing(want, ch);

            bool fading = false;
            for (size_t f = 0; f < got; ++f) {
                m_fade = run ? std::min(1.0, m_fade + m_fadeInStep) : std::max(0.0, m_fade - fadeOutStep);
                fading |= m_fade < 1.0;
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
                    if (g != 1.0) s[c] *= g;
                    peak = std::max(peak, std::fabs(s[c]));
                }
                m_spectrum[specPos & (kSpectrumSize - 1)].store(
                    static_cast<float>(mono * m_fade / static_cast<double>(ch)), std::memory_order_relaxed);
                ++specPos;
                // Peak limiter: instant attack, smooth release. Inactive below the ceiling.
                if (peak * m_limiterGain > m_ceiling) m_limiterGain = m_ceiling / peak;
                else m_limiterGain += (1.0 - m_limiterGain) * m_limiterRelease;
                if (m_limiterGain != 1.0) {
                    // The release approaches 1 asymptotically: snap once inaudible.
                    if (m_limiterGain > 1.0 - 1e-9) m_limiterGain = 1.0;
                    for (size_t c = 0; c < ch; ++c) s[c] *= m_limiterGain;
                }
            }
            if (got > 0) {
                std::copy(m_scratch.data() + (got - 1) * ch, m_scratch.data() + got * ch, m_lastOut.begin());
                const size_t inexact = m_encoder.encode(m_scratch.data(), got * ch, out + frame * frameBytes, enc);
                if (!fading && inexact > 0) m_inexact.fetch_add(inexact, std::memory_order_relaxed);
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
                    const double k = static_cast<double>(ramp - f - 1) / static_cast<double>(ramp);
                    for (size_t c = 0; c < ch; ++c) m_scratch[f * ch + c] = m_lastOut[c] * k;
                }
                m_encoder.encode(m_scratch.data(), ramp * ch, out + frame * frameBytes, enc);
                frame += ramp;
                std::fill(m_lastOut.begin(), m_lastOut.end(), 0.0);
                break;
            }
        }
        if (frame < total) std::memset(out + frame * frameBytes, 0, (total - frame) * frameBytes);
        m_spectrumPos.store(specPos, std::memory_order_release);
        return oboe::DataCallbackResult::Continue;
    }

private:
    static constexpr size_t kChunkFrames = 512;

    size_t readRing(size_t frames, size_t ch) {
        const size_t got = m_ring->read(m_scratch.data(), frames * ch) / ch;
        if (got > 0) m_consumedFrames.fetch_add(got, std::memory_order_acq_rel);
        return got;
    }
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

    static oboe::AudioFormat oboeFormat(SampleEncoding e) {
        switch (e) {
            case SampleEncoding::I16: return oboe::AudioFormat::I16;
            case SampleEncoding::I24: return oboe::AudioFormat::I24;
            case SampleEncoding::I32: return oboe::AudioFormat::I32;
            default: return oboe::AudioFormat::Float;
        }
    }

    /** Opens one stream; direct = exact rate and format with every conversion disabled. */
    /** rate 0: the device's own (mixer) rate, whatever it is. */
    std::shared_ptr<oboe::AudioStream> openStream(int channels, bool direct, SampleEncoding encoding, uint32_t rate) {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::None)
            ->setSharingMode(oboe::SharingMode::Shared)
            ->setUsage(oboe::Usage::Media)
            ->setContentType(oboe::ContentType::Music)
            ->setFormat(oboeFormat(encoding))
            ->setFormatConversionAllowed(!direct)
            ->setChannelCount(channels)
            ->setChannelConversionAllowed(!direct)
            ->setSampleRateConversionQuality(direct ? oboe::SampleRateConversionQuality::None
                                                    : oboe::SampleRateConversionQuality::High)
            ->setDataCallback(this)
            ->setErrorCallback(std::make_shared<ErrorCallback>(m_signal));
        if (rate > 0) builder.setSampleRate(static_cast<int32_t>(rate));

        std::shared_ptr<oboe::AudioStream> stream;
        const oboe::Result result = builder.openStream(stream);
        if (result != oboe::Result::OK || !stream) {
            ENGINE_LOGE("Oboe openStream(%u Hz, %d ch, direct %d) failed: %s",
                        rate, channels, direct ? 1 : 0, oboe::convertToText(result));
            return nullptr;
        }
        if ((rate > 0 && stream->getSampleRate() != static_cast<int32_t>(rate)) || stream->getSampleRate() <= 0 ||
            stream->getChannelCount() != channels ||
            stream->getFormat() != oboeFormat(encoding)) {
            ENGINE_LOGE("Oboe returned an unexpected format (%d Hz, %d ch)",
                        stream->getSampleRate(), stream->getChannelCount());
            stream->close();
            return nullptr;
        }
        return stream;
    }

    bool openLocked(int channels) {
        closeStreamLocked();
        // The app sets the device's mixer attributes in the resolver, before the open.
        const int requested = m_resolver ? m_resolver(sampleRate(), channels) : -1;
        bool direct = requested >= static_cast<int>(SampleEncoding::Float) &&
                      requested <= static_cast<int>(SampleEncoding::I32);
        SampleEncoding encoding = direct ? static_cast<SampleEncoding>(requested) : SampleEncoding::Float;
        std::shared_ptr<oboe::AudioStream> stream = direct ? openStream(channels, true, encoding, sampleRate()) : nullptr;
        if (!stream && direct) {
            ENGINE_LOGI("Direct output refused at %u Hz; using the shared mixer", sampleRate());
            direct = false;
            encoding = SampleEncoding::Float;
        }
        bool resample = false;
        if (!direct) {
            // At the mixer's own rate, converting here if the file's rate differs.
            stream = openStream(channels, false, encoding, 0);
            if (stream && static_cast<uint32_t>(stream->getSampleRate()) != sampleRate()) {
                resample = m_resampler.configure(sampleRate(), static_cast<uint32_t>(stream->getSampleRate()),
                                                 static_cast<size_t>(channels), kChunkFrames);
                if (!resample) {
                    // An impractical ratio: let Oboe convert, as before.
                    stream->close();
                    stream = openStream(channels, false, encoding, sampleRate());
                }
            }
            if (!stream) stream = openStream(channels, false, encoding, sampleRate());
        }
        if (!stream) return false;
        if (!resample) m_resampler = dsp::PolyphaseResampler();
        m_resetResampler.store(false, std::memory_order_release);
        const uint32_t deviceRate = static_cast<uint32_t>(stream->getSampleRate());
        m_deviceRate.store(deviceRate, std::memory_order_release);
        m_direct.store(direct, std::memory_order_release);
        m_encoding.store(encoding, std::memory_order_release);
        m_ceiling = direct ? kDirectLimiterCeiling : kLimiterCeiling;
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
        m_lastOut.assign(static_cast<size_t>(channels), 0.0);
        const double sr = static_cast<double>(deviceRate);
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
        ENGINE_LOGI("Oboe stream opened: %u Hz (file %u Hz%s), %d ch, %d-bit%s", deviceRate, sampleRate(),
                    resample ? ", engine SRC" : "", channels, bitsPerSample(encoding), direct ? ", direct" : "");
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
        m_direct.store(false, std::memory_order_release);
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
    DirectResolver m_resolver;
    // Written under m_streamMutex while no stream runs; read from any thread.
    std::atomic<uint32_t> m_sampleRate{48000};
    std::atomic<uint32_t> m_deviceRate{48000};
    std::atomic<int> m_channels{0};
    size_t m_capacityFrames = 0;

    // Shared with the callback. Changed only while no stream is open.
    std::unique_ptr<core::RingBuffer<double>> m_ring;
    std::vector<double> m_scratch;
    std::vector<double> m_lastOut;
    PcmEncoder m_encoder;
    dsp::PolyphaseResampler m_resampler;    // active only while the device rate differs
    std::atomic<bool> m_resetResampler{false};
    double m_ceiling = kLimiterCeiling;
    double m_gainStep = 0.001;
    double m_limiterRelease = 0.0001;
    double m_fadeInStep = 0.001;

    // Callback-private state.
    double m_fade = 0.0;
    double m_gain = 1.0;
    double m_limiterGain = 1.0;

    std::atomic<bool> m_failed{false};
    std::atomic<bool> m_direct{false};
    std::atomic<SampleEncoding> m_encoding{SampleEncoding::Float};
    std::atomic<uint64_t> m_inexact{0};
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
