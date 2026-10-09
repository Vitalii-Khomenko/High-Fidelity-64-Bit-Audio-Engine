#pragma once

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <vector>
#include <sys/stat.h>
#include <unistd.h>

#include "DecoderFactory.h"

namespace audio_engine {
namespace decoders {

/**
 * Track length without decoding, for indexing a library: header fields where
 * a format has them (FLAC STREAMINFO, MP4 mvhd, Xing/VBRI or CBR for MP3, the
 * last Ogg granule position) and a decoder open (headers only) for the rest.
 * MP3 is never scanned frame by frame here.
 */
class DurationProbe {
public:
    explicit DurationProbe(int fd) : m_fd(fd) {
        struct stat st {};
        m_size = fd >= 0 && ::fstat(fd, &st) == 0 && st.st_size > 0 ? static_cast<uint64_t>(st.st_size) : 0;
    }

    /** Milliseconds, 0 when unknown. */
    int64_t durationMs() {
        if (m_fd < 0 || m_size == 0) return 0;
        uint64_t start = skipId3();
        switch (sniff(m_fd)) {
            case Container::Flac: return flac(start);
            case Container::Mp3: return mp3(start);
            case Container::Mp4: return mp4();
            case Container::OggVorbis:
            case Container::OggOpus:
            case Container::OggFlac: return ogg(start);
            case Container::Adts:
            case Container::Matroska:
            case Container::Ogg:
            case Container::Unknown: return 0;   // would need the platform decoder
            default: return opened();
        }
    }

private:
    bool at(uint64_t offset, void* dst, size_t n) const {
        if (offset > m_size || n > m_size - offset) return false;
        return ::pread(m_fd, dst, n, static_cast<off_t>(offset)) == static_cast<ssize_t>(n);
    }
    static uint32_t be32(const uint8_t* p) { return (uint32_t(p[0]) << 24) | (uint32_t(p[1]) << 16) | (uint32_t(p[2]) << 8) | p[3]; }
    static uint64_t be64(const uint8_t* p) { return (uint64_t(be32(p)) << 32) | be32(p + 4); }
    static uint64_t le64(const uint8_t* p) {
        uint64_t v = 0;
        for (int i = 7; i >= 0; --i) v = (v << 8) | p[i];
        return v;
    }

    uint64_t skipId3() const {
        uint8_t h[10];
        if (!at(0, h, 10) || std::memcmp(h, "ID3", 3) != 0) return 0;
        return 10 + sniffing::synchsafe(h + 6) + ((h[5] & 0x10) ? 10 : 0);
    }

    int64_t opened() const {
        auto decoder = openDecoder(m_fd);
        if (!decoder || decoder->getSampleRate() == 0) return 0;
        return static_cast<int64_t>(decoder->getTotalFrames() * 1000ull / decoder->getSampleRate());
    }

    int64_t flac(uint64_t start) const {
        uint8_t s[4 + 4 + 34];
        if (!at(start, s, sizeof(s)) || (s[4] & 0x7f) != 0) return 0;   // first block must be STREAMINFO
        const uint8_t* p = s + 8;
        const uint32_t rate = (uint32_t(p[10]) << 12) | (uint32_t(p[11]) << 4) | (p[12] >> 4);
        const uint64_t total = (uint64_t(p[13] & 0x0F) << 32) | be32(p + 14);
        return rate ? static_cast<int64_t>(total * 1000 / rate) : 0;
    }

    int64_t mp3(uint64_t start) const {
        // Find the first frame header within 64 KiB.
        uint8_t buf[65536];
        const size_t n = static_cast<size_t>(std::min<uint64_t>(sizeof(buf), m_size - std::min(m_size, start)));
        if (n < 4 || !at(start, buf, n)) return 0;
        static const int kRates[3][3] = {{44100, 48000, 32000}, {22050, 24000, 16000}, {11025, 12000, 8000}};
        static const int kBitrates[2][15] = {
            {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320},   // MPEG-1 layer III
            {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160},       // MPEG-2/2.5 layer III
        };
        for (size_t i = 0; i + 4 <= n; ++i) {
            const uint8_t* h = buf + i;
            if (h[0] != 0xFF || (h[1] & 0xE0) != 0xE0) continue;
            const int version = (h[1] >> 3) & 3;           // 3 = MPEG-1, 2 = MPEG-2, 0 = MPEG-2.5
            const int layer = (h[1] >> 1) & 3;             // 1 = layer III
            const int bitrateIndex = h[2] >> 4;
            const int rateIndex = (h[2] >> 2) & 3;
            if (version == 1 || layer != 1 || bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) continue;
            const bool mpeg1 = version == 3;
            const int rate = kRates[mpeg1 ? 0 : version == 2 ? 1 : 2][rateIndex];
            const int samplesPerFrame = mpeg1 ? 1152 : 576;
            const bool mono = (h[3] >> 6) == 3;
            const size_t side = mpeg1 ? (mono ? 17 : 32) : (mono ? 9 : 17);
            // Xing / Info frame count.
            if (i + 4 + side + 12 <= n && (!std::memcmp(h + 4 + side, "Xing", 4) || !std::memcmp(h + 4 + side, "Info", 4))) {
                const uint8_t* x = h + 4 + side;
                if (be32(x + 4) & 1) return static_cast<int64_t>(uint64_t(be32(x + 8)) * samplesPerFrame * 1000 / rate);
            }
            // VBRI (Fraunhofer) frame count at a fixed offset.
            if (i + 4 + 32 + 18 <= n && !std::memcmp(h + 4 + 32, "VBRI", 4)) {
                return static_cast<int64_t>(uint64_t(be32(h + 4 + 32 + 14)) * samplesPerFrame * 1000 / rate);
            }
            // CBR estimate from the audio byte count.
            const int kbps = kBitrates[mpeg1 ? 0 : 1][bitrateIndex];
            uint64_t bytes = m_size - start - i;
            uint8_t tag[3];
            if (m_size >= 128 && at(m_size - 128, tag, 3) && !std::memcmp(tag, "TAG", 3)) bytes -= std::min<uint64_t>(bytes, 128);
            return static_cast<int64_t>(bytes * 8 / static_cast<uint64_t>(kbps));
        }
        return 0;
    }

    int64_t mp4() const {
        uint64_t pos = 0;
        for (int guard = 0; guard < 64 && pos + 8 <= m_size; ++guard) {
            uint8_t h[16];
            if (!at(pos, h, 8)) return 0;
            uint64_t size = be32(h);
            uint64_t header = 8;
            if (size == 1) {
                if (!at(pos + 8, h + 8, 8)) return 0;
                size = be64(h + 8);
                header = 16;
            } else if (size == 0) {
                size = m_size - pos;
            }
            if (size < header) return 0;
            if (!std::memcmp(h + 4, "moov", 4)) {
                // mvhd is (almost always) the first child.
                uint8_t m[8 + 32];
                if (!at(pos + header, m, sizeof(m)) || std::memcmp(m + 4, "mvhd", 4) != 0) return 0;
                const uint8_t* b = m + 8;
                if (b[0] == 1) {
                    const uint32_t scale = be32(b + 20);
                    return scale ? static_cast<int64_t>(be64(b + 24) * 1000 / scale) : 0;
                }
                const uint32_t scale = be32(b + 12);
                return scale ? static_cast<int64_t>(uint64_t(be32(b + 16)) * 1000 / scale) : 0;
            }
            pos += size;
        }
        return 0;
    }

    int64_t ogg(uint64_t start) const {
        // Rate from the first packet; length from the last page's granule position.
        uint8_t first[27 + 255 + 64] = {};
        if (!at(start, first, std::min<uint64_t>(sizeof(first), m_size - start))) return 0;
        const uint8_t* packet = first + 27 + first[26];
        uint64_t rate = 0, preskip = 0;
        if (!std::memcmp(packet, "OpusHead", 8)) {
            rate = 48000;
            preskip = packet[10] | (packet[11] << 8);
        } else if (packet[0] == 0x01 && !std::memcmp(packet + 1, "vorbis", 6)) {
            rate = packet[12] | (packet[13] << 8) | (packet[14] << 16) | (uint64_t(packet[15]) << 24);
        } else if (packet[0] == 0x7F && !std::memcmp(packet + 1, "FLAC", 4)) {
            const uint8_t* si = packet + 13 + 4;   // mapping header, "fLaC", block header
            rate = (uint32_t(si[10]) << 12) | (uint32_t(si[11]) << 4) | (si[12] >> 4);
        }
        if (rate == 0) return 0;
        const uint64_t tail = std::min<uint64_t>(m_size - start, 65536);
        std::vector<uint8_t> buf(static_cast<size_t>(tail));
        if (!at(m_size - tail, buf.data(), buf.size())) return 0;
        for (size_t i = buf.size() >= 14 ? buf.size() - 14 : 0; i-- > 0;) {
            if (!std::memcmp(buf.data() + i, "OggS", 4)) {
                const uint64_t granule = le64(buf.data() + i + 6);
                if (granule == ~0ull) continue;
                return granule > preskip ? static_cast<int64_t>((granule - preskip) * 1000 / rate) : 0;
            }
        }
        return 0;
    }

    int m_fd;
    uint64_t m_size = 0;
};

} // namespace decoders
} // namespace audio_engine
