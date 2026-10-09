#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <vector>

#include "BiquadFilter.h"

namespace audio_engine {
namespace dsp {

enum class EqBandType : int { Peak = 0, LowShelf = 1, HighShelf = 2, LowPass = 3, HighPass = 4 };

struct EqBand {
    EqBandType type = EqBandType::Peak;
    double frequency = 1000.0;
    double q = 0.707;
    double gainDb = 0.0;
};

/** The classic five sliders as bands: 60 Hz shelf, 230 / 910 / 3600 Hz peaks, 14 kHz shelf. */
inline std::vector<EqBand> graphicBands(const double gainsDb[5]) {
    return {
        {EqBandType::LowShelf, 60.0, 0.707, gainsDb[0]},
        {EqBandType::Peak, 230.0, 0.9, gainsDb[1]},
        {EqBandType::Peak, 910.0, 0.9, gainsDb[2]},
        {EqBandType::Peak, 3600.0, 0.9, gainsDb[3]},
        {EqBandType::HighShelf, 14000.0, 0.707, gainsDb[4]},
    };
}

/**
 * Cascade of up to kMaxBands RBJ biquads in double precision (graphic EQ,
 * AutoEQ / Equalizer APO parametric profiles). Band changes keep the filter
 * state, so they apply without a click.
 */
class ParametricEq {
public:
    static constexpr size_t kMaxBands = 20;

    void prepare(uint32_t sampleRate) {
        m_sampleRate = sampleRate;
        rebuild();
    }

    void setBands(std::vector<EqBand> bands) {
        if (bands.size() > kMaxBands) bands.resize(kMaxBands);
        m_bands = std::move(bands);
        rebuild();
    }

    const std::vector<EqBand>& bands() const { return m_bands; }

    void processInterleaved(double* data, size_t frames, size_t channels) {
        for (auto& f : m_filters) f.processRawInterleaved(data, frames, channels);
    }

    void reset() {
        for (auto& f : m_filters) f.reset();
    }

    bool empty() const { return m_filters.empty(); }

    /**
     * Highest gain of the whole cascade in dB (0 if it never boosts), sampled
     * densely on a log scale plus at every band frequency. Used as automatic
     * headroom so boosts cannot clip.
     */
    double peakGainDb() const {
        if (m_filters.empty() || m_sampleRate == 0) return 0.0;
        const double top = std::min(20000.0, m_sampleRate * 0.49);
        std::vector<double> points;
        for (int i = 0; i <= 480; ++i) points.push_back(10.0 * std::pow(top / 10.0, i / 480.0));
        for (const auto& b : m_bands) points.push_back(std::clamp(b.frequency, 10.0, top));
        double peak = 1.0;
        for (double f : points) {
            double m = 1.0;
            for (const auto& filter : m_filters) m *= filter.magnitudeAt(f);
            peak = std::max(peak, m);
        }
        return 20.0 * std::log10(peak);
    }

private:
    void rebuild() {
        // Keep existing filter objects (and their state) when only parameters change.
        m_filters.resize(m_bands.size());
        if (m_sampleRate == 0) return;
        for (size_t i = 0; i < m_bands.size(); ++i) {
            const EqBand& b = m_bands[i];
            m_filters[i].prepare(m_sampleRate, 0);
            const double q = std::clamp(b.q, 0.05, 30.0);
            const double gain = std::isfinite(b.gainDb) ? std::clamp(b.gainDb, -30.0, 30.0) : 0.0;
            m_filters[i].setParameters(type(b.type), b.frequency, q, gain);
        }
    }

    static FilterType type(EqBandType t) {
        switch (t) {
            case EqBandType::LowShelf: return FilterType::LowShelf;
            case EqBandType::HighShelf: return FilterType::HighShelf;
            case EqBandType::LowPass: return FilterType::LowPass;
            case EqBandType::HighPass: return FilterType::HighPass;
            default: return FilterType::Peak;
        }
    }

    uint32_t m_sampleRate = 0;
    std::vector<EqBand> m_bands;
    std::vector<BiquadFilter> m_filters;
};

} // namespace dsp
} // namespace audio_engine
