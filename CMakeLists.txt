cmake_minimum_required(VERSION 3.10.2)
project("MusicPlayerPro_AudioEngine")

# Define C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Configure the paths to our C++ engine headers
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/src)

# Define our audio engine native library
add_library(audioengine SHARED
    src/jni/audio_engine_jni.cpp
    # Note: We currently only have headers for the engine. 
    # If .cpp files are added for AudioEndpoint or other modules, add them here.
)

# Identify Android libraries to link against, e.g. log, OpenSLES or Oboe if needed
find_library(log-lib log)
find_package(oboe REQUIRED CONFIG)

target_link_libraries(audioengine oboe::oboe ${log-lib})
