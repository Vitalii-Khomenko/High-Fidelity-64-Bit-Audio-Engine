#include <jni.h>
#include <string>
#include <memory>
#include <iostream>
#include "../core/AudioPlayer.h"
#include "../decoders/FlacDecoder.h"
#include "../decoders/Mp3Decoder.h"
#include "../decoders/WavDecoder.h"
#include <unistd.h>

static std::unique_ptr<audio_engine::core::AudioPlayer> g_player;

extern "C" JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_initEngine(JNIEnv* env, jobject /* this */) {
    g_player = std::make_unique<audio_engine::core::AudioPlayer>();
}

extern "C" JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_setVolume(JNIEnv* env, jobject /* this */, jdouble volume) {
    if (g_player) {
        g_player->setVolume(volume);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_setEqEnabled(JNIEnv* env, jobject /* this */, jboolean enabled) {
}

extern "C" JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_setSpeed(JNIEnv* env, jobject /* this */, jdouble speed) {
    if (g_player) {
        g_player->setSpeed(speed);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aiproject_musicplayer_AudioEngine_loadFileFd(JNIEnv* env, jobject /* this */, jint fd) {
    if (!g_player) return JNI_FALSE;

    g_player->stop();

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

extern "C" JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_play(JNIEnv* env, jobject /* this */) {
    if (g_player) g_player->play();
}

extern "C" JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_pause(JNIEnv* env, jobject /* this */) {
    if (g_player) g_player->stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_shutdownEngine(JNIEnv* env, jobject /* this */) {
    g_player.reset();
}

extern "C" JNIEXPORT void JNICALL
Java_com_aiproject_musicplayer_AudioEngine_seekTo(JNIEnv* env, jobject /* this */, jdouble positionMs) {
    if (g_player) {
        g_player->seekToMs(positionMs);
    }
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_aiproject_musicplayer_AudioEngine_getDurationMs(JNIEnv* env, jobject /* this */) {
    if (g_player) return g_player->getDurationMs();
    return 0.0;
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_aiproject_musicplayer_AudioEngine_getPositionMs(JNIEnv* env, jobject /* this */) {
    if (g_player) return g_player->getPositionMs();
    return 0.0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aiproject_musicplayer_AudioEngine_isPlaying(JNIEnv* env, jobject /* this */) {
    if (g_player) return g_player->isPlaying() ? JNI_TRUE : JNI_FALSE;
    return JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aiproject_musicplayer_AudioEngine_getSampleRateNative(JNIEnv* env, jobject /* this */) {
    if (g_player) return static_cast<jint>(g_player->getSampleRate());
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aiproject_musicplayer_AudioEngine_getBitsPerSample(JNIEnv* env, jobject /* this */) {
    if (g_player) return static_cast<jint>(g_player->getBitsPerSample());
    return 0;
}
