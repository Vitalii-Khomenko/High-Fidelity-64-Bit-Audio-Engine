#pragma once

#include "IAudioDecoder.h"
#include "../dsp/FirDesign.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <unistd.h>
#include <vector>

namespace audio_engine {
namespace decoders {

/**
 * DSD decoder for DSF (.dsf) and uncompressed DSDIFF (.dff) files.
 *
 * The 1-bit stream is converted to PCM with a two-stage linear-phase decimator:
 *
 *   1. A 128-tap Kaiser FIR evaluated with per-byte lookup tables. It produces
 *      one sample per DSD byte (fs / 8): 352.8 kHz for DSD64. Its stopband
 *      (> ~0.08 fs) suppresses the modulator noise that would alias into the
 *      audio band.
 *   2. A Kaiser FIR decimating to fs / 32 / (fs / 2.8224 MHz), i.e. always
 *      88.2 kHz for the 44.1 kHz family and 96 kHz for the 48 kHz family.
 *      Passband 0..24 kHz is flat, everything that would fold below 24 kHz is
 *      attenuated by more than 110 dB.
 *
 * Unity gain: a stream of all ones decodes to +1.0. Content at the SACD 0 dB
 * reference (50 % modulation) therefore peaks around -6 dBFS.
 */
class DsdDecoder : public IAudioDecoder {
public:
    static constexpr size_t kStage1Bytes = 16;                 // 128 taps
    static constexpr size_t kStage1Taps = kStage1Bytes * 8;
    static constexpr double kStage1Cutoff = 0.0517;           // of the DSD rate
    static constexpr size_t kStage2TapsPerPhase = 16;
    static constexpr uint8_t kSilenceByte = 0x69;             // balanced idle pattern

    DsdDecoder() = default;
    ~DsdDecoder() override { closeFile(); }

    bool openFd(int fd) override {
        closeFile();
        m_fd = ::dup(fd);
        if (m_fd < 0) return false;
        if (::lseek(m_fd, 0, SEEK_SET) < 0) { closeFile(); return false; }
        uint8_t magic[4] = {};
        if (!readExact(magic, 4)) { closeFile(); return false; }
        bool ok = false;
        if (std::memcmp(magic, "DSD ", 4) == 0) ok = parseDsf();
        else if (std::memcmp(magic, "FRM8", 4) == 0) ok = parseDff();
        if (!ok) { closeFile(); return false; }
        if (!prepareFilters()) { closeFile(); return false; }
        m_isOpen = true;
        return seekToFrame(0);
    }

    size_t readFrames(core::AudioBuffer& buffer, size_t framesToRead) override {
        if (!m_isOpen || buffer.getNumChannels() < m_channels) return 0;
        const uint64_t remaining = m_totalFrames - m_currentFrame;
        framesToRead = static_cast<size_t>(std::min<uint64_t>({
            framesToRead, buffer.getNumFrames(), remaining }));
        size_t produced = 0;
        while (produced < framesToRead) {
            for (size_t m = 0; m < m_decimation; ++m) {
                if (m_chunkPos >= m_chunkLen && !loadChunk()) {
                    m_currentFrame += produced;
                    return produced;
                }
                for (size_t ch = 0; ch < m_channels; ++ch) {
                    pushStage1(ch, m_chunk[ch][m_chunkPos]);
                }
                ++m_chunkPos;
                ++m_bytePos;
            }
            for (size_t ch = 0; ch < m_channels; ++ch) {
                buffer.getWritePointer(ch)[produced] = stage2Output(ch);
            }
            ++produced;
        }
        m_currentFrame += produced;
        return produced;
    }

    bool seekToFrame(uint64_t targetFrame) override {
        if (!m_isOpen) return false;
        targetFrame = std::min(targetFrame, m_totalFrames);
        resetFilters();
        // Pre-roll enough bytes to fill both filter histories so the first
        // sample after a seek is not a filter transient.
        const uint64_t targetByte = targetFrame * m_decimation;
        const uint64_t preRoll = kStage1Bytes + m_stage2Taps;
        m_bytePos = targetByte > preRoll ? targetByte - preRoll : 0;
        m_chunkLen = 0;
        m_chunkPos = 0;
        while (m_bytePos < targetByte) {
            if (m_chunkPos >= m_chunkLen && !loadChunk()) return false;
            for (size_t ch = 0; ch < m_channels; ++ch) pushStage1(ch, m_chunk[ch][m_chunkPos]);
            ++m_chunkPos;
            ++m_bytePos;
        }
        m_currentFrame = targetFrame;
        return true;
    }

    uint32_t getSampleRate() const override { return m_outputRate; }
    size_t getNumChannels() const override { return m_channels; }
    uint32_t getBitsPerSample() const override { return 1; }
    uint64_t getTotalFrames() const override { return m_totalFrames; }
    uint64_t getCurrentFrame() const override { return m_currentFrame; }
    Codec getCodec() const override { return m_isDff ? Codec::Dff : Codec::Dsf; }
    uint32_t getDsdRate() const override { return m_dsdRate; }

    /** File offset of the DSF ID3v2 metadata chunk, 0 if absent. */
    uint64_t getMetadataOffset() const { return m_metadataOffset; }

private:
    struct ChannelFilter {
        std::array<uint8_t, kStage1Bytes> history{};
        size_t historyPos = 0;
        std::vector<double> stage2;   // doubled ring buffer for contiguous dot products
        size_t stage2Pos = 0;
    };

    int m_fd = -1;
    bool m_isOpen = false;
    bool m_isDff = false;

    uint32_t m_dsdRate = 0;
    uint32_t m_outputRate = 0;
    size_t m_channels = 0;
    size_t m_decimation = 0;         // DSD bytes per output frame
    uint64_t m_bytesPerChannel = 0;
    uint64_t m_totalFrames = 0;
    uint64_t m_currentFrame = 0;
    uint64_t m_bytePos = 0;          // per-channel byte index of the next byte to filter
    uint64_t m_metadataOffset = 0;

    // DSF: block interleaved. DFF: byte interleaved.
    uint64_t m_dataOffset = 0;
    uint32_t m_blockSize = 4096;
    static constexpr size_t kDffChunkBytes = 4096;

    std::vector<std::vector<uint8_t>> m_chunk;   // per channel, MSB-first bytes
    std::vector<uint8_t> m_raw;
    size_t m_chunkLen = 0;
    size_t m_chunkPos = 0;

    std::array<std::array<double, 256>, kStage1Bytes> m_stage1Lut{};
    std::vector<double> m_stage2Coefficients;   // reversed for a forward dot product
    size_t m_stage2Taps = 0;
    std::vector<ChannelFilter> m_filters;
    std::array<uint8_t, 256> m_bitReverse{};

    void closeFile() {
        if (m_fd >= 0) { ::close(m_fd); m_fd = -1; }
        m_isOpen = false;
    }

    bool readExact(void* dst, size_t bytes) {
        auto* out = static_cast<uint8_t*>(dst);
        while (bytes > 0) {
            const ssize_t n = ::read(m_fd, out, bytes);
            if (n <= 0) return false;
            out += n;
            bytes -= static_cast<size_t>(n);
        }
        return true;
    }

    bool preadExact(void* dst, size_t bytes, uint64_t offset) {
        auto* out = static_cast<uint8_t*>(dst);
        while (bytes > 0) {
            const ssize_t n = ::pread(m_fd, out, bytes, static_cast<off_t>(offset));
            if (n <= 0) return false;
            out += n;
            offset += static_cast<uint64_t>(n);
            bytes -= static_cast<size_t>(n);
        }
        return true;
    }

    static uint32_t readLE32(const uint8_t* p) {
        return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8)
             | (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
    }
    static uint64_t readLE64(const uint8_t* p) {
        return static_cast<uint64_t>(readLE32(p)) | (static_cast<uint64_t>(readLE32(p + 4)) << 32);
    }
    static uint32_t readBE32(const uint8_t* p) {
        return (static_cast<uint32_t>(p[0]) << 24) | (static_cast<uint32_t>(p[1]) << 16)
             | (static_cast<uint32_t>(p[2]) << 8) | static_cast<uint32_t>(p[3]);
    }
    static uint64_t readBE64(const uint8_t* p) {
        return (static_cast<uint64_t>(readBE32(p)) << 32) | static_cast<uint64_t>(readBE32(p + 4));
    }

    static bool validDsdRate(uint32_t rate) {
        return rate >= 1000000 && rate <= 50000000 && rate % 8 == 0;
    }

    // ── DSF ───────────────────────────────────────────────────────────────────
    bool parseDsf() {
        uint8_t buf[40];
        // DSD chunk: id(4, already read) size(8) fileSize(8) metadataPointer(8)
        if (!readExact(buf, 24) || readLE64(buf) != 28) return false;
        m_metadataOffset = readLE64(buf + 16);

        if (!readExact(buf, 12) || std::memcmp(buf, "fmt ", 4) != 0) return false;
        const uint64_t fmtSize = readLE64(buf + 4);
        if (fmtSize != 52) return false;
        if (!readExact(buf, 40)) return false;
        const uint32_t version = readLE32(buf);
        const uint32_t formatId = readLE32(buf + 4);
        const uint32_t channels = readLE32(buf + 12);
        const uint32_t rate = readLE32(buf + 16);
        const uint32_t bits = readLE32(buf + 20);
        const uint64_t sampleCount = readLE64(buf + 24);
        const uint32_t blockSize = readLE32(buf + 32);
        if (version != 1 || formatId != 0 || bits != 1) return false;
        if (channels == 0 || channels > 8 || !validDsdRate(rate)) return false;
        if (blockSize == 0 || blockSize > (1u << 20) || sampleCount == 0) return false;

        if (!readExact(buf, 12) || std::memcmp(buf, "data", 4) != 0) return false;
        const uint64_t dataChunkSize = readLE64(buf + 4);
        if (dataChunkSize < 12) return false;
        const off_t dataOffset = ::lseek(m_fd, 0, SEEK_CUR);
        const off_t fileEnd = ::lseek(m_fd, 0, SEEK_END);
        if (dataOffset < 0 || fileEnd < dataOffset) return false;
        const uint64_t dataBytes = dataChunkSize - 12;
        if (dataBytes > static_cast<uint64_t>(fileEnd - dataOffset)) return false;

        const uint64_t bytesPerChannel = sampleCount / 8;
        const uint64_t blocks = (bytesPerChannel + blockSize - 1) / blockSize;
        if (blocks == 0 || blocks > dataBytes / (static_cast<uint64_t>(blockSize) * channels)) return false;

        m_isDff = false;
        m_channels = channels;
        m_dsdRate = rate;
        m_blockSize = blockSize;
        m_dataOffset = static_cast<uint64_t>(dataOffset);
        m_bytesPerChannel = bytesPerChannel;
        return true;
    }

    // ── DSDIFF ────────────────────────────────────────────────────────────────
    bool parseDff() {
        uint8_t buf[16];
        if (!readExact(buf, 12) || std::memcmp(buf + 8, "DSD ", 4) != 0) return false;
        const off_t fileEnd = ::lseek(m_fd, 0, SEEK_END);
        if (fileEnd < 16) return false;
        const uint64_t formSize = readBE64(buf);
        if (formSize < 4 || formSize > static_cast<uint64_t>(fileEnd) - 12) return false;
        const uint64_t formEnd = 12 + formSize;

        uint32_t rate = 0;
        uint32_t channels = 0;
        bool uncompressed = false;
        uint64_t dataOffset = 0;
        uint64_t dataBytes = 0;

        uint64_t pos = 16;
        for (int guard = 0; guard < 256 && pos + 12 <= formEnd; ++guard) {
            if (!preadExact(buf, 12, pos)) break;
            const uint64_t size = readBE64(buf + 4);
            if (size > formEnd - pos - 12) return false;
            const uint64_t body = pos + 12;

            if (std::memcmp(buf, "PROP", 4) == 0 && size >= 4) {
                uint8_t type[4];
                if (!preadExact(type, 4, body) || std::memcmp(type, "SND ", 4) != 0) return false;
                uint64_t propPos = body + 4;
                const uint64_t propEnd = body + size;
                for (int g = 0; g < 64 && propPos + 12 <= propEnd; ++g) {
                    uint8_t sub[12];
                    if (!preadExact(sub, 12, propPos)) return false;
                    const uint64_t subSize = readBE64(sub + 4);
                    if (subSize > propEnd - propPos - 12) return false;
                    const uint64_t subBody = propPos + 12;
                    uint8_t value[4];
                    if (std::memcmp(sub, "FS  ", 4) == 0 && subSize >= 4) {
                        if (!preadExact(value, 4, subBody)) return false;
                        rate = readBE32(value);
                    } else if (std::memcmp(sub, "CHNL", 4) == 0 && subSize >= 2) {
                        if (!preadExact(value, 2, subBody)) return false;
                        channels = (static_cast<uint32_t>(value[0]) << 8) | value[1];
                    } else if (std::memcmp(sub, "CMPR", 4) == 0 && subSize >= 4) {
                        if (!preadExact(value, 4, subBody)) return false;
                        uncompressed = std::memcmp(value, "DSD ", 4) == 0;
                    }
                    propPos = subBody + ((subSize + 1) & ~1ULL);
                }
            } else if (std::memcmp(buf, "DSD ", 4) == 0) {
                dataOffset = body;
                dataBytes = size;
            }
            pos = body + ((size + 1) & ~1ULL);
        }

        if (!validDsdRate(rate) || channels == 0 || channels > 8 || !uncompressed || dataOffset == 0) {
            return false;
        }
        m_isDff = true;
        m_channels = channels;
        m_dsdRate = rate;
        m_dataOffset = dataOffset;
        m_bytesPerChannel = dataBytes / channels;
        m_metadataOffset = 0;
        return m_bytesPerChannel > 0;
    }

    // ── Filters ───────────────────────────────────────────────────────────────
    bool prepareFilters() {
        const uint32_t stage1Rate = m_dsdRate / 8;
        const uint32_t familyBase = (m_dsdRate % 44100 == 0) ? 88200 : 96000;
        m_decimation = std::max<size_t>(1, stage1Rate / familyBase);
        while (m_decimation > 1 && stage1Rate % m_decimation != 0) --m_decimation;
        m_outputRate = static_cast<uint32_t>(stage1Rate / m_decimation);
        if (m_outputRate == 0) return false;
        m_totalFrames = m_bytesPerChannel / m_decimation;
        if (m_totalFrames == 0) return false;

        for (int v = 0; v < 256; ++v) {
            uint8_t r = 0;
            for (int b = 0; b < 8; ++b) if (v & (1 << b)) r |= static_cast<uint8_t>(0x80 >> b);
            m_bitReverse[v] = r;
        }

        // Stage 1: newest bit is the LSB of the newest (MSB-first) byte.
        const std::vector<double> h1 = dsp::designKaiserLowpass(kStage1Taps, kStage1Cutoff, 120.0);
        for (size_t j = 0; j < kStage1Bytes; ++j) {
            for (int v = 0; v < 256; ++v) {
                double acc = 0.0;
                for (int bit = 0; bit < 8; ++bit) {
                    const double sample = (v & (1 << bit)) ? 1.0 : -1.0;
                    acc += h1[j * 8 + static_cast<size_t>(bit)] * sample;
                }
                m_stage1Lut[j][static_cast<size_t>(v)] = acc;
            }
        }

        // Stage 2: cutoff at half the output rate, transition 24 kHz .. out - 24 kHz.
        m_stage2Taps = kStage2TapsPerPhase * m_decimation;
        const double cutoff = 0.5 / static_cast<double>(m_decimation);
        std::vector<double> h2 = dsp::designKaiserLowpass(m_stage2Taps, cutoff, 110.0);
        m_stage2Coefficients.assign(h2.rbegin(), h2.rend());

        m_filters.assign(m_channels, ChannelFilter{});
        for (auto& filter : m_filters) filter.stage2.assign(m_stage2Taps * 2, 0.0);

        const size_t chunkBytes = m_isDff ? kDffChunkBytes : m_blockSize;
        m_chunk.assign(m_channels, std::vector<uint8_t>(chunkBytes));
        m_raw.resize(chunkBytes * m_channels);
        return true;
    }

    void resetFilters() {
        for (auto& filter : m_filters) {
            filter.history.fill(kSilenceByte);
            filter.historyPos = 0;
            std::fill(filter.stage2.begin(), filter.stage2.end(), 0.0);
            filter.stage2Pos = 0;
        }
    }

    inline void pushStage1(size_t ch, uint8_t msbFirstByte) {
        ChannelFilter& f = m_filters[ch];
        f.historyPos = (f.historyPos + 1) % kStage1Bytes;
        f.history[f.historyPos] = msbFirstByte;
        double acc = 0.0;
        size_t idx = f.historyPos;
        for (size_t j = 0; j < kStage1Bytes; ++j) {
            acc += m_stage1Lut[j][f.history[idx]];
            idx = idx == 0 ? kStage1Bytes - 1 : idx - 1;
        }
        // Store twice so the newest m_stage2Taps samples are always contiguous.
        f.stage2[f.stage2Pos] = acc;
        f.stage2[f.stage2Pos + m_stage2Taps] = acc;
        f.stage2Pos = (f.stage2Pos + 1) % m_stage2Taps;
    }

    inline double stage2Output(size_t ch) const {
        const ChannelFilter& f = m_filters[ch];
        // Oldest sample sits at stage2Pos. The filter is linear phase (symmetric),
        // so mirrored taps share one multiply.
        const double* x = f.stage2.data() + f.stage2Pos;
        const double* h = m_stage2Coefficients.data();
        const size_t n = m_stage2Taps;
        double acc = 0.0;
        for (size_t k = 0; k < n / 2; ++k) acc += h[k] * (x[k] + x[n - 1 - k]);
        if (n % 2) acc += h[n / 2] * x[n / 2];
        return acc;
    }

    // Loads the chunk containing m_bytePos into per-channel MSB-first buffers.
    bool loadChunk() {
        if (m_bytePos >= m_bytesPerChannel) return false;
        if (m_isDff) {
            const uint64_t bytes = std::min<uint64_t>(kDffChunkBytes, m_bytesPerChannel - m_bytePos);
            const size_t total = static_cast<size_t>(bytes) * m_channels;
            if (!preadExact(m_raw.data(), total, m_dataOffset + m_bytePos * m_channels)) return false;
            for (size_t i = 0; i < static_cast<size_t>(bytes); ++i) {
                for (size_t ch = 0; ch < m_channels; ++ch) m_chunk[ch][i] = m_raw[i * m_channels + ch];
            }
            m_chunkLen = static_cast<size_t>(bytes);
            m_chunkPos = 0;
            return true;
        }
        const uint64_t block = m_bytePos / m_blockSize;
        const size_t offset = static_cast<size_t>(m_bytePos % m_blockSize);
        const uint64_t groupBytes = static_cast<uint64_t>(m_blockSize) * m_channels;
        if (!preadExact(m_raw.data(), static_cast<size_t>(groupBytes), m_dataOffset + block * groupBytes)) {
            return false;
        }
        const uint64_t blockStart = block * m_blockSize;
        const size_t valid = static_cast<size_t>(std::min<uint64_t>(m_blockSize, m_bytesPerChannel - blockStart));
        for (size_t ch = 0; ch < m_channels; ++ch) {
            const uint8_t* src = m_raw.data() + ch * m_blockSize;
            for (size_t i = 0; i < valid; ++i) m_chunk[ch][i] = m_bitReverse[src[i]];
        }
        m_chunkLen = valid;
        m_chunkPos = offset;
        return m_chunkPos < m_chunkLen;
    }
};

} // namespace decoders
} // namespace audio_engine
