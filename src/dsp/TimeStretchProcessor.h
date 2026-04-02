#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>

#include "../third_party/sonic/sonic.h"

namespace audio_engine {
namespace dsp {

enum class TimeStretchMode {
    Music = 0,
    Speech = 1,
};

class TimeStretchProcessor {
public:
    void prepare(uint32_t sampleRate, size_t channels) {
        m_sampleRate = sampleRate;
        m_channels = channels;
        recreateStream();
    }

    void setMode(TimeStretchMode mode) {
        m_mode = mode;
        applyProfile();
        reset();
    }

    void reset() {
        recreateStream();
        m_endOfInput = false;
        m_flushedEnd = false;
    }

    void setSpeed(double speed) {
        m_targetSpeed = std::clamp(speed, 0.75, 2.0);
    }

    void clearEndOfInput() {
        m_endOfInput = false;
        m_flushedEnd = false;
    }

    void markEndOfInput() {
        m_endOfInput = true;
    }

    void appendInterleaved(const double* input, size_t frames) {
        if (!input || frames == 0 || m_channels == 0 || !m_stream) return;

        synchronizeSpeed();

        const size_t sampleCount = frames * m_channels;
        if (m_floatBuffer.size() < sampleCount) {
            m_floatBuffer.resize(sampleCount);
        }
        for (size_t index = 0; index < sampleCount; ++index) {
            m_floatBuffer[index] = static_cast<float>(std::clamp(input[index], -1.0, 1.0));
        }
        sonicWriteFloatToStream(m_stream.get(), m_floatBuffer.data(), static_cast<int>(frames));
    }

    size_t renderInterleaved(double* output, size_t requestedFrames) {
        if (!output || requestedFrames == 0 || m_channels == 0 || !m_stream) return 0;

        synchronizeSpeed();

        if (m_endOfInput && !m_flushedEnd) {
            sonicFlushStream(m_stream.get());
            m_flushedEnd = true;
        }

        const size_t sampleCount = requestedFrames * m_channels;
        if (m_floatBuffer.size() < sampleCount) {
            m_floatBuffer.resize(sampleCount);
        }

        const int producedFrames = sonicReadFloatFromStream(
            m_stream.get(),
            m_floatBuffer.data(),
            static_cast<int>(requestedFrames)
        );
        if (producedFrames <= 0) {
            return 0;
        }

        const size_t producedSamples = static_cast<size_t>(producedFrames) * m_channels;
        for (size_t index = 0; index < producedSamples; ++index) {
            output[index] = static_cast<double>(m_floatBuffer[index]);
        }
        return static_cast<size_t>(producedFrames);
    }

    size_t getAvailableFrames() const {
        if (!m_stream) return 0;
        const int available = sonicSamplesAvailable(m_stream.get());
        return available > 0 ? static_cast<size_t>(available) : 0;
    }

    bool isDrained() const {
        return m_endOfInput && m_flushedEnd && (!m_stream || sonicSamplesAvailable(m_stream.get()) == 0);
    }

private:
    static constexpr double kSpeedSlewPerUpdate = 0.035;

    struct SonicStreamDeleter {
        void operator()(sonicStream stream) const {
            if (stream) {
                sonicDestroyStream(stream);
            }
        }
    };

    void synchronizeSpeed(bool immediate = false) {
        if (!m_stream) return;

        const double targetSpeed = m_targetSpeed;
        if (immediate || std::abs(m_currentSpeed - targetSpeed) <= kSpeedSlewPerUpdate) {
            m_currentSpeed = targetSpeed;
        } else if (m_currentSpeed < targetSpeed) {
            m_currentSpeed += kSpeedSlewPerUpdate;
        } else {
            m_currentSpeed -= kSpeedSlewPerUpdate;
        }

        if (std::abs(m_appliedSpeed - m_currentSpeed) > 0.0005) {
            sonicSetSpeed(m_stream.get(), static_cast<float>(m_currentSpeed));
            m_appliedSpeed = m_currentSpeed;
        }
    }

    void applyProfile() {
        if (!m_stream) return;
        m_currentSpeed = m_targetSpeed;
        m_appliedSpeed = m_targetSpeed;
        sonicSetSpeed(m_stream.get(), static_cast<float>(m_targetSpeed));
        sonicSetPitch(m_stream.get(), 1.0f);
        sonicSetRate(m_stream.get(), 1.0f);
        sonicSetVolume(m_stream.get(), 1.0f);
        sonicSetQuality(m_stream.get(), 1);
    }

    void recreateStream() {
        m_stream.reset();
        if (m_sampleRate == 0 || m_channels == 0) {
            return;
        }
        m_stream.reset(sonicCreateStream(static_cast<int>(m_sampleRate), static_cast<int>(m_channels)));
        applyProfile();
    }

    uint32_t m_sampleRate{0};
    size_t m_channels{0};
    double m_targetSpeed{1.0};
    double m_currentSpeed{1.0};
    double m_appliedSpeed{1.0};
    bool m_endOfInput{false};
    bool m_flushedEnd{false};
    TimeStretchMode m_mode{TimeStretchMode::Music};
    std::unique_ptr<sonicStreamStruct, SonicStreamDeleter> m_stream;
    std::vector<float> m_floatBuffer;
};

} // namespace dsp
} // namespace audio_engine