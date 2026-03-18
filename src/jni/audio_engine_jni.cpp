#include <jni.h>
#include <string>
#include <memory>
#include <iostream>
#include "../core/AudioPlayer.h"
#include "../decoders/FlacDecoder.h"
#include "../decoders/Mp3Decoder.h"
#include "../decoders/WavDecoder.h"
#include <unistd.h>

// Singleton for Prototype Engine
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
    // Boilerplate integration
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aiproject_musicplayer_AudioEngine_loadFileFd(JNIEnv* env, jobject /* this */, jint fd) {
    if (!g_player) return JNI_FALSE;

    // Stop playback BEFORE changing decoder
    g_player->stop();

    std::unique_ptr<audio_engine::decoders::IAudioDecoder> decoder;

    // We can probe with lseek and read, but simply trying to initialize 
    // each decoder sequentially works fine since they gracefully fail
    // However, when we pass FD, we need to reset its position!
    
    // Try FLAC
    lseek(fd, 0, SEEK_SET);
    decoder = std::make_unique<FlacDecoder>();
    if (decoder->openFd(fd)) {
        close(fd); // Our decoders dup() it, so we close the original from app
        g_player->setDecoder(std::move(decoder));
        return JNI_TRUE;
    }

    // Try MP3
    lseek(fd, 0, SEEK_SET);
    decoder = std::make_unique<Mp3Decoder>();
    if (decoder->openFd(fd)) {
        close(fd);
        g_player->setDecoder(std::move(decoder));
        return JNI_TRUE;
    }

    // Try WAV
    lseek(fd, 0, SEEK_SET);
    decoder = std::make_unique<WavDecoder>();
    if (decoder->openFd(fd)) {
        close(fd);
        g_player->setDecoder(std::move(decoder));
        return JNI_TRUE;
    }

    // Failed
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
