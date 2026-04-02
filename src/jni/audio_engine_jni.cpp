#include <jni.h>
#include <string>
#include <memory>
#include <mutex>
#include <unistd.h>
#include "../core/AudioPlayer.h"
#include "../decoders/FlacDecoder.h"
#include "../decoders/Mp3Decoder.h"
#include "../decoders/WavDecoder.h"
#include "../decoders/ReplayGainScanner.h"
#include "../decoders/DsdDecoder.h"

// g_playerMutex guards g_player access from multiple threads:
// - loadFileFd / loadNextFileFd run on Dispatchers.IO (Kotlin coroutine)
// - shutdownEngine runs on Main thread (onDestroy)
// - All other JNI calls run on Main thread, but the mutex is cheap when uncontested.
static std::unique_ptr<audio_engine::core::AudioPlayer> g_player;
static std::mutex g_playerMutex;

extern "C" JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_initEngine(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_playerMutex);
    g_player = std::make_unique<audio_engine::core::AudioPlayer>();
}

extern "C" JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_shutdownEngine(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_playerMutex);
    g_player.reset();
}

// ---------------------------------------------------------------------------
// File loading — auto-detects FLAC / MP3 / WAV
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jboolean JNICALL
Java_com_aiproject_musicplayer_AudioEngine_loadFileFd(JNIEnv*, jobject, jint fd) {
    std::lock_guard<std::mutex> lk(g_playerMutex);
    if (!g_player) { close(fd); return JNI_FALSE; }

    // Scan ReplayGain tag before format detection (both use lseek+read internally)
    float rgDb = audio_engine::decoders::scanReplayGainDb(fd);

    std::unique_ptr<audio_engine::decoders::IAudioDecoder> decoder;

    lseek(fd, 0, SEEK_SET);
    decoder = std::make_unique<FlacDecoder>();
    if (decoder->openFd(fd)) {
        close(fd);
        g_player->setDecoder(std::move(decoder));
        g_player->setReplayGainDb(rgDb);
        return JNI_TRUE;
    }
    lseek(fd, 0, SEEK_SET);
    decoder = std::make_unique<Mp3Decoder>();
    if (decoder->openFd(fd)) {
        close(fd);
        g_player->setDecoder(std::move(decoder));
        g_player->setReplayGainDb(rgDb);
        return JNI_TRUE;
    }
    lseek(fd, 0, SEEK_SET);
    decoder = std::make_unique<WavDecoder>();
    if (decoder->openFd(fd)) {
        close(fd);
        g_player->setDecoder(std::move(decoder));
        g_player->setReplayGainDb(rgDb);
        return JNI_TRUE;
    }
    lseek(fd, 0, SEEK_SET);
    decoder = std::make_unique<audio_engine::decoders::DsdDecoder>();
    if (decoder->openFd(fd)) {
        close(fd);
        g_player->setDecoder(std::move(decoder));
        g_player->setReplayGainDb(0.0f); // DSD has no ReplayGain
        return JNI_TRUE;
    }
    close(fd);
    return JNI_FALSE;
}

// ---------------------------------------------------------------------------
// Transport
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_play(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_playerMutex);
    if (g_player) g_player->play();
}

/**
 * pause() — stops the decode thread; Oboe stream keeps running (silence).
 * The stream is NEVER closed here.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_pause(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_playerMutex);
    if (g_player) g_player->pause();
}

extern "C" JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_seekTo(JNIEnv*, jobject, jdouble positionMs) {
    std::lock_guard<std::mutex> lk(g_playerMutex);
    if (g_player) g_player->seekToMs(positionMs);
}

// ---------------------------------------------------------------------------
// DSP controls
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_setVolume(JNIEnv*, jobject, jdouble volume) {
    std::lock_guard<std::mutex> lk(g_playerMutex);
    if (g_player) g_player->setVolume(volume);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_forceSilence(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_playerMutex);
    if (g_player) g_player->forceSilence();
}

extern "C" JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_clearBufferedAudio(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_playerMutex);
    if (g_player) g_player->clearBufferedAudio();
}

extern "C" JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_setSpeed(JNIEnv*, jobject, jdouble speed) {
    std::lock_guard<std::mutex> lk(g_playerMutex);
    if (g_player) g_player->setSpeed(speed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_setSpeedMode(JNIEnv*, jobject, jint mode) {
    std::lock_guard<std::mutex> lk(g_playerMutex);
    if (g_player) g_player->setSpeedMode(static_cast<int>(mode));
}

extern "C" JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_setEqEnabled(JNIEnv*, jobject, jboolean enabled) {
    std::lock_guard<std::mutex> lk(g_playerMutex);
    if (g_player) g_player->setEqEnabled(enabled == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_setEqBandGain(JNIEnv*, jobject, jint bandIndex, jdouble gainDb) {
    std::lock_guard<std::mutex> lk(g_playerMutex);
    if (g_player && bandIndex >= 0) {
        g_player->setEqBandGain(static_cast<size_t>(bandIndex), gainDb);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_resetEqBands(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_playerMutex);
    if (g_player) g_player->resetEqBands();
}

// ---------------------------------------------------------------------------
// Metadata & state queries
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jdouble JNICALL
Java_com_aiproject_musicplayer_AudioEngine_getDurationMs(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_playerMutex);
    return g_player ? g_player->getDurationMs() : 0.0;
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_aiproject_musicplayer_AudioEngine_getPositionMs(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_playerMutex);
    return g_player ? g_player->getPositionMs() : 0.0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aiproject_musicplayer_AudioEngine_isPlaying(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_playerMutex);
    return (g_player && g_player->isPlaying()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aiproject_musicplayer_AudioEngine_getSampleRateNative(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_playerMutex);
    return g_player ? static_cast<jint>(g_player->getSampleRate()) : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aiproject_musicplayer_AudioEngine_getBitsPerSample(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_playerMutex);
    return g_player ? static_cast<jint>(g_player->getBitsPerSample()) : 0;
}

// ---------------------------------------------------------------------------
// Gapless: load next track into pre-load slot
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jboolean JNICALL
Java_com_aiproject_musicplayer_AudioEngine_loadNextFileFd(JNIEnv*, jobject, jint fd) {
    std::lock_guard<std::mutex> lk(g_playerMutex);
    if (!g_player) { close(fd); return JNI_FALSE; }
    float rgDb = audio_engine::decoders::scanReplayGainDb(fd);
    std::unique_ptr<audio_engine::decoders::IAudioDecoder> decoder;
    lseek(fd, 0, SEEK_SET);
    decoder = std::make_unique<FlacDecoder>();
    if (decoder->openFd(fd)) { close(fd); g_player->setNextDecoder(std::move(decoder), rgDb); return JNI_TRUE; }
    lseek(fd, 0, SEEK_SET);
    decoder = std::make_unique<Mp3Decoder>();
    if (decoder->openFd(fd)) { close(fd); g_player->setNextDecoder(std::move(decoder), rgDb); return JNI_TRUE; }
    lseek(fd, 0, SEEK_SET);
    decoder = std::make_unique<WavDecoder>();
    if (decoder->openFd(fd)) { close(fd); g_player->setNextDecoder(std::move(decoder), rgDb); return JNI_TRUE; }
    close(fd);
    return JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_clearNextTrack(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_playerMutex);
    if (g_player) g_player->clearNextDecoder();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aiproject_musicplayer_AudioEngine_pollGaplessAdvanced(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_playerMutex);
    return (g_player && g_player->pollGaplessAdvanced()) ? JNI_TRUE : JNI_FALSE;
}

// ---------------------------------------------------------------------------
// Spectrum analyzer
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_getSpectrum(JNIEnv* env, jobject, jfloatArray jBands) {
    std::lock_guard<std::mutex> lk(g_playerMutex);
    if (!g_player) return;
    jsize  count = env->GetArrayLength(jBands);
    float* data  = env->GetFloatArrayElements(jBands, nullptr);
    if (data) {
        g_player->getSpectrumBands(data, static_cast<int>(count), g_player->getSampleRate());
        env->ReleaseFloatArrayElements(jBands, data, 0);
    }
}

// ---------------------------------------------------------------------------
// ReplayGain query
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jfloat JNICALL
Java_com_aiproject_musicplayer_AudioEngine_getReplayGainDb(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_playerMutex);
    return g_player ? g_player->getReplayGainDb() : 0.0f;
}

// Returns the native DSD sample rate (e.g. 2822400 for DSD64, 5644800 for DSD128).
// Returns 0 if the current track is not DSD.
extern "C" JNIEXPORT jint JNICALL
Java_com_aiproject_musicplayer_AudioEngine_getDsdNativeRate(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_playerMutex);
    return g_player ? static_cast<jint>(g_player->getDsdNativeRate()) : 0;
}
