#pragma once

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

#if defined(__ANDROID__) || defined(__linux__)
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>
#endif

#include "UacDescriptors.h"
#include "UsbTransport.h"

namespace audio_engine {
namespace usb {

/**
 * Plays PCM to a USB Audio Class 1/2 DAC through a UsbTransport: chooses the
 * alternate setting and rate, sets the clock, and keeps isochronous transfers
 * in flight from its own thread. Packet sizes follow the DAC's clock through
 * its feedback endpoint (asynchronous DACs), so nothing drifts.
 *
 * The audio itself comes from a render callback that writes interleaved
 * frames in the device format (OboeOutput's callback), so gain, fades, the
 * limiter, dither and conversion are shared with every other output.
 *
 * The interfaces are claimed at the first open() and kept until close(), so
 * Android does not get the DAC back (and reroute) between tracks.
 *
 * Threads: open()/start()/pause()/close() from one control thread at a time;
 * the render and error callbacks run on the streaming thread.
 */
class UacStreamer {
public:
    using Render = std::function<void(uint8_t* out, size_t frames)>;
    using ErrorHandler = std::function<void()>;

    static constexpr uint32_t kCommonRates[] = {44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000};
    static constexpr double kTransferSeconds = 0.002;   // audio per transfer
    static constexpr int kTransfersInFlight = 12;        // ~24 ms queued
    static constexpr unsigned kControlTimeoutMs = 1000;

    UacStreamer(std::unique_ptr<UsbTransport> transport, UacDevice device)
        : m_transport(std::move(transport)), m_device(std::move(device)) {
        m_speed = m_transport->speed();
        if (m_speed == UsbSpeed::Unknown) m_speed = UsbSpeed::High;   // every UAC2 DAC is high speed
    }

    ~UacStreamer() { close(); }

    UacStreamer(const UacStreamer&) = delete;
    UacStreamer& operator=(const UacStreamer&) = delete;

    const UacDevice& device() const { return m_device; }
    bool usable() const { return m_device.version != 0 && !m_device.formats.empty(); }
    /** True once the device failed (unplugged, permission revoked); it stays unusable. */
    bool failed() const { return m_failed.load(std::memory_order_acquire); }

    /** Rates the DAC plays with `channels` channels (asks UAC2 clocks once, which claims the DAC). */
    std::vector<uint32_t> rates(int channels) {
        std::vector<uint32_t> out;
        for (const auto& f : m_device.formats) {
            if (f.channels != channels) continue;
            for (uint32_t r : formatRates(f)) if (std::find(out.begin(), out.end(), r) == out.end()) out.push_back(r);
        }
        std::sort(out.begin(), out.end());
        return out;
    }

    /**
     * The rate to open for a source at `rate`: the same rate when the DAC has
     * it, else (the engine converts) its smallest multiple, else the lowest
     * rate above it, else the highest. 0 when nothing fits the channels.
     */
    uint32_t chooseRate(uint32_t rate, int channels) {
        const auto all = rates(channels);
        if (all.empty()) return 0;
        if (std::find(all.begin(), all.end(), rate) != all.end()) return rate;
        for (uint32_t r : all) if (r % rate == 0) return r;
        for (uint32_t r : all) if (r > rate) return r;
        return all.back();
    }

    /**
     * Configures the DAC for `rate` / `channels` with the widest sample format
     * it offers there. Returns the chosen format, or nullptr.
     */
    const PlaybackFormat* open(uint32_t rate, int channels, Render render, ErrorHandler onError) {
        pause();
        if (failed()) return nullptr;
        const PlaybackFormat* best = nullptr;
        for (const auto& f : m_device.formats) {
            if (f.channels != channels) continue;
            const auto list = formatRates(f);
            if (std::find(list.begin(), list.end(), rate) == list.end()) continue;
            if (!best || f.bits > best->bits || (f.bits == best->bits && f.subslotBytes < best->subslotBytes)) best = &f;
        }
        if (!best || !claimAll()) return nullptr;
        m_format = *best;
        m_rate = rate;
        m_render = std::move(render);
        m_onError = std::move(onError);
        // Rate first (UAC2 clocks, UAC1 endpoint), at the zero-bandwidth setting.
        m_transport->setInterface(m_format.interface, 0);
        if (!setRate()) return nullptr;
        prepareSchedule();
        return &m_format;
    }

    /** Starts streaming (the render callback is called from now on). */
    bool start() {
        if (!m_claimed || failed()) return false;
        if (m_thread.joinable()) return true;
        if (!m_transport->setInterface(m_format.interface, m_format.alt)) return fail();
        // UAC1 endpoints take the rate after the alternate setting is active.
        if (m_device.version == 1) setRate();
        m_stop.store(false, std::memory_order_release);
        m_thread = std::thread([this] { run(); });
        return true;
    }

    /**
     * Stops streaming and lets the DAC idle (zero-bandwidth setting). The
     * transfers already queued still play (they hold the end of the fade-out,
     * already taken from the ring), so this returns after ~24 ms.
     */
    void pause() {
        if (!m_thread.joinable()) return;
        m_stop.store(true, std::memory_order_release);
        m_thread.join();
        if (!failed()) m_transport->setInterface(m_format.interface, 0);
    }

    /** Stops and hands every interface back to Android. */
    void close() {
        pause();
        if (m_claimed) {
            for (int iface : m_claimedInterfaces) m_transport->releaseInterface(iface);
            m_claimedInterfaces.clear();
            m_claimed = false;
        }
        m_render = nullptr;
        m_onError = nullptr;
    }

    const PlaybackFormat& format() const { return m_format; }
    uint32_t rate() const { return m_rate; }
    bool running() const { return m_thread.joinable(); }
    /** Frames per packet the DAC asks for, Q16.16 (nominal until feedback arrives). */
    uint32_t framesPerPacketQ16() const { return m_fppQ16.load(std::memory_order_relaxed); }
    uint64_t framesSent() const { return m_framesSent.load(std::memory_order_relaxed); }

private:
    /** The control interface and every playback streaming interface, once. */
    bool claimAll() {
        if (m_claimed) return true;
        std::vector<int> wanted{m_device.controlInterface};
        for (const auto& f : m_device.formats) {
            if (std::find(wanted.begin(), wanted.end(), f.interface) == wanted.end()) wanted.push_back(f.interface);
        }
        for (int iface : wanted) {
            if (!m_transport->claimInterface(iface)) {
                for (int done : m_claimedInterfaces) m_transport->releaseInterface(done);
                m_claimedInterfaces.clear();
                return false;
            }
            m_claimedInterfaces.push_back(iface);
        }
        m_claimed = true;
        return true;
    }

    // ── Rates and clocks ─────────────────────────────────────────────────────

    std::vector<uint32_t> formatRates(const PlaybackFormat& f) {
        std::vector<RateRange> ranges = f.rates;
        if (m_device.version == 2) ranges = clockRanges(f.clockId);
        std::vector<uint32_t> out;
        for (const auto& r : ranges) {
            if (r.min == r.max) {
                if (r.min > 0) out.push_back(r.min);
                continue;
            }
            for (uint32_t c : kCommonRates) if (r.contains(c)) out.push_back(c);
        }
        // A packet must hold the frames of one service interval.
        const double perPacket = 1.0 / packetsPerSecond(f);
        out.erase(std::remove_if(out.begin(), out.end(), [&](uint32_t r) {
            return (static_cast<double>(r) * perPacket + 1.0) * static_cast<double>(f.frameBytes()) > f.maxPacketBytes;
        }), out.end());
        return out;
    }

    std::vector<RateRange> clockRanges(uint8_t clockId) {
        const uint8_t source = m_device.clockSourceFor(clockId);
        if (source == 0) return {};
        for (const auto& c : m_rangeCache) if (c.first == source) return c.second;
        std::vector<RateRange> ranges;
        // usbfs passes class requests only for interfaces we hold (else snd-usb-audio does).
        if (!claimAll()) return ranges;
        // GET RANGE of CS_SAM_FREQ_CONTROL: the count first, then everything.
        uint8_t head[2] = {};
        const uint16_t index = static_cast<uint16_t>(source << 8 | m_device.controlInterface);
        if (m_transport->control(0xA1, 0x02, 0x0100, index, head, 2, kControlTimeoutMs) == 2) {
            const uint16_t n = detail::le16(head);
            std::vector<uint8_t> buf(2 + 12u * n);
            const int got = m_transport->control(0xA1, 0x02, 0x0100, index, buf.data(),
                                                 static_cast<uint16_t>(buf.size()), kControlTimeoutMs);
            for (uint16_t i = 0; got >= 2 && i < n && 2 + 12 * (i + 1) <= got; ++i) {
                const uint8_t* p = buf.data() + 2 + 12 * i;
                ranges.push_back({detail::le32(p), detail::le32(p + 4), detail::le32(p + 8)});
            }
        }
        m_rangeCache.emplace_back(source, ranges);
        return ranges;
    }

    bool setRate() {
        if (m_device.version == 2) {
            const uint8_t source = m_device.clockSourceFor(m_format.clockId);
            if (source == 0) return false;
            const ClockEntity* c = m_device.clock(source);
            if (c && (c->controls & 0x03) != 0x03) {
                // A fixed clock: only its own rate works.
                const auto ranges = clockRanges(m_format.clockId);
                return std::any_of(ranges.begin(), ranges.end(), [&](const RateRange& r) { return r.contains(m_rate); });
            }
            const uint16_t index = static_cast<uint16_t>(source << 8 | m_device.controlInterface);
            uint8_t data[4] = {static_cast<uint8_t>(m_rate), static_cast<uint8_t>(m_rate >> 8),
                               static_cast<uint8_t>(m_rate >> 16), static_cast<uint8_t>(m_rate >> 24)};
            if (m_transport->control(0x21, 0x01, 0x0100, index, data, 4, kControlTimeoutMs) < 0) return false;
            uint8_t check[4] = {};
            if (m_transport->control(0xA1, 0x01, 0x0100, index, check, 4, kControlTimeoutMs) == 4) {
                return detail::le32(check) == m_rate;
            }
            return true;   // some DACs do not answer GET CUR; trust the SET
        }
        if (!m_format.uac1RateControl) return true;
        uint8_t data[3] = {static_cast<uint8_t>(m_rate), static_cast<uint8_t>(m_rate >> 8), static_cast<uint8_t>(m_rate >> 16)};
        return m_transport->control(0x22, 0x01, 0x0100, m_format.endpoint, data, 3, kControlTimeoutMs) >= 0;
    }

    // ── Packet schedule ──────────────────────────────────────────────────────

    double packetsPerSecond(const PlaybackFormat& f) const {
        const double base = m_speed == UsbSpeed::Full || m_speed == UsbSpeed::Low ? 1000.0 : 8000.0;
        return base / static_cast<double>(1u << std::min<uint8_t>(f.interval - 1, 15));
    }

    void prepareSchedule() {
        m_packetsPerSecond = packetsPerSecond(m_format);
        m_nominalQ16 = static_cast<uint32_t>(static_cast<double>(m_rate) / m_packetsPerSecond * 65536.0 + 0.5);
        m_fppQ16.store(m_nominalQ16, std::memory_order_relaxed);
        m_accumulator = 0;
        m_feedbackShift = 0;
        m_feedbackLocked = false;
        m_packetsPerTransfer = std::max<size_t>(1, static_cast<size_t>(m_packetsPerSecond * kTransferSeconds + 0.5));
        m_maxFramesPerPacket = m_format.maxPacketBytes / m_format.frameBytes();
    }

    /** Frames for the next packet: the fractional rate accumulated in Q16.16. */
    size_t nextPacketFrames() {
        m_accumulator += m_fppQ16.load(std::memory_order_relaxed);
        size_t n = m_accumulator >> 16;
        m_accumulator &= 0xFFFF;
        if (n > m_maxFramesPerPacket) n = m_maxFramesPerPacket;
        return n;
    }

    /**
     * A feedback value: Q10.14 frames per frame at full speed, Q16.16 per
     * microframe at high speed, per packet interval. Devices get the format
     * wrong often enough that the scale is found against the nominal rate the
     * first time (as Linux does) and kept.
     */
    void onFeedback(const uint8_t* p, uint32_t bytes) {
        if (bytes < 3) return;
        uint64_t value = bytes >= 4 ? detail::le32(p) : detail::le24(p);
        if (bytes == 3) value <<= 2;   // Q10.14 -> Q16.16
        if (value == 0) return;
        // Per (micro)frame -> per packet.
        const double framesPerPacketInterval = static_cast<double>(1u << std::min<uint8_t>(m_format.interval - 1, 15));
        double q16 = static_cast<double>(value) * framesPerPacketInterval;
        if (!m_feedbackLocked) {
            int shift = 0;
            double f = q16;
            while (f < m_nominalQ16 * 0.5 && shift < 8) { f *= 2.0; ++shift; }
            while (f > m_nominalQ16 * 1.5 && shift > -8) { f /= 2.0; --shift; }
            if (f < m_nominalQ16 * 0.5 || f > m_nominalQ16 * 1.5) return;   // nonsense: keep nominal
            m_feedbackShift = shift;
            m_feedbackLocked = true;
        }
        q16 = m_feedbackShift >= 0 ? q16 * static_cast<double>(1 << m_feedbackShift)
                                   : q16 / static_cast<double>(1 << -m_feedbackShift);
        // Accept only a sane deviation (±1 %) from the nominal rate.
        if (q16 < m_nominalQ16 * 0.99 || q16 > m_nominalQ16 * 1.01) return;
        m_fppQ16.store(static_cast<uint32_t>(q16 + 0.5), std::memory_order_relaxed);
    }

    void fill(IsoTransfer* t) {
        const size_t frameBytes = m_format.frameBytes();
        size_t frames = 0;
        for (size_t i = 0; i < t->packets(); ++i) {
            const size_t n = nextPacketFrames();
            t->length[i] = static_cast<uint32_t>(n * frameBytes);
            frames += n;
        }
        if (m_render && frames > 0) m_render(t->buffer.data(), frames);
        m_framesSent.fetch_add(frames, std::memory_order_relaxed);
    }

    // ── Streaming thread ─────────────────────────────────────────────────────

    void run() {
#if defined(__ANDROID__) || defined(__linux__)
        // Android's THREAD_PRIORITY_URGENT_AUDIO.
        setpriority(PRIO_PROCESS, static_cast<id_t>(syscall(SYS_gettid)), -19);
#endif
        std::vector<IsoTransfer*> data, feedback;
        const size_t dataBytes = m_packetsPerTransfer * (m_maxFramesPerPacket * m_format.frameBytes());
        for (int i = 0; i < kTransfersInFlight; ++i) data.push_back(m_transport->allocIso(m_format.endpoint, m_packetsPerTransfer, dataBytes));
        if (m_format.feedbackEndpoint != 0) {
            for (int i = 0; i < 2; ++i) {
                auto* t = m_transport->allocIso(m_format.feedbackEndpoint, 1, std::max<size_t>(4, m_format.feedbackMaxPacket));
                t->length[0] = static_cast<uint32_t>(std::max<size_t>(4, m_format.feedbackMaxPacket));
                feedback.push_back(t);
            }
        }
        size_t inFlight = 0;
        bool ok = true;
        for (auto* t : feedback) {
            if (m_transport->submit(t) == 0) ++inFlight;
        }
        for (auto* t : data) {
            fill(t);
            if (m_transport->submit(t) != 0) { ok = false; break; }
            ++inFlight;
        }

        bool draining = !ok;
        while (inFlight > 0) {
            if (!draining && m_stop.load(std::memory_order_acquire)) {
                // Let the queued audio play out; only the feedback reads are cancelled.
                draining = true;
                for (auto* t : feedback) m_transport->discard(t);
            }
            int error = 0;
            IsoTransfer* done = m_transport->reap(100, &error);
            if (!done) {
                if (error == -ENODEV || error == -ESHUTDOWN || error == -EPIPE) { ok = false; break; }
                continue;
            }
            --inFlight;
            if (done->status == -ENODEV || done->status == -ESHUTDOWN || done->status == -EPROTO) {
                ok = false;
                if (!draining) {
                    draining = true;
                    for (auto* t : data) m_transport->discard(t);
                    for (auto* t : feedback) m_transport->discard(t);
                }
                continue;
            }
            if (draining) continue;
            if (done->endpoint == m_format.feedbackEndpoint && m_format.feedbackEndpoint != 0) {
                if (done->status == 0) onFeedback(done->buffer.data(), done->actual[0]);
            } else {
                fill(done);
            }
            if (m_transport->submit(done) == 0) {
                ++inFlight;
            } else {
                ok = false;
                draining = true;
                for (auto* t : data) m_transport->discard(t);
                for (auto* t : feedback) m_transport->discard(t);
            }
        }
        for (auto* t : data) m_transport->freeIso(t);
        for (auto* t : feedback) m_transport->freeIso(t);
        if (!ok) {
            m_failed.store(true, std::memory_order_release);
            if (m_onError) m_onError();
        }
    }

    bool fail() {
        m_failed.store(true, std::memory_order_release);
        return false;
    }

    std::unique_ptr<UsbTransport> m_transport;
    UacDevice m_device;
    UsbSpeed m_speed = UsbSpeed::High;
    std::vector<std::pair<uint8_t, std::vector<RateRange>>> m_rangeCache;

    PlaybackFormat m_format;
    uint32_t m_rate = 0;
    bool m_claimed = false;
    std::vector<int> m_claimedInterfaces;
    Render m_render;
    ErrorHandler m_onError;

    double m_packetsPerSecond = 8000.0;
    uint32_t m_nominalQ16 = 0;
    size_t m_packetsPerTransfer = 16;
    size_t m_maxFramesPerPacket = 0;
    // Streaming thread state.
    uint64_t m_accumulator = 0;
    int m_feedbackShift = 0;
    bool m_feedbackLocked = false;

    std::atomic<uint32_t> m_fppQ16{0};
    std::atomic<uint64_t> m_framesSent{0};
    std::atomic<bool> m_failed{false};
    std::atomic<bool> m_stop{false};
    std::thread m_thread;
};

}  // namespace usb
}  // namespace audio_engine
