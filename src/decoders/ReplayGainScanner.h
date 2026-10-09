#pragma once

#include <algorithm>
#include <cmath>
#include <cstring>
#include <string>
#include <unistd.h>
#include <vector>

#include "../tags/TagReader.h"

namespace audio_engine {
namespace decoders {

using ReplayGainInfo = tags::ReplayGainInfo;

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

namespace rg {

// Last resort for containers without a parser: "KEY=value" text in the first 64 KiB.
inline void scanPlainText(int fd, ReplayGainInfo& info) {
    std::vector<char> buf(65536);
    const ssize_t n = ::pread(fd, buf.data(), buf.size(), 0);
    if (n <= 0) return;
    const std::string text(buf.data(), static_cast<size_t>(n));
    std::string low = text;
    std::transform(low.begin(), low.end(), low.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    for (const char* key : {"replaygain_track_gain", "replaygain_track_peak", "replaygain_album_gain", "replaygain_album_peak"}) {
        const size_t at = low.find(key);
        if (at == std::string::npos) continue;
        size_t v = at + std::strlen(key);
        while (v < text.size() && (text[v] == '=' || text[v] == '\0' || text[v] == ' ' || text[v] == ':')) ++v;
        float number = 0.0f;
        if (!tags::parseNumber(text.substr(v, 24), number)) continue;
        const std::string k(key);
        if (k == "replaygain_track_gain") { info.trackGainDb = number; info.hasTrack = true; }
        else if (k == "replaygain_track_peak") info.trackPeak = number;
        else if (k == "replaygain_album_gain") { info.albumGainDb = number; info.hasAlbum = true; }
        else info.albumPeak = number;
    }
}

} // namespace rg

/** Reads ReplayGain tags without moving the descriptor's file offset. */
inline ReplayGainInfo readReplayGain(int fd) {
    if (fd < 0) return {};
    ReplayGainInfo info = tags::readTags(fd).replayGain;
    if (!info.hasTrack && !info.hasAlbum) rg::scanPlainText(fd, info);
    return info;
}

} // namespace decoders
} // namespace audio_engine
