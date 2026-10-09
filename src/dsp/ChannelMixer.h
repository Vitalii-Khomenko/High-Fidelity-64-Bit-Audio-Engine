#pragma once

#include <algorithm>
#include <array>
#include <cstddef>

namespace audio_engine {
namespace dsp {

/**
 * Fold-down of WAVE/FLAC-ordered multichannel audio to stereo, used only when
 * the device refuses to open the source layout.
 *
 * Centre and surround channels are mixed at -3 dB, LFE is dropped, and the
 * result is scaled so a full-scale front channel plus centre stays below 0 dBFS
 * (the output limiter catches rare simultaneous peaks).
 */
class StereoDownmix {
public:
    static constexpr size_t kMaxChannels = 8;

    void configure(size_t inputChannels) {
        m_inputs = inputChannels;
        m_left.fill(0.0);
        m_right.fill(0.0);
        constexpr double c = 0.7071067811865476;
        enum : int { L, R, C, LFE, BL, BR, SL, SR, BC };
        // Channel order per count (WAVEFORMATEXTENSIBLE defaults, as used by FLAC).
        int layout[kMaxChannels] = {L, R, C, LFE, BL, BR, SL, SR};
        switch (inputChannels) {
            case 3: { const int l[] = {L, R, C}; std::copy(l, l + 3, layout); break; }
            case 4: { const int l[] = {L, R, BL, BR}; std::copy(l, l + 4, layout); break; }
            case 5: { const int l[] = {L, R, C, BL, BR}; std::copy(l, l + 5, layout); break; }
            case 6: { const int l[] = {L, R, C, LFE, BL, BR}; std::copy(l, l + 6, layout); break; }
            case 7: { const int l[] = {L, R, C, LFE, BC, SL, SR}; std::copy(l, l + 7, layout); break; }
            default: break;
        }
        for (size_t i = 0; i < inputChannels && i < kMaxChannels; ++i) {
            switch (layout[i]) {
                case L: m_left[i] = 1.0; break;
                case R: m_right[i] = 1.0; break;
                case C: case BC: m_left[i] = c; m_right[i] = c; break;
                case BL: case SL: m_left[i] = c; break;
                case BR: case SR: m_right[i] = c; break;
                default: break;  // LFE
            }
        }
        const double norm = 1.0 / (1.0 + c);
        for (size_t i = 0; i < kMaxChannels; ++i) {
            m_left[i] *= norm;
            m_right[i] *= norm;
        }
    }

    /** In-place: frames of m_inputs channels become frames of 2 channels. */
    void process(double* data, size_t frames) const {
        for (size_t f = 0; f < frames; ++f) {
            const double* in = data + f * m_inputs;
            double l = 0.0;
            double r = 0.0;
            for (size_t c = 0; c < m_inputs; ++c) {
                l += in[c] * m_left[c];
                r += in[c] * m_right[c];
            }
            // Writing frame f never overtakes the unread input of frame f + 1.
            data[f * 2] = l;
            data[f * 2 + 1] = r;
        }
    }

private:
    size_t m_inputs = 2;
    std::array<double, kMaxChannels> m_left{};
    std::array<double, kMaxChannels> m_right{};
};

} // namespace dsp
} // namespace audio_engine
