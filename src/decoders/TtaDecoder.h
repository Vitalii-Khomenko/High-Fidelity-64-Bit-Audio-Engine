#pragma once

#include "FileSource.h"
#include "IAudioDecoder.h"

#include <algorithm>
#include <memory>
#include <vector>

#include <libtta.h>

namespace audio_engine {
namespace decoders {

/**
 * True Audio (.tta) through libtta++ (LGPL-3, linked as libtta.so).
 *
 * libtta seeks only to frame starts (about 1.045 s apart); the rest of the
 * way to the target is decoded and discarded, so seeks are sample-exact.
 */
class TtaDecoder : public IAudioDecoder {
public:
    ~TtaDecoder() override { closeDecoder(); }

    bool openFd(int fd) override {
        closeDecoder();
        if (!m_source.open(fd)) return false;
        m_io.source = &m_source;
        if (!init()) {
            closeDecoder();
            return false;
        }
        return true;
    }

    size_t readFrames(core::AudioBuffer& buffer, size_t framesToRead) override {
        if (!m_tta || buffer.getNumChannels() < m_channels) return 0;
        framesToRead = std::min(framesToRead, buffer.getNumFrames());
        if (m_total > 0) framesToRead = static_cast<size_t>(std::min<uint64_t>(framesToRead, m_total - std::min(m_total, m_position)));
        if (framesToRead == 0) return 0;
        const size_t frames = decode(framesToRead);
        const size_t frameBytes = m_depth * m_channels;
        for (size_t ch = 0; ch < m_channels; ++ch) {
            double* dst = buffer.getWritePointer(ch);
            const uint8_t* p = m_scratch.data() + ch * m_depth;
            for (size_t i = 0; i < frames; ++i, p += frameBytes) {
                int32_t v = m_depth == 2 ? static_cast<int16_t>(p[0] | (p[1] << 8))
                                         : (p[0] | (p[1] << 8) | (p[2] << 16));
                if (m_depth == 3 && (v & 0x800000)) v -= 0x1000000;
                dst[i] = v * m_scale;
            }
        }
        m_position += frames;
        return frames;
    }

    bool seekToFrame(uint64_t frame) override {
        if (!m_tta) return false;
        if (m_total > 0 && frame >= m_total) {
            // readFrames() stops at m_total; no need to decode up to it.
            m_position = m_total;
            return true;
        }
        uint64_t start = 0;
        const uint64_t frameIndex = frame / m_frameLength;
        bool positioned = false;
        if (m_tta->seek_allowed) {
            try {
                // The smallest whole second that libtta maps to this frame.
                const uint32_t seconds = static_cast<uint32_t>((256 * frameIndex + 244) / 245);
                uint32_t newPos = 0;
                m_tta->set_position(seconds, &newPos);
                start = frameIndex * m_frameLength;
                positioned = true;
            } catch (const std::exception&) {
                positioned = false;
            }
        }
        if (!positioned) {
            // No usable seek table: start over and decode up to the target.
            m_source.seek(0, 0);
            if (!init()) return false;
            start = 0;
        }
        m_position = start;
        while (m_position < frame) {
            const size_t n = decode(static_cast<size_t>(std::min<uint64_t>(frame - m_position, 4096)));
            if (n == 0) return false;
            m_position += n;
        }
        return true;
    }

    uint32_t getSampleRate() const override { return m_tta ? m_rate : 0; }
    size_t getNumChannels() const override { return m_tta ? m_channels : 0; }
    uint32_t getBitsPerSample() const override { return m_bits; }
    uint64_t getTotalFrames() const override { return m_total; }
    uint64_t getCurrentFrame() const override { return m_position; }
    Codec getCodec() const override { return Codec::Tta; }

private:
    // libtta passes this struct back to the callbacks; the callback table is
    // its first member, so the pointer converts to the whole struct.
    struct Io {
        TTA_io_callback cb{&onRead, &onWrite, &onSeek};
        FileSource* source = nullptr;
    };

    bool init() {
        m_tta = std::make_unique<tta::tta_decoder>(&m_io.cb);
        TTA_info info{};
        try {
            m_tta->init_get_info(&info, 0);
        } catch (const std::exception&) {
            m_tta.reset();
            return false;
        }
        // Format 2 is password-protected; 8-bit is not supported by libtta.
        if (info.format != TTA_FORMAT_SIMPLE || info.nch == 0 || info.nch > 8 || info.sps == 0 ||
            (info.bps != 16 && info.bps != 24)) {
            m_tta.reset();
            return false;
        }
        m_channels = info.nch;
        m_rate = info.sps;
        m_bits = info.bps;
        m_depth = (info.bps + 7) / 8;
        m_scale = 1.0 / static_cast<double>(1u << (info.bps - 1));
        m_total = info.samples;
        m_frameLength = std::max<uint64_t>(1, 256ull * info.sps / 245);
        m_position = 0;
        return true;
    }

    /** Decodes up to frames into m_scratch; 0 at the end or on an error. */
    size_t decode(size_t frames) {
        const size_t bytes = frames * m_depth * m_channels;
        // libtta stores 24-bit samples with a 4-byte write: keep slack at the end.
        if (m_scratch.size() < bytes + 4) m_scratch.resize(bytes + 4);
        size_t done = 0;
        try {
            while (done < frames) {
                const int n = m_tta->process_stream(m_scratch.data() + done * m_depth * m_channels,
                                                    static_cast<TTAuint32>((frames - done) * m_depth * m_channels));
                if (n <= 0) break;
                done += static_cast<size_t>(n);
            }
        } catch (const std::exception&) {
            // Read error or damaged data: what was decoded so far is kept.
        }
        return done;
    }

    void closeDecoder() {
        m_tta.reset();
        m_source.close();
    }

    static FileSource* src(TTA_io_callback* cb) { return reinterpret_cast<Io*>(cb)->source; }
    static TTAint32 CALLBACK onRead(TTA_io_callback* cb, TTAuint8* buffer, TTAuint32 size) {
        return static_cast<TTAint32>(src(cb)->read(buffer, size));
    }
    static TTAint32 CALLBACK onWrite(TTA_io_callback*, TTAuint8*, TTAuint32) { return 0; }
    static TTAint64 CALLBACK onSeek(TTA_io_callback* cb, TTAint64 offset) {
        return src(cb)->seek(offset, 0) ? offset : -1;
    }

    FileSource m_source;
    alignas(16) Io m_io;
    std::unique_ptr<tta::tta_decoder> m_tta;
    size_t m_channels = 0;
    uint32_t m_rate = 0;
    uint32_t m_bits = 0;
    size_t m_depth = 2;
    double m_scale = 1.0;
    uint64_t m_total = 0;
    uint64_t m_frameLength = 1;
    uint64_t m_position = 0;
    std::vector<uint8_t> m_scratch;
};

} // namespace decoders
} // namespace audio_engine
