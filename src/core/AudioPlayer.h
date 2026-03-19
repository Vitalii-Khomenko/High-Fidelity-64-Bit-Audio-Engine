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

/**
 * @brief Main engine controller managing the playback loop and DSP chain.
 */
class AudioPlayer {
public:
    AudioPlayer() :
        m_isPlaying(false),
        m_stopThread(false),
        m_seekRequest(-1),
        m_ringBuffer(std::make_unique<RingBuffer<double>>(65536)) // 65536 power of 2
    {
        m_gainProcessor = std::make_unique<dsp::GainProcessor>();
        m_eqProcessor = std::make_unique<dsp::BiquadFilter>();
        m_endpoint = std::make_unique<hw::OboeAudioEndpoint>(m_ringBuffer.get());
    }

    ~AudioPlayer() {
        stop();
    }

    void setDecoder(std::unique_ptr<decoders::IAudioDecoder> decoder) {
        stop();
        m_decoder = std::move(decoder);
        m_seekRequest.store(-1, std::memory_order_relaxed);

        uint32_t sampleRate = m_decoder->getSampleRate();
        size_t channels = m_decoder->getNumChannels();

        m_gainProcessor->prepare(sampleRate, 1024);
        m_eqProcessor->prepare(sampleRate, 1024);

        // Re-init audio endpoint
        m_endpoint->terminate();
        m_endpoint->initialize(sampleRate, channels);
    }

    void setVolume(double linearVolume) {
        m_gainProcessor->setGainLinear(linearVolume);
    }

    void seekToMs(double ms) {
        if (!m_decoder) return;
        uint64_t targetFrame = static_cast<uint64_t>((ms / 1000.0) * m_decoder->getSampleRate());
        uint64_t totalFrames = m_decoder->getTotalFrames();
        if (totalFrames > 0 && targetFrame >= totalFrames) {
            targetFrame = totalFrames - 1;
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
        if (m_isPlaying) {
            m_stopThread = true;
            m_isPlaying = false;
            m_endpoint->terminate();
        }
        // Always attempt to join if stopping
        if (m_decodeThread.joinable()) {
            m_decodeThread.join();
        }
    }

private:
    void decodeLoop() {
        const size_t CHUNK_FRAMES = 1024;
        const size_t channels = m_decoder->getNumChannels();
        AudioBuffer pcmBuffer(channels, CHUNK_FRAMES, m_decoder->getSampleRate());
        std::vector<double> interleaved(CHUNK_FRAMES * channels);

        while (!m_stopThread) {
            // Handle pending seek
            int64_t seekFrame = m_seekRequest.load(std::memory_order_acquire);
            if (seekFrame >= 0) {
                m_decoder->seekToFrame(static_cast<uint64_t>(seekFrame));
                m_ringBuffer->clear();
                m_seekRequest.store(-1, std::memory_order_release);
            }

            // Check if there's enough space in the RingBuffer
            if (m_ringBuffer->getAvailableWrite() < (CHUNK_FRAMES * channels)) {
                // Buffer is full, wait a bit
                std::this_thread::sleep_for(std::chrono::milliseconds(5));
                continue;
            }

            // Decode a chunk
            size_t framesRead = m_decoder->readFrames(pcmBuffer, CHUNK_FRAMES);
            if (framesRead == 0) {
                // Check if EOF was legitimate or we just seeked exactly to end
                seekFrame = m_seekRequest.load(std::memory_order_acquire);
                if (seekFrame >= 0) continue; // Loop around to process the seek
                
                m_isPlaying = false;
                break; // Stop decoding thread
            }

            // Apply DSP chain in 64-bit precision
            m_eqProcessor->processBlock(pcmBuffer);
            m_gainProcessor->processBlock(pcmBuffer);

            // Interleave the 64-bit floats for the ring buffer
            for (size_t f = 0; f < framesRead; ++f) {
                for (size_t ch = 0; ch < channels; ++ch) {
                    interleaved[f * channels + ch] = pcmBuffer.getReadPointer(ch)[f];
                }
            }

            // Push to Lock-Free RingBuffer
            m_ringBuffer->write(interleaved.data(), framesRead * channels);
        }
    }

    std::atomic<bool> m_isPlaying;
    std::atomic<bool> m_stopThread;
    std::atomic<int64_t> m_seekRequest;
    std::thread m_decodeThread;

    std::unique_ptr<decoders::IAudioDecoder> m_decoder;
    std::unique_ptr<RingBuffer<double>> m_ringBuffer;

    std::unique_ptr<dsp::GainProcessor> m_gainProcessor;
    std::unique_ptr<dsp::BiquadFilter> m_eqProcessor;
    std::unique_ptr<hw::OboeAudioEndpoint> m_endpoint;
};

} // namespace core
} // namespace audio_engine
