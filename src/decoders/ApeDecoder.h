#pragma once

#include "FileSource.h"
#include "IAudioDecoder.h"

#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <vector>

#include "All.h"
#include "IAPEIO.h"
#include "MACLib.h"

namespace audio_engine {
namespace decoders {

/** Monkey's Audio (.ape) through the official SDK. */
class ApeDecoder : public IAudioDecoder {
public:
    ~ApeDecoder() override { closeDecoder(); }

    bool openFd(int fd) override {
        closeDecoder();
        if (!m_source.open(fd)) return false;
        m_io = std::make_unique<SourceIO>(m_source);
        int error = 0;
        m_ape.reset(CreateIAPEDecompressEx(m_io.get(), &error));
        if (!m_ape || error != ERROR_SUCCESS) {
            closeDecoder();
            return false;
        }
        using I = APE::IAPEDecompress;
        const int64_t channels = m_ape->GetInfo(I::APE_INFO_CHANNELS);
        const int64_t rate = m_ape->GetInfo(I::APE_INFO_SAMPLE_RATE);
        const int64_t bits = m_ape->GetInfo(I::APE_INFO_BITS_PER_SAMPLE);
        const int64_t align = m_ape->GetInfo(I::APE_INFO_BLOCK_ALIGN);
        if (channels <= 0 || channels > 8 || rate <= 0 || (bits != 8 && bits != 16 && bits != 24 && bits != 32) ||
            align != channels * (bits / 8)) {
            closeDecoder();
            return false;
        }
        m_channels = static_cast<size_t>(channels);
        m_rate = static_cast<uint32_t>(rate);
        m_bits = static_cast<uint32_t>(bits);
        m_float = (m_ape->GetInfo(I::APE_INFO_FORMAT_FLAGS) & APE_FORMAT_FLAG_FLOATING_POINT) != 0 && bits == 32;
        const int64_t total = m_ape->GetInfo(I::APE_DECOMPRESS_TOTAL_BLOCKS);
        m_total = total > 0 ? static_cast<uint64_t>(total) : 0;
        m_position = 0;
        m_atEnd = false;
        return true;
    }

    size_t readFrames(core::AudioBuffer& buffer, size_t framesToRead) override {
        if (!m_ape || m_atEnd || buffer.getNumChannels() < m_channels) return 0;
        framesToRead = std::min(framesToRead, buffer.getNumFrames());
        const size_t bytesPerSample = m_bits / 8;
        const size_t frameBytes = bytesPerSample * m_channels;
        if (m_scratch.size() < framesToRead * frameBytes) m_scratch.resize(framesToRead * frameBytes);
        APE::int64 got = 0;
        if (m_ape->GetData(m_scratch.data(), static_cast<APE::int64>(framesToRead), &got) != ERROR_SUCCESS || got <= 0) {
            return 0;
        }
        const size_t frames = static_cast<size_t>(got);
        for (size_t ch = 0; ch < m_channels; ++ch) {
            double* dst = buffer.getWritePointer(ch);
            const uint8_t* p = m_scratch.data() + ch * bytesPerSample;
            for (size_t i = 0; i < frames; ++i, p += frameBytes) dst[i] = sample(p);
        }
        m_position += frames;
        return frames;
    }

    bool seekToFrame(uint64_t frame) override {
        if (!m_ape) return false;
        m_atEnd = m_total > 0 && frame >= m_total;
        if (m_atEnd) {
            m_position = m_total;
            return true;
        }
        if (m_ape->Seek(static_cast<APE::int64>(frame)) != ERROR_SUCCESS) return false;
        m_position = frame;
        return true;
    }

    uint32_t getSampleRate() const override { return m_ape ? m_rate : 0; }
    size_t getNumChannels() const override { return m_ape ? m_channels : 0; }
    uint32_t getBitsPerSample() const override { return m_bits; }
    uint64_t getTotalFrames() const override { return m_total; }
    uint64_t getCurrentFrame() const override { return m_position; }
    Codec getCodec() const override { return Codec::Ape; }
    FileSource* fileSource() override { return &m_source; }

private:
    /** IAPEIO over our FileSource (read-only). */
    class SourceIO : public APE::IAPEIO {
    public:
        explicit SourceIO(FileSource& source) : m_src(source) {}
        int Open(const APE::str_utfn*, bool) override { return ERROR_SUCCESS; }
        int Close() override { return ERROR_SUCCESS; }
        int Read(void* buffer, APE::int64 bytes, APE::int64* read) override {
            const size_t n = bytes > 0 ? m_src.read(buffer, static_cast<size_t>(bytes)) : 0;
            if (read) *read = static_cast<APE::int64>(n);
            return (n == 0 && bytes > 0) ? ERROR_IO_READ : ERROR_SUCCESS;
        }
        int Write(const void*, APE::int64, APE::int64* written) override {
            if (written) *written = 0;
            return ERROR_IO_WRITE;
        }
        int Seek(APE::int64 position, APE::SeekMethod method) override {
            // Like the SDK's own file I/O, an offset from the end counts backwards.
            if (method == APE::SeekFileEnd) return m_src.seek(-std::llabs(position), 2) ? 0 : -1;
            return m_src.seek(position, method == APE::SeekFileCurrent ? 1 : 0) ? 0 : -1;
        }
        int Create(const APE::str_utfn*) override { return ERROR_IO_WRITE; }
        int Delete() override { return ERROR_IO_WRITE; }
        int SetEOF() override { return ERROR_IO_WRITE; }
        unsigned char* GetBuffer(int*) override { return nullptr; }
        APE::int64 GetPosition() override { return static_cast<APE::int64>(m_src.tell()); }
        APE::int64 GetSize() override { return static_cast<APE::int64>(m_src.size()); }

    private:
        FileSource& m_src;
    };

    double sample(const uint8_t* p) const {
        switch (m_bits) {
            case 8: return (static_cast<int>(p[0]) - 128) / 128.0;   // WAV-style unsigned
            case 16: return static_cast<int16_t>(p[0] | (p[1] << 8)) / 32768.0;
            case 24: {
                int32_t v = p[0] | (p[1] << 8) | (p[2] << 16);
                if (v & 0x800000) v -= 0x1000000;
                return v / 8388608.0;
            }
            default: {
                uint32_t u = uint32_t(p[0]) | (uint32_t(p[1]) << 8) | (uint32_t(p[2]) << 16) | (uint32_t(p[3]) << 24);
                if (m_float) {
                    float f;
                    std::memcpy(&f, &u, sizeof(f));
                    return f;
                }
                return static_cast<int32_t>(u) / 2147483648.0;
            }
        }
    }

    void closeDecoder() {
        m_ape.reset();   // before the I/O object it reads from
        m_io.reset();
        m_source.close();
    }

    FileSource m_source;
    std::unique_ptr<SourceIO> m_io;
    std::unique_ptr<APE::IAPEDecompress> m_ape;
    size_t m_channels = 0;
    uint32_t m_rate = 0;
    uint32_t m_bits = 0;
    bool m_float = false;
    uint64_t m_total = 0;
    uint64_t m_position = 0;
    bool m_atEnd = false;
    std::vector<uint8_t> m_scratch;
};

} // namespace decoders
} // namespace audio_engine
