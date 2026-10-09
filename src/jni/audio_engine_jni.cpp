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

/** Opens fd with the right decoder; a CUE track becomes a range of the file. */
std::unique_ptr<dec::IAudioDecoder> openDecoder(int fd, jlong startUs, jlong endUs) {
    return dec::RangeDecoder::wrap(dec::openDecoder(fd), startUs, endUs);
}

double gainFor(int fd, jint replayGainMode) {
    const auto mode = static_cast<dec::ReplayGainMode>(replayGainMode);
    if (mode == dec::ReplayGainMode::Off) return 1.0;
    return dec::replayGainLinear(dec::readReplayGain(fd), mode);
}

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
Java_com_aiproject_musicplayer_AudioEngine_nativeLoad(JNIEnv*, jobject, jlong id, jint fd, jint replayGainMode,
                                                      jlong startUs, jlong endUs) {
    OwnedFd owned{fd};
    auto inst = instance(id);
    if (fd < 0 || !inst) return JNI_FALSE;
    inst->invalidateNext();  // any pre-load still being opened is stale now
    auto decoder = openDecoder(fd, startUs, endUs);
    if (!decoder) return JNI_FALSE;
    const double gain = gainFor(fd, replayGainMode);
    return inst->player->load(std::move(decoder), gain) ? JNI_TRUE : JNI_FALSE;
}

/** Takes ownership of fd. Ignored if load()/clearNext() happened meanwhile. */
JNIEXPORT jboolean JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeLoadNext(JNIEnv*, jobject, jlong id, jint fd, jint replayGainMode,
                                                          jlong startUs, jlong endUs) {
    OwnedFd owned{fd};
    auto inst = instance(id);
    if (fd < 0 || !inst) return JNI_FALSE;
    const uint64_t generation = inst->generation();
    auto decoder = openDecoder(fd, startUs, endUs);
    if (!decoder) return JNI_FALSE;
    const double gain = gainFor(fd, replayGainMode);
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

JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeSetEqEnabled(JNIEnv*, jobject, jlong id, jboolean enabled) {
    if (auto p = player(id)) p->setEqEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeSetEqBand(JNIEnv*, jobject, jlong id, jint band, jdouble gainDb) {
    if (band < 0) return;
    if (auto p = player(id)) p->setEqBandGain(static_cast<size_t>(band), gainDb);
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

/** Duration in ms from the engine's own decoders, 0 when the file cannot be opened. */
JNIEXPORT jlong JNICALL
Java_com_aiproject_musicplayer_NativeTags_probeDurationMs(JNIEnv*, jclass, jint fd) {
    if (fd < 0) return 0;
    auto decoder = dec::openDecoder(fd);
    if (!decoder || decoder->getSampleRate() == 0) return 0;
    return static_cast<jlong>(decoder->getTotalFrames() * 1000ull / decoder->getSampleRate());
}

} // extern "C"
