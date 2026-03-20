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

#include "AudioBuffer.h"
#include "RingBuffer.h"
#include "../dsp/GainProcessor.h"
#include "../dsp/BiquadFilter.h"
#include "../dsp/Fft.h"
#include "../decoders/IAudioDecoder.h"
#include "../hw/OboeAudioEndpoint.h"

namespace audio_engine {
namespace core {

class AudioPlayer {
public:
    static constexpr size_t SPECTRUM_N     = 2048;
    static constexpr int    SPECTRUM_BANDS = 32;

    AudioPlayer()
        : m_isPlaying(false), m_stopThread(false),
          m_seekRequest(-1), m_speed(1.0), m_userVolume(1.0),
          m_gaplessAdvanced(false),
          m_specWritePos(0),
          m_ringBuffer(std::make_unique<RingBuffer<double>>(65536))
    {
        m_gainProcessor = std::make_unique<dsp::GainProcessor>();
        m_eqProcessor   = std::make_unique<dsp::BiquadFilter>();
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
        m_stopThread.store(false, std::memory_order_relaxed);
        m_isPlaying.store(true,  std::memory_order_release);
        if (m_decodeThread.joinable()) m_decodeThread.join();
        m_decodeThread = std::thread([this]() { decodeLoop(); });
    }

    void pause() { pauseDecodeThread(); }

    // ── Seeking & metadata ───────────────────────────────────────────────────

    void seekToMs(double ms) {
        std::lock_guard<std::mutex> lk(m_decoderMutex);
        if (!m_decoder) return;
        const uint32_t sr = m_decoder->getSampleRate();
        if (sr == 0) return;
        uint64_t targetFrame = static_cast<uint64_t>((ms / 1000.0) * sr);
        const uint64_t total = m_decoder->getTotalFrames();
        if (total > 0 && targetFrame >= total) targetFrame = total > 1 ? total - 2 : 0;
        m_seekRequest.store(static_cast<int64_t>(targetFrame), std::memory_order_release);
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
        std::lock_guard<std::mutex> lk(m_decoderMutex);
        if (!m_decoder) return 0.0;
        const int64_t  pending = m_seekRequest.load(std::memory_order_acquire);
        const uint32_t sr = m_decoder->getSampleRate();
        if (sr == 0) return 0.0;
        if (pending >= 0) return (static_cast<double>(pending) / sr) * 1000.0;
        return (static_cast<double>(m_decoder->getCurrentFrame()) / sr) * 1000.0;
    }

    // ── DSP controls ─────────────────────────────────────────────────────────

    void setVolume(double v) {
        m_userVolume.store(v, std::memory_order_relaxed);
        m_gainProcessor->setGainLinear(v);
    }

    void setSpeed(double speed) {
        if (speed < 0.25) speed = 0.25;
        if (speed > 4.0)  speed = 4.0;
        m_speed.store(speed, std::memory_order_relaxed);
    }

    void setReplayGainDb(float db) { m_gainProcessor->setReplayGainDb(db); }
    float getReplayGainDb()  const { return m_gainProcessor->getReplayGainDb(); }

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

    // Returns true (once) if a gapless track advance just occurred.
    bool pollGaplessAdvanced() {
        return m_gaplessAdvanced.exchange(false, std::memory_order_acq_rel);
    }

private:
    void pauseDecodeThread() {
        m_stopThread.store(true,  std::memory_order_release);
        m_isPlaying.store(false, std::memory_order_release);
        if (m_decodeThread.joinable()) m_decodeThread.join();
    }

    void shutdownInternal() {
        pauseDecodeThread();
        m_endpoint->terminate();
    }

    // Write mono mix of a frame block into the spectrum circular buffer.
    void updateSpectrum(const double* interleaved, size_t frames, size_t channels) {
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

        size_t   channels;
        uint32_t decoderSR;
        {
            std::lock_guard<std::mutex> lk(m_decoderMutex);
            channels  = m_decoder->getNumChannels();
            decoderSR = m_decoder->getSampleRate();
        }

        AudioBuffer srcBuffer(channels, CHUNK_FRAMES * 4, decoderSR);
        AudioBuffer outBuffer(channels, CHUNK_FRAMES,     decoderSR);
        std::vector<double> interleaved(CHUNK_FRAMES * channels);

        while (!m_stopThread.load(std::memory_order_acquire)) {

            // Handle seek requests
            int64_t seekFrame = m_seekRequest.load(std::memory_order_acquire);
            if (seekFrame >= 0) {
                std::lock_guard<std::mutex> lk(m_decoderMutex);
                m_decoder->seekToFrame(static_cast<uint64_t>(seekFrame));
                m_ringBuffer->clear();
                m_seekRequest.store(-1, std::memory_order_release);
            }

            // Back-pressure
            if (m_ringBuffer->getAvailableWrite() < CHUNK_FRAMES * channels) {
                std::this_thread::sleep_for(std::chrono::milliseconds(2));
                continue;
            }

            const double speed       = m_speed.load(std::memory_order_relaxed);
            const bool   normalSpeed = (speed > 0.99 && speed < 1.01);

            if (normalSpeed) {
                size_t framesRead;
                {
                    std::lock_guard<std::mutex> lk(m_decoderMutex);
                    framesRead = m_decoder->readFrames(outBuffer, CHUNK_FRAMES);
                }
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
                                m_gainProcessor->setReplayGainDb(m_nextReplayGainDb);
                                m_gainProcessor->setGainLinear(m_userVolume.load());
                                m_gaplessAdvanced.store(true, std::memory_order_release);
                                switched = true;
                            } else {
                                m_nextDecoder.reset(); // format mismatch
                            }
                        }
                    }
                    if (switched) continue;
                    // ────────────────────────────────────────────────────────────
                    m_isPlaying.store(false, std::memory_order_release);
                    break;
                }
                m_eqProcessor->processBlock(outBuffer);
                m_gainProcessor->processBlock(outBuffer);
                for (size_t f = 0; f < framesRead; ++f)
                    for (size_t ch = 0; ch < channels; ++ch)
                        interleaved[f * channels + ch] = outBuffer.getReadPointer(ch)[f];
                m_ringBuffer->write(interleaved.data(), framesRead * channels);
                updateSpectrum(interleaved.data(), framesRead, channels);

            } else {
                size_t srcFramesToRead = static_cast<size_t>(CHUNK_FRAMES * speed + 0.5);
                if (srcFramesToRead < 1)               srcFramesToRead = 1;
                if (srcFramesToRead > CHUNK_FRAMES * 4) srcFramesToRead = CHUNK_FRAMES * 4;
                size_t framesRead;
                {
                    std::lock_guard<std::mutex> lk(m_decoderMutex);
                    framesRead = m_decoder->readFrames(srcBuffer, srcFramesToRead);
                }
                if (framesRead == 0) {
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
                                m_gainProcessor->setReplayGainDb(m_nextReplayGainDb);
                                m_gainProcessor->setGainLinear(m_userVolume.load());
                                m_gaplessAdvanced.store(true, std::memory_order_release);
                                switched = true;
                            } else {
                                m_nextDecoder.reset();
                            }
                        }
                    }
                    if (switched) continue;
                    m_isPlaying.store(false, std::memory_order_release);
                    break;
                }
                const size_t outFrames = CHUNK_FRAMES;
                const double ratio = (framesRead > 1)
                    ? static_cast<double>(framesRead - 1) / static_cast<double>(outFrames - 1)
                    : 0.0;
                for (size_t f = 0; f < outFrames; ++f) {
                    const double srcPos = f * ratio;
                    const size_t i0  = static_cast<size_t>(srcPos);
                    const size_t i1  = (i0 + 1 < framesRead) ? i0 + 1 : i0;
                    const double frac = srcPos - static_cast<double>(i0);
                    for (size_t ch = 0; ch < channels; ++ch) {
                        const double* src = srcBuffer.getReadPointer(ch);
                        interleaved[f * channels + ch] =
                            src[i0] * (1.0 - frac) + src[i1] * frac;
                    }
                }
                m_gainProcessor->processRawInterleaved(interleaved.data(), outFrames, channels);
                m_ringBuffer->write(interleaved.data(), outFrames * channels);
                updateSpectrum(interleaved.data(), outFrames, channels);
            }
        }
    }

    // ── Members ──────────────────────────────────────────────────────────────

    std::atomic<bool>    m_isPlaying;
    std::atomic<bool>    m_stopThread;
    std::atomic<int64_t> m_seekRequest;
    std::atomic<double>  m_speed;
    std::atomic<double>  m_userVolume;
    std::atomic<bool>    m_gaplessAdvanced;
    std::thread          m_decodeThread;

    mutable std::mutex                       m_decoderMutex;
    std::unique_ptr<decoders::IAudioDecoder> m_decoder;

    mutable std::mutex                       m_nextDecoderMutex;
    std::unique_ptr<decoders::IAudioDecoder> m_nextDecoder;
    float                                    m_nextReplayGainDb{0.0f};

    std::unique_ptr<RingBuffer<double>>      m_ringBuffer;
    std::unique_ptr<dsp::GainProcessor>      m_gainProcessor;
    std::unique_ptr<dsp::BiquadFilter>       m_eqProcessor;
    std::unique_ptr<hw::OboeAudioEndpoint>   m_endpoint;

    // Spectrum
    std::array<float, SPECTRUM_N>    m_specBuf;
    std::atomic<size_t>              m_specWritePos;
    mutable float                    m_specSmooth[SPECTRUM_BANDS];
    mutable std::mutex               m_specMutex;
};

} // namespace core
} // namespace audio_engine
