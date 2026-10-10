// DSP blocks added in 0.13: parametric EQ, crossfeed, true peak, limiter, loudness.
#include <random>

#include "test_util.h"

#include "decoders/LoudnessScan.h"
#include "dsp/Crossfeed.h"
#include "dsp/LoudnessMeter.h"
#include "dsp/ParametricEq.h"
#include "dsp/TruePeak.h"
#include "dsp/TruePeakLimiter.h"
#include "hw/PcmEncoder.h"

using namespace audio_engine;
using namespace test;

std::vector<double> stereoSine(double freq, double ampL, double ampR, size_t frames, double rate, double phase = 0.0) {
    std::vector<double> x(frames * 2);
    for (size_t n = 0; n < frames; ++n) {
        const double s = std::sin(2.0 * M_PI * freq * n / rate + phase);
        x[n * 2] = ampL * s;
        x[n * 2 + 1] = ampR * s;
    }
    return x;
}

std::vector<double> channel(const std::vector<double>& x, size_t c) {
    std::vector<double> out(x.size() / 2);
    for (size_t i = 0; i < out.size(); ++i) out[i] = x[i * 2 + c];
    return out;
}

void testParametricEq() {
    dsp::ParametricEq eq;
    eq.prepare(48000);
    eq.setBands({{dsp::EqBandType::Peak, 1000, 1.0, 6.0}, {dsp::EqBandType::Peak, 1000, 1.0, 6.0}});
    CHECK_NEAR(eq.peakGainDb(), 12.0, 0.05);
    eq.setBands({{dsp::EqBandType::Peak, 1000, 1.0, -6.0}});
    CHECK_NEAR(eq.peakGainDb(), 0.0, 1e-9);   // cuts only: no headroom needed
    eq.setBands({{dsp::EqBandType::HighPass, 100, 0.707, 0}});
    auto x = stereoSine(20.0, 0.5, 0.5, 48000, 48000);
    eq.processInterleaved(x.data(), 48000, 2);
    CHECK(db(fitSine(channel(x, 0), 24000, 48000, 20.0, 48000) / 0.5) < -20.0);
}

void testCrossfeed() {
    dsp::Crossfeed cf;
    cf.prepare(44100);
    cf.setPreset(dsp::Crossfeed::Preset::Default);
    // Mono content keeps its level.
    auto mono = stereoSine(100.0, 0.5, 0.5, 44100, 44100);
    cf.process(mono.data(), 44100);
    CHECK_NEAR(db(fitSine(channel(mono, 0), 22050, 44100, 100.0, 44100) / 0.5), 0.0, 0.1);
    // A left-only bass tone reaches the right ear 4.5 dB down (the preset's feed level).
    cf.reset();
    auto left = stereoSine(40.0, 0.5, 0.0, 44100, 44100);
    cf.process(left.data(), 44100);
    const double l = fitSine(channel(left, 0), 22050, 44100, 40.0, 44100);
    const double r = fitSine(channel(left, 1), 22050, 44100, 40.0, 44100);
    CHECK_NEAR(db(r / l), -4.5, 0.4);
    // Treble stays on its side.
    cf.reset();
    auto treble = stereoSine(10000.0, 0.5, 0.0, 44100, 44100);
    cf.process(treble.data(), 44100);
    CHECK(db(fitSine(channel(treble, 1), 22050, 44100, 10000.0, 44100) / 0.5) < -20.0);
    // Off is bit-exact.
    cf.setPreset(dsp::Crossfeed::Preset::Off);
    auto same = stereoSine(300.0, 0.3, 0.1, 1000, 44100);
    auto copy = same;
    cf.process(same.data(), 1000);
    CHECK(same == copy);
    std::printf("  Crossfeed: mono unity, bass feed %.2f dB\n", db(r / l));
}

void testTruePeak() {
    // fs/4 sine at 45 degrees: every sample is at 0.707, the waveform peaks at 1.0.
    dsp::TruePeakDetector d;
    double sample = 0.0, truePeak = 0.0;
    for (int n = 0; n < 4000; ++n) {
        const double x = std::sin(M_PI / 2 * n + M_PI / 4);
        sample = std::max(sample, std::fabs(x));
        truePeak = std::max(truePeak, d.push(x));
    }
    CHECK_NEAR(db(sample), -3.01, 0.01);
    CHECK_NEAR(db(truePeak), 0.0, 0.3);
    std::printf("  True peak of an fs/4 sine with 0.707 samples: %.2f dBTP\n", db(truePeak));
}

std::vector<double> runLimiter(dsp::TruePeakLimiter& lim, const std::vector<double>& in, std::mt19937& rng) {
    std::vector<double> out;
    std::uniform_int_distribution<size_t> chunk(1, 3000);
    size_t pos = 0;
    const size_t frames = in.size() / 2;
    std::vector<double> buf;
    while (pos < frames) {
        const size_t n = std::min(chunk(rng), frames - pos);
        buf.assign(in.begin() + pos * 2, in.begin() + (pos + n) * 2);
        const size_t made = lim.process(buf.data(), n, buf.data());   // in place, like the player
        out.insert(out.end(), buf.begin(), buf.begin() + made * 2);
        pos += n;
    }
    buf.assign(lim.latency() * 2 + 2, 0.0);
    const size_t tail = lim.flush(buf.data());
    out.insert(out.end(), buf.begin(), buf.begin() + tail * 2);
    return out;
}

void testLimiter() {
    std::mt19937 rng(7);
    dsp::TruePeakLimiter lim;
    lim.prepare(48000, 2, -1.0);
    // Below the ceiling: bit-exact, nothing added or lost.
    const auto quiet = stereoSine(997.0, 0.8, 0.5, 96000, 48000, 0.3);
    const auto passed = runLimiter(lim, quiet, rng);
    CHECK(passed == quiet);
    // Far above it: every true peak at or below -1 dBTP, length preserved.
    std::vector<double> loud = stereoSine(3000.0, 2.0, 1.2, 96000, 48000, 0.7);
    for (size_t i = 40000; i < 40010; ++i) loud[i * 2] = 3.0;   // a transient
    const auto limited = runLimiter(lim, loud, rng);
    CHECK(limited.size() == loud.size());
    dsp::TruePeakDetector dl, dr;
    double tp = 0.0;
    for (size_t i = 0; i < limited.size() / 2; ++i) tp = std::max({tp, dl.push(limited[i * 2]), dr.push(limited[i * 2 + 1])});
    std::printf("  Limiter: +6 dB input -> %.3f dBTP out (ceiling -1)\n", db(tp));
    CHECK(db(tp) <= -1.0 + 0.05);
    // Mostly the steady level, not pumping: the sine ends near the ceiling.
    const double settled = fitSine(channel(limited, 0), 60000, 90000, 3000.0, 48000);
    CHECK(settled > 0.75 && settled < 0.9);
}

void testLoudness() {
    // EBU Tech 3341 case 1: 1 kHz sine at -23 dBFS in both channels = -23.0 LUFS.
    for (double rate : {48000.0, 44100.0, 96000.0}) {
        dsp::LoudnessMeter m;
        m.prepare(static_cast<uint32_t>(rate), 2);
        const double a = std::pow(10.0, -23.0 / 20.0);
        const auto x = stereoSine(1000.0, a, a, static_cast<size_t>(rate * 20), rate);
        m.add(x.data(), x.size() / 2);
        CHECK_NEAR(m.integratedLufs(), -23.0, 0.1);
        CHECK_NEAR(m.truePeakDb(), -23.0, 0.1);
    }
    // Case 3-ish: silence after the programme is gated out.
    dsp::LoudnessMeter m;
    m.prepare(48000, 2);
    const double a = std::pow(10.0, -23.0 / 20.0);
    auto x = stereoSine(1000.0, a, a, 48000 * 10, 48000);
    x.resize(x.size() * 2, 0.0);
    m.add(x.data(), x.size() / 2);
    CHECK_NEAR(m.integratedLufs(), -23.0, 0.1);
    // Relative gate: a quiet part 20 LU down does not drag the result.
    dsp::LoudnessMeter g;
    g.prepare(48000, 2);
    auto loud = stereoSine(1000.0, a, a, 48000 * 10, 48000);
    const double q = std::pow(10.0, -43.0 / 20.0);
    auto quiet = stereoSine(1000.0, q, q, 48000 * 10, 48000);
    g.add(loud.data(), loud.size() / 2);
    g.add(quiet.data(), quiet.size() / 2);
    CHECK_NEAR(g.integratedLufs(), -23.0, 0.2);
    // Silence: no loudness at all.
    dsp::LoudnessMeter s;
    s.prepare(48000, 2);
    std::vector<double> zero(48000 * 2 * 2, 0.0);
    s.add(zero.data(), 48000 * 2);
    CHECK(std::isinf(s.integratedLufs()));
    std::puts("  Loudness: EBU 3341 sine at 44.1/48/96 kHz, absolute and relative gates");
}

void testLoudnessScan() {
    // A WAV file through the decoder path: 1 kHz at -18 dBFS stereo for 5 s.
    const uint32_t rate = 48000, frames = rate * 5;
    Bytes b; tag(b, "RIFF"); le(b, 36 + frames * 4, 4); tag(b, "WAVE"); tag(b, "fmt "); le(b, 16, 4);
    le(b, 1, 2); le(b, 2, 2); le(b, rate, 4); le(b, rate * 4, 4); le(b, 4, 2); le(b, 16, 2);
    tag(b, "data"); le(b, frames * 4, 4);
    const double a = std::pow(10.0, -18.0 / 20.0);
    for (uint32_t n = 0; n < frames; ++n) {
        const int16_t v = static_cast<int16_t>(std::lround(a * 32767.0 * std::sin(2 * M_PI * 1000.0 * n / rate)));
        le(b, static_cast<uint16_t>(v), 2); le(b, static_cast<uint16_t>(v), 2);
    }
    const int fd = tempFile(b);
    const auto r = decoders::scanLoudness(fd);
    CHECK(r.ok);
    CHECK_NEAR(r.integratedLufs, -18.0, 0.1);
    CHECK_NEAR(r.truePeakDb, -18.0, 0.1);
    CHECK_NEAR(r.seconds, 5.0, 1e-6);
    const auto part = decoders::scanLoudness(fd, 1000000, 3000000);   // a CUE-style range
    CHECK(part.ok);
    CHECK_NEAR(part.seconds, 2.0, 1e-6);
    close(fd);
    const int bad = tempFile(Bytes(100, 1));
    CHECK(!decoders::scanLoudness(bad).ok);
    close(bad);
}

// Device conversion: exact samples pass untouched, rounded ones are dithered.
void testPcmEncoder() {
    using hw::SampleEncoding;
    hw::PcmEncoder enc;
    std::vector<double> src;
    for (int v : {0, 1, -1, 12345, -32768, 32767}) src.push_back(v / 32768.0);

    int16_t i16[6];
    CHECK(enc.encode(src.data(), 6, i16, SampleEncoding::I16) == 0);
    for (size_t i = 0; i < 6; ++i) CHECK(i16[i] == static_cast<int16_t>(src[i] * 32768.0));

    uint8_t i24[18];
    CHECK(enc.encode(src.data(), 6, i24, SampleEncoding::I24) == 0);
    for (size_t i = 0; i < 6; ++i) {
        const int32_t v = static_cast<int32_t>(static_cast<uint32_t>(i24[i * 3]) << 8 |
                                               static_cast<uint32_t>(i24[i * 3 + 1]) << 16 |
                                               static_cast<uint32_t>(i24[i * 3 + 2]) << 24) >> 8;
        CHECK(v == static_cast<int32_t>(src[i] * 32768.0) * 256);
    }

    int32_t i32[6];
    float f32[6];
    CHECK(enc.encode(src.data(), 6, i32, SampleEncoding::I32) == 0);
    CHECK(enc.encode(src.data(), 6, f32, SampleEncoding::Float) == 0);
    for (size_t i = 0; i < 6; ++i) {
        CHECK(i32[i] == static_cast<int32_t>(src[i] * 32768.0) * 65536);
        CHECK(static_cast<double>(f32[i]) == src[i]);
    }

    // Full scale and overs clamp to the largest code instead of wrapping.
    const double overs[2] = {1.0, -1.5};
    CHECK(enc.encode(overs, 2, i16, SampleEncoding::I16) == 0);
    CHECK(i16[0] == 32767 && i16[1] == -32768);

    // A quarter LSB: plain rounding would give 0 forever; TPDF dither keeps the
    // mean (linear quantiser) with errors never beyond ±1.5 LSB.
    std::vector<double> quarter(200000, 0.25 / 32768.0);
    std::vector<int16_t> q(quarter.size());
    CHECK(enc.encode(quarter.data(), quarter.size(), q.data(), SampleEncoding::I16) == quarter.size());
    double mean = 0.0;
    for (int16_t v : q) { CHECK(v >= -1 && v <= 2); mean += v; }
    CHECK_NEAR(mean / q.size(), 0.25, 0.01);
}

int main() {
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    testParametricEq();
    testCrossfeed();
    testTruePeak();
    testLimiter();
    testPcmEncoder();
    testLoudness();
    testLoudnessScan();
    std::puts("DSP tests passed: parametric EQ, crossfeed, true peak, limiter, loudness, PCM encoder.");
    return 0;
}
