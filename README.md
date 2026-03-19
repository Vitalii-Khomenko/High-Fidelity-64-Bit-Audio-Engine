# High-Fidelity 64-Bit Audio Engine — Android Music Player

An audiophile-grade, bit-perfect music player for Android built entirely in C/C++ with a Jetpack Compose UI.  Unlike standard players that rely on the Android OS Mixer (AudioFlinger) and its forced 48 kHz resampling, this engine communicates directly with the DAC via Google's **Oboe** framework.

---

## Architecture

```
Jetpack Compose UI
        │
        ▼
 PlaybackService (Foreground, MediaSession)
        │
        ▼
  AudioEngine.kt  ──JNI──▶  audio_engine_jni.cpp
                                    │
                                    ▼
                            AudioPlayer (C++)
                            ├── Decode Thread
                            │     ├── FLAC (dr_flac)
                            │     ├── MP3  (dr_mp3)
                            │     └── WAV  (dr_wav)
                            │
                            ├── Lock-Free RingBuffer<double>  (64-bit)
                            │
                            ├── DSP Pipeline
                            │     ├── GainProcessor  (anti-zipper volume)
                            │     └── BiquadFilter   (parametric EQ)
                            │
                            └── OboeAudioEndpoint
                                  └── Oboe → Hardware DAC (Float32)
```

---

## Key Features

### Audio Engine
| Feature | Detail |
|---|---|
| **Bit depth** | Full 64-bit `double` pipeline internally; 32-bit `float` to Oboe |
| **Formats** | FLAC, MP3, WAV (auto-detected from file content, not extension) |
| **Sample rates** | Source-native (44.1 / 48 / 88.2 / 96 / 192 kHz) |
| **Stream lifecycle** | Oboe stream is created **once** and kept alive; never closed between tracks — eliminates timing/resource bugs |
| **Speed control** | 0.25× – 4.0× with linear interpolation resampling |
| **Volume** | Anti-zipper gain smoothing (no click artefacts) |
| **Seeking** | Atomic seek-request model; safe across decode/audio threads |
| **Ring buffer** | Lock-free power-of-2 buffer; back-pressure aware decode loop |
| **TPDF Dither** | Mathematically fast dithering before bit-depth reduction |
| **Threading** | Decode thread + Oboe real-time callback thread; mutex-guarded decoder access |

### Android Integration
| Feature | Detail |
|---|---|
| **Scoped Storage** | File Descriptor passed natively via `/proc/self/fd/X` (Android 11+ compatible) |
| **Foreground Service** | `MediaSessionCompat` + notification with track title |
| **Audio Focus** | Full GAIN / LOSS / LOSS_TRANSIENT / DUCK handling; auto-resume only on transient interruptions (phone call, navigation) — never on user Stop |
| **Ghost playback prevention** | `START_NOT_STICKY` + `pausedByFocusLoss` flag; stopping playback releases audio focus and removes notification |
| **Playlists** | Room database with create / rename / delete / load |
| **Sleep Timer** | Countdown with 30-second fade-out |
| **Folder scan** | `DocumentsContract` tree picker with persisted URI permissions |

### UI (Jetpack Compose)
- Transport controls: Play/Pause, Stop (resets to beginning), Previous, Next
- Seek bar with real-time position / duration display
- Speed selector (0.5× / 0.75× / 1× / 1.25× / 1.5× / 2×)
- Volume slider
- Track info: title, sample rate (kHz), bit depth
- Playlist with now-playing indicator
- Dark/light Material 3 theme

---

## Building

```bash
# Prerequisites: Android Studio Hedgehog+, NDK r25+, CMake 3.22+
./gradlew assembleDebug
```

Gradle pulls Jetpack Compose and the Oboe C++ framework automatically.  CMake compiles the NDK native library.

Output APK: `app/build/outputs/apk/debug/app-debug.apk`

---

## File Structure

```
app/src/main/
├── java/com/aiproject/musicplayer/
│   ├── AudioEngine.kt          # JNI bridge
│   ├── PlaybackService.kt      # Foreground service + MediaSession + AudioFocus
│   └── MainActivity.kt         # Jetpack Compose UI
└── cpp/  (via CMakeLists.txt)
    src/
    ├── core/
    │   ├── AudioPlayer.h       # Main engine: decode thread, DSP, transport
    │   ├── RingBuffer.h        # Lock-free ring buffer (template)
    │   └── AudioBuffer.h       # Multi-channel sample buffer
    ├── hw/
    │   └── OboeAudioEndpoint.h # Oboe stream + real-time callback
    ├── dsp/
    │   ├── GainProcessor.h     # Volume with anti-zipper smoothing
    │   └── BiquadFilter.h      # Parametric EQ (IIR biquad)
    ├── decoders/
    │   ├── IAudioDecoder.h     # Decoder interface
    │   ├── FlacDecoder.h       # FLAC via dr_flac (single-header)
    │   ├── Mp3Decoder.h        # MP3  via dr_mp3  (single-header)
    │   └── WavDecoder.h        # WAV  via dr_wav  (single-header)
    └── jni/
        └── audio_engine_jni.cpp
```

---

## Why not AudioTrack / ExoPlayer?

Standard Android audio paths (AudioTrack, MediaPlayer, ExoPlayer) all route through **AudioFlinger**, the OS audio mixer, which:
- Resamples everything to 48 kHz (quality loss)
- Mixes in 16-bit integer arithmetic (precision loss)
- Adds 20–200 ms latency

This engine uses **Oboe in Shared mode** with Float32 output and source-native sample rates, keeping the full precision of the original file all the way to the DAC.
