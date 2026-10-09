#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <vector>

#include "FirDesign.h"

namespace audio_engine {
namespace dsp {

/**
 * True-peak (inter-sample peak) estimate per ITU-R BS.1770 Annex 2: the
 * signal is interpolated 4x with a 48-tap linear-phase filter and the largest
 * magnitude of the sample and its three interpolated neighbours is taken.
 * One instance per channel; feed it samples in order.
 */
class TruePeakDetector {
public:
    static constexpr int kFactor = 4;
    static constexpr int kTapsPerPhase = 12;
    /** Samples between input and the peak value that belongs to it. */
    static constexpr int kDelay = kTapsPerPhase / 2;

    TruePeakDetector() {
        static const std::vector<double> h = designKaiserLowpass(kFactor * kTapsPerPhase, 0.5 / kFactor * 0.92, 90.0);
        for (int p = 0; p < kFactor; ++p) {
            for (int k = 0; k < kTapsPerPhase; ++k) m_phase[p][k] = h[k * kFactor + p] * kFactor;
        }
        reset();
    }

    void reset() {
        m_history.fill(0.0);
        m_pos = 0;
    }

    /** Pushes one sample; returns the true peak of the sample kDelay positions back. */
    double push(double x) {
        m_history[m_pos] = x;
        m_history[m_pos + kTapsPerPhase] = x;   // mirrored, so the window is contiguous
        m_pos = (m_pos + 1) % kTapsPerPhase;
        const double* w = m_history.data() + m_pos;   // oldest first
        double peak = std::fabs(w[kTapsPerPhase - 1 - kDelay]);
        for (int p = 1; p < kFactor; ++p) {
            double acc = 0.0;
            for (int k = 0; k < kTapsPerPhase; ++k) acc += m_phase[p][k] * w[kTapsPerPhase - 1 - k];
            peak = std::max(peak, std::fabs(acc));
        }
        return peak;
    }

private:
    std::array<std::array<double, kTapsPerPhase>, kFactor> m_phase{};
    std::array<double, 2 * kTapsPerPhase> m_history{};
    int m_pos = 0;
};

} // namespace dsp
} // namespace audio_engine
