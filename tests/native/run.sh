#!/usr/bin/env bash
# Host-side native tests for the audio engine. No Android SDK required.
#   bash tests/native/run.sh          ASan + UBSan builds (default)
#   TSAN=1 bash tests/native/run.sh   additionally runs the player tests under ThreadSanitizer
set -euo pipefail
cd "$(dirname "$0")/../.."
export UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1
export ASAN_OPTIONS=detect_leaks=1

work=$(mktemp -d /tmp/hifi-native-test.XXXXXX)
trap 'rm -rf "$work"' EXIT

# ── Third-party decoders: built once without sanitizers, cached between runs ──
tp=src/third_party
cache=tests/native/.cache
mkdir -p "$cache"
tp_inc="-I$tp/ogg/include -I$tp/vorbis/include -I$tp/opus/include -I$tp/opusfile/include
        -I$tp/wavpack/include -I$tp/monkeys-audio/Shared -I$tp/monkeys-audio/MACLib -I$tp/libtta"

compile_lib() {   # name, flags, sources...
    local name=$1 flags
    flags=$(echo $2)   # one line: it is pasted into the sh -c script below
    shift 2
    local objs=() todo=() src obj
    for src in "$@"; do
        obj="$cache/$name-$(echo "$src" | tr '/' '_').o"
        objs+=("$obj")
        if [[ ! -f "$obj" || "$src" -nt "$obj" ]]; then todo+=("$src" "$obj"); fi
    done
    printf '%s\n' "${todo[@]}" | xargs -r -P "$(nproc)" -n 2 sh -c '
        case "$0" in *.cpp) exec c++ -std=c++17 -O2 -w -fPIC '"$flags"' -c "$0" -o "$1";;
                     *)     exec cc -O2 -w -fPIC '"$flags"' -c "$0" -o "$1";; esac'
    rm -f "$cache/lib$name.a"
    ar rcs "$cache/lib$name.a" "${objs[@]}"
}

simd=""
case "$(uname -m)" in x86_64|i?86) simd=x86;; esac
compile_lib ogg "-I$tp/ogg/include" $tp/ogg/src/bitwise.c $tp/ogg/src/framing.c
compile_lib vorbis "-I$tp/ogg/include -I$tp/vorbis/include -I$tp/vorbis/lib" \
    $(for f in analysis bitrate block codebook envelope floor0 floor1 info lpc lsp mapping0 mdct psy registry res0 \
               sharedbook smallft synthesis window vorbisfile; do echo $tp/vorbis/lib/$f.c; done)
compile_lib opus "-DOPUS_BUILD -DUSE_ALLOCA -DHAVE_LRINT -DHAVE_LRINTF -DENABLE_HARDENING -I$tp/opus/include -I$tp/opus
                  -I$tp/opus/celt -I$tp/opus/silk -I$tp/opus/silk/float" \
    $tp/opus/celt/*.c $tp/opus/silk/*.c $tp/opus/silk/float/*.c $tp/opus/src/*.c
compile_lib opusfile "-DOP_DISABLE_HTTP -DOP_DISABLE_DOCS -I$tp/ogg/include -I$tp/opus/include -I$tp/opusfile/include" \
    $tp/opusfile/src/*.c
compile_lib wavpack "-I$tp/wavpack/include" $tp/wavpack/src/*.c
mac_src=$(ls $tp/monkeys-audio/MACLib/*.cpp $tp/monkeys-audio/MACLib/Old/*.cpp $tp/monkeys-audio/Shared/*.cpp)
if [[ $simd == x86 ]]; then
    # Per-file instruction sets, as in cmake/ThirdParty.cmake.
    for f in AVX2:-mavx2 AVX512:"-mavx512dq -mavx512bw" SSE4.1:-msse4.1; do
        file=$tp/monkeys-audio/MACLib/NNFilter${f%%:*}.cpp
        obj="$cache/mac-simd-${f%%:*}.o"
        [[ -f "$obj" && ! "$file" -nt "$obj" ]] || c++ -std=c++17 -O2 -w -fPIC ${f#*:} -I$tp/monkeys-audio/Shared \
            -I$tp/monkeys-audio/MACLib -c "$file" -o "$obj"
    done
    mac_src=$(echo "$mac_src" | grep -v -E 'NNFilter(AVX2|AVX512|SSE4\.1)\.cpp')
fi
compile_lib mac "-I$tp/monkeys-audio/Shared -I$tp/monkeys-audio/MACLib" $mac_src
[[ $simd == x86 ]] && ar rs "$cache/libmac.a" "$cache"/mac-simd-*.o 2>/dev/null
compile_lib tta "-I$tp/libtta" $tp/libtta/libtta.cpp
tp_libs="$cache/libopusfile.a $cache/libopus.a $cache/libvorbis.a $cache/libogg.a $cache/libwavpack.a
         $cache/libmac.a $cache/libtta.a"

# ── Engine tests ─────────────────────────────────────────────────────────────
san="-fsanitize=address,undefined -fno-omit-frame-pointer -fno-sanitize-recover=undefined"
flags="-std=c++17 -O1 -g -pthread -DHIFI_MEDIACODEC=1 -Itests/native/stubs -Isrc $tp_inc"
cc -O1 -g $san -c src/third_party/sonic/sonic.c -o "$work/sonic.o"
c++ $flags $san tests/native/decoder_tests.cpp "$work/sonic.o" $tp_libs -o "$work/decoder_tests" -lm
c++ $flags $san tests/native/format_tests.cpp $tp_libs -o "$work/format_tests" -lm
c++ $flags $san tests/native/player_tests.cpp "$work/sonic.o" $tp_libs -o "$work/player_tests" -lm

if command -v sox >/dev/null && command -v flac >/dev/null && command -v lame >/dev/null; then
    sox -n -r 44100 -c 2 -b 16 "$work/tone.wav" synth 1.2 sine 440 vol 0.25
    sox -n -r 44100 -c 2 -b 24 "$work/tone.aiff" synth 1.2 sine 440 vol 0.25
    flac --silent "$work/tone.wav" -o "$work/tone.flac"
    flac --silent --tag="REPLAYGAIN_TRACK_GAIN=-7.25 dB" --tag="REPLAYGAIN_TRACK_PEAK=0.5" \
        "$work/tone.wav" -o "$work/tagged.flac"
    lame --silent --noreplaygain -b 320 "$work/tone.wav" "$work/tone-cbr.mp3"
    lame --silent --noreplaygain -V 2 "$work/tone.wav" "$work/tone-vbr.mp3"
    lame --silent --noreplaygain -V 2 -t "$work/tone.wav" "$work/tone-no-xing.mp3"
    sox -n -r 44100 -c 2 -b 16 "$work/long.wav" synth 600 sine 220 vol 0.2
    lame --silent --noreplaygain -b 64 "$work/long.wav" "$work/long.mp3"
    "$work/decoder_tests" "$work"
    sox "$work/tone.wav" --comment "TITLE=Ogg Title" -C 6 "$work/tone.ogg"
    "$work/format_tests" "$work"
else
    echo "sox/flac/lame not found: skipping encoded-file fixtures"
    "$work/decoder_tests"
    "$work/format_tests"
fi
"$work/player_tests"

if [[ "${TSAN:-0}" == "1" ]]; then
    cc -O1 -g -fsanitize=thread -c src/third_party/sonic/sonic.c -o "$work/sonic-tsan.o"
    c++ $flags -fsanitize=thread tests/native/player_tests.cpp "$work/sonic-tsan.o" $tp_libs \
        -o "$work/player_tests_tsan" -lm
    TSAN_OPTIONS=halt_on_error=1 "$work/player_tests_tsan"
fi
