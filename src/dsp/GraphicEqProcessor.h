#pragma once

#include <array>
#include <cstddef>
#include <cstdint>

#include "BiquadFilter.h"

namespace audio_engine {
namespace dsp {

class GraphicEqProcessor : public IAudioProcessor {
public:
    static constexpr size_t BandCount = 5;

    void prepare(uint32_t sampleRate, size_t bufferSize) override {
        m_sampleRate = sampleRate;
        for (auto& filter : m_filters) {
            filter.prepare(sampleRate, bufferSize);
        }
        updateCoefficients();
        reset();
    }

    void processBlock(core::AudioBuffer& buffer) override {
        if (!m_enabled) return;
        for (auto& filter : m_filters) {
            filter.processBlock(buffer);
        }
    }

    void processRawInterleaved(double* data, size_t numFrames, size_t numChannels) {
        if (!m_enabled || !data || numFrames == 0 || numChannels == 0) return;
        for (auto& filter : m_filters) {
            filter.processRawInterleaved(data, numFrames, numChannels);
        }
    }

    void reset() override {
        for (auto& filter : m_filters) {
            filter.reset();
        }
    }

    void setEnabled(bool enabled) {
        if (m_enabled == enabled) return;
        m_enabled = enabled;
        reset();
    }

    bool isEnabled() const {
        return m_enabled;
    }

    void setBandGain(size_t bandIndex, double gainDb) {
        if (bandIndex >= BandCount) return;
        m_bandGainsDb[bandIndex] = gainDb;
        updateCoefficients();
    }

    double getBandGain(size_t bandIndex) const {
        return bandIndex < BandCount ? m_bandGainsDb[bandIndex] : 0.0;
    }

    void resetBands() {
        m_bandGainsDb.fill(0.0);
        updateCoefficients();
        reset();
    }

private:
    void updateCoefficients() {
        if (m_sampleRate == 0) return;

        m_filters[0].setParameters(FilterType::LowShelf, 60.0, 0.707, m_bandGainsDb[0]);
        m_filters[1].setParameters(FilterType::Peak, 230.0, 0.9, m_bandGainsDb[1]);
        m_filters[2].setParameters(FilterType::Peak, 910.0, 0.9, m_bandGainsDb[2]);
        m_filters[3].setParameters(FilterType::Peak, 3600.0, 0.9, m_bandGainsDb[3]);
        m_filters[4].setParameters(FilterType::HighShelf, 14000.0, 0.707, m_bandGainsDb[4]);
    }

    uint32_t m_sampleRate{0};
    bool m_enabled{false};
    std::array<double, BandCount> m_bandGainsDb{0.0, 0.0, 0.0, 0.0, 0.0};
    std::array<BiquadFilter, BandCount> m_filters;
};

} // namespace dsp
} // namespace audio_engine