#pragma once
// Fake extractor over a test container:
//   "FAKEMC01", mime (24 bytes, NUL padded), then little-endian u32 fields
//   rate, channels, framesPerPacket, encoderDelay, encoderPadding, frames,
//   alacBits (0 = no csd-0), followed by interleaved float32 samples.
// Every packet is a sync sample.
#include <cmath>
#include <sys/types.h>
#include <unistd.h>

#include "NdkMediaFormat.h"

enum SeekMode { AMEDIAEXTRACTOR_SEEK_PREVIOUS_SYNC, AMEDIAEXTRACTOR_SEEK_NEXT_SYNC, AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC };

namespace fake_media {
constexpr size_t kHeader = 8 + 24 + 7 * 4;
inline uint32_t le32(const uint8_t* p) { return p[0] | (p[1] << 8) | (p[2] << 16) | (uint32_t(p[3]) << 24); }
}

struct AMediaExtractor {
    int fd = -1;
    std::string mime;
    uint32_t rate = 0, channels = 0, fpp = 0, delay = 0, padding = 0, frames = 0, alacBits = 0;
    uint32_t packet = 0;
    bool selected = false;

    uint32_t packets() const { return (frames + fpp - 1) / fpp; }
};

inline AMediaExtractor* AMediaExtractor_new() { return new AMediaExtractor(); }
inline media_status_t AMediaExtractor_delete(AMediaExtractor* e) { delete e; return AMEDIA_OK; }

inline media_status_t AMediaExtractor_setDataSourceFd(AMediaExtractor* e, int fd, off64_t, off64_t) {
    uint8_t h[fake_media::kHeader];
    if (::pread(fd, h, sizeof(h), 0) != static_cast<ssize_t>(sizeof(h)) || std::memcmp(h, "FAKEMC01", 8) != 0) {
        return AMEDIA_ERROR_UNKNOWN;
    }
    e->fd = fd;
    e->mime.assign(reinterpret_cast<const char*>(h + 8), strnlen(reinterpret_cast<const char*>(h + 8), 24));
    const uint8_t* p = h + 32;
    e->rate = fake_media::le32(p); e->channels = fake_media::le32(p + 4); e->fpp = fake_media::le32(p + 8);
    e->delay = fake_media::le32(p + 12); e->padding = fake_media::le32(p + 16); e->frames = fake_media::le32(p + 20);
    e->alacBits = fake_media::le32(p + 24);
    return e->fpp > 0 ? AMEDIA_OK : AMEDIA_ERROR_UNKNOWN;
}

inline size_t AMediaExtractor_getTrackCount(AMediaExtractor*) { return 1; }

inline AMediaFormat* AMediaExtractor_getTrackFormat(AMediaExtractor* e, size_t) {
    auto* f = AMediaFormat_new();
    f->str["mime"] = e->mime;
    f->i32["sample-rate"] = static_cast<int32_t>(e->rate);
    f->i32["channel-count"] = static_cast<int32_t>(e->channels);
    f->i64["durationUs"] = static_cast<int64_t>(std::llround(e->frames * 1e6 / e->rate));
    if (e->delay) f->i32["encoder-delay"] = static_cast<int32_t>(e->delay);
    if (e->padding) f->i32["encoder-padding"] = static_cast<int32_t>(e->padding);
    if (e->alacBits) {
        std::vector<uint8_t> cookie(24, 0);
        cookie[5] = static_cast<uint8_t>(e->alacBits);
        f->buf["csd-0"] = cookie;
    }
    return f;
}

inline media_status_t AMediaExtractor_selectTrack(AMediaExtractor* e, size_t) { e->selected = true; return AMEDIA_OK; }

inline ssize_t AMediaExtractor_readSampleData(AMediaExtractor* e, uint8_t* buffer, size_t capacity) {
    if (e->packet >= e->packets()) return -1;
    const uint32_t first = e->packet * e->fpp;
    const uint32_t count = std::min(e->fpp, e->frames - first);
    const size_t bytes = size_t(count) * e->channels * 4;
    if (bytes > capacity) return -1;
    const off_t offset = static_cast<off_t>(fake_media::kHeader + size_t(first) * e->channels * 4);
    return ::pread(e->fd, buffer, bytes, offset) == static_cast<ssize_t>(bytes) ? static_cast<ssize_t>(bytes) : -1;
}

inline int64_t AMediaExtractor_getSampleTime(AMediaExtractor* e) {
    if (e->packet >= e->packets()) return -1;
    return static_cast<int64_t>(double(e->packet) * e->fpp * 1e6 / e->rate);   // truncated, like real containers
}

inline bool AMediaExtractor_advance(AMediaExtractor* e) {
    if (e->packet >= e->packets()) return false;
    ++e->packet;
    return e->packet < e->packets();
}

inline media_status_t AMediaExtractor_seekTo(AMediaExtractor* e, int64_t us, SeekMode) {
    const double frame = std::max<int64_t>(0, us) * double(e->rate) / 1e6;
    e->packet = std::min<uint32_t>(static_cast<uint32_t>(frame / e->fpp), e->packets());
    return AMEDIA_OK;
}
