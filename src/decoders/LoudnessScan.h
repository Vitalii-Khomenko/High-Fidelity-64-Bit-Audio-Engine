#pragma once

#include <cmath>
#include <vector>

#include "DecoderFactory.h"
#include "RangeDecoder.h"
#include "../dsp/LoudnessMeter.h"

namespace audio_engine {
namespace decoders {

struct LoudnessResult {
    bool ok = false;
    double integratedLufs = -INFINITY;
    double truePeakDb = -INFINITY;
    double seconds = 0.0;
};

/** Decodes a whole file (or a CUE range) as fast as possible and measures it. */
inline LoudnessResult scanLoudness(int fd, int64_t startUs = 0, int64_t endUs = 0) {
    LoudnessResult result;
    auto decoder = RangeDecoder::wrap(openDecoder(fd), startUs, endUs);
    if (!decoder) return result;
    const size_t channels = decoder->getNumChannels();
    const uint32_t rate = decoder->getSampleRate();
    dsp::LoudnessMeter meter;
    meter.prepare(rate, channels);
    constexpr size_t kBlock = 4096;
    core::AudioBuffer buffer(channels, kBlock, rate);
    std::vector<double> interleaved(kBlock * channels);
    uint64_t frames = 0;
    for (;;) {
        const size_t n = decoder->readFrames(buffer, kBlock);
        if (n == 0) break;
        for (size_t c = 0; c < channels; ++c) {
            const double* src = buffer.getReadPointer(c);
            for (size_t i = 0; i < n; ++i) interleaved[i * channels + c] = std::isfinite(src[i]) ? src[i] : 0.0;
        }
        meter.add(interleaved.data(), n);
        frames += n;
    }
    result.ok = frames > 0;
    result.integratedLufs = meter.integratedLufs();
    result.truePeakDb = meter.truePeakDb();
    result.seconds = rate ? static_cast<double>(frames) / rate : 0.0;
    return result;
}

} // namespace decoders
} // namespace audio_engine
