#pragma once
#include "IAudioDecoder.h"
#include <unistd.h>

#define DR_MP3_IMPLEMENTATION
#include "dr_mp3.h"

class Mp3Decoder : public audio_engine::decoders::IAudioDecoder {
public:
    Mp3Decoder() : m_fd(-1) {}

    ~Mp3Decoder() override {
        if (m_initialized) {
            drmp3_uninit(&mp3Frame);
        }
        if (m_fd != -1) {
            close(m_fd);
        }
    }

    bool open(const std::string& filepath) override {
        m_initialized = drmp3_init_file(&mp3Frame, filepath.c_str(), nullptr);
        return m_initialized;
    }

    bool openFd(int fd) {
        m_fd = dup(fd);
        m_initialized = drmp3_init(&mp3Frame, onRead, onSeek, &m_fd, nullptr);
        if (!m_initialized) {
            close(m_fd);
            m_fd = -1;
            return false;
        }
        return true;
    }

    size_t readFrames(audio_engine::core::AudioBuffer& buffer, size_t framesToRead) override {
        if (!m_initialized) return 0;
        
        uint32_t channels = getNumChannels();
        std::vector<float> tempBuffer(framesToRead * channels);
        drmp3_uint64 framesDecoded = drmp3_read_pcm_frames_f32(&mp3Frame, framesToRead, tempBuffer.data());

        if (framesDecoded == 0) return 0;

        for (uint32_t ch = 0; ch < channels; ++ch) {
            double* dest = buffer.getWritePointer(ch);
            for (size_t i = 0; i < framesDecoded; ++i) {
                dest[i] = static_cast<double>(tempBuffer[i * channels + ch]);
            }
        }
        return static_cast<size_t>(framesDecoded);
    }

    uint32_t getSampleRate() const override { return m_initialized ? mp3Frame.sampleRate : 0; }
    size_t getNumChannels() const override { return m_initialized ? mp3Frame.channels : 0; }
    uint64_t getTotalFrames() const override { return m_initialized ? drmp3_get_pcm_frame_count(&mp3Frame) : 0; }

private:
    drmp3 mp3Frame;
    bool m_initialized = false;
    int m_fd;

    static size_t onRead(void* pUserData, void* pBufferOut, size_t bytesToRead) {
        int fd = *static_cast<int*>(pUserData);
        ssize_t bytesRead = read(fd, pBufferOut, bytesToRead);
        return bytesRead > 0 ? static_cast<size_t>(bytesRead) : 0;
    }

    static drmp3_bool32 onSeek(void* pUserData, int offset, drmp3_seek_origin origin) {
        int fd = *static_cast<int*>(pUserData);
        int whence = SEEK_SET;
        if (origin == drmp3_seek_origin_current) whence = SEEK_CUR;
        
        off_t newPos = lseek(fd, offset, whence);
        return newPos >= 0 ? DRMP3_TRUE : DRMP3_FALSE;
    }
};
