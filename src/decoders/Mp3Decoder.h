#pragma once

#include "FileSource.h"
#include "IAudioDecoder.h"

#include <algorithm>
#include <vector>

#define DR_MP3_IMPLEMENTATION
#include "dr_mp3.h"

namespace audio_engine {
namespace decoders {

/**
 * MP3 decoder (dr_mp3) with an exact frame count and a seek table.
 *
 * dr_mp3 seeks by decoding from the start of the file unless a seek table is
 * bound. For a long audiobook that turns every seek (and every resume from a
 * bookmark) into seconds of work, so open() walks the frame headers once,
 * counts the PCM frames and records a seek point roughly every second.
 */
class Mp3Decoder : public IAudioDecoder {
public:
    ~Mp3Decoder() override { closeDecoder(); }

    bool openFd(int fd) override {
        closeDecoder();
        if (!m_source.open(fd)) return false;
        m_initialized = drmp3_init(&m_mp3, &FileSource::onRead, onSeek, onTell, nullptr,
                                   &m_source, nullptr) == DRMP3_TRUE;
        if (!m_initialized || m_mp3.channels == 0 || m_mp3.sampleRate == 0) {
            closeDecoder();
            return false;
        }
        if (!buildSeekTable()) {
            closeDecoder();
            return false;
        }
        return true;
    }

    size_t readFrames(core::AudioBuffer& buffer, size_t framesToRead) override {
        if (!m_initialized) return 0;
        const size_t channels = m_mp3.channels;
        if (buffer.getNumChannels() < channels) return 0;
        framesToRead = std::min(framesToRead, buffer.getNumFrames());
        if (m_scratch.size() < framesToRead * channels) m_scratch.resize(framesToRead * channels);
        const size_t decoded = static_cast<size_t>(
            drmp3_read_pcm_frames_f32(&m_mp3, framesToRead, m_scratch.data()));
        for (size_t ch = 0; ch < channels; ++ch) {
            double* dst = buffer.getWritePointer(ch);
            for (size_t i = 0; i < decoded; ++i) dst[i] = m_scratch[i * channels + ch];
        }
        return decoded;
    }

    // dr_mp3 counts frames from the start of the stream including the encoder
    // delay it skips on playback; our frame numbers start after that delay.
    bool seekToFrame(uint64_t frame) override {
        if (!m_initialized) return false;
        const uint64_t raw = frame == 0 ? 0 : frame + m_mp3.delayInPCMFrames;
        return drmp3_seek_to_pcm_frame(&m_mp3, raw) == DRMP3_TRUE;
    }

    uint32_t getSampleRate() const override { return m_initialized ? m_mp3.sampleRate : 0; }
    size_t getNumChannels() const override { return m_initialized ? m_mp3.channels : 0; }
    uint32_t getBitsPerSample() const override { return 0; }
    uint64_t getTotalFrames() const override { return m_initialized ? m_totalFrames : 0; }
    uint64_t getCurrentFrame() const override {
        if (!m_initialized) return 0;
        const uint64_t delay = m_mp3.delayInPCMFrames;
        return m_mp3.currentPCMFrame > delay ? m_mp3.currentPCMFrame - delay : 0;
    }
    Codec getCodec() const override { return Codec::Mp3; }
    FileSource* fileSource() override { return &m_source; }

    size_t getSeekPointCount() const { return m_seekPoints.size(); }

private:
    FileSource m_source;
    drmp3 m_mp3{};
    bool m_initialized = false;
    uint64_t m_totalFrames = 0;
    std::vector<float> m_scratch;
    std::vector<drmp3_seek_point> m_seekPoints;

    void closeDecoder() {
        if (m_initialized) drmp3_uninit(&m_mp3);
        m_initialized = false;
        m_totalFrames = 0;
        m_seekPoints.clear();
        m_source.close();
    }

    // Mirrors drmp3_calculate_seek_points(), but in a single pass that also
    // yields the frame count. A seek point for target frame start S(i) starts
    // decoding DRMP3_SEEK_LEADING_MP3_FRAMES frames earlier to refill the bit
    // reservoir, then lets dr_mp3 discard samples up to the target.
    bool buildSeekTable() {
        constexpr int kLeading = DRMP3_SEEK_LEADING_MP3_FRAMES;
        static_assert(kLeading == 2, "seek point layout assumes two leading frames");
        if (!drmp3_seek_to_start_of_stream(&m_mp3)) return false;

        struct FrameInfo { uint64_t bytePos; uint64_t pcmStart; };
        FrameInfo recent[kLeading + 1] = {};
        uint64_t frameIndex = 0;
        uint64_t running = 0;
        uint64_t nextTarget = 0;
        const uint64_t stride = std::max<uint32_t>(1, m_mp3.sampleRate);  // ~1 s
        m_seekPoints.clear();

        for (;;) {
            const uint64_t bytePos = m_mp3.streamCursor - m_mp3.dataSize;
            const drmp3_uint32 frames = drmp3_decode_next_frame_ex(&m_mp3, nullptr, nullptr, nullptr);
            if (frames == 0) break;
            recent[0] = recent[1];
            recent[1] = recent[2];
            recent[2] = FrameInfo{bytePos, running};
            if (frameIndex >= static_cast<uint64_t>(kLeading) && running >= nextTarget) {
                drmp3_seek_point point{};
                point.seekPosInBytes = recent[0].bytePos;
                point.pcmFrameIndex = recent[2].pcmStart;
                point.mp3FramesToDiscard = kLeading;
                point.pcmFramesToDiscard = static_cast<drmp3_uint16>(recent[2].pcmStart - recent[1].pcmStart);
                m_seekPoints.push_back(point);
                nextTarget = running + stride;
            }
            running += frames;
            ++frameIndex;
        }
        if (running == 0) return false;

        // With a Xing/Info header dr_mp3 trims encoder delay and padding.
        m_totalFrames = m_mp3.totalPCMFrameCount != DRMP3_UINT64_MAX
            ? drmp3_get_pcm_frame_count(&m_mp3)
            : running;
        if (m_totalFrames == 0) m_totalFrames = running;

        if (!drmp3_seek_to_start_of_stream(&m_mp3)) return false;
        if (!m_seekPoints.empty()) {
            drmp3_bind_seek_table(&m_mp3, static_cast<drmp3_uint32>(m_seekPoints.size()), m_seekPoints.data());
        }
        return true;
    }

    static drmp3_bool32 onSeek(void* user, int offset, drmp3_seek_origin origin) {
        return FileSource::onSeekImpl(user, offset, static_cast<int>(origin)) ? DRMP3_TRUE : DRMP3_FALSE;
    }
    static drmp3_bool32 onTell(void* user, drmp3_int64* cursor) {
        int64_t value = 0;
        FileSource::onTellImpl(user, &value);
        *cursor = value;
        return DRMP3_TRUE;
    }
};

} // namespace decoders
} // namespace audio_engine
