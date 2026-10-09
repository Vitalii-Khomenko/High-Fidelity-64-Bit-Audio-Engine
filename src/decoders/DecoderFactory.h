#pragma once

#include <cstring>
#include <memory>
#include <unistd.h>

#include "ApeDecoder.h"
#include "DsdDecoder.h"
#include "FlacDecoder.h"
#include "Mp3Decoder.h"
#include "OggOpusDecoder.h"
#include "TtaDecoder.h"
#include "VorbisDecoder.h"
#include "WavDecoder.h"
#include "WavPackDecoder.h"
#if HIFI_MEDIACODEC
#include "MediaCodecDecoder.h"
#endif

namespace audio_engine {
namespace decoders {

enum class Container {
    Unknown, Flac, OggFlac, OggVorbis, OggOpus, Ogg, Riff, Dsd, Mp3, WavPack, Ape, Tta, Mp4, Adts, Matroska,
};

namespace sniffing {

inline uint32_t synchsafe(const uint8_t* p) {
    return (uint32_t(p[0] & 0x7f) << 21) | (uint32_t(p[1] & 0x7f) << 14) | (uint32_t(p[2] & 0x7f) << 7) | uint32_t(p[3] & 0x7f);
}

/** Codec inside an Ogg stream, from the first packet of the first page. */
inline Container oggKind(int fd, uint64_t at) {
    uint8_t page[27 + 255 + 8] = {};
    const ssize_t n = ::pread(fd, page, sizeof(page), static_cast<off_t>(at));
    if (n < 28) return Container::Ogg;
    const size_t segments = page[26];
    const size_t packet = 27 + segments;
    if (static_cast<size_t>(n) < packet + 8) return Container::Ogg;
    const uint8_t* p = page + packet;
    if (!std::memcmp(p, "OpusHead", 8)) return Container::OggOpus;
    if (p[0] == 0x01 && !std::memcmp(p + 1, "vorbis", 6)) return Container::OggVorbis;
    if (p[0] == 0x7F && !std::memcmp(p + 1, "FLAC", 4)) return Container::OggFlac;
    return Container::Ogg;
}

} // namespace sniffing

/**
 * Identifies a file from its first bytes. An ID3v2 tag in front (common on
 * MP3, sometimes on FLAC, APE and TTA) is skipped before looking.
 */
inline Container sniff(int fd) {
    uint64_t at = 0;
    for (int guard = 0; guard < 4; ++guard) {
        uint8_t h[16] = {};
        const ssize_t n = ::pread(fd, h, sizeof(h), static_cast<off_t>(at));
        if (n < 4) return Container::Unknown;
        if (!std::memcmp(h, "ID3", 3) && n >= 10 && h[3] >= 2 && h[3] <= 4) {
            at += 10 + sniffing::synchsafe(h + 6) + ((h[5] & 0x10) ? 10 : 0);
            continue;
        }
        if (!std::memcmp(h, "fLaC", 4)) return Container::Flac;
        if (!std::memcmp(h, "OggS", 4)) return sniffing::oggKind(fd, at);
        if (!std::memcmp(h, "RIFF", 4) || !std::memcmp(h, "RF64", 4) || !std::memcmp(h, "riff", 4) ||
            !std::memcmp(h, "FORM", 4) || !std::memcmp(h, "RIFX", 4)) return Container::Riff;
        if (!std::memcmp(h, "DSD ", 4) || !std::memcmp(h, "FRM8", 4)) return Container::Dsd;
        if (!std::memcmp(h, "wvpk", 4)) return Container::WavPack;
        if (!std::memcmp(h, "MAC ", 4)) return Container::Ape;
        if (!std::memcmp(h, "TTA1", 4)) return Container::Tta;
        if (n >= 8 && !std::memcmp(h + 4, "ftyp", 4)) return Container::Mp4;
        if (h[0] == 0x1A && h[1] == 0x45 && h[2] == 0xDF && h[3] == 0xA3) return Container::Matroska;
        // ADTS AAC: 12-bit sync, layer bits 00. MPEG audio layers have non-zero layer bits.
        if (h[0] == 0xFF && (h[1] & 0xF6) == 0xF0) return Container::Adts;
        if (h[0] == 0xFF && (h[1] & 0xE0) == 0xE0) return Container::Mp3;
        // An ID3v2 tag followed by something else is most likely MP3 with junk.
        return at > 0 ? Container::Mp3 : Container::Unknown;
    }
    return Container::Unknown;
}

inline std::unique_ptr<IAudioDecoder> makeDecoder(Container kind) {
    switch (kind) {
        case Container::Flac:
        case Container::OggFlac: return std::make_unique<FlacDecoder>();
        case Container::OggVorbis: return std::make_unique<VorbisDecoder>();
        case Container::OggOpus: return std::make_unique<OggOpusDecoder>();
        case Container::Riff: return std::make_unique<WavDecoder>();
        case Container::Dsd: return std::make_unique<DsdDecoder>();
        case Container::Mp3: return std::make_unique<Mp3Decoder>();
        case Container::WavPack: return std::make_unique<WavPackDecoder>();
        case Container::Ape: return std::make_unique<ApeDecoder>();
        case Container::Tta: return std::make_unique<TtaDecoder>();
#if HIFI_MEDIACODEC
        case Container::Mp4:
        case Container::Adts:
        case Container::Matroska:
        case Container::Ogg: return std::make_unique<MediaCodecDecoder>();
#endif
        default: return nullptr;
    }
}

/** Opens fd (not taken over) with the decoder its signature calls for. */
inline std::unique_ptr<IAudioDecoder> openDecoder(int fd) {
    const Container kind = sniff(fd);
    if (auto decoder = makeDecoder(kind)) {
        if (decoder->openFd(fd)) return decoder;
        return nullptr;
    }
    // No recognisable signature (e.g. MP3 behind junk bytes): try permissive decoders.
    for (Container fallback : {Container::Flac, Container::Riff, Container::Mp3}) {
        auto decoder = makeDecoder(fallback);
        if (decoder && decoder->openFd(fd)) return decoder;
    }
#if HIFI_MEDIACODEC
    auto decoder = std::make_unique<MediaCodecDecoder>();
    if (decoder->openFd(fd)) return decoder;
#endif
    return nullptr;
}

} // namespace decoders
} // namespace audio_engine
