cmake_minimum_required(VERSION 3.10.2)
project("HiFiPlayerAudioEngine" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 99)

# The engine is header-only C++ (src/) plus the JNI bridge and Sonic.
add_library(audioengine SHARED
    src/jni/audio_engine_jni.cpp
    src/third_party/sonic/sonic.c
)

target_include_directories(audioengine PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/src
    ${CMAKE_CURRENT_SOURCE_DIR}/src/third_party/sonic
)

# Real-time DSP (DSD decimation, EQ, time-stretch) must keep up even in debug
# builds, so the engine is always optimised. Exact IEEE maths is kept: no -ffast-math.
target_compile_options(audioengine PRIVATE -O2)

find_package(oboe REQUIRED CONFIG)
target_link_libraries(audioengine PRIVATE oboe::oboe log m)
