#pragma once

#include <string>
#include <memory>
#include "../core/AudioBuffer.h"

namespace audio_engine {
namespace decoders {

/**
 * @brief Common interface for all audio decoders (FLAC, MP3, WAV, DSD).
 */
class IAudioDecoder {
public:
    virtual ~IAudioDecoder() = default;

    /**
     * @brief Opens an audio file (or URI).
     * @param filepath Path to the file.
     * @return true if successfully opened and parsed headers.
     */
    virtual bool open(const std::string& filepath) = 0;

    /**
     * @brief Reads a specific number of frames into a 64-bit AudioBuffer directly.
     * @param buffer The destination buffer (will be resized or data written up to limit).
     * @param framesToRead Maximum number of frames to decode in this pass.
     * @return Number of frames actually read (0 means EOF).
     */
    virtual size_t readFrames(core::AudioBuffer& buffer, size_t framesToRead) = 0;

    /**
     * @brief Gets the source track's sample rate (essential for bit-perfect output).
     */
    virtual uint32_t getSampleRate() const = 0;

    /**
     * @brief Gets number of channels.
     */
    virtual size_t getNumChannels() const = 0;

    /**
     * @brief Gets total number of frames in the track (if known).
     */
    virtual uint64_t getTotalFrames() const = 0;
};

} // namespace decoders
} // namespace audio_engine
