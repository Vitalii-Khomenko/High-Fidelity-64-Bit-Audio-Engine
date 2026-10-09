#pragma once

#include "FileSource.h"
#include "IAudioDecoder.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>

#include <wavpack.h>

namespace audio_engine {
namespace decoders {

/**
 * WavPack (lossless, hybrid lossy without a .wvc correction file, integer or
 * float). WavPack keeps the WAVE channel order.
 */
class WavPackDecoder : public IAudioDecoder {
public:
    ~WavPackDecoder() override { closeDecoder(); }

    bool openFd(int fd) override {
        closeDecoder();
        if (!m_source.open(fd)) return false;
        char error[80] = {};
        m_wpc = WavpackOpenFileInputEx64(&kReader, &m_source, nullptr, error, OPEN_NORMALIZE, 0);
        if (!m_wpc) {
            m_source.close();
            return false;
        }
        const int channels = WavpackGetNumChannels(m_wpc);
        const uint32_t rate = WavpackGetSampleRate(m_wpc);
        const int bytes = WavpackGetBytesPerSample(m_wpc);
        const int mode = WavpackGetMode(m_wpc);
        // DSD WavPack needs a library built with DSD support; not offered.
        if (channels <= 0 || channels > 8 || rate == 0 || bytes < 1 || bytes > 4 ||
            (WavpackGetQualifyMode(m_wpc) & QMODE_DSD_AUDIO)) {
            closeDecoder();
            return false;
        }
        m_channels = static_cast<size_t>(channels);
        m_rate = rate;
        m_float = (mode & MODE_FLOAT) != 0;
        m_bits = static_cast<uint32_t>(WavpackGetBitsPerSample(m_wpc));
        // Samples come back right-justified in the container size.
        m_scale = 1.0 / std::ldexp(1.0, 8 * bytes - 1);
        const int64_t total = WavpackGetNumSamples64(m_wpc);
        m_total = total > 0 ? static_cast<uint64_t>(total) : 0;
        m_position = 0;
        m_atEnd = false;
        return true;
    }

    size_t readFrames(core::AudioBuffer& buffer, size_t framesToRead) override {
        if (!m_wpc || m_atEnd || buffer.getNumChannels() < m_channels) return 0;
        framesToRead = std::min(framesToRead, buffer.getNumFrames());
        if (m_scratch.size() < framesToRead * m_channels) m_scratch.resize(framesToRead * m_channels);
        const uint32_t got = WavpackUnpackSamples(m_wpc, m_scratch.data(), static_cast<uint32_t>(framesToRead));
        for (size_t ch = 0; ch < m_channels; ++ch) {
            double* dst = buffer.getWritePointer(ch);
            for (uint32_t i = 0; i < got; ++i) {
                const int32_t v = m_scratch[i * m_channels + ch];
                if (m_float) {
                    float f;
                    std::memcpy(&f, &v, sizeof(f));
                    dst[i] = f;
                } else {
                    dst[i] = v * m_scale;
                }
            }
        }
        m_position += got;
        return got;
    }

    bool seekToFrame(uint64_t frame) override {
        if (!m_wpc) return false;
        m_atEnd = m_total > 0 && frame >= m_total;   // the library cannot seek to the very end
        if (m_atEnd) {
            m_position = m_total;
            return true;
        }
        if (!WavpackSeekSample64(m_wpc, static_cast<int64_t>(frame))) {
            // A failed seek leaves the context unusable; reopen at the start.
            return reopen() && frame == 0;
        }
        m_position = frame;
        return true;
    }

    uint32_t getSampleRate() const override { return m_wpc ? m_rate : 0; }
    size_t getNumChannels() const override { return m_wpc ? m_channels : 0; }
    uint32_t getBitsPerSample() const override { return m_bits; }
    uint64_t getTotalFrames() const override { return m_total; }
    uint64_t getCurrentFrame() const override { return m_position; }
    Codec getCodec() const override { return Codec::WavPack; }
    FileSource* fileSource() override { return &m_source; }

private:
    FileSource m_source;
    WavpackContext* m_wpc = nullptr;
    size_t m_channels = 0;
    uint32_t m_rate = 0;
    uint32_t m_bits = 0;
    bool m_float = false;
    double m_scale = 1.0;
    uint64_t m_total = 0;
    uint64_t m_position = 0;
    bool m_atEnd = false;
    std::vector<int32_t> m_scratch;

    void closeDecoder() {
        if (m_wpc) WavpackCloseFile(m_wpc);
        m_wpc = nullptr;
        m_source.close();
    }

    bool reopen() {
        if (m_wpc) WavpackCloseFile(m_wpc);
        m_source.seek(0, 0);
        char error[80] = {};
        m_wpc = WavpackOpenFileInputEx64(&kReader, &m_source, nullptr, error, OPEN_NORMALIZE, 0);
        m_position = 0;
        m_atEnd = false;
        return m_wpc != nullptr;
    }

    static FileSource* src(void* id) { return static_cast<FileSource*>(id); }

    static int32_t readBytes(void* id, void* data, int32_t count) {
        return count > 0 ? static_cast<int32_t>(src(id)->read(data, static_cast<size_t>(count))) : 0;
    }
    static int32_t writeBytes(void*, void*, int32_t) { return 0; }
    static int64_t getPos(void* id) { return static_cast<int64_t>(src(id)->tell()); }
    static int setPosAbs(void* id, int64_t pos) { return src(id)->seek(pos, 0) ? 0 : -1; }
    static int setPosRel(void* id, int64_t delta, int mode) {
        const int origin = mode == SEEK_CUR ? 1 : mode == SEEK_END ? 2 : 0;
        return src(id)->seek(delta, origin) ? 0 : -1;
    }
    static int pushBackByte(void* id, int c) { return src(id)->seek(-1, 1) ? c : EOF; }
    static int64_t getLength(void* id) { return static_cast<int64_t>(src(id)->size()); }
    static int canSeek(void*) { return 1; }

    static inline WavpackStreamReader64 kReader = {
        readBytes, writeBytes, getPos, setPosAbs, setPosRel, pushBackByte, getLength, canSeek, nullptr, nullptr,
    };
};

} // namespace decoders
} // namespace audio_engine
