#pragma once
#include <unistd.h>
#include <cmath>
#include <string>
#include <algorithm>
#include <vector>
#include <cctype>

namespace audio_engine {
namespace decoders {

// Scans the first 64 KB of a file for "REPLAYGAIN_TRACK_GAIN" tag.
// Works for FLAC (vorbis comments) and MP3 (ID3v2 TXXX frames) since both
// store the value as plain UTF-8 text.
// Returns gain in dB (e.g. -6.5f), or 0.0f if not found.
inline float scanReplayGainDb(int fd) {
    if (fd < 0) return 0.0f;
    lseek(fd, 0, SEEK_SET);
    std::vector<char> buf(65536, 0);
    ssize_t n = ::read(fd, buf.data(), buf.size());
    lseek(fd, 0, SEEK_SET);
    if (n <= 32) return 0.0f;

    std::string lower(buf.data(), (size_t)n);
    std::transform(lower.begin(), lower.end(), lower.begin(),
                   [](unsigned char c){ return (char)std::tolower(c); });

    const char* tag = "replaygain_track_gain";
    size_t pos = lower.find(tag);
    if (pos == std::string::npos) return 0.0f;

    size_t eq = std::string::npos;
    for (size_t i = pos + 21, lim = std::min((size_t)n, pos + 28); i < lim; ++i) {
        if (buf[i] == '=') { eq = i; break; }
        if (buf[i] != 0 && buf[i] != ' ') break;
    }
    if (eq == std::string::npos) return 0.0f;

    std::string val;
    for (size_t i = eq + 1, lim = std::min((size_t)n, eq + 20); i < lim; ++i) {
        char c = buf[i];
        if (c == 0 || c == ' ') { if (val.empty()) continue; break; }
        if (c == '+' && val.empty()) continue;
        if (c == '-' && val.empty()) { val = "-"; continue; }
        if (std::isdigit((unsigned char)c) || c == '.') { val += c; continue; }
        if (!val.empty()) break;
    }
    if (val.empty() || val == "-") return 0.0f;
    try { return std::stof(val); } catch (...) { return 0.0f; }
}

} // namespace decoders
} // namespace audio_engine
