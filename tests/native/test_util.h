#pragma once

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <unistd.h>
#include <vector>

#define CHECK(cond) do { if (!(cond)) { \
    std::fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #cond); std::abort(); } } while (0)
#define CHECK_NEAR(a, b, tol) do { const double _a = (a), _b = (b); if (!(std::fabs(_a - _b) <= (tol))) { \
    std::fprintf(stderr, "%s:%d: CHECK_NEAR failed: %s=%.9g %s=%.9g tol=%.3g\n", __FILE__, __LINE__, #a, _a, #b, _b, (double)(tol)); \
    std::abort(); } } while (0)

namespace test {

using Bytes = std::vector<uint8_t>;

inline void tag(Bytes& b, const char* s) { b.insert(b.end(), s, s + 4); }
inline void le(Bytes& b, uint64_t v, int n) { for (int i = 0; i < n; ++i) b.push_back(static_cast<uint8_t>(v >> (8 * i))); }
inline void be(Bytes& b, uint64_t v, int n) { for (int i = n - 1; i >= 0; --i) b.push_back(static_cast<uint8_t>(v >> (8 * i))); }

/** Anonymous temporary file holding bytes; returns an fd positioned at 0. */
inline int tempFile(const Bytes& bytes) {
    char path[] = "/tmp/hifi-fixture-XXXXXX";
    const int fd = mkstemp(path);
    CHECK(fd >= 0);
    unlink(path);
    size_t done = 0;
    while (done < bytes.size()) {
        const ssize_t n = write(fd, bytes.data() + done, bytes.size() - done);
        CHECK(n > 0);
        done += static_cast<size_t>(n);
    }
    lseek(fd, 0, SEEK_SET);
    return fd;
}

inline Bytes readFile(const std::string& path) {
    Bytes out;
    FILE* f = std::fopen(path.c_str(), "rb");
    CHECK(f != nullptr);
    uint8_t buf[65536];
    size_t n;
    while ((n = std::fread(buf, 1, sizeof(buf), f)) > 0) out.insert(out.end(), buf, buf + n);
    std::fclose(f);
    return out;
}

/** Least-squares fit of dc + a*cos + b*sin at frequency f; returns amplitude, fills residual. */
inline double fitSine(const std::vector<double>& x, size_t begin, size_t end, double f, double rate,
                      std::vector<double>* residual = nullptr) {
    double s[3][3] = {}, r[3] = {};
    const double w = 2.0 * M_PI * f / rate;
    for (size_t n = begin; n < end; ++n) {
        const double v[3] = {1.0, std::cos(w * n), std::sin(w * n)};
        for (int i = 0; i < 3; ++i) {
            r[i] += v[i] * x[n];
            for (int j = 0; j < 3; ++j) s[i][j] += v[i] * v[j];
        }
    }
    // Gaussian elimination on the 3x3 normal equations.
    double a[3][4];
    for (int i = 0; i < 3; ++i) { for (int j = 0; j < 3; ++j) a[i][j] = s[i][j]; a[i][3] = r[i]; }
    for (int i = 0; i < 3; ++i) {
        for (int k = i + 1; k < 3; ++k) {
            const double m = a[k][i] / a[i][i];
            for (int j = i; j < 4; ++j) a[k][j] -= m * a[i][j];
        }
    }
    double c[3];
    for (int i = 2; i >= 0; --i) {
        double v = a[i][3];
        for (int j = i + 1; j < 3; ++j) v -= a[i][j] * c[j];
        c[i] = v / a[i][i];
    }
    if (residual) {
        residual->assign(x.size(), 0.0);
        for (size_t n = begin; n < end; ++n) {
            (*residual)[n] = x[n] - (c[0] + c[1] * std::cos(w * n) + c[2] * std::sin(w * n));
        }
    }
    return std::sqrt(c[1] * c[1] + c[2] * c[2]);
}

inline double rms(const std::vector<double>& x, size_t begin, size_t end) {
    double acc = 0.0;
    for (size_t n = begin; n < end; ++n) acc += x[n] * x[n];
    return std::sqrt(acc / static_cast<double>(end - begin));
}

inline double db(double ratio) { return 20.0 * std::log10(ratio); }

} // namespace test
