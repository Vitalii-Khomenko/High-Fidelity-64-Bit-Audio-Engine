#include <jni.h>

#include <atomic>
#include <cmath>
#include <cstring>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <unistd.h>

#include "../core/AudioPlayer.h"
#include "../decoders/DsdDecoder.h"
#include "../decoders/FlacDecoder.h"
#include "../decoders/Mp3Decoder.h"
#include "../decoders/ReplayGainScanner.h"
#include "../decoders/WavDecoder.h"

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
    std::atomic<uint64_t> nextGeneration{0};
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

enum class Sniffed { Flac, Riff, Dsd, Mp3, Unknown };

Sniffed sniff(int fd) {
    uint8_t h[12] = {};
    const ssize_t n = ::pread(fd, h, sizeof(h), 0);
    if (n < 4) return Sniffed::Unknown;
    if (!std::memcmp(h, "fLaC", 4) || !std::memcmp(h, "OggS", 4)) return Sniffed::Flac;
    if (!std::memcmp(h, "RIFF", 4) || !std::memcmp(h, "RF64", 4) || !std::memcmp(h, "riff", 4) ||
        !std::memcmp(h, "FORM", 4) || !std::memcmp(h, "RIFX", 4)) return Sniffed::Riff;
    if (!std::memcmp(h, "DSD ", 4) || !std::memcmp(h, "FRM8", 4)) return Sniffed::Dsd;
    if (!std::memcmp(h, "ID3", 3) || (h[0] == 0xFF && (h[1] & 0xE0) == 0xE0)) return Sniffed::Mp3;
    return Sniffed::Unknown;
}

std::unique_ptr<dec::IAudioDecoder> makeDecoder(Sniffed kind) {
    switch (kind) {
        case Sniffed::Flac: return std::make_unique<dec::FlacDecoder>();
        case Sniffed::Riff: return std::make_unique<dec::WavDecoder>();
        case Sniffed::Dsd: return std::make_unique<dec::DsdDecoder>();
        case Sniffed::Mp3: return std::make_unique<dec::Mp3Decoder>();
        default: return nullptr;
    }
}

std::unique_ptr<dec::IAudioDecoder> openDecoder(int fd) {
    const Sniffed kind = sniff(fd);
    if (auto decoder = makeDecoder(kind)) {
        if (decoder->openFd(fd)) return decoder;
        return nullptr;
    }
    // No recognisable signature (e.g. MP3 behind junk bytes): try permissive decoders.
    for (Sniffed fallback : {Sniffed::Flac, Sniffed::Riff, Sniffed::Mp3}) {
        auto decoder = makeDecoder(fallback);
        if (decoder && decoder->openFd(fd)) return decoder;
    }
    return nullptr;
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
    ++released->nextGeneration;
    // Destroyed here (or by the last in-flight call) outside the mutex.
}

/** Takes ownership of fd. */
JNIEXPORT jboolean JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeLoad(JNIEnv*, jobject, jlong id, jint fd, jint replayGainMode) {
    OwnedFd owned{fd};
    auto inst = instance(id);
    if (fd < 0 || !inst) return JNI_FALSE;
    ++inst->nextGeneration;
    auto decoder = openDecoder(fd);
    if (!decoder) return JNI_FALSE;
    const double gain = gainFor(fd, replayGainMode);
    return inst->player->load(std::move(decoder), gain) ? JNI_TRUE : JNI_FALSE;
}

/** Takes ownership of fd. Ignored if load()/clearNext() happened meanwhile. */
JNIEXPORT jboolean JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeLoadNext(JNIEnv*, jobject, jlong id, jint fd, jint replayGainMode) {
    OwnedFd owned{fd};
    auto inst = instance(id);
    if (fd < 0 || !inst) return JNI_FALSE;
    const uint64_t generation = inst->nextGeneration.load();
    auto decoder = openDecoder(fd);
    if (!decoder) return JNI_FALSE;
    const double gain = gainFor(fd, replayGainMode);
    if (generation != inst->nextGeneration.load()) return JNI_FALSE;
    inst->player->setNext(std::move(decoder), gain);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeClearNext(JNIEnv*, jobject, jlong id) {
    auto inst = instance(id);
    if (!inst) return;
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

} // extern "C"
