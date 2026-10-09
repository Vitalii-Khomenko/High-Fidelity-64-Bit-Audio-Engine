// AudioPlayer + OboeOutput scenarios against the simulated Oboe in stubs/.
#include <atomic>
#include <chrono>
#include <thread>

#include "test_util.h"

#include "core/AudioPlayer.h"
#include "decoders/WavDecoder.h"

using namespace audio_engine;
using namespace std::chrono_literals;
using core::PlayerState;

namespace {

/** Sine (or constant) source with an exact length. */
class ToneDecoder : public decoders::IAudioDecoder {
public:
    ToneDecoder(uint64_t frames, uint32_t rate = 48000, size_t channels = 2, double amp = 0.25, double freq = 0.0)
        : m_total(frames), m_rate(rate), m_channels(channels), m_amp(amp), m_freq(freq) {}
    bool openFd(int) override { return true; }
    size_t readFrames(core::AudioBuffer& b, size_t n) override {
        n = static_cast<size_t>(std::min<uint64_t>({n, b.getNumFrames(), m_total - m_pos}));
        for (size_t c = 0; c < m_channels; ++c) {
            double* out = b.getWritePointer(c);
            for (size_t f = 0; f < n; ++f) {
                out[f] = m_freq > 0 ? m_amp * std::sin(2 * M_PI * m_freq * double(m_pos + f) / m_rate) : m_amp;
            }
        }
        m_pos += n;
        return n;
    }
    bool seekToFrame(uint64_t f) override { m_pos = std::min(f, m_total); ++seeks; return true; }
    uint32_t getSampleRate() const override { return m_rate; }
    size_t getNumChannels() const override { return m_channels; }
    uint32_t getBitsPerSample() const override { return 24; }
    uint64_t getTotalFrames() const override { return m_total; }
    uint64_t getCurrentFrame() const override { return m_pos; }
    decoders::Codec getCodec() const override { return decoders::Codec::Wav; }
    int seeks = 0;
private:
    uint64_t m_total, m_pos = 0;
    uint32_t m_rate;
    size_t m_channels;
    double m_amp, m_freq;
};

template <typename Pred>
bool waitFor(Pred pred, std::chrono::milliseconds timeout = 4000ms) {
    const auto deadline = std::chrono::steady_clock::now() + timeout;
    while (!pred()) {
        if (std::chrono::steady_clock::now() > deadline) return false;
        std::this_thread::sleep_for(1ms);
    }
    return true;
}

std::vector<float> takeCapture() {
    std::lock_guard<std::mutex> lock(oboe::captureMutex);
    std::vector<float> out;
    out.swap(oboe::captured);
    return out;
}

void startCapture() {
    std::lock_guard<std::mutex> lock(oboe::captureMutex);
    oboe::captured.clear();
    oboe::capture = true;
}

void testPlaysToEnd() {
    core::AudioPlayer p;
    CHECK(p.state() == PlayerState::Idle);
    CHECK(p.load(std::make_unique<ToneDecoder>(9600), 1.0));
    CHECK(p.state() == PlayerState::Paused);
    CHECK_NEAR(p.durationMs(), 200.0, 1e-9);
    CHECK(p.positionMs() == 0.0);
    const auto start = std::chrono::steady_clock::now();
    CHECK(p.play());
    CHECK(waitFor([&] { return p.state() == PlayerState::Ended; }));
    // Completion waits for the device to play the buffered audio.
    CHECK(std::chrono::steady_clock::now() - start > 150ms);
    CHECK_NEAR(p.positionMs(), 200.0, 0.5);
    // Play after the end restarts from the top.
    CHECK(p.play());
    CHECK(p.positionMs() < 100.0);
    CHECK(waitFor([&] { return p.state() == PlayerState::Ended; }));
}

void testPauseResumeKeepsEverySample() {
    core::AudioPlayer p;
    auto decoder = std::make_unique<ToneDecoder>(48000 * 3 / 4);
    ToneDecoder* raw = decoder.get();
    CHECK(p.load(std::move(decoder), 1.0));
    const int seeksAfterLoad = raw->seeks;
    CHECK(p.play());
    double last = 0.0;
    for (int i = 0; i < 4; ++i) {
        CHECK(waitFor([&] { return p.positionMs() > last + 60.0; }));
        p.pause();
        CHECK(p.state() == PlayerState::Paused);
        const double paused = p.positionMs();
        std::this_thread::sleep_for(60ms);
        CHECK(p.positionMs() == paused);  // nothing is consumed while paused
        CHECK(paused >= last);
        last = paused;
        CHECK(p.play());
    }
    CHECK(waitFor([&] { return p.state() == PlayerState::Ended; }));
    CHECK_NEAR(p.positionMs(), 750.0, 0.5);
    CHECK(raw->seeks == seeksAfterLoad);  // resume never re-seeks the decoder
}

void testVolumeIsImmediate() {
    core::AudioPlayer p;
    CHECK(p.load(std::make_unique<ToneDecoder>(48000 * 2), 1.0));
    p.setVolume(1.0);
    CHECK(p.play());
    CHECK(waitFor([&] { return p.positionMs() > 100.0; }));
    startCapture();
    p.setVolume(0.0);
    std::this_thread::sleep_for(150ms);
    auto out = takeCapture();
    oboe::capture = false;
    // After ~100 ms of smoothing the output must be silent even though
    // ~300 ms of full-level audio is still queued in the ring.
    CHECK(out.size() > 2000);
    double tail = 0.0;
    for (size_t i = out.size() - 1000; i < out.size(); ++i) tail = std::max(tail, double(std::fabs(out[i])));
    CHECK(tail < 1e-3);
    p.pause();
}

void testSeek() {
    core::AudioPlayer p;
    CHECK(p.load(std::make_unique<ToneDecoder>(48000 * 2), 1.0));
    p.seekToMs(1500.0);  // while paused: applied synchronously
    CHECK_NEAR(p.positionMs(), 1500.0, 0.1);
    CHECK(p.play());
    CHECK(waitFor([&] { return p.positionMs() > 1550.0; }));
    p.seekToMs(200.0);   // while playing
    CHECK(p.positionMs() < 260.0);
    CHECK(waitFor([&] { return p.positionMs() > 260.0; }));
    p.seekToMs(-50.0);
    CHECK(waitFor([&] { return p.positionMs() < 100.0; }));
    p.seekToMs(1e12);    // clamps to the last frame
    CHECK(waitFor([&] { return p.state() == PlayerState::Ended; }));
    p.seekToMs(NAN);
    p.setSpeed(NAN);
    p.seekToMs(500.0);   // seeking after the end makes the track playable again
    CHECK(p.state() == PlayerState::Paused);
    CHECK_NEAR(p.positionMs(), 500.0, 0.1);
}

void testGaplessSameFormat() {
    core::AudioPlayer p;
    CHECK(p.load(std::make_unique<ToneDecoder>(9600), 1.0));
    p.setNext(std::make_unique<ToneDecoder>(14400), 0.5);
    startCapture();
    CHECK(p.play());
    // The event fires only once the boundary is audible, then the position restarts.
    CHECK(waitFor([&] { return p.consumeTrackAdvanced(); }));
    CHECK(!p.consumeTrackAdvanced());
    CHECK_NEAR(p.durationMs(), 300.0, 1e-9);
    CHECK(p.positionMs() < 120.0);
    CHECK_NEAR(p.trackInfo().gainDb, 20.0 * std::log10(0.5), 1e-9);
    CHECK(waitFor([&] { return p.state() == PlayerState::Ended; }));
    oboe::capture = false;
    const auto out = takeCapture();
    // No gap: every frame of both tracks reached the device, levels as gained.
    size_t full = 0, half = 0;
    for (size_t i = 0; i < out.size(); i += 2) {
        if (std::fabs(out[i] - 0.25f) < 1e-4f) ++full;
        if (std::fabs(out[i] - 0.125f) < 1e-4f) ++half;
    }
    CHECK(full > 9600 - 1200 && full <= 9600);  // minus the fade-in
    CHECK(half == 14400 || half > 14400 - 64);
}

size_t countLevel(const std::vector<float>& out, float level) {
    size_t n = 0;
    for (size_t i = 0; i < out.size(); i += 2) if (std::fabs(out[i] - level) < 1e-4f) ++n;
    return n;
}

// The decoder runs ~300 ms ahead: near the end of A it is already on B while
// A is still being heard. Seeks and clearNext() must apply to A.
void testSeekDuringGaplessLookAhead() {
    core::AudioPlayer p;
    CHECK(p.load(std::make_unique<ToneDecoder>(30000, 48000, 2, 0.25), 1.0));  // 625 ms
    p.setNext(std::make_unique<ToneDecoder>(14400, 48000, 2, 0.125), 1.0);
    CHECK(p.play());
    CHECK(waitFor([&] { return p.positionMs() > 420.0; }));  // B decoded, not yet audible
    CHECK(!p.consumeTrackAdvanced());
    p.seekToMs(100.0);
    CHECK(waitFor([&] { return p.positionMs() > 120.0 && p.positionMs() < 300.0; }));
    CHECK_NEAR(p.durationMs(), 625.0, 1e-9);  // still A
    startCapture();
    CHECK(waitFor([&] { return p.consumeTrackAdvanced(); }));  // B follows A after all
    CHECK_NEAR(p.durationMs(), 300.0, 1e-9);
    CHECK(waitFor([&] { return p.state() == PlayerState::Ended; }));
    oboe::capture = false;
    const auto captured = takeCapture();
    const size_t heardB = countLevel(captured, 0.125f);
    CHECK(heardB > 14400 - 64 && heardB <= 14400);  // B played once, completely
}

void testClearNextDuringGaplessLookAhead() {
    core::AudioPlayer p;
    CHECK(p.load(std::make_unique<ToneDecoder>(30000, 48000, 2, 0.25), 1.0));
    p.setNext(std::make_unique<ToneDecoder>(14400, 48000, 2, 0.125), 1.0);
    startCapture();
    CHECK(p.play());
    CHECK(waitFor([&] { return p.positionMs() > 420.0; }));
    p.clearNext();
    CHECK(waitFor([&] { return p.state() == PlayerState::Ended; }));
    oboe::capture = false;
    const auto out = takeCapture();
    CHECK(!p.consumeTrackAdvanced());
    // B was never heard (a few ramp samples pass through 0.125 on the way down/up).
    CHECK(countLevel(out, 0.125f) < 16);
    CHECK_NEAR(p.durationMs(), 625.0, 1e-9);
    CHECK_NEAR(p.positionMs(), 625.0, 0.5);
}

void testFailedRestartIsReported() {
    core::AudioPlayer p;
    CHECK(p.load(std::make_unique<ToneDecoder>(96000), 1.0));
    CHECK(p.play());
    CHECK(waitFor([&] { return p.positionMs() > 50.0; }));
    oboe::failOpen = true;  // the device stays gone
    oboe::lastStream.lock()->disconnect();
    CHECK(waitFor([&] { return p.state() == PlayerState::Error; }, 15000ms));
    oboe::failOpen = false;
    p.pause();
    p.seekToMs(0);
    CHECK(p.play());  // a later play reopens the output
    CHECK(p.state() == PlayerState::Playing);
}

/** Blocks inside its first readFrames() until released. */
class GateDecoder : public ToneDecoder {
public:
    GateDecoder() : ToneDecoder(48000) {}
    size_t readFrames(core::AudioBuffer& b, size_t n) override {
        if (!used.exchange(true)) {
            entered = true;
            while (!release) std::this_thread::sleep_for(1ms);
        }
        return ToneDecoder::readFrames(b, n);
    }
    std::atomic<bool> entered{false}, release{false}, used{false};
};

// Audit A02: a pause that lands while a block is being decoded must not drop it.
void testPauseDuringReadKeepsBlock() {
    core::AudioPlayer p;
    auto decoder = std::make_unique<GateDecoder>();
    auto* gate = decoder.get();
    CHECK(p.load(std::move(decoder), 1.0));
    CHECK(p.play());
    CHECK(waitFor([&] { return gate->entered.load(); }));
    std::atomic<bool> pausing{false};
    std::thread pauser([&] { pausing = true; p.pause(); });
    CHECK(waitFor([&] { return pausing.load(); }));
    std::this_thread::sleep_for(30ms);
    gate->release = true;
    pauser.join();
    CHECK(p.play());
    CHECK(waitFor([&] { return p.state() == PlayerState::Ended; }));
    CHECK_NEAR(p.positionMs(), 1000.0, 0.5);
}

// Audit A03: the next track is shorter than the look-ahead, so it has already
// ended while the previous one is still heard.
void testShortNextTrackLookAhead() {
    {
        core::AudioPlayer p;
        CHECK(p.load(std::make_unique<ToneDecoder>(30000), 1.0));
        p.setNext(std::make_unique<ToneDecoder>(480), 1.0);
        CHECK(p.play());
        CHECK(waitFor([&] { return p.positionMs() > 420.0; }));
        p.seekToMs(100.0);
        std::this_thread::sleep_for(30ms);
        CHECK_NEAR(p.durationMs(), 625.0, 1e-9);  // still A
        CHECK(p.positionMs() < 300.0);
        CHECK(waitFor([&] { return p.consumeTrackAdvanced(); }));  // B still follows
        CHECK_NEAR(p.durationMs(), 10.0, 1e-9);
        CHECK(waitFor([&] { return p.state() == PlayerState::Ended; }));
    }
    {
        core::AudioPlayer p;
        CHECK(p.load(std::make_unique<ToneDecoder>(30000, 48000, 2, 0.25), 1.0));
        p.setNext(std::make_unique<ToneDecoder>(480, 48000, 2, 0.125), 1.0);
        startCapture();
        CHECK(p.play());
        CHECK(waitFor([&] { return p.positionMs() > 420.0; }));
        p.clearNext();
        CHECK(waitFor([&] { return p.state() == PlayerState::Ended; }));
        oboe::capture = false;
        CHECK(countLevel(takeCapture(), 0.125f) < 16);  // B was never heard
        CHECK(!p.consumeTrackAdvanced());
    }
}

// Audit A04: clearing a next track of another format while the output drains.
void testClearDuringFormatChangeDrain() {
    core::AudioPlayer p;
    CHECK(p.load(std::make_unique<ToneDecoder>(9600), 1.0));
    p.setNext(std::make_unique<ToneDecoder>(8820, 44100, 1), 1.0);
    CHECK(p.play());
    std::this_thread::sleep_for(60ms);   // all of A is queued; the engine is draining
    oboe::suspendCallbacks = true;
    std::this_thread::sleep_for(30ms);
    p.clearNext();
    oboe::suspendCallbacks = false;
    CHECK(waitFor([&] { return p.state() == PlayerState::Ended; }));
    CHECK(p.trackInfo().sampleRate == 48000);
    CHECK(p.outputSampleRate() == 48000);
}

// Audit A07: format queries must not wait for a slow stream open.
void testQueriesDoNotWaitForStreamOpen() {
    core::AudioPlayer p;
    CHECK(p.load(std::make_unique<ToneDecoder>(48000), 1.0));
    oboe::openDelayMs = 300;
    std::thread loader([&] { CHECK(p.load(std::make_unique<ToneDecoder>(44100, 44100), 1.0)); });
    CHECK(waitFor([&] { return oboe::opening.load(); }));
    const auto start = std::chrono::steady_clock::now();
    p.outputSampleRate();
    p.outputChannels();
    p.positionMs();
    p.trackInfo();
    float bands[32];
    p.spectrum(bands, 32);
    const auto waited = std::chrono::steady_clock::now() - start;
    loader.join();
    oboe::openDelayMs = 0;
    CHECK(waited < 50ms);
}

// Audit A08: NaN/Inf in a float WAV must not reach the device or the DSP state.
void testNonFiniteSamplesAreSilenced(const std::string& dir) {
    const std::string path = dir + "/nonfinite.wav";
    drwav_data_format fmt{};
    fmt.container = drwav_container_riff;
    fmt.format = DR_WAVE_FORMAT_IEEE_FLOAT;
    fmt.channels = 2;
    fmt.sampleRate = 48000;
    fmt.bitsPerSample = 64;
    drwav writer{};
    CHECK(drwav_init_file_write(&writer, path.c_str(), &fmt, nullptr));
    std::vector<double> samples(4800 * 2);
    for (size_t i = 0; i < samples.size(); ++i) {
        samples[i] = i % 3 == 0 ? NAN : (i % 3 == 1 ? INFINITY : -INFINITY);
    }
    CHECK(drwav_write_pcm_frames(&writer, 4800, samples.data()) == 4800);
    drwav_uninit(&writer);

    core::AudioPlayer p;
    p.setEqEnabled(true);
    p.setEqBandGain(1, 6.0);
    auto decoder = std::make_unique<decoders::WavDecoder>();
    CHECK(decoder->open(path));
    CHECK(p.load(std::move(decoder), 1.0));
    p.setNext(std::make_unique<ToneDecoder>(4800, 48000, 2, 0.25), 1.0);  // a normal track after it
    startCapture();
    CHECK(p.play());
    CHECK(waitFor([&] { return p.state() == PlayerState::Ended; }));
    oboe::capture = false;
    const auto out = takeCapture();
    size_t invalid = 0;
    for (float v : out) if (!std::isfinite(v)) ++invalid;
    CHECK(invalid == 0);
    // The following track plays normally: the EQ state was not poisoned
    // (0.25 through the -6 dB automatic preamp of the +6 dB band = 0.1253).
    size_t audible = 0;
    for (size_t i = 0; i < out.size(); i += 2) if (std::fabs(out[i] - 0.1253f) < 0.001f) ++audible;
    CHECK(audible > 3000);
    std::remove(path.c_str());
}

void testFormatChangeTransition() {
    core::AudioPlayer p;
    CHECK(p.load(std::make_unique<ToneDecoder>(4800, 48000, 2), 1.0));
    p.setNext(std::make_unique<ToneDecoder>(8820, 44100, 1), 1.0);
    CHECK(p.play());
    CHECK(waitFor([&] { return p.consumeTrackAdvanced(); }));
    CHECK(p.trackInfo().sampleRate == 44100);
    CHECK(p.outputSampleRate() == 44100);
    CHECK(p.outputChannels() == 1);
    CHECK(p.state() == PlayerState::Playing);
    CHECK(waitFor([&] { return p.state() == PlayerState::Ended; }));
    CHECK_NEAR(p.positionMs(), 200.0, 0.5);
}

void testMultichannelFallback() {
    oboe::maxChannels = 2;
    core::AudioPlayer p;
    CHECK(p.load(std::make_unique<ToneDecoder>(4800, 48000, 6, 0.5), 1.0));
    CHECK(p.outputChannels() == 2);
    CHECK(p.trackInfo().channels == 6);
    startCapture();
    CHECK(p.play());
    CHECK(waitFor([&] { return p.state() == PlayerState::Ended; }));
    oboe::capture = false;
    oboe::maxChannels = 8;
    const auto out = takeCapture();
    float peak = 0;
    for (float v : out) peak = std::max(peak, std::fabs(v));
    CHECK(peak > 0.5f && peak <= 0.99f);  // folded down, never above the limiter ceiling
}

void testOutputFailure() {
    oboe::failOpen = true;
    core::AudioPlayer p;
    CHECK(!p.load(std::make_unique<ToneDecoder>(4800), 1.0));
    CHECK(p.state() == PlayerState::Error);
    CHECK(!p.play());
    oboe::failOpen = false;
    CHECK(p.load(std::make_unique<ToneDecoder>(4800), 1.0));
    CHECK(p.play());
    CHECK(waitFor([&] { return p.state() == PlayerState::Ended; }));
}

void testReconnect() {
    core::AudioPlayer p;
    CHECK(p.load(std::make_unique<ToneDecoder>(48000), 1.0));
    CHECK(p.play());
    CHECK(waitFor([&] { return p.positionMs() > 100.0; }));
    const int opens = oboe::openCount;
    oboe::lastStream.lock()->disconnect();
    // The worker reopens the stream and playback carries on to the end.
    CHECK(waitFor([&] { return oboe::openCount > opens; }));
    CHECK(waitFor([&] { return p.state() == PlayerState::Ended; }));
    CHECK_NEAR(p.positionMs(), 1000.0, 0.5);
}

void testLimiterAndEq() {
    core::AudioPlayer p;
    p.setEqEnabled(true);
    p.setEqBandGain(2, 12.0);
    CHECK(p.load(std::make_unique<ToneDecoder>(24000, 48000, 2, 0.99, 910.0), 2.0));  // +6 dB on top
    startCapture();
    CHECK(p.play());
    CHECK(waitFor([&] { return p.state() == PlayerState::Ended; }));
    oboe::capture = false;
    const auto out = takeCapture();
    float peak = 0;
    for (float v : out) { CHECK(std::isfinite(v)); peak = std::max(peak, std::fabs(v)); }
    CHECK(peak <= 0.99f);
    CHECK(peak > 0.8f);
}

// Switching the limiter on and off while playing hands out what it holds:
// every frame arrives exactly once. Crossfeed on mono content keeps the level.
void testOutputDspToggles() {
    core::AudioPlayer p;
    CHECK(p.load(std::make_unique<ToneDecoder>(48000), 1.0));
    p.setNext(std::make_unique<ToneDecoder>(24000, 48000, 2, 0.125), 1.0);
    startCapture();
    CHECK(p.play());
    CHECK(waitFor([&] { return p.positionMs() > 150.0; }));
    p.setLimiter(false);
    CHECK(waitFor([&] { return p.positionMs() > 350.0; }));
    p.setLimiter(true);
    CHECK(waitFor([&] { return p.positionMs() > 600.0; }));
    p.setLimiter(false);
    CHECK(waitFor([&] { return p.consumeTrackAdvanced(); }));
    p.setLimiter(true);
    CHECK(waitFor([&] { return p.state() == PlayerState::Ended; }));
    oboe::capture = false;
    const auto out = takeCapture();
    const size_t full = countLevel(out, 0.25f), half = countLevel(out, 0.125f);
    CHECK(full > 48000 - 1200 && full <= 48000);
    CHECK(half > 24000 - 64 && half <= 24000 + 1);   // +1: a fade-in sample passes 0.125
    CHECK_NEAR(p.positionMs(), 500.0, 0.5);

    // Crossfeed: identical channels (mono) pass at unity once the filters settle.
    core::AudioPlayer c;
    c.setCrossfeed(1);
    CHECK(c.load(std::make_unique<ToneDecoder>(24000), 1.0));
    startCapture();
    CHECK(c.play());
    CHECK(waitFor([&] { return c.state() == PlayerState::Ended; }));
    oboe::capture = false;
    const auto mono = takeCapture();
    CHECK(mono.size() >= 2 * 20000);
    CHECK(std::fabs(mono[2 * 20000] - 0.25f) < 2e-3f);
}

void testSpeed() {
    core::AudioPlayer p;
    CHECK(p.load(std::make_unique<ToneDecoder>(48000, 48000, 2, 0.25, 440.0), 1.0));
    p.setSpeed(2.0);
    const auto start = std::chrono::steady_clock::now();
    CHECK(p.play());
    CHECK(waitFor([&] { return p.state() == PlayerState::Ended; }));
    const auto elapsed = std::chrono::steady_clock::now() - start;
    CHECK(elapsed < 850ms);   // one second of audio at 2x
    CHECK(elapsed > 350ms);
    CHECK(p.positionMs() > 900.0);
    // Back to 1x mid-track: the bit-exact path resumes at the audible frame.
    CHECK(p.load(std::make_unique<ToneDecoder>(48000), 1.0));
    p.setSpeed(1.5);
    CHECK(p.play());
    CHECK(waitFor([&] { return p.positionMs() > 200.0; }));
    p.setSpeed(1.0);
    const double before = p.positionMs();
    std::this_thread::sleep_for(30ms);
    CHECK(p.positionMs() >= before - 5.0);
    p.setSpeedMode(1);
    CHECK(waitFor([&] { return p.state() == PlayerState::Ended; }));
}

void testSpectrum() {
    core::AudioPlayer p;
    CHECK(p.load(std::make_unique<ToneDecoder>(48000, 48000, 2, 0.5, 1000.0), 1.0));
    CHECK(p.play());
    CHECK(waitFor([&] { return p.positionMs() > 200.0; }));
    float bands[32];
    for (int i = 0; i < 4; ++i) p.spectrum(bands, 32);  // let the attack smoothing settle
    int loudest = 0;
    for (int i = 0; i < 32; ++i) { CHECK(std::isfinite(bands[i]) && bands[i] >= 0 && bands[i] <= 1); if (bands[i] > bands[loudest]) loudest = i; }
    // 1 kHz sits in band floor(32 * log10(50) / 3) = 18 of the 20 Hz..20 kHz scale.
    CHECK(loudest >= 17 && loudest <= 19);
    CHECK(bands[loudest] > 0.85f);  // -6 dBFS on the 60 dB scale reads 0.9
    float many[200];
    p.spectrum(many, 200);  // clamps to the supported band count
    p.pause();
    p.spectrum(bands, 32);
    CHECK(bands[loudest] < 1.0f);
}

void testConcurrentControl() {
    core::AudioPlayer p;
    std::atomic<bool> stop{false};
    std::thread reader([&] {
        float bands[32];
        while (!stop) {
            p.positionMs(); p.durationMs(); p.trackInfo(); p.consumeTrackAdvanced(); p.spectrum(bands, 32);
        }
    });
    for (int i = 0; i < 60; ++i) {
        CHECK(p.load(std::make_unique<ToneDecoder>(4800 + i * 37, i % 3 ? 48000 : 44100, 1 + i % 2), 1.0));
        p.setNext(std::make_unique<ToneDecoder>(2400), 1.0);
        p.play();
        if (i % 4 == 0) p.seekToMs(30.0);
        if (i % 5 == 0) p.setSpeed(i % 2 ? 1.25 : 1.0);
        std::this_thread::sleep_for(std::chrono::milliseconds(i % 7));
        if (i % 3 == 0) p.pause();
        if (i % 11 == 0) p.stop();
    }
    stop = true;
    reader.join();
}

// Audit A01: play() racing the decode thread's gapless switch.
void testPlayDuringGaplessSwitch() {
    core::AudioPlayer p;
    CHECK(p.load(std::make_unique<ToneDecoder>(4800), 1.0));
    std::atomic<bool> done{false};
    std::thread controls([&] { while (!done.load()) { p.play(); p.seekToMs(10.0); } });
    for (int i = 0; i < 100; ++i) {
        CHECK(p.load(std::make_unique<ToneDecoder>(4800), 1.0));
        p.setNext(std::make_unique<ToneDecoder>(2400), 1.0);
        p.play();
        std::this_thread::sleep_for(5ms);
    }
    done = true;
    controls.join();
}

void testDestroyWhilePlaying() {
    for (int i = 0; i < 10; ++i) {
        core::AudioPlayer p;
        CHECK(p.load(std::make_unique<ToneDecoder>(48000), 1.0));
        p.setNext(std::make_unique<ToneDecoder>(48000), 1.0);
        CHECK(p.play());
        std::this_thread::sleep_for(std::chrono::milliseconds(i * 3));
    }
}

} // namespace

int main() {
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    testPlaysToEnd();
    testPauseResumeKeepsEverySample();
    testVolumeIsImmediate();
    testSeek();
    testGaplessSameFormat();
    testSeekDuringGaplessLookAhead();
    testClearNextDuringGaplessLookAhead();
    testFormatChangeTransition();
    testPauseDuringReadKeepsBlock();
    testShortNextTrackLookAhead();
    testClearDuringFormatChangeDrain();
    testQueriesDoNotWaitForStreamOpen();
    testNonFiniteSamplesAreSilenced("/tmp");
    testMultichannelFallback();
    testOutputFailure();
    testReconnect();
    testFailedRestartIsReported();
    testLimiterAndEq();
    testOutputDspToggles();
    testSpeed();
    testSpectrum();
    testConcurrentControl();
    testPlayDuringGaplessSwitch();
    testDestroyWhilePlaying();
    std::puts("Player tests passed: end/drain, sample-exact pause/resume, immediate volume, seek, gapless, "
              "seek/clear during gapless look-ahead, format change, downmix fallback, output failure, reconnect, "
              "failed restart, limiter+EQ, speed, spectrum, concurrency, audit A01-A04/A07/A08 regressions.");
    return 0;
}
