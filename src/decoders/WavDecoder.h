#pragma once
#include "IAudioDecoder.h"
#include <unistd.h>

#define DR_WAV_IMPLEMENTATION
#include "dr_wav.h"

class WavDecoder : public audio_engine::decoders::IAudioDecoder {
public:
    WavDecoder() : m_fd(-1) {}

    ~WavDecoder() override {
        if (m_initialized) {
            drwav_uninit(&wavFrame);
        }
        if (m_fd != -1) {
            close(m_fd);
        }
    }

    bool open(const std::string& filepath) override {
        m_initialized = drwav_init_file(&wavFrame, filepath.c_str(), nullptr);
        return m_initialized;
    }

    bool openFd(int fd) override {
        m_fd = dup(fd);
        m_initialized = drwav_init(&wavFrame, onRead, onSeek, onTell, &m_fd, nullptr);
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
        drwav_uint64 framesDecoded = drwav_read_pcm_frames_f32(&wavFrame, framesToRead, tempBuffer.data());

        if (framesDecoded == 0) return 0;

        for (uint32_t ch = 0; ch < channels; ++ch) {
            double* dest = buffer.getWritePointer(ch);
            for (size_t i = 0; i < framesDecoded; ++i) {
                dest[i] = static_cast<double>(tempBuffer[i * channels + ch]);
            }
        }
        return static_cast<size_t>(framesDecoded);
    }

    uint32_t getSampleRate() const override { return m_initialized ? wavFrame.sampleRate : 0; }
    size_t getNumChannels() const override { return m_initialized ? wavFrame.channels : 0; }
    uint64_t getTotalFrames() const override { return m_initialized ? wavFrame.totalPCMFrameCount : 0; }

private:
    drwav wavFrame;
    bool m_initialized = false;
    int m_fd;

    static size_t onRead(void* pUserData, void* pBufferOut, size_t bytesToRead) {
        int fd = *static_cast<int*>(pUserData);
        ssize_t bytesRead = read(fd, pBufferOut, bytesToRead);
        return bytesRead > 0 ? static_cast<size_t>(bytesRead) : 0;
    }

    static drwav_bool32 onSeek(void* pUserData, int offset, drwav_seek_origin origin) {
        int fd = *static_cast<int*>(pUserData);
        int whence = SEEK_SET;
        if (origin == DRWAV_SEEK_CUR) whence = SEEK_CUR;
        if (origin == DRWAV_SEEK_END) whence = SEEK_END;
        
        off_t newPos = lseek(fd, offset, whence);
        return newPos >= 0 ? DRWAV_TRUE : DRWAV_FALSE;
    }

    static drwav_bool32 onTell(void* pUserData, drwav_int64* pCursor) {
        int fd = *static_cast<int*>(pUserData);
        off_t current = lseek(fd, 0, SEEK_CUR);
        if (current < 0) return DRWAV_FALSE;
        *pCursor = static_cast<drwav_int64>(current);
        return DRWAV_TRUE;
    }
};
