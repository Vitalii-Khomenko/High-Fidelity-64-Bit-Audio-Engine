#pragma once

#include "IAudioDecoder.h"
#include <unistd.h>
#include <cstring>
#include <cstdint>
#include <vector>
#include <array>
#include <algorithm>
#include <cmath>

namespace audio_engine {
namespace decoders {

/**
 * DSD Decoder — supports DSF (.dsf) and DSDIFF (.dff) formats.
 *
 * Converts 1-bit DSD bitstream → 64-bit double PCM via box-filter decimation:
 *   DSD64  (2 822 400 Hz / 16) → 176 400 Hz PCM  (DoP-rate, excellent quality)
 *   DSD128 (5 644 800 Hz / 16) → 352 800 Hz PCM
 *
 * DSF block structure: blocks of blockSize bytes per channel, interleaved
 *   as [Ch0 block][Ch1 block][Ch0 block][Ch1 block]...
 *   DSD bits within each byte are stored LSB-first.
 *
 * DSDIFF structure: straight interleaved bytes [Ch0_byte][Ch1_byte]...
 *   DSD bits within each byte are stored MSB-first.
 */
class DsdDecoder : public IAudioDecoder {
public:
    static constexpr int DECIM = 16; // 16 DSD bits → 1 PCM sample

    DsdDecoder()  = default;
    ~DsdDecoder() override { closeFile(); }

    // ── IAudioDecoder ─────────────────────────────────────────────────────────

    bool open(const std::string& /*filepath*/) override { return false; }

    bool openFd(int fd) override {
        closeFile();
        m_fd = fd;

        // Detect format by magic bytes
        uint8_t magic[4] = {};
        if (::read(m_fd, magic, 4) != 4) return false;

        if (::memcmp(magic, "DSD ", 4) == 0) {
            return parseDsf();
        } else if (::memcmp(magic, "FRM8", 4) == 0) {
            return parseDff();
        }
        return false;
    }

    size_t readFrames(audio_engine::core::AudioBuffer& buffer,
                      size_t framesToRead) override {
        if (!m_isOpen) return 0;
        size_t framesProduced = 0;

        for (size_t f = 0; f < framesToRead; ++f) {
            bool ok = true;
            for (size_t ch = 0; ch < m_numChannels && ok; ++ch) {
                double sum = 0.0;
                for (int b = 0; b < DECIM && ok; ++b) {
                    uint8_t bit = 0;
                    if (readDsdBit(ch, bit)) {
                        sum += bit ? 1.0 : -1.0;
                    } else {
                        ok = false;
                    }
                }
                if (double* ptr = buffer.getWritePointer(ch))
                    ptr[f] = sum / DECIM;
            }
            if (!ok) break;
            ++framesProduced;
        }

        m_currentPcmFrame += framesProduced;
        return framesProduced;
    }

    bool seekToFrame(uint64_t targetPcmFrame) override {
        if (!m_isOpen) return false;
        // Convert PCM frame → DSD bit position
        uint64_t targetDsdBit = targetPcmFrame * DECIM;

        if (m_isDff) {
            // DSDIFF: interleaved bytes, MSB-first
            // byte index per channel = targetDsdBit / 8
            uint64_t byteIdx = targetDsdBit / 8;
            m_dffBytePos = byteIdx;
            m_dffBitPos  = static_cast<int>(targetDsdBit % 8);
            // Re-read buffer at new position
            m_dffBufSize = 0;
            m_dffBufPos  = 0;
        } else {
            // DSF: block-interleaved, LSB-first
            // For each channel, find which block and bit within block
            for (size_t ch = 0; ch < m_numChannels; ++ch) {
                uint64_t bitInChannel = targetDsdBit;
                m_chBlock[ch]  = bitInChannel / (m_blockSize * 8ULL);
                m_chBufBit[ch] = bitInChannel % (m_blockSize * 8ULL);
                // Invalidate buffer to force reload
                m_chBufSize[ch] = 0;
            }
        }
        m_currentPcmFrame = targetPcmFrame;
        return true;
    }

    uint32_t getSampleRate()    const override { return m_outSampleRate; }
    size_t   getNumChannels()   const override { return m_numChannels; }
    uint32_t getBitsPerSample() const override { return 1; }
    uint64_t getTotalFrames()   const override { return m_totalPcmFrames; }
    uint64_t getCurrentFrame()  const override { return m_currentPcmFrame; }

    // Extra DSD info for UI
    uint32_t getDsdNativeRate() const { return m_dsdSampleRate; }

private:
    int      m_fd           = -1;
    bool     m_isOpen       = false;
    bool     m_isDff        = false;

    uint32_t m_dsdSampleRate  = 0;
    uint32_t m_outSampleRate  = 0;
    size_t   m_numChannels    = 0;
    uint64_t m_totalPcmFrames = 0;
    uint64_t m_currentPcmFrame = 0;

    // ── DSF state ─────────────────────────────────────────────────────────────
    uint32_t m_blockSize   = 4096; // bytes per channel per block
    uint64_t m_dataOffset  = 0;    // file offset of first audio byte

    // Per-channel block cache
    std::vector<std::vector<uint8_t>> m_chBuf;
    std::vector<size_t>  m_chBufSize;  // valid bytes in chBuf[ch]
    std::vector<uint64_t> m_chBufBit;  // current bit position within chBuf
    std::vector<uint64_t> m_chBlock;   // which block number is loaded for ch

    // ── DSDIFF state ──────────────────────────────────────────────────────────
    uint64_t m_dffDataOffset = 0;
    uint64_t m_dffDataSize   = 0;
    uint64_t m_dffBytePos    = 0;  // byte index per channel (in logical order)
    int      m_dffBitPos     = 0;  // bit index within byte (0-7, MSB first)
    // Read buffer for DSDIFF
    static constexpr size_t DFF_BUF = 4096;
    std::vector<uint8_t>  m_dffBuf;
    size_t  m_dffBufPos  = 0;
    size_t  m_dffBufSize = 0;

    // ── Helpers ───────────────────────────────────────────────────────────────

    void closeFile() {
        if (m_fd >= 0) { ::close(m_fd); m_fd = -1; }
        m_isOpen = false;
    }

    static uint32_t readLE32(const uint8_t* p) {
        return (uint32_t)p[0] | ((uint32_t)p[1]<<8) | ((uint32_t)p[2]<<16) | ((uint32_t)p[3]<<24);
    }
    static uint64_t readLE64(const uint8_t* p) {
        uint64_t lo = readLE32(p), hi = readLE32(p+4);
        return lo | (hi << 32);
    }
    static uint32_t readBE32(const uint8_t* p) {
        return ((uint32_t)p[0]<<24)|((uint32_t)p[1]<<16)|((uint32_t)p[2]<<8)|(uint32_t)p[3];
    }
    static uint64_t readBE64(const uint8_t* p) {
        uint64_t hi = readBE32(p), lo = readBE32(p+4);
        return (hi << 32) | lo;
    }

    // ── DSF parser ────────────────────────────────────────────────────────────
    bool parseDsf() {
        // DSD  chunk is 28 bytes; we already read the first 4 ("DSD ")
        // Read rest of DSD chunk: chunk_size(8) + total_size(8) + metadata_ptr(8) = 24 bytes
        uint8_t buf[64];
        if (::read(m_fd, buf, 24) != 24) return false;
        // buf[0..7]  = chunk size (should be 28)
        // buf[8..15] = total file size
        // buf[16..23] = metadata chunk pointer

        // fmt  chunk starts at byte 28
        if (::lseek(m_fd, 28, SEEK_SET) < 0) return false;
        if (::read(m_fd, buf, 4) != 4) return false;
        if (::memcmp(buf, "fmt ", 4) != 0) return false;

        // fmt  chunk size (8 bytes LE) — should be 52
        if (::read(m_fd, buf, 8) != 8) return false;
        // uint64_t fmtSize = readLE64(buf); // 52

        // fmt  body: 44 bytes
        if (::read(m_fd, buf, 44) != 44) return false;
        // [0..3]  format_version = 1
        // [4..7]  format_id = 0 (DSD raw)
        // [8..11] channel_type
        uint32_t channelCount = readLE32(buf + 12);
        uint32_t sampleRate   = readLE32(buf + 16);
        // [20..23] bits_per_sample = 1
        uint64_t sampleCount  = readLE64(buf + 24); // DSD samples per channel
        uint32_t blockSize    = readLE32(buf + 32);
        // [36..39] reserved = 0

        if (channelCount == 0 || channelCount > 8) return false;
        if (sampleRate == 0) return false;

        m_numChannels   = channelCount;
        m_dsdSampleRate = sampleRate;
        m_outSampleRate = sampleRate / DECIM;
        m_blockSize     = (blockSize > 0) ? blockSize : 4096;
        m_totalPcmFrames = sampleCount / DECIM;

        // data chunk: starts at byte 28 + 12 + 44 = 84? No.
        // DSD chunk = 28, fmt chunk = 12(header) + 44(body) = 56 → data starts at 28+56 = 84
        // Actually: each chunk = 4(id) + 8(size) + body
        // fmt chunk size field value = 52 means body=52, total=12+52=64, so after DSD(28)+fmt(64)=92
        // But let me seek explicitly to byte 92 and look for "data"
        if (::lseek(m_fd, 92, SEEK_SET) < 0) return false;
        if (::read(m_fd, buf, 4) != 4) return false;
        if (::memcmp(buf, "data", 4) != 0) return false;

        // data chunk size (8 bytes LE, includes the 12-byte header)
        if (::read(m_fd, buf, 8) != 8) return false;
        // Data audio starts immediately after
        m_dataOffset = (uint64_t)::lseek(m_fd, 0, SEEK_CUR);

        // Init per-channel buffers
        m_chBuf.assign(m_numChannels, std::vector<uint8_t>(m_blockSize));
        m_chBufSize.assign(m_numChannels, 0);
        m_chBufBit.assign(m_numChannels, 0);
        m_chBlock.assign(m_numChannels, 0);

        m_isOpen = true;
        return true;
    }

    // ── DSDIFF parser ─────────────────────────────────────────────────────────
    bool parseDff() {
        // We already read "FRM8" (4 bytes).
        // FRM8 chunk: 4(id) + 8(size) + 4(form-type "DSD ") = 16 bytes at start
        uint8_t buf[256];
        if (::read(m_fd, buf, 12) != 12) return false; // size(8) + "DSD "(4)
        if (::memcmp(buf + 8, "DSD ", 4) != 0) return false;

        // Scan sub-chunks inside FRM8 for PROP and DSD
        // FRM8 body starts at offset 16
        uint64_t filePos = 16;

        uint32_t sampleRate   = 0;
        size_t   channelCount = 0;
        bool     isUncompressed = false;
        uint64_t dsdDataOffset = 0;
        uint64_t dsdDataBytes  = 0;

        // Iterate sub-chunks
        for (int pass = 0; pass < 64; ++pass) {
            if (::lseek(m_fd, (off_t)filePos, SEEK_SET) < 0) break;
            if (::read(m_fd, buf, 12) != 12) break; // id(4) + size(8)
            uint64_t chunkSize = readBE64(buf + 4);
            uint64_t nextChunk = filePos + 12 + ((chunkSize + 1) & ~1ULL); // word-aligned

            if (::memcmp(buf, "PROP", 4) == 0) {
                // PROP contains SND + nested chunks; read the 4-byte prop-type ("SND ")
                uint8_t propType[4];
                if (::read(m_fd, propType, 4) == 4) {
                    // Scan nested chunks inside PROP
                    uint64_t propPos = filePos + 16; // after PROP header + SND  type
                    uint64_t propEnd = filePos + 12 + chunkSize;
                    for (int q = 0; q < 32 && propPos < propEnd; ++q) {
                        if (::lseek(m_fd, (off_t)propPos, SEEK_SET) < 0) break;
                        uint8_t nb[12];
                        if (::read(m_fd, nb, 12) != 12) break;
                        uint64_t nSize = readBE64(nb + 4);

                        if (::memcmp(nb, "FS  ", 4) == 0) {
                            uint8_t fsb[4];
                            if (::read(m_fd, fsb, 4) == 4)
                                sampleRate = readBE32(fsb);
                        } else if (::memcmp(nb, "CHNL", 4) == 0) {
                            uint8_t chnb[2];
                            if (::read(m_fd, chnb, 2) == 2)
                                channelCount = (chnb[0] << 8) | chnb[1];
                        } else if (::memcmp(nb, "CMPR", 4) == 0) {
                            uint8_t cmprType[4];
                            if (::read(m_fd, cmprType, 4) == 4)
                                isUncompressed = (::memcmp(cmprType, "DSD ", 4) == 0);
                        }
                        propPos += 12 + ((nSize + 1) & ~1ULL);
                    }
                }
            } else if (::memcmp(buf, "DSD ", 4) == 0) {
                // This is the actual audio data chunk
                dsdDataOffset = filePos + 12;
                dsdDataBytes  = chunkSize;
            }

            filePos = nextChunk;
            if (filePos >= (uint64_t)::lseek(m_fd, 0, SEEK_END)) break;
        }

        if (sampleRate == 0 || channelCount == 0 || !isUncompressed || dsdDataOffset == 0)
            return false;

        // Compute sample count from data size
        // DSDIFF: bytes are interleaved [ch0][ch1]..., so total_bytes / numChannels = bytes per ch
        uint64_t sampleCount = (dsdDataBytes / channelCount) * 8; // bits per channel

        m_numChannels    = channelCount;
        m_dsdSampleRate  = sampleRate;
        m_outSampleRate  = sampleRate / DECIM;
        m_totalPcmFrames = sampleCount / DECIM;
        m_dffDataOffset  = dsdDataOffset;
        m_dffDataSize    = dsdDataBytes;
        m_dffBytePos     = 0;
        m_dffBitPos      = 7; // MSB first, start at bit 7
        m_dffBuf.resize(DFF_BUF);
        m_dffBufSize = 0;
        m_dffBufPos  = 0;

        m_isDff  = true;
        m_isOpen = true;
        return true;
    }

    // ── Bit readers ───────────────────────────────────────────────────────────

    bool readDsdBit(size_t ch, uint8_t& outBit) {
        return m_isDff ? readDsdBitDff(ch, outBit) : readDsdBitDsf(ch, outBit);
    }

    // DSF: block-interleaved, LSB-first
    bool readDsdBitDsf(size_t ch, uint8_t& outBit) {
        if (m_chBufSize[ch] == 0 || m_chBufBit[ch] >= m_chBufSize[ch] * 8) {
            if (!loadDsfBlock(ch)) return false;
        }
        size_t byteIdx = m_chBufBit[ch] / 8;
        size_t bitIdx  = m_chBufBit[ch] % 8;
        outBit = (m_chBuf[ch][byteIdx] >> bitIdx) & 1u; // LSB-first
        m_chBufBit[ch]++;
        return true;
    }

    bool loadDsfBlock(size_t ch) {
        uint64_t filePos = m_dataOffset
                         + m_chBlock[ch] * (uint64_t)m_numChannels * m_blockSize
                         + ch * (uint64_t)m_blockSize;
        if (::lseek(m_fd, (off_t)filePos, SEEK_SET) < 0) return false;
        ssize_t n = ::read(m_fd, m_chBuf[ch].data(), m_blockSize);
        if (n <= 0) return false;
        m_chBufSize[ch] = (size_t)n;
        m_chBufBit[ch]  = 0;
        m_chBlock[ch]++;
        return true;
    }

    // DSDIFF: interleaved bytes [ch0][ch1]..., MSB-first
    bool readDsdBitDff(size_t ch, uint8_t& outBit) {
        // In DSDIFF, interleaved byte address for channel ch at byte position bytePos:
        // file_offset = dffDataOffset + bytePos * numChannels + ch
        // where bytePos is the current byte index per channel

        // Physical byte in file: m_dffBytePos * numChannels + ch
        uint64_t physicalByte = m_dffBytePos * m_numChannels + ch;
        uint64_t fileOff = m_dffDataOffset + physicalByte;

        if (fileOff >= m_dffDataOffset + m_dffDataSize) return false;

        // Check if this byte is in our buffer
        if (m_dffBufSize == 0 || fileOff < m_dffBufFileBase ||
            fileOff >= m_dffBufFileBase + m_dffBufSize) {
            // Fill buffer starting at fileOff (aligned to channel boundary)
            uint64_t alignedOff = m_dffDataOffset + (m_dffBytePos * m_numChannels);
            if (::lseek(m_fd, (off_t)alignedOff, SEEK_SET) < 0) return false;
            ssize_t n = ::read(m_fd, m_dffBuf.data(), m_dffBuf.size());
            if (n <= 0) return false;
            m_dffBufFileBase = alignedOff;
            m_dffBufSize     = (size_t)n;
        }

        size_t localIdx = (size_t)(fileOff - m_dffBufFileBase);
        if (localIdx >= m_dffBufSize) return false;

        uint8_t byte = m_dffBuf[localIdx];
        outBit = (byte >> m_dffBitPos) & 1u; // MSB-first: bit 7 first

        // Advance bit position
        m_dffBitPos--;
        if (m_dffBitPos < 0) {
            m_dffBitPos = 7;
            // Move to next byte for this channel
            m_dffBytePos++;
        }
        return true;
    }

    uint64_t m_dffBufFileBase = 0;
};

} // namespace decoders
} // namespace audio_engine
