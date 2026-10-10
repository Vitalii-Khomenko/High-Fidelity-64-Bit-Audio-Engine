#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <numeric>
#include <vector>

namespace audio_engine {
namespace dsp {

/**
 * Streaming sample-rate converter for an exact rational ratio (out/in = L/M),
 * in double precision, for the shared output path: the engine converts to the
 * mixer's own rate itself so Android's mixer does not resample.
 *
 * Polyphase FIR from one Kaiser-windowed sinc designed at L x the input rate:
 * passband flat to 45.35 % of the lower rate (20 kHz at 44.1 kHz), stopband
 * from half the lower rate, 140 dB down. Every phase is exact (no coefficient
 * interpolation), so the only error is the filter's own.
 *
 * Threads: configure() on a control thread while no stream runs; process()
 * and reset() on the audio callback only (no allocation, no locks).
 */
class PolyphaseResampler {
public:
    static constexpr double kStopbandDb = 140.0;
    static constexpr double kPassbandFraction = 0.4535;   // of the lower rate
    static constexpr size_t kMaxTableSize = 1u << 21;     // coefficients (16 MiB)

    struct Result {
        size_t produced;  // output frames written
        bool starved;     // the source had fewer frames than needed
    };

    /** Prepares in -> out for up to maxOutFrames per process() call. False if the ratio is impractical. */
    bool configure(uint32_t inRate, uint32_t outRate, size_t channels, size_t maxOutFrames) {
        m_active = false;
        if (inRate == 0 || outRate == 0 || channels == 0 || inRate == outRate) return false;
        const uint64_t g = std::gcd(static_cast<uint64_t>(inRate), static_cast<uint64_t>(outRate));
        const uint64_t L = outRate / g;
        const uint64_t M = inRate / g;

        const double lower = std::min(inRate, outRate);
        const double passEdge = kPassbandFraction * lower;
        const double stopEdge = 0.5 * lower;
        const double upRate = static_cast<double>(L) * inRate;
        const double transition = 2.0 * M_PI * (stopEdge - passEdge) / upRate;  // rad/sample at L x in
        const size_t prototype = static_cast<size_t>(std::ceil((kStopbandDb - 7.95) / (2.285 * transition))) + 1;
        const size_t taps = (prototype + L - 1) / L;
        if (L * taps > kMaxTableSize) return false;

        m_L = static_cast<uint32_t>(L);
        m_M = static_cast<uint32_t>(M);
        m_taps = taps;
        m_channels = channels;
        design(passEdge, stopEdge, upRate);

        m_history.assign(2 * m_taps * m_channels, 0.0);
        const size_t maxIn = static_cast<size_t>((static_cast<uint64_t>(maxOutFrames) * m_M) / m_L) + 2;
        m_input.assign(maxIn * m_channels, 0.0);
        m_maxOut = maxOutFrames;
        m_active = true;
        reset();
        return true;
    }

    bool active() const { return m_active; }
    size_t taps() const { return m_taps; }
    /** Group delay in input frames. */
    double latencyFrames() const { return (static_cast<double>(m_taps * m_L) - 1.0) / (2.0 * m_L); }

    /** Forgets the history (after a flush): the next output starts from silence. */
    void reset() {
        std::fill(m_history.begin(), m_history.end(), 0.0);
        m_write = 0;
        m_phase = 0;
        m_need = 1;
        m_zeros = m_taps;  // all history is silence
    }

    /**
     * Writes up to `frames` (<= maxOutFrames) interleaved output frames.
     * pull(dst, n) must copy up to n interleaved input frames and return how
     * many it copied; exactly the frames the outputs use are requested. When
     * the source runs dry the filter is flushed with silence, so the last
     * samples still come out; produced < frames only once it holds silence.
     */
    template <typename Pull>
    Result process(double* out, size_t frames, Pull&& pull) {
        frames = std::min(frames, m_maxOut);
        if (frames == 0) return {0, false};
        const uint64_t need = m_need + (static_cast<uint64_t>(m_phase) + (frames - 1) * static_cast<uint64_t>(m_M)) / m_L;
        const size_t got = need > 0 ? pull(m_input.data(), static_cast<size_t>(need)) : 0;
        const bool starved = got < need;
        size_t used = 0;
        size_t produced = 0;
        for (; produced < frames; ++produced) {
            for (; m_need > 0; --m_need) {
                if (used < got) {
                    push(m_input.data() + used * m_channels);
                    ++used;
                    m_zeros = 0;
                } else if (m_zeros < m_taps) {
                    pushSilence();
                    ++m_zeros;
                } else {
                    return {produced, starved};
                }
            }
            filter(out + produced * m_channels);
            m_phase += m_M;
            m_need = m_phase / m_L;
            m_phase %= m_L;
        }
        return {produced, starved};
    }

private:
    static double besselI0(double x) {
        double sum = 1.0, term = 1.0;
        const double q = x * x / 4.0;
        for (int k = 1; k < 200 && term > sum * 1e-17; ++k) {
            term *= q / (static_cast<double>(k) * k);
            sum += term;
        }
        return sum;
    }

    void design(double passEdge, double stopEdge, double upRate) {
        const size_t n = m_taps * m_L;
        const double cutoff = (passEdge + stopEdge) / 2.0 / upRate;  // cycles per sample at L x in
        const double beta = 0.1102 * (kStopbandDb - 8.7);
        const double i0beta = besselI0(beta);
        const double centre = (static_cast<double>(n) - 1.0) / 2.0;
        std::vector<double> h(n);
        double sum = 0.0;
        for (size_t j = 0; j < n; ++j) {
            const double t = static_cast<double>(j) - centre;
            const double x = 2.0 * M_PI * cutoff * t;
            const double sinc = t == 0.0 ? 2.0 * cutoff : std::sin(x) / (M_PI * t);
            const double r = t / (centre + 0.5);
            const double window = besselI0(beta * std::sqrt(std::max(0.0, 1.0 - r * r))) / i0beta;
            h[j] = sinc * window;
            sum += h[j];
        }
        // Unity gain at DC after the zero-stuffing by L.
        const double scale = static_cast<double>(m_L) / sum;
        m_table.assign(n, 0.0);
        for (size_t p = 0; p < m_L; ++p) {
            for (size_t k = 0; k < m_taps; ++k) m_table[p * m_taps + k] = h[k * m_L + p] * scale;
        }
    }

    // The history keeps every frame twice, so taps newest..oldest are contiguous.
    void push(const double* frame) {
        m_write = m_write == 0 ? m_taps - 1 : m_write - 1;
        double* a = m_history.data() + m_write * m_channels;
        double* b = a + m_taps * m_channels;
        for (size_t c = 0; c < m_channels; ++c) a[c] = b[c] = frame[c];
    }

    void pushSilence() {
        m_write = m_write == 0 ? m_taps - 1 : m_write - 1;
        double* a = m_history.data() + m_write * m_channels;
        double* b = a + m_taps * m_channels;
        for (size_t c = 0; c < m_channels; ++c) a[c] = b[c] = 0.0;
    }

    void filter(double* out) const {
        const double* coef = m_table.data() + static_cast<size_t>(m_phase) * m_taps;
        const double* x = m_history.data() + m_write * m_channels;
        if (m_channels == 2) {
            double l0 = 0.0, r0 = 0.0, l1 = 0.0, r1 = 0.0;
            size_t k = 0;
            for (; k + 1 < m_taps; k += 2) {
                l0 += coef[k] * x[2 * k];
                r0 += coef[k] * x[2 * k + 1];
                l1 += coef[k + 1] * x[2 * k + 2];
                r1 += coef[k + 1] * x[2 * k + 3];
            }
            if (k < m_taps) {
                l0 += coef[k] * x[2 * k];
                r0 += coef[k] * x[2 * k + 1];
            }
            out[0] = l0 + l1;
            out[1] = r0 + r1;
            return;
        }
        for (size_t c = 0; c < m_channels; ++c) {
            double acc = 0.0;
            for (size_t k = 0; k < m_taps; ++k) acc += coef[k] * x[k * m_channels + c];
            out[c] = acc;
        }
    }

    bool m_active = false;
    uint32_t m_L = 1, m_M = 1;
    size_t m_taps = 1;
    size_t m_channels = 1;
    size_t m_maxOut = 0;
    std::vector<double> m_table;    // [phase][tap]
    std::vector<double> m_history;  // 2 x taps frames, interleaved
    std::vector<double> m_input;    // staging for one process() call
    size_t m_write = 0;
    uint32_t m_phase = 0;
    uint64_t m_need = 1;            // input frames to take before the next output
    size_t m_zeros = 0;             // trailing silent frames in the history
};

} // namespace dsp
} // namespace audio_engine
