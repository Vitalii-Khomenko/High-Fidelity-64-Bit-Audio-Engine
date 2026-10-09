// Decoder, tag and DSP regression tests. Run through tests/native/run.sh.
#include <atomic>
#include <chrono>
#include <cstring>
#include <fcntl.h>
#include <thread>

#include "test_util.h"

#include "core/RingBuffer.h"
#include "decoders/DsdDecoder.h"
#include "decoders/FlacDecoder.h"
#include "decoders/Mp3Decoder.h"
#include "decoders/ReplayGainScanner.h"
#include "decoders/WavDecoder.h"
#include "dsp/ChannelMixer.h"
#include "dsp/FirDesign.h"
#include "dsp/GraphicEqProcessor.h"
#include "dsp/TimeStretchProcessor.h"

using namespace audio_engine;
using namespace test;

// ── DSD fixtures from a second-order sigma-delta modulator ──────────────────

constexpr uint32_t kDsd64 = 2822400;

/** One bit per sample (true = +1) for each channel. */
std::vector<std::vector<bool>> modulate(size_t channels, size_t samples,
                                        const std::vector<std::pair<double, double>>& tones) {
    std::vector<std::vector<bool>> bits(channels, std::vector<bool>(samples));
    for (size_t ch = 0; ch < channels; ++ch) {
        double i1 = 0.0, i2 = 0.0, y = 0.0;
        for (size_t n = 0; n < samples; ++n) {
            double x = 0.0;
            for (const auto& [freq, amp] : tones) {
                x += amp * std::sin(2.0 * M_PI * freq * n / kDsd64 + ch * 0.5);
            }
            i1 += x - y;
            i2 += i1 - y;
            y = i2 >= 0.0 ? 1.0 : -1.0;
            bits[ch][n] = y > 0.0;
        }
    }
    return bits;
}

Bytes packBytes(const std::vector<bool>& bits, bool lsbFirst) {
    Bytes out(bits.size() / 8, 0);
    for (size_t i = 0; i < out.size() * 8; ++i) {
        if (!bits[i]) continue;
        const int bit = static_cast<int>(i % 8);
        out[i / 8] |= static_cast<uint8_t>(lsbFirst ? (1 << bit) : (0x80 >> bit));
    }
    return out;
}

Bytes makeDsf(const std::vector<std::vector<bool>>& bits, const Bytes& id3 = {}) {
    const uint32_t blockSize = 4096;
    const size_t channels = bits.size();
    const uint64_t sampleCount = bits[0].size();
    std::vector<Bytes> packed;
    for (const auto& b : bits) packed.push_back(packBytes(b, true));
    const uint64_t bytesPerChannel = packed[0].size();
    const uint64_t blocks = (bytesPerChannel + blockSize - 1) / blockSize;
    const uint64_t dataSize = 12 + blocks * blockSize * channels;
    const uint64_t metadataOffset = id3.empty() ? 0 : 28 + 52 + dataSize;
    Bytes b;
    tag(b, "DSD "); le(b, 28, 8); le(b, 28 + 52 + dataSize + id3.size(), 8); le(b, metadataOffset, 8);
    tag(b, "fmt "); le(b, 52, 8); le(b, 1, 4); le(b, 0, 4); le(b, channels == 2 ? 2 : 1, 4);
    le(b, channels, 4); le(b, kDsd64, 4); le(b, 1, 4); le(b, sampleCount, 8); le(b, blockSize, 4); le(b, 0, 4);
    tag(b, "data"); le(b, dataSize, 8);
    for (uint64_t blk = 0; blk < blocks; ++blk) {
        for (size_t ch = 0; ch < channels; ++ch) {
            for (uint32_t i = 0; i < blockSize; ++i) {
                const uint64_t idx = blk * blockSize + i;
                b.push_back(idx < bytesPerChannel ? packed[ch][idx] : 0);
            }
        }
    }
    b.insert(b.end(), id3.begin(), id3.end());
    return b;
}

void iffChunk(Bytes& b, const char* name, const Bytes& data) {
    tag(b, name); be(b, data.size(), 8); b.insert(b.end(), data.begin(), data.end());
    if (data.size() % 2) b.push_back(0);
}

Bytes makeDff(const std::vector<std::vector<bool>>& bits) {
    const size_t channels = bits.size();
    std::vector<Bytes> packed;
    for (const auto& b : bits) packed.push_back(packBytes(b, false));
    Bytes prop; tag(prop, "SND ");
    Bytes fs; be(fs, kDsd64, 4); iffChunk(prop, "FS  ", fs);
    Bytes chnl; be(chnl, channels, 2); for (size_t c = 0; c < channels; ++c) tag(chnl, c ? "SRGT" : "SLFT");
    iffChunk(prop, "CHNL", chnl);
    Bytes cmpr; tag(cmpr, "DSD "); iffChunk(prop, "CMPR", cmpr);
    Bytes data;
    for (size_t i = 0; i < packed[0].size(); ++i) for (size_t c = 0; c < channels; ++c) data.push_back(packed[c][i]);
    Bytes body; tag(body, "DSD "); iffChunk(body, "PROP", prop); iffChunk(body, "DSD ", data);
    Bytes b; tag(b, "FRM8"); be(b, body.size(), 8); b.insert(b.end(), body.begin(), body.end());
    return b;
}

std::vector<std::vector<double>> decodeAll(decoders::IAudioDecoder& d) {
    const size_t ch = d.getNumChannels();
    std::vector<std::vector<double>> out(ch);
    core::AudioBuffer buf(ch, 1000, d.getSampleRate());
    size_t n;
    while ((n = d.readFrames(buf, 777)) > 0) {
        for (size_t c = 0; c < ch; ++c) out[c].insert(out[c].end(), buf.getReadPointer(c), buf.getReadPointer(c) + n);
    }
    return out;
}

/** Low-passes x at 20 kHz (for in-band noise measurement). */
std::vector<double> lowpass20k(const std::vector<double>& x, double rate) {
    const auto h = dsp::designKaiserLowpass(511, 20000.0 / rate, 100.0);
    std::vector<double> y(x.size(), 0.0);
    for (size_t n = h.size(); n < x.size(); ++n) {
        double acc = 0.0;
        for (size_t k = 0; k < h.size(); ++k) acc += h[k] * x[n - k];
        y[n] = acc;
    }
    return y;
}

void testDsdQuality(bool dff) {
    const size_t samples = kDsd64 / 2;  // 0.5 s
    const auto bits = modulate(2, samples, {{1000.0, 0.5}});
    const Bytes file = dff ? makeDff(bits) : makeDsf(bits);
    const int fd = tempFile(file);
    decoders::DsdDecoder d;
    CHECK(d.openFd(fd));
    close(fd);  // the decoder owns a duplicate
    CHECK(d.getSampleRate() == 88200);
    CHECK(d.getDsdRate() == kDsd64);
    CHECK(d.getBitsPerSample() == 1);
    CHECK(d.getCodec() == (dff ? decoders::Codec::Dff : decoders::Codec::Dsf));
    CHECK(d.getTotalFrames() == samples / 32);
    const auto pcm = decodeAll(d);
    CHECK(pcm[0].size() == d.getTotalFrames());

    for (size_t ch = 0; ch < 2; ++ch) {
        std::vector<double> residual;
        const size_t begin = 2000, end = pcm[ch].size() - 2000;
        const double amp = fitSine(pcm[ch], begin, end, 1000.0, 88200.0, &residual);
        CHECK_NEAR(amp, 0.5, 0.5 * 0.002);  // unity gain within 0.02 dB
        const auto inBand = lowpass20k(residual, 88200.0);
        const double snr = db((amp / std::sqrt(2.0)) / rms(inBand, begin + 600, end));
        if (ch == 0) std::printf("  DSD64 %s 1 kHz: gain error %.4f dB, in-band SNR %.1f dB\n",
                                 dff ? "DFF" : "DSF", db(amp / 0.5), snr);
        // A second-order test modulator itself reaches ~71.7 dB in band, so this
        // checks that the decimator adds no measurable noise of its own.
        CHECK(snr > 70.0);
    }

    // Seeking must reproduce the continuous decode exactly (pre-roll fills the filters).
    for (uint64_t target : {uint64_t(0), uint64_t(1), uint64_t(5000), d.getTotalFrames() - 100}) {
        CHECK(d.seekToFrame(target));
        CHECK(d.getCurrentFrame() == target);
        core::AudioBuffer buf(2, 64, 88200);
        const size_t n = d.readFrames(buf, 64);
        CHECK(n == std::min<uint64_t>(64, d.getTotalFrames() - target));
        for (size_t i = 0; i < n; ++i) CHECK(buf.getReadPointer(1)[i] == pcm[1][target + i]);
    }
    CHECK(d.seekToFrame(d.getTotalFrames()));
    core::AudioBuffer buf(2, 64, 88200);
    CHECK(d.readFrames(buf, 64) == 0);
}

void testDsdAntiAlias() {
    // 70 kHz would fold to 18.2 kHz at 88.2 kHz without a proper decimator.
    const size_t samples = kDsd64 / 2;
    const auto bits = modulate(1, samples, {{70000.0, 0.25}});
    const int fd = tempFile(makeDsf(bits));
    decoders::DsdDecoder d;
    CHECK(d.openFd(fd));
    close(fd);
    const auto pcm = decodeAll(d);
    const double alias = fitSine(pcm[0], 2000, pcm[0].size() - 2000, 88200.0 - 70000.0, 88200.0);
    std::printf("  DSD64 70 kHz tone -> 18.2 kHz alias: %.1f dB below input\n", db(0.25 / std::max(alias, 1e-12)));
    CHECK(alias < 0.25 * 1e-4);
}

void testDsdRejectsGarbage() {
    for (size_t n = 0; n < 200; ++n) {
        Bytes b(n, 0x5a);
        if (n >= 4) std::memcpy(b.data(), n % 2 ? "DSD " : "FRM8", 4);
        const int fd = tempFile(b);
        { decoders::DsdDecoder d; CHECK(!d.openFd(fd)); }
        CHECK(fcntl(fd, F_GETFD) != -1);  // caller keeps its descriptor
        close(fd);
    }
}

// ── ReplayGain tags ─────────────────────────────────────────────────────────

Bytes id3Txxx(int version, uint8_t encoding, const std::string& key, const std::string& value) {
    Bytes payload{encoding};
    auto text = [&](const std::string& s, bool terminate) {
        if (encoding == 1) {
            payload.push_back(0xFF); payload.push_back(0xFE);
            for (char c : s) { payload.push_back(static_cast<uint8_t>(c)); payload.push_back(0); }
            if (terminate) { payload.push_back(0); payload.push_back(0); }
        } else {
            payload.insert(payload.end(), s.begin(), s.end());
            if (terminate) payload.push_back(0);
        }
    };
    text(key, true);
    text(value, false);
    Bytes frame; tag(frame, "TXXX");
    const uint32_t size = static_cast<uint32_t>(payload.size());
    if (version == 4) { for (int s = 21; s >= 0; s -= 7) frame.push_back(static_cast<uint8_t>((size >> s) & 0x7f)); }
    else be(frame, size, 4);
    frame.push_back(0); frame.push_back(0);
    frame.insert(frame.end(), payload.begin(), payload.end());
    return frame;
}

Bytes id3Tag(int version, const std::vector<Bytes>& frames) {
    Bytes body;
    for (const auto& f : frames) body.insert(body.end(), f.begin(), f.end());
    body.resize(body.size() + 32, 0);  // padding
    Bytes h{'I', 'D', '3', static_cast<uint8_t>(version), 0, 0};
    const uint32_t size = static_cast<uint32_t>(body.size());
    for (int s = 21; s >= 0; s -= 7) h.push_back(static_cast<uint8_t>((size >> s) & 0x7f));
    h.insert(h.end(), body.begin(), body.end());
    return h;
}

void testReplayGain() {
    using decoders::ReplayGainMode;
    for (int version : {3, 4}) {
        for (uint8_t encoding : {uint8_t(0), uint8_t(1), uint8_t(3)}) {
            Bytes file = id3Tag(version, {
                id3Txxx(version, encoding, "REPLAYGAIN_TRACK_GAIN", "-6.48 dB"),
                id3Txxx(version, encoding, "replaygain_track_peak", "0.988525"),
                id3Txxx(version, encoding, "REPLAYGAIN_ALBUM_GAIN", "+2.10 dB"),
            });
            file.resize(file.size() + 4096, 0xFF);
            const int fd = tempFile(file);
            const auto info = decoders::readReplayGain(fd);
            CHECK(info.hasTrack && info.hasAlbum);
            CHECK_NEAR(info.trackGainDb, -6.48, 1e-4);
            CHECK_NEAR(info.trackPeak, 0.988525, 1e-6);
            CHECK_NEAR(info.albumGainDb, 2.10, 1e-4);
            CHECK(lseek(fd, 0, SEEK_CUR) == 0);  // reading tags never moves the cursor
            CHECK_NEAR(decoders::replayGainLinear(info, ReplayGainMode::Track), std::pow(10.0, -6.48 / 20.0), 1e-6);
            // The album peak is unknown, so album gain applies as tagged.
            CHECK_NEAR(decoders::replayGainLinear(info, ReplayGainMode::Album), std::pow(10.0, 2.10 / 20.0), 1e-6);
            CHECK(decoders::replayGainLinear(info, ReplayGainMode::Off) == 1.0);
            close(fd);
        }
    }
    // Peak protection: +6 dB with a 0.9 peak is limited to 1/0.9.
    decoders::ReplayGainInfo loud;
    loud.hasTrack = true; loud.trackGainDb = 6.0f; loud.trackPeak = 0.9f;
    CHECK_NEAR(decoders::replayGainLinear(loud, ReplayGainMode::Track), 1.0 / 0.9, 1e-6);

    // DSF keeps its ID3v2 tag at the metadata pointer.
    const auto bits = modulate(2, 8192 * 8, {});
    const int fd = tempFile(makeDsf(bits, id3Tag(3, {id3Txxx(3, 0, "REPLAYGAIN_TRACK_GAIN", "-1.25 dB")})));
    const auto dsfInfo = decoders::readReplayGain(fd);
    CHECK(dsfInfo.hasTrack);
    CHECK_NEAR(dsfInfo.trackGainDb, -1.25, 1e-4);
    close(fd);

    // No tags at all.
    const int empty = tempFile(Bytes(1000, 0));
    const auto none = decoders::readReplayGain(empty);
    CHECK(!none.hasTrack && !none.hasAlbum);
    close(empty);
}

// ── PCM / encoded formats ───────────────────────────────────────────────────

void testWavPrecision() {
    Bytes b; tag(b, "RIFF"); le(b, 40, 4); tag(b, "WAVE"); tag(b, "fmt "); le(b, 16, 4);
    le(b, 1, 2); le(b, 1, 2); le(b, 48000, 4); le(b, 192000, 4); le(b, 4, 2); le(b, 32, 2);
    tag(b, "data"); le(b, 8, 4); le(b, 2147483647u, 4); le(b, 0x80000000u, 4);
    const int fd = tempFile(b);
    decoders::WavDecoder d;
    CHECK(d.openFd(fd));
    close(fd);
    core::AudioBuffer out(1, 4, 48000);
    CHECK(d.readFrames(out, 100) == 2);
    CHECK(out.getReadPointer(0)[0] == 2147483647.0 / 2147483648.0);
    CHECK(out.getReadPointer(0)[1] == -1.0);
}

void testWavFloat64() {
    const double values[3] = {0.123456789012345678, -0.999999999999, 1e-300};
    Bytes b; tag(b, "RIFF"); le(b, 36 + 24, 4); tag(b, "WAVE"); tag(b, "fmt "); le(b, 16, 4);
    le(b, 3, 2); le(b, 1, 2); le(b, 96000, 4); le(b, 96000 * 8, 4); le(b, 8, 2); le(b, 64, 2);
    tag(b, "data"); le(b, 24, 4);
    for (double v : values) { uint64_t u; std::memcpy(&u, &v, 8); le(b, u, 8); }
    const int fd = tempFile(b);
    decoders::WavDecoder d;
    CHECK(d.openFd(fd));
    close(fd);
    core::AudioBuffer out(1, 3, 96000);
    CHECK(d.readFrames(out, 3) == 3);
    for (int i = 0; i < 3; ++i) CHECK(out.getReadPointer(0)[i] == values[i]);  // bit exact
}

void testEncodedFile(decoders::IAudioDecoder& d, const std::string& path, bool exactSeek) {
    CHECK(d.open(path));
    CHECK(d.getSampleRate() == 44100 && d.getNumChannels() == 2);
    const uint64_t total = d.getTotalFrames();
    CHECK(total > 44100 && total < 60000);
    const auto pcm = decodeAll(d);
    CHECK(pcm[0].size() >= total - 1152 && pcm[0].size() <= total + 1152);
    double energy = 0.0;
    for (double v : pcm[0]) { CHECK(std::isfinite(v)); energy += v * v; }
    CHECK(energy > 1.0);
    for (uint64_t target : {uint64_t(0), uint64_t(12345), uint64_t(40000)}) {
        CHECK(d.seekToFrame(target));
        core::AudioBuffer buf(2, 256, 44100);
        const size_t n = d.readFrames(buf, 256);
        CHECK(n == 256);
        double maxErr = 0.0;
        for (size_t i = 0; i < n; ++i) maxErr = std::max(maxErr, std::fabs(buf.getReadPointer(0)[i] - pcm[0][target + i]));
        if (exactSeek) CHECK(maxErr == 0.0);
        else CHECK(maxErr < 1e-3);  // MP3 frames overlap; dr_mp3 re-primes the bit reservoir
    }
    // Reopening garbage must fail cleanly and leave the decoder unusable, not stale.
    const int bad = tempFile(Bytes(16, 0));
    CHECK(!d.openFd(bad));
    close(bad);
    core::AudioBuffer buf(2, 16, 44100);
    CHECK(d.readFrames(buf, 16) == 0);
}

void testMp3SeekSpeed(const std::string& path) {
    decoders::Mp3Decoder d;
    CHECK(d.open(path));
    CHECK(d.getSeekPointCount() > 100);
    const uint64_t total = d.getTotalFrames();
    const auto start = std::chrono::steady_clock::now();
    for (int i = 0; i < 20; ++i) CHECK(d.seekToFrame(total - d.getSampleRate() - i * 1000));
    const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
    std::printf("  MP3 %.0f min: 20 seeks near the end took %.1f ms\n", total / double(d.getSampleRate()) / 60.0, ms);
    CHECK(ms < 1000.0);
}

// ── DSP ─────────────────────────────────────────────────────────────────────

void testRing() {
    core::RingBuffer<uint64_t> q(64);
    std::atomic<bool> done{false};
    std::thread writer([&] { for (uint64_t n = 1; n <= 200000;) if (q.write(&n, 1)) ++n; done = true; });
    uint64_t expected = 1, n = 0;
    while (!done || q.getAvailableRead()) { if (q.read(&n, 1)) CHECK(n == expected++); }
    writer.join();
    CHECK(expected == 200001);
    std::thread producer([&] { for (int i = 0; i < 100000; ++i) { uint64_t x = i; q.write(&x, 1); } });
    std::thread consumer([&] { uint64_t x; for (int i = 0; i < 100000; ++i) q.read(&x, 1); });
    for (int i = 0; i < 10000; ++i) q.clear();
    producer.join();
    consumer.join();
    q.clear();
    CHECK(q.getAvailableRead() == 0);
}

void testStretch() {
    constexpr size_t frames = 44100;
    std::vector<double> in(frames * 2);
    for (size_t f = 0; f < frames; ++f) in[f * 2] = in[f * 2 + 1] = 0.5 * std::sin(f * 2 * M_PI * 440 / 44100);
    for (double speed : {0.5, 0.75, 1.25, 2.0}) {
        dsp::TimeStretchProcessor p;
        p.prepare(44100, 2);
        p.setSpeed(speed);
        p.reset();
        p.appendInterleaved(in.data(), frames);
        p.markEndOfInput();
        std::vector<double> out(4096);
        size_t total = 0, got;
        while ((got = p.renderInterleaved(out.data(), 1024))) {
            total += got;
            for (size_t i = 0; i < got * 2; ++i) CHECK(std::isfinite(out[i]));
        }
        CHECK(p.isDrained());
        CHECK(std::fabs(double(total) - frames / speed) < 4410);
    }
}

void testStretchResolution() {
    // Float Sonic: a -66 dBFS tone survives time-stretching without 16-bit
    // quantisation noise, and a +3.5 dB over-full-scale tone is not clipped.
    for (double amp : {0.0005, 1.5}) {
        constexpr size_t frames = 44100;
        std::vector<double> in(frames);
        for (size_t f = 0; f < frames; ++f) in[f] = amp * std::sin(2 * M_PI * 441.0 * f / 44100.0);
        dsp::TimeStretchProcessor p;
        p.prepare(44100, 1);
        p.setSpeed(1.25);
        p.reset();
        p.appendInterleaved(in.data(), frames);
        p.markEndOfInput();
        std::vector<double> out, chunk(1024);
        size_t got;
        while ((got = p.renderInterleaved(chunk.data(), 1024))) out.insert(out.end(), chunk.begin(), chunk.begin() + got);
        double peak = 0;
        for (double v : out) peak = std::max(peak, std::fabs(v));
        std::vector<double> residual;
        const double fitted = fitSine(out, 2000, out.size() - 2000, 441.0, 44100.0, &residual);
        const double snr = db(fitted / std::sqrt(2.0) / rms(residual, 2000, out.size() - 2000));
        std::printf("  Sonic 1.25x, %.1f dBFS tone: SNR %.1f dB, peak %.4f\n", db(amp), snr, peak);
        CHECK(snr > 60.0);
        CHECK(peak > amp * 0.97);
    }
}

void testEqResponse() {
    // A +6 dB peak at 910 Hz must raise a 910 Hz sine by ~6 dB and leave 10 kHz alone.
    for (double freq : {910.0, 10000.0}) {
        dsp::GraphicEqProcessor eq;
        eq.prepare(48000, 1024);
        eq.setEnabled(true);
        eq.setBandGain(2, 6.0);
        std::vector<double> x(48000);
        for (size_t n = 0; n < x.size(); ++n) x[n] = 0.1 * std::sin(2 * M_PI * freq * n / 48000.0);
        eq.processRawInterleaved(x.data(), x.size(), 1);
        const double amp = fitSine(x, 8000, 48000, freq, 48000.0);
        const double gain = db(amp / 0.1);
        if (freq < 1000) CHECK_NEAR(gain, 6.0, 0.2); else CHECK_NEAR(gain, 0.0, 0.3);
    }
}

void testDownmix() {
    dsp::StereoDownmix mix;
    mix.configure(6);  // L R C LFE BL BR
    std::vector<double> data = {1, 0, 0, 1, 0, 0,   0, 0, 1, 0, 0, 0};
    mix.process(data.data(), 2);
    const double norm = 1.0 / (1.0 + std::sqrt(0.5));
    CHECK_NEAR(data[0], norm, 1e-12);                    // L only
    CHECK_NEAR(data[1], 0.0, 1e-12);                     // LFE dropped
    CHECK_NEAR(data[2], std::sqrt(0.5) * norm, 1e-12);   // centre to both
    CHECK_NEAR(data[3], std::sqrt(0.5) * norm, 1e-12);
}

int main(int argc, char** argv) {
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    testDsdQuality(false);
    testDsdQuality(true);
    testDsdAntiAlias();
    testDsdRejectsGarbage();
    testReplayGain();
    testWavPrecision();
    testWavFloat64();
    testRing();
    testStretch();
    testStretchResolution();
    testEqResponse();
    testDownmix();
    std::puts("Decoder/DSP tests passed: DSD quality and seeking, ReplayGain tags, WAV precision, ring, stretch, EQ, downmix.");

    if (argc >= 2) {
        const std::string root = argv[1];
        decoders::WavDecoder wav;
        decoders::FlacDecoder flac;
        decoders::Mp3Decoder mp3;
        testEncodedFile(wav, root + "/tone.wav", true);
        testEncodedFile(wav, root + "/tone.aiff", true);
        CHECK(wav.open(root + "/tone.aiff") && wav.getCodec() == decoders::Codec::Aiff);
        testEncodedFile(flac, root + "/tone.flac", true);
        testEncodedFile(mp3, root + "/tone-cbr.mp3", false);
        testEncodedFile(mp3, root + "/tone-vbr.mp3", false);
        testEncodedFile(mp3, root + "/tone-no-xing.mp3", false);
        const int tagged = ::open((root + "/tagged.flac").c_str(), O_RDONLY);
        CHECK(tagged >= 0);
        const auto rg = decoders::readReplayGain(tagged);
        CHECK(rg.hasTrack);
        CHECK_NEAR(rg.trackGainDb, -7.25, 1e-4);
        close(tagged);
        testMp3SeekSpeed(root + "/long.mp3");
        std::puts("Encoded fixtures passed: WAV/AIFF/FLAC/MP3 (CBR, VBR, no Xing) decode, seek, reopen; FLAC tags; MP3 seek table.");
    }
    return 0;
}
