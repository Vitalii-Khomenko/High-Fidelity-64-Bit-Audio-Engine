#pragma once

#include <thread>
#include <atomic>
#include <memory>
#include <vector>
#include <chrono>

#include "AudioBuffer.h"
#include "RingBuffer.h"
#include "../dsp/GainProcessor.h"
#include "../dsp/BiquadFilter.h"
#include "../decoders/IAudioDecoder.h"
#include "../hw/OboeAudioEndpoint.h"

namespace audio_engine {
namespace core {

class AudioPlayer {
public:
    AudioPlayer() :
        m_isPlaying(false),
        m_stopThread(false),
        m_seekRequest(-1),
        m_speed(1.0),
        m_ringBuffer(std::make_unique<RingBuffer<double>>(65536))
    {
        m_gainProcessor = std::make_unique<dsp::GainProcessor>();
        m_eqProcessor = std::make_unique<dsp::BiquadFilter>();
        m_endpoint = std::make_unique<hw::OboeAudioEndpoint>(m_ringBuffer.get());
    }

    ~AudioPlayer() {
        stop();
    }

    void setDecoder(std::unique_ptr<decoders::IAudioDecoder> decoder) {
        // Fully stop the current playing thread and oboe strictly before switching
        stop();
        
        m_decoder = std::move(decoder);
        m_seekRequest.store(-1, std::memory_order_relaxed);
        m_ringBuffer->clear();

        uint32_t sampleRate = m_decoder->getSampleRate();
        size_t channels = m_decoder->getNumChannels();

        m_gainProcessor->prepare(sampleRate, 1024);
        m_eqProcessor->prepare(sampleRate, 1024);

        // Terminate and properly re-init
        m_endpoint->terminate();
        m_endpoint->initialize(sampleRate, channels);
    }

    void setSpeed(double speed) {
        if (speed < 0.25) speed = 0.25;
        if (speed > 4.0) speed = 4.0;
        m_speed.store(speed, std::memory_order_relaxed);
    }

    bool isPlaying() const {
        return m_isPlaying.load(std::memory_order_relaxed);
    }

    uint32_t getSampleRate() const {
        if (!m_decoder) return 0;
        return m_decoder->getSampleRate();
    }

    uint32_t getBitsPerSample() const {
        if (!m_decoder) return 0;
        return m_decoder->getBitsPerSample();
    }

    void setVolume(double linearVolume) {
        // The volume slider in compose goes 0.0 to 1.0 but maybe something is not mapping correctly?
        // Ensure atomic write handles it.
        m_gainProcessor->setGainLinear(linearVolume);
    }

    double getDurationMs() const {
        if (!m_decoder) return 0.0;
        uint64_t frames = m_decoder->getTotalFrames();
        uint32_t sr = m_decoder->getSampleRate();
        if (sr == 0) return 0.0;
        return (static_cast<double>(frames) / sr) * 1000.0;
    }

    double getPositionMs() const {
        if (!m_decoder) return 0.0;
        int64_t pendingSeek = m_seekRequest.load(std::memory_order_acquire);
        if (pendingSeek >= 0) {
            uint32_t sr = m_decoder->getSampleRate();
            if (sr == 0) return 0.0;
            return (static_cast<double>(pendingSeek) / sr) * 1000.0;
        }

        uint64_t frames = m_decoder->getCurrentFrame();
        uint32_t sr = m_decoder->getSampleRate();
        if (sr == 0) return 0.0;
        return (static_cast<double>(frames) / sr) * 1000.0;
    }

    void seekToMs(double ms) {
        if (!m_decoder) return;
        uint32_t sr = m_decoder->getSampleRate();
        if (sr == 0) return;
        
        uint64_t targetFrame = static_cast<uint64_t>((ms / 1000.0) * sr);
        uint64_t totalFrames = m_decoder->getTotalFrames();
        if (totalFrames > 0 && targetFrame >= totalFrames) {
            targetFrame = totalFrames > 1 ? totalFrames - 2 : 0; 
        }
        m_seekRequest.store(static_cast<int64_t>(targetFrame), std::memory_order_release);
    }

    void play() {
        if (!m_decoder || m_isPlaying) return;

        m_stopThread = false;
        m_isPlaying = true;

        m_endpoint->startHardwareThread();

        if (m_decodeThread.joinable()) {
            m_decodeThread.join();
        }

        m_decodeThread = std::thread([this]() {
            decodeLoop();
        });
    }

    void stop() {
        m_stopThread = true; // Always set to true regardless of m_isPlaying
        if (m_isPlaying) {
            m_isPlaying = false;
            m_endpoint->terminate();
        } else {
            // Edge case: it finished naturally, but Oboe is still playing the tail!
            m_endpoint->terminate();
        }
        
        if (m_decodeThread.joinable()) {
            m_decodeThread.join();
        }
        m_ringBuffer->clear();
    }

private:
    void decodeLoop() {
        const size_t CHUNK_FRAMES = 1024;
        const size_t channels = m_decoder->getNumChannels();
        // Source buffer large enough for 4x speed (max speed)
        AudioBuffer srcBuffer(channels, CHUNK_FRAMES * 4, m_decoder->getSampleRate());
        AudioBuffer outBuffer(channels, CHUNK_FRAMES, m_decoder->getSampleRate());
        std::vector<double> interleaved(CHUNK_FRAMES * channels);

        while (!m_stopThread) {
            int64_t seekFrame = m_seekRequest.load(std::memory_order_acquire);
            if (seekFrame >= 0) {
                m_decoder->seekToFrame(static_cast<uint64_t>(seekFrame));
                m_ringBuffer->clear();
                m_seekRequest.store(-1, std::memory_order_release);
            }

            if (m_ringBuffer->getAvailableWrite() < (CHUNK_FRAMES * channels)) {
                std::this_thread::sleep_for(std::chrono::milliseconds(2));
                continue;
            }

            double speed = m_speed.load(std::memory_order_relaxed);
            bool normalSpeed = (speed > 0.99 && speed < 1.01);

            if (normalSpeed) {
                // Fast path: no resampling needed
                size_t framesRead = m_decoder->readFrames(outBuffer, CHUNK_FRAMES);
                if (framesRead == 0) {
                    seekFrame = m_seekRequest.load(std::memory_order_acquire);
                    if (seekFrame >= 0) continue;
                    m_isPlaying = false;
                    break;
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
                // Speed-change path: decode speed*CHUNK_FRAMES source frames,
                // then linear-interpolate resample to exactly CHUNK_FRAMES output frames.
                size_t srcFramesToRead = static_cast<size_t>(CHUNK_FRAMES * speed + 0.5);
                if (srcFramesToRead < 1) srcFramesToRead = 1;
                if (srcFramesToRead > CHUNK_FRAMES * 4) srcFramesToRead = CHUNK_FRAMES * 4;

                size_t framesRead = m_decoder->readFrames(srcBuffer, srcFramesToRead);
                if (framesRead == 0) {
                    seekFrame = m_seekRequest.load(std::memory_order_acquire);
                    if (seekFrame >= 0) continue;
                    m_isPlaying = false;
                    break;
                }

                // Linear interpolation resample: framesRead src frames -> CHUNK_FRAMES output frames.
                // NOTE: We resample FIRST, then apply DSP on the correctly-sized interleaved output.
                // This avoids applying DSP to extra zero-padded frames in the oversized srcBuffer.
                size_t outFrames = CHUNK_FRAMES;
                double ratio = (framesRead > 1)
                    ? static_cast<double>(framesRead - 1) / static_cast<double>(outFrames - 1)
                    : 0.0;

                for (size_t f = 0; f < outFrames; ++f) {
                    double srcPos = f * ratio;
                    size_t i0 = static_cast<size_t>(srcPos);
                    size_t i1 = i0 + 1;
                    if (i1 >= framesRead) i1 = framesRead - 1;
                    double frac = srcPos - static_cast<double>(i0);

                    for (size_t ch = 0; ch < channels; ++ch) {
                        const double* src = srcBuffer.getReadPointer(ch);
                        interleaved[f * channels + ch] = src[i0] * (1.0 - frac) + src[i1] * frac;
                    }
                }

                // Apply gain smoothly on the correctly-sized interleaved output.
                m_gainProcessor->processRawInterleaved(interleaved.data(), outFrames, channels);

                m_ringBuffer->write(interleaved.data(), outFrames * channels);
            }
        }
    }

    std::atomic<bool> m_isPlaying;
    std::atomic<bool> m_stopThread;
    std::atomic<int64_t> m_seekRequest;
    std::atomic<double> m_speed;
    std::thread m_decodeThread;

    std::unique_ptr<decoders::IAudioDecoder> m_decoder;
    std::unique_ptr<RingBuffer<double>> m_ringBuffer;

    std::unique_ptr<dsp::GainProcessor> m_gainProcessor;
    std::unique_ptr<dsp::BiquadFilter> m_eqProcessor;
    std::unique_ptr<hw::OboeAudioEndpoint> m_endpoint;
};

} // namespace core
} // namespace audio_engine
