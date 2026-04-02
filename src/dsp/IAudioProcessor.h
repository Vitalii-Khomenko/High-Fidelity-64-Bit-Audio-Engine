#pragma once

#include "../core/AudioBuffer.h"

namespace audio_engine {
namespace dsp {

/**
 * @brief Interface for any digital signal processor.
 * 
 * Provides a common contract for modules that modify AudioBuffers
 * (e.g., EQ, Volume Control, Crossfeed) strictly utilizing 64-bit double precision.
 */
class IAudioProcessor {
public:
    virtual ~IAudioProcessor() = default;

    /**
     * @brief Prepares the processor before processing audio. Ensure proper internal state allocation.
     * @param sampleRate The operating sample rate.
     * @param bufferSize The maximum expected buffer size per callback.
     */
    virtual void prepare(uint32_t sampleRate, size_t bufferSize) = 0;

    /**
     * @brief Processes a block of audio. Modifies the buffer in-place.
     * @param buffer The input-output AudioBuffer to process.
     */
    virtual void processBlock(core::AudioBuffer& buffer) = 0;

    /**
     * @brief Resets any internal state (e.g., filter history) to prevent artifacts.
     */
    virtual void reset() = 0;
};

} // namespace dsp
} // namespace audio_engine
