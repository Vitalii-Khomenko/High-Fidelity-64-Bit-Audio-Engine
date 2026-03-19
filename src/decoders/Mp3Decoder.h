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

    bool openFd(int fd) override {
        m_fd = dup(fd);
        m_initialized = drmp3_init(&mp3Frame, onRead, onSeek, onTell, nullptr, &m_fd, nullptr);
        if (!m_initialized) {
            close(m_fd);
            m_fd = -1;
            return false;
        }
        // Cache total frames ONCE on open to avoid repeated file scanning during playback.
        // drmp3_get_pcm_frame_count() scans the entire file and moves the read position,
        // so calling it repeatedly (e.g. every 500ms from UI) causes data races and corrupts playback.
        m_totalFrames = drmp3_get_pcm_frame_count(&mp3Frame);
        // Reset position back to the beginning after scanning
        drmp3_seek_to_pcm_frame(&mp3Frame, 0);
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

    bool seekToFrame(uint64_t targetFrame) override { return m_initialized ? drmp3_seek_to_pcm_frame(&mp3Frame, targetFrame) == DRMP3_TRUE : false; }

    uint32_t getSampleRate() const override { return m_initialized ? mp3Frame.sampleRate : 0; }
    size_t getNumChannels() const override { return m_initialized ? mp3Frame.channels : 0; }
    uint32_t getBitsPerSample() const override { return 16; } // MP3 is always 16-bit encoded
    uint64_t getTotalFrames() const override { return m_initialized ? m_totalFrames : 0; }
    uint64_t getCurrentFrame() const override { return m_initialized ? mp3Frame.currentPCMFrame : 0; }

private:
    mutable drmp3 mp3Frame;
    bool m_initialized = false;
    int m_fd;
    uint64_t m_totalFrames = 0;

    static size_t onRead(void* pUserData, void* pBufferOut, size_t bytesToRead) {
        int fd = *static_cast<int*>(pUserData);
        ssize_t bytesRead = read(fd, pBufferOut, bytesToRead);
        return bytesRead > 0 ? static_cast<size_t>(bytesRead) : 0;
    }

    static drmp3_bool32 onSeek(void* pUserData, int offset, drmp3_seek_origin origin) {
        int fd = *static_cast<int*>(pUserData);
        int whence = SEEK_SET;
        if (origin == DRMP3_SEEK_CUR) whence = SEEK_CUR;
        if (origin == DRMP3_SEEK_END) whence = SEEK_END;
        
        off_t newPos = lseek(fd, offset, whence);
        return newPos >= 0 ? DRMP3_TRUE : DRMP3_FALSE;
    }

    static drmp3_bool32 onTell(void* pUserData, drmp3_int64* pCursor) {
        int fd = *static_cast<int*>(pUserData);
        off_t current = lseek(fd, 0, SEEK_CUR);
        if (current < 0) return DRMP3_FALSE;
        *pCursor = static_cast<drmp3_int64>(current);
        return DRMP3_TRUE;
    }
};
