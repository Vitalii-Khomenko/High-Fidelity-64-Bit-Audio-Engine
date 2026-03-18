#pragma once

#include "IAudioProcessor.h"
#include <cmath>
#include <vector>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

namespace audio_engine {
namespace dsp {

/**
 * @brief Enum defining standard biquad coefficient types based on Robert Bristow-Johnson's EQ Cookbook.
 */
enum class FilterType {
    LowPass,
    HighPass,
    BandPass,
    Notch,
    Peak,
    LowShelf,
    HighShelf
};

/**
 * @brief Represents custom Direct Form I Biquad structure operating in 64-bit precision.
 * 
 * y[n] = (b0/a0)*x[n] + (b1/a0)*x[n-1] + (b2/a0)*x[n-2] 
 *        - (a1/a0)*y[n-1] - (a2/a0)*y[n-2]
 */
class BiquadFilter : public IAudioProcessor {
public:
    BiquadFilter() {
        reset();
    }

    void prepare(uint32_t sampleRate, size_t bufferSize) override {
        m_sampleRate = sampleRate;
        calculateCoefficients();
    }

    void processBlock(core::AudioBuffer& buffer) override {
        const size_t numChannels = buffer.getNumChannels();
        const size_t numFrames = buffer.getNumFrames();

        // Ensure we handle mono/stereo correctly without reallocating state frequently
        if (m_state.size() < numChannels) {
            m_state.resize(numChannels, {0.0, 0.0, 0.0, 0.0});
        }

        // Apply coefficients
        const double b0 = m_b0 / m_a0;
        const double b1 = m_b1 / m_a0;
        const double b2 = m_b2 / m_a0;
        const double a1 = m_a1 / m_a0;
        const double a2 = m_a2 / m_a0;

        for (size_t ch = 0; ch < numChannels; ++ch) {
            double* data = buffer.getWritePointer(ch);
            auto& state = m_state[ch];

            for (size_t i = 0; i < numFrames; ++i) {
                double xn = data[i];

                double yn = b0 * xn + b1 * state.x1 + b2 * state.x2 - a1 * state.y1 - a2 * state.y2;

                // Update delays
                state.x2 = state.x1;
                state.x1 = xn;
                state.y2 = state.y1;
                state.y1 = yn;

                data[i] = yn;
            }
        }
    }

    void reset() override {
        for (auto& s : m_state) {
            s.x1 = s.x2 = s.y1 = s.y2 = 0.0;
        }
    }

    void setParameters(FilterType type, double frequency, double qFactor, double gainDb) {
        m_type = type;
        m_frequency = frequency;
        m_qFactor = qFactor;
        m_gainDb = gainDb;
        if (m_sampleRate > 0) {
            calculateCoefficients();
        }
    }

private:
    void calculateCoefficients() {
        if (m_sampleRate == 0) return;

        double w0 = 2.0 * M_PI * m_frequency / m_sampleRate;
        double alpha = std::sin(w0) / (2.0 * m_qFactor);
        double A = std::pow(10.0, m_gainDb / 40.0); // For Peaking and Shelf types
        
        switch (m_type) {
            case FilterType::LowPass:
                m_b0 = (1.0 - std::cos(w0)) / 2.0;
                m_b1 = 1.0 - std::cos(w0);
                m_b2 = (1.0 - std::cos(w0)) / 2.0;
                m_a0 = 1.0 + alpha;
                m_a1 = -2.0 * std::cos(w0);
                m_a2 = 1.0 - alpha;
                break;
            case FilterType::Peak:
                m_b0 = 1.0 + alpha * A;
                m_b1 = -2.0 * std::cos(w0);
                m_b2 = 1.0 - alpha * A;
                m_a0 = 1.0 + alpha / A;
                m_a1 = -2.0 * std::cos(w0);
                m_a2 = 1.0 - alpha / A;
                break;
            // TODO: Other filter types
            default:
                m_b0 = 1.0; m_b1 = 0.0; m_b2 = 0.0;
                m_a0 = 1.0; m_a1 = 0.0; m_a2 = 0.0;
        }
    }

    FilterType m_type = FilterType::Peak;
    double m_frequency = 1000.0;
    double m_qFactor = 0.707;
    double m_gainDb = 0.0;
    uint32_t m_sampleRate = 44100;

    // Filter Coefficients
    double m_b0 = 1.0, m_b1 = 0.0, m_b2 = 0.0;
    double m_a0 = 1.0, m_a1 = 0.0, m_a2 = 0.0;

    // Delay lines per channel
    struct FilterState {
        double x1 = 0.0, x2 = 0.0, y1 = 0.0, y2 = 0.0;
    };
    std::vector<FilterState> m_state;
};

} // namespace dsp
} // namespace audio_engine
