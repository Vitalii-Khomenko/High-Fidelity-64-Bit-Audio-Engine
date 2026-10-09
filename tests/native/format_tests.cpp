// Formats, CUE ranges, container sniffing and tags. Run through tests/native/run.sh.
//
// Lossless fixtures (WavPack, Monkey's Audio, TTA) and Ogg Opus are encoded here
// with the same vendored libraries, so they need no external tools. The
// MediaCodec path runs against the fake NDK media API in stubs/media.
#include <cstring>
#include <fcntl.h>
#include <random>

#include "test_util.h"

#include "decoders/DecoderFactory.h"
#include "decoders/DurationProbe.h"
#include "decoders/RangeDecoder.h"
#include "tags/TagReader.h"

#include <ogg/ogg.h>
#include <opus.h>

using namespace audio_engine;
using namespace test;

// ── Helpers ─────────────────────────────────────────────────────────────────

std::vector<std::vector<double>> decodeAll(decoders::IAudioDecoder& d) {
    std::vector<std::vector<double>> out(d.getNumChannels());
    core::AudioBuffer buf(d.getNumChannels(), 1000, d.getSampleRate());
    for (;;) {
        const size_t n = d.readFrames(buf, 1000);
        if (n == 0) break;
        for (size_t ch = 0; ch < out.size(); ++ch) {
            out[ch].insert(out[ch].end(), buf.getReadPointer(ch), buf.getReadPointer(ch) + n);
        }
    }
    return out;
}

std::string tempPath(const char* suffix) {
    char path[] = "/tmp/hifi-format-XXXXXX";
    const int fd = mkstemp(path);
    CHECK(fd >= 0);
    close(fd);
    unlink(path);
    return std::string(path) + suffix;
}

void writeFile(const std::string& path, const Bytes& bytes) {
    FILE* f = std::fopen(path.c_str(), "wb");
    CHECK(f);
    CHECK(std::fwrite(bytes.data(), 1, bytes.size(), f) == bytes.size());
    std::fclose(f);
}

/** Interleaved integer PCM: a sine per channel plus a little noise, so codecs have work to do. */
struct Pcm {
    uint32_t rate = 44100, channels = 2, bits = 16;
    std::vector<int32_t> samples;
    size_t frames() const { return samples.size() / channels; }
    double value(size_t frame, size_t ch) const { return samples[frame * channels + ch] / std::ldexp(1.0, bits - 1); }
};

Pcm makePcm(uint32_t rate, uint32_t channels, uint32_t bits, size_t frames) {
    Pcm p;
    p.rate = rate; p.channels = channels; p.bits = bits;
    p.samples.resize(frames * channels);
    std::mt19937 rng(1234);
    std::uniform_int_distribution<int> noise(-64, 64);
    const double full = std::ldexp(1.0, bits - 1) - 1.0;
    for (size_t f = 0; f < frames; ++f) {
        for (uint32_t c = 0; c < channels; ++c) {
            const double v = 0.5 * std::sin(2.0 * M_PI * 440.0 * (c + 1) * f / rate);
            p.samples[f * channels + c] = static_cast<int32_t>(std::lround(v * full)) + noise(rng) * (bits > 16 ? 256 : 1);
        }
    }
    return p;
}

Bytes wavBytes(const Pcm& p) {
    const uint32_t bytes = p.bits / 8;
    const uint32_t data = static_cast<uint32_t>(p.samples.size() * bytes);
    Bytes b; tag(b, "RIFF"); le(b, 36 + data, 4); tag(b, "WAVE"); tag(b, "fmt "); le(b, 16, 4);
    le(b, 1, 2); le(b, p.channels, 2); le(b, p.rate, 4); le(b, p.rate * p.channels * bytes, 4);
    le(b, p.channels * bytes, 2); le(b, p.bits, 2); tag(b, "data"); le(b, data, 4);
    for (int32_t s : p.samples) le(b, static_cast<uint32_t>(s), static_cast<int>(bytes));
    return b;
}

// ── Encoders for fixtures ───────────────────────────────────────────────────

Bytes encodeWavPack(const Pcm& p) {
    Bytes out;
    auto blockOut = [](void* id, void* data, int32_t count) -> int {
        auto* o = static_cast<Bytes*>(id);
        o->insert(o->end(), static_cast<uint8_t*>(data), static_cast<uint8_t*>(data) + count);
        return 1;
    };
    WavpackContext* wpc = WavpackOpenFileOutput(blockOut, &out, nullptr);
    CHECK(wpc);
    WavpackConfig cfg{};
    cfg.bytes_per_sample = static_cast<int>(p.bits / 8);
    cfg.bits_per_sample = static_cast<int>(p.bits);
    cfg.channel_mask = p.channels == 2 ? 3 : 4;
    cfg.num_channels = static_cast<int>(p.channels);
    cfg.sample_rate = static_cast<int32_t>(p.rate);
    CHECK(WavpackSetConfiguration64(wpc, &cfg, static_cast<int64_t>(p.frames()), nullptr));
    CHECK(WavpackPackInit(wpc));
    std::vector<int32_t> samples = p.samples;
    CHECK(WavpackPackSamples(wpc, samples.data(), static_cast<uint32_t>(p.frames())));
    CHECK(WavpackFlushSamples(wpc));
    WavpackCloseFile(wpc);
    return out;
}

struct TtaSink {
    TTA_io_callback cb;
    Bytes* data;
    size_t pos;
};

Bytes encodeTta(const Pcm& p) {
    Bytes out;
    alignas(16) TtaSink sink{};
    sink.data = &out;
    sink.cb.write = [](TTA_io_callback* cb, TTAuint8* buf, TTAuint32 n) -> TTAint32 {
        auto* s = reinterpret_cast<TtaSink*>(cb);
        if (s->data->size() < s->pos + n) s->data->resize(s->pos + n);
        std::memcpy(s->data->data() + s->pos, buf, n);
        s->pos += n;
        return static_cast<TTAint32>(n);
    };
    sink.cb.seek = [](TTA_io_callback* cb, TTAint64 offset) -> TTAint64 {
        reinterpret_cast<TtaSink*>(cb)->pos = static_cast<size_t>(offset);
        return offset;
    };
    sink.cb.read = [](TTA_io_callback*, TTAuint8*, TTAuint32) -> TTAint32 { return 0; };
    tta::tta_encoder encoder(&sink.cb);
    TTA_info info{TTA_FORMAT_SIMPLE, p.channels, p.bits, p.rate, static_cast<TTAuint32>(p.frames())};
    encoder.init_set_info(&info, 0);
    const size_t depth = p.bits / 8;
    Bytes input;
    for (int32_t s : p.samples) le(input, static_cast<uint32_t>(s), static_cast<int>(depth));
    input.resize(input.size() + 4, 0);   // libtta reads 24-bit samples 4 bytes at a time
    encoder.process_stream(input.data(), static_cast<TTAuint32>(input.size() - 4));
    encoder.finalize();
    return out;
}

Bytes encodeApe(const Pcm& p) {
    const std::string wav = tempPath(".wav");
    const std::string ape = tempPath(".ape");
    writeFile(wav, wavBytes(p));
    CHECK(CompressFile(wav.c_str(), ape.c_str(), APE_COMPRESSION_LEVEL_HIGH) == 0);
    Bytes out = readFile(ape);
    unlink(wav.c_str());
    unlink(ape.c_str());
    return out;
}

/** Ogg Opus with OpusHead / OpusTags and exact end trimming (granule position). */
Bytes encodeOggOpus(const std::vector<float>& interleaved, int channels, const std::vector<std::string>& comments) {
    int error = 0;
    OpusEncoder* enc = opus_encoder_create(48000, channels, OPUS_APPLICATION_AUDIO, &error);
    CHECK(enc && error == OPUS_OK);
    opus_encoder_ctl(enc, OPUS_SET_BITRATE(160000));
    opus_int32 preskip = 0;
    opus_encoder_ctl(enc, OPUS_GET_LOOKAHEAD(&preskip));

    Bytes out;
    ogg_stream_state os;
    ogg_stream_init(&os, 0x1234);
    auto flush = [&](bool force) {
        ogg_page page;
        while (force ? ogg_stream_flush(&os, &page) : ogg_stream_pageout(&os, &page)) {
            out.insert(out.end(), page.header, page.header + page.header_len);
            out.insert(out.end(), page.body, page.body + page.body_len);
        }
    };
    auto packet = [&](Bytes& data, bool bos, bool eos, int64_t granule, int64_t no) {
        ogg_packet op{};
        op.packet = data.data();
        op.bytes = static_cast<long>(data.size());
        op.b_o_s = bos; op.e_o_s = eos; op.granulepos = granule; op.packetno = no;
        CHECK(ogg_stream_packetin(&os, &op) == 0);
    };

    Bytes head{'O', 'p', 'u', 's', 'H', 'e', 'a', 'd', 1, static_cast<uint8_t>(channels)};
    le(head, static_cast<uint32_t>(preskip), 2); le(head, 48000, 4); le(head, 0, 2); head.push_back(0);
    packet(head, true, false, 0, 0);
    flush(true);
    Bytes tags{'O', 'p', 'u', 's', 'T', 'a', 'g', 's'};
    le(tags, 4, 4); tag(tags, "test");
    le(tags, comments.size(), 4);
    for (const auto& c : comments) { le(tags, c.size(), 4); tags.insert(tags.end(), c.begin(), c.end()); }
    packet(tags, false, false, 0, 1);
    flush(true);

    const size_t frames = interleaved.size() / static_cast<size_t>(channels);
    const size_t step = 960;
    std::vector<float> chunk(step * static_cast<size_t>(channels));
    int64_t no = 2;
    // Pad the end so the encoder flushes its lookahead; the granule trims it again.
    for (size_t at = 0; at < frames + static_cast<size_t>(preskip); at += step) {
        std::fill(chunk.begin(), chunk.end(), 0.0f);
        for (size_t i = 0; i < step && at + i < frames; ++i) {
            for (int c = 0; c < channels; ++c) chunk[i * channels + c] = interleaved[(at + i) * channels + c];
        }
        unsigned char data[4000];
        const int bytes = opus_encode_float(enc, chunk.data(), static_cast<int>(step), data, sizeof(data));
        CHECK(bytes > 0);
        const bool last = at + step >= frames + static_cast<size_t>(preskip);
        const int64_t granule = last ? static_cast<int64_t>(frames) + preskip : static_cast<int64_t>(at + step);
        Bytes pkt(data, data + bytes);
        packet(pkt, false, last, granule, no++);
        flush(false);
    }
    flush(true);
    ogg_stream_clear(&os);
    opus_encoder_destroy(enc);
    return out;
}

Bytes fakeMediaCodec(const char* mime, uint32_t rate, uint32_t channels, uint32_t fpp, uint32_t delay,
                     uint32_t padding, const std::vector<float>& interleaved, uint32_t alacBits = 0) {
    Bytes b{'F', 'A', 'K', 'E', 'M', 'C', '0', '1'};
    Bytes name(24, 0);
    std::memcpy(name.data(), mime, std::strlen(mime));
    b.insert(b.end(), name.begin(), name.end());
    const uint32_t frames = static_cast<uint32_t>(interleaved.size() / channels);
    for (uint32_t v : {rate, channels, fpp, delay, padding, frames, alacBits}) le(b, v, 4);
    for (float f : interleaved) { uint32_t u; std::memcpy(&u, &f, 4); le(b, u, 4); }
    return b;
}

// ── Lossless formats: bit-exact decode and sample-exact seeking ─────────────

void checkLossless(const char* name, const Bytes& file, const Pcm& p, decoders::Codec codec) {
    const int fd = tempFile(file);
    auto d = decoders::openDecoder(fd);
    CHECK(d != nullptr);
    close(fd);
    CHECK(d->getCodec() == codec);
    CHECK(d->getSampleRate() == p.rate && d->getNumChannels() == p.channels);
    CHECK(d->getBitsPerSample() == p.bits);
    CHECK(d->getTotalFrames() == p.frames());
    const auto pcm = decodeAll(*d);
    CHECK(pcm[0].size() == p.frames());
    for (size_t f = 0; f < p.frames(); ++f) {
        for (size_t c = 0; c < p.channels; ++c) {
            if (pcm[c][f] != p.value(f, c)) {
                std::fprintf(stderr, "%s: frame %zu ch %zu: %.9f != %.9f\n", name, f, c, pcm[c][f], p.value(f, c));
                CHECK(false);
            }
        }
    }
    for (uint64_t target : {uint64_t(0), uint64_t(1), uint64_t(4097), uint64_t(p.frames() / 2), uint64_t(p.frames() - 64)}) {
        CHECK(d->seekToFrame(target));
        CHECK(d->getCurrentFrame() == target);
        core::AudioBuffer buf(p.channels, 64, p.rate);
        CHECK(d->readFrames(buf, 64) == 64);
        for (size_t i = 0; i < 64; ++i) CHECK(buf.getReadPointer(0)[i] == p.value(target + i, 0));
    }
    CHECK(d->seekToFrame(p.frames()));
    core::AudioBuffer buf(p.channels, 16, p.rate);
    CHECK(d->readFrames(buf, 16) == 0);
    std::printf("  %s %u-bit: bit-exact, seeks exact\n", name, p.bits);
}

void testLosslessFormats() {
    for (uint32_t bits : {16u, 24u}) {
        const Pcm p = makePcm(44100, 2, bits, 50000);
        checkLossless("WavPack", encodeWavPack(p), p, decoders::Codec::WavPack);
        checkLossless("TTA", encodeTta(p), p, decoders::Codec::Tta);
        checkLossless("APE", encodeApe(p), p, decoders::Codec::Ape);
    }
    // Mono and 96 kHz.
    const Pcm mono = makePcm(96000, 1, 24, 30000);
    checkLossless("WavPack", encodeWavPack(mono), mono, decoders::Codec::WavPack);
    checkLossless("TTA", encodeTta(mono), mono, decoders::Codec::Tta);
    // Garbage with a valid signature is refused cleanly.
    for (const char* magic : {"wvpk", "MAC ", "TTA1"}) {
        Bytes junk(4096, 0x5A);
        std::memcpy(junk.data(), magic, 4);
        const int fd = tempFile(junk);
        CHECK(decoders::openDecoder(fd) == nullptr);
        close(fd);
    }
}

// ── Opus and Vorbis ─────────────────────────────────────────────────────────

void testOpus() {
    const size_t frames = 72000;   // 1.5 s at 48 kHz
    std::vector<float> src(frames * 2);
    for (size_t f = 0; f < frames; ++f) {
        src[f * 2] = static_cast<float>(0.3 * std::sin(2.0 * M_PI * 1000.0 * f / 48000.0));
        src[f * 2 + 1] = static_cast<float>(0.2 * std::sin(2.0 * M_PI * 500.0 * f / 48000.0));
    }
    const Bytes file = encodeOggOpus(src, 2, {"TITLE=Opus Title", "ARTIST=Opus Artist", "R128_TRACK_GAIN=-512"});
    const int fd = tempFile(file);
    CHECK(decoders::sniff(fd) == decoders::Container::OggOpus);
    auto d = decoders::openDecoder(fd);
    CHECK(d && d->getCodec() == decoders::Codec::Opus);
    CHECK(d->getSampleRate() == 48000 && d->getNumChannels() == 2 && d->getBitsPerSample() == 0);
    CHECK(d->getTotalFrames() == frames);   // pre-skip and end padding trimmed: gapless
    const auto pcm = decodeAll(*d);
    CHECK(pcm[0].size() == frames);
    CHECK_NEAR(fitSine(pcm[0], 10000, 60000, 1000.0, 48000.0), 0.3, 0.01);
    CHECK_NEAR(fitSine(pcm[1], 10000, 60000, 500.0, 48000.0), 0.2, 0.01);
    CHECK(d->seekToFrame(30000));
    core::AudioBuffer buf(2, 512, 48000);
    CHECK(d->readFrames(buf, 512) == 512);
    double err = 0.0;
    for (size_t i = 0; i < 512; ++i) err = std::max(err, std::fabs(buf.getReadPointer(0)[i] - pcm[0][30000 + i]));
    // Sample-aligned, but not bit-identical: CELT predicts band energies from
    // earlier frames, so a decoder started by a seek needs a few frames to agree.
    CHECK(err < 0.05);
    core::AudioBuffer later(2, 4096, 48000);
    CHECK(d->readFrames(later, 4096) == 4096);
    std::vector<double> after(later.getReadPointer(0), later.getReadPointer(0) + 4096);
    double settled = 0.0;
    for (size_t i = 2048; i < 4096; ++i) settled = std::max(settled, std::fabs(after[i] - pcm[0][30512 + i]));
    std::printf("  Opus after seek: level %.4f at 10-85 ms, difference %.5f at 50-95 ms\n",
                fitSine(after, 0, 4096, 1000.0, 48000.0), settled);
    CHECK(settled < 0.01);   // converged within 100 ms
    const auto t = tags::readTags(fd);
    CHECK(t.title == "Opus Title" && t.artist == "Opus Artist");
    CHECK(t.replayGain.hasTrack);
    CHECK_NEAR(t.replayGain.trackGainDb, 3.0, 1e-4);   // -2 dB at -23 LUFS = +3 dB at -18 LUFS
    close(fd);
    std::printf("  Opus: gapless length, level, aligned seeks (peak difference %.3f)\n", err);
}

void testVorbis(const std::string& path) {
    const int fd = ::open(path.c_str(), O_RDONLY);
    CHECK(fd >= 0);
    CHECK(decoders::sniff(fd) == decoders::Container::OggVorbis);
    auto d = decoders::openDecoder(fd);
    CHECK(d && d->getCodec() == decoders::Codec::Vorbis);
    CHECK(d->getSampleRate() == 44100 && d->getNumChannels() == 2);
    const uint64_t total = d->getTotalFrames();
    CHECK(total == 52920);
    const auto pcm = decodeAll(*d);
    CHECK(pcm[0].size() == total);
    CHECK_NEAR(fitSine(pcm[0], 5000, 45000, 440.0, 44100.0), 0.25, 0.01);
    for (uint64_t target : {uint64_t(12345), uint64_t(40000)}) {
        CHECK(d->seekToFrame(target));
        core::AudioBuffer buf(2, 256, 44100);
        CHECK(d->readFrames(buf, 256) == 256);
        for (size_t i = 0; i < 256; ++i) CHECK_NEAR(buf.getReadPointer(0)[i], pcm[0][target + i], 1e-6);
    }
    const auto t = tags::readTags(fd);
    CHECK(t.title == "Ogg Title");
    close(fd);
    std::puts("  Vorbis: length, level, exact seeks, comments");
}

void testChannelOrder() {
    // 5.1 in Vorbis order (L C R BL BR LFE) lands in WAVE order (L R C LFE BL BR).
    const auto map = decoders::waveFromVorbis(6);
    const int expected[6] = {0, 2, 1, 5, 3, 4};
    for (int i = 0; i < 6; ++i) CHECK(map[i] == expected[i]);
    CHECK(decoders::waveFromVorbis(2)[0] == 0 && decoders::waveFromVorbis(2)[1] == 1);
}

// ── MediaCodec path (fake codec) ────────────────────────────────────────────

std::vector<float> rampSignal(size_t frames, uint32_t channels) {
    std::vector<float> s(frames * channels);
    for (size_t f = 0; f < frames; ++f) {
        for (uint32_t c = 0; c < channels; ++c) {
            s[f * channels + c] = static_cast<float>(0.4 * std::sin(0.01 * f + c) + (c ? -1e-4 : 1e-4) * ((f % 7) - 3.0));
        }
    }
    return s;
}

void testMediaCodec() {
    const uint32_t delay = 2112, padding = 500;
    const size_t body = 40000;
    const auto signal = rampSignal(delay + body + padding, 2);
    for (int latency : {0, 2, 5}) {
        fake_media::latencyPackets = latency;
        const int fd = tempFile(fakeMediaCodec("audio/mp4a-latm", 44100, 2, 1024, delay, padding, signal));
        decoders::MediaCodecDecoder d;
        CHECK(d.openFd(fd));
        close(fd);
        CHECK(d.getCodec() == decoders::Codec::Aac && d.getBitsPerSample() == 0);
        CHECK(d.getTotalFrames() == body);   // encoder delay and padding trimmed
        const auto pcm = decodeAll(d);
        CHECK(pcm[0].size() == body);
        for (size_t i = 0; i < body; ++i) {
            CHECK(pcm[0][i] == static_cast<double>(signal[(delay + i) * 2]));
            CHECK(pcm[1][i] == static_cast<double>(signal[(delay + i) * 2 + 1]));
        }
        for (uint64_t target : {uint64_t(0), uint64_t(5), uint64_t(12345), uint64_t(body - 100)}) {
            CHECK(d.seekToFrame(target));
            core::AudioBuffer buf(2, 100, 44100);
            CHECK(d.readFrames(buf, 100) == 100);
            for (size_t i = 0; i < 100; ++i) CHECK(buf.getReadPointer(1)[i] == pcm[1][target + i]);
        }
    }
    fake_media::latencyPackets = 2;

    // A decoder that ignores the float request delivers 16-bit PCM.
    fake_media::forceEncoding = 2;
    {
        const int fd = tempFile(fakeMediaCodec("audio/mp4a-latm", 48000, 2, 1024, 0, 0, signal));
        decoders::MediaCodecDecoder d;
        CHECK(d.openFd(fd));
        close(fd);
        const auto pcm = decodeAll(d);
        CHECK(pcm[0].size() == signal.size() / 2);
        for (size_t i = 0; i < pcm[0].size(); ++i) CHECK_NEAR(pcm[0][i], signal[i * 2], 1.0 / 32000.0);
    }
    fake_media::forceEncoding = 0;

    // ALAC reports its bit depth from the magic cookie.
    {
        const int fd = tempFile(fakeMediaCodec("audio/alac", 96000, 2, 4096, 0, 0, signal, 24));
        decoders::MediaCodecDecoder d;
        CHECK(d.openFd(fd) && d.getCodec() == decoders::Codec::Alac && d.getBitsPerSample() == 24);
        close(fd);
    }
    // No decoder for the stream: open fails.
    fake_media::refuseCodec = true;
    {
        const int fd = tempFile(fakeMediaCodec("audio/mp4a-latm", 44100, 2, 1024, 0, 0, signal));
        decoders::MediaCodecDecoder d;
        CHECK(!d.openFd(fd));
        close(fd);
    }
    fake_media::refuseCodec = false;
    std::puts("  MediaCodec: float and 16-bit output, delay/padding trimming, exact seeks, ALAC depth");
}

// ── CUE ranges ──────────────────────────────────────────────────────────────

void testRanges() {
    const Pcm p = makePcm(44100, 2, 16, 150000);
    const Bytes wav = wavBytes(p);
    auto open = [&](int64_t startUs, int64_t endUs) {
        const int fd = tempFile(wav);
        auto d = decoders::RangeDecoder::wrap(decoders::openDecoder(fd), startUs, endUs);
        close(fd);
        return d;
    };
    auto range = open(1000000, 2000000);
    CHECK(range && range->getTotalFrames() == 44100);
    const auto pcm = decodeAll(*range);
    CHECK(pcm[0].size() == 44100);
    for (size_t i = 0; i < 44100; ++i) CHECK(pcm[0][i] == p.value(44100 + i, 0));
    CHECK(range->seekToFrame(1000));
    core::AudioBuffer buf(2, 10, 44100);
    CHECK(range->readFrames(buf, 10) == 10 && buf.getReadPointer(0)[0] == p.value(45100, 0));

    // CUE tracks of one file join without a gap or an overlap.
    std::vector<double> joined;
    const int64_t cuts[] = {0, 1000000, 2133333, 0};   // 0 at the end: to the end of the file
    for (int i = 0; i < 3; ++i) {
        auto part = open(cuts[i], cuts[i + 1]);
        CHECK(part);
        const auto ch = decodeAll(*part);
        joined.insert(joined.end(), ch[0].begin(), ch[0].end());
    }
    CHECK(joined.size() == p.frames());
    for (size_t i = 0; i < joined.size(); ++i) CHECK(joined[i] == p.value(i, 0));

    CHECK(open(0, 0) && open(0, 0)->getTotalFrames() == p.frames());   // no range: the plain decoder
    CHECK(open(5000000, 0) == nullptr);                                // starts after the end
    CHECK(open(2000000, 1000000) == nullptr);                          // empty
    std::puts("  CUE ranges: exact cut points, gapless joins");
}

// ── Sniffing ────────────────────────────────────────────────────────────────

void testSniffing() {
    using C = decoders::Container;
    auto kind = [](Bytes b) {
        b.resize(std::max<size_t>(b.size(), 64), 0);
        const int fd = tempFile(b);
        const C c = decoders::sniff(fd);
        close(fd);
        return c;
    };
    auto ogg = [](const char* packet, size_t n) {
        Bytes b{'O', 'g', 'g', 'S', 0, 2};
        b.resize(26, 0);
        b.push_back(1);                       // one segment
        b.push_back(static_cast<uint8_t>(n));
        b.insert(b.end(), packet, packet + n);
        return b;
    };
    CHECK(kind({'f', 'L', 'a', 'C'}) == C::Flac);
    Bytes id3flac{'I', 'D', '3', 3, 0, 0, 0, 0, 0, 20};
    id3flac.resize(30, 0);
    for (char c : std::string("fLaC")) id3flac.push_back(static_cast<uint8_t>(c));
    CHECK(kind(id3flac) == C::Flac);
    CHECK(kind(ogg("OpusHead", 8)) == C::OggOpus);
    CHECK(kind(ogg("\x01vorbis", 7)) == C::OggVorbis);
    CHECK(kind(ogg("\x7F" "FLAC", 5)) == C::OggFlac);
    CHECK(kind(ogg("Speex   ", 8)) == C::Ogg);
    CHECK(kind({'w', 'v', 'p', 'k'}) == C::WavPack);
    CHECK(kind({'M', 'A', 'C', ' '}) == C::Ape);
    CHECK(kind({'T', 'T', 'A', '1'}) == C::Tta);
    CHECK(kind({0, 0, 0, 0x20, 'f', 't', 'y', 'p', 'M', '4', 'A', ' '}) == C::Mp4);
    CHECK(kind({0x1A, 0x45, 0xDF, 0xA3}) == C::Matroska);
    CHECK(kind({0xFF, 0xF1, 0x50, 0x80}) == C::Adts);
    CHECK(kind({0xFF, 0xFB, 0x90, 0x64}) == C::Mp3);
    Bytes id3junk{'I', 'D', '3', 4, 0, 0, 0, 0, 0, 4, 1, 2, 3, 4, 9, 9, 9, 9};
    CHECK(kind(id3junk) == C::Mp3);
    CHECK(kind({'R', 'I', 'F', 'F'}) == C::Riff);
    CHECK(kind({1, 2, 3, 4}) == C::Unknown);
    std::puts("  Sniffing: all signatures, ID3v2 prefix skipped");
}

// ── Tags ────────────────────────────────────────────────────────────────────

Bytes flacBlock(int type, const Bytes& body, bool last) {
    Bytes b{static_cast<uint8_t>((last ? 0x80 : 0) | type)};
    be(b, body.size(), 3);
    b.insert(b.end(), body.begin(), body.end());
    return b;
}

Bytes vorbisCommentBody(const std::vector<std::string>& comments) {
    Bytes b;
    le(b, 6, 4); for (char c : std::string("vendor")) b.push_back(static_cast<uint8_t>(c));
    le(b, comments.size(), 4);
    for (const auto& c : comments) { le(b, c.size(), 4); b.insert(b.end(), c.begin(), c.end()); }
    return b;
}

Bytes pictureBody(int type, const std::string& mime, const Bytes& data) {
    Bytes b;
    be(b, static_cast<uint64_t>(type), 4);
    be(b, mime.size(), 4); b.insert(b.end(), mime.begin(), mime.end());
    be(b, 5, 4); for (char c : std::string("cover")) b.push_back(static_cast<uint8_t>(c));
    be(b, 600, 4); be(b, 600, 4); be(b, 24, 4); be(b, 0, 4);
    be(b, data.size(), 4); b.insert(b.end(), data.begin(), data.end());
    return b;
}

Bytes image(uint8_t seed, size_t n) {
    Bytes b{0x89, 'P', 'N', 'G'};
    for (size_t i = 0; b.size() < n; ++i) b.push_back(static_cast<uint8_t>(seed + i * 7));
    return b;
}

void testFlacTags() {
    const Bytes front = image(1, 300), back = image(9, 200);
    Bytes f{'f', 'L', 'a', 'C'};
    const Bytes streaminfo(34, 0);
    for (const Bytes& blk : {flacBlock(0, streaminfo, false),
                             flacBlock(6, pictureBody(4, "image/png", back), false),
                             flacBlock(4, vorbisCommentBody({"TITLE=Привет", "ARTIST=A", "ARTIST=B", "ALBUM=Alb",
                                 "ALBUMARTIST=AA", "TRACKNUMBER=3/12", "DISCNUMBER=1", "DISCTOTAL=2", "DATE=2001-05-12",
                                 "GENRE=Rock", "LYRICS=la la", "REPLAYGAIN_TRACK_GAIN=-3.5 dB", "REPLAYGAIN_ALBUM_GAIN=-4 dB"}), false),
                             flacBlock(6, pictureBody(3, "image/png", front), true)}) {
        f.insert(f.end(), blk.begin(), blk.end());
    }
    const int fd = tempFile(f);
    auto t = tags::readTags(fd);
    CHECK(t.title == "Привет" && t.artist == "A; B" && t.album == "Alb" && t.albumArtist == "AA");
    CHECK(t.track == 3 && t.trackTotal == 12 && t.disc == 1 && t.discTotal == 2);
    CHECK(t.year == "2001" && t.genre == "Rock" && t.lyrics == "la la");
    CHECK(t.replayGain.hasTrack && t.replayGain.hasAlbum);
    CHECK_NEAR(t.replayGain.trackGainDb, -3.5, 1e-5);
    CHECK(t.hasPicture && t.picture.data.empty());   // not read unless asked for
    t = tags::readTags(fd, true);
    CHECK(t.picture.data == front && t.picture.mime == "image/png" && t.picture.type == 3);   // front beats back
    close(fd);
}

Bytes id3Frame(int version, const char* id, const Bytes& payload, uint8_t flags2 = 0) {
    Bytes f;
    for (int i = 0; i < 4; ++i) f.push_back(static_cast<uint8_t>(id[i]));
    const uint32_t size = static_cast<uint32_t>(payload.size());
    if (version == 4) { for (int s = 21; s >= 0; s -= 7) f.push_back(static_cast<uint8_t>((size >> s) & 0x7f)); }
    else be(f, size, 4);
    f.push_back(0); f.push_back(flags2);
    f.insert(f.end(), payload.begin(), payload.end());
    return f;
}

Bytes id3Tag(int version, const std::vector<Bytes>& frames, uint8_t flags = 0) {
    Bytes body;
    for (const auto& f : frames) body.insert(body.end(), f.begin(), f.end());
    body.resize(body.size() + 16, 0);
    Bytes h{'I', 'D', '3', static_cast<uint8_t>(version), 0, flags};
    const uint32_t size = static_cast<uint32_t>(body.size());
    for (int s = 21; s >= 0; s -= 7) h.push_back(static_cast<uint8_t>((size >> s) & 0x7f));
    h.insert(h.end(), body.begin(), body.end());
    return h;
}

Bytes text(uint8_t encoding, std::initializer_list<uint8_t> bytes) {
    Bytes b{encoding};
    b.insert(b.end(), bytes);
    return b;
}
Bytes text(uint8_t encoding, const std::string& s) {
    Bytes b{encoding};
    b.insert(b.end(), s.begin(), s.end());
    return b;
}
Bytes utf16(const std::u16string& s) {
    Bytes b{1, 0xFF, 0xFE};
    for (char16_t c : s) { b.push_back(static_cast<uint8_t>(c)); b.push_back(static_cast<uint8_t>(c >> 8)); }
    return b;
}

Bytes apic(int type, const Bytes& data) {
    Bytes b{0};
    for (char c : std::string("image/jpeg")) b.push_back(static_cast<uint8_t>(c));
    b.push_back(0);
    b.push_back(static_cast<uint8_t>(type));
    b.push_back(0);   // empty description
    b.insert(b.end(), data.begin(), data.end());
    return b;
}

Bytes id3v1(const std::string& title, const std::string& album, uint8_t track, uint8_t genre) {
    Bytes b{'T', 'A', 'G'};
    auto field = [&](const std::string& s, size_t n) { Bytes f(n, 0); std::memcpy(f.data(), s.data(), std::min(n, s.size())); b.insert(b.end(), f.begin(), f.end()); };
    field(title, 30); field("V1 Artist", 30); field(album, 30); field("1987", 4);
    Bytes comment(30, 0); comment[29] = track; b.insert(b.end(), comment.begin(), comment.end());
    b.push_back(genre);
    return b;
}

void testId3Tags() {
    const Bytes front = image(3, 500), back = image(5, 400);
    // v2.3: UTF-16 title, Windows-1251 artist labelled Latin-1, real Latin-1 album.
    Bytes file = id3Tag(3, {
        id3Frame(3, "TIT2", utf16(u"Тест")),
        id3Frame(3, "TPE1", text(0, {0xCA, 0xE8, 0xED, 0xEE})),                 // "Кино" in 1251
        id3Frame(3, "TALB", text(0, {'C', 'a', 'f', 0xE9})),                   // "Café"
        id3Frame(3, "TRCK", text(0, "7")),
        id3Frame(3, "TCON", text(0, "(17)")),
        id3Frame(3, "USLT", Bytes{0, 'e', 'n', 'g', 0, 'w', 'o', 'r', 'd', 's'}),
        id3Frame(3, "APIC", apic(4, back)),
        id3Frame(3, "APIC", apic(3, front)),
        id3Frame(3, "TXXX", Bytes{0, 'R', 'E', 'P', 'L', 'A', 'Y', 'G', 'A', 'I', 'N', '_', 'T', 'R', 'A', 'C', 'K', '_',
                                  'G', 'A', 'I', 'N', 0, '-', '1', '.', '5', ' ', 'd', 'B'}),
    });
    file.insert(file.end(), {0xFF, 0xFB, 0x90, 0x64});
    file.resize(file.size() + 2000, 0);
    const Bytes v1 = id3v1("V1 Title", "V1 Album", 9, 13);
    file.insert(file.end(), v1.begin(), v1.end());
    int fd = tempFile(file);
    auto t = tags::readTags(fd, true);
    CHECK(t.title == "Тест");
    CHECK(t.artist == "Кино");
    CHECK(t.album == "Café");
    CHECK(t.track == 7 && t.genre == "Rock" && t.lyrics == "words");
    CHECK(t.year == "1987");                               // only ID3v1 has it
    CHECK(t.picture.data == front && t.picture.type == 3);
    CHECK(t.replayGain.hasTrack);
    CHECK_NEAR(t.replayGain.trackGainDb, -1.5, 1e-5);
    close(fd);

    // v2.4: UTF-8, multiple artists, a data length indicator and frame unsynchronisation.
    Bytes unsynced = {3, 'X', 0xFF, 0x00, 'Y'};   // "X\xFFY" stored with a stuffed zero
    Bytes dli{0, 0, 0, 3};
    dli.insert(dli.end(), unsynced.begin(), unsynced.end());
    file = id3Tag(4, {
        id3Frame(4, "TPE1", Bytes{3, 'A', 0, 'B'}),
        id3Frame(4, "TDRC", text(3, "1999-01-01")),
        id3Frame(4, "TIT2", dli, 0x03),
        id3Frame(4, "TPE2", text(3, "Альбомный")),
    });
    file.resize(file.size() + 100, 0);
    fd = tempFile(file);
    t = tags::readTags(fd);
    CHECK(t.artist == "A; B" && t.year == "1999" && t.albumArtist == "Альбомный");
    CHECK(t.title == std::string("X\xC3\xBFY"));   // U+00FF decoded from the cleaned bytes
    close(fd);

    // v2.3 with whole-tag unsynchronisation: the picture contains FF 00 pairs.
    Bytes pic{0xFF, 0xD8, 0xFF, 0x00, 0xE0, 0xFF};
    Bytes payload = apic(3, pic);
    Bytes stuffed;
    for (size_t i = 0; i < payload.size(); ++i) {
        stuffed.push_back(payload[i]);
        if (payload[i] == 0xFF) stuffed.push_back(0x00);
    }
    Bytes frame = id3Frame(3, "APIC", stuffed);
    // The frame size counts the original bytes.
    const uint32_t original = static_cast<uint32_t>(payload.size());
    frame[4] = static_cast<uint8_t>(original >> 24); frame[5] = static_cast<uint8_t>(original >> 16);
    frame[6] = static_cast<uint8_t>(original >> 8); frame[7] = static_cast<uint8_t>(original);
    file = id3Tag(3, {frame}, 0x80);
    fd = tempFile(file);
    t = tags::readTags(fd, true);
    CHECK(t.picture.data == pic);
    close(fd);
}

void testApeAndMp4Tags() {
    // APEv2 at the end of a Monkey's Audio style file, with a binary front cover.
    const Bytes cover = image(7, 256);
    auto item = [](const std::string& key, const Bytes& value, uint32_t flags) {
        Bytes b; le(b, value.size(), 4); le(b, flags, 4);
        b.insert(b.end(), key.begin(), key.end()); b.push_back(0);
        b.insert(b.end(), value.begin(), value.end());
        return b;
    };
    auto str = [](const std::string& s) { return Bytes(s.begin(), s.end()); };
    Bytes coverValue = str("front.jpg");
    coverValue.push_back(0);
    coverValue.insert(coverValue.end(), cover.begin(), cover.end());
    Bytes items;
    for (const Bytes& i : {item("Title", str("Ape Title"), 0), item("Artist", str("X"), 0), item("Album", str("Ape Album"), 0),
                           item("Track", str("2/9"), 0), item("Year", str("2010"), 0),
                           item("REPLAYGAIN_ALBUM_GAIN", str("-6.00 dB"), 0), item("Cover Art (Front)", coverValue, 2)}) {
        items.insert(items.end(), i.begin(), i.end());
    }
    Bytes file{'M', 'A', 'C', ' '};
    file.resize(1000, 0x11);
    file.insert(file.end(), items.begin(), items.end());
    Bytes footer = str("APETAGEX");
    le(footer, 2000, 4); le(footer, items.size() + 32, 4); le(footer, 7, 4); le(footer, 0, 4); le(footer, 0, 8);
    file.insert(file.end(), footer.begin(), footer.end());
    int fd = tempFile(file);
    auto t = tags::readTags(fd, true);
    CHECK(t.title == "Ape Title" && t.artist == "X" && t.album == "Ape Album" && t.year == "2010");
    CHECK(t.track == 2 && t.trackTotal == 9);
    CHECK(t.replayGain.hasAlbum);
    CHECK_NEAR(t.replayGain.albumGainDb, -6.0, 1e-5);
    CHECK(t.picture.data == cover && t.picture.type == 3);
    close(fd);

    // MP4 / M4A: moov/udta/meta/ilst.
    auto atom = [](const char* type, const Bytes& body) {
        Bytes b; be(b, body.size() + 8, 4);
        for (int i = 0; i < 4; ++i) b.push_back(static_cast<uint8_t>(type[i]));
        b.insert(b.end(), body.begin(), body.end());
        return b;
    };
    auto data = [&](uint32_t type, const Bytes& payload) {
        Bytes body; be(body, type, 4); be(body, 0, 4);
        body.insert(body.end(), payload.begin(), payload.end());
        return atom("data", body);
    };
    auto cat = [](std::initializer_list<Bytes> parts) {
        Bytes b;
        for (const auto& p : parts) b.insert(b.end(), p.begin(), p.end());
        return b;
    };
    const Bytes art = image(2, 128);
    Bytes freeformName{0, 0, 0, 0};
    for (char c : std::string("replaygain_track_gain")) freeformName.push_back(static_cast<uint8_t>(c));
    Bytes freeformMean{0, 0, 0, 0};
    for (char c : std::string("com.apple.iTunes")) freeformMean.push_back(static_cast<uint8_t>(c));
    const Bytes ilst = atom("ilst", cat({
        atom("\xA9nam", data(1, str("M4A Title"))),
        atom("\xA9" "ART", data(1, str("M4A Artist"))),
        atom("aART", data(1, str("M4A Album Artist"))),
        atom("\xA9" "alb", data(1, str("M4A Album"))),
        atom("\xA9" "day", data(1, str("2015-03-01T00:00:00Z"))),
        atom("trkn", data(0, Bytes{0, 0, 0, 4, 0, 11, 0, 0})),
        atom("disk", data(0, Bytes{0, 0, 0, 2, 0, 2})),
        atom("gnre", data(0, Bytes{0, 18})),
        atom("covr", data(14, art)),
        atom("----", cat({atom("mean", freeformMean), atom("name", freeformName), data(1, str("-2.00 dB"))})),
    }));
    Bytes hdlr(25, 0);
    const Bytes meta = atom("meta", cat({Bytes{0, 0, 0, 0}, atom("hdlr", hdlr), ilst}));
    file = cat({atom("ftyp", str("M4A \0\0\0\0")), atom("mdat", Bytes(500, 0)), atom("moov", cat({atom("mvhd", Bytes(100, 0)), atom("udta", meta)}))});
    fd = tempFile(file);
    t = tags::readTags(fd, true);
    CHECK(t.title == "M4A Title" && t.artist == "M4A Artist" && t.albumArtist == "M4A Album Artist" && t.album == "M4A Album");
    CHECK(t.year == "2015" && t.track == 4 && t.trackTotal == 11 && t.disc == 2 && t.discTotal == 2);
    CHECK(t.genre == "Rock");
    CHECK(t.picture.data == art && t.picture.mime == "image/png");
    CHECK(t.replayGain.hasTrack);
    CHECK_NEAR(t.replayGain.trackGainDb, -2.0, 1e-5);
    close(fd);

    // WAV LIST/INFO.
    Bytes info = str("INFO");
    for (auto [id, value] : {std::pair<const char*, std::string>{"INAM", "Wav Title"}, {"IART", "Wav Artist"}, {"IPRD", "Wav Album"}}) {
        Bytes v = str(value); v.push_back(0);
        Bytes chunk = str(id); le(chunk, v.size(), 4); chunk.insert(chunk.end(), v.begin(), v.end());
        if (chunk.size() & 1) chunk.push_back(0);
        info.insert(info.end(), chunk.begin(), chunk.end());
    }
    const Pcm p = makePcm(44100, 1, 16, 100);
    Bytes wav = wavBytes(p);
    Bytes list = str("LIST"); le(list, info.size(), 4); list.insert(list.end(), info.begin(), info.end());
    wav.insert(wav.end(), list.begin(), list.end());
    fd = tempFile(wav);
    t = tags::readTags(fd);
    CHECK(t.title == "Wav Title" && t.artist == "Wav Artist" && t.album == "Wav Album");
    close(fd);

    // Nothing at all.
    fd = tempFile(Bytes(500, 0));
    t = tags::readTags(fd, true);
    CHECK(t.title.empty() && !t.hasPicture && !t.replayGain.hasTrack);
    close(fd);
    std::puts("  Tags: FLAC, ID3v2.3/2.4 (+unsync, 1251), ID3v1, APEv2, MP4, RIFF INFO, pictures");
}

void testTextCodec() {
    // Mostly-ASCII Western text with accents stays Latin-1; Cyrillic becomes 1251.
    const uint8_t western[] = {'H', 0xE9, 'l', 0xE8, 'n', 'e'};
    CHECK(tags::fromLatin1(western, sizeof(western)) == "Hélène");
    const uint8_t russian[] = {0xC3, 0xF0, 0xF3, 0xEF, 0xEF, 0xE0, ' ', 0xEA, 0xF0, 0xEE, 0xE2, 0xE8};
    CHECK(tags::fromLatin1(russian, sizeof(russian)) == "Группа крови");
    const std::string utf8 = "Ёж";
    CHECK(tags::fromUtf8OrLegacy(reinterpret_cast<const uint8_t*>(utf8.data()), utf8.size()) == utf8);
    CHECK(!tags::isValidUtf8(reinterpret_cast<const uint8_t*>("\xC3"), 1));
    CHECK(tags::id3GenreText("(17)") == "Rock" && tags::id3GenreText("(17)Indie") == "Indie" && tags::id3GenreText("8") == "Jazz");
}

int64_t probeMs(const Bytes& file) {
    const int fd = tempFile(file);
    const int64_t ms = decoders::DurationProbe(fd).durationMs();
    close(fd);
    return ms;
}

int64_t probeMs(const std::string& path) {
    const int fd = ::open(path.c_str(), O_RDONLY);
    CHECK(fd >= 0);
    const int64_t ms = decoders::DurationProbe(fd).durationMs();
    close(fd);
    return ms;
}

void testDurationProbe(const std::string& root) {
    const Pcm p = makePcm(44100, 2, 16, 50000);   // 1133 ms
    CHECK(probeMs(encodeWavPack(p)) == 1133);
    CHECK(probeMs(encodeTta(p)) == 1133);
    CHECK(probeMs(encodeApe(p)) == 1133);
    CHECK(probeMs(wavBytes(p)) == 1133);
    std::vector<float> opus(48000 * 2, 0.1f);
    CHECK(probeMs(encodeOggOpus(opus, 2, {})) == 1000);
    CHECK(probeMs(Bytes(4096, 0)) == 0);
    if (!root.empty()) {
        CHECK(probeMs(root + "/tone.flac") == 1200);
        CHECK(probeMs(root + "/tone.ogg") == 1200);
        const int64_t vbr = probeMs(root + "/tone-vbr.mp3");     // Xing frame count
        const int64_t cbr = probeMs(root + "/tone-cbr.mp3");     // byte count / bitrate
        std::printf("  Duration probe: MP3 VBR %lld ms, CBR %lld ms (1200 expected)\n", (long long)vbr, (long long)cbr);
        CHECK(vbr >= 1200 && vbr <= 1260);
        CHECK(cbr >= 1150 && cbr <= 1260);
    }
}

int main(int argc, char** argv) {
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    testLosslessFormats();
    testOpus();
    testChannelOrder();
    testMediaCodec();
    testRanges();
    testSniffing();
    testTextCodec();
    testFlacTags();
    testId3Tags();
    testApeAndMp4Tags();
    if (argc >= 2) testVorbis(std::string(argv[1]) + "/tone.ogg");
    testDurationProbe(argc >= 2 ? argv[1] : "");
    std::puts("Format tests passed.");
    return 0;
}
