#pragma once

#include <thread>
#include <atomic>
#include <memory>
#include <vector>
#include <chrono>
#include <mutex>

#include "AudioBuffer.h"
#include "RingBuffer.h"
#include "../dsp/GainProcessor.h"
#include "../dsp/BiquadFilter.h"
#include "../decoders/IAudioDecoder.h"
#include "../hw/OboeAudioEndpoint.h"

namespace audio_engine {
namespace core {

/**
 * @brief High-fidelity 64-bit audio player.
 *
 * Lifecycle design
 * ----------------
 *  • The Oboe stream is created once (in setDecoder / ensureStream) and kept
 *    alive until the engine is destroyed.  It always runs and simply outputs
 *    silence when the ring buffer is empty.
 *
 *  • pause()  — stops the decode thread; the stream keeps running silently.
 *  • play()   — (re)starts the decode thread; no stream operations.
 *  • setDecoder() — swaps the file decoder; reinitialises the stream ONLY
 *                   when sample-rate or channel count changes.
 *  • ~AudioPlayer() — pauses and then fully terminates the stream.
 *
 * This means there are ZERO stream close/open cycles between tracks at the
 * same sample rate, which eliminates all timing / resource-exhaustion bugs
 * that caused "nothing plays after the first track".
 */
class AudioPlayer {
public:
    AudioPlayer()
        : m_isPlaying(false),
          m_stopThread(false),
          m_seekRequest(-1),
          m_speed(1.0),
          m_ringBuffer(std::make_unique<RingBuffer<double>>(65536))
    {
        m_gainProcessor = std::make_unique<dsp::GainProcessor>();
        m_eqProcessor   = std::make_unique<dsp::BiquadFilter>();
        m_endpoint      = std::make_unique<hw::OboeAudioEndpoint>(m_ringBuffer.get());
    }

    ~AudioPlayer() {
        shutdownInternal();
    }

    // ------------------------------------------------------------------ //
    //  Decoder management                                                 //
    // ------------------------------------------------------------------ //

    /**
     * Load a new decoder (i.e. a new audio file).
     * Stops any running decode thread.  Does NOT start playback — call play()
     * afterwards.  Reinitialises the Oboe stream only when the sample rate or
     * channel count changes.
     */
    void setDecoder(std::unique_ptr<decoders::IAudioDecoder> decoder) {
        pauseDecodeThread(); // stop thread but keep stream running

        {
            std::lock_guard<std::mutex> lk(m_decoderMutex);
            m_decoder = std::move(decoder);
        }

        m_seekRequest.store(-1, std::memory_order_relaxed);

        const uint32_t sr  = m_decoder->getSampleRate();
        const size_t   ch  = m_decoder->getNumChannels();

        m_gainProcessor->prepare(sr, 1024);
        m_eqProcessor->prepare(sr, 1024);

        // Open / reopen Oboe stream only when audio format changes
        if (!m_endpoint->isInitialized() ||
            m_endpoint->getStreamSampleRate()   != static_cast<int32_t>(sr) ||
            m_endpoint->getStreamChannelCount() != static_cast<int32_t>(ch))
        {
            m_endpoint->terminate();
            if (!m_endpoint->initialize(sr, ch)) {
                // initialize() failed — try once more with a tiny delay
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
                m_endpoint->initialize(sr, ch);
            }
        }
        // Ring buffer is cleared here; the Oboe callback is the only other
        // thread that touches the ring buffer, and it will safely read 0
        // samples (silence) because writeIndex == readIndex after clear().
        m_ringBuffer->clear();
    }

    // ------------------------------------------------------------------ //
    //  Transport                                                          //
    // ------------------------------------------------------------------ //

    /**
     * Start or resume playback.
     * The Oboe stream must already be running (set by setDecoder).
     */
    void play() {
        if (!m_decoder) return;
        if (m_isPlaying.load()) return;

        // Ensure the stream is alive (e.g. recovered after an audio error)
        if (!m_endpoint->isRunning()) {
            const uint32_t sr = m_decoder->getSampleRate();
            const size_t   ch = m_decoder->getNumChannels();
            m_endpoint->terminate();
            m_endpoint->initialize(sr, ch);
        }

        m_ringBuffer->clear();
        m_stopThread.store(false, std::memory_order_relaxed);
        m_isPlaying.store(true,  std::memory_order_release);

        if (m_decodeThread.joinable()) {
            m_decodeThread.join();
        }
        m_decodeThread = std::thread([this]() { decodeLoop(); });
    }

    /**
     * Pause playback.
     * Stops the decode thread.  The Oboe stream keeps running (silence).
     */
    void pause() {
        pauseDecodeThread();
    }

    // ------------------------------------------------------------------ //
    //  Seeking & metadata                                                 //
    // ------------------------------------------------------------------ //

    void seekToMs(double ms) {
        std::lock_guard<std::mutex> lk(m_decoderMutex);
        if (!m_decoder) return;
        const uint32_t sr = m_decoder->getSampleRate();
        if (sr == 0) return;
        uint64_t targetFrame = static_cast<uint64_t>((ms / 1000.0) * sr);
        const uint64_t total = m_decoder->getTotalFrames();
        if (total > 0 && targetFrame >= total) {
            targetFrame = total > 1 ? total - 2 : 0;
        }
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
        const int64_t pendingSeek = m_seekRequest.load(std::memory_order_acquire);
        const uint32_t sr = m_decoder->getSampleRate();
        if (sr == 0) return 0.0;
        if (pendingSeek >= 0) {
            return (static_cast<double>(pendingSeek) / sr) * 1000.0;
        }
        return (static_cast<double>(m_decoder->getCurrentFrame()) / sr) * 1000.0;
    }

    // ------------------------------------------------------------------ //
    //  DSP controls                                                       //
    // ------------------------------------------------------------------ //

    void setVolume(double linearVolume) {
        m_gainProcessor->setGainLinear(linearVolume);
    }

    void setSpeed(double speed) {
        if (speed < 0.25) speed = 0.25;
        if (speed > 4.0)  speed = 4.0;
        m_speed.store(speed, std::memory_order_relaxed);
    }

    // ------------------------------------------------------------------ //
    //  State queries                                                      //
    // ------------------------------------------------------------------ //

    bool     isPlaying()      const { return m_isPlaying.load(std::memory_order_relaxed); }
    uint32_t getSampleRate()  const {
        std::lock_guard<std::mutex> lk(m_decoderMutex);
        return m_decoder ? m_decoder->getSampleRate() : 0;
    }
    uint32_t getBitsPerSample() const {
        std::lock_guard<std::mutex> lk(m_decoderMutex);
        return m_decoder ? m_decoder->getBitsPerSample() : 0;
    }

private:
    // ------------------------------------------------------------------ //
    //  Internal helpers                                                   //
    // ------------------------------------------------------------------ //

    /** Stop the decode thread without touching the Oboe stream. */
    void pauseDecodeThread() {
        m_stopThread.store(true,  std::memory_order_release);
        m_isPlaying.store(false, std::memory_order_release);
        if (m_decodeThread.joinable()) {
            m_decodeThread.join();
        }
    }

    /** Full engine teardown — called from destructor only. */
    void shutdownInternal() {
        pauseDecodeThread();
        m_endpoint->terminate();
    }

    // ------------------------------------------------------------------ //
    //  Decode loop (runs on background thread)                           //
    // ------------------------------------------------------------------ //

    void decodeLoop() {
        constexpr size_t CHUNK_FRAMES = 1024;

        size_t   channels;
        uint32_t decoderSR;
        {
            std::lock_guard<std::mutex> lk(m_decoderMutex);
            channels  = m_decoder->getNumChannels();
            decoderSR = m_decoder->getSampleRate();
        }

        // Source buffer: large enough for 4× speed
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

            // Back-pressure: wait if ring buffer is nearly full
            if (m_ringBuffer->getAvailableWrite() < CHUNK_FRAMES * channels) {
                std::this_thread::sleep_for(std::chrono::milliseconds(2));
                continue;
            }

            const double speed      = m_speed.load(std::memory_order_relaxed);
            const bool   normalSpeed = (speed > 0.99 && speed < 1.01);

            if (normalSpeed) {
                // ---- Fast path (1× speed, no resampling) ---- //
                size_t framesRead;
                {
                    std::lock_guard<std::mutex> lk(m_decoderMutex);
                    framesRead = m_decoder->readFrames(outBuffer, CHUNK_FRAMES);
                }
                if (framesRead == 0) {
                    // Check if a seek arrived while reading
                    if (m_seekRequest.load(std::memory_order_acquire) >= 0) continue;
                    m_isPlaying.store(false, std::memory_order_release);
                    break; // end of track
                }

                m_eqProcessor->processBlock(outBuffer);
                m_gainProcessor->processBlock(outBuffer);

                for (size_t f = 0; f < framesRead; ++f) {
                    for (size_t ch = 0; ch < channels; ++ch) {
                        interleaved[f * channels + ch] = outBuffer.getReadPointer(ch)[f];
                    }
                }
                m_ringBuffer->write(interleaved.data(), framesRead * channels);

            } else {
                // ---- Speed-change path (linear interpolation resampling) ---- //
                size_t srcFramesToRead = static_cast<size_t>(CHUNK_FRAMES * speed + 0.5);
                if (srcFramesToRead < 1)              srcFramesToRead = 1;
                if (srcFramesToRead > CHUNK_FRAMES * 4) srcFramesToRead = CHUNK_FRAMES * 4;

                size_t framesRead;
                {
                    std::lock_guard<std::mutex> lk(m_decoderMutex);
                    framesRead = m_decoder->readFrames(srcBuffer, srcFramesToRead);
                }
                if (framesRead == 0) {
                    if (m_seekRequest.load(std::memory_order_acquire) >= 0) continue;
                    m_isPlaying.store(false, std::memory_order_release);
                    break;
                }

                // Linear interpolation: framesRead source → CHUNK_FRAMES output
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

                m_gainProcessor->processRawInterleaved(
                    interleaved.data(), outFrames, channels);

                m_ringBuffer->write(interleaved.data(), outFrames * channels);
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  Members                                                            //
    // ------------------------------------------------------------------ //

    std::atomic<bool>    m_isPlaying;
    std::atomic<bool>    m_stopThread;
    std::atomic<int64_t> m_seekRequest;
    std::atomic<double>  m_speed;
    std::thread          m_decodeThread;

    mutable std::mutex                      m_decoderMutex;
    std::unique_ptr<decoders::IAudioDecoder> m_decoder;
    std::unique_ptr<RingBuffer<double>>      m_ringBuffer;

    std::unique_ptr<dsp::GainProcessor>  m_gainProcessor;
    std::unique_ptr<dsp::BiquadFilter>   m_eqProcessor;
    std::unique_ptr<hw::OboeAudioEndpoint> m_endpoint;
};

} // namespace core
} // namespace audio_engine
