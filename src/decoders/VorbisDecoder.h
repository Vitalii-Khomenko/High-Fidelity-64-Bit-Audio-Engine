#pragma once

#include "ChannelOrder.h"
#include "FileSource.h"
#include "IAudioDecoder.h"

#include <algorithm>
#include <cstdio>

#include <vorbis/vorbisfile.h>

namespace audio_engine {
namespace decoders {

/** Ogg Vorbis through the reference decoder (libvorbisfile), sample-exact seeking. */
class VorbisDecoder : public IAudioDecoder {
public:
    ~VorbisDecoder() override { closeDecoder(); }

    bool openFd(int fd) override {
        closeDecoder();
        if (!m_source.open(fd)) return false;
        const ov_callbacks callbacks{onRead, onSeek, nullptr, onTell};
        if (ov_open_callbacks(&m_source, &m_vf, nullptr, 0, callbacks) != 0) {
            m_source.close();
            return false;
        }
        m_open = true;
        const vorbis_info* info = ov_info(&m_vf, -1);
        if (!info || info->channels <= 0 || info->channels > 8 || info->rate <= 0) {
            closeDecoder();
            return false;
        }
        m_channels = static_cast<size_t>(info->channels);
        m_rate = static_cast<uint32_t>(info->rate);
        m_order = waveFromVorbis(m_channels);
        const ogg_int64_t total = ov_pcm_total(&m_vf, -1);
        m_total = total > 0 ? static_cast<uint64_t>(total) : 0;
        m_link = -1;
        return true;
    }

    size_t readFrames(core::AudioBuffer& buffer, size_t framesToRead) override {
        if (!m_open || buffer.getNumChannels() < m_channels) return 0;
        framesToRead = std::min(framesToRead, buffer.getNumFrames());
        size_t done = 0;
        while (done < framesToRead) {
            float** pcm = nullptr;
            int link = 0;
            const long n = ov_read_float(&m_vf, &pcm, static_cast<int>(framesToRead - done), &link);
            if (n == OV_HOLE) continue;   // a gap in the data: skip it
            if (n <= 0) break;
            if (link != m_link) {
                // A chained stream may change its format; the player cannot follow that mid-track.
                const vorbis_info* info = ov_info(&m_vf, link);
                if (!info || static_cast<size_t>(info->channels) != m_channels ||
                    static_cast<uint32_t>(info->rate) != m_rate) {
                    break;
                }
                m_link = link;
            }
            for (size_t w = 0; w < m_channels; ++w) {
                const float* src = pcm[m_order[w]];
                double* dst = buffer.getWritePointer(w) + done;
                for (long i = 0; i < n; ++i) dst[i] = src[i];
            }
            done += static_cast<size_t>(n);
        }
        return done;
    }

    bool seekToFrame(uint64_t frame) override {
        if (!m_open) return false;
        if (m_total > 0) frame = std::min(frame, m_total);
        return ov_pcm_seek(&m_vf, static_cast<ogg_int64_t>(frame)) == 0;
    }

    uint32_t getSampleRate() const override { return m_open ? m_rate : 0; }
    size_t getNumChannels() const override { return m_open ? m_channels : 0; }
    uint32_t getBitsPerSample() const override { return 0; }
    uint64_t getTotalFrames() const override { return m_total; }
    uint64_t getCurrentFrame() const override {
        if (!m_open) return 0;
        const ogg_int64_t pos = ov_pcm_tell(const_cast<OggVorbis_File*>(&m_vf));
        return pos > 0 ? static_cast<uint64_t>(pos) : 0;
    }
    Codec getCodec() const override { return Codec::Vorbis; }
    FileSource* fileSource() override { return &m_source; }

private:
    FileSource m_source;
    OggVorbis_File m_vf{};
    bool m_open = false;
    size_t m_channels = 0;
    uint32_t m_rate = 0;
    uint64_t m_total = 0;
    int m_link = -1;
    std::array<int, 8> m_order{};

    void closeDecoder() {
        if (m_open) ov_clear(&m_vf);
        m_open = false;
        m_source.close();
    }

    static size_t onRead(void* dst, size_t size, size_t count, void* user) {
        if (size == 0) return 0;
        return static_cast<FileSource*>(user)->read(dst, size * count) / size;
    }
    static int onSeek(void* user, ogg_int64_t offset, int whence) {
        const int origin = whence == SEEK_CUR ? 1 : whence == SEEK_END ? 2 : 0;
        return static_cast<FileSource*>(user)->seek(offset, origin) ? 0 : -1;
    }
    static long onTell(void* user) { return static_cast<long>(static_cast<FileSource*>(user)->tell()); }
};

} // namespace decoders
} // namespace audio_engine
