#pragma once

#include <vector>
#include <cstdint>
#include <cstddef>
#include <algorithm>

namespace audio_engine {
namespace core {

/**
 * @brief Represents an audio buffer handling 64-bit float (double) precision processing.
 * 
 * Used for storing and passing audio data between DSP processors and hardware endpoints.
 */
class AudioBuffer {
public:
    /**
     * @brief Constructs an AudioBuffer with a specific channel count, frame size, and sample rate.
     * @param numChannels Number of audio channels (e.g., 2 for Stereo).
     * @param numFrames Number of frames per channel.
     * @param sampleRate Sampling rate in Hz (e.g., 44100, 96000).
     */
    AudioBuffer(size_t numChannels, size_t numFrames, uint32_t sampleRate)
        : m_numChannels(numChannels),
          m_numFrames(numFrames),
          m_sampleRate(sampleRate) {
        
        // Allocate space for channels
        m_channels.resize(m_numChannels);
        for (auto& channel : m_channels) {
            channel.resize(m_numFrames, 0.0);
        }
    }

    ~AudioBuffer() = default;

    // Delete copy constructors to prevent deep copies in real-time threads
    AudioBuffer(const AudioBuffer&) = delete;
    AudioBuffer& operator=(const AudioBuffer&) = delete;

    // Allow move semantics
    AudioBuffer(AudioBuffer&&) noexcept = default;
    AudioBuffer& operator=(AudioBuffer&&) noexcept = default;

    /**
     * @brief Get a pointer to the start of a specific channel's data.
     * @param channelIndex The index of the channel.
     * @return double* pointing to the data, or nullptr if out of bounds.
     */
    double* getWritePointer(size_t channelIndex) {
        if (channelIndex >= m_numChannels) return nullptr;
        return m_channels[channelIndex].data();
    }

    /**
     * @brief Get a const pointer to the start of a specific channel's data.
     * @param channelIndex The index of the channel.
     * @return const double* pointing to the data, or nullptr if out of bounds.
     */
    const double* getReadPointer(size_t channelIndex) const {
        if (channelIndex >= m_numChannels) return nullptr;
        return m_channels[channelIndex].data();
    }

    /**
     * @brief Clear the buffer (fill with zeros).
     */
    void clear() {
        for (auto& channel : m_channels) {
            std::fill(channel.begin(), channel.end(), 0.0);
        }
    }

    // Getters for audio metadata
    size_t getNumChannels() const noexcept { return m_numChannels; }
    size_t getNumFrames() const noexcept { return m_numFrames; }
    uint32_t getSampleRate() const noexcept { return m_sampleRate; }

    void setSampleRate(uint32_t sampleRate) noexcept { m_sampleRate = sampleRate; }

private:
    size_t m_numChannels;
    size_t m_numFrames;
    uint32_t m_sampleRate;
    
    // Each channel is a continuous block of 64-bit floats
    std::vector<std::vector<double>> m_channels;
};

} // namespace core
} // namespace audio_engine
