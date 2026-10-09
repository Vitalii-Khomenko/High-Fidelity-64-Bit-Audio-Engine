#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <vector>

#include "TruePeak.h"

namespace audio_engine {
namespace dsp {

/**
 * Look-ahead true-peak limiter. The gain needed by each frame (ceiling over
 * its 4x-oversampled peak) goes through a sliding minimum over the look-ahead
 * window, a release, and a moving average of the same length. The average of
 * minima that all include a frame's own requirement can never exceed it, so
 * no true peak passes the ceiling, and the gain ramps smoothly into a peak
 * instead of jumping. Below the ceiling the signal passes untouched (gain
 * exactly 1).
 *
 * The audio is delayed by latency() frames. After reset() the first frames
 * are withheld rather than padded with silence, and flush() hands them out at
 * the end, so no audio is added or lost.
 */
class TruePeakLimiter {
public:
    void prepare(uint32_t sampleRate, size_t channels, double ceilingDb = -1.0) {
        m_channels = std::max<size_t>(1, channels);
        m_window = std::max<size_t>(16, static_cast<size_t>(sampleRate * 0.0015));
        m_delay = static_cast<size_t>(TruePeakDetector::kDelay) + m_window - 1;
        m_ceiling = std::pow(10.0, ceilingDb / 20.0);
        m_releaseStep = 1.0 - std::exp(-1.0 / (0.05 * std::max<uint32_t>(sampleRate, 1)));
        m_detectors.assign(m_channels, TruePeakDetector());
        m_audio.assign(m_delay * m_channels, 0.0);
        reset();
    }

    void reset() {
        for (auto& d : m_detectors) d.reset();
        m_minima.clear();
        m_averaged.assign(m_window, 1.0);
        m_sum = static_cast<double>(m_window);
        m_release = 1.0;
        m_index = 0;
        m_held = 0;
        m_audioPos = 0;
        m_minGain = 1.0;
    }

    /** Frames held back inside the limiter right now. */
    size_t latency() const { return m_held; }

    /** Lowest gain applied since the last call (for tests and meters). */
    double takeMinGain() {
        const double g = m_minGain;
        m_minGain = 1.0;
        return g;
    }

    /**
     * Limits frames of interleaved audio from in to out (which may alias) and
     * returns how many frames were produced: fewer than given only right after
     * reset(), while the look-ahead fills.
     */
    size_t process(const double* in, size_t frames, double* out) {
        size_t produced = 0;
        for (size_t f = 0; f < frames; ++f) {
            double frame[8];
            const size_t ch = std::min<size_t>(m_channels, 8);
            for (size_t c = 0; c < ch; ++c) frame[c] = in[f * m_channels + c];
            if (step(frame, ch, out ? out + produced * m_channels : nullptr)) ++produced;
        }
        return produced;
    }

    /** Hands out the withheld frames (end of a track). Returns their count. */
    size_t flush(double* out) {
        // Push silence through: once the delay line is full again, the frames
        // leaving it are the real ones, oldest first, followed by padding.
        const size_t real = m_held;
        size_t produced = 0;
        const double zero[8] = {};
        while (produced < real) {
            if (step(zero, std::min<size_t>(m_channels, 8), out + produced * m_channels)) ++produced;
        }
        reset();
        return produced;
    }

    size_t channels() const { return m_channels; }

private:
    /** One frame in; true when one delayed frame came out into out. */
    bool step(const double* frame, size_t ch, double* out) {
        double tp = 0.0;
        for (size_t c = 0; c < ch; ++c) tp = std::max(tp, m_detectors[c].push(frame[c]));
        const double required = tp > m_ceiling ? m_ceiling / tp : 1.0;

        // Sliding minimum of the requirement over the window.
        const uint64_t i = m_index++;
        while (!m_minima.empty() && m_minima.back().second >= required) m_minima.pop_back();
        m_minima.emplace_back(i, required);
        while (m_minima.front().first + m_window <= i) m_minima.pop_front();
        // Release: the gain may fall at once but only rises gradually.
        m_release = std::min(m_minima.front().second, m_release + (1.0 - m_release) * m_releaseStep);
        // Moving average over the window.
        const size_t slot = static_cast<size_t>(i % m_window);
        m_sum += m_release - m_averaged[slot];
        m_averaged[slot] = m_release;
        const double gain = std::min(1.0, m_sum / static_cast<double>(m_window));

        // Delay line: once full, the frame leaving it is the one this gain belongs to.
        double* cell = m_audio.data() + m_audioPos * m_channels;
        bool emitted = false;
        if (m_held == m_delay) {
            if (out) {
                // Exactly 1.0 when not limiting: samples pass bit-exact.
                const double g = gain >= 1.0 - 1e-9 ? 1.0 : gain;
                for (size_t c = 0; c < m_channels; ++c) out[c] = cell[c] * g;
                m_minGain = std::min(m_minGain, g);
            }
            emitted = true;
        } else {
            ++m_held;
        }
        for (size_t c = 0; c < m_channels; ++c) cell[c] = c < ch ? frame[c] : 0.0;
        m_audioPos = (m_audioPos + 1) % m_delay;
        return emitted;
    }

    size_t m_channels = 2;
    size_t m_window = 64;
    size_t m_delay = 69;
    double m_ceiling = 0.891;
    double m_releaseStep = 0.0004;
    std::vector<TruePeakDetector> m_detectors;
    std::deque<std::pair<uint64_t, double>> m_minima;
    std::vector<double> m_averaged;
    double m_sum = 0.0;
    double m_release = 1.0;
    uint64_t m_index = 0;
    std::vector<double> m_audio;
    size_t m_audioPos = 0;
    size_t m_held = 0;
    double m_minGain = 1.0;
};

} // namespace dsp
} // namespace audio_engine
