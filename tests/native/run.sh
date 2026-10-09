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

san="-fsanitize=address,undefined -fno-omit-frame-pointer -fno-sanitize-recover=undefined"
cc -O1 -g $san -c src/third_party/sonic/sonic.c -o "$work/sonic.o"
c++ -std=c++17 -O1 -g -pthread $san -Itests/native/stubs -Isrc \
    tests/native/decoder_tests.cpp "$work/sonic.o" -o "$work/decoder_tests" -lm
c++ -std=c++17 -O1 -g -pthread $san -Itests/native/stubs -Isrc \
    tests/native/player_tests.cpp "$work/sonic.o" -o "$work/player_tests" -lm

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
else
    echo "sox/flac/lame not found: skipping encoded-file fixtures"
    "$work/decoder_tests"
fi
"$work/player_tests"

if [[ "${TSAN:-0}" == "1" ]]; then
    cc -O1 -g -fsanitize=thread -c src/third_party/sonic/sonic.c -o "$work/sonic-tsan.o"
    c++ -std=c++17 -O1 -g -pthread -fsanitize=thread -Itests/native/stubs -Isrc \
        tests/native/player_tests.cpp "$work/sonic-tsan.o" -o "$work/player_tests_tsan" -lm
    TSAN_OPTIONS=halt_on_error=1 "$work/player_tests_tsan"
fi
