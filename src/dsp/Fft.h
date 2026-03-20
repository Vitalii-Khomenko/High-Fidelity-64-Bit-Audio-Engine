#pragma once
#include <cmath>
#include <algorithm>

namespace audio_engine {
namespace dsp {

// In-place radix-2 Cooley-Tukey FFT. N must be a power of 2.
// After the call: re[k] and im[k] contain the real/imaginary DFT output.
inline void fft(double* re, double* im, int N) {
    for (int i = 1, j = 0; i < N; ++i) {
        int bit = N >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) { std::swap(re[i], re[j]); std::swap(im[i], im[j]); }
    }
    for (int len = 2; len <= N; len <<= 1) {
        double ang = -2.0 * M_PI / len;
        double wRe = std::cos(ang), wIm = std::sin(ang);
        for (int i = 0; i < N; i += len) {
            double cRe = 1.0, cIm = 0.0;
            for (int j = 0; j < len / 2; ++j) {
                double uRe = re[i+j], uIm = im[i+j];
                double vRe = re[i+j+len/2]*cRe - im[i+j+len/2]*cIm;
                double vIm = re[i+j+len/2]*cIm + im[i+j+len/2]*cRe;
                re[i+j]         = uRe + vRe;  im[i+j]         = uIm + vIm;
                re[i+j+len/2]   = uRe - vRe;  im[i+j+len/2]   = uIm - vIm;
                double ncRe = cRe*wRe - cIm*wIm;
                cIm = cRe*wIm + cIm*wRe;
                cRe = ncRe;
            }
        }
    }
}

} // namespace dsp
} // namespace audio_engine
