#pragma once

#include <atomic>
#include <thread>
#include <vector>
#include <memory>
#include <android/log.h>
#include <oboe/Oboe.h>

#include "../core/RingBuffer.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "AudioEngine", __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "AudioEngine", __VA_ARGS__)

namespace audio_engine {
namespace hw {

/**
 * @brief Oboe Audio Endpoint — long-lived stream.
 *
 * The stream is created ONCE (in initialize()) and kept alive for the entire
 * duration of the engine.  It is never closed between tracks.  The
 * onAudioReady callback simply outputs silence when the ring buffer is empty,
 * so there is no need to start / stop the stream per-track.
 *
 * terminate() is only called during engine shutdown.
 */
class OboeAudioEndpoint : public oboe::AudioStreamCallback {
public:
    explicit OboeAudioEndpoint(core::RingBuffer<double>* ringBuffer)
        : m_ringBuffer(ringBuffer), m_stream(nullptr), m_streamRunning(false),
          m_alive(std::make_shared<std::atomic<bool>>(true)) {}

    ~OboeAudioEndpoint() {
        // Signal any detached reconnect threads that this object is gone
        m_alive->store(false, std::memory_order_release);
        terminate();
    }

    /**
     * Open and START the Oboe stream.
     * Should be called once.  If called a second time with different
     * parameters, it closes the previous stream first.
     */
    bool initialize(uint32_t sampleRate, size_t numChannels) {
        // Close existing stream if parameters changed or not yet opened
        if (m_stream) {
            if (m_stream->getSampleRate() == static_cast<int32_t>(sampleRate) &&
                m_stream->getChannelCount() == static_cast<int32_t>(numChannels) &&
                m_streamRunning) {
                // Reuse existing stream — just clear old audio data
                return true;
            }
            terminateInternal();
        }

        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
               ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
               ->setSharingMode(oboe::SharingMode::Shared)
               ->setFormat(oboe::AudioFormat::Float)
               ->setChannelCount(static_cast<int>(numChannels))
               ->setSampleRate(static_cast<int>(sampleRate))
               ->setCallback(this);

        oboe::Result result = builder.openStream(m_stream);
        if (result != oboe::Result::OK) {
            LOGE("Oboe openStream failed: %s", oboe::convertToText(result));
            m_stream = nullptr;
            return false;
        }

        m_numChannels = static_cast<size_t>(m_stream->getChannelCount());
        m_tempBuffer.resize(8192 * m_numChannels, 0.0);

        result = m_stream->requestStart();
        if (result != oboe::Result::OK) {
            LOGE("Oboe requestStart failed: %s", oboe::convertToText(result));
            m_stream->close();
            m_stream = nullptr;
            return false;
        }

        m_streamRunning = true;
        LOGI("Oboe stream started: %d Hz, %zu ch", sampleRate, numChannels);
        return true;
    }

    /**
     * Full shutdown — stop and close the stream.
     * Only call this when the engine is being destroyed.
     */
    void terminate() {
        terminateInternal();
    }

    bool isInitialized() const { return m_stream != nullptr; }
    bool isRunning()     const { return m_streamRunning.load(); }

    int32_t getStreamSampleRate()    const { return m_stream ? m_stream->getSampleRate()    : 0; }
    int32_t getStreamChannelCount()  const { return m_stream ? m_stream->getChannelCount()  : 0; }

    // ------------------------------------------------------------------ //
    //  Oboe real-time callback                                            //
    // ------------------------------------------------------------------ //
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* /*stream*/,
                                          void* audioData,
                                          int32_t numFrames) override {
        float* out = static_cast<float*>(audioData);
        size_t samplesNeeded = static_cast<size_t>(numFrames) * m_numChannels;

        if (samplesNeeded > m_tempBuffer.size()) {
            samplesNeeded = m_tempBuffer.size();
        }

        size_t samplesRead = m_ringBuffer->read(m_tempBuffer.data(), samplesNeeded);

        // Silence for any underrun samples
        for (size_t i = samplesRead; i < samplesNeeded; ++i) {
            m_tempBuffer[i] = 0.0;
        }

        // Downcast 64-bit → 32-bit float for Oboe
        for (size_t i = 0; i < samplesNeeded; ++i) {
            out[i] = static_cast<float>(m_tempBuffer[i]);
        }

        return oboe::DataCallbackResult::Continue;
    }

    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override {
        LOGE("Oboe stream error after close: %s", oboe::convertToText(error));
        m_streamRunning = false;
        if (error == oboe::Result::ErrorDisconnected) {
            // Audio device changed (screen recording, headphone unplug, BT switch).
            // On Xiaomi MIUI, BT disconnects can fire multiple times in quick
            // succession — m_reinitInProgress prevents stacking reconnect threads.
            bool expected = false;
            if (!m_reinitInProgress.compare_exchange_strong(
                    expected, true, std::memory_order_acq_rel)) {
                LOGI("Oboe reconnect already in progress, skipping duplicate");
                return;
            }
            const int32_t sr    = stream->getSampleRate();
            const int32_t ch    = stream->getChannelCount();
            auto aliveFlag      = m_alive;
            OboeAudioEndpoint* self = this;
            std::thread([self, aliveFlag, sr, ch]() {
                std::this_thread::sleep_for(std::chrono::milliseconds(150));
                if (!aliveFlag->load(std::memory_order_acquire)) {
                    self->m_reinitInProgress.store(false, std::memory_order_release);
                    return;
                }
                self->terminateInternal();
                if (!aliveFlag->load(std::memory_order_acquire)) {
                    self->m_reinitInProgress.store(false, std::memory_order_release);
                    return;
                }
                if (!self->initialize(static_cast<uint32_t>(sr),
                                      static_cast<size_t>(ch))) {
                    if (aliveFlag->load(std::memory_order_acquire))
                        self->initialize(48000, 2); // fallback to safe defaults
                }
                LOGI("Oboe stream auto-reconnected after device change");
                self->m_reinitInProgress.store(false, std::memory_order_release);
            }).detach();
        }
    }

private:
    void terminateInternal() {
        if (m_stream) {
            // Synchronous stop — waits for any in-flight callback to finish
            m_stream->stop();
            m_stream->close();
            m_stream = nullptr;
        }
        m_streamRunning = false;
    }

    core::RingBuffer<double>*          m_ringBuffer;
    std::shared_ptr<oboe::AudioStream> m_stream;
    std::atomic<bool>                  m_streamRunning{false};
    size_t                             m_numChannels{2};
    std::vector<double>                m_tempBuffer;
    // Shared lifetime flag — detached reconnect threads check this before
    // dereferencing 'this', preventing use-after-free on fast shutdown.
    std::shared_ptr<std::atomic<bool>> m_alive;
    // Prevents multiple concurrent reconnect threads on rapid disconnect events
    // (common on Xiaomi MIUI when Bluetooth disconnects fire in bursts).
    std::atomic<bool> m_reinitInProgress{false};
};

} // namespace hw
} // namespace audio_engine
