#pragma once

#include "IAudioProcessor.h"
#include <atomic>
#include <cmath>

namespace audio_engine {
namespace dsp {

/**
 * @brief Thread-safe 64-bit precision Gain and Volume controller.
 * 
 * Supports smooth, de-zippered interpolation between volume changes to avoid 
 * audio artifacts (clicks) when adjustments happen in real time.
 */
class GainProcessor : public IAudioProcessor {
public:
    GainProcessor() : m_gain(1.0), m_targetGain(1.0), m_smoothFactor(0.001) {}

    void prepare(uint32_t sampleRate, size_t bufferSize) override {
        // Adjust smoothing depending on the sample rate.
        // E.g., a simple first order low-pass filter formula for approx 20ms time constant.
        m_smoothFactor = 1.0 - std::exp(-1.0 / (0.02 * static_cast<double>(sampleRate)));
    }

    void processBlock(core::AudioBuffer& buffer) override {
        const size_t numChannels = buffer.getNumChannels();
        const size_t numFrames = buffer.getNumFrames();

        double currentGain = m_gain.load(std::memory_order_acquire);
        double targetGain = m_targetGain.load(std::memory_order_acquire);

        // If target gain matches current gain perfectly, no interpolation needed
        if (std::abs(currentGain - targetGain) < 1e-9) {
            if (std::abs(currentGain - 1.0) < 1e-9) {
                // Unity gain, do nothing
                return;
            } else {
                // Constant non-unity gain
                for (size_t ch = 0; ch < numChannels; ++ch) {
                    double* channelData = buffer.getWritePointer(ch);
                    for (size_t i = 0; i < numFrames; ++i) {
                        channelData[i] *= currentGain;
                    }
                }
            }
        } else {
            // Apply smoothing interpolation over the buffer
            // A simple per-sample recursive smoothing
            for (size_t i = 0; i < numFrames; ++i) {
                currentGain += (targetGain - currentGain) * m_smoothFactor;
                
                for (size_t ch = 0; ch < numChannels; ++ch) {
                    double* channelData = buffer.getWritePointer(ch);
                    channelData[i] *= currentGain;
                }
            }
            m_gain.store(currentGain, std::memory_order_release);
        }
    }

    void reset() override {
        // Upon reset, smoothly glide to target from target, effectively bypassing smoothing.
        m_gain.store(m_targetGain.load(std::memory_order_acquire), std::memory_order_relaxed);
    }

    /**
     * @brief Set the gain value smoothly.
     * @param linearGain Gain as a linear multiplier (0.0 = silence, 1.0 = unity).
     */
    void setGainLinear(double linearGain) {
        if (linearGain < 0.0) linearGain = 0.0;
        m_targetGain.store(linearGain, std::memory_order_release);
    }

    /**
     * @brief Set the gain value using decibels smoothly.
     * @param dB Gain in decibels.
     */
    void setGainDecibels(double dB) {
        setGainLinear(std::pow(10.0, dB / 20.0));
    }

private:
    std::atomic<double> m_gain;
    std::atomic<double> m_targetGain;
    double m_smoothFactor;
};

} // namespace dsp
} // namespace audio_engine
