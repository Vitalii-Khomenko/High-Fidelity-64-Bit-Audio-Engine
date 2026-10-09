#pragma once
// Fake decoder: "decodes" the float packets of the fake container. It holds
// back `latencyPackets` packets like a real codec pipeline, reports the
// output format first, and delivers 16-bit PCM unless float was requested
// (or `forceEncoding` says otherwise).
#include <atomic>
#include <deque>

#include "NdkMediaFormat.h"

typedef struct ANativeWindow ANativeWindow;
typedef struct AMediaCrypto AMediaCrypto;

enum : int32_t {
    AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM = 4,
};
enum : ssize_t {
    AMEDIACODEC_INFO_TRY_AGAIN_LATER = -1,
    AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED = -2,
    AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED = -3,
};

struct AMediaCodecBufferInfo {
    int32_t offset;
    int32_t size;
    int64_t presentationTimeUs;
    uint32_t flags;
};

namespace fake_media {
inline std::atomic<int> latencyPackets{2};
inline std::atomic<int> forceEncoding{0};          // 0: honour the request
inline std::atomic<bool> refuseCodec{false};
}

struct AMediaCodec {
    struct Packet { std::vector<uint8_t> data; int64_t pts; bool eos; };
    uint32_t channels = 2, rate = 44100;
    int32_t encoding = 2;
    bool started = false, formatSent = false, eosQueued = false;
    std::vector<uint8_t> input = std::vector<uint8_t>(1 << 20);
    std::vector<uint8_t> output;
    std::deque<Packet> pending;
};

inline AMediaCodec* AMediaCodec_createDecoderByType(const char*) {
    return fake_media::refuseCodec ? nullptr : new AMediaCodec();
}
inline media_status_t AMediaCodec_delete(AMediaCodec* c) { delete c; return AMEDIA_OK; }

inline media_status_t AMediaCodec_configure(AMediaCodec* c, const AMediaFormat* format, ANativeWindow*, AMediaCrypto*, uint32_t) {
    auto* f = const_cast<AMediaFormat*>(format);
    int32_t v = 0;
    if (AMediaFormat_getInt32(f, "channel-count", &v)) c->channels = static_cast<uint32_t>(v);
    if (AMediaFormat_getInt32(f, "sample-rate", &v)) c->rate = static_cast<uint32_t>(v);
    c->encoding = AMediaFormat_getInt32(f, "pcm-encoding", &v) ? v : 2;
    if (fake_media::forceEncoding) c->encoding = fake_media::forceEncoding;
    return AMEDIA_OK;
}
inline media_status_t AMediaCodec_start(AMediaCodec* c) { c->started = true; return AMEDIA_OK; }
inline media_status_t AMediaCodec_stop(AMediaCodec* c) { c->started = false; return AMEDIA_OK; }
inline media_status_t AMediaCodec_flush(AMediaCodec* c) {
    c->pending.clear();
    c->eosQueued = false;
    return AMEDIA_OK;
}

inline ssize_t AMediaCodec_dequeueInputBuffer(AMediaCodec* c, int64_t) {
    return c->eosQueued || c->pending.size() > 8 ? AMEDIACODEC_INFO_TRY_AGAIN_LATER : 0;
}
inline uint8_t* AMediaCodec_getInputBuffer(AMediaCodec* c, size_t, size_t* size) {
    *size = c->input.size();
    return c->input.data();
}
inline media_status_t AMediaCodec_queueInputBuffer(AMediaCodec* c, size_t, off_t offset, size_t size, uint64_t time, uint32_t flags) {
    AMediaCodec::Packet p;
    p.data.assign(c->input.begin() + offset, c->input.begin() + offset + static_cast<off_t>(size));
    p.pts = static_cast<int64_t>(time);
    p.eos = (flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0;
    if (p.eos) c->eosQueued = true;
    c->pending.push_back(std::move(p));
    return AMEDIA_OK;
}

inline ssize_t AMediaCodec_dequeueOutputBuffer(AMediaCodec* c, AMediaCodecBufferInfo* info, int64_t) {
    if (!c->formatSent) {
        c->formatSent = true;
        return AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED;
    }
    if (c->pending.empty() || (!c->eosQueued && static_cast<int>(c->pending.size()) <= fake_media::latencyPackets)) {
        return AMEDIACODEC_INFO_TRY_AGAIN_LATER;
    }
    AMediaCodec::Packet p = std::move(c->pending.front());
    c->pending.pop_front();
    const size_t samples = p.data.size() / 4;
    const size_t width = c->encoding == 4 || c->encoding == 22 ? 4 : c->encoding == 21 ? 3 : 2;
    c->output.assign(samples * width, 0);
    for (size_t i = 0; i < samples; ++i) {
        float f;
        std::memcpy(&f, p.data.data() + i * 4, 4);
        uint8_t* o = c->output.data() + i * width;
        if (c->encoding == 4) {
            std::memcpy(o, &f, 4);
        } else {
            const double scale = width == 2 ? 32767.0 : width == 3 ? 8388607.0 : 2147483647.0;
            const int64_t v = static_cast<int64_t>(std::lround(std::max(-1.0f, std::min(1.0f, f)) * scale));
            for (size_t b = 0; b < width; ++b) o[b] = static_cast<uint8_t>(v >> (8 * b));
        }
    }
    info->offset = 0;
    info->size = static_cast<int32_t>(c->output.size());
    info->presentationTimeUs = p.pts;
    info->flags = p.eos ? AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM : 0;
    return 0;
}

inline uint8_t* AMediaCodec_getOutputBuffer(AMediaCodec* c, size_t, size_t* size) {
    *size = c->output.size();
    return c->output.data();
}
inline media_status_t AMediaCodec_releaseOutputBuffer(AMediaCodec*, size_t, bool) { return AMEDIA_OK; }

inline AMediaFormat* AMediaCodec_getOutputFormat(AMediaCodec* c) {
    auto* f = AMediaFormat_new();
    f->i32["sample-rate"] = static_cast<int32_t>(c->rate);
    f->i32["channel-count"] = static_cast<int32_t>(c->channels);
    f->i32["pcm-encoding"] = c->encoding;
    return f;
}
