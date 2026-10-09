#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>

#include "Fft.h"

namespace audio_engine {
namespace dsp {

/**
 * Log-spaced magnitude bands (20 Hz .. 20 kHz) for the UI visualiser.
 * Fast attack, slow decay; values are normalised to 0..1 over a 60 dB range.
 */
class SpectrumAnalyzer {
public:
    static constexpr size_t kSize = 2048;
    static constexpr int kMaxBands = 64;

    SpectrumAnalyzer() {
        for (size_t i = 0; i < kSize; ++i) {
            m_window[i] = 0.5 * (1.0 - std::cos(2.0 * M_PI * static_cast<double>(i) / (kSize - 1)));
        }
        m_smooth.fill(0.0f);
    }

    /** samples: newest kSize mono samples, oldest first. */
    void analyze(const float* samples, uint32_t sampleRate, float* bands, int bandCount) {
        bandCount = std::clamp(bandCount, 0, kMaxBands);
        if (bandCount == 0 || sampleRate == 0) return;
        for (size_t i = 0; i < kSize; ++i) {
            m_re[i] = static_cast<double>(samples[i]) * m_window[i];
            m_im[i] = 0.0;
        }
        fft(m_re.data(), m_im.data(), static_cast<int>(kSize));

        const double fMin = 20.0;
        const double fMax = std::min(20000.0, sampleRate * 0.5);
        const double logRange = std::log10(fMax / fMin);
        // Hann coherent gain is 0.5, so a full-scale sine reads about 0 dB.
        const double norm = 4.0 / static_cast<double>(kSize);
        for (int b = 0; b < bandCount; ++b) {
            const double lo = fMin * std::pow(10.0, logRange * b / bandCount);
            const double hi = fMin * std::pow(10.0, logRange * (b + 1) / bandCount);
            const int binLo = std::max(1, static_cast<int>(lo * kSize / sampleRate));
            const int binHi = std::min(static_cast<int>(kSize / 2),
                                       std::max(binLo + 1, static_cast<int>(hi * kSize / sampleRate) + 1));
            double mag = 0.0;
            for (int k = binLo; k < binHi; ++k) {
                mag = std::max(mag, std::sqrt(m_re[k] * m_re[k] + m_im[k] * m_im[k]) * norm);
            }
            const double db = 20.0 * std::log10(mag + 1e-9);
            const float level = static_cast<float>(std::clamp((db + 60.0) / 60.0, 0.0, 1.0));
            float& s = m_smooth[static_cast<size_t>(b)];
            s = level > s ? s * 0.25f + level * 0.75f : s * 0.85f;
            bands[b] = s;
        }
    }

    void decay(float* bands, int bandCount) {
        bandCount = std::clamp(bandCount, 0, kMaxBands);
        for (int b = 0; b < bandCount; ++b) {
            m_smooth[static_cast<size_t>(b)] *= 0.8f;
            bands[b] = m_smooth[static_cast<size_t>(b)];
        }
    }

private:
    std::array<double, kSize> m_window{};
    std::array<double, kSize> m_re{};
    std::array<double, kSize> m_im{};
    std::array<float, kMaxBands> m_smooth{};
};

} // namespace dsp
} // namespace audio_engine
