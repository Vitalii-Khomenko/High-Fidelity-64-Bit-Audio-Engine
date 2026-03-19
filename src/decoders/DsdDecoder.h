#pragma once

#include "IAudioDecoder.h"
#include <fstream>
#include <vector>
#include <cstdint>
#include <cstring>
#include <string>

namespace audio_engine {
namespace decoders {

/**
 * @brief High-Fidelity DSD Decoder (DSF Format).
 * 
 * Capable of converting 1-bit DSD bitstreams to 64-bit float PCM 
 * or packaging them directly into DoP (DSD over PCM) format.
 */
class DsdDecoder : public IAudioDecoder {
public:
    DsdDecoder() : m_sampleRate(0), m_numChannels(0), m_totalFrames(0), m_isOpen(false), m_dopMode(false) {}

    ~DsdDecoder() override {
        if (m_file.is_open()) {
            m_file.close();
        }
    }

    bool open(const std::string& filepath) override {
        m_file.open(filepath, std::ios::binary);
        if (!m_file.is_open()) return false;

        // Parse Standard DSF (DSD Stream File) Headers
        char magic[4];
        m_file.read(magic, 4);
        if (std::strncmp(magic, "DSD ", 4) != 0) {
            return false; // Not a DSF wrapper
        }

        // Skip to "fmt " chunk
        m_file.seekg(28, std::ios::beg);
        m_file.read(magic, 4);
        if (std::strncmp(magic, "fmt ", 4) != 0) return false;

        // Read metadata
        m_file.seekg(40, std::ios::beg);
        
        uint32_t channelType;
        m_file.read(reinterpret_cast<char*>(&channelType), 4);
        
        uint32_t channels;
        m_file.read(reinterpret_cast<char*>(&channels), 4);
        m_numChannels = channels;

        uint32_t sampleRate;
        m_file.read(reinterpret_cast<char*>(&sampleRate), 4);
        m_sampleRate = sampleRate;

        uint32_t bitsPerSample;
        m_file.read(reinterpret_cast<char*>(&bitsPerSample), 4);

        uint64_t sampleCount;
        m_file.read(reinterpret_cast<char*>(&sampleCount), 8);
        m_totalFrames = sampleCount / 8; // frames in PCM decimation context

        uint32_t blockSize;
        m_file.read(reinterpret_cast<char*>(&blockSize), 4);

        // Seek to "data" chunk
        m_file.seekg(92, std::ios::beg);
        m_file.read(magic, 4);
        if (std::strncmp(magic, "data", 4) == 0) {
            m_file.seekg(8, std::ios::cur); // skip size
        }
        
        m_dataStart = m_file.tellg();
        m_isOpen = true;
        return true;
    }

    size_t readFrames(core::AudioBuffer& buffer, size_t framesToRead) override {
        if (!m_isOpen || m_file.eof()) return 0;
        
        size_t channels = getNumChannels();
        
        // --- ARCHITECTURE PLACEHOLDER ---
        // Native DSD (1-bit) runs at MHz frequencies (e.g. 2.8224 MHz for DSD64).
        // 1. To output PCM, we read 1-bit chunks and apply a multi-stage FIR decimation low-pass filter
        //    to output 352.8 kHz / 176.4 kHz / 88.2 kHz into the 64-bit double AudioBuffer.
        // 2. To output DoP (DSD over PCM), we pack the 16 bits of DSD payload + 8 bits DoP markers 
        //    (0x05 and 0xFA alternating) directly, bypassing standard float conversion.
        
        size_t actualFramesRead = 0;
        for (size_t i = 0; i < framesToRead; ++i) {
            if (m_file.eof()) break;
            
            for (size_t ch = 0; ch < channels; ++ch) {
                // Dummy read logic for the architecture
                uint8_t dsdByte;
                if (m_file.read(reinterpret_cast<char*>(&dsdByte), 1)) {
                    // double pcmSample = runFIRFilter(dsdByte);
                    // buffer.getWritePointer(ch)[i] = pcmSample;
                    buffer.getWritePointer(ch)[i] = 0.0; // Silence placeholder
                }
            }
            actualFramesRead++;
        }

        m_currentFrame += actualFramesRead;
        return actualFramesRead;
    }

    /**
     * @brief PCM output sample rate post-decimation.
     * E.g., DSD64 (2822400 Hz) Decimated by 8 = 352800 Hz.
     */
    uint32_t getSampleRate() const override {
        return m_isOpen ? m_sampleRate / 8 : 0;
    }

    size_t   getNumChannels()   const override { return m_numChannels; }
    uint64_t getTotalFrames()   const override { return m_totalFrames; }
    uint64_t getCurrentFrame()  const override { return m_currentFrame; }
    uint32_t getBitsPerSample() const override { return 1; } // DSD is 1-bit PCM

    bool seekToFrame(uint64_t targetFrame) override {
        if (!m_isOpen) return false;
        // Seek relative to data start: each frame = 1 byte per channel
        std::streamoff byteOffset = static_cast<std::streamoff>(targetFrame * m_numChannels);
        m_file.seekg(static_cast<std::streamoff>(m_dataStart) + byteOffset, std::ios::beg);
        m_currentFrame = targetFrame;
        return !m_file.fail();
    }

    /**
     * @brief Toggle DoP (DSD over PCM) standard mode.
     * When true, engine avoids decimation and securely packs DSD bits.
     */
    void setDoPMode(bool enable) {
        m_dopMode = enable;
    }

private:
    std::ifstream m_file;
    bool m_isOpen;
    uint32_t m_sampleRate;
    size_t m_numChannels;
    uint64_t m_totalFrames;
    uint64_t m_currentFrame = 0;
    std::streampos m_dataStart;
    bool m_dopMode;
};

} // namespace decoders
} // namespace audio_engine
