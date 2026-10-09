#pragma once

#include <array>
#include <cstddef>

namespace audio_engine {
namespace decoders {

/**
 * The engine works in WAVE channel order (L R C LFE BL BR SL SR). Vorbis and
 * Opus (mapping family 1) use the Vorbis order instead. waveFromVorbis(n)[w]
 * is the Vorbis channel that becomes WAVE channel w.
 */
inline std::array<int, 8> waveFromVorbis(size_t channels) {
    switch (channels) {
        case 3: return {0, 2, 1, 3, 4, 5, 6, 7};      // L C R
        case 5: return {0, 2, 1, 3, 4, 5, 6, 7};      // L C R BL BR
        case 6: return {0, 2, 1, 5, 3, 4, 6, 7};      // L C R BL BR LFE
        case 7: return {0, 2, 1, 6, 5, 3, 4, 7};      // L C R SL SR BC LFE
        case 8: return {0, 2, 1, 7, 5, 6, 3, 4};      // L C R SL SR BL BR LFE
        default: return {0, 1, 2, 3, 4, 5, 6, 7};     // mono, stereo, quad
    }
}

} // namespace decoders
} // namespace audio_engine
