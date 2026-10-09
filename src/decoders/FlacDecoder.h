#pragma once

#include "FileSource.h"
#include "IAudioDecoder.h"

#include <algorithm>
#include <vector>

#define DR_FLAC_IMPLEMENTATION
#include "dr_flac.h"

namespace audio_engine {
namespace decoders {

class FlacDecoder : public IAudioDecoder {
public:
    ~FlacDecoder() override { closeDecoder(); }

    bool openFd(int fd) override {
        closeDecoder();
        if (!m_source.open(fd)) return false;
        m_flac = drflac_open(&FileSource::onRead, onSeek, onTell, &m_source, nullptr);
        if (!m_flac || m_flac->channels == 0 || m_flac->sampleRate == 0) {
            closeDecoder();
            return false;
        }
        return true;
    }

    size_t readFrames(core::AudioBuffer& buffer, size_t framesToRead) override {
        if (!m_flac) return 0;
        const size_t channels = m_flac->channels;
        if (buffer.getNumChannels() < channels) return 0;
        framesToRead = std::min(framesToRead, buffer.getNumFrames());
        if (m_scratch.size() < framesToRead * channels) m_scratch.resize(framesToRead * channels);
        const size_t decoded = static_cast<size_t>(
            drflac_read_pcm_frames_s32(m_flac, framesToRead, m_scratch.data()));
        constexpr double scale = 1.0 / 2147483648.0;
        for (size_t ch = 0; ch < channels; ++ch) {
            double* dst = buffer.getWritePointer(ch);
            for (size_t i = 0; i < decoded; ++i) dst[i] = m_scratch[i * channels + ch] * scale;
        }
        return decoded;
    }

    bool seekToFrame(uint64_t frame) override {
        return m_flac && drflac_seek_to_pcm_frame(m_flac, frame) == DRFLAC_TRUE;
    }

    uint32_t getSampleRate() const override { return m_flac ? m_flac->sampleRate : 0; }
    size_t getNumChannels() const override { return m_flac ? m_flac->channels : 0; }
    uint32_t getBitsPerSample() const override { return m_flac ? m_flac->bitsPerSample : 0; }
    uint64_t getTotalFrames() const override { return m_flac ? m_flac->totalPCMFrameCount : 0; }
    uint64_t getCurrentFrame() const override { return m_flac ? m_flac->currentPCMFrame : 0; }
    Codec getCodec() const override { return Codec::Flac; }

private:
    FileSource m_source;
    drflac* m_flac = nullptr;
    std::vector<int32_t> m_scratch;

    void closeDecoder() {
        if (m_flac) drflac_close(m_flac);
        m_flac = nullptr;
        m_source.close();
    }

    static drflac_bool32 onSeek(void* user, int offset, drflac_seek_origin origin) {
        return FileSource::onSeekImpl(user, offset, static_cast<int>(origin)) ? DRFLAC_TRUE : DRFLAC_FALSE;
    }
    static drflac_bool32 onTell(void* user, drflac_int64* cursor) {
        int64_t value = 0;
        FileSource::onTellImpl(user, &value);
        *cursor = value;
        return DRFLAC_TRUE;
    }
};

} // namespace decoders
} // namespace audio_engine
