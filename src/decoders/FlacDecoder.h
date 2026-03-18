#pragma once
#include "IAudioDecoder.h"

// Define implementations exactly once here
#define DR_FLAC_IMPLEMENTATION
#include "dr_flac.h"

class FlacDecoder : public audio_engine::decoders::IAudioDecoder {
public:
    FlacDecoder() : pFlac(nullptr) {}

    ~FlacDecoder() override {
        if (pFlac) {
            drflac_close(pFlac);
        }
    }

    bool open(const std::string& filepath) override {
        pFlac = drflac_open_file(filepath.c_str(), nullptr);
        return pFlac != nullptr;
    }

    size_t readFrames(audio_engine::core::AudioBuffer& buffer, size_t framesToRead) override {
        if (!pFlac) return 0;
        
        uint32_t channels = getNumChannels();
        // Since we want double precision, we decode to interleaved 32-bit int or float and convert.
        // For highest audiophile precision, dr_flac can decode direct to std::int32_t.
        std::vector<int32_t> tempBuffer(framesToRead * channels);
        
        drflac_uint64 framesDecoded = drflac_read_pcm_frames_s32(pFlac, framesToRead, tempBuffer.data());
        
        if (framesDecoded == 0) return 0;

        // Convert the interleaved S32 to separated 64-bit float channels
        double scale = 1.0 / 2147483648.0; // max int32
        for (uint32_t ch = 0; ch < channels; ++ch) {
            double* dest = buffer.getWritePointer(ch);
            for (size_t i = 0; i < framesDecoded; ++i) {
                dest[i] = tempBuffer[i * channels + ch] * scale;
            }
        }
        
        return static_cast<size_t>(framesDecoded);
    }

    uint32_t getSampleRate() const override { return pFlac ? pFlac->sampleRate : 0; }
    size_t getNumChannels() const override { return pFlac ? pFlac->channels : 0; }
    uint64_t getTotalFrames() const override { return pFlac ? pFlac->totalPCMFrameCount : 0; }

private:
        drflac* pFlac;
};
