#include <jni.h>

#include <atomic>
#include <cstdio>
#include <cmath>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <unistd.h>

#include "../core/AudioPlayer.h"
#include "../decoders/DecoderFactory.h"
#include "../decoders/DurationProbe.h"
#include "../decoders/LoudnessScan.h"
#include "../decoders/RangeDecoder.h"
#include "../decoders/ReplayGainScanner.h"
#include "../tags/TagReader.h"

using audio_engine::core::AudioPlayer;
using audio_engine::core::TrackInfo;
namespace dec = audio_engine::decoders;

namespace {

// Each Kotlin AudioEngine owns one instance, addressed by an opaque id. The
// registry mutex only guards the map: a call copies the shared_ptr and works on
// its own reference, so a slow call never blocks others, and releasing one
// instance (e.g. by a service being destroyed) can never touch a newer one.
struct Instance {
    std::shared_ptr<AudioPlayer> player = std::make_shared<AudioPlayer>();
    // Guards nextGeneration together with publishing a pre-loaded decoder, so
    // "check generation, then setNext" cannot interleave with clearNext()/load().
    std::mutex nextLock;
    uint64_t nextGeneration = 0;

    void invalidateNext() {
        std::lock_guard<std::mutex> lock(nextLock);
        ++nextGeneration;
    }
    uint64_t generation() {
        std::lock_guard<std::mutex> lock(nextLock);
        return nextGeneration;
    }
};

std::mutex g_mutex;
std::unordered_map<jlong, std::shared_ptr<Instance>> g_instances;
jlong g_lastId = 0;

std::shared_ptr<Instance> instance(jlong id) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const auto it = g_instances.find(id);
    return it == g_instances.end() ? nullptr : it->second;
}

std::shared_ptr<AudioPlayer> player(jlong id) {
    auto inst = instance(id);
    return inst ? inst->player : nullptr;
}

struct OwnedFd {
    int fd;
    ~OwnedFd() { if (fd >= 0) ::close(fd); }
};

// Downloads in progress (DLNA streaming), registered by the app.
std::mutex g_streamsMutex;
std::unordered_map<jlong, std::shared_ptr<dec::StreamState>> g_streams;
jlong g_lastStream = 0;

std::shared_ptr<dec::StreamState> stream(jlong id) {
    if (id <= 0) return nullptr;
    std::lock_guard<std::mutex> lock(g_streamsMutex);
    const auto it = g_streams.find(id);
    return it == g_streams.end() ? nullptr : it->second;
}

/** Opens fd with the right decoder; a CUE track becomes a range of the file. */
std::unique_ptr<dec::IAudioDecoder> openDecoder(int fd, jlong startUs, jlong endUs, jlong streamId = 0) {
    return dec::RangeDecoder::wrap(dec::openDecoder(fd, stream(streamId)), startUs, endUs);
}

/**
 * Gain for a track: its ReplayGain tags, or else the app's measured values
 * (EBU R128 analysis, NaN when unknown) for files without tags.
 */
double gainFor(int fd, jint replayGainMode, const jdouble* fallback) {
    const auto mode = static_cast<dec::ReplayGainMode>(replayGainMode);
    if (mode == dec::ReplayGainMode::Off) return 1.0;
    dec::ReplayGainInfo info = dec::readReplayGain(fd);
    if (!info.hasTrack && !info.hasAlbum && fallback) {
        if (std::isfinite(fallback[0])) {
            info.hasTrack = true;
            info.trackGainDb = static_cast<float>(fallback[0]);
            info.trackPeak = std::isfinite(fallback[1]) ? static_cast<float>(fallback[1]) : 0.0f;
        }
        if (std::isfinite(fallback[2])) {
            info.hasAlbum = true;
            info.albumGainDb = static_cast<float>(fallback[2]);
            info.albumPeak = std::isfinite(fallback[3]) ? static_cast<float>(fallback[3]) : 0.0f;
        }
    }
    return dec::replayGainLinear(info, mode);
}

/** [trackGainDb, trackPeak, albumGainDb, albumPeak] from Kotlin, or all NaN. */
struct Fallback {
    jdouble v[4] = {NAN, NAN, NAN, NAN};
    Fallback(JNIEnv* env, jdoubleArray a) {
        if (a && env->GetArrayLength(a) >= 4) env->GetDoubleArrayRegion(a, 0, 4, v);
    }
};

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeCreate(JNIEnv*, jobject) {
    auto inst = std::make_shared<Instance>();
    std::lock_guard<std::mutex> lock(g_mutex);
    const jlong id = ++g_lastId;
    g_instances.emplace(id, std::move(inst));
    return id;
}

JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeRelease(JNIEnv*, jobject, jlong id) {
    std::shared_ptr<Instance> released;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        const auto it = g_instances.find(id);
        if (it == g_instances.end()) return;
        released = std::move(it->second);
        g_instances.erase(it);
    }
    released->invalidateNext();
    // Destroyed here (or by the last in-flight call) outside the mutex.
}

/** Takes ownership of fd. */
JNIEXPORT jboolean JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeLoad(JNIEnv* env, jobject, jlong id, jint fd, jint replayGainMode,
                                                      jlong startUs, jlong endUs, jdoubleArray fallbackGain, jlong streamId) {
    const Fallback fallback(env, fallbackGain);
    OwnedFd owned{fd};
    auto inst = instance(id);
    if (fd < 0 || !inst) return JNI_FALSE;
    inst->invalidateNext();  // any pre-load still being opened is stale now
    auto decoder = openDecoder(fd, startUs, endUs, streamId);
    if (!decoder) return JNI_FALSE;
    const double gain = gainFor(fd, replayGainMode, fallback.v);
    return inst->player->load(std::move(decoder), gain) ? JNI_TRUE : JNI_FALSE;
}

/** Takes ownership of fd. Ignored if load()/clearNext() happened meanwhile. */
JNIEXPORT jboolean JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeLoadNext(JNIEnv* env, jobject, jlong id, jint fd, jint replayGainMode,
                                                          jlong startUs, jlong endUs, jdoubleArray fallbackGain, jlong streamId) {
    const Fallback fallback(env, fallbackGain);
    OwnedFd owned{fd};
    auto inst = instance(id);
    if (fd < 0 || !inst) return JNI_FALSE;
    const uint64_t generation = inst->generation();
    auto decoder = openDecoder(fd, startUs, endUs, streamId);
    if (!decoder) return JNI_FALSE;
    const double gain = gainFor(fd, replayGainMode, fallback.v);
    {
        std::lock_guard<std::mutex> lock(inst->nextLock);
        if (generation != inst->nextGeneration) return JNI_FALSE;
        inst->player->setNext(std::move(decoder), gain);
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeClearNext(JNIEnv*, jobject, jlong id) {
    auto inst = instance(id);
    if (!inst) return;
    std::lock_guard<std::mutex> lock(inst->nextLock);
    ++inst->nextGeneration;
    inst->player->clearNext();
}

JNIEXPORT jboolean JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativePlay(JNIEnv*, jobject, jlong id) {
    auto p = player(id);
    return p && p->play() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativePause(JNIEnv*, jobject, jlong id) {
    if (auto p = player(id)) p->pause();
}

JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeStop(JNIEnv*, jobject, jlong id) {
    if (auto p = player(id)) p->stop();
}

JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeSeekTo(JNIEnv*, jobject, jlong id, jdouble positionMs) {
    if (auto p = player(id)) p->seekToMs(positionMs);
}

JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeSetVolume(JNIEnv*, jobject, jlong id, jdouble volume) {
    if (auto p = player(id)) p->setVolume(volume);
}

JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeSetSpeed(JNIEnv*, jobject, jlong id, jdouble speed) {
    if (auto p = player(id)) p->setSpeed(speed);
}

JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeSetSpeedMode(JNIEnv*, jobject, jlong id, jint mode) {
    if (auto p = player(id)) p->setSpeedMode(mode);
}

/** bands: [type, frequency, q, gainDb] per band (dsp::EqBandType ids). */
JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeSetEq(JNIEnv* env, jobject, jlong id, jboolean enabled, jdouble preampDb,
                                                       jdoubleArray bands) {
    std::vector<audio_engine::dsp::EqBand> list;
    if (bands) {
        const jsize n = env->GetArrayLength(bands);
        std::vector<jdouble> v(static_cast<size_t>(n));
        env->GetDoubleArrayRegion(bands, 0, n, v.data());
        for (jsize i = 0; i + 3 < n && list.size() < audio_engine::dsp::ParametricEq::kMaxBands; i += 4) {
            const int type = static_cast<int>(v[i]);
            if (type < 0 || type > 4 || !std::isfinite(v[i + 1]) || !std::isfinite(v[i + 2]) || !std::isfinite(v[i + 3])) continue;
            list.push_back({static_cast<audio_engine::dsp::EqBandType>(type), v[i + 1], v[i + 2], v[i + 3]});
        }
    }
    if (auto p = player(id)) p->setEq(enabled == JNI_TRUE, preampDb, std::move(list));
}

JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeSetCrossfeed(JNIEnv*, jobject, jlong id, jint preset) {
    if (auto p = player(id)) p->setCrossfeed(preset);
}

JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeSetLimiter(JNIEnv*, jobject, jlong id, jboolean enabled) {
    if (auto p = player(id)) p->setLimiter(enabled == JNI_TRUE);
}

JNIEXPORT jint JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeGetState(JNIEnv*, jobject, jlong id) {
    auto p = player(id);
    return p ? static_cast<jint>(p->state()) : 0;
}

JNIEXPORT jdouble JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeGetPositionMs(JNIEnv*, jobject, jlong id) {
    auto p = player(id);
    return p ? p->positionMs() : 0.0;
}

JNIEXPORT jdouble JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeGetDurationMs(JNIEnv*, jobject, jlong id) {
    auto p = player(id);
    return p ? p->durationMs() : 0.0;
}

JNIEXPORT jboolean JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeConsumeTrackAdvanced(JNIEnv*, jobject, jlong id) {
    auto p = player(id);
    return p && p->consumeTrackAdvanced() ? JNI_TRUE : JNI_FALSE;
}

/**
 * out: [sampleRate, channels, bitsPerSample, dsdRate, codec, outputRate,
 *       outputChannels, gainCentiDb, serialLow32, underruns]
 */
JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeGetTrackInfo(JNIEnv* env, jobject, jlong id, jintArray out) {
    if (!out) return;
    jint values[10] = {};
    if (auto p = player(id)) {
        const TrackInfo info = p->trackInfo();
        values[0] = static_cast<jint>(info.sampleRate);
        values[1] = static_cast<jint>(info.channels);
        values[2] = static_cast<jint>(info.bitsPerSample);
        values[3] = static_cast<jint>(info.dsdRate);
        values[4] = static_cast<jint>(info.codec);
        values[5] = static_cast<jint>(p->outputSampleRate());
        values[6] = static_cast<jint>(p->outputChannels());
        values[7] = static_cast<jint>(std::lround(info.gainDb * 100.0));
        values[8] = static_cast<jint>(info.serial & 0x7fffffff);
        values[9] = static_cast<jint>(std::min<uint64_t>(p->underrunCount(), 0x7fffffff));
    }
    const jsize n = std::min<jsize>(env->GetArrayLength(out), 10);
    env->SetIntArrayRegion(out, 0, n, values);
}

JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeGetSpectrum(JNIEnv* env, jobject, jlong id, jfloatArray out) {
    if (!out) return;
    const jsize count = std::min<jsize>(env->GetArrayLength(out), audio_engine::dsp::SpectrumAnalyzer::kMaxBands);
    float bands[audio_engine::dsp::SpectrumAnalyzer::kMaxBands] = {};
    if (auto p = player(id)) p->spectrum(bands, static_cast<int>(count));
    env->SetFloatArrayRegion(out, 0, count, bands);
}

// ── Streams: files still being downloaded ───────────────────────────────────

JNIEXPORT jlong JNICALL
Java_com_aiproject_musicplayer_NativeStreams_create(JNIEnv*, jclass, jlong totalBytes) {
    auto state = std::make_shared<dec::StreamState>();
    state->total.store(totalBytes > 0 ? static_cast<uint64_t>(totalBytes) : 0);
    std::lock_guard<std::mutex> lock(g_streamsMutex);
    const jlong id = ++g_lastStream;
    g_streams.emplace(id, std::move(state));
    return id;
}

/** status: 0 downloading, 1 complete, 2 failed. */
JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_NativeStreams_progress(JNIEnv*, jclass, jlong id, jlong availableBytes, jint status) {
    if (auto s = stream(id)) {
        s->available.store(availableBytes > 0 ? static_cast<uint64_t>(availableBytes) : 0, std::memory_order_release);
        s->status.store(status, std::memory_order_release);
    }
}

/** Forgets a stream; decoders that still read it keep their own reference. */
JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_NativeStreams_release(JNIEnv*, jclass, jlong id) {
    std::lock_guard<std::mutex> lock(g_streamsMutex);
    g_streams.erase(id);
}

// ── Tags (stateless; the caller keeps ownership of fd) ──────────────────────

/**
 * UTF-8 fields separated by NUL: title, artist, album, album artist, genre,
 * year, lyrics, track, track total, disc, disc total, has picture (0/1),
 * track gain dB, album gain dB (empty when absent). Strings go through a byte
 * array because NewStringUTF() cannot take 4-byte UTF-8 sequences.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_aiproject_musicplayer_NativeTags_readTags(JNIEnv* env, jclass, jint fd) {
    if (fd < 0) return nullptr;
    const auto t = audio_engine::tags::readTags(fd, false);
    auto number = [](int v) { return v > 0 ? std::to_string(v) : std::string(); };
    auto gain = [](bool has, float db) {
        if (!has) return std::string();
        char buf[32];
        std::snprintf(buf, sizeof(buf), "%.2f", db);
        return std::string(buf);
    };
    const std::string fields[] = {
        t.title, t.artist, t.album, t.albumArtist, t.genre, t.year, t.lyrics,
        number(t.track), number(t.trackTotal), number(t.disc), number(t.discTotal), t.hasPicture ? "1" : "0",
        gain(t.replayGain.hasTrack, t.replayGain.trackGainDb), gain(t.replayGain.hasAlbum, t.replayGain.albumGainDb),
    };
    std::string joined;
    for (size_t i = 0; i < sizeof(fields) / sizeof(fields[0]); ++i) {
        if (i) joined.push_back('\0');
        joined += fields[i];
    }
    jbyteArray out = env->NewByteArray(static_cast<jsize>(joined.size()));
    if (out) env->SetByteArrayRegion(out, 0, static_cast<jsize>(joined.size()), reinterpret_cast<const jbyte*>(joined.data()));
    return out;
}

/** The embedded cover (front cover preferred), or null. */
JNIEXPORT jbyteArray JNICALL
Java_com_aiproject_musicplayer_NativeTags_readPicture(JNIEnv* env, jclass, jint fd) {
    if (fd < 0) return nullptr;
    const auto t = audio_engine::tags::readTags(fd, true);
    const auto& data = t.picture.data;
    if (data.empty() || data.size() > 0x7fffffff) return nullptr;
    jbyteArray out = env->NewByteArray(static_cast<jsize>(data.size()));
    if (out) env->SetByteArrayRegion(out, 0, static_cast<jsize>(data.size()), reinterpret_cast<const jbyte*>(data.data()));
    return out;
}

/**
 * Decodes the whole file (or CUE range) and returns [integrated LUFS, true
 * peak dBTP, seconds], or null when it cannot be decoded. Seconds to minutes
 * of CPU for long files: call from a background thread.
 */
JNIEXPORT jdoubleArray JNICALL
Java_com_aiproject_musicplayer_NativeTags_analyzeLoudness(JNIEnv* env, jclass, jint fd, jlong startUs, jlong endUs) {
    if (fd < 0) return nullptr;
    const auto r = dec::scanLoudness(fd, startUs, endUs);
    if (!r.ok) return nullptr;
    const jdouble values[3] = {r.integratedLufs, r.truePeakDb, r.seconds};
    jdoubleArray out = env->NewDoubleArray(3);
    if (out) env->SetDoubleArrayRegion(out, 0, 3, values);
    return out;
}

/** Duration in ms from headers (no decoding; see DurationProbe.h), 0 when unknown. */
JNIEXPORT jlong JNICALL
Java_com_aiproject_musicplayer_NativeTags_probeDurationMs(JNIEnv*, jclass, jint fd) {
    return fd < 0 ? 0 : static_cast<jlong>(dec::DurationProbe(fd).durationMs());
}

} // extern "C"
