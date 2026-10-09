#include <jni.h>

#include <atomic>
#include <cmath>
#include <cstring>
#include <memory>
#include <mutex>
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

// The mutex only guards the pointer. Each call works on its own reference, so
// a slow call never blocks the others and shutdown cannot free a player that
// is still in use.
std::mutex g_mutex;
std::shared_ptr<AudioPlayer> g_player;
std::atomic<uint64_t> g_nextGeneration{0};

std::shared_ptr<AudioPlayer> player() {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_player;
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

JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeInit(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_player) g_player = std::make_shared<AudioPlayer>();
}

JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeRelease(JNIEnv*, jobject) {
    std::shared_ptr<AudioPlayer> released;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        released.swap(g_player);
    }
    ++g_nextGeneration;
    // Destroyed here (or by the last in-flight call) outside the mutex.
}

/** Takes ownership of fd. */
JNIEXPORT jboolean JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeLoad(JNIEnv*, jobject, jint fd, jint replayGainMode) {
    OwnedFd owned{fd};
    if (fd < 0) return JNI_FALSE;
    ++g_nextGeneration;
    auto decoder = openDecoder(fd);
    if (!decoder) return JNI_FALSE;
    const double gain = gainFor(fd, replayGainMode);
    auto p = player();
    return p && p->load(std::move(decoder), gain) ? JNI_TRUE : JNI_FALSE;
}

/** Takes ownership of fd. Ignored if load()/clearNext() happened meanwhile. */
JNIEXPORT jboolean JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeLoadNext(JNIEnv*, jobject, jint fd, jint replayGainMode) {
    OwnedFd owned{fd};
    if (fd < 0) return JNI_FALSE;
    const uint64_t generation = g_nextGeneration.load();
    auto decoder = openDecoder(fd);
    if (!decoder) return JNI_FALSE;
    const double gain = gainFor(fd, replayGainMode);
    auto p = player();
    if (!p || generation != g_nextGeneration.load()) return JNI_FALSE;
    p->setNext(std::move(decoder), gain);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeClearNext(JNIEnv*, jobject) {
    ++g_nextGeneration;
    if (auto p = player()) p->clearNext();
}

JNIEXPORT jboolean JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativePlay(JNIEnv*, jobject) {
    auto p = player();
    return p && p->play() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativePause(JNIEnv*, jobject) {
    if (auto p = player()) p->pause();
}

JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeStop(JNIEnv*, jobject) {
    if (auto p = player()) p->stop();
}

JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeSeekTo(JNIEnv*, jobject, jdouble positionMs) {
    if (auto p = player()) p->seekToMs(positionMs);
}

JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeSetVolume(JNIEnv*, jobject, jdouble volume) {
    if (auto p = player()) p->setVolume(volume);
}

JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeSetSpeed(JNIEnv*, jobject, jdouble speed) {
    if (auto p = player()) p->setSpeed(speed);
}

JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeSetSpeedMode(JNIEnv*, jobject, jint mode) {
    if (auto p = player()) p->setSpeedMode(mode);
}

JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeSetEqEnabled(JNIEnv*, jobject, jboolean enabled) {
    if (auto p = player()) p->setEqEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeSetEqBand(JNIEnv*, jobject, jint band, jdouble gainDb) {
    if (band < 0) return;
    if (auto p = player()) p->setEqBandGain(static_cast<size_t>(band), gainDb);
}

JNIEXPORT jint JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeGetState(JNIEnv*, jobject) {
    auto p = player();
    return p ? static_cast<jint>(p->state()) : 0;
}

JNIEXPORT jdouble JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeGetPositionMs(JNIEnv*, jobject) {
    auto p = player();
    return p ? p->positionMs() : 0.0;
}

JNIEXPORT jdouble JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeGetDurationMs(JNIEnv*, jobject) {
    auto p = player();
    return p ? p->durationMs() : 0.0;
}

JNIEXPORT jboolean JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeConsumeTrackAdvanced(JNIEnv*, jobject) {
    auto p = player();
    return p && p->consumeTrackAdvanced() ? JNI_TRUE : JNI_FALSE;
}

/**
 * out: [sampleRate, channels, bitsPerSample, dsdRate, codec, outputRate,
 *       outputChannels, gainCentiDb, serialLow32, underruns]
 */
JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_nativeGetTrackInfo(JNIEnv* env, jobject, jintArray out) {
    if (!out) return;
    jint values[10] = {};
    if (auto p = player()) {
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
Java_com_aiproject_musicplayer_AudioEngine_nativeGetSpectrum(JNIEnv* env, jobject, jfloatArray out) {
    if (!out) return;
    const jsize count = std::min<jsize>(env->GetArrayLength(out), audio_engine::dsp::SpectrumAnalyzer::kMaxBands);
    float bands[audio_engine::dsp::SpectrumAnalyzer::kMaxBands] = {};
    if (auto p = player()) p->spectrum(bands, static_cast<int>(count));
    env->SetFloatArrayRegion(out, 0, count, bands);
}

} // extern "C"
