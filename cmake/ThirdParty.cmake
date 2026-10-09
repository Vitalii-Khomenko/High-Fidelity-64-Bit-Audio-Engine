# Third-party decoders, shared by the Android build (CMakeLists.txt) and the
# host tests (tests/native/CMakeLists.txt). Set HIFI_THIRD_PARTY to the
# src/third_party directory before including this file.
#
# Everything except libtta is BSD-licensed and linked statically; the linker
# only keeps the decoder parts the engine references. libtta is LGPL-3 and is
# built as its own shared library (libtta.so) so it stays replaceable.

set(TP ${HIFI_THIRD_PARTY})

# Upstream code is used unmodified; its warnings are not ours to fix.
set(HIFI_TP_FLAGS -w)

# ── Ogg ─────────────────────────────────────────────────────────────────────
add_library(hifi_ogg STATIC ${TP}/ogg/src/bitwise.c ${TP}/ogg/src/framing.c)
target_include_directories(hifi_ogg PUBLIC ${TP}/ogg/include)
target_compile_options(hifi_ogg PRIVATE ${HIFI_TP_FLAGS})

# ── Vorbis (decoder + vorbisfile) ───────────────────────────────────────────
set(VORBIS_SRC analysis bitrate block codebook envelope floor0 floor1 info lpc
    lsp mapping0 mdct psy registry res0 sharedbook smallft synthesis window
    vorbisfile)
list(TRANSFORM VORBIS_SRC PREPEND ${TP}/vorbis/lib/)
list(TRANSFORM VORBIS_SRC APPEND .c)
add_library(hifi_vorbis STATIC ${VORBIS_SRC})
target_include_directories(hifi_vorbis PUBLIC ${TP}/vorbis/include PRIVATE ${TP}/vorbis/lib)
target_link_libraries(hifi_vorbis PUBLIC hifi_ogg)
target_compile_options(hifi_vorbis PRIVATE ${HIFI_TP_FLAGS})

# ── Opus (floating point, portable C) and opusfile ──────────────────────────
file(GLOB OPUS_SRC ${TP}/opus/celt/*.c ${TP}/opus/silk/*.c ${TP}/opus/silk/float/*.c ${TP}/opus/src/*.c)
add_library(hifi_opus STATIC ${OPUS_SRC})
target_include_directories(hifi_opus PUBLIC ${TP}/opus/include
    PRIVATE ${TP}/opus ${TP}/opus/celt ${TP}/opus/silk ${TP}/opus/silk/float)
target_compile_definitions(hifi_opus PRIVATE OPUS_BUILD USE_ALLOCA HAVE_LRINT HAVE_LRINTF ENABLE_HARDENING)
target_compile_options(hifi_opus PRIVATE ${HIFI_TP_FLAGS})

add_library(hifi_opusfile STATIC ${TP}/opusfile/src/info.c ${TP}/opusfile/src/internal.c
    ${TP}/opusfile/src/opusfile.c ${TP}/opusfile/src/stream.c)
target_include_directories(hifi_opusfile PUBLIC ${TP}/opusfile/include ${TP}/opus/include)
target_compile_definitions(hifi_opusfile PRIVATE OP_DISABLE_HTTP OP_DISABLE_DOCS)
target_link_libraries(hifi_opusfile PUBLIC hifi_opus hifi_ogg)
target_compile_options(hifi_opusfile PRIVATE ${HIFI_TP_FLAGS})

# ── WavPack ─────────────────────────────────────────────────────────────────
file(GLOB WAVPACK_SRC ${TP}/wavpack/src/*.c)
add_library(hifi_wavpack STATIC ${WAVPACK_SRC})
target_include_directories(hifi_wavpack PUBLIC ${TP}/wavpack/include)
target_compile_options(hifi_wavpack PRIVATE ${HIFI_TP_FLAGS})

# ── Monkey's Audio (APE) ────────────────────────────────────────────────────
file(GLOB MAC_SRC ${TP}/monkeys-audio/MACLib/*.cpp ${TP}/monkeys-audio/MACLib/Old/*.cpp
    ${TP}/monkeys-audio/Shared/*.cpp)
# The SIMD variants compile to plain C where the instruction set is not
# enabled; NNFilter.cpp picks one at run time.
if(CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64|AMD64|i.86")
    set_source_files_properties(${TP}/monkeys-audio/MACLib/NNFilterAVX2.cpp PROPERTIES COMPILE_OPTIONS "-mavx2")
    set_source_files_properties(${TP}/monkeys-audio/MACLib/NNFilterAVX512.cpp PROPERTIES COMPILE_OPTIONS "-mavx512dq;-mavx512bw")
    set_source_files_properties(${TP}/monkeys-audio/MACLib/NNFilterSSE4.1.cpp PROPERTIES COMPILE_OPTIONS "-msse4.1")
endif()
add_library(hifi_mac STATIC ${MAC_SRC})
target_include_directories(hifi_mac PUBLIC ${TP}/monkeys-audio/Shared ${TP}/monkeys-audio/MACLib)
target_compile_options(hifi_mac PRIVATE ${HIFI_TP_FLAGS})

# ── TTA (LGPL-3, separate shared library) ───────────────────────────────────
add_library(tta SHARED ${TP}/libtta/libtta.cpp)
target_include_directories(tta PUBLIC ${TP}/libtta)
# Uses the 'register' keyword, which C++17 removed.
set_target_properties(tta PROPERTIES CXX_STANDARD 14)
target_compile_options(tta PRIVATE ${HIFI_TP_FLAGS})
