#pragma once
// Host-side stand-in for Oboe used by the native regression tests. A stream
// runs a thread that calls the data callback in bursts at roughly real time
// and records what was rendered. Android builds always use the real Oboe.
#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

namespace oboe {
enum class Result { OK, ErrorDisconnected, ErrorInternal, ErrorInvalidState };
enum class Direction { Output };
enum class PerformanceMode { None, PowerSaving, LowLatency };
enum class SharingMode { Shared, Exclusive };
enum class AudioFormat { Float };
enum class Usage { Media };
enum class ContentType { Music };
enum class SampleRateConversionQuality { None, Fastest, Low, Medium, High, Best };
enum class StreamState { Open, Started, Paused, Stopped, Closed, Disconnected };
enum class DataCallbackResult { Continue, Stop };
inline const char* convertToText(Result) { return "test"; }

class AudioStream;
class AudioStreamDataCallback {
public:
    virtual ~AudioStreamDataCallback() = default;
    virtual DataCallbackResult onAudioReady(AudioStream*, void*, int32_t) = 0;
};
class AudioStreamErrorCallback {
public:
    virtual ~AudioStreamErrorCallback() = default;
    virtual void onErrorAfterClose(AudioStream*, Result) {}
};

// Test controls.
inline std::atomic<bool> failOpen{false};
inline std::atomic<int> maxChannels{8};
inline std::atomic<bool> suspendCallbacks{false};
inline std::atomic<int> burstFrames{256};
inline std::atomic<int> openCount{0};
inline std::mutex captureMutex;
inline std::vector<float> captured;
inline std::atomic<bool> capture{false};

class AudioStream {
public:
    AudioStream(int rate, int channels, AudioStreamDataCallback* cb, std::shared_ptr<AudioStreamErrorCallback> err)
        : m_rate(rate), m_channels(channels), m_callback(cb), m_error(std::move(err)) {}
    ~AudioStream() { halt(); }

    int getSampleRate() const { return m_rate; }
    int getChannelCount() const { return m_channels; }
    AudioFormat getFormat() const { return AudioFormat::Float; }
    StreamState getState() const { return m_state.load(); }

    Result requestStart() {
        if (m_state == StreamState::Closed || m_state == StreamState::Disconnected) return Result::ErrorDisconnected;
        if (m_state == StreamState::Started) return Result::OK;
        halt();
        m_state = StreamState::Started;
        m_running = true;
        m_worker = std::thread([this] { run(); });
        return Result::OK;
    }
    Result requestPause() { halt(); if (m_state == StreamState::Started) m_state = StreamState::Paused; return Result::OK; }
    Result requestStop() { halt(); if (m_state != StreamState::Closed) m_state = StreamState::Stopped; return Result::OK; }
    Result stop() { return requestStop(); }
    Result close() { halt(); m_state = StreamState::Closed; return Result::OK; }

    /** Simulates a device disconnect: Oboe closes the stream, then reports it. */
    void disconnect() {
        halt();
        m_state = StreamState::Closed;
        if (m_error) m_error->onErrorAfterClose(this, Result::ErrorDisconnected);
    }

private:
    void halt() {
        m_running = false;
        if (m_worker.joinable() && m_worker.get_id() != std::this_thread::get_id()) m_worker.join();
    }
    void run() {
        std::vector<float> out;
        while (m_running) {
            const int frames = burstFrames.load();
            out.assign(static_cast<size_t>(frames * m_channels), 0.0f);
            if (!suspendCallbacks) {
                m_callback->onAudioReady(this, out.data(), frames);
                if (capture) {
                    std::lock_guard<std::mutex> lock(captureMutex);
                    captured.insert(captured.end(), out.begin(), out.end());
                }
            }
            std::this_thread::sleep_for(std::chrono::microseconds(1000000LL * frames / m_rate));
        }
    }

    int m_rate;
    int m_channels;
    AudioStreamDataCallback* m_callback;
    std::shared_ptr<AudioStreamErrorCallback> m_error;
    std::atomic<StreamState> m_state{StreamState::Open};
    std::atomic<bool> m_running{false};
    std::thread m_worker;
};

inline std::weak_ptr<AudioStream> lastStream;

class AudioStreamBuilder {
public:
    AudioStreamBuilder* setDirection(Direction) { return this; }
    AudioStreamBuilder* setPerformanceMode(PerformanceMode) { return this; }
    AudioStreamBuilder* setSharingMode(SharingMode) { return this; }
    AudioStreamBuilder* setUsage(Usage) { return this; }
    AudioStreamBuilder* setContentType(ContentType) { return this; }
    AudioStreamBuilder* setFormat(AudioFormat) { return this; }
    AudioStreamBuilder* setFormatConversionAllowed(bool) { return this; }
    AudioStreamBuilder* setChannelConversionAllowed(bool) { return this; }
    AudioStreamBuilder* setSampleRateConversionQuality(SampleRateConversionQuality) { return this; }
    AudioStreamBuilder* setChannelCount(int c) { m_channels = c; return this; }
    AudioStreamBuilder* setSampleRate(int r) { m_rate = r; return this; }
    AudioStreamBuilder* setDataCallback(AudioStreamDataCallback* c) { m_callback = c; return this; }
    AudioStreamBuilder* setErrorCallback(std::shared_ptr<AudioStreamErrorCallback> c) { m_error = std::move(c); return this; }
    Result openStream(std::shared_ptr<AudioStream>& stream) {
        if (failOpen || m_channels > maxChannels) return Result::ErrorInternal;
        stream = std::make_shared<AudioStream>(m_rate, m_channels, m_callback, m_error);
        lastStream = stream;
        ++openCount;
        return Result::OK;
    }

private:
    int m_rate = 48000;
    int m_channels = 2;
    AudioStreamDataCallback* m_callback = nullptr;
    std::shared_ptr<AudioStreamErrorCallback> m_error;
};
} // namespace oboe
