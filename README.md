# HiFi Player — 64-Bit Hi-Fi Audio Engine for Android

A professional-grade music player for Android built on a fully custom 64-bit C++ audio engine. Designed for audiophiles, audiobook listeners, and anyone who cares about sound quality.

---

## Features

### Audio Engine
- **64-bit double-precision DSP pipeline** — all signal processing runs at full `double` precision before being downsampled to 32-bit float for output
- **Oboe audio backend** — low-latency stream stays alive between tracks (no close/reopen per track), eliminating dropouts and timing issues
- **Lock-free ring buffer** — decode thread and audio callback communicate via a power-of-2 ring buffer with no mutexes on the hot path
- **Variable playback speed** — real-time pitch-transparent speed control with linear interpolation in the decode loop
- **Volume control** — anti-zipper smoothing via `GainProcessor`
- **Parametric EQ** — `BiquadFilter` implementing all 7 RBJ EQ Cookbook filter types: LowPass, HighPass, BandPass, Notch, AllPass, LowShelf, HighShelf

### Supported Formats
| Format | Decoder | Notes |
|--------|---------|-------|
| MP3    | dr_mp3  | CBR and VBR (Xing/Info header optional) |
| FLAC   | dr_flac | Lossless, up to 32-bit/192 kHz |
| WAV    | dr_wav  | PCM and IEEE float |
| DSD    | stub    | Architecture stub — full libusb integration pending |

### Android Integration
- **Foreground service** (`PlaybackService`) keeps audio running when the app is in the background
- **MediaSession + MediaStyle notification** — lock-screen and notification-shade controls with Previous / Play-Pause / Next buttons
- **Hardware media button support** — headset buttons, Bluetooth remotes, and car audio controls all work via `MediaButtonReceiver`
- **Audio focus** — automatically pauses on phone calls and other audio interruptions, resumes when focus is regained
- **Notification progress bar** — auto-advances without polling using `SystemClock.elapsedRealtime()` and playback speed metadata
- **Async track loading** — file scanning and decoder initialization run on `Dispatchers.IO` with a `Mutex` preventing race conditions; the UI never freezes

### Playlist & Audiobook Features
- **Persistent played-track tracking** — tracks you've listened to are highlighted in the playlist with a green check mark; state survives app restarts (stored in SharedPreferences by URI)
- **Clear progress** — one tap resets the audiobook progress markers
- **State restoration** — current track is restored after Activity recreation (rotation, background kill)
- **Playlist management** — create, load, and manage playlists backed by a Room database
- **Auto-advance** — automatically plays the next track when one finishes

### USB Audio (Architecture Stub)
- `UsbAudioEndpoint` defines the architecture for bypassing Android's mixer (which resamples to 48 kHz) and streaming raw PCM or DoP packets directly to a USB DAC via libusb isochronous transfers
- Requires `UsbManager` permission grant in Java + fd passed to native via JNI

---

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                  Android UI Layer                    │
│  MainActivity (Jetpack Compose) + PlaybackService    │
│  MediaSessionCompat · AudioFocus · Notification      │
└───────────────────┬─────────────────────────────────┘
                    │ JNI (audio_engine_jni.cpp)
┌───────────────────▼─────────────────────────────────┐
│                  C++ Audio Engine                    │
│                                                      │
│  IAudioDecoder ──► AudioPlayer ──► RingBuffer<double>│
│  (Mp3/Flac/Wav)    decode thread    lock-free        │
│                    DSP chain                         │
│                    GainProcessor                     │
│                    BiquadFilter                      │
└───────────────────┬─────────────────────────────────┘
                    │ Oboe callback
┌───────────────────▼─────────────────────────────────┐
│             OboeAudioEndpoint                        │
│  Long-lived stream · double→float · underrun silence │
└─────────────────────────────────────────────────────┘
```

---

## Building

### Prerequisites
- Android Studio Hedgehog or later **or** command-line tools only
- Android NDK r25c+ (install via SDK Manager or `sdkmanager "ndk;25.2.9519653"`)
- Java 17+

### Clone
```bash
git clone https://github.com/Vitalii-Khomenko/High-Fidelity-64-Bit-Audio-Engine.git
cd High-Fidelity-64-Bit-Audio-Engine
```

### Debug APK (development / sideload)
```bash
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

### Release APK (signed, for distribution)
```bash
# 1. Create a keystore (one-time)
keytool -genkeypair -v -keystore hifi-player.jks -alias hifi -keyalg RSA -keysize 2048 -validity 10000

# 2. Add signing config to local.properties (never commit this file)
echo "KEYSTORE_FILE=../hifi-player.jks"   >> local.properties
echo "KEYSTORE_PASSWORD=your_password"    >> local.properties
echo "KEY_ALIAS=hifi"                     >> local.properties
echo "KEY_PASSWORD=your_password"         >> local.properties

# 3. Build
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

### Install directly to a connected device / emulator
```bash
./gradlew installDebug
# or with adb:
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Clean build
```bash
./gradlew clean assembleDebug
```

### Build on Windows (Command Prompt / PowerShell)
```bat
gradlew.bat assembleDebug
```

---

## Technical Notes

### MP3 VBR Fix
Variable-bitrate MP3 files without an Xing/Info header caused tracks to stop after ~1 second. Root cause: `drmp3_get_pcm_frame_count()` scans the entire file, leaving the file offset at EOF. `drmp3_seek_to_pcm_frame(0)` silently fails for such files. Fix: after frame-count scan, call `drmp3_uninit()`, `lseek(fd, 0, SEEK_SET)`, then `drmp3_init()` again for playback.

### Oboe Stream Lifecycle
The Oboe stream is created once when the engine is initialized and kept alive for the entire session. Between tracks, only the decoder is swapped out. This avoids the latency, resource exhaustion, and timing bugs that occur when closing and reopening a stream for each track.

### Double-Tap / Race Condition Prevention
`isLoadingTrack` in the UI debounces rapid track selections. `loadMutex` (Kotlin coroutine `Mutex`) in `PlaybackService` serializes concurrent JNI decoder loads so only one native operation runs at a time even if the user taps quickly.

---

## Build Requirements

- Android Studio Hedgehog or later
- NDK r25c or later (C++17)
- `minSdk 26` (Android 8.0) — required for `AudioFocusRequest` API
- `targetSdk 35`
- Gradle 8.x

### Dependencies
- Oboe (audio HAL, included via CMake `find_package`)
- dr_libs (dr_mp3, dr_flac, dr_wav — single-header, included in `src/decoders/`)
- AndroidX Media (`androidx.media:media:1.6.0`) — MediaSessionCompat, MediaButtonReceiver
- Jetpack Compose + Material3
- Room (playlist database)

---

## Project Structure

```
MusicPlayerPro/
├── app/src/main/
│   ├── java/com/aiproject/musicplayer/
│   │   ├── MainActivity.kt          # Compose UI, playlist, playback controls
│   │   ├── PlaybackService.kt       # Foreground service, MediaSession, audio focus
│   │   ├── AudioEngine.kt           # Kotlin JNI wrapper
│   │   └── db/                      # Room database (playlists)
│   ├── res/
│   │   ├── drawable/                # ic_launcher_foreground/background (equalizer icon)
│   │   ├── mipmap-anydpi-v26/       # Adaptive icon
│   │   └── values/                  # strings.xml, themes.xml
│   └── AndroidManifest.xml
└── src/
    ├── core/
    │   └── AudioPlayer.h            # Main engine: decoder + DSP + ring buffer
    ├── decoders/
    │   ├── IAudioDecoder.h
    │   ├── Mp3Decoder.h
    │   ├── FlacDecoder.h
    │   ├── WavDecoder.h
    │   └── DsdDecoder.h
    ├── dsp/
    │   ├── GainProcessor.h
    │   └── BiquadFilter.h
    ├── hw/
    │   ├── IAudioEndpoint.h
    │   ├── OboeAudioEndpoint.h
    │   └── UsbAudioEndpoint.h       # Architecture stub
    └── jni/
        └── audio_engine_jni.cpp
```

---

## License

MIT License — see [LICENSE](LICENSE) for details.
