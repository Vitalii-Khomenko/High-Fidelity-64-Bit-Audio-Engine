#pragma once

#include <cmath>
#include <cstddef>
#include <cstdint>

namespace audio_engine {
namespace dsp {

/**
 * Headphone crossfeed after Boris Mikhaylov's bs2b (Bauer stereophonic-to-
 * binaural, MIT licence), in double precision: each ear also hears the other
 * channel low-passed and slightly delayed, while a high-shelf keeps the
 * direct channel's tonal balance. Mono content passes at unity gain.
 */
class Crossfeed {
public:
    enum class Preset : int { Off = 0, Default = 1, ChuMoy = 2, JanMeier = 3 };

    void prepare(uint32_t sampleRate) {
        m_rate = sampleRate;
        configure();
    }

    void setPreset(Preset preset) {
        if (preset == m_preset) return;
        m_preset = preset;
        configure();
    }

    Preset preset() const { return m_preset; }
    bool active() const { return m_preset != Preset::Off && m_rate > 0; }

    void reset() {
        m_lo[0] = m_lo[1] = m_hi[0] = m_hi[1] = m_asis[0] = m_asis[1] = 0.0;
    }

    /** In place, interleaved stereo. */
    void process(double* data, size_t frames) {
        if (!active()) return;
        for (size_t f = 0; f < frames; ++f) {
            double* s = data + f * 2;
            m_lo[0] = m_a0Lo * s[0] + m_b1Lo * m_lo[0];
            m_lo[1] = m_a0Lo * s[1] + m_b1Lo * m_lo[1];
            m_hi[0] = m_a0Hi * s[0] + m_a1Hi * m_asis[0] + m_b1Hi * m_hi[0];
            m_hi[1] = m_a0Hi * s[1] + m_a1Hi * m_asis[1] + m_b1Hi * m_hi[1];
            m_asis[0] = s[0];
            m_asis[1] = s[1];
            s[0] = (m_hi[0] + m_lo[1]) * m_gain;
            s[1] = (m_hi[1] + m_lo[0]) * m_gain;
        }
    }

private:
    void configure() {
        reset();
        if (m_rate == 0 || m_preset == Preset::Off) return;
        // Cut frequency (Hz) and feed level (dB) of the bs2b presets.
        double cut = 700.0, feed = 4.5;
        if (m_preset == Preset::ChuMoy) feed = 6.0;
        if (m_preset == Preset::JanMeier) { cut = 650.0; feed = 9.5; }
        const double gbLo = feed * -5.0 / 6.0 - 3.0;
        const double gbHi = feed / 6.0 - 3.0;
        const double gLo = std::pow(10.0, gbLo / 20.0);
        const double gHi = 1.0 - std::pow(10.0, gbHi / 20.0);
        const double cutHi = cut * std::pow(2.0, (gbLo - 20.0 * std::log10(gHi)) / 12.0);
        double x = std::exp(-2.0 * M_PI * cut / m_rate);
        m_b1Lo = x;
        m_a0Lo = gLo * (1.0 - x);
        x = std::exp(-2.0 * M_PI * cutHi / m_rate);
        m_b1Hi = x;
        m_a0Hi = 1.0 - gHi * (1.0 - x);
        m_a1Hi = -x;
        m_gain = 1.0 / (1.0 - gHi + gLo);
    }

    uint32_t m_rate = 0;
    Preset m_preset = Preset::Off;
    double m_a0Lo = 0, m_b1Lo = 0, m_a0Hi = 1, m_a1Hi = 0, m_b1Hi = 0, m_gain = 1;
    double m_lo[2] = {}, m_hi[2] = {}, m_asis[2] = {};
};

} // namespace dsp
} // namespace audio_engine
