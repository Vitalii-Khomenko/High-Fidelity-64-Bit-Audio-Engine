#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <vector>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

namespace audio_engine {
namespace dsp {

// Zeroth-order modified Bessel function of the first kind (power series).
inline double besselI0(double x) {
    double sum = 1.0;
    double term = 1.0;
    const double halfX = x * 0.5;
    for (int k = 1; k < 64; ++k) {
        term *= (halfX / k) * (halfX / k);
        sum += term;
        if (term < sum * 1e-17) break;
    }
    return sum;
}

// Kaiser beta for a stopband attenuation of attenuationDb (Kaiser's formula).
inline double kaiserBeta(double attenuationDb) {
    if (attenuationDb > 50.0) return 0.1102 * (attenuationDb - 8.7);
    if (attenuationDb >= 21.0) {
        return 0.5842 * std::pow(attenuationDb - 21.0, 0.4) + 0.07886 * (attenuationDb - 21.0);
    }
    return 0.0;
}

/**
 * Linear-phase Kaiser-windowed sinc low-pass filter.
 *
 * @param taps            number of coefficients
 * @param cutoff          -6 dB point as a fraction of the sample rate (0 < cutoff < 0.5)
 * @param attenuationDb   desired stopband attenuation
 * @return coefficients normalised to unity DC gain
 */
inline std::vector<double> designKaiserLowpass(size_t taps, double cutoff, double attenuationDb) {
    std::vector<double> h(taps, 0.0);
    if (taps == 0) return h;
    const double beta = kaiserBeta(attenuationDb);
    const double i0Beta = besselI0(beta);
    const double centre = (static_cast<double>(taps) - 1.0) * 0.5;
    double sum = 0.0;
    for (size_t n = 0; n < taps; ++n) {
        const double t = static_cast<double>(n) - centre;
        const double sinc = (t == 0.0)
            ? 2.0 * cutoff
            : std::sin(2.0 * M_PI * cutoff * t) / (M_PI * t);
        const double ratio = centre > 0.0 ? t / centre : 0.0;
        const double window = besselI0(beta * std::sqrt(std::max(0.0, 1.0 - ratio * ratio))) / i0Beta;
        h[n] = sinc * window;
        sum += h[n];
    }
    if (sum != 0.0) {
        for (double& value : h) value /= sum;
    }
    return h;
}

} // namespace dsp
} // namespace audio_engine
