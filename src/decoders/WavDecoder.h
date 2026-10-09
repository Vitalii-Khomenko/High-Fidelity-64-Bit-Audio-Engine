#pragma once

#include "FileSource.h"
#include "IAudioDecoder.h"

#include <algorithm>
#include <vector>

#define DR_WAV_IMPLEMENTATION
#include "dr_wav.h"

namespace audio_engine {
namespace decoders {

/** WAV, RF64, W64 and AIFF/AIFC (PCM, IEEE float, A-law, mu-law, ADPCM). */
class WavDecoder : public IAudioDecoder {
public:
    ~WavDecoder() override { closeDecoder(); }

    bool openFd(int fd) override {
        closeDecoder();
        if (!m_source.open(fd)) return false;
        m_initialized = drwav_init(&m_wav, &FileSource::onRead, onSeek, onTell, &m_source, nullptr) == DRWAV_TRUE;
        if (!m_initialized || m_wav.channels == 0 || m_wav.sampleRate == 0) {
            closeDecoder();
            return false;
        }
        m_currentFrame = 0;
        return true;
    }

    size_t readFrames(core::AudioBuffer& buffer, size_t framesToRead) override {
        if (!m_initialized) return 0;
        const size_t channels = m_wav.channels;
        if (buffer.getNumChannels() < channels) return 0;
        framesToRead = std::min(framesToRead, buffer.getNumFrames());
        const size_t samples = framesToRead * channels;
        size_t decoded = 0;

        if (m_wav.translatedFormatTag == DR_WAVE_FORMAT_IEEE_FLOAT && m_wav.bitsPerSample == 64) {
            // Native read handles both little- and big-endian containers.
            if (m_double.size() < samples) m_double.resize(samples);
            decoded = static_cast<size_t>(drwav_read_pcm_frames(&m_wav, framesToRead, m_double.data()));
            for (size_t ch = 0; ch < channels; ++ch) {
                double* dst = buffer.getWritePointer(ch);
                for (size_t i = 0; i < decoded; ++i) dst[i] = m_double[i * channels + ch];
            }
        } else if (m_wav.translatedFormatTag == DR_WAVE_FORMAT_PCM) {
            // Integer PCM up to 32 bits is exact in s32 and then in double.
            if (m_int.size() < samples) m_int.resize(samples);
            decoded = static_cast<size_t>(drwav_read_pcm_frames_s32(&m_wav, framesToRead, m_int.data()));
            constexpr double scale = 1.0 / 2147483648.0;
            for (size_t ch = 0; ch < channels; ++ch) {
                double* dst = buffer.getWritePointer(ch);
                for (size_t i = 0; i < decoded; ++i) dst[i] = m_int[i * channels + ch] * scale;
            }
        } else {
            if (m_float.size() < samples) m_float.resize(samples);
            decoded = static_cast<size_t>(drwav_read_pcm_frames_f32(&m_wav, framesToRead, m_float.data()));
            for (size_t ch = 0; ch < channels; ++ch) {
                double* dst = buffer.getWritePointer(ch);
                for (size_t i = 0; i < decoded; ++i) dst[i] = m_float[i * channels + ch];
            }
        }
        m_currentFrame += decoded;
        return decoded;
    }

    bool seekToFrame(uint64_t frame) override {
        if (!m_initialized || drwav_seek_to_pcm_frame(&m_wav, frame) != DRWAV_TRUE) return false;
        m_currentFrame = frame;
        return true;
    }

    uint32_t getSampleRate() const override { return m_initialized ? m_wav.sampleRate : 0; }
    size_t getNumChannels() const override { return m_initialized ? m_wav.channels : 0; }
    uint32_t getBitsPerSample() const override { return m_initialized ? m_wav.bitsPerSample : 0; }
    uint64_t getTotalFrames() const override { return m_initialized ? m_wav.totalPCMFrameCount : 0; }
    uint64_t getCurrentFrame() const override { return m_currentFrame; }
    Codec getCodec() const override {
        return m_initialized && m_wav.container == drwav_container_aiff ? Codec::Aiff : Codec::Wav;
    }

private:
    FileSource m_source;
    drwav m_wav{};
    bool m_initialized = false;
    uint64_t m_currentFrame = 0;
    std::vector<int32_t> m_int;
    std::vector<float> m_float;
    std::vector<double> m_double;

    void closeDecoder() {
        if (m_initialized) drwav_uninit(&m_wav);
        m_initialized = false;
        m_source.close();
    }

    static drwav_bool32 onSeek(void* user, int offset, drwav_seek_origin origin) {
        return FileSource::onSeekImpl(user, offset, static_cast<int>(origin)) ? DRWAV_TRUE : DRWAV_FALSE;
    }
    static drwav_bool32 onTell(void* user, drwav_int64* cursor) {
        int64_t value = 0;
        FileSource::onTellImpl(user, &value);
        *cursor = value;
        return DRWAV_TRUE;
    }
};

} // namespace decoders
} // namespace audio_engine
