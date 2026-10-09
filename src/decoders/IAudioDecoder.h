#pragma once

#include <cstdint>
#include <fcntl.h>
#include <string>
#include <unistd.h>

#include "../core/AudioBuffer.h"

namespace audio_engine {
namespace decoders {

class FileSource;

enum class Codec : int {
    Unknown = 0,
    Flac = 1,
    Wav = 2,
    Mp3 = 3,
    Dsf = 4,
    Dff = 5,
    Aiff = 6,
    Aac = 7,       // through MediaCodec
    Alac = 8,      // through MediaCodec
    Vorbis = 9,
    Opus = 10,
    WavPack = 11,
    Ape = 12,
    Tta = 13,
    Other = 14,    // anything else MediaCodec decodes
};

/**
 * Pull-model decoder producing planar 64-bit PCM.
 *
 * A decoder is used by one thread at a time. openFd() never takes ownership of
 * the descriptor it is given: implementations dup() it and close their copy.
 */
class IAudioDecoder {
public:
    virtual ~IAudioDecoder() = default;

    virtual bool openFd(int fd) = 0;

    /** Convenience wrapper for tests and tools. */
    bool open(const std::string& path) {
        const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd < 0) return false;
        const bool result = openFd(fd);
        ::close(fd);
        return result;
    }

    /** Decodes up to framesToRead frames into buffer. Returns 0 at end of stream. */
    virtual size_t readFrames(core::AudioBuffer& buffer, size_t framesToRead) = 0;

    virtual bool seekToFrame(uint64_t targetFrame) = 0;

    /** PCM rate produced by readFrames(). */
    virtual uint32_t getSampleRate() const = 0;
    virtual size_t getNumChannels() const = 0;
    /** Source resolution: 16/24/32 for PCM, 1 for DSD, 0 for lossy codecs. */
    virtual uint32_t getBitsPerSample() const = 0;
    virtual uint64_t getTotalFrames() const = 0;
    virtual uint64_t getCurrentFrame() const = 0;

    virtual Codec getCodec() const = 0;
    /** Native 1-bit rate for DSD sources, 0 otherwise. */
    virtual uint32_t getDsdRate() const { return 0; }

    /** The byte source the decoder reads through, if it uses one (streaming, abort). */
    virtual FileSource* fileSource() { return nullptr; }
};

} // namespace decoders
} // namespace audio_engine
