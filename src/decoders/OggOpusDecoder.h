#pragma once

#include "ChannelOrder.h"
#include "FileSource.h"
#include "IAudioDecoder.h"

#include <algorithm>
#include <cstdio>
#include <vector>

#include <opusfile.h>

namespace audio_engine {
namespace decoders {

/**
 * Ogg Opus through libopusfile. Opus always decodes at 48 kHz; pre-skip and
 * the header output gain are applied by opusfile, so the stream is gapless.
 */
class OggOpusDecoder : public IAudioDecoder {
public:
    static constexpr uint32_t kRate = 48000;

    ~OggOpusDecoder() override { closeDecoder(); }

    bool openFd(int fd) override {
        closeDecoder();
        if (!m_source.open(fd)) return false;
        const OpusFileCallbacks callbacks{onRead, onSeek, onTell, nullptr};
        int error = 0;
        m_of = op_open_callbacks(&m_source, &callbacks, nullptr, 0, &error);
        if (!m_of) {
            m_source.close();
            return false;
        }
        const int channels = op_channel_count(m_of, -1);
        if (channels <= 0 || channels > 8) {
            closeDecoder();
            return false;
        }
        m_channels = static_cast<size_t>(channels);
        m_order = waveFromVorbis(m_channels);
        const ogg_int64_t total = op_pcm_total(m_of, -1);
        m_total = total > 0 ? static_cast<uint64_t>(total) : 0;
        return true;
    }

    size_t readFrames(core::AudioBuffer& buffer, size_t framesToRead) override {
        if (!m_of || buffer.getNumChannels() < m_channels) return 0;
        framesToRead = std::min(framesToRead, buffer.getNumFrames());
        if (m_scratch.size() < framesToRead * m_channels) m_scratch.resize(framesToRead * m_channels);
        size_t done = 0;
        while (done < framesToRead) {
            int link = 0;
            const int n = op_read_float(m_of, m_scratch.data(),
                                        static_cast<int>((framesToRead - done) * m_channels), &link);
            if (n == OP_HOLE) continue;
            if (n <= 0) break;
            // Chained streams with another channel count cannot continue mid-track.
            if (static_cast<size_t>(op_channel_count(m_of, link)) != m_channels) break;
            for (size_t w = 0; w < m_channels; ++w) {
                const size_t src = static_cast<size_t>(m_order[w]);
                double* dst = buffer.getWritePointer(w) + done;
                for (int i = 0; i < n; ++i) dst[i] = m_scratch[static_cast<size_t>(i) * m_channels + src];
            }
            done += static_cast<size_t>(n);
        }
        return done;
    }

    bool seekToFrame(uint64_t frame) override {
        if (!m_of) return false;
        if (m_total > 0) frame = std::min(frame, m_total);
        return op_pcm_seek(m_of, static_cast<ogg_int64_t>(frame)) == 0;
    }

    uint32_t getSampleRate() const override { return m_of ? kRate : 0; }
    size_t getNumChannels() const override { return m_of ? m_channels : 0; }
    uint32_t getBitsPerSample() const override { return 0; }
    uint64_t getTotalFrames() const override { return m_total; }
    uint64_t getCurrentFrame() const override {
        if (!m_of) return 0;
        const ogg_int64_t pos = op_pcm_tell(m_of);
        return pos > 0 ? static_cast<uint64_t>(pos) : 0;
    }
    Codec getCodec() const override { return Codec::Opus; }

private:
    FileSource m_source;
    OggOpusFile* m_of = nullptr;
    size_t m_channels = 0;
    uint64_t m_total = 0;
    std::vector<float> m_scratch;
    std::array<int, 8> m_order{};

    void closeDecoder() {
        if (m_of) op_free(m_of);
        m_of = nullptr;
        m_source.close();
    }

    static int onRead(void* user, unsigned char* dst, int bytes) {
        if (bytes <= 0) return 0;
        return static_cast<int>(static_cast<FileSource*>(user)->read(dst, static_cast<size_t>(bytes)));
    }
    static int onSeek(void* user, opus_int64 offset, int whence) {
        const int origin = whence == SEEK_CUR ? 1 : whence == SEEK_END ? 2 : 0;
        return static_cast<FileSource*>(user)->seek(offset, origin) ? 0 : -1;
    }
    static opus_int64 onTell(void* user) { return static_cast<opus_int64>(static_cast<FileSource*>(user)->tell()); }
};

} // namespace decoders
} // namespace audio_engine
