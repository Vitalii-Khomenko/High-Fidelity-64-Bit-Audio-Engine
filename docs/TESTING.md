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

`dsp_tests.cpp`

- Parametric EQ: response peak of stacked bands, cuts need no headroom,
  high-pass attenuation.
- Crossfeed: mono at unity, bass fed across at the preset level (−4.5 dB),
  treble kept on its side, *Off* bit-exact.
- True peak of an fs/4 sine sampled at ±0.707 (≈ 0 dBTP).
- Limiter in random block sizes: bit-exact below the ceiling, no output true
  peak above −1 dBTP at +6 dB input, nothing added or lost.
- Loudness: EBU Tech 3341 sine at −23 dBFS = −23.0 LUFS at 44.1 / 48 / 96 kHz,
  absolute and relative gating, silence; a whole WAV file and a CUE range
  through the decoder path.

`player_tests.cpp` runs `AudioPlayer` against a simulated Oboe stream
(`stubs/oboe/Oboe.h`): playing to the end with drain, pause/resume without a
decoder seek and without losing samples, immediate volume, seeks while playing
and paused, gapless switch (sample count and per-track gain), format-change
transition, multichannel fallback to stereo, output failure, device reconnect,
seek and clear-next during the gapless look-ahead window (also with a next
track shorter than the look-ahead), clearing a format-change transition while
draining, a pause during a decoder read, format queries during a slow stream
open, NaN/Inf float WAV input, `play()` racing a gapless switch (TSan), a
reconnect whose restart keeps failing, limiter ceiling with EQ boost, switching the limiter and crossfeed while
playing and across a gapless boundary (every frame exactly once), DLNA
streaming (a pause during a download stall resumes without a lost or repeated
frame; a failed download ends the track instead of hanging), time-stretch timing, spectrum, concurrent control
from several threads and destruction while playing. Direct output: a 16-bit
source arrives in 24-bit sample for sample, a −6.02 dB volume stays exact
without dither while 0.3 is dithered and reported, a refused format falls back
to the float mixer stream, the app is asked again on a rate change across
tracks, and leaving direct mode while playing keeps the position.

`usb_tests.cpp` drives the own USB driver against a simulated DAC (its own
clock, feedback endpoint, control requests, unplug): UAC2 and UAC1 descriptors,
rates from clock ranges and the packet-size limit, rate and alternate setting
requests, interfaces kept across tracks and handed back, feedback in Q16.16
(high speed) and Q10.14 (full speed) and from a DAC reporting in the wrong unit,
a DAC 80 ppm fast for 60 s without drift, UAC1 payload sample for sample, and
the player playing through it bit-exact with a pause (no queued audio cut off),
converting to a rate the DAC has, handing the DAC back, and an unplug while
playing that does not continue on the speaker.

`dsp_tests.cpp` checks the engine's sample-rate converter (44.1 → 48 kHz level
and residual below −120 dB, flat at 19.9 kHz, a 30 kHz tone at 96 kHz kept
130 dB below 18 kHz after 96 → 48 kHz, the same samples for any callback size)
and the player runs a 44.1 kHz track into a 48 kHz device with an exact
position across pause / resume. It also checks the device PCM encoder: exact 16/24/32-bit and
float values pass unchanged, overs clamp, and a quarter-LSB signal keeps its
mean under TPDF dither.

The stubs exist only on the test include path; Android builds use real Oboe.

## JVM unit tests

```bash
./gradlew testDebugUnitTest
```

Queue and shuffle logic (`PlaybackQueueTest`), audio focus policy, supported
formats and sorting (including album order), CUE sheets (timing, pregaps,
one file per track, code pages) and range URIs, the native tag record, library
grouping keys and search patterns, AutoEQ / Equalizer APO parsing, the AutoEQ index and
profile URLs, EQ profile storage, R128 → ReplayGain maths, LRC lyrics (offsets, repeated and word time tags, plain text), the DLNA renderer (SOAP actions, faults, state
queries, volume, time / DIDL / SSDP / LastChange formats, and an end-to-end
run over real sockets: description, SOAP Play, SUBSCRIBE and NOTIFY), media ids (incl. albums / artists), DLNA protocol (paging, containers, XML hardening), EQ
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

## On real devices

Testing on phones and USB DACs has started and continues with every release,
alongside the host suites above (which cover the engine, the USB driver against a
simulated DAC, and the app logic, but not the audio HAL, Bluetooth or OEM power
management).

### Status

| Device | Confirmed | In progress |
|---|---|---|
| Samsung Galaxy A55 | Playback and the library (incl. *Folders*), the English interface with Russian file names and tags, the signal-path indicator (mixed output on the speaker), the DLNA renderer switching on and off (0.19.1), a USB DAC connected | Volume with the USB DAC after the decibel scale (0.19.2), *Open with* / *Share*, the own USB driver |
| Xiaomi phone with a USB DAC | The DAC is recognised; the phone offers no bit-perfect mixer attributes, and the app says so instead of claiming bit-perfect | The own USB driver with this DAC |

Still to cover: hours-long runs with the own driver (clock drift), several DACs
(UAC1, two UAC2 vendors, hardware volume, native DSD), Bluetooth codecs, Android
Auto, and a phone with working Android 14 bit-perfect output (e.g. a Pixel).
Reports with phone model, Android version, DAC, file format and steps go into
this table.

### Checklist

Before treating a build as stable, check:

1. WAV, FLAC, MP3 (VBR without Xing), DSF and DFF, WavPack, APE, TTA, Ogg Vorbis, Opus, M4A (AAC and ALAC), an HE-AAC stream; the first and last seconds of short tracks.
1. A CUE album (FLAC + CUE, and APE + CUE whose sheet names a `.wav`): track list, gapless joins, seeking inside a track, the last track's length.
2. Pause / resume (no repeated or missing audio), stop / play, quick track changes, seeking while playing and paused.
3. Speeds 0.75 – 2.0× in both profiles, switching back to 1.00×, EQ changes during playback.
4. Gapless albums, repeat one / all, shuffle; changing the queue seconds before a track ends.
5. Headphones and Bluetooth: unplug (pauses), reconnect, switching outputs while playing; a call or voice message and return of focus.
6. Screen off for 30 minutes; sleep timer; leaving and reopening the app; notification and lock-screen controls with the app closed.
7. SAF folders, saved playlists, Books bookmarks after a restart, DLNA on your server.
11. Lyrics: a `.lrc` next to a track and lyrics in tags; the home-screen widget with the app closed.
10. DLNA: a large FLAC from your server starts before it has downloaded; the renderer with BubbleUPnP (or Windows "Cast to device"): play, pause, seek, volume, next track gapless.
9. Sound: an AutoEQ profile for your headphones (search, apply, switch back to 5 bands), crossfeed presets with headphones, *Measure library* on a few albums without tags and their level against tagged ones, the limiter with EQ boosts.
8. Library: first indexing of a large folder (progress, time), albums and artists, search in Cyrillic, covers (embedded and `cover.jpg`), adding a file and *Update library*; lock-screen and notification cover; albums with covers in Android Auto.
12. Bit-perfect USB (Android 14+, a DAC that shows its input rate): turn it on, play 44.1 / 48 / 88.2 / 96 / 192 kHz files and check the DAC display and the *BIT-PERFECT* chip; volume keys change the level; volume below 100 % or EQ shows *PROCESSED*; plug the DAC in and out while playing; turn the setting off while playing (back to *MIXED*, other apps audible again).
13. Own USB driver (any Android version, any UAC1/UAC2 DAC): turn it on, allow access, play 44.1 / 48 / 96 / 192 kHz and check the DAC display and the *USB DRIVER* chip; pause and resume (no click); a long album for clock drift (no clicks after an hour); unplug while playing (pauses), plug in again; turn the driver off (Android plays to the DAC again). Useful logs: `adb logcat -s AudioEngine`.
14. Library → Folders: a folder of mixed artists plays in file-name order; search by folder name. A file manager's *Open with* and *Share* (one and several files) start playback and show the player; a `.m3u` is refused. Russian file names and tags display correctly in the English interface.

Useful reports include the phone model, Android version, file format and rate,
and the exact steps.
