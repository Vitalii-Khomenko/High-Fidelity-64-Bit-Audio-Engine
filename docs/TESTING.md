# Testing

## Native engine (host, no Android SDK)

```bash
bash tests/native/run.sh          # AddressSanitizer + UndefinedBehaviorSanitizer
TSAN=1 bash tests/native/run.sh   # additionally ThreadSanitizer on the player scenarios
```

Requires a C/C++17 compiler. With SoX, FLAC and LAME installed the script also
generates real fixtures (16-bit WAV, 24-bit AIFF, FLAC with and without
ReplayGain tags, MP3 CBR / VBR / VBR without Xing, a 10-minute MP3).

`decoder_tests.cpp`

- DSD: DSF and DFF files synthesised by a second-order sigma-delta modulator.
  Checks gain (±0.02 dB), in-band SNR against the modulator's own, the 70 kHz
  → 18.2 kHz alias (asserted ≥ 80 dB down, measured 110 dB), sample-exact
  seeking, EOF, rejection of
  malformed headers and descriptor ownership.
- ReplayGain: ID3v2.3/2.4 TXXX in Latin-1, UTF-16 and UTF-8, FLAC Vorbis
  comments, DSF metadata, peak limiting, untouched file offset.
- PCM precision: 32-bit integer and 64-bit float WAV are bit-exact.
- Encoded files: full decode, duration, seek accuracy (exact for WAV/AIFF/FLAC),
  reopen, failed reopen; MP3 seek-table speed.
- DSP: lock-free ring under concurrent flushes, Sonic speeds and resolution
  (quiet and over-full-scale tones), EQ response, downmix matrix.

`player_tests.cpp` runs `AudioPlayer` against a simulated Oboe stream
(`stubs/oboe/Oboe.h`): playing to the end with drain, pause/resume without a
decoder seek and without losing samples, immediate volume, seeks while playing
and paused, gapless switch (sample count and per-track gain), format-change
transition, multichannel fallback to stereo, output failure, device reconnect,
seek and clear-next during the gapless look-ahead window, a reconnect whose
restart keeps failing, limiter ceiling with EQ boost, time-stretch timing, spectrum, concurrent control
from several threads and destruction while playing.

The stubs exist only on the test include path; Android builds use real Oboe.

## JVM unit tests

```bash
./gradlew testDebugUnitTest
```

Queue and shuffle logic (`PlaybackQueueTest`), audio focus policy, supported
formats and sorting, DLNA protocol (paging, containers, XML hardening), EQ
settings, speed clamping, DSD labels, library folder serialisation.

## Instrumented tests

```bash
./gradlew connectedDebugAndroidTest
```

Needs a device or emulator: service queue commands and notification actions,
SAF scanning through a test DocumentsProvider, DLNA browse and download
against MockWebServer.

## Lint

```bash
./gradlew lintDebug
```

## Screenshots

`docs/screenshots` were rendered on the JVM with Paparazzi from `AppContent`
(the UI takes plain state, so no service is needed). Paparazzi is not part of
the main build because it downloads layoutlib and needs network access.

## On a real device

Host tests cannot cover the audio HAL, Bluetooth or OEM power management.
Before treating a build as stable, check:

1. WAV, FLAC, MP3 (VBR without Xing), DSF and DFF; the first and last seconds of short tracks.
2. Pause / resume (no repeated or missing audio), stop / play, quick track changes, seeking while playing and paused.
3. Speeds 0.75 – 2.0× in both profiles, switching back to 1.00×, EQ changes during playback.
4. Gapless albums, repeat one / all, shuffle; changing the queue seconds before a track ends.
5. Headphones and Bluetooth: unplug (pauses), reconnect, switching outputs while playing; a call or voice message and return of focus.
6. Screen off for 30 minutes; sleep timer; leaving and reopening the app; notification and lock-screen controls with the app closed.
7. SAF folders, saved playlists, Books bookmarks after a restart, DLNA on your server.

Useful reports include the phone model, Android version, file format and rate,
and the exact steps.
