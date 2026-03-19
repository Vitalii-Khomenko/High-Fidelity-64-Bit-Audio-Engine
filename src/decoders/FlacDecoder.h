#pragma once
#include "IAudioDecoder.h"
#include <unistd.h>

// Define implementations exactly once here
#define DR_FLAC_IMPLEMENTATION
#include "dr_flac.h"

class FlacDecoder : public audio_engine::decoders::IAudioDecoder {
public:
    FlacDecoder() : pFlac(nullptr), m_fd(-1) {}

    ~FlacDecoder() override {
        if (pFlac) {
            drflac_close(pFlac);
        }
        if (m_fd != -1) {
            close(m_fd);
        }
    }

    bool open(const std::string& filepath) override {
        // Fallback for paths
        pFlac = drflac_open_file(filepath.c_str(), nullptr);
        return pFlac != nullptr;
    }

    bool openFd(int fd) override {
        m_fd = dup(fd); // duplicate FD to own its lifecycle
        pFlac = drflac_open(onRead, onSeek, onTell, &m_fd, nullptr);
        if (!pFlac) {
            close(m_fd);
            m_fd = -1;
            return false;
        }
        return true;
    }

    size_t readFrames(audio_engine::core::AudioBuffer& buffer, size_t framesToRead) override {
        if (!pFlac) return 0;
        
        uint32_t channels = getNumChannels();
        std::vector<int32_t> tempBuffer(framesToRead * channels);
        drflac_uint64 framesDecoded = drflac_read_pcm_frames_s32(pFlac, framesToRead, tempBuffer.data());

        if (framesDecoded == 0) return 0;

        double scale = 1.0 / 2147483648.0; 
        for (uint32_t ch = 0; ch < channels; ++ch) {
            double* dest = buffer.getWritePointer(ch);
            for (size_t i = 0; i < framesDecoded; ++i) {
                dest[i] = tempBuffer[i * channels + ch] * scale;
            }
        }
        return static_cast<size_t>(framesDecoded);
    }

    bool seekToFrame(uint64_t targetFrame) override { return pFlac ? drflac_seek_to_pcm_frame(pFlac, targetFrame) == DRFLAC_TRUE : false; }

    uint32_t getSampleRate() const override { return pFlac ? pFlac->sampleRate : 0; }
    size_t getNumChannels() const override { return pFlac ? pFlac->channels : 0; }
    uint64_t getTotalFrames() const override { return pFlac ? pFlac->totalPCMFrameCount : 0; }

private:
    drflac* pFlac;
    int m_fd;

    static size_t onRead(void* pUserData, void* pBufferOut, size_t bytesToRead) {
        int fd = *static_cast<int*>(pUserData);
        ssize_t bytesRead = read(fd, pBufferOut, bytesToRead);
        return bytesRead > 0 ? static_cast<size_t>(bytesRead) : 0;
    }

    static drflac_bool32 onSeek(void* pUserData, int offset, drflac_seek_origin origin) {
        int fd = *static_cast<int*>(pUserData);
        int whence = SEEK_SET;
        if (origin == DRFLAC_SEEK_CUR) whence = SEEK_CUR;
        if (origin == DRFLAC_SEEK_END) whence = SEEK_END;
        
        off_t newPos = lseek(fd, offset, whence);
        return newPos >= 0 ? DRFLAC_TRUE : DRFLAC_FALSE;
    }

    static drflac_bool32 onTell(void* pUserData, drflac_int64* pCursor) {
        int fd = *static_cast<int*>(pUserData);
        off_t current = lseek(fd, 0, SEEK_CUR);
        if (current < 0) return DRFLAC_FALSE;
        *pCursor = static_cast<drflac_int64>(current);
        return DRFLAC_TRUE;
    }
};
