#include <jni.h>
#include <string>
#include <memory>
#include <iostream>
#include "../core/AudioPlayer.h"
#include "../decoders/FlacDecoder.h"

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
    
    // Convert OS File Descriptor to /proc/self/fd/ path
    // This allows C++ open() / fopen() to read the file transparently without strict permissions.
    std::string fdPath = "/proc/self/fd/" + std::to_string(fd);
    
    // Initialize decoder
    auto decoder = std::make_unique<FlacDecoder>(); // Using FLAC decode as default 
    if (decoder->open(fdPath)) {
        g_player->setDecoder(std::move(decoder));
        return JNI_TRUE;
    }
    
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
