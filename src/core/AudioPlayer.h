#pragma once

#include <thread>
#include <atomic>
#include <memory>
#include <vector>
#include <array>
#include <chrono>
#include <mutex>
#include <cmath>
#include <algorithm>
#include <functional>
#include <cstdlib>

#include "AudioBuffer.h"
#include "RingBuffer.h"
#include "../dsp/GainProcessor.h"
#include "../dsp/BiquadFilter.h"
#include "../dsp/Fft.h"
#include "../dsp/GraphicEqProcessor.h"
#include "../dsp/TimeStretchProcessor.h"
#include "../decoders/IAudioDecoder.h"
#include "../decoders/DsdDecoder.h"
#include "../hw/OboeAudioEndpoint.h"

namespace audio_engine {
namespace core {

class AudioPlayer {
public:
    static constexpr size_t SPECTRUM_N     = 2048;
    static constexpr int    SPECTRUM_BANDS = 32;
    static constexpr size_t EDGE_RAMP_FRAMES = 384;

    AudioPlayer()
        : m_isPlaying(false), m_stopThread(false),
          m_seekRequest(-1), m_speed(1.0), m_userVolume(1.0),
          m_gaplessAdvanced(false),
          m_specWritePos(0),
            m_ringBuffer(std::make_unique<RingBuffer<double>>(262144))
    {
        m_gainProcessor = std::make_unique<dsp::GainProcessor>();
        m_eqProcessor   = std::make_unique<dsp::GraphicEqProcessor>();
        m_timeStretchProcessor = std::make_unique<dsp::TimeStretchProcessor>();
        m_endpoint      = std::make_unique<hw::OboeAudioEndpoint>(m_ringBuffer.get());
        m_specBuf.fill(0.0f);
        std::fill(std::begin(m_specSmooth), std::end(m_specSmooth), 0.0f);
    }

    ~AudioPlayer() { shutdownInternal(); }

    // ── Decoder management ──────────────────────────────────────────────────

    void setDecoder(std::unique_ptr<decoders::IAudioDecoder> decoder) {
        pauseDecodeThread();
        {
            std::lock_guard<std::mutex> lk(m_decoderMutex);
            m_decoder = std::move(decoder);
        }
        // Clear any pre-loaded next decoder
        {
            std::lock_guard<std::mutex> lk(m_nextDecoderMutex);
            m_nextDecoder.reset();
        }
        m_seekRequest.store(-1, std::memory_order_relaxed);
        const uint32_t sr = m_decoder->getSampleRate();
        const size_t   ch = m_decoder->getNumChannels();
        m_gainProcessor->prepare(sr, 1024);
        m_eqProcessor->prepare(sr, 1024);
        applyPendingEqConfiguration();
        m_timeStretchProcessor->prepare(sr, ch);
        m_timeStretchProcessor->setSpeed(m_speed.load(std::memory_order_relaxed));
        m_gainProcessor->forceSilence();
        if (!m_endpoint->isInitialized() ||
            m_endpoint->getStreamSampleRate()   != static_cast<int32_t>(sr) ||
            m_endpoint->getStreamChannelCount() != static_cast<int32_t>(ch))
        {
            m_endpoint->terminate();
            if (!m_endpoint->initialize(sr, ch)) {
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
                m_endpoint->initialize(sr, ch);
            }
        }
        m_ringBuffer->clear();
        m_timeStretchProcessor->reset();
        resetPositionState(0.0);
        m_startRampFramesRemaining = EDGE_RAMP_FRAMES;
        m_gaplessBlendPending = false;
        m_tailValidFrames = 0;
        m_tailInterleaved.assign(EDGE_RAMP_FRAMES * ch, 0.0);
    }

    // Pre-load next track for gapless transition.
    // Called while current track is still playing (~8 s before end).
    void setNextDecoder(std::unique_ptr<decoders::IAudioDecoder> next, float rgDb) {
        std::lock_guard<std::mutex> lk(m_nextDecoderMutex);
        m_nextDecoder    = std::move(next);
        m_nextReplayGainDb = rgDb;
    }

    void clearNextDecoder() {
        std::lock_guard<std::mutex> lk(m_nextDecoderMutex);
        m_nextDecoder.reset();
    }

    // ── Transport ────────────────────────────────────────────────────────────

    void play() {
        if (!m_decoder) return;
        if (m_isPlaying.load()) return;
        if (!m_endpoint->isRunning()) {
            const uint32_t sr = m_decoder->getSampleRate();
            const size_t   ch = m_decoder->getNumChannels();
            m_endpoint->terminate();
            m_endpoint->initialize(sr, ch);
        }
        m_ringBuffer->clear();
        m_timeStretchProcessor->clearEndOfInput();
        m_stopThread.store(false, std::memory_order_relaxed);
        startPositionClock();
        m_isPlaying.store(true,  std::memory_order_release);
        if (m_decodeThread.joinable()) m_decodeThread.join();
        m_decodeThread = std::thread([this]() { decodeLoop(); });
    }

    void pause() { pauseDecodeThread(); }

    // ── Seeking & metadata ───────────────────────────────────────────────────

    void seekToMs(double ms) {
        uint32_t sr = 0;
        uint64_t total = 0;
        {
            std::lock_guard<std::mutex> lk(m_decoderMutex);
            if (!m_decoder) return;
            sr = m_decoder->getSampleRate();
            if (sr == 0) return;
            total = m_decoder->getTotalFrames();
        }

        uint64_t targetFrame = static_cast<uint64_t>((ms / 1000.0) * sr);
        if (total > 0 && targetFrame >= total) targetFrame = total > 1 ? total - 2 : 0;
        const double targetMs = (static_cast<double>(targetFrame) / sr) * 1000.0;
        m_seekRequest.store(static_cast<int64_t>(targetFrame), std::memory_order_release);
        resetPositionState(targetMs);
    }

    double getDurationMs() const {
        std::lock_guard<std::mutex> lk(m_decoderMutex);
        if (!m_decoder) return 0.0;
        const uint64_t frames = m_decoder->getTotalFrames();
        const uint32_t sr     = m_decoder->getSampleRate();
        if (sr == 0) return 0.0;
        return (static_cast<double>(frames) / sr) * 1000.0;
    }

    double getPositionMs() const {
        const int64_t  pending = m_seekRequest.load(std::memory_order_acquire);
        uint32_t sr = 0;
        uint64_t totalFrames = 0;
        {
            std::lock_guard<std::mutex> lk(m_decoderMutex);
            if (!m_decoder) return 0.0;
            sr = m_decoder->getSampleRate();
            totalFrames = m_decoder->getTotalFrames();
        }
        if (sr == 0) return 0.0;
        if (pending >= 0) return (static_cast<double>(pending) / sr) * 1000.0;

        const double durationMs = totalFrames > 0
            ? (static_cast<double>(totalFrames) / sr) * 1000.0
            : 0.0;
        const double positionMs = getTrackedPositionMs();
        return durationMs > 0.0 ? std::clamp(positionMs, 0.0, durationMs) : std::max(0.0, positionMs);
    }

    // ── DSP controls ─────────────────────────────────────────────────────────

    void setVolume(double v) {
        m_userVolume.store(v, std::memory_order_relaxed);
        m_gainProcessor->setGainLinear(v);
    }

    void forceSilence() {
        m_gainProcessor->forceSilence();
    }

    void clearBufferedAudio() {
        m_ringBuffer->clear();
    }

    void setSpeed(double speed) {
        if (speed < 0.75) speed = 0.75;
        if (speed > 2.0)  speed = 2.0;
        snapshotPositionClock();
        const double previous = m_speed.load(std::memory_order_relaxed);
        const bool wasNormal = isNormalPlaybackSpeed(previous);
        const bool nowNormal = isNormalPlaybackSpeed(speed);
        m_speed.store(speed, std::memory_order_relaxed);
        if (wasNormal != nowNormal) {
            m_speedPathChangePending.store(true, std::memory_order_release);
        }
    }

    void setSpeedMode(int mode) {
        const int normalizedMode = (mode == 1) ? 1 : 0;
        const int previousMode = m_speedMode.load(std::memory_order_relaxed);
        m_speedMode.store(normalizedMode, std::memory_order_relaxed);
        if (previousMode != normalizedMode) {
            m_speedModeChangePending.store(true, std::memory_order_release);
        }
    }

    void setReplayGainDb(float db) { m_gainProcessor->setReplayGainDb(db); }
    float getReplayGainDb()  const { return m_gainProcessor->getReplayGainDb(); }

    void setEqEnabled(bool enabled) {
        {
            std::lock_guard<std::mutex> lk(m_eqConfigMutex);
            m_eqEnabled = enabled;
        }
        m_eqConfigDirty.store(true, std::memory_order_release);
    }

    void setEqBandGain(size_t bandIndex, double gainDb) {
        if (bandIndex >= m_eqBandGainsDb.size()) return;
        {
            std::lock_guard<std::mutex> lk(m_eqConfigMutex);
            m_eqBandGainsDb[bandIndex] = gainDb;
        }
        m_eqConfigDirty.store(true, std::memory_order_release);
    }

    void resetEqBands() {
        {
            std::lock_guard<std::mutex> lk(m_eqConfigMutex);
            m_eqEnabled = false;
            m_eqBandGainsDb.fill(0.0);
        }
        m_eqConfigDirty.store(true, std::memory_order_release);
    }

    // ── Spectrum ─────────────────────────────────────────────────────────────

    void getSpectrumBands(float* bands, int bandCount, uint32_t sampleRate) const {
        // Decay to zero when not playing
        if (!m_isPlaying.load(std::memory_order_relaxed) || sampleRate == 0) {
            std::lock_guard<std::mutex> lk(m_specMutex);
            for (int i = 0; i < bandCount; ++i) {
                m_specSmooth[i] *= 0.82f;
                bands[i] = m_specSmooth[i];
            }
            return;
        }
        // Copy circular buffer (oldest -> newest)
        std::array<double, SPECTRUM_N> re{}, im{};
        const size_t wp = m_specWritePos.load(std::memory_order_acquire);
        for (size_t i = 0; i < SPECTRUM_N; ++i) {
            const size_t idx = (wp + i) % SPECTRUM_N;
            const double w = 0.5 * (1.0 - std::cos(2.0 * M_PI * i / (SPECTRUM_N - 1)));
            re[i] = static_cast<double>(m_specBuf[idx]) * w;
        }
        dsp::fft(re.data(), im.data(), static_cast<int>(SPECTRUM_N));

        const double fMin = 20.0, fMax = 20000.0;
        const double logRange = std::log10(fMax / fMin);

        std::lock_guard<std::mutex> lk(m_specMutex);
        for (int b = 0; b < bandCount; ++b) {
            const double freqLo = fMin * std::pow(10.0, logRange *  b      / bandCount);
            const double freqHi = fMin * std::pow(10.0, logRange * (b + 1) / bandCount);
            const int binLo = std::max(1, static_cast<int>(freqLo * SPECTRUM_N / sampleRate));
            const int binHi = std::min(static_cast<int>(SPECTRUM_N / 2),
                                       static_cast<int>(freqHi * SPECTRUM_N / sampleRate) + 1);
            double mag = 0.0;
            for (int k = binLo; k < binHi; ++k) {
                const double m = std::sqrt(re[k]*re[k] + im[k]*im[k]) / (SPECTRUM_N / 2);
                if (m > mag) mag = m;
            }
            const double dB = 20.0 * std::log10(mag + 1e-7);
            float norm = static_cast<float>((dB + 60.0) / 60.0);
            norm = std::max(0.0f, std::min(1.0f, norm));
            if (norm > m_specSmooth[b])
                m_specSmooth[b] = m_specSmooth[b] * 0.25f + norm * 0.75f; // fast attack
            else
                m_specSmooth[b] *= 0.87f;                                  // slow decay
            bands[b] = m_specSmooth[b];
        }
    }

    // ── State queries ────────────────────────────────────────────────────────

    bool     isPlaying()       const { return m_isPlaying.load(std::memory_order_relaxed); }
    uint32_t getSampleRate()   const {
        std::lock_guard<std::mutex> lk(m_decoderMutex);
        return m_decoder ? m_decoder->getSampleRate() : 0;
    }
    uint32_t getBitsPerSample() const {
        std::lock_guard<std::mutex> lk(m_decoderMutex);
        return m_decoder ? m_decoder->getBitsPerSample() : 0;
    }
    uint32_t getDsdNativeRate() const {
        std::lock_guard<std::mutex> lk(m_decoderMutex);
        if (!m_decoder) return 0;
        auto* dsd = dynamic_cast<const decoders::DsdDecoder*>(m_decoder.get());
        return dsd ? dsd->getDsdNativeRate() : 0;
    }

    // Returns true (once) if a gapless track advance just occurred.
    bool pollGaplessAdvanced() {
        return m_gaplessAdvanced.exchange(false, std::memory_order_acq_rel);
    }

private:
    static bool isNormalPlaybackSpeed(double speed) {
        return speed > 0.99 && speed < 1.01;
    }

    double getTrackedPositionMs() const {
        std::lock_guard<std::mutex> lk(m_positionMutex);
        double positionMs = m_positionBaseMs;
        if (m_positionClockRunning) {
            const auto now = std::chrono::steady_clock::now();
            const auto elapsedMs = std::chrono::duration<double, std::milli>(now - m_positionClockStart).count();
            positionMs += elapsedMs * m_speed.load(std::memory_order_relaxed);
        }
        return positionMs;
    }

    void snapshotPositionClock() {
        std::lock_guard<std::mutex> lk(m_positionMutex);
        if (!m_positionClockRunning) return;
        const auto now = std::chrono::steady_clock::now();
        const auto elapsedMs = std::chrono::duration<double, std::milli>(now - m_positionClockStart).count();
        m_positionBaseMs += elapsedMs * m_speed.load(std::memory_order_relaxed);
        m_positionClockStart = now;
    }

    void startPositionClock() {
        std::lock_guard<std::mutex> lk(m_positionMutex);
        m_positionClockStart = std::chrono::steady_clock::now();
        m_positionClockRunning = true;
    }

    void stopPositionClock() {
        snapshotPositionClock();
        std::lock_guard<std::mutex> lk(m_positionMutex);
        m_positionClockRunning = false;
    }

    void resetPositionState(double positionMs) {
        std::lock_guard<std::mutex> lk(m_positionMutex);
        m_positionBaseMs = std::max(0.0, positionMs);
        m_positionClockStart = std::chrono::steady_clock::now();
        m_positionClockRunning = m_isPlaying.load(std::memory_order_relaxed);
    }

    void applyPendingEqConfiguration() {
        if (!m_eqConfigDirty.exchange(false, std::memory_order_acq_rel)) return;

        std::array<double, dsp::GraphicEqProcessor::BandCount> gains{};
        bool enabled = false;
        {
            std::lock_guard<std::mutex> lk(m_eqConfigMutex);
            gains = m_eqBandGainsDb;
            enabled = m_eqEnabled;
        }

        m_eqProcessor->setEnabled(enabled);
        for (size_t index = 0; index < gains.size(); ++index) {
            m_eqProcessor->setBandGain(index, gains[index]);
        }
    }

    void clearBufferTail(AudioBuffer& buffer, size_t validFrames) {
        const size_t totalFrames = buffer.getNumFrames();
        if (validFrames >= totalFrames) return;
        const size_t channels = buffer.getNumChannels();
        for (size_t ch = 0; ch < channels; ++ch) {
            double* data = buffer.getWritePointer(ch);
            std::fill(data + validFrames, data + totalFrames, 0.0);
        }
    }

    void applyStartRamp(double* interleaved, size_t frames, size_t channels) {
        if (m_startRampFramesRemaining == 0 || !interleaved) return;
        const size_t rampFrames = std::min(frames, m_startRampFramesRemaining);
        const size_t offset = EDGE_RAMP_FRAMES - m_startRampFramesRemaining;
        for (size_t f = 0; f < rampFrames; ++f) {
            const double gain = static_cast<double>(offset + f + 1)
                              / static_cast<double>(EDGE_RAMP_FRAMES);
            for (size_t ch = 0; ch < channels; ++ch) {
                interleaved[f * channels + ch] *= gain;
            }
        }
        m_startRampFramesRemaining -= rampFrames;
    }

    void applyTailRamp(double* interleaved, size_t frames, size_t channels) {
        if (!interleaved || frames == 0) return;
        const size_t rampFrames = std::min(frames, EDGE_RAMP_FRAMES);
        for (size_t i = 0; i < rampFrames; ++i) {
            const size_t frameIndex = frames - rampFrames + i;
            const double gain = static_cast<double>(rampFrames - i - 1)
                              / static_cast<double>(rampFrames);
            for (size_t ch = 0; ch < channels; ++ch) {
                interleaved[frameIndex * channels + ch] *= gain;
            }
        }
    }

    void applyGaplessBlend(double* interleaved, size_t frames, size_t channels) {
        if (!m_gaplessBlendPending || !interleaved || m_tailValidFrames == 0) return;
        const size_t blendFrames = std::min({frames, m_tailValidFrames, EDGE_RAMP_FRAMES});
        const size_t tailOffset = (m_tailValidFrames - blendFrames) * channels;
        for (size_t f = 0; f < blendFrames; ++f) {
            const double wet = static_cast<double>(f + 1) / static_cast<double>(blendFrames + 1);
            const double dry = 1.0 - wet;
            for (size_t ch = 0; ch < channels; ++ch) {
                const double prev = m_tailInterleaved[tailOffset + f * channels + ch];
                interleaved[f * channels + ch] = prev * dry + interleaved[f * channels + ch] * wet;
            }
        }
        m_gaplessBlendPending = false;
    }

    void updateTailHistory(const double* interleaved, size_t frames, size_t channels) {
        if (!interleaved || channels == 0) return;
        if (m_tailInterleaved.size() != EDGE_RAMP_FRAMES * channels) {
            m_tailInterleaved.assign(EDGE_RAMP_FRAMES * channels, 0.0);
            m_tailValidFrames = 0;
        }

        if (frames >= EDGE_RAMP_FRAMES) {
            const size_t startFrame = frames - EDGE_RAMP_FRAMES;
            std::copy(
                interleaved + startFrame * channels,
                interleaved + frames * channels,
                m_tailInterleaved.begin()
            );
            m_tailValidFrames = EDGE_RAMP_FRAMES;
            return;
        }

        const size_t keepFrames = std::min(m_tailValidFrames, EDGE_RAMP_FRAMES - frames);
        if (keepFrames > 0) {
            std::move(
                m_tailInterleaved.begin() + (m_tailValidFrames - keepFrames) * channels,
                m_tailInterleaved.begin() + m_tailValidFrames * channels,
                m_tailInterleaved.begin()
            );
        }
        std::copy(
            interleaved,
            interleaved + frames * channels,
            m_tailInterleaved.begin() + keepFrames * channels
        );
        m_tailValidFrames = keepFrames + frames;
    }

    void pauseDecodeThread() {
        stopPositionClock();
        m_stopThread.store(true,  std::memory_order_release);
        m_isPlaying.store(false, std::memory_order_release);
        if (m_decodeThread.joinable()) m_decodeThread.join();
        m_ringBuffer->clear();
        m_timeStretchProcessor->reset();
    }

    void shutdownInternal() {
        pauseDecodeThread();
        m_endpoint->terminate();
    }

    // Write mono mix of a frame block into the spectrum circular buffer.
    void updateSpectrum(const double* interleaved, size_t frames, size_t channels) {
        // Hold the spectrum mutex so getSpectrumBands() on another thread
        // never reads a partially-written circular buffer (data race / UB).
        std::lock_guard<std::mutex> lk(m_specMutex);
        for (size_t f = 0; f < frames; ++f) {
            float mono = 0.0f;
            for (size_t c = 0; c < channels; ++c)
                mono += static_cast<float>(interleaved[f * channels + c]);
            mono /= static_cast<float>(channels);
            const size_t pos = m_specWritePos.fetch_add(1, std::memory_order_relaxed) % SPECTRUM_N;
            m_specBuf[pos] = mono;
        }
    }

    void decodeLoop() {
        constexpr size_t CHUNK_FRAMES = 1024;
        constexpr size_t TIME_STRETCH_LOW_WATER_FRAMES = CHUNK_FRAMES * 4;
        constexpr size_t TIME_STRETCH_TARGET_BUFFER_FRAMES = CHUNK_FRAMES * 8;
        constexpr size_t TIME_STRETCH_BATCH_FRAMES = CHUNK_FRAMES * 6;

        size_t   channels;
        uint32_t decoderSR;
        {
            std::lock_guard<std::mutex> lk(m_decoderMutex);
            channels  = m_decoder->getNumChannels();
            decoderSR = m_decoder->getSampleRate();
        }

        AudioBuffer srcBuffer(channels, CHUNK_FRAMES * 8, decoderSR);
        AudioBuffer outBuffer(channels, CHUNK_FRAMES,     decoderSR);
        std::vector<double> interleaved(std::max(TIME_STRETCH_BATCH_FRAMES, CHUNK_FRAMES * 8) * channels);

        while (!m_stopThread.load(std::memory_order_acquire)) {
            applyPendingEqConfiguration();

            if (m_speedModeChangePending.exchange(false, std::memory_order_acq_rel)) {
                m_timeStretchProcessor->setMode(
                    m_speedMode.load(std::memory_order_relaxed) == 1
                        ? dsp::TimeStretchMode::Speech
                        : dsp::TimeStretchMode::Music
                );
                m_speedPathChangePending.store(true, std::memory_order_release);
            }

            if (m_speedPathChangePending.exchange(false, std::memory_order_acq_rel)) {
                m_timeStretchProcessor->reset();
                m_timeStretchProcessor->setSpeed(m_speed.load(std::memory_order_relaxed));
                m_timeStretchProcessor->clearEndOfInput();
                m_ringBuffer->clear();
                m_startRampFramesRemaining = EDGE_RAMP_FRAMES;
            }

            // Handle seek requests
            int64_t seekFrame = m_seekRequest.load(std::memory_order_acquire);
            if (seekFrame >= 0) {
                std::lock_guard<std::mutex> lk(m_decoderMutex);
                m_decoder->seekToFrame(static_cast<uint64_t>(seekFrame));
                m_ringBuffer->clear();
                m_timeStretchProcessor->reset();
                m_timeStretchProcessor->setSpeed(m_speed.load(std::memory_order_relaxed));
                m_timeStretchProcessor->clearEndOfInput();
                m_startRampFramesRemaining = EDGE_RAMP_FRAMES;
                m_seekRequest.store(-1, std::memory_order_release);
            }

            const double speed       = m_speed.load(std::memory_order_relaxed);
            const bool   normalSpeed = isNormalPlaybackSpeed(speed);

            if (normalSpeed) {
                if (m_ringBuffer->getAvailableWrite() < CHUNK_FRAMES * channels) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(2));
                    continue;
                }
                m_timeStretchProcessor->reset();
                size_t framesRead;
                {
                    std::lock_guard<std::mutex> lk(m_decoderMutex);
                    framesRead = m_decoder->readFrames(outBuffer, CHUNK_FRAMES);
                }
                const bool likelyEndOfTrack = framesRead > 0 && framesRead < CHUNK_FRAMES;
                if (framesRead == 0) {
                    if (m_seekRequest.load(std::memory_order_acquire) >= 0) continue;
                    // ── Gapless transition ─────────────────────────────────────
                    bool switched = false;
                    {
                        std::lock_guard<std::mutex> lk(m_nextDecoderMutex);
                        if (m_nextDecoder) {
                            const uint32_t nextSR = m_nextDecoder->getSampleRate();
                            const size_t   nextCh = m_nextDecoder->getNumChannels();
                            if (nextSR == decoderSR && nextCh == channels) {
                                {
                                    std::lock_guard<std::mutex> lk2(m_decoderMutex);
                                    m_decoder = std::move(m_nextDecoder);
                                }
                                m_gainProcessor->prepare(nextSR, 1024);
                                m_eqProcessor->prepare(nextSR, 1024);
                                applyPendingEqConfiguration();
                                m_gainProcessor->setReplayGainDb(m_nextReplayGainDb);
                                m_gainProcessor->setGainLinear(m_userVolume.load());
                                resetPositionState(0.0);
                                m_gaplessBlendPending = (m_tailValidFrames > 0);
                                m_startRampFramesRemaining = 0;
                                m_gaplessAdvanced.store(true, std::memory_order_release);
                                switched = true;
                            } else {
                                m_nextDecoder.reset(); // format mismatch
                            }
                        }
                    }
                    if (switched) continue;
                    // ────────────────────────────────────────────────────────────
                    stopPositionClock();
                    m_isPlaying.store(false, std::memory_order_release);
                    break;
                }
                clearBufferTail(outBuffer, framesRead);
                m_eqProcessor->processBlock(outBuffer);
                m_gainProcessor->processBlock(outBuffer);
                for (size_t f = 0; f < framesRead; ++f)
                    for (size_t ch = 0; ch < channels; ++ch)
                        interleaved[f * channels + ch] = outBuffer.getReadPointer(ch)[f];
                applyGaplessBlend(interleaved.data(), framesRead, channels);
                applyStartRamp(interleaved.data(), framesRead, channels);
                if (likelyEndOfTrack && !m_nextDecoder) {
                    applyTailRamp(interleaved.data(), framesRead, channels);
                }
                m_ringBuffer->write(interleaved.data(), framesRead * channels);
                updateSpectrum(interleaved.data(), framesRead, channels);
                updateTailHistory(interleaved.data(), framesRead, channels);

            } else {
                m_timeStretchProcessor->setSpeed(speed);
                const size_t bufferedFrames = m_ringBuffer->getAvailableRead() / channels;
                if (bufferedFrames >= TIME_STRETCH_TARGET_BUFFER_FRAMES) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(2));
                    continue;
                }

                const size_t requestedFrames = std::clamp(
                    TIME_STRETCH_TARGET_BUFFER_FRAMES - bufferedFrames,
                    CHUNK_FRAMES,
                    TIME_STRETCH_BATCH_FRAMES
                );
                bool decoderExhausted = false;

                auto feedTimeStretchInput = [&](size_t minFramesToRead) {
                    size_t srcFramesToRead = static_cast<size_t>(std::ceil(minFramesToRead * speed))
                        + CHUNK_FRAMES;
                    srcFramesToRead = std::clamp<size_t>(srcFramesToRead, CHUNK_FRAMES * 2, CHUNK_FRAMES * 8);

                    size_t framesRead;
                    {
                        std::lock_guard<std::mutex> lk(m_decoderMutex);
                        framesRead = m_decoder->readFrames(srcBuffer, srcFramesToRead);
                    }
                    if (framesRead == 0) {
                        decoderExhausted = true;
                        m_timeStretchProcessor->markEndOfInput();
                        return;
                    }

                    for (size_t frame = 0; frame < framesRead; ++frame) {
                        for (size_t ch = 0; ch < channels; ++ch) {
                            interleaved[frame * channels + ch] = srcBuffer.getReadPointer(ch)[frame];
                        }
                    }
                    m_timeStretchProcessor->appendInterleaved(interleaved.data(), framesRead);
                };

                while (m_timeStretchProcessor->getAvailableFrames() < TIME_STRETCH_LOW_WATER_FRAMES && !decoderExhausted) {
                    const size_t bufferedStretchFrames = m_timeStretchProcessor->getAvailableFrames();
                    const size_t lowWaterDeficit = TIME_STRETCH_LOW_WATER_FRAMES > bufferedStretchFrames
                        ? TIME_STRETCH_LOW_WATER_FRAMES - bufferedStretchFrames
                        : 0;
                    const size_t requestDeficit = requestedFrames > bufferedStretchFrames
                        ? requestedFrames - bufferedStretchFrames
                        : 0;
                    const size_t framesNeeded = std::max(
                        lowWaterDeficit,
                        requestDeficit
                    );
                    feedTimeStretchInput(framesNeeded);
                }

                size_t outFrames = m_timeStretchProcessor->renderInterleaved(interleaved.data(), requestedFrames);

                while (outFrames < requestedFrames && !decoderExhausted) {
                    feedTimeStretchInput(requestedFrames - outFrames);
                    outFrames += m_timeStretchProcessor->renderInterleaved(
                        interleaved.data() + outFrames * channels,
                        requestedFrames - outFrames
                    );
                }

                if (outFrames == 0 && decoderExhausted) {
                    if (m_seekRequest.load(std::memory_order_acquire) >= 0) continue;
                    bool switched = false;
                    {
                        std::lock_guard<std::mutex> lk(m_nextDecoderMutex);
                        if (m_nextDecoder) {
                            const uint32_t nextSR = m_nextDecoder->getSampleRate();
                            const size_t   nextCh = m_nextDecoder->getNumChannels();
                            if (nextSR == decoderSR && nextCh == channels) {
                                {
                                    std::lock_guard<std::mutex> lk2(m_decoderMutex);
                                    m_decoder = std::move(m_nextDecoder);
                                }
                                m_gainProcessor->prepare(nextSR, 1024);
                                m_eqProcessor->prepare(nextSR, 1024);
                                applyPendingEqConfiguration();
                                m_timeStretchProcessor->prepare(nextSR, nextCh);
                                m_timeStretchProcessor->setSpeed(m_speed.load(std::memory_order_relaxed));
                                m_gainProcessor->setReplayGainDb(m_nextReplayGainDb);
                                m_gainProcessor->setGainLinear(m_userVolume.load());
                                resetPositionState(0.0);
                                m_gaplessBlendPending = (m_tailValidFrames > 0);
                                m_startRampFramesRemaining = 0;
                                m_gaplessAdvanced.store(true, std::memory_order_release);
                                switched = true;
                            } else {
                                m_nextDecoder.reset();
                            }
                        }
                    }
                    if (switched) continue;
                    stopPositionClock();
                    m_isPlaying.store(false, std::memory_order_release);
                    break;
                }

                const bool likelyEndOfTrack = decoderExhausted
                    && m_timeStretchProcessor->isDrained()
                    && outFrames < requestedFrames;
                m_eqProcessor->processRawInterleaved(interleaved.data(), outFrames, channels);
                m_gainProcessor->processRawInterleaved(interleaved.data(), outFrames, channels);
                applyGaplessBlend(interleaved.data(), outFrames, channels);
                applyStartRamp(interleaved.data(), outFrames, channels);
                if (likelyEndOfTrack && !m_nextDecoder) {
                    applyTailRamp(interleaved.data(), outFrames, channels);
                }
                m_ringBuffer->write(interleaved.data(), outFrames * channels);
                updateSpectrum(interleaved.data(), outFrames, channels);
                updateTailHistory(interleaved.data(), outFrames, channels);
            }
        }
    }

    // ── Members ──────────────────────────────────────────────────────────────

    std::atomic<bool>    m_isPlaying;
    std::atomic<bool>    m_stopThread;
    std::atomic<int64_t> m_seekRequest;
    std::atomic<double>  m_speed;
    std::atomic<int>     m_speedMode{0};
    std::atomic<bool>    m_speedPathChangePending{false};
    std::atomic<bool>    m_speedModeChangePending{false};
    std::atomic<double>  m_userVolume;
    std::atomic<bool>    m_gaplessAdvanced;
    std::thread          m_decodeThread;
    mutable std::mutex   m_positionMutex;
    double               m_positionBaseMs{0.0};
    std::chrono::steady_clock::time_point m_positionClockStart{};
    bool                 m_positionClockRunning{false};
    size_t               m_startRampFramesRemaining{0};
    bool                 m_gaplessBlendPending{false};
    size_t               m_tailValidFrames{0};
    std::vector<double>  m_tailInterleaved;

    mutable std::mutex                       m_decoderMutex;
    std::unique_ptr<decoders::IAudioDecoder> m_decoder;

    mutable std::mutex                       m_nextDecoderMutex;
    std::unique_ptr<decoders::IAudioDecoder> m_nextDecoder;
    float                                    m_nextReplayGainDb{0.0f};

    mutable std::mutex                       m_eqConfigMutex;
    std::array<double, dsp::GraphicEqProcessor::BandCount> m_eqBandGainsDb{0.0, 0.0, 0.0, 0.0, 0.0};
    bool                                     m_eqEnabled{false};
    std::atomic<bool>                        m_eqConfigDirty{true};

    std::unique_ptr<RingBuffer<double>>      m_ringBuffer;
    std::unique_ptr<dsp::GainProcessor>      m_gainProcessor;
    std::unique_ptr<dsp::GraphicEqProcessor> m_eqProcessor;
    std::unique_ptr<dsp::TimeStretchProcessor> m_timeStretchProcessor;
    std::unique_ptr<hw::OboeAudioEndpoint>   m_endpoint;

    // Spectrum
    std::array<float, SPECTRUM_N>    m_specBuf;
    std::atomic<size_t>              m_specWritePos;
    mutable float                    m_specSmooth[SPECTRUM_BANDS];
    mutable std::mutex               m_specMutex;
};

} // namespace core
} // namespace audio_engine
