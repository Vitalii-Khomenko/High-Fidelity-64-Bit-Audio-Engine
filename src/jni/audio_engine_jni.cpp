#include <jni.h>
#include <string>
#include <memory>
#include <unistd.h>
#include "../core/AudioPlayer.h"
#include "../decoders/FlacDecoder.h"
#include "../decoders/Mp3Decoder.h"
#include "../decoders/WavDecoder.h"

static std::unique_ptr<audio_engine::core::AudioPlayer> g_player;

extern "C" JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_initEngine(JNIEnv*, jobject) {
    g_player = std::make_unique<audio_engine::core::AudioPlayer>();
}

extern "C" JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_shutdownEngine(JNIEnv*, jobject) {
    g_player.reset();
}

// ---------------------------------------------------------------------------
// File loading — auto-detects FLAC / MP3 / WAV
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jboolean JNICALL
Java_com_aiproject_musicplayer_AudioEngine_loadFileFd(JNIEnv*, jobject, jint fd) {
    if (!g_player) return JNI_FALSE;

    // Try each format; each decoder dup()-s the fd, so we must reset the
    // file position between attempts and close the original fd at the end.

    std::unique_ptr<audio_engine::decoders::IAudioDecoder> decoder;

    lseek(fd, 0, SEEK_SET);
    decoder = std::make_unique<FlacDecoder>();
    if (decoder->openFd(fd)) {
        close(fd);
        g_player->setDecoder(std::move(decoder));
        return JNI_TRUE;
    }

    lseek(fd, 0, SEEK_SET);
    decoder = std::make_unique<Mp3Decoder>();
    if (decoder->openFd(fd)) {
        close(fd);
        g_player->setDecoder(std::move(decoder));
        return JNI_TRUE;
    }

    lseek(fd, 0, SEEK_SET);
    decoder = std::make_unique<WavDecoder>();
    if (decoder->openFd(fd)) {
        close(fd);
        g_player->setDecoder(std::move(decoder));
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
    if (g_player) g_player->play();
}

/**
 * pause() — stops the decode thread; Oboe stream keeps running (silence).
 * The stream is NEVER closed here.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_pause(JNIEnv*, jobject) {
    if (g_player) g_player->pause();
}

extern "C" JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_seekTo(JNIEnv*, jobject, jdouble positionMs) {
    if (g_player) g_player->seekToMs(positionMs);
}

// ---------------------------------------------------------------------------
// DSP controls
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_setVolume(JNIEnv*, jobject, jdouble volume) {
    if (g_player) g_player->setVolume(volume);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_setSpeed(JNIEnv*, jobject, jdouble speed) {
    if (g_player) g_player->setSpeed(speed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_setEqEnabled(JNIEnv*, jobject, jboolean /*enabled*/) {
    // EQ band control will be added in a future version
}

// ---------------------------------------------------------------------------
// Metadata & state queries
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jdouble JNICALL
Java_com_aiproject_musicplayer_AudioEngine_getDurationMs(JNIEnv*, jobject) {
    return g_player ? g_player->getDurationMs() : 0.0;
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_aiproject_musicplayer_AudioEngine_getPositionMs(JNIEnv*, jobject) {
    return g_player ? g_player->getPositionMs() : 0.0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aiproject_musicplayer_AudioEngine_isPlaying(JNIEnv*, jobject) {
    return (g_player && g_player->isPlaying()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aiproject_musicplayer_AudioEngine_getSampleRateNative(JNIEnv*, jobject) {
    return g_player ? static_cast<jint>(g_player->getSampleRate()) : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aiproject_musicplayer_AudioEngine_getBitsPerSample(JNIEnv*, jobject) {
    return g_player ? static_cast<jint>(g_player->getBitsPerSample()) : 0;
}
