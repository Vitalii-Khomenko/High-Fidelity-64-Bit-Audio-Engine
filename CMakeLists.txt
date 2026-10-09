cmake_minimum_required(VERSION 3.10.2)
project("HiFiPlayerAudioEngine" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 99)

# Third-party decoders (Ogg, Vorbis, Opus, WavPack, Monkey's Audio, TTA).
set(HIFI_THIRD_PARTY ${CMAKE_CURRENT_SOURCE_DIR}/src/third_party)
include(cmake/ThirdParty.cmake)

# The engine is header-only C++ (src/) plus the JNI bridge and Sonic.
add_library(audioengine SHARED
    src/jni/audio_engine_jni.cpp
    src/third_party/sonic/sonic.c
)

target_include_directories(audioengine PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/src
    ${CMAKE_CURRENT_SOURCE_DIR}/src/third_party/sonic
)

# MediaCodec (AAC, ALAC, ...) is only available on Android.
target_compile_definitions(audioengine PRIVATE HIFI_MEDIACODEC=1)

# Real-time DSP (DSD decimation, EQ, time-stretch) must keep up even in debug
# builds, so the engine and its decoders are always optimised. Exact IEEE maths
# is kept: no -ffast-math.
target_compile_options(audioengine PRIVATE -O2)
foreach(lib hifi_ogg hifi_vorbis hifi_opus hifi_opusfile hifi_wavpack hifi_mac tta)
    target_compile_options(${lib} PRIVATE -O2)
endforeach()

find_package(oboe REQUIRED CONFIG)
target_link_libraries(audioengine PRIVATE
    hifi_opusfile hifi_vorbis hifi_wavpack hifi_mac tta
    oboe::oboe mediandk log m)
# Keep the decoders' unused parts (encoders) out of the library.
target_link_options(audioengine PRIVATE -Wl,--gc-sections)
