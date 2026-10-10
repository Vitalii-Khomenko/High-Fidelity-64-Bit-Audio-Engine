#pragma once

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <deque>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

#include <sys/resource.h>
#include <unistd.h>

#include "AudioBuffer.h"
#include "../decoders/FileSource.h"
#include "../decoders/IAudioDecoder.h"
#include "../dsp/ChannelMixer.h"
#include "../dsp/Crossfeed.h"
#include "../dsp/ParametricEq.h"
#include "../dsp/SpectrumAnalyzer.h"
#include "../dsp/TimeStretchProcessor.h"
#include "../dsp/TruePeakLimiter.h"
#include "../hw/OboeOutput.h"

namespace audio_engine {
namespace core {

enum class PlayerState : int {
    Idle = 0,     // nothing loaded
    Paused = 1,   // loaded, not playing
    Playing = 2,
    Ended = 3,    // reached the end and the output has drained
    Error = 4,    // output could not be opened
};

struct TrackInfo {
    uint64_t serial = 0;
    uint32_t sampleRate = 0;
    uint32_t channels = 0;
    uint32_t bitsPerSample = 0;
    uint64_t totalFrames = 0;
    uint32_t dsdRate = 0;
    int codec = 0;
    double gainDb = 0.0;   // applied ReplayGain
};

/**
 * Playback engine: one decode thread feeding OboeOutput.
 *
 *   decoder -> EQ (+headroom) -> ReplayGain -> [time stretch] -> downmix
 *           -> crossfeed -> true-peak limiter -> ring
 *   ring -> volume / fades / sample-peak guard -> device       (Oboe callback)
 *
 * Public methods may be called from any thread. Mutators are serialised by
 * m_control; getters never wait for decoding or I/O.
 *
 * Position is derived from frames actually consumed by the device callback,
 * through a list of segments that map output frames to source frames. Each
 * seek, speed change and gapless track switch starts a segment at the output
 * frame where it becomes audible, so the reported position and the "track
 * changed" event follow what is heard, not what has been decoded.
 */
class AudioPlayer {
public:
    static constexpr size_t kChunkFrames = 1024;
    static constexpr double kBufferSeconds = 0.3;
    static constexpr double kEdgeRampSeconds = 0.004;
    static constexpr int kPauseFadeMs = 40;
    static constexpr int kSwitchFadeMs = 25;

    AudioPlayer() : m_output(std::make_unique<hw::OboeOutput>()) {}
    ~AudioPlayer() {
        std::lock_guard<std::mutex> lock(m_control);
        stopDecodeThread();
        m_output.reset();
    }

    AudioPlayer(const AudioPlayer&) = delete;
    AudioPlayer& operator=(const AudioPlayer&) = delete;

    // ── Loading ──────────────────────────────────────────────────────────────

    /** Replaces the current track. Leaves the player paused at frame 0. */
    bool load(std::unique_ptr<decoders::IAudioDecoder> decoder, double gainLinear) {
        if (!isUsable(decoder.get())) return false;
        watchIo(*decoder);
        std::lock_guard<std::mutex> lock(m_control);
        stopDecodeThread();
        if (m_output->isRunRequested()) m_output->stop(kSwitchFadeMs);
        clearNextSlot();
        m_prevDecoder.reset();
        m_revertNext.store(false, std::memory_order_release);

        const TrackInfo info = makeInfo(*decoder, gainLinear);
        const bool sameFormat = m_output->isConfigured() &&
            m_output->sampleRate() == info.sampleRate && m_decoderChannels == info.channels;
        m_pendingFrames = 0;
        m_pending.clear();
        if (sameFormat) {
            m_output->flush();
        } else if (!m_output->configure(info.sampleRate, static_cast<int>(info.channels))) {
            m_decoder.reset();
            m_loaded.store(false, std::memory_order_release);
            m_state.store(PlayerState::Error, std::memory_order_release);
            return false;
        }
        m_decoder = std::move(decoder);
        m_resync = false;
        m_loaded.store(true, std::memory_order_release);
        m_gainLinear = gainLinear;
        prepareDsp(info.sampleRate, info.channels);
        m_seekRequest.store(-1, std::memory_order_release);
        m_startRampRemaining = m_edgeRampFrames;
        {
            std::lock_guard<std::mutex> infoLock(m_infoMutex);
            m_current = info;
            m_segments.clear();
            m_segments.push_back(Segment{m_output->consumedFrames(), 0.0, currentRate(), info});
        }
        m_trackAdvanced.store(false, std::memory_order_release);
        m_state.store(PlayerState::Paused, std::memory_order_release);
        return true;
    }

    /** Queues the next track for a gapless (or near-gapless) transition. */
    void setNext(std::unique_ptr<decoders::IAudioDecoder> decoder, double gainLinear) {
        if (!isUsable(decoder.get())) return;
        watchIo(*decoder);
        std::lock_guard<std::mutex> lock(m_nextMutex);
        m_next = std::move(decoder);
        m_nextGain = gainLinear;
    }

    /**
     * Drops the queued next track. If the decoder already switched to it but
     * it is not audible yet, the switch is undone and the current track
     * continues from the audible frame.
     */
    void clearNext() {
        clearNextSlot();
        m_revertNext.store(true, std::memory_order_release);
    }

    bool hasNext() const {
        std::lock_guard<std::mutex> lock(m_nextMutex);
        return m_next != nullptr;
    }

    // ── Transport ────────────────────────────────────────────────────────────

    bool play() {
        std::lock_guard<std::mutex> lock(m_control);
        const PlayerState s = m_state.load(std::memory_order_acquire);
        // m_decoder belongs to the decode thread; only the flag is read here.
        if (!m_loaded.load(std::memory_order_acquire) || s == PlayerState::Idle || s == PlayerState::Error) return false;
        if (s == PlayerState::Playing) return true;
        if (s == PlayerState::Ended) seekLocked(0);
        startDecodeThread();
        if (!m_output->start()) {
            stopDecodeThread();
            m_state.store(PlayerState::Error, std::memory_order_release);
            return false;
        }
        m_state.store(PlayerState::Playing, std::memory_order_release);
        return true;
    }

    void pause() {
        std::lock_guard<std::mutex> lock(m_control);
        if (m_state.load(std::memory_order_acquire) != PlayerState::Playing) return;
        stopDecodeThread();
        m_output->stop(kPauseFadeMs);
        applyPendingSeek();
        // The decode thread may have reached the end while we waited.
        if (m_state.load(std::memory_order_acquire) == PlayerState::Playing) {
            m_state.store(PlayerState::Paused, std::memory_order_release);
        }
    }

    void stop() {
        std::lock_guard<std::mutex> lock(m_control);
        stopDecodeThread();
        if (m_output->isRunRequested()) m_output->stop(kPauseFadeMs);
        m_seekRequest.store(-1, std::memory_order_release);
        if (m_decoder) {
            seekAudible(0);
            m_state.store(PlayerState::Paused, std::memory_order_release);
        }
    }

    void seekToMs(double ms) {
        if (!std::isfinite(ms)) return;
        std::lock_guard<std::mutex> lock(m_control);
        if (!m_loaded.load(std::memory_order_acquire)) return;
        // Seek within the track being heard. The decoder may already be on the
        // next track (gapless look-ahead), and it belongs to the decode thread.
        TrackInfo audible;
        {
            std::lock_guard<std::mutex> infoLock(m_infoMutex);
            promoteLocked();
            audible = m_current;
        }
        const uint32_t sr = audible.sampleRate;
        const uint64_t total = audible.totalFrames;
        if (sr == 0) return;
        double frame = std::max(0.0, ms) * sr / 1000.0;
        if (total > 0) frame = std::min(frame, static_cast<double>(total > 1 ? total - 1 : 0));
        const int64_t target = static_cast<int64_t>(frame);
        if (m_threadRunning.load(std::memory_order_acquire)) {
            m_seekRequest.store(target, std::memory_order_release);
        } else {
            m_seekRequest.store(-1, std::memory_order_release);
            seekAudible(target);
            if (m_state.load(std::memory_order_acquire) == PlayerState::Ended) {
                m_state.store(PlayerState::Paused, std::memory_order_release);
            }
        }
    }

    // ── Controls ─────────────────────────────────────────────────────────────

    void setVolume(double linear) { m_output->setVolume(linear); }

    void setSpeed(double speed) {
        if (!std::isfinite(speed)) return;
        m_speed.store(std::clamp(speed, 0.5, 2.0), std::memory_order_release);
    }

    void setSpeedMode(int mode) {
        const int normalized = mode == 1 ? 1 : 0;
        if (m_speedMode.exchange(normalized, std::memory_order_acq_rel) != normalized) {
            m_speedModeDirty.store(true, std::memory_order_release);
        }
    }

    void setEqEnabled(bool enabled) {
        std::lock_guard<std::mutex> lock(m_eqMutex);
        m_eqEnabled = enabled;
        m_eqDirty.store(true, std::memory_order_release);
    }

    /** One slider of the five-band graphic EQ; switches the EQ to the graphic bands. */
    void setEqBandGain(size_t band, double gainDb) {
        if (band >= 5 || !std::isfinite(gainDb)) return;
        std::lock_guard<std::mutex> lock(m_eqMutex);
        m_graphic[band] = std::clamp(gainDb, -12.0, 12.0);
        m_eqBands = dsp::graphicBands(m_graphic.data());
        m_eqUserPreampDb = 0.0;
        m_eqDirty.store(true, std::memory_order_release);
    }

    /**
     * Any parametric EQ (graphic sliders, AutoEQ profile). The applied preamp
     * is the lower of [preampDb] and the cascade's own peak gain, so the EQ
     * never boosts above the source level.
     */
    void setEq(bool enabled, double preampDb, std::vector<dsp::EqBand> bands) {
        std::lock_guard<std::mutex> lock(m_eqMutex);
        m_eqEnabled = enabled;
        m_eqUserPreampDb = std::isfinite(preampDb) ? std::clamp(preampDb, -30.0, 0.0) : 0.0;
        m_eqBands = std::move(bands);
        m_eqDirty.store(true, std::memory_order_release);
    }

    void resetEq() {
        std::lock_guard<std::mutex> lock(m_eqMutex);
        m_eqEnabled = false;
        m_graphic.fill(0.0);
        m_eqBands.clear();
        m_eqUserPreampDb = 0.0;
        m_eqDirty.store(true, std::memory_order_release);
    }

    /** Headphone crossfeed preset (dsp::Crossfeed::Preset), stereo output only. */
    void setCrossfeed(int preset) {
        m_crossfeedPreset.store(std::clamp(preset, 0, 3), std::memory_order_release);
    }

    /** True-peak limiter at -1 dBTP in the decode path (on by default). */
    void setLimiter(bool enabled) { m_limiterWanted.store(enabled, std::memory_order_release); }

    /** Lowest gain the true-peak limiter applied since the last call (1 = untouched). */
    double takeLimiterMinGain() { return m_limiterMinGain.exchange(1.0, std::memory_order_acq_rel); }

    // ── Queries (never block on decoding) ────────────────────────────────────

    PlayerState state() const {
        const PlayerState s = m_state.load(std::memory_order_acquire);
        // A lost device that could not be reopened must not look like playback.
        if (s == PlayerState::Playing && m_output->hasFailed()) return PlayerState::Error;
        return s;
    }

    double positionMs() const {
        std::lock_guard<std::mutex> lock(m_infoMutex);
        promoteLocked();
        const uint32_t sr = m_current.sampleRate;
        if (sr == 0) return 0.0;
        const int64_t pending = m_seekRequest.load(std::memory_order_acquire);
        double frames = pending >= 0 ? static_cast<double>(pending) : sourceFrameLocked(m_output->consumedFrames());
        if (m_current.totalFrames > 0) frames = std::min(frames, static_cast<double>(m_current.totalFrames));
        return std::max(0.0, frames) * 1000.0 / sr;
    }

    double durationMs() const {
        std::lock_guard<std::mutex> lock(m_infoMutex);
        promoteLocked();
        return m_current.sampleRate ? m_current.totalFrames * 1000.0 / m_current.sampleRate : 0.0;
    }

    TrackInfo trackInfo() const {
        std::lock_guard<std::mutex> lock(m_infoMutex);
        promoteLocked();
        return m_current;
    }

    uint32_t outputSampleRate() const { return m_output->isConfigured() ? m_output->sampleRate() : 0; }
    int outputChannels() const { return m_output->isConfigured() ? m_output->channels() : 0; }
    uint64_t underrunCount() const { return m_output->underrunCount(); }

    /** Direct (bit-perfect) output; see hw::OboeOutput::setDirectResolver. */
    void setDirectOutput(hw::OboeOutput::DirectResolver resolver) { m_output->setDirectResolver(std::move(resolver)); }
    /** Reopens the output (e.g. the routed device changed), keeping position and queued audio. */
    void reopenOutput() { m_output->requestReopen(); }
    bool isDirectOutput() const { return m_output->isConfigured() && m_output->isDirect(); }
    int outputBits() const { return m_output->isConfigured() ? hw::bitsPerSample(m_output->encoding()) : 0; }
    bool outputIsFloat() const { return m_output->encoding() == hw::SampleEncoding::Float; }
    uint64_t inexactOutputSamples() const { return m_output->inexactSamples(); }

    /** True once after the audible track changed through a gapless transition. */
    bool consumeTrackAdvanced() {
        {
            std::lock_guard<std::mutex> lock(m_infoMutex);
            promoteLocked();
        }
        return m_trackAdvanced.exchange(false, std::memory_order_acq_rel);
    }

    void spectrum(float* bands, int count) {
        if (!bands || count <= 0) return;
        std::lock_guard<std::mutex> lock(m_spectrumMutex);
        std::fill(bands, bands + count, 0.0f);
        if (state() != PlayerState::Playing || !m_output->isConfigured()) {
            m_analyzer.decay(bands, count);
            return;
        }
        m_output->copySpectrum(m_spectrumSamples.data(), m_spectrumSamples.size());
        m_analyzer.analyze(m_spectrumSamples.data(), m_output->sampleRate(), bands, count);
    }

private:
    struct Segment {
        uint64_t startOut;      // consumed-frame index where it becomes audible
        double startSource;     // source frame at startOut
        double rate;            // source frames per output frame
        TrackInfo info;
    };

    /** A read waiting for a download gives up while the decode thread is being stopped. */
    void watchIo(decoders::IAudioDecoder& d) {
        if (auto* source = d.fileSource()) source->setAbortFlag(&m_abortIo);
    }

    static bool isUsable(const decoders::IAudioDecoder* d) {
        return d && d->getSampleRate() > 0 && d->getNumChannels() > 0 && d->getNumChannels() <= 8;
    }

    TrackInfo makeInfo(const decoders::IAudioDecoder& d, double gainLinear) {
        TrackInfo info;
        info.serial = ++m_serial;
        info.sampleRate = d.getSampleRate();
        info.channels = static_cast<uint32_t>(d.getNumChannels());
        info.bitsPerSample = d.getBitsPerSample();
        info.totalFrames = d.getTotalFrames();
        info.dsdRate = d.getDsdRate();
        info.codec = static_cast<int>(d.getCodec());
        info.gainDb = gainLinear > 0.0 ? 20.0 * std::log10(gainLinear) : 0.0;
        return info;
    }

    static bool isNormalSpeed(double speed) { return speed > 0.995 && speed < 1.005; }
    double currentRate() const {
        const double speed = m_speed.load(std::memory_order_acquire);
        return isNormalSpeed(speed) ? 1.0 : speed;
    }

    // ── Position segments (m_infoMutex) ──────────────────────────────────────

    void promoteLocked() const {
        const uint64_t consumed = m_output->consumedFrames();
        while (m_segments.size() > 1 && m_segments[1].startOut <= consumed) m_segments.pop_front();
        if (!m_segments.empty() && m_segments.front().info.serial != m_current.serial) {
            m_current = m_segments.front().info;
            m_trackAdvanced.store(true, std::memory_order_release);
        }
    }

    double sourceFrameLocked(uint64_t outFrame) const {
        if (m_segments.empty()) return 0.0;
        const Segment* seg = &m_segments.front();
        for (const Segment& s : m_segments) {
            if (s.startOut <= outFrame) seg = &s; else break;
        }
        const double delta = outFrame > seg->startOut ? static_cast<double>(outFrame - seg->startOut) : 0.0;
        return seg->startSource + delta * seg->rate;
    }

    /** Source frame of the newest audio written into the ring. */
    double writeHeadSourceFrame() {
        std::lock_guard<std::mutex> lock(m_infoMutex);
        if (m_segments.empty()) return 0.0;
        const Segment& last = m_segments.back();
        const uint64_t written = m_output->writtenFrames() + outputLatency();
        const double delta = written > last.startOut ? static_cast<double>(written - last.startOut) : 0.0;
        return last.startSource + delta * last.rate;
    }

    double audibleSourceFrame() {
        std::lock_guard<std::mutex> lock(m_infoMutex);
        promoteLocked();
        return sourceFrameLocked(m_output->consumedFrames());
    }

    void resetSegments(double sourceFrame, const TrackInfo& info) {
        std::lock_guard<std::mutex> lock(m_infoMutex);
        m_segments.clear();
        m_segments.push_back(Segment{m_output->writtenFrames(), sourceFrame, currentRate(), info});
        promoteLocked();
    }

    void appendSegment(uint64_t startOut, double sourceFrame, double rate, const TrackInfo& info) {
        std::lock_guard<std::mutex> lock(m_infoMutex);
        while (m_segments.size() > 64) m_segments.pop_front();
        m_segments.push_back(Segment{startOut, sourceFrame, rate, info});
    }

    TrackInfo decoderInfo() {
        std::lock_guard<std::mutex> lock(m_infoMutex);
        return m_segments.empty() ? m_current : m_segments.back().info;
    }

    // ── DSP (owned by the decode thread while it runs) ───────────────────────

    void prepareDsp(uint32_t sampleRate, uint32_t channels) {
        m_decoderChannels = channels;
        m_outChannels = static_cast<uint32_t>(m_output->channels());
        m_downmix.configure(channels);
        m_eq.prepare(sampleRate);
        m_eqDirty.store(true, std::memory_order_release);
        applyEq();
        m_crossfeed.prepare(sampleRate);
        m_crossfeed.setPreset(static_cast<dsp::Crossfeed::Preset>(m_crossfeedPreset.load(std::memory_order_acquire)));
        m_limiter.prepare(sampleRate, m_outChannels);
        m_limiterOn = m_limiterWanted.load(std::memory_order_acquire);
        m_stretch.prepare(sampleRate, channels);
        m_stretch.setMode(m_speedMode.load(std::memory_order_acquire) == 1
            ? dsp::TimeStretchMode::Speech : dsp::TimeStretchMode::Music);
        m_speedModeDirty.store(false, std::memory_order_release);
        m_stretch.setSpeed(m_speed.load(std::memory_order_acquire));
        m_appliedSpeed = m_speed.load(std::memory_order_acquire);
        m_normalPath = isNormalSpeed(m_appliedSpeed);
        m_inputExhausted = false;
        m_source = AudioBuffer(channels, kChunkFrames * 2, sampleRate);
        m_work.assign(kChunkFrames * 4 * channels, 0.0);
        m_targetFrames = static_cast<size_t>(sampleRate * kBufferSeconds);
        m_edgeRampFrames = std::max<size_t>(1, static_cast<size_t>(sampleRate * kEdgeRampSeconds));
    }

    void applyEq() {
        if (!m_eqDirty.exchange(false, std::memory_order_acq_rel)) return;
        std::vector<dsp::EqBand> bands;
        double userPreamp = 0.0;
        bool enabled;
        {
            std::lock_guard<std::mutex> lock(m_eqMutex);
            bands = m_eqBands;
            userPreamp = m_eqUserPreampDb;
            enabled = m_eqEnabled;
        }
        if (!enabled) bands.clear();
        const bool wasEmpty = m_eq.empty();
        m_eq.setBands(std::move(bands));
        if (wasEmpty) m_eq.reset();
        // Headroom: the cascade's own peak gain, or the profile's preamp if lower.
        m_eqPreamp = enabled ? std::pow(10.0, std::min(userPreamp, -m_eq.peakGainDb()) / 20.0) : 1.0;
    }

    /** Crossfeed preset and limiter switch changes, picked up by the decode thread. */
    void applyOutputDsp() {
        const auto preset = static_cast<dsp::Crossfeed::Preset>(m_crossfeedPreset.load(std::memory_order_acquire));
        if (preset != m_crossfeed.preset()) m_crossfeed.setPreset(preset);
        const bool wanted = m_limiterWanted.load(std::memory_order_acquire);
        if (wanted == m_limiterOn) return;
        if (!wanted) flushLimiter();   // hand out what it holds before bypassing it
        m_limiter.reset();
        m_limiterOn = wanted;
    }

    /** Frames processed but still inside the limiter's look-ahead. */
    uint64_t outputLatency() const { return m_limiterOn ? m_limiter.latency() : 0; }

    /** Writes the limiter's withheld frames (end of a track, or switching it off). */
    void flushLimiter() {
        if (!m_limiterOn || m_limiter.latency() == 0) return;
        m_limited.resize(m_limiter.latency() * m_outChannels + 16);
        const size_t n = m_limiter.flush(m_limited.data());
        writeOut(m_limited.data(), n);
    }

    void clearNextSlot() {
        std::lock_guard<std::mutex> lock(m_nextMutex);
        m_next.reset();
    }

    // A seek requested while the thread was stopping is applied synchronously.
    void applyPendingSeek() {
        const int64_t pending = m_seekRequest.exchange(-1, std::memory_order_acq_rel);
        if (pending >= 0) seekAudible(pending);
    }

    /** True while the decoder runs ahead on the next track but its first sample is not audible yet. */
    bool switchPending() const {
        return m_prevDecoder && m_output->consumedFrames() < m_prevBoundary;
    }

    /** Puts the audible track back as the decoder; the look-ahead track is re-queued or dropped. */
    void revertSwitch(bool keepNext) {
        if (keepNext) {
            std::lock_guard<std::mutex> lock(m_nextMutex);
            if (!m_next) {
                m_decoder->seekToFrame(0);  // it had already decoded its look-ahead
                m_next = std::move(m_decoder);
                m_nextGain = m_gainLinear;
            }
        }
        m_decoder = std::move(m_prevDecoder);
        m_gainLinear = m_prevGain;
    }

    /** Seeks the track that is being heard (frame in its own timeline). */
    void seekAudible(int64_t frame) {
        if (switchPending()) {
            const TrackInfo info = m_prevInfo;
            revertSwitch(!m_revertNext.exchange(false, std::memory_order_acq_rel));
            seekLocked(frame, &info);
        } else {
            seekLocked(frame);
        }
    }

    void seekLocked(int64_t frame, const TrackInfo* info = nullptr) {
        if (!m_decoder) return;
        m_prevDecoder.reset();
        m_resync = false;   // a seek re-syncs the decoder anyway
        m_decoder->seekToFrame(static_cast<uint64_t>(std::max<int64_t>(frame, 0)));
        m_output->flush();
        m_pendingFrames = 0;
        m_pending.clear();
        m_eq.reset();
        m_crossfeed.reset();
        m_limiter.reset();
        m_stretch.reset();
        m_stretch.setSpeed(m_speed.load(std::memory_order_acquire));
        m_inputExhausted = false;
        m_startRampRemaining = m_edgeRampFrames;
        // Clear only the request we served; a newer one stays queued.
        int64_t served = frame;
        m_seekRequest.compare_exchange_strong(served, -1, std::memory_order_acq_rel);
        resetSegments(static_cast<double>(frame), info ? *info : decoderInfo());
    }

    // ── Decode thread ────────────────────────────────────────────────────────

    void startDecodeThread() {
        if (m_threadRunning.load(std::memory_order_acquire)) return;
        if (m_thread.joinable()) m_thread.join();
        m_stopThread.store(false, std::memory_order_release);
        m_threadRunning.store(true, std::memory_order_release);
        m_thread = std::thread([this] { decodeLoop(); });
    }

    void stopDecodeThread() {
        m_stopThread.store(true, std::memory_order_release);
        m_abortIo.store(true, std::memory_order_release);
        if (m_thread.joinable()) m_thread.join();
        m_abortIo.store(false, std::memory_order_release);
        m_threadRunning.store(false, std::memory_order_release);
        // A read cut short leaves the decoder mid-frame: restart it at the audible frame.
        if (m_decoder && m_decoder->fileSource() && m_decoder->fileSource()->consumeInterrupted()) m_resync = true;
    }

    bool stopRequested() const { return m_stopThread.load(std::memory_order_acquire); }

    void decodeLoop() {
        // Above normal priority; ignored where not permitted.
        setpriority(PRIO_PROCESS, static_cast<id_t>(gettid()), -16);
        if (m_resync) {
            m_resync = false;
            seekAudible(static_cast<int64_t>(audibleSourceFrame()));
        }
        while (!stopRequested()) {
            applyEq();
            applyOutputDsp();
            if (m_prevDecoder && !switchPending()) m_prevDecoder.reset();  // boundary is audible now
            if (m_revertNext.exchange(false, std::memory_order_acq_rel) && switchPending()) {
                // The next track was cleared before anyone heard it.
                const int64_t audible = static_cast<int64_t>(audibleSourceFrame());
                const TrackInfo info = m_prevInfo;
                revertSwitch(false);
                seekLocked(audible, &info);
                continue;
            }
            const int64_t seek = m_seekRequest.load(std::memory_order_acquire);
            if (seek >= 0) {
                seekAudible(seek);
                continue;
            }
            if (m_pendingFrames > 0 && !flushPending()) continue;
            const double speed = m_speed.load(std::memory_order_acquire);
            const bool normal = isNormalSpeed(speed);
            const bool modeChanged = m_speedModeDirty.exchange(false, std::memory_order_acq_rel);
            if (modeChanged) {
                m_stretch.setMode(m_speedMode.load(std::memory_order_acquire) == 1
                    ? dsp::TimeStretchMode::Speech : dsp::TimeStretchMode::Music);
            }
            if (normal != m_normalPath || (modeChanged && !normal)) {
                // Switching between the bit-exact path and the time-stretcher
                // (or between stretch profiles) restarts from the audible frame.
                m_normalPath = normal;
                m_appliedSpeed = speed;
                seekAudible(static_cast<int64_t>(audibleSourceFrame()));
                continue;
            }
            if (!normal && speed != m_appliedSpeed) {
                const uint64_t at = m_output->writtenFrames() + outputLatency();
                appendSegment(at, writeHeadSourceFrame(), speed, decoderInfo());
                m_stretch.setSpeed(speed);
                m_appliedSpeed = speed;
            }

            if (m_output->bufferedFrames() >= m_targetFrames || m_output->writableFrames() < kChunkFrames) {
                std::this_thread::sleep_for(std::chrono::milliseconds(4));
                continue;
            }

            const size_t produced = normal ? decodeNormal() : decodeStretched(speed);
            if (produced > 0) continue;
            if (m_seekRequest.load(std::memory_order_acquire) >= 0) continue;
            if (switchPending()) {
                // A short next track already ended while the previous one is
                // still audible: wait until that boundary is heard, so seeks
                // and clearNext() keep applying to the right track.
                std::this_thread::sleep_for(std::chrono::milliseconds(4));
                continue;
            }
            if (!m_normalPath && !m_stretch.isDrained()) continue;
            if (finishOrAdvance()) continue;
            if (!stopRequested() && (m_seekRequest.load(std::memory_order_acquire) >= 0 ||
                                     m_revertNext.load(std::memory_order_acquire))) continue;
            break;
        }
        m_threadRunning.store(false, std::memory_order_release);
    }

    size_t decodeNormal() {
        size_t frames = m_decoder->readFrames(m_source, kChunkFrames);
        if (frames == 0) {
            // The limiter still holds the last frames: the boundary is after them.
            if (!switchToNextGapless(m_output->writtenFrames() + outputLatency())) return 0;
            frames = m_decoder->readFrames(m_source, kChunkFrames);
            if (frames == 0) return 0;
        }
        interleave(m_source, frames, m_work.data());
        applyTrackDsp(m_work.data(), frames);
        emit(m_work.data(), frames);
        return frames;
    }

    size_t decodeStretched(double speed) {
        const size_t want = std::min(kChunkFrames * 2, m_output->writableFrames());
        while (!m_inputExhausted && m_stretch.getAvailableFrames() < want && !stopRequested()) {
            const size_t frames = m_decoder->readFrames(m_source, kChunkFrames);
            if (frames == 0) {
                if (m_seekRequest.load(std::memory_order_acquire) >= 0) return 0;
                if (switchPending()) break;  // decide after the earlier boundary is heard
                // Keep feeding the stretcher across a gapless boundary.
                const uint64_t boundary = m_output->writtenFrames() + outputLatency() + m_stretch.getAvailableFrames();
                if (switchToNextGapless(boundary)) continue;
                m_inputExhausted = true;
                m_stretch.markEndOfInput();
                break;
            }
            interleave(m_source, frames, m_work.data());
            // EQ and ReplayGain belong to the source track, so they run before
            // the stretcher; a gapless switch then changes gain at the right sample.
            applyTrackDsp(m_work.data(), frames);
            m_stretch.appendInterleaved(m_work.data(), frames);
        }
        (void)speed;
        const size_t rendered = m_stretch.renderInterleaved(m_work.data(), want);
        if (rendered > 0) emit(m_work.data(), rendered);
        return rendered;
    }

    // NaN/Inf from a damaged float file would poison the EQ filter state and
    // the output limiter; they become silence. Finite over-full-scale values
    // are kept (the limiter handles them).
    void interleave(const AudioBuffer& src, size_t frames, double* dst) const {
        const size_t ch = m_decoderChannels;
        for (size_t c = 0; c < ch; ++c) {
            const double* in = src.getReadPointer(c);
            for (size_t f = 0; f < frames; ++f) dst[f * ch + c] = std::isfinite(in[f]) ? in[f] : 0.0;
        }
    }

    void applyTrackDsp(double* data, size_t frames) {
        const size_t ch = m_decoderChannels;
        m_eq.processInterleaved(data, frames, ch);
        const double gain = m_gainLinear * m_eqPreamp;
        if (gain != 1.0) {
            for (size_t i = 0; i < frames * ch; ++i) data[i] *= gain;
        }
    }

    // Edge ramp, downmix, crossfeed, limiter, then into the ring.
    void emit(double* data, size_t frames) {
        const size_t ch = m_decoderChannels;
        if (m_startRampRemaining > 0) {
            const size_t ramp = std::min(frames, m_startRampRemaining);
            const size_t offset = m_edgeRampFrames - m_startRampRemaining;
            for (size_t f = 0; f < ramp; ++f) {
                const double g = static_cast<double>(offset + f + 1) / static_cast<double>(m_edgeRampFrames);
                for (size_t c = 0; c < ch; ++c) data[f * ch + c] *= g;
            }
            m_startRampRemaining -= ramp;
        }
        if (m_outChannels == 2 && ch > 2) m_downmix.process(data, frames);
        if (m_outChannels == 2) m_crossfeed.process(data, frames);
        if (m_limiterOn) {
            frames = m_limiter.process(data, frames, data);   // fewer only while its look-ahead fills
            const double g = m_limiter.takeMinGain();
            if (g < 1.0) {
                double cur = m_limiterMinGain.load(std::memory_order_relaxed);
                while (g < cur && !m_limiterMinGain.compare_exchange_weak(cur, g, std::memory_order_acq_rel)) {}
            }
        }
        writeOut(data, frames);
    }

    /** Writes processed frames into the ring; what a pause interrupts is kept for the restart. */
    void writeOut(const double* data, size_t frames) {
        if (m_pendingFrames > 0) {
            // Earlier frames are still waiting: keep the order.
            m_pending.insert(m_pending.end(), data, data + frames * m_outChannels);
            m_pendingFrames += frames;
            return;
        }
        size_t done = 0;
        while (done < frames && !stopRequested() && m_seekRequest.load(std::memory_order_acquire) < 0) {
            const size_t n = m_output->write(data + done * m_outChannels, frames - done);
            done += n;
            if (n == 0) std::this_thread::sleep_for(std::chrono::milliseconds(2));
        }
        if (done < frames && m_seekRequest.load(std::memory_order_acquire) < 0) {
            // Stopped (pause) with samples already decoded: keep them for the
            // next start, otherwise resume would skip this part of the block.
            const size_t samples = (frames - done) * m_outChannels;
            m_pending.insert(m_pending.end(), data + done * m_outChannels, data + done * m_outChannels + samples);
            m_pendingFrames += frames - done;
        }
    }

    /** Writes what a pause left behind. True when nothing is pending any more. */
    bool flushPending() {
        size_t done = 0;
        while (done < m_pendingFrames && !stopRequested() && m_seekRequest.load(std::memory_order_acquire) < 0) {
            const size_t n = m_output->write(m_pending.data() + done * m_outChannels, m_pendingFrames - done);
            done += n;
            if (n == 0) std::this_thread::sleep_for(std::chrono::milliseconds(2));
        }
        if (done > 0) {
            m_pending.erase(m_pending.begin(), m_pending.begin() + static_cast<std::ptrdiff_t>(done * m_outChannels));
            m_pendingFrames -= done;
        }
        return m_pendingFrames == 0;
    }

    /** Same-format next track: swap decoders without touching the output. */
    bool switchToNextGapless(uint64_t boundaryOut) {
        // One look-ahead switch at a time: the previous decoder must stay
        // until its own boundary has been heard.
        if (switchPending()) return false;
        std::unique_ptr<decoders::IAudioDecoder> next;
        double gain = 1.0;
        {
            std::lock_guard<std::mutex> lock(m_nextMutex);
            if (!m_next || m_next->getSampleRate() != m_decoder->getSampleRate() ||
                m_next->getNumChannels() != m_decoderChannels) {
                return false;
            }
            next = std::move(m_next);
            gain = m_nextGain;
        }
        const TrackInfo info = makeInfo(*next, gain);
        // Keep the finishing decoder until its last sample has been heard, so
        // a seek or clearNext() in the meantime still applies to it.
        m_revertNext.store(false, std::memory_order_release);
        m_prevInfo = decoderInfo();
        m_prevDecoder = std::move(m_decoder);
        m_prevGain = m_gainLinear;
        m_prevBoundary = boundaryOut;
        m_decoder = std::move(next);
        m_gainLinear = gain;
        appendSegment(boundaryOut, 0.0, m_normalPath ? 1.0 : m_appliedSpeed, info);
        return true;
    }

    /**
     * End of the current decoder. A next track with a different format is
     * played after the queue drains and the stream is reopened; otherwise the
     * player ends. Returns true when playback continues.
     */
    bool finishOrAdvance() {
        // Drain first and only then take the next track, so clearNext() or a
        // seek during the drain still cancels a format-change transition.
        flushLimiter();
        if (!drainOutput()) return false;
        m_prevDecoder.reset();
        std::unique_ptr<decoders::IAudioDecoder> next;
        double gain = 1.0;
        {
            std::lock_guard<std::mutex> lock(m_nextMutex);
            next = std::move(m_next);
            gain = m_nextGain;
        }
        if (!next) {
            m_output->stop(5);
            if (!stopRequested()) m_state.store(PlayerState::Ended, std::memory_order_release);
            return false;
        }
        const TrackInfo info = makeInfo(*next, gain);
        if (!m_output->configure(info.sampleRate, static_cast<int>(info.channels)) || !m_output->start()) {
            m_state.store(PlayerState::Error, std::memory_order_release);
            return false;
        }
        m_decoder = std::move(next);
        m_gainLinear = gain;
        m_pendingFrames = 0;
        m_pending.clear();
        prepareDsp(info.sampleRate, info.channels);
        m_startRampRemaining = 0;
        {
            std::lock_guard<std::mutex> lock(m_infoMutex);
            m_segments.clear();
            m_segments.push_back(Segment{m_output->consumedFrames(), 0.0, currentRate(), info});
        }
        return true;
    }

    /** Waits until the device consumed everything written. False if stopped, seeking or reverting. */
    bool drainOutput() {
        while (!stopRequested()) {
            if (m_seekRequest.load(std::memory_order_acquire) >= 0) return false;
            if (m_revertNext.load(std::memory_order_acquire) && switchPending()) return false;
            if (m_output->consumedFrames() >= m_output->writtenFrames()) return true;
            std::this_thread::sleep_for(std::chrono::milliseconds(3));
        }
        return false;
    }

    // ── Members ──────────────────────────────────────────────────────────────

    std::unique_ptr<hw::OboeOutput> m_output;

    std::mutex m_control;
    std::thread m_thread;
    std::atomic<bool> m_stopThread{false};
    std::atomic<bool> m_threadRunning{false};
    std::atomic<PlayerState> m_state{PlayerState::Idle};
    std::atomic<int64_t> m_seekRequest{-1};
    std::atomic<double> m_speed{1.0};
    std::atomic<int> m_speedMode{0};
    std::atomic<bool> m_speedModeDirty{false};
    std::atomic<uint64_t> m_serial{0};

    std::atomic<bool> m_loaded{false};   // a decoder is installed (readable from any thread)
    std::atomic<bool> m_abortIo{false};  // raised while the decode thread is being stopped
    bool m_resync = false;               // m_control: re-seek before decoding again

    // Decode-thread state.
    std::unique_ptr<decoders::IAudioDecoder> m_decoder;
    std::vector<double> m_pending;       // decoded, processed, not yet written (pause mid-block)
    size_t m_pendingFrames = 0;
    uint32_t m_decoderChannels = 0;
    uint32_t m_outChannels = 0;
    double m_gainLinear = 1.0;
    double m_eqPreamp = 1.0;
    double m_appliedSpeed = 1.0;
    bool m_normalPath = true;
    bool m_inputExhausted = false;
    size_t m_targetFrames = 0;
    size_t m_edgeRampFrames = 1;
    size_t m_startRampRemaining = 0;
    AudioBuffer m_source{1, 1, 48000};
    std::vector<double> m_work;
    dsp::ParametricEq m_eq;
    dsp::TimeStretchProcessor m_stretch;
    dsp::StereoDownmix m_downmix;
    dsp::Crossfeed m_crossfeed;
    dsp::TruePeakLimiter m_limiter;
    bool m_limiterOn = true;
    std::vector<double> m_limited;

    // Gapless look-ahead: the finished decoder until its tail is audible.
    std::unique_ptr<decoders::IAudioDecoder> m_prevDecoder;
    double m_prevGain = 1.0;
    TrackInfo m_prevInfo;
    uint64_t m_prevBoundary = 0;
    std::atomic<bool> m_revertNext{false};

    mutable std::mutex m_nextMutex;
    std::unique_ptr<decoders::IAudioDecoder> m_next;
    double m_nextGain = 1.0;

    std::mutex m_eqMutex;
    std::array<double, 5> m_graphic{};
    std::vector<dsp::EqBand> m_eqBands;
    double m_eqUserPreampDb = 0.0;
    bool m_eqEnabled = false;
    std::atomic<bool> m_eqDirty{true};

    std::atomic<int> m_crossfeedPreset{0};
    std::atomic<bool> m_limiterWanted{true};
    std::atomic<double> m_limiterMinGain{1.0};

    mutable std::mutex m_infoMutex;
    mutable TrackInfo m_current;
    mutable std::deque<Segment> m_segments;
    mutable std::atomic<bool> m_trackAdvanced{false};

    std::mutex m_spectrumMutex;
    dsp::SpectrumAnalyzer m_analyzer;
    std::array<float, dsp::SpectrumAnalyzer::kSize> m_spectrumSamples{};
};

} // namespace core
} // namespace audio_engine
