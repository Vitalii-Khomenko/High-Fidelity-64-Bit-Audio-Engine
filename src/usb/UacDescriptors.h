#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace audio_engine {
namespace usb {

/**
 * USB Audio Class descriptors (UAC1 and UAC2) of one device, from the raw
 * configuration descriptors Android hands over (UsbDeviceConnection
 * .getRawDescriptors()). Only what playback needs: the AudioControl
 * interface, clock entities (UAC2), and every AudioStreaming alternate setting
 * with an isochronous OUT endpoint and PCM type I format.
 */
enum class SyncType : uint8_t { None = 0, Async = 1, Adaptive = 2, Sync = 3 };

struct RateRange {
    uint32_t min = 0, max = 0, res = 0;
    bool contains(uint32_t rate) const {
        if (rate < min || rate > max) return false;
        return res == 0 || (rate - min) % res == 0;
    }
};

/** One playable alternate setting of an AudioStreaming interface. */
struct PlaybackFormat {
    uint8_t interface = 0;
    uint8_t alt = 0;
    uint8_t channels = 0;
    uint8_t subslotBytes = 0;   // bytes per sample in the packet
    uint8_t bits = 0;           // valid bits (left-justified in the subslot)
    uint8_t terminalLink = 0;
    // Data endpoint
    uint8_t endpoint = 0;
    uint16_t maxPacketBytes = 0;   // including high-bandwidth transactions
    uint8_t interval = 1;          // bInterval
    SyncType sync = SyncType::None;
    bool implicitFeedback = false; // async without a feedback endpoint of its own
    // Explicit feedback endpoint (0 if none)
    uint8_t feedbackEndpoint = 0;
    uint16_t feedbackMaxPacket = 0;
    uint8_t feedbackInterval = 1;
    // Rates: UAC1 lists them here (discrete or one continuous range); UAC2 asks the clock.
    std::vector<RateRange> rates;
    bool uac1RateControl = false;  // UAC1: the endpoint accepts SET_CUR sampling frequency
    uint8_t clockId = 0;           // UAC2: clock entity feeding this format's terminal

    size_t frameBytes() const { return static_cast<size_t>(channels) * subslotBytes; }
};

struct ClockEntity {
    enum class Kind : uint8_t { Source, Selector, Multiplier } kind = Kind::Source;
    uint8_t id = 0;
    std::vector<uint8_t> inputs;   // selector pins / multiplier source
    uint8_t controls = 0;          // source bmControls (bits 0-1: frequency control)
};

struct UacDevice {
    int version = 0;               // 1 or 2; 0 when no usable audio function
    uint8_t controlInterface = 0;
    std::vector<ClockEntity> clocks;
    std::vector<PlaybackFormat> formats;

    const ClockEntity* clock(uint8_t id) const {
        for (const auto& c : clocks) if (c.id == id) return &c;
        return nullptr;
    }

    /** Follows selectors (first pin) and multipliers to the clock source. 0 if none. */
    uint8_t clockSourceFor(uint8_t id) const {
        for (int depth = 0; depth < 8 && id != 0; ++depth) {
            const ClockEntity* c = clock(id);
            if (!c) return 0;
            if (c->kind == ClockEntity::Kind::Source) return c->id;
            id = c->inputs.empty() ? 0 : c->inputs.front();
        }
        return 0;
    }
};

namespace detail {
inline uint16_t le16(const uint8_t* p) { return static_cast<uint16_t>(p[0] | p[1] << 8); }
inline uint32_t le24(const uint8_t* p) { return p[0] | p[1] << 8 | static_cast<uint32_t>(p[2]) << 16; }
inline uint32_t le32(const uint8_t* p) { return le24(p) | static_cast<uint32_t>(p[3]) << 24; }
}  // namespace detail

constexpr uint8_t kClassAudio = 1;
constexpr uint8_t kSubclassControl = 1;
constexpr uint8_t kSubclassStreaming = 2;
constexpr uint8_t kProtocolUac2 = 0x20;

/** Parses a whole configuration (several may follow each other; the first audio one wins). */
inline UacDevice parseUac(const uint8_t* data, size_t size) {
    using namespace detail;
    UacDevice dev;
    struct Terminal { uint8_t id, clock; };
    std::vector<Terminal> inputTerminals;

    // Current interface while walking.
    int ifNumber = -1, ifAlt = 0, ifSubclass = 0, ifProtocol = 0;
    bool isAudio = false;
    PlaybackFormat current;
    bool haveGeneral = false, haveFormat = false, pcm = false;
    std::vector<PlaybackFormat> pending;   // data endpoint seen, maybe more endpoints follow
    uint8_t uac1SyncAddress = 0;

    auto finishInterface = [&]() {
        for (auto& f : pending) {
            if (haveGeneral && haveFormat && pcm && f.channels > 0 && f.subslotBytes >= 2 && f.subslotBytes <= 4) {
                dev.formats.push_back(f);
            }
        }
        pending.clear();
        haveGeneral = haveFormat = pcm = false;
        uac1SyncAddress = 0;
        current = PlaybackFormat{};
    };

    for (size_t pos = 0; pos + 2 <= size;) {
        const uint8_t len = data[pos];
        if (len < 2 || pos + len > size) break;
        const uint8_t* d = data + pos;
        const uint8_t type = d[1];

        if (type == 0x04 && len >= 9) {  // INTERFACE
            finishInterface();
            ifNumber = d[2];
            ifAlt = d[3];
            isAudio = d[5] == kClassAudio;
            ifSubclass = d[6];
            ifProtocol = d[7];
            if (isAudio && ifSubclass == kSubclassControl && dev.version == 0) {
                dev.version = ifProtocol == kProtocolUac2 ? 2 : (ifProtocol == 0 ? 1 : 0);
                dev.controlInterface = static_cast<uint8_t>(ifNumber);
            }
            current.interface = static_cast<uint8_t>(ifNumber);
            current.alt = static_cast<uint8_t>(ifAlt);
        } else if (type == 0x24 && isAudio && len >= 3) {  // CS_INTERFACE
            const uint8_t sub = d[2];
            if (ifSubclass == kSubclassControl) {
                if (dev.version == 2) {
                    if (sub == 0x02 && len >= 17) inputTerminals.push_back({d[3], d[7]});           // INPUT_TERMINAL
                    else if (sub == 0x0A && len >= 8) {                                              // CLOCK_SOURCE
                        ClockEntity c; c.kind = ClockEntity::Kind::Source; c.id = d[3]; c.controls = d[5];
                        dev.clocks.push_back(c);
                    } else if (sub == 0x0B && len >= 5) {                                            // CLOCK_SELECTOR
                        ClockEntity c; c.kind = ClockEntity::Kind::Selector; c.id = d[3];
                        for (uint8_t i = 0; i < d[4] && 5u + i < len; ++i) c.inputs.push_back(d[5 + i]);
                        dev.clocks.push_back(c);
                    } else if (sub == 0x0C && len >= 5) {                                            // CLOCK_MULTIPLIER
                        ClockEntity c; c.kind = ClockEntity::Kind::Multiplier; c.id = d[3]; c.inputs.push_back(d[4]);
                        dev.clocks.push_back(c);
                    }
                }
            } else if (ifSubclass == kSubclassStreaming && ifAlt != 0) {
                if (dev.version == 2) {
                    if (sub == 0x01 && len >= 16) {          // AS_GENERAL
                        haveGeneral = true;
                        current.terminalLink = d[3];
                        pcm = d[5] == 1 && (le32(d + 6) & 1u);   // type I, PCM
                        current.channels = d[10];
                    } else if (sub == 0x02 && len >= 6 && d[3] == 1) {   // FORMAT_TYPE I
                        haveFormat = true;
                        current.subslotBytes = d[4];
                        current.bits = d[5];
                    }
                } else if (dev.version == 1) {
                    if (sub == 0x01 && len >= 7) {           // AS_GENERAL
                        haveGeneral = true;
                        current.terminalLink = d[3];
                        pcm = le16(d + 5) == 0x0001;         // PCM (not IEEE float, not DSD)
                    } else if (sub == 0x02 && len >= 8 && d[3] == 1) {   // FORMAT_TYPE I
                        haveFormat = true;
                        current.channels = d[4];
                        current.subslotBytes = d[5];
                        current.bits = d[6];
                        const uint8_t n = d[7];
                        if (n == 0 && len >= 14) {
                            current.rates.push_back({le24(d + 8), le24(d + 11), 1});
                        } else {
                            for (uint8_t i = 0; i < n && 8u + 3u * i + 3u <= len; ++i) {
                                const uint32_t r = le24(d + 8 + 3 * i);
                                current.rates.push_back({r, r, 0});
                            }
                        }
                    }
                }
            }
        } else if (type == 0x05 && isAudio && ifSubclass == kSubclassStreaming && ifAlt != 0 && len >= 7) {  // ENDPOINT
            const uint8_t address = d[2];
            const uint8_t attributes = d[3];
            const uint16_t wMax = le16(d + 4);
            const uint16_t packet = static_cast<uint16_t>((wMax & 0x7FF) * (1 + ((wMax >> 11) & 3)));
            const bool isoch = (attributes & 3) == 1;
            const uint8_t usage = (attributes >> 4) & 3;
            if (isoch && !(address & 0x80) && usage != 1) {        // OUT data
                PlaybackFormat f = current;
                f.endpoint = address;
                f.maxPacketBytes = packet;
                f.interval = std::max<uint8_t>(1, d[6]);
                f.sync = static_cast<SyncType>((attributes >> 2) & 3);
                if (dev.version == 1 && len >= 9) uac1SyncAddress = d[8];
                pending.push_back(f);
            } else if (isoch && (address & 0x80) && !pending.empty() &&
                       (usage == 1 || (dev.version == 1 && address == uac1SyncAddress))) {  // feedback IN
                for (auto& f : pending) {
                    f.feedbackEndpoint = address;
                    f.feedbackMaxPacket = packet;
                    f.feedbackInterval = std::max<uint8_t>(1, d[6]);
                }
            }
        } else if (type == 0x25 && isAudio && ifSubclass == kSubclassStreaming && dev.version == 1 && len >= 4 &&
                   d[2] == 0x01 && !pending.empty()) {   // UAC1 CS_ENDPOINT EP_GENERAL
            pending.back().uac1RateControl = (d[3] & 0x01) != 0;
        }
        pos += len;
    }
    finishInterface();

    for (auto& f : dev.formats) {
        if (f.sync == SyncType::Async && f.feedbackEndpoint == 0) f.implicitFeedback = true;
        if (dev.version == 2) {
            for (const auto& t : inputTerminals) if (t.id == f.terminalLink) f.clockId = t.clock;
        }
    }
    if (dev.version == 0) dev.formats.clear();
    return dev;
}

}  // namespace usb
}  // namespace audio_engine
