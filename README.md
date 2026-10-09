# HiFi Player — native 64-bit audio engine for Android

**English** · [Русский](README.ru.md)

An Android music and audiobook player built around its own C++ playback engine:
decoders, 64-bit DSP, a DSD-to-PCM decimator, gapless transitions and an Oboe
output stage, with a Kotlin / Jetpack Compose app on top.

<p>
  <img src="docs/screenshots/player_dark.png" width="200" alt="Player, dark theme">
  <img src="docs/screenshots/player_light.png" width="200" alt="Player, light theme">
  <img src="docs/screenshots/sound_dark.png" width="200" alt="Sound settings">
  <img src="docs/screenshots/library_dark.png" width="200" alt="Library">
</p>

## What the engine does

| | |
|---|---|
| **Formats** | FLAC, WAV / RF64 / W64, AIFF / AIFC, MP3 (CBR, VBR, with or without Xing), DSF and DSDIFF (DSD64 – DSD512) |
| **Precision** | Decoding, EQ, ReplayGain and the DSD decimator run in `double`. Integer PCM up to 32 bit and 64-bit float WAV enter the pipeline bit-exact. The device receives 32-bit float. |
| **DSD** | Two-stage linear-phase FIR decimation to 88.2 / 96 kHz: flat to 24 kHz, aliases suppressed by 110 dB, unity gain. |
| **Gapless** | The next track is pre-opened; same-format tracks join sample-exactly, a format change reopens the stream after the queue drains. |
| **Transport** | Pause and resume stop and restart consumption at an exact frame — no re-seek, no lost samples. Volume and fades act after the buffer, so they are heard immediately. |
| **Speed** | 0.75× – 2.0× with pitch preserved (Sonic, converted to float processing). At exactly 1.00× samples pass untouched. |
| **Loudness** | ReplayGain (track / album) read from Vorbis comments and ID3v2 (MP3, WAV, AIFF, DSF) and limited by the tagged peak; EQ boosts lower the level automatically; a peak limiter guards the output. |
| **Robustness** | Device switches (Bluetooth, USB, headphones) reopen the stream with the same format; unsupported multichannel layouts fold down to stereo; Oboe resamples when the device refuses a rate. |

Measured by the native test suite (`tests/native`), on the host:

- DSD64 1 kHz tone: gain error 0.0000 dB; in-band SNR equals the test modulator's own (71.8 vs 71.7 dB) — the decimator adds no measurable noise.
- DSD64 70 kHz tone: the 18.2 kHz alias is 110 dB below the input.
- Time-stretch at 1.25×, −66 dBFS tone: SNR 149.5 dB (the original 16-bit Sonic: 31 dB).
- MP3, 10 minutes: 20 seeks near the end take ~18 ms in total (seek table built while opening).

Details: [docs/AUDIO_ENGINE.md](docs/AUDIO_ENGINE.md).

## The app

- **Player** — now playing with format chips (codec, rate, bits, DSD, ReplayGain, output), a pixel spectrum of what is actually being heard, transport, shuffle / repeat, and the queue.
- **Library** — folders through the Storage Access Framework (with a folder browser), a device scan (MediaStore), DLNA / UPnP servers with folder navigation, and saved playlists.
- **Sound** — volume, speed and time-stretch profile, 5-band EQ, ReplayGain mode.
- **Settings** — theme (system / dark / light), *Music* or *Books* listening mode (Books keeps a bookmark per file and marks finished chapters), sleep timer with a 30-second fade, and the signal path from file to device.

Playback lives in a foreground service with a media session: notification,
lock screen, headset and Bluetooth buttons, audio focus and "becoming noisy"
all work without the UI. The visual design follows [airwitech.com](https://airwitech.com):
ink and paper colours, violet / amber / cyan tones, Sora and Source Sans 3,
square shapes, hairline rules and pixel glyphs.

## Build

Requirements: JDK 17 or 21, Android SDK 35, NDK 28.2.13676358, CMake 3.22.1.

```bash
./gradlew assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. Release signing,
the side-by-side `audit` variant and toolchain notes are in
[docs/BUILDING.md](docs/BUILDING.md).

## Tests

```bash
bash tests/native/run.sh                 # engine: ASan + UBSan, real WAV/AIFF/FLAC/MP3 fixtures
TSAN=1 bash tests/native/run.sh          # plus ThreadSanitizer on the player scenarios
./gradlew testDebugUnitTest lintDebug    # JVM tests and Android lint
```

The native suite needs only a C/C++17 compiler (SoX, FLAC and LAME enable the
encoded-file fixtures). See [docs/TESTING.md](docs/TESTING.md), including the
checklist for real devices.

## Documentation

| | |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Layers, threads, data flow, persistence |
| [docs/AUDIO_ENGINE.md](docs/AUDIO_ENGINE.md) | The native engine in depth |
| [docs/BUILDING.md](docs/BUILDING.md) | Toolchain, variants, signing |
| [docs/TESTING.md](docs/TESTING.md) | Automated tests and the device checklist |
| [docs/ROADMAP.md](docs/ROADMAP.md) | Known limitations and next steps |
| [CHANGELOG.md](CHANGELOG.md) | Release notes |
| [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) | Bundled libraries and fonts |

## Honest limits

Android mixes all apps in shared mode, so the system may resample and apply its
own volume; this player does not claim bit-perfect output. DSD is converted to
PCM (no DoP). See [docs/ROADMAP.md](docs/ROADMAP.md).

## License

MIT — see [LICENSE](LICENSE). Third-party components keep their own licenses
([THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)).
