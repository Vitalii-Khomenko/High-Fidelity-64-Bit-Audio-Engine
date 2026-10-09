#pragma once

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

namespace audio_engine {
namespace decoders {

struct ReplayGainInfo {
    float trackGainDb = 0.0f;
    float trackPeak = 0.0f;     // linear, 0 = unknown
    float albumGainDb = 0.0f;
    float albumPeak = 0.0f;
    bool hasTrack = false;
    bool hasAlbum = false;
};

enum class ReplayGainMode : int { Off = 0, Track = 1, Album = 2 };

/**
 * Linear gain for the selected mode. When a peak is known the gain is limited
 * so the loudest sample cannot exceed full scale (ReplayGain "prevent clipping").
 */
inline double replayGainLinear(const ReplayGainInfo& info, ReplayGainMode mode) {
    if (mode == ReplayGainMode::Off) return 1.0;
    bool useAlbum = mode == ReplayGainMode::Album && info.hasAlbum;
    if (!useAlbum && !info.hasTrack) {
        if (!info.hasAlbum) return 1.0;
        useAlbum = true;
    }
    const float db = useAlbum ? info.albumGainDb : info.trackGainDb;
    const float peak = useAlbum ? info.albumPeak : info.trackPeak;
    double linear = std::pow(10.0, std::clamp(static_cast<double>(db), -24.0, 18.0) / 20.0);
    if (peak > 0.0f && std::isfinite(peak)) linear = std::min(linear, 1.0 / peak);
    return linear;
}

namespace tags {

inline bool preadAll(int fd, void* dst, size_t bytes, uint64_t offset) {
    auto* out = static_cast<uint8_t*>(dst);
    while (bytes > 0) {
        const ssize_t n = ::pread(fd, out, bytes, static_cast<off_t>(offset));
        if (n <= 0) return false;
        out += n;
        offset += static_cast<uint64_t>(n);
        bytes -= static_cast<size_t>(n);
    }
    return true;
}

inline uint32_t be32(const uint8_t* p) {
    return (uint32_t(p[0]) << 24) | (uint32_t(p[1]) << 16) | (uint32_t(p[2]) << 8) | uint32_t(p[3]);
}
inline uint32_t le32(const uint8_t* p) {
    return uint32_t(p[0]) | (uint32_t(p[1]) << 8) | (uint32_t(p[2]) << 16) | (uint32_t(p[3]) << 24);
}
inline uint64_t le64(const uint8_t* p) { return uint64_t(le32(p)) | (uint64_t(le32(p + 4)) << 32); }
inline uint32_t synchsafe32(const uint8_t* p) {
    return (uint32_t(p[0] & 0x7f) << 21) | (uint32_t(p[1] & 0x7f) << 14)
         | (uint32_t(p[2] & 0x7f) << 7) | uint32_t(p[3] & 0x7f);
}

inline std::string lower(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return s;
}

// Locale-independent parse of values like "-6.48 dB", "+1.2", "0.988525".
inline bool parseNumber(const std::string& text, float& out) {
    size_t i = 0;
    while (i < text.size() && (text[i] == ' ' || text[i] == '\t')) ++i;
    bool negative = false;
    if (i < text.size() && (text[i] == '+' || text[i] == '-')) negative = text[i++] == '-';
    double value = 0.0;
    bool digits = false;
    while (i < text.size() && std::isdigit(static_cast<unsigned char>(text[i]))) {
        value = value * 10.0 + (text[i++] - '0');
        digits = true;
    }
    if (i < text.size() && (text[i] == '.' || text[i] == ',')) {
        ++i;
        double scale = 0.1;
        while (i < text.size() && std::isdigit(static_cast<unsigned char>(text[i]))) {
            value += (text[i++] - '0') * scale;
            scale *= 0.1;
            digits = true;
        }
    }
    if (!digits) return false;
    out = static_cast<float>(negative ? -value : value);
    return std::isfinite(out);
}

inline void applyField(ReplayGainInfo& info, const std::string& rawKey, const std::string& value) {
    const std::string key = lower(rawKey);
    float number = 0.0f;
    if (!parseNumber(value, number)) return;
    if (key == "replaygain_track_gain") { info.trackGainDb = number; info.hasTrack = true; }
    else if (key == "replaygain_track_peak") { info.trackPeak = number; }
    else if (key == "replaygain_album_gain") { info.albumGainDb = number; info.hasAlbum = true; }
    else if (key == "replaygain_album_peak") { info.albumPeak = number; }
}

inline bool parseFlac(int fd, ReplayGainInfo& info) {
    uint64_t pos = 4;
    for (int guard = 0; guard < 128; ++guard) {
        uint8_t header[4];
        if (!preadAll(fd, header, 4, pos)) return false;
        const bool last = (header[0] & 0x80) != 0;
        const int type = header[0] & 0x7f;
        const uint32_t length = (uint32_t(header[1]) << 16) | (uint32_t(header[2]) << 8) | header[3];
        pos += 4;
        if (type == 4 && length >= 8 && length <= (16u << 20)) {
            std::vector<uint8_t> block(length);
            if (!preadAll(fd, block.data(), length, pos)) return false;
            size_t p = 0;
            const uint32_t vendor = le32(block.data());
            p = 4 + static_cast<size_t>(vendor);
            if (p + 4 > length) return false;
            const uint32_t count = le32(block.data() + p);
            p += 4;
            for (uint32_t c = 0; c < count && p + 4 <= length; ++c) {
                const uint32_t len = le32(block.data() + p);
                p += 4;
                if (len > length - p) break;
                const std::string comment(reinterpret_cast<const char*>(block.data() + p), len);
                p += len;
                const size_t eq = comment.find('=');
                if (eq != std::string::npos) applyField(info, comment.substr(0, eq), comment.substr(eq + 1));
            }
            return true;
        }
        pos += length;
        if (last) break;
    }
    return false;
}

// Decodes an ID3 text payload (encoding byte already removed) to UTF-8/ASCII.
inline std::string decodeId3Text(const uint8_t* data, size_t size, uint8_t encoding) {
    std::string out;
    if (encoding == 1 || encoding == 2) {
        bool bigEndian = encoding == 2;
        size_t i = 0;
        if (encoding == 1 && size >= 2) {
            if (data[0] == 0xFE && data[1] == 0xFF) { bigEndian = true; i = 2; }
            else if (data[0] == 0xFF && data[1] == 0xFE) { bigEndian = false; i = 2; }
        }
        for (; i + 1 < size; i += 2) {
            const uint16_t unit = bigEndian ? (uint16_t(data[i]) << 8) | data[i + 1]
                                            : (uint16_t(data[i + 1]) << 8) | data[i];
            // ID3v2.3 repeats the BOM before the value string.
            if (unit == 0xFEFF) continue;
            if (unit == 0xFFFE) { bigEndian = !bigEndian; continue; }
            if (unit == 0) { out.push_back('\0'); continue; }
            // ReplayGain keys and values are ASCII; drop anything else.
            out.push_back(unit < 0x80 ? static_cast<char>(unit) : '?');
        }
    } else {
        out.assign(reinterpret_cast<const char*>(data), size);
    }
    return out;
}

inline bool parseId3v2(int fd, uint64_t offset, ReplayGainInfo& info) {
    uint8_t header[10];
    if (!preadAll(fd, header, 10, offset) || std::memcmp(header, "ID3", 3) != 0) return false;
    const int version = header[3];
    if (version < 2 || version > 4) return false;
    const uint8_t flags = header[5];
    const uint32_t tagSize = synchsafe32(header + 6);
    if (tagSize == 0 || tagSize > (64u << 20)) return false;
    std::vector<uint8_t> tag(tagSize);
    if (!preadAll(fd, tag.data(), tagSize, offset + 10)) return false;

    if (flags & 0x80) {  // tag-level unsynchronisation: remove 0xFF 0x00 stuffing
        std::vector<uint8_t> clean;
        clean.reserve(tag.size());
        for (size_t i = 0; i < tag.size(); ++i) {
            clean.push_back(tag[i]);
            if (tag[i] == 0xFF && i + 1 < tag.size() && tag[i + 1] == 0x00) ++i;
        }
        tag.swap(clean);
    }

    size_t p = 0;
    if ((flags & 0x40) && version >= 3 && tag.size() >= 4) {  // extended header
        const uint32_t ext = version == 4 ? synchsafe32(tag.data()) : be32(tag.data()) + 4;
        p = std::min<size_t>(tag.size(), ext);
    }
    const size_t headerLen = version == 2 ? 6 : 10;
    bool found = false;
    while (p + headerLen <= tag.size()) {
        const uint8_t* fh = tag.data() + p;
        if (fh[0] == 0) break;  // padding
        uint32_t size = 0;
        if (version == 2) size = (uint32_t(fh[3]) << 16) | (uint32_t(fh[4]) << 8) | fh[5];
        else if (version == 3) size = be32(fh + 4);
        else size = synchsafe32(fh + 4);
        p += headerLen;
        if (size > tag.size() - p) break;
        const bool txxx = version == 2 ? std::memcmp(fh, "TXX", 3) == 0 : std::memcmp(fh, "TXXX", 4) == 0;
        if (txxx && size > 1) {
            const uint8_t encoding = tag[p];
            const std::string text = decodeId3Text(tag.data() + p + 1, size - 1, encoding);
            const size_t sep = text.find('\0');
            if (sep != std::string::npos) {
                std::string value = text.substr(sep + 1);
                const size_t end = value.find('\0');
                if (end != std::string::npos) value.resize(end);
                applyField(info, text.substr(0, sep), value);
                found = true;
            }
        }
        p += size;
    }
    return found;
}

// RIFF/RF64 (little endian sizes) or AIFF (big endian): look for an ID3 chunk.
inline bool parseChunkedId3(int fd, bool bigEndian, uint64_t fileSize, ReplayGainInfo& info) {
    uint64_t pos = 12;
    for (int guard = 0; guard < 512 && pos + 8 <= fileSize; ++guard) {
        uint8_t ch[8];
        if (!preadAll(fd, ch, 8, pos)) return false;
        const uint32_t size = bigEndian ? be32(ch + 4) : le32(ch + 4);
        if (std::memcmp(ch, "id3 ", 4) == 0 || std::memcmp(ch, "ID3 ", 4) == 0) {
            return parseId3v2(fd, pos + 8, info);
        }
        pos += 8 + size + (size & 1u);
    }
    return false;
}

// Last resort for unusual containers: "KEY=value" text in the first 64 KiB.
inline void scanPlainText(int fd, ReplayGainInfo& info) {
    std::vector<char> buf(65536);
    const ssize_t n = ::pread(fd, buf.data(), buf.size(), 0);
    if (n <= 0) return;
    std::string text(buf.data(), static_cast<size_t>(n));
    const std::string low = lower(text);
    for (const char* key : {"replaygain_track_gain", "replaygain_track_peak",
                            "replaygain_album_gain", "replaygain_album_peak"}) {
        const size_t at = low.find(key);
        if (at == std::string::npos) continue;
        size_t v = at + std::strlen(key);
        while (v < text.size() && (text[v] == '=' || text[v] == '\0' || text[v] == ' ' || text[v] == ':')) ++v;
        applyField(info, key, text.substr(v, 24));
    }
}

} // namespace tags

/** Reads ReplayGain tags without moving the descriptor's file offset. */
inline ReplayGainInfo readReplayGain(int fd) {
    ReplayGainInfo info;
    if (fd < 0) return info;
    uint8_t magic[12] = {};
    if (::pread(fd, magic, sizeof(magic), 0) < 4) return info;
    struct stat st {};
    const uint64_t fileSize = ::fstat(fd, &st) == 0 && st.st_size > 0 ? static_cast<uint64_t>(st.st_size) : 0;
    bool parsed = false;
    if (std::memcmp(magic, "fLaC", 4) == 0) {
        parsed = tags::parseFlac(fd, info);
    } else if (std::memcmp(magic, "ID3", 3) == 0) {
        parsed = tags::parseId3v2(fd, 0, info);
    } else if (std::memcmp(magic, "RIFF", 4) == 0 || std::memcmp(magic, "RF64", 4) == 0) {
        parsed = tags::parseChunkedId3(fd, false, fileSize, info);
    } else if (std::memcmp(magic, "FORM", 4) == 0) {
        parsed = tags::parseChunkedId3(fd, true, fileSize, info);
    } else if (std::memcmp(magic, "DSD ", 4) == 0) {
        uint8_t dsd[24];
        if (tags::preadAll(fd, dsd, sizeof(dsd), 4)) {
            const uint64_t metadata = tags::le64(dsd + 16);
            if (metadata > 0 && metadata < fileSize) parsed = tags::parseId3v2(fd, metadata, info);
        }
    }
    if (!parsed || (!info.hasTrack && !info.hasAlbum)) tags::scanPlainText(fd, info);
    return info;
}

} // namespace decoders
} // namespace audio_engine
