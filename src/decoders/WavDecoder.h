#pragma once
#include "IAudioDecoder.h"

#define DR_WAV_IMPLEMENTATION
#include "dr_wav.h"

class WavDecoder : public audio_engine::decoders::IAudioDecoder {
public:
    WavDecoder() {}

    ~WavDecoder() override {
        if (m_isOpened) {
            drwav_uninit(&m_wav);
        }
    }

    bool open(const std::string& filepath) override {
        m_isOpened = drwav_init_file(&m_wav, filepath.c_str(), nullptr);
        return m_isOpened;
    }

    size_t readFrames(audio_engine::core::AudioBuffer& buffer, size_t framesToRead) override {
        if (!m_isOpened) return 0;
        
        uint32_t channels = getNumChannels();
        std::vector<float> tempBuffer(framesToRead * channels);
        
        drwav_uint64 framesDecoded = drwav_read_pcm_frames_f32(&m_wav, framesToRead, tempBuffer.data());
        
        if (framesDecoded == 0) return 0;

        for (uint32_t ch = 0; ch < channels; ++ch) {
            double* dest = buffer.getWritePointer(ch);
            for (size_t i = 0; i < framesDecoded; ++i) {
                dest[i] = static_cast<double>(tempBuffer[i * channels + ch]);
            }
        }
        
        return static_cast<size_t>(framesDecoded);
    }

    uint32_t getSampleRate() const override { return m_isOpened ? m_wav.sampleRate : 0; }
    size_t getNumChannels() const override { return m_isOpened ? m_wav.channels : 0; }
    uint64_t getTotalFrames() const override { return m_isOpened ? m_wav.totalPCMFrameCount : 0; }

private:
        drwav m_wav;
        bool m_isOpened = false;
};
