# Testing

## Native engine (host, no Android SDK)

```bash
bash tests/native/run.sh          # AddressSanitizer + UndefinedBehaviorSanitizer
TSAN=1 bash tests/native/run.sh   # additionally ThreadSanitizer on the player scenarios
```

Requires a C/C++17 compiler. The third-party decoders (Ogg, Vorbis, Opus,
WavPack, Monkey's Audio, TTA) are built once without sanitizers and cached in
`tests/native/.cache` (rebuilt when a source changes). With SoX, FLAC and LAME
installed the script also generates real fixtures (16-bit WAV, 24-bit AIFF,
FLAC with and without ReplayGain tags, MP3 CBR / VBR / VBR without Xing, a
10-minute MP3, Ogg Vorbis with a comment).

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

`format_tests.cpp`

- WavPack, Monkey's Audio and TTA, 16 and 24 bit, stereo and mono 96 kHz,
  encoded in the test with the same libraries: bit-exact decode, exact length,
  sample-exact seeks (including to the very end), refusal of damaged files.
- Ogg Opus written by the test (libopus + libogg, pre-skip and end trimming):
  exact gapless length, level, aligned seeks that converge within 100 ms, tags
  and R128 gain. Ogg Vorbis from SoX: length, level, exact seeks, comments.
- MediaCodec path against a fake extractor / codec (`stubs/media`): float and
  16-bit output, codec latency of 0–5 packets, encoder delay and padding
  trimming, exact seeks, ALAC bit depth, a phone without a decoder.
- CUE ranges: exact cut points, three ranges of one file join to the original
  sample for sample, invalid ranges refused.
- Container sniffing for every signature, with an ID3v2 tag in front.
- Duration without decoding: WAV, WavPack, TTA, APE, Opus, FLAC, Vorbis,
  MP3 with a Xing header and CBR.
- Tags: FLAC (comments, front vs back cover), ID3v2.3 (UTF-16, Windows-1251
  text labelled Latin-1, Latin-1, `USLT`, two `APIC`), ID3v2.4 (UTF-8, multiple
  values, frame unsynchronisation, data length indicator), whole-tag
  unsynchronisation, ID3v1 fallback, APEv2 with a binary cover, MP4 `ilst`
  with freeform ReplayGain, WAV `LIST/INFO`, untagged files.

`player_tests.cpp` runs `AudioPlayer` against a simulated Oboe stream
(`stubs/oboe/Oboe.h`): playing to the end with drain, pause/resume without a
decoder seek and without losing samples, immediate volume, seeks while playing
and paused, gapless switch (sample count and per-track gain), format-change
transition, multichannel fallback to stereo, output failure, device reconnect,
seek and clear-next during the gapless look-ahead window (also with a next
track shorter than the look-ahead), clearing a format-change transition while
draining, a pause during a decoder read, format queries during a slow stream
open, NaN/Inf float WAV input, `play()` racing a gapless switch (TSan), a
reconnect whose restart keeps failing, limiter ceiling with EQ boost, time-stretch timing, spectrum, concurrent control
from several threads and destruction while playing.

The stubs exist only on the test include path; Android builds use real Oboe.

## JVM unit tests

```bash
./gradlew testDebugUnitTest
```

Queue and shuffle logic (`PlaybackQueueTest`), audio focus policy, supported
formats and sorting (including album order), CUE sheets (timing, pregaps,
one file per track, code pages) and range URIs, the native tag record, library
grouping keys and search patterns, media ids (incl. albums / artists), DLNA protocol (paging, containers, XML hardening), EQ
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

## Android Auto

`MediaBrowserInstrumentedTest` connects like Android Auto and checks the root
of the browse tree (`connectedDebugAndroidTest`). For the real head unit:

1. On the phone: Android Auto settings → tap the version ten times →
   developer settings → enable **Unknown sources** (needed for apps not
   installed from Google Play) and **Start head unit server**.
2. On the computer: `sdkmanager "extras;google;auto"`, then
   `adb forward tcp:5277 tcp:5277` and run `desktop-head-unit` from
   `$ANDROID_HOME/extras/google/auto`.
3. Check: Queue / Playlists / Folders browse, play from each, next/previous and
   seek from the car, voice "play <title> on HiFi Player", an unreadable file
   (error on the car screen), and phone-to-car hand-over while playing.

## On a real device

Host tests cannot cover the audio HAL, Bluetooth or OEM power management.
Before treating a build as stable, check:

1. WAV, FLAC, MP3 (VBR without Xing), DSF and DFF, WavPack, APE, TTA, Ogg Vorbis, Opus, M4A (AAC and ALAC), an HE-AAC stream; the first and last seconds of short tracks.
1. A CUE album (FLAC + CUE, and APE + CUE whose sheet names a `.wav`): track list, gapless joins, seeking inside a track, the last track's length.
2. Pause / resume (no repeated or missing audio), stop / play, quick track changes, seeking while playing and paused.
3. Speeds 0.75 – 2.0× in both profiles, switching back to 1.00×, EQ changes during playback.
4. Gapless albums, repeat one / all, shuffle; changing the queue seconds before a track ends.
5. Headphones and Bluetooth: unplug (pauses), reconnect, switching outputs while playing; a call or voice message and return of focus.
6. Screen off for 30 minutes; sleep timer; leaving and reopening the app; notification and lock-screen controls with the app closed.
7. SAF folders, saved playlists, Books bookmarks after a restart, DLNA on your server.
8. Library: first indexing of a large folder (progress, time), albums and artists, search in Cyrillic, covers (embedded and `cover.jpg`), adding a file and *Update library*; lock-screen and notification cover; albums with covers in Android Auto.

Useful reports include the phone model, Android version, file format and rate,
and the exact steps.
