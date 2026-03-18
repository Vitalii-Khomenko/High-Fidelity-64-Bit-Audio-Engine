#pragma once

#include <string>
#include <vector>
#include "../core/AudioBuffer.h"

namespace audio_engine {
namespace decoders {

class IAudioDecoder {
public:
    virtual ~IAudioDecoder() = default;

    virtual bool open(const std::string& filepath) = 0;
    virtual bool openFd(int fd) { return false; } // Default false for those not implementing it

    virtual size_t readFrames(audio_engine::core::AudioBuffer& buffer, size_t framesToRead) = 0;
    
    virtual uint32_t getSampleRate() const = 0;
    virtual size_t getNumChannels() const = 0;
    virtual uint64_t getTotalFrames() const = 0;
};

} // namespace decoders
} // namespace audio_engine
