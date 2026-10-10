// Own USB driver (phase 2): descriptor parsing, the streamer against a
// simulated DAC (clock, feedback, control requests, unplug) and the player
// playing through it.
#include <atomic>
#include <chrono>
#include <cstring>
#include <map>
#include <mutex>
#include <thread>

#include "test_util.h"

#include "core/AudioPlayer.h"
#include "usb/UacDescriptors.h"
#include "usb/UacStreamer.h"

using namespace audio_engine;
using namespace std::chrono_literals;
using core::PlayerState;
using test::Bytes;

namespace {

// ── Descriptors of two typical DACs ──────────────────────────────────────────

void put(Bytes& b, std::initializer_list<int> v) { for (int x : v) b.push_back(static_cast<uint8_t>(x)); }

/** UAC2, high speed, async with explicit feedback: 24-in-32 bit (alt 1) and 16-bit (alt 2). */
Bytes uac2Dac(int fbInterval = 4) {
    Bytes b;
    put(b, {9, 2, 0, 0, 2, 1, 0, 0x80, 50});                    // configuration
    put(b, {8, 0x0B, 0, 2, 1, 0, 0x20, 0});                     // IAD
    put(b, {9, 4, 0, 0, 0, 1, 1, 0x20, 0});                     // AudioControl
    put(b, {9, 0x24, 0x01, 0x00, 0x02, 1, 0, 0, 0});             // header 2.0
    put(b, {8, 0x24, 0x0B, 0x28, 1, 0x29, 0x03, 0});             // clock selector -> source 0x29
    put(b, {8, 0x24, 0x0A, 0x29, 0x03, 0x07, 0, 0});             // clock source, programmable
    put(b, {17, 0x24, 0x02, 1, 0x01, 0x01, 0, 0x28, 2, 3, 0, 0, 0, 0, 0, 0, 0});   // input terminal (USB streaming)
    put(b, {12, 0x24, 0x03, 3, 0x01, 0x03, 0, 1, 0x28, 0, 0, 0});                 // output terminal
    put(b, {9, 4, 1, 0, 0, 1, 2, 0x20, 0});                     // AudioStreaming alt 0
    for (int alt = 1; alt <= 2; ++alt) {
        const int subslot = alt == 1 ? 4 : 2, bits = alt == 1 ? 24 : 16;
        put(b, {9, 4, 1, alt, 2, 1, 2, 0x20, 0});
        put(b, {16, 0x24, 0x01, 1, 0, 1, 1, 0, 0, 0, 2, 3, 0, 0, 0, 0});            // AS_GENERAL: PCM, 2 ch
        put(b, {6, 0x24, 0x02, 1, subslot, bits});                                   // FORMAT_TYPE I
        put(b, {7, 5, 0x01, 0x05, 0x88, 0x01, 1});                                   // iso OUT async, 392 bytes
        put(b, {8, 0x25, 0x01, 0, 0, 0, 0, 0});
        put(b, {7, 5, 0x81, 0x11, 4, 0, fbInterval});                                // feedback IN
    }
    b[2] = static_cast<uint8_t>(b.size());
    b[3] = static_cast<uint8_t>(b.size() >> 8);
    return b;
}

/** UAC1, full speed, adaptive: 16-bit stereo at 44.1 / 48 / 96 kHz. */
Bytes uac1Dac() {
    Bytes b;
    put(b, {9, 2, 0, 0, 2, 1, 0, 0x80, 50});
    put(b, {9, 4, 0, 0, 0, 1, 1, 0, 0});
    put(b, {9, 0x24, 0x01, 0x00, 0x01, 30, 0, 1, 1});
    put(b, {12, 0x24, 0x02, 1, 0x01, 0x01, 0, 2, 3, 0, 0, 0});
    put(b, {9, 4, 1, 0, 0, 1, 2, 0, 0});
    put(b, {9, 4, 1, 1, 1, 1, 2, 0, 0});
    put(b, {7, 0x24, 0x01, 1, 1, 0x01, 0x00});
    put(b, {17, 0x24, 0x02, 1, 2, 2, 16, 3, 0x44, 0xAC, 0x00, 0x80, 0xBB, 0x00, 0x00, 0x77, 0x01});
    put(b, {9, 5, 0x02, 0x09, 0x88, 0x01, 1, 0, 0});            // iso OUT adaptive, 392 bytes
    put(b, {7, 0x25, 0x01, 0x01, 0, 0, 0});                      // sampling frequency control
    b[2] = static_cast<uint8_t>(b.size());
    return b;
}

// ── A simulated DAC ──────────────────────────────────────────────────────────

struct DacState {
    std::mutex mutex;
    usb::UsbSpeed speed = usb::UsbSpeed::High;
    double clockPpm = 0.0;           // the DAC's crystal against the nominal rate
    int feedbackFormat = 4;          // bytes: 4 = Q16.16, 3 = Q10.14
    double feedbackScale = 1.0;      // a buggy DAC reports in another unit
    std::vector<usb::RateRange> ranges{{44100, 44100, 0}, {48000, 48000, 0}, {88200, 88200, 0}, {96000, 96000, 0},
                                       {176400, 176400, 0}, {192000, 192000, 0}};
    uint32_t rate = 0;
    std::map<int, int> claimed;      // interface -> claim count
    std::map<int, int> alt;          // interface -> alternate setting
    int released = 0;
    bool realtime = false;           // pace completions on the wall clock (player tests)
    int failAfter = -1;              // data transfers until the device "unplugs"
    std::vector<uint8_t> captured;   // every data byte played
    bool capture = false;
    // Clock bookkeeping
    double time = 0.0;               // simulated seconds
    double dataEnd = 0.0;
    double startTime = -1.0;
    uint64_t framesReceived = 0;
    size_t frameBytes = 8;
    double minLevel = 1e18, maxLevel = -1e18;   // received - consumed (frames)
    int dataTransfers = 0;
    int discardedData = 0;           // audio cancelled before it played
};

class FakeTransport : public usb::UsbTransport {
public:
    explicit FakeTransport(std::shared_ptr<DacState> s) : m_s(std::move(s)), m_wall(std::chrono::steady_clock::now()) {}
    ~FakeTransport() override { for (auto* t : m_all) delete t; }

    usb::UsbSpeed speed() const override { return m_s->speed; }
    bool claimInterface(int iface) override { std::lock_guard<std::mutex> l(m_s->mutex); ++m_s->claimed[iface]; return true; }
    void releaseInterface(int iface) override {
        std::lock_guard<std::mutex> l(m_s->mutex);
        if (m_s->claimed[iface] > 0) --m_s->claimed[iface];
        ++m_s->released;
    }
    bool setInterface(int iface, int alt) override { std::lock_guard<std::mutex> l(m_s->mutex); m_s->alt[iface] = alt; return true; }

    int control(uint8_t type, uint8_t request, uint16_t value, uint16_t, uint8_t* data, uint16_t length, unsigned) override {
        std::lock_guard<std::mutex> l(m_s->mutex);
        if (type == 0xA1 && request == 0x02 && value == 0x0100) {          // UAC2 GET RANGE
            Bytes r;
            test::le(r, m_s->ranges.size(), 2);
            for (const auto& x : m_s->ranges) { test::le(r, x.min, 4); test::le(r, x.max, 4); test::le(r, x.res, 4); }
            const size_t n = std::min<size_t>(length, r.size());
            std::memcpy(data, r.data(), n);
            return static_cast<int>(n);
        }
        if (type == 0x21 && request == 0x01) { m_s->rate = usb::detail::le32(data); return 4; }   // UAC2 SET CUR
        if (type == 0xA1 && request == 0x01) {                                                   // UAC2 GET CUR
            for (int i = 0; i < 4; ++i) data[i] = static_cast<uint8_t>(m_s->rate >> (8 * i));
            return 4;
        }
        if (type == 0x22 && request == 0x01) { m_s->rate = usb::detail::le24(data); return 3; }   // UAC1 endpoint
        return -EPIPE;
    }

    usb::IsoTransfer* allocIso(uint8_t ep, size_t packets, size_t bytes) override {
        auto* t = new usb::IsoTransfer();
        t->endpoint = ep;
        t->buffer.assign(bytes, 0);
        t->length.assign(packets, 0);
        t->actual.assign(packets, 0);
        m_all.push_back(t);
        return t;
    }
    void freeIso(usb::IsoTransfer* t) override {
        m_all.erase(std::find(m_all.begin(), m_all.end(), t));
        m_queue.erase(std::remove_if(m_queue.begin(), m_queue.end(), [&](const Pending& p) { return p.t == t; }), m_queue.end());
        delete t;
    }

    int submit(usb::IsoTransfer* t) override {
        std::lock_guard<std::mutex> l(m_s->mutex);
        const double pps = packetsPerSecond();
        if (t->endpoint & 0x80) {
            m_queue.push_back({t, m_s->time + 1.0 / 1000.0, false});
            return 0;
        }
        if (m_s->failAfter >= 0 && m_s->dataTransfers >= m_s->failAfter) return -ENODEV;
        ++m_s->dataTransfers;
        if (m_s->startTime < 0) m_s->startTime = m_s->time + 0.001;   // first packet plays after 1 ms
        const double start = std::max(m_s->dataEnd, m_s->time);
        m_s->dataEnd = start + static_cast<double>(t->packets()) / pps;
        m_queue.push_back({t, m_s->dataEnd, false});
        return 0;
    }

    void discard(usb::IsoTransfer* t) override {
        std::lock_guard<std::mutex> l(m_s->mutex);
        for (auto& p : m_queue) if (p.t == t) p.discarded = true;
    }

    usb::IsoTransfer* reap(int, int* error) override {
        *error = 0;
        Pending next;
        {
            std::lock_guard<std::mutex> l(m_s->mutex);
            if (m_s->failAfter >= 0 && m_s->dataTransfers >= m_s->failAfter && !m_queue.empty()) {
                // Unplugged: everything in flight completes with an error.
                auto p = m_queue.front();
                m_queue.erase(m_queue.begin());
                p.t->status = -ENODEV;
                return p.t;
            }
            if (m_queue.empty()) { *error = 0; return nullptr; }
            auto it = std::min_element(m_queue.begin(), m_queue.end(), [](const Pending& a, const Pending& b) {
                return a.discarded != b.discarded ? a.discarded : a.when < b.when;
            });
            next = *it;
            m_queue.erase(it);
        }
        if (m_s->realtime && !next.discarded) {
            const auto due = m_wall + std::chrono::duration<double>(next.when);
            std::this_thread::sleep_until(due);
        }
        std::lock_guard<std::mutex> l(m_s->mutex);
        auto* t = next.t;
        if (next.discarded) {
            if (!(t->endpoint & 0x80)) ++m_s->discardedData;
            t->status = -ENOENT;
            return t;
        }
        m_s->time = std::max(m_s->time, next.when);
        t->status = 0;
        const double deviceRate = m_s->rate * (1.0 + m_s->clockPpm * 1e-6);
        if (t->endpoint & 0x80) {
            // Frames per (micro)frame of the DAC's clock.
            const double perFrame = deviceRate / (m_s->speed == usb::UsbSpeed::High ? 8000.0 : 1000.0) * m_s->feedbackScale;
            const uint32_t v = m_s->feedbackFormat == 4 ? static_cast<uint32_t>(perFrame * 65536.0 + 0.5)
                                                        : static_cast<uint32_t>(perFrame * 16384.0 + 0.5);
            for (int i = 0; i < 4; ++i) t->buffer[i] = static_cast<uint8_t>(v >> (8 * i));
            t->actual[0] = static_cast<uint32_t>(m_s->feedbackFormat);
            return t;
        }
        size_t bytes = 0;
        for (size_t i = 0; i < t->packets(); ++i) { t->actual[i] = t->length[i]; bytes += t->length[i]; }
        if (m_s->capture) m_s->captured.insert(m_s->captured.end(), t->buffer.begin(), t->buffer.begin() + bytes);
        // The DAC's buffer level at the end of this transfer.
        m_s->framesReceived += bytes / m_s->frameBytes;
        const double consumed = std::max(0.0, m_s->time - m_s->startTime) * deviceRate;
        const double level = static_cast<double>(m_s->framesReceived) - consumed;
        if (m_s->time > 0.5) {   // after the start-up
            m_s->minLevel = std::min(m_s->minLevel, level);
            m_s->maxLevel = std::max(m_s->maxLevel, level);
        }
        return t;
    }

private:
    struct Pending { usb::IsoTransfer* t; double when; bool discarded; };
    double packetsPerSecond() const { return m_s->speed == usb::UsbSpeed::High ? 8000.0 : 1000.0; }

    std::shared_ptr<DacState> m_s;
    std::vector<usb::IsoTransfer*> m_all;
    std::vector<Pending> m_queue;
    std::chrono::steady_clock::time_point m_wall;
};

std::shared_ptr<usb::UacStreamer> makeDac(const Bytes& descriptors, std::shared_ptr<DacState> state) {
    return std::make_shared<usb::UacStreamer>(std::make_unique<FakeTransport>(state),
                                              usb::parseUac(descriptors.data(), descriptors.size()));
}

template <typename Pred>
bool waitFor(Pred pred, std::chrono::milliseconds timeout = 8000ms) {
    const auto deadline = std::chrono::steady_clock::now() + timeout;
    while (!pred()) {
        if (std::chrono::steady_clock::now() > deadline) return false;
        std::this_thread::sleep_for(1ms);
    }
    return true;
}

double simTime(DacState& s) { std::lock_guard<std::mutex> l(s.mutex); return s.time; }

// ── Descriptor parsing ───────────────────────────────────────────────────────

void testParseUac2() {
    const Bytes d = uac2Dac();
    const auto dev = usb::parseUac(d.data(), d.size());
    CHECK(dev.version == 2);
    CHECK(dev.controlInterface == 0);
    CHECK(dev.formats.size() == 2);
    const auto& f = dev.formats[0];
    CHECK(f.interface == 1 && f.alt == 1 && f.channels == 2 && f.subslotBytes == 4 && f.bits == 24);
    CHECK(f.endpoint == 0x01 && f.maxPacketBytes == 392 && f.sync == usb::SyncType::Async);
    CHECK(f.feedbackEndpoint == 0x81 && f.feedbackMaxPacket == 4 && !f.implicitFeedback);
    CHECK(f.clockId == 0x28 && dev.clockSourceFor(f.clockId) == 0x29);   // through the selector
    CHECK(dev.formats[1].subslotBytes == 2 && dev.formats[1].bits == 16);
    // Truncated or foreign data: nothing usable, no crash.
    CHECK(usb::parseUac(d.data(), 20).formats.empty());
    const Bytes junk{3, 4, 1, 0, 0xFF, 2};
    CHECK(usb::parseUac(junk.data(), junk.size()).version == 0);
}

void testParseUac1() {
    const Bytes d = uac1Dac();
    const auto dev = usb::parseUac(d.data(), d.size());
    CHECK(dev.version == 1);
    CHECK(dev.formats.size() == 1);
    const auto& f = dev.formats[0];
    CHECK(f.channels == 2 && f.subslotBytes == 2 && f.bits == 16 && f.sync == usb::SyncType::Adaptive);
    CHECK(f.uac1RateControl && f.feedbackEndpoint == 0);
    CHECK(f.rates.size() == 3 && f.rates[0].min == 44100 && f.rates[2].min == 96000);
}

// ── Streamer ─────────────────────────────────────────────────────────────────

void testRatesAndSetup() {
    auto s = std::make_shared<DacState>();
    s->ranges = {{44100, 48000, 3900}, {88200, 96000, 7800}, {176400, 192000, 15600}, {352800, 384000, 31200},
                 {705600, 768000, 62400}};
    auto dac = makeDac(uac2Dac(), s);
    // 705.6 / 768 kHz need 89+ frames per microframe: only the 16-bit setting's 4-byte frames fit 392 bytes.
    CHECK((dac->rates(2) == std::vector<uint32_t>{44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000}));
    CHECK(dac->rates(6).empty());
    CHECK(dac->chooseRate(96000, 2) == 96000);
    CHECK(dac->chooseRate(22050, 2) == 44100);    // a multiple
    CHECK(dac->chooseRate(32000, 2) == 96000);    // a multiple before the next one up
    CHECK(dac->chooseRate(37800, 2) == 44100);    // the next one up
    CHECK(dac->chooseRate(1536000, 2) == 768000); // the highest
    const auto* fast = dac->open(768000, 2, [](uint8_t*, size_t) {}, [] {});
    CHECK(fast && fast->bits == 16);              // the only format that fits
    const auto* f = dac->open(96000, 2, [](uint8_t*, size_t) {}, [] {});
    CHECK(f && f->bits == 24 && f->subslotBytes == 4);   // the widest format
    CHECK(s->rate == 96000);
    CHECK(s->claimed[0] == 1 && s->claimed[1] == 1);
    CHECK(dac->start());
    CHECK(waitFor([&] { return dac->framesSent() > 96000 / 10; }));
    { std::lock_guard<std::mutex> l(s->mutex); CHECK(s->alt[1] == 1); }
    dac->pause();
    { std::lock_guard<std::mutex> l(s->mutex); CHECK(s->alt[1] == 0); }
    // Interfaces stay claimed across opens; close() hands them back.
    CHECK(dac->open(44100, 2, [](uint8_t*, size_t) {}, [] {}));
    CHECK(s->claimed[1] == 1 && s->rate == 44100);
    dac->close();
    CHECK(s->claimed[0] == 0 && s->claimed[1] == 0 && s->released == 2);
}

/** Plays `seconds` of simulated time at a DAC clock `ppm` off; returns the DAC buffer swing in frames. */
double drift(double ppm, int feedbackBytes, double feedbackScale, usb::UsbSpeed speed, double seconds,
             uint32_t* fppOut = nullptr) {
    auto s = std::make_shared<DacState>();
    s->clockPpm = ppm;
    s->feedbackFormat = feedbackBytes;
    s->feedbackScale = feedbackScale;
    s->speed = speed;
    auto dac = makeDac(uac2Dac(speed == usb::UsbSpeed::High ? 4 : 1), s);
    CHECK(dac->open(44100, 2, [](uint8_t* out, size_t n) { std::memset(out, 0, n * 8); }, [] {}));
    CHECK(dac->start());
    CHECK(waitFor([&] { return simTime(*s) > seconds; }, 60000ms));
    dac->pause();
    if (fppOut) *fppOut = dac->framesPerPacketQ16();
    std::lock_guard<std::mutex> l(s->mutex);
    return s->maxLevel - s->minLevel;
}

void testFeedbackFollowsTheDacClock() {
    // +80 ppm: without feedback 44100 x 80e-6 x 60 s = 212 frames would pile up.
    uint32_t fpp = 0;
    const double hs = drift(80.0, 4, 1.0, usb::UsbSpeed::High, 60.0, &fpp);
    CHECK(hs < 16.0);
    CHECK_NEAR(fpp / 65536.0, 44100.0 * 1.00008 / 8000.0, 1e-4);
    // Full speed sends Q10.14 in 3 bytes.
    CHECK(drift(-120.0, 3, 1.0, usb::UsbSpeed::Full, 60.0) < 16.0);
    // A DAC that reports in the wrong unit (x8, as some high-speed DACs do) is still followed.
    CHECK(drift(80.0, 4, 8.0, usb::UsbSpeed::High, 60.0) < 16.0);
    std::printf("  Feedback: +80 ppm DAC, 60 s: buffer swing %.1f frames (no drift)\n", hs);
}

void testPayloadAndUac1() {
    auto s = std::make_shared<DacState>();
    s->speed = usb::UsbSpeed::Full;
    s->frameBytes = 4;
    auto dac = makeDac(uac1Dac(), s);
    CHECK((dac->rates(2) == std::vector<uint32_t>{44100, 48000, 96000}));
    uint64_t counter = 0;
    const auto* f = dac->open(48000, 2, [&](uint8_t* out, size_t n) {
        for (size_t i = 0; i < n * 2; ++i, ++counter) {
            const int16_t v = static_cast<int16_t>(counter & 0x7FFF);
            std::memcpy(out + i * 2, &v, 2);
        }
    }, [] {});
    CHECK(f && f->subslotBytes == 2);
    { std::lock_guard<std::mutex> l(s->mutex); s->capture = true; }
    CHECK(dac->start());
    CHECK(s->rate == 48000);   // UAC1: SET_CUR on the endpoint
    CHECK(waitFor([&] { return simTime(*s) > 2.0; }));
    dac->pause();
    std::lock_guard<std::mutex> l(s->mutex);
    // Exactly 48 frames per 1 ms packet, every rendered sample in order.
    CHECK(s->captured.size() >= 2 * 48000 * 4 - 4 * 48 * 16);
    for (size_t i = 0; i < s->captured.size() / 2; ++i) {
        int16_t v;
        std::memcpy(&v, s->captured.data() + i * 2, 2);
        CHECK(v == static_cast<int16_t>(i & 0x7FFF));
    }
}

void testUnplugIsReported() {
    auto s = std::make_shared<DacState>();
    s->failAfter = 200;
    auto dac = makeDac(uac2Dac(), s);
    std::atomic<int> errors{0};
    CHECK(dac->open(48000, 2, [](uint8_t* out, size_t n) { std::memset(out, 0, n * 8); }, [&] { ++errors; }));
    CHECK(dac->start());
    CHECK(waitFor([&] { return errors.load() == 1; }));
    CHECK(dac->failed());
    CHECK(!dac->start());
    CHECK(!dac->open(48000, 2, [](uint8_t*, size_t) {}, [] {}));
    dac->close();
}

// ── The player through the own driver ────────────────────────────────────────

/** 16-bit counter source as in player_tests: L(n) = n % 20000 + 1, R(n) = -(n % 7919) - 1. */
class CounterDecoder : public decoders::IAudioDecoder {
public:
    CounterDecoder(uint64_t frames, uint32_t rate) : m_total(frames), m_rate(rate) {}
    bool openFd(int) override { return true; }
    size_t readFrames(core::AudioBuffer& b, size_t n) override {
        n = static_cast<size_t>(std::min<uint64_t>({n, b.getNumFrames(), m_total - m_pos}));
        for (size_t f = 0; f < n; ++f) {
            b.getWritePointer(0)[f] = left(m_pos + f) / 32768.0;
            b.getWritePointer(1)[f] = right(m_pos + f) / 32768.0;
        }
        m_pos += n;
        return n;
    }
    bool seekToFrame(uint64_t f) override { m_pos = std::min(f, m_total); return true; }
    uint32_t getSampleRate() const override { return m_rate; }
    size_t getNumChannels() const override { return 2; }
    uint32_t getBitsPerSample() const override { return 16; }
    uint64_t getTotalFrames() const override { return m_total; }
    uint64_t getCurrentFrame() const override { return m_pos; }
    decoders::Codec getCodec() const override { return decoders::Codec::Wav; }
    static int left(uint64_t n) { return static_cast<int>(n % 20000) + 1; }
    static int right(uint64_t n) { return -static_cast<int>(n % 7919) - 1; }
private:
    uint64_t m_total, m_pos = 0;
    uint32_t m_rate;
};

constexpr int kFadeMsAllowance = 60;   // pause fade-out (40 ms) and fade-in (12 ms), with margin

void testPlayerThroughOwnDriver() {
    auto s = std::make_shared<DacState>();
    s->realtime = true;
    s->clockPpm = 50.0;
    auto dac = makeDac(uac2Dac(), s);
    core::AudioPlayer p;
    p.setUsbDevice(dac);
    const uint64_t total = 48000;
    CHECK(p.load(std::make_unique<CounterDecoder>(total, 48000), 1.0));
    CHECK(p.isUsbOutput() && p.isDirectOutput());
    CHECK(p.outputSampleRate() == 48000 && p.outputBits() == 24 && !p.outputIsFloat());
    { std::lock_guard<std::mutex> l(s->mutex); s->capture = true; }
    CHECK(p.play());
    CHECK(waitFor([&] { return p.positionMs() > 400.0; }));
    p.pause();
    std::this_thread::sleep_for(50ms);
    CHECK(p.play());
    CHECK(waitFor([&] { return p.state() == PlayerState::Ended; }));
    CHECK_NEAR(p.positionMs(), 1000.0, 0.5);
    CHECK(p.inexactOutputSamples() == 0);
    { std::lock_guard<std::mutex> l(s->mutex); CHECK(s->discardedData == 0); }   // the pause cut nothing off
    // 24-bit samples in 32-bit slots: 16-bit values << 16. Every untouched frame
    // arrives exactly once and in order; only the fades (start, pause) differ.
    std::this_thread::sleep_for(100ms);   // the transfers still in flight reach the DAC
    std::vector<int32_t> out;
    {
        std::lock_guard<std::mutex> l(s->mutex);
        out.resize(s->captured.size() / 4);
        std::memcpy(out.data(), s->captured.data(), out.size() * 4);
    }
    int64_t last = -1;
    size_t exact = 0;
    for (size_t i = 0; i + 1 < out.size(); i += 2) {
        if ((out[i] & 0xFFFF) || (out[i + 1] & 0xFFFF) || out[i] <= 0 || out[i + 1] >= 0) continue;   // silence or a fade
        // L = n % 20000 + 1 and R = -(n % 7919) - 1 identify n below 48000 uniquely.
        const int64_t a = (out[i] >> 16) - 1, b = -(out[i + 1] >> 16) - 1;
        int64_t n = -1;
        for (int64_t k = 0; k < 3; ++k) if ((a + 20000 * k) % 7919 == b) n = a + 20000 * k;
        if (n < 0) continue;         // a faded sample that happens to look exact
        if (n <= last) std::fprintf(stderr, "frame %zu: source frame %lld after %lld\n", i / 2, (long long)n, (long long)last);
        CHECK(n > last);             // never repeated, never out of order
        last = n;
        ++exact;
        if (n == static_cast<int64_t>(total) - 1) break;   // the end ramp follows
    }
    CHECK(last == static_cast<int64_t>(total) - 1);
    CHECK(exact > total - 1000 - 2 * 48 * kFadeMsAllowance);

    // A file at a rate the DAC lacks is converted to one it has.
    s->ranges = {{48000, 48000, 0}, {96000, 96000, 0}};
    auto dac2 = makeDac(uac2Dac(), s);
    p.setUsbDevice(dac2);
    CHECK(p.load(std::make_unique<CounterDecoder>(22050, 44100), 1.0));
    CHECK(p.isUsbOutput() && p.outputSampleRate() == 48000);
    CHECK(p.play());
    CHECK(waitFor([&] { return p.state() == PlayerState::Ended; }));
    CHECK_NEAR(p.positionMs(), 500.0, 0.5);

    // Removing the driver hands the DAC back and plays on through Oboe.
    p.setUsbDevice(nullptr);
    CHECK(s->claimed[0] == 0 && s->claimed[1] == 0);
    CHECK(p.load(std::make_unique<CounterDecoder>(4800, 48000), 1.0));
    CHECK(!p.isUsbOutput());
}

void testUnplugDuringPlayback() {
    auto s = std::make_shared<DacState>();
    s->realtime = true;
    s->failAfter = 150;   // ~0.3 s in
    auto dac = makeDac(uac2Dac(), s);
    core::AudioPlayer p;
    p.setUsbDevice(dac);
    CHECK(p.load(std::make_unique<CounterDecoder>(48000 * 3, 48000), 1.0));
    CHECK(p.isUsbOutput());
    CHECK(p.play());
    // The stream moves to Oboe but does not start by itself (the app pauses, like Android on unplug).
    CHECK(waitFor([&] { return !p.isUsbOutput() && p.outputSampleRate() > 0; }));
    const double at = p.positionMs();
    std::this_thread::sleep_for(150ms);
    CHECK(p.positionMs() == at);
    p.pause();
    CHECK(p.play());   // the user resumes: on the phone now
    CHECK(waitFor([&] { return p.positionMs() > at + 100.0; }));
    p.stop();
}

}  // namespace

int main() {
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    testParseUac2();
    testParseUac1();
    testRatesAndSetup();
    testFeedbackFollowsTheDacClock();
    testPayloadAndUac1();
    testUnplugIsReported();
    testPlayerThroughOwnDriver();
    testUnplugDuringPlayback();
    std::puts("USB tests passed: UAC1/UAC2 descriptors, rates via clock ranges, setup and release, async feedback "
              "(HS Q16.16, FS Q10.14, wrong-unit DAC) without drift, UAC1 payload, unplug, player bit-exact through "
              "the own driver with pause, conversion to a DAC rate, hand-back, unplug while playing.");
    return 0;
}
