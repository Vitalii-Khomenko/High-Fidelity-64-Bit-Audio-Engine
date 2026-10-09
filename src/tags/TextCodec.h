#pragma once

#include <cstdint>
#include <string>

namespace audio_engine {
namespace tags {

inline void appendUtf8(std::string& out, uint32_t cp) {
    if (cp < 0x80) {
        out.push_back(static_cast<char>(cp));
    } else if (cp < 0x800) {
        out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp < 0x10000) {
        out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp < 0x110000) {
        out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    }
}

/** Windows-1251 (Cyrillic) code points for bytes 0x80..0xFF. */
inline uint32_t cp1251(uint8_t b) {
    static const uint16_t table[64] = {
        0x0402, 0x0403, 0x201A, 0x0453, 0x201E, 0x2026, 0x2020, 0x2021, 0x20AC, 0x2030, 0x0409, 0x2039, 0x040A, 0x040C, 0x040B, 0x040F,
        0x0452, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014, 0xFFFD, 0x2122, 0x0459, 0x203A, 0x045A, 0x045C, 0x045B, 0x045F,
        0x00A0, 0x040E, 0x045E, 0x0408, 0x00A4, 0x0490, 0x00A6, 0x00A7, 0x0401, 0x00A9, 0x0404, 0x00AB, 0x00AC, 0x00AD, 0x00AE, 0x0407,
        0x00B0, 0x00B1, 0x0406, 0x0456, 0x0491, 0x00B5, 0x00B6, 0x00B7, 0x0451, 0x2116, 0x0454, 0x00BB, 0x0458, 0x0405, 0x0455, 0x0457,
    };
    if (b < 0x80) return b;
    if (b >= 0xC0) return 0x0410 + (b - 0xC0);   // А..я
    return table[b - 0x80];
}

/**
 * Single-byte text that claims to be ISO-8859-1. Russian files very often
 * carry Windows-1251 there; when most letters are in the upper half (as in
 * Cyrillic text, unlike accented Western text) it is decoded as 1251.
 */
inline std::string fromLatin1(const uint8_t* data, size_t size) {
    size_t high = 0, ascii = 0;
    for (size_t i = 0; i < size; ++i) {
        const uint8_t c = data[i];
        if (c >= 0xC0) ++high;
        else if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) ++ascii;
    }
    const bool cyrillic = high >= 2 && high * 2 > high + ascii;
    std::string out;
    out.reserve(size);
    for (size_t i = 0; i < size; ++i) {
        const uint8_t c = data[i];
        if (c == 0) break;
        appendUtf8(out, cyrillic ? cp1251(c) : c);
    }
    return out;
}

inline bool isValidUtf8(const uint8_t* data, size_t size) {
    size_t i = 0;
    while (i < size) {
        const uint8_t c = data[i];
        size_t extra;
        if (c < 0x80) extra = 0;
        else if (c >= 0xC2 && c <= 0xDF) extra = 1;
        else if ((c & 0xF0) == 0xE0) extra = 2;
        else if (c >= 0xF0 && c <= 0xF4) extra = 3;
        else return false;
        if (extra > size - i - 1) return false;
        for (size_t k = 1; k <= extra; ++k) {
            if ((data[i + k] & 0xC0) != 0x80) return false;
        }
        i += extra + 1;
    }
    return true;
}

/** UTF-8 if it is valid UTF-8, otherwise treated as single-byte text. */
inline std::string fromUtf8OrLegacy(const uint8_t* data, size_t size) {
    size_t n = 0;
    while (n < size && data[n] != 0) ++n;
    if (isValidUtf8(data, n)) return std::string(reinterpret_cast<const char*>(data), n);
    return fromLatin1(data, n);
}

/** UTF-16 with optional BOM; bigEndian is the default when there is none. */
inline std::string fromUtf16(const uint8_t* data, size_t size, bool bigEndian) {
    std::string out;
    size_t i = 0;
    while (i + 1 < size) {
        uint32_t unit = bigEndian ? (uint32_t(data[i]) << 8) | data[i + 1] : (uint32_t(data[i + 1]) << 8) | data[i];
        i += 2;
        if (unit == 0xFEFF) continue;                         // BOM (repeated by some ID3v2.3 writers)
        if (unit == 0xFFFE) { bigEndian = !bigEndian; continue; }
        if (unit == 0) break;
        if (unit >= 0xD800 && unit < 0xDC00 && i + 1 < size) {
            const uint32_t low = bigEndian ? (uint32_t(data[i]) << 8) | data[i + 1] : (uint32_t(data[i + 1]) << 8) | data[i];
            if (low >= 0xDC00 && low < 0xE000) {
                unit = 0x10000 + ((unit - 0xD800) << 10) + (low - 0xDC00);
                i += 2;
            }
        }
        appendUtf8(out, unit);
    }
    return out;
}

} // namespace tags
} // namespace audio_engine
