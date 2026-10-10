#pragma once

#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>

namespace audio_engine {
namespace hw {

/** Sample format of the device stream. Ids are shared with Kotlin (BitPerfectOutput). */
enum class SampleEncoding : int {
    Float = 0, I16 = 1, I24 = 2, I32 = 3,
    I24in32 = 4,   // 24-bit samples left-justified in 32-bit slots (USB DACs); native only
};

inline size_t bytesPerSample(SampleEncoding e) {
    switch (e) {
        case SampleEncoding::I16: return 2;
        case SampleEncoding::I24: return 3;
        default: return 4;
    }
}

inline int bitsPerSample(SampleEncoding e) {
    switch (e) {
        case SampleEncoding::I16: return 16;
        case SampleEncoding::I24:
        case SampleEncoding::I24in32: return 24;
        default: return 32;
    }
}

/**
 * Converts the engine's doubles to the device format.
 *
 * A sample that the target format represents exactly is written unchanged, so
 * an untouched 16-bit source reaches a 16/24/32-bit DAC bit for bit and digital
 * silence stays silent. Only samples that would be rounded (after volume, EQ,
 * ReplayGain, ...) get TPDF dither of ±1 LSB. encode() returns how many samples
 * were not exact: zero means the output carries the source's own values.
 */
class PcmEncoder {
public:
    size_t encode(const double* in, size_t samples, void* out, SampleEncoding encoding) {
        switch (encoding) {
            case SampleEncoding::I16: return encodeInt<int16_t, 2>(in, samples, out, 32768.0);
            case SampleEncoding::I24: return encodeInt<int32_t, 3>(in, samples, out, 8388608.0);
            case SampleEncoding::I32: return encodeInt<int32_t, 4>(in, samples, out, 2147483648.0);
            case SampleEncoding::I24in32: return encodeInt<int32_t, 4, 8>(in, samples, out, 8388608.0);
            default: break;
        }
        float* o = static_cast<float*>(out);
        size_t inexact = 0;
        for (size_t i = 0; i < samples; ++i) {
            o[i] = static_cast<float>(in[i]);
            inexact += static_cast<double>(o[i]) != in[i];
        }
        return inexact;
    }

private:
    template <typename T, size_t Bytes, int Shift = 0>
    size_t encodeInt(const double* in, size_t samples, void* out, double scale) {
        const double lo = -scale;
        const double hi = scale - 1.0;
        uint8_t* o = static_cast<uint8_t*>(out);
        size_t inexact = 0;
        for (size_t i = 0; i < samples; ++i) {
            double v = in[i] * scale;
            if (v != std::nearbyint(v)) {
                ++inexact;
                v = std::nearbyint(v + tpdf());
            }
            v = v < lo ? lo : (v > hi ? hi : v);
            const auto s = static_cast<int32_t>(static_cast<uint32_t>(static_cast<int32_t>(v)) << Shift);
            if (Bytes == 3) {
                o[i * 3] = static_cast<uint8_t>(s);
                o[i * 3 + 1] = static_cast<uint8_t>(s >> 8);
                o[i * 3 + 2] = static_cast<uint8_t>(s >> 16);
            } else {
                const T t = static_cast<T>(s);
                std::memcpy(o + i * Bytes, &t, Bytes);
            }
        }
        return inexact;
    }

    /** Triangular noise in (-1, 1) LSB: the sum of two uniform ±0.5 LSB values. */
    double tpdf() { return uniform() + uniform() - 1.0; }

    double uniform() {
        m_state ^= m_state << 13;
        m_state ^= m_state >> 17;
        m_state ^= m_state << 5;
        return static_cast<double>(m_state) * (1.0 / 4294967296.0);
    }

    uint32_t m_state = 0x9e3779b9u;
};

} // namespace hw
} // namespace audio_engine
