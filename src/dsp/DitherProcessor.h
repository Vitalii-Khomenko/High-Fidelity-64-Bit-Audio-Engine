#pragma once

#include "../core/AudioBuffer.h"
#include <random>
#include <vector>
#include <algorithm>
#include <cmath>
#include <cstdint>

namespace audio_engine {
namespace dsp {

/**
 * @brief Triangular Probability Density Function (TPDF) Dithering.
 * 
 * Applies dithering prior to converting 64-bit float streams into
 * integer domains (16-bit, 24-bit, 32-bit integer DAC limits) to prevent
 * quantization distortion.
 */
class DitherProcessor {
public:
    DitherProcessor(uint8_t targetBitDepth = 16) : m_seed(123456789) {
        setTargetBitDepth(targetBitDepth);
    }

    void setTargetBitDepth(uint8_t bitDepth) {
        m_targetBitDepth = bitDepth;
        m_lsbFactor = std::pow(2.0, static_cast<double>(bitDepth - 1));
        // LSB peak-to-peak factor requires double the inverse
        m_invLsbFactor = 1.0 / m_lsbFactor;
    }

    /**
     * @brief Extremely fast, lock-free Pseudo-Random Number Generator (LCG)
     * Suitable for real-time DSP audio threading without taking locks.
     */
    inline double fastRandom() {
        m_seed = m_seed * 1664525 + 1013904223;
        // Map to -1.0 to 1.0
        return 2.0 * (static_cast<double>(m_seed) / 4294967296.0) - 1.0;
    }

    /**
     * @brief Apply dithering and quantize internal float-64 states into standard integer boundaries.
     * @param inputBuffer The continuous 64-bit float AudioBuffer.
     * @param outputBuffer Pointer to the target int32_t (or int16) destination array.
     */
    void processToInteger(core::AudioBuffer& inputBuffer, int32_t* outputBuffer) {
        const size_t numFrames = inputBuffer.getNumFrames();
        const size_t numChannels = inputBuffer.getNumChannels();

        if (numChannels == 0) return;

        // Apply TPDF dither and hard quantize
        for (size_t ch = 0; ch < numChannels; ++ch) {
            const double* channelData = inputBuffer.getReadPointer(ch);
            
            for (size_t i = 0; i < numFrames; ++i) {
                double sample = channelData[i];
                
                // Add dither noise equivalent to 2 LSB Peak-to-Peak (TPDF)
                double rpdf1 = fastRandom();
                double rpdf2 = fastRandom();
                double ditherNoise = (rpdf1 + rpdf2) * m_invLsbFactor; // TPDF Dither
                
                // Final quantization (Mid-tread)
                double quantized = std::floor((sample + ditherNoise) * m_lsbFactor + 0.5);
                
                // Fast hard clipping to prevent wrapping artifacts
                if (quantized > (m_lsbFactor - 1.0)) {
                    quantized = m_lsbFactor - 1.0;
                } else if (quantized < -m_lsbFactor) {
                    quantized = -m_lsbFactor;
                }
                
                outputBuffer[i * numChannels + ch] = static_cast<int32_t>(quantized);
            }
        }
    }

private:
    uint8_t m_targetBitDepth;
    double m_lsbFactor;
    double m_invLsbFactor;
    uint32_t m_seed;
};

} // namespace dsp
} // namespace audio_engine
