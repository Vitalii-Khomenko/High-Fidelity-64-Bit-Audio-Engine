#pragma once
#include "IAudioDecoder.h"

#define DR_MP3_IMPLEMENTATION
#include "dr_mp3.h"

class Mp3Decoder : public audio_engine::decoders::IAudioDecoder {
public:
    Mp3Decoder() {}

    ~Mp3Decoder() override {
        if (m_isOpened) {
            drmp3_uninit(&m_mp3);
        }
    }

    bool open(const std::string& filepath) override {
        m_isOpened = drmp3_init_file(&m_mp3, filepath.c_str(), nullptr);
        return m_isOpened;
    }

    size_t readFrames(audio_engine::core::AudioBuffer& buffer, size_t framesToRead) override {
        if (!m_isOpened) return 0;
        
        uint32_t channels = getNumChannels();
        std::vector<float> tempBuffer(framesToRead * channels);
        
        drmp3_uint64 framesDecoded = drmp3_read_pcm_frames_f32(&m_mp3, framesToRead, tempBuffer.data());
        
        if (framesDecoded == 0) return 0;

        for (uint32_t ch = 0; ch < channels; ++ch) {
            double* dest = buffer.getWritePointer(ch);
            for (size_t i = 0; i < framesDecoded; ++i) {
                dest[i] = static_cast<double>(tempBuffer[i * channels + ch]);
            }
        }
        
        return static_cast<size_t>(framesDecoded);
    }

    uint32_t getSampleRate() const override { return m_isOpened ? m_mp3.sampleRate : 0; }
    size_t getNumChannels() const override { return m_isOpened ? m_mp3.channels : 0; }
    uint64_t getTotalFrames() const override { return m_isOpened ? drmp3_get_pcm_frame_count(&m_mp3) : 0; }

private:
    mutable drmp3 m_mp3;
    bool m_isOpened = false;
};
