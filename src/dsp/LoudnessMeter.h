#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <vector>

#include "TruePeak.h"

namespace audio_engine {
namespace dsp {

/**
 * Integrated loudness (EBU R128 / ITU-R BS.1770-4) and true peak of a whole
 * programme. K-weighting is the BS.1770 pre-filter and RLB high-pass,
 * computed for the actual sample rate (as libebur128 does); 400 ms blocks
 * with 75 % overlap; absolute gate at -70 LUFS and relative gate 10 LU below.
 * Channel weights for the WAVE order: L, R, C 1.0; LFE ignored; surrounds 1.41.
 */
class LoudnessMeter {
public:
    void prepare(uint32_t sampleRate, size_t channels) {
        m_rate = sampleRate;
        m_channels = std::max<size_t>(1, channels);
        const double rate = sampleRate;
        // Stage 1: high shelf (head effect).
        double f0 = 1681.974450955533, g = 3.999843853973347, q = 0.7071752369554196;
        double k = std::tan(M_PI * f0 / rate);
        const double vh = std::pow(10.0, g / 20.0);
        const double vb = std::pow(vh, 0.4996667741545416);
        double a0 = 1.0 + k / q + k * k;
        m_pb[0] = (vh + vb * k / q + k * k) / a0;
        m_pb[1] = 2.0 * (k * k - vh) / a0;
        m_pb[2] = (vh - vb * k / q + k * k) / a0;
        m_pa[1] = 2.0 * (k * k - 1.0) / a0;
        m_pa[2] = (1.0 - k / q + k * k) / a0;
        // Stage 2: RLB high-pass.
        f0 = 38.13547087602444;
        q = 0.5003270373238773;
        k = std::tan(M_PI * f0 / rate);
        a0 = 1.0 + k / q + k * k;
        m_ra[1] = 2.0 * (k * k - 1.0) / a0;
        m_ra[2] = (1.0 - k / q + k * k) / a0;

        m_weights.assign(m_channels, 1.0);
        if (m_channels >= 4) m_weights[3] = m_channels >= 6 ? 0.0 : 1.41;   // LFE in 5.1 / 7.1; BL in quad
        for (size_t c = 4; c < m_channels; ++c) m_weights[c] = 1.41;
        if (m_channels == 4) m_weights[2] = 1.41, m_weights[3] = 1.41;     // quad: L R BL BR

        m_state.assign(m_channels, {});
        m_peaks.assign(m_channels, TruePeakDetector());
        m_hop = std::max<size_t>(1, sampleRate / 10);   // 100 ms
        m_hopEnergy.clear();
        m_accum = 0.0;
        m_inHop = 0;
        m_blocks.clear();
        m_truePeak = 0.0;
    }

    /** Interleaved frames. */
    void add(const double* data, size_t frames) {
        for (size_t f = 0; f < frames; ++f) {
            double sum = 0.0;
            for (size_t c = 0; c < m_channels; ++c) {
                const double x = data[f * m_channels + c];
                m_truePeak = std::max(m_truePeak, m_peaks[c].push(x));
                if (m_weights[c] == 0.0) continue;
                Biquads& s = m_state[c];
                // Direct form II transposed, both stages.
                const double y1 = m_pb[0] * x + s.z1;
                s.z1 = m_pb[1] * x - m_pa[1] * y1 + s.z2;
                s.z2 = m_pb[2] * x - m_pa[2] * y1;
                const double y2 = y1 + s.w1;            // b = {1, -2, 1}
                s.w1 = -2.0 * y1 - m_ra[1] * y2 + s.w2;
                s.w2 = y1 - m_ra[2] * y2;
                sum += m_weights[c] * y2 * y2;
            }
            m_accum += sum;
            if (++m_inHop == m_hop) {
                m_hopEnergy.push_back(m_accum / static_cast<double>(m_hop));
                m_accum = 0.0;
                m_inHop = 0;
                if (m_hopEnergy.size() >= 4) {
                    const size_t n = m_hopEnergy.size();
                    m_blocks.push_back((m_hopEnergy[n - 1] + m_hopEnergy[n - 2] + m_hopEnergy[n - 3] + m_hopEnergy[n - 4]) / 4.0);
                    if (m_hopEnergy.size() > 8) m_hopEnergy.erase(m_hopEnergy.begin(), m_hopEnergy.end() - 4);
                }
            }
        }
    }

    /** Integrated loudness in LUFS; -inf for silence or programmes shorter than 400 ms. */
    double integratedLufs() const {
        const double absGate = energyOf(-70.0);
        double sum = 0.0;
        size_t count = 0;
        for (double e : m_blocks) if (e > absGate) { sum += e; ++count; }
        if (count == 0) return -INFINITY;
        const double relGate = sum / static_cast<double>(count) * std::pow(10.0, -10.0 / 10.0);
        double gated = 0.0;
        size_t n = 0;
        for (double e : m_blocks) if (e > absGate && e > relGate) { gated += e; ++n; }
        return n ? loudnessOf(gated / static_cast<double>(n)) : -INFINITY;
    }

    /** True peak in dBTP (4x oversampled). */
    double truePeakDb() const { return m_truePeak > 0.0 ? 20.0 * std::log10(m_truePeak) : -INFINITY; }
    double truePeak() const { return m_truePeak; }

    static double loudnessOf(double energy) { return -0.691 + 10.0 * std::log10(energy); }
    static double energyOf(double lufs) { return std::pow(10.0, (lufs + 0.691) / 10.0); }

private:
    struct Biquads { double z1 = 0, z2 = 0, w1 = 0, w2 = 0; };

    uint32_t m_rate = 48000;
    size_t m_channels = 2;
    double m_pb[3] = {1, 0, 0}, m_pa[3] = {1, 0, 0}, m_ra[3] = {1, 0, 0};
    std::vector<double> m_weights;
    std::vector<Biquads> m_state;
    std::vector<TruePeakDetector> m_peaks;
    size_t m_hop = 4800;
    std::vector<double> m_hopEnergy;
    double m_accum = 0.0;
    size_t m_inHop = 0;
    std::vector<double> m_blocks;
    double m_truePeak = 0.0;
};

} // namespace dsp
} // namespace audio_engine
