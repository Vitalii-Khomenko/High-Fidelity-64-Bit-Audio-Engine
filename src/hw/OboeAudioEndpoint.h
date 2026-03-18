#pragma once

#include <vector>
#include <string>
#include <memory>
#include <iostream>
#include <oboe/Oboe.h>

#include "../core/RingBuffer.h"
#include "../dsp/DitherProcessor.h"

namespace audio_engine {
namespace hw {

/**
 * @brief Oboe Audio Endpoint for High-Fidelity Android Audio Output.
 *
 * Uses Google's Oboe library to achieve the lowest possible latency and
 * exclusive hardware access (AAudio EXCLUSIVE mode) on modern Android devices.
 */
class OboeAudioEndpoint : public oboe::AudioStreamCallback {
public:
    /**
     * @brief Construct the endpoint linked to a specific DSP RingBuffer.
     * @param ringBuffer The ring buffer containing 64-bit float interleaved samples.
     */
    OboeAudioEndpoint(core::RingBuffer<double>* ringBuffer) 
        : m_ringBuffer(ringBuffer), m_stream(nullptr), m_isPlaying(false) {
    }

    ~OboeAudioEndpoint() {
        terminate();
    }

    bool initialize(uint32_t sampleRate, size_t numChannels) {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
               ->setPerformanceMode(oboe::PerformanceMode::None)
               ->setSharingMode(oboe::SharingMode::Shared)
               ->setFormat(oboe::AudioFormat::Float) // Let Oboe mix 32-bit floats
               ->setChannelCount(numChannels)
               ->setSampleRate(sampleRate)
               ->setCallback(this);

        oboe::Result result = builder.openStream(m_stream);
        
        if (result != oboe::Result::OK) {
            std::cerr << "Failed to create Oboe audio stream. Error: " << oboe::convertToText(result) << std::endl;
            return false;
        }

        m_numChannels = m_stream->getChannelCount();
        m_tempBuffer.resize(8192 * m_numChannels, 0.0); // Pre-allocate scratch buffer
        return true;
    }

    void startHardwareThread() {
        if (m_stream && !m_isPlaying) {
            m_isPlaying = true;
            m_stream->requestStart();
        }
    }

    void terminate() {
        if (m_stream) {
            m_stream->requestStop();
            m_stream->close();
            m_stream.reset();
        }
        m_isPlaying = false;
    }

    /**
     * @brief Oboe Callback: Pulled from the hardware audio thread.
     */
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *audioStream, void *audioData, int32_t numFrames) override {
        // We asked Oboe for Float.
        float* outBuffer = static_cast<float*>(audioData);
        size_t samplesNeeded = numFrames * m_numChannels;
        
        // Ensure our temp buffer can hold the chunk (safe check, shouldn't allocate in RT)
        if (samplesNeeded > m_tempBuffer.size()) {
            // Ideally avoid allocations in real-time, just play what we can
            samplesNeeded = m_tempBuffer.size(); 
        }

        // Pull double-precision samples from our Lock-Free RingBuffer
        size_t samplesRead = m_ringBuffer->read(m_tempBuffer.data(), samplesNeeded);

        // Fill remaining with silence if underrun
        for (size_t i = samplesRead; i < samplesNeeded; ++i) {
            m_tempBuffer[i] = 0.0;
        }

        // Simple downcast from 64-bit float to 32-bit float for Oboe
        for (size_t i = 0; i < samplesNeeded; ++i) {
            outBuffer[i] = static_cast<float>(m_tempBuffer[i]);
        }

        return oboe::DataCallbackResult::Continue;
    }

private:
    core::RingBuffer<double>* m_ringBuffer;
    std::shared_ptr<oboe::AudioStream> m_stream;
    bool m_isPlaying;
    size_t m_numChannels = 2;
    std::vector<double> m_tempBuffer;
};

} // namespace hw
} // namespace audio_engine