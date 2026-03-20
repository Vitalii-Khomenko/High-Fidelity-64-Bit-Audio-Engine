# HiFi Player — 64-Bit Native Audio Engine for Android

> A professional-grade music and audiobook player built on a fully custom 64-bit C++ audio engine.
> No resampling. No bit-crushing. No compromises.

---

## Why HiFi Player Is Better Than Every Typical Android Player

Most Android music players — including Spotify, YouTube Music, Poweramp, and BlackPlayer — share a fundamental architectural limitation: **they rely on Android's MediaPlayer or AudioTrack APIs, which internally resample all audio to 48 kHz / 16-bit before it ever reaches the hardware.** Your 192 kHz FLAC file gets silently degraded before a single sample reaches your ears.

HiFi Player is built differently, from the ground up:

| What Matters | Typical Android Player | HiFi Player |
|---|---|---|
| **DSP precision** | 32-bit float or 16-bit int | **64-bit double** throughout the entire pipeline |
| **Audio path** | Android MediaPlayer → Java AudioTrack → mixer → resampling | **Native C++ → Oboe → hardware HAL**, bypassing the Java audio stack |
| **Between-track gap** | Stream closed and reopened per track (clicks, dropouts) | **Oboe stream stays alive + gapless pre-load** — decoder swapped atomically at EOF, literally zero gap |
| **MP3 VBR handling** | Often silently truncated or seeks incorrectly | **Full VBR support** with double-init fix for files without Xing/Info header |
| **Track switching** | Immediate cut — audible click | **200 ms fade-out + fade-in** on every transition |
| **Audiobook resume** | Per-app, single position saved | **Per-track bookmarks** — every chapter remembers its own position independently |
| **UI freeze on load** | Common — file scan blocks main thread | **IO coroutine + Mutex** — UI never freezes, even on large FLAC files |
| **Bit depth** | Downsampled to 16-bit by Android mixer | **32-bit float output** to Oboe, preserving full dynamic range |
| **EQ** | None, or basic preset-only | **7-band parametric EQ** — RBJ Cookbook: LP, HP, BP, Notch, AP, LowShelf, HighShelf |
| **USB DAC** | Not supported | **Architecture ready** — UsbAudioEndpoint stub for direct isochronous transfer to USB DAC, bypassing Android's 48 kHz mixer entirely |
| **Output device detection** | Not shown | **Live headset info** — shows device name, connection type (USB / Bluetooth A2DP / Wired), max supported sample rate and bit depth |
| **Playback speed** | None or MediaPlayer-based (degrades quality) | **Pitch-transparent speed control** via linear interpolation in the native decode loop |
| **Repeat modes** | Basic | Off / Repeat One / Repeat All with per-track bookmark preservation |

### The 64-Bit Difference

When your DAC plays back a 24-bit / 96 kHz recording, the difference between noise floor and maximum signal is 144 dB. At 16-bit that headroom collapses to 96 dB. **A single rounding error in 32-bit float processing at high sample rates introduces quantization noise that is audible on high-end headphones.**

HiFi Player keeps every sample in `double` (64-bit, 53-bit mantissa) from the moment it leaves the decoder until the very last step before the Oboe callback, where it is converted to `float` for hardware output. This eliminates accumulated rounding error across the EQ, gain, and speed-change stages.

### The Oboe Advantage

Android's standard `AudioTrack` has 20–50 ms latency and introduces its own resampling. **Oboe** (Google's low-latency audio library, the same backend used by professional audio apps like FL Studio Mobile) operates at hardware-native sample rates with latency as low as 1 ms on supported devices.

---

## Features

### Audio Engine (C++ Native)
- **64-bit double-precision DSP** — full `double` pipeline from decoder output to Oboe callback
- **Oboe audio backend** — persistent low-latency stream, never closed between tracks
- **Lock-free ring buffer** — power-of-2 `RingBuffer<double>` between decode thread and audio callback, zero mutex on the hot path
- **Smooth volume transitions** — `GainProcessor` with anti-zipper smoothing eliminates all pops
- **Fade-out on pause/stop** — 200 ms ramp to silence before any transport operation
- **Fade-in on track start** — 300 ms ramp from silence after every track load
- **Pitch-transparent speed control** — linear interpolation in the decode loop, 0.5×–2.5×
- **7-type parametric EQ** — RBJ EQ Cookbook BiquadFilter: LowPass, HighPass, BandPass, Notch, AllPass, LowShelf, HighShelf
- **ReplayGain** — automatic loudness normalization; scans FLAC vorbis comments and MP3 ID3v2 for `REPLAYGAIN_TRACK_GAIN`; applied as a `GainProcessor` multiplier; displayed in UI as `±X.X dB`
- **Gapless playback** — `m_nextDecoder` slot pre-loads the next track ~8 s before EOF; at EOF the C++ engine swaps decoders atomically with no stream interruption, no click, no silence
- **Spectrum analyzer** — 2048-point Hann-windowed Cooley-Tukey FFT; 32 logarithmic bands with per-band attack/decay smoothing; rendered as a live hue-gradient bar chart (blue → red by amplitude)

### Supported Formats
| Format | Decoder | Notes |
|--------|---------|-------|
| **FLAC** | dr_flac | Lossless, up to 32-bit / 192 kHz |
| **MP3**  | dr_mp3  | CBR and VBR — full Xing/Info-less VBR fix applied |
| **WAV**  | dr_wav  | PCM 8/16/24/32-bit and IEEE float |
| **DSD**  | DsdDecoder | DSF + DSDIFF (DFF) — 16× decimation → DoP-rate PCM; DSD64/DSD128/DSD256/DSD512 |

### Playback & UX
- **Smooth track transitions** — fade-out → silence → load → seek → fade-in, no audible clicks
- **Auto-advance** — plays next track automatically at end; respects repeat mode
- **Repeat modes** — Off / Repeat One / Repeat All (button in transport controls)
- **Variable speed** — 0.5×–2.5× with 9 preset steps
- **Sleep timer** — 5 / 10 / 15 / 30 / 60 minutes with 30-second volume fade-out

### Audiobook Features
- **Per-track position bookmarks** — every track independently remembers where you stopped; switching chapters and returning resumes at the exact second
- **Played-track indicators** — green check mark on every track you've finished; resets on explicit Clear
- **Persistent state across restarts** — playlist, current track, and position survive app kill and device reboot (SharedPreferences + Room DB)
- **Auto-scroll** — playlist smoothly scrolls to the current chapter on every track change

### Android System Integration
- **Foreground service** — audio continues when screen is off or app is minimized
- **MediaSession + MediaStyle notification** — lock-screen and notification-shade controls with Previous / Play-Pause / Next; progress bar auto-advances without polling
- **Hardware media button support** — headset buttons, Bluetooth remotes, car audio via `MediaButtonReceiver`
- **Audio focus** — pauses on phone calls / navigation prompts, resumes after
- **Stops on swipe-away** — when user removes app from recents, playback stops and notification is cleared
- **Async loading** — `Dispatchers.IO` coroutine + `Mutex` serializes JNI loads; UI is always responsive

### Output Device Detection & Bluetooth Codec
- Detects connected headset / DAC in real time
- Shows device name, connection type (USB / Bluetooth A2DP / Bluetooth LE / Wired), maximum supported sample rate and bit depth
- **Bluetooth codec**: reads active A2DP codec via `BluetoothA2dp` profile proxy — displays LDAC / aptX HD / aptX / AAC / SBC next to device name
- Updates automatically on connect/disconnect
- Priority: USB DAC > USB headset > Bluetooth A2DP > Bluetooth LE > wired

### Network Sources (DLNA/UPnP)
- **SSDP discovery** — scans local network for UPnP MediaServer:1 devices (Plex, Emby, Jellyfin, Kodi, Windows Media Player, etc.)
- **ContentDirectory browse** — SOAP request returns up to 500 audio tracks per container
- **Add to playlist** — add individual tracks or entire server library to the current playlist
- **HTTP cache** — DLNA tracks are downloaded to local cache on first play; subsequent plays are instant
- Access via **⋮ → Browse Network (DLNA)** — scan starts automatically on open

### Playlist Management
- **Add Folder** — grant persistent URI permission to any folder; loads on every restart automatically
- **Scan MediaStore** — discover all audio files on the device
- **Save / Load / Rename / Delete** playlists — backed by Room SQLite database
- **Folder path** shown under each track name in the playlist
- **Clear** — removes playlist and resets all audiobook progress markers

---

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                     Android UI Layer                          │
│   MainActivity (Jetpack Compose)  ·  PlaybackService          │
│   MediaSessionCompat  ·  AudioFocus  ·  MediaStyle Notif.     │
│   Room DB (playlists)  ·  SharedPreferences (bookmarks)       │
└────────────────────────┬─────────────────────────────────────┘
                         │ JNI  (audio_engine_jni.cpp)
┌────────────────────────▼─────────────────────────────────────┐
│                    C++ Audio Engine                           │
│                                                               │
│  IAudioDecoder ──► AudioPlayer (decode thread)                │
│  ├─ FlacDecoder       ├─ GainProcessor (anti-zipper)          │
│  ├─ Mp3Decoder        ├─ BiquadFilter  (7-type EQ)            │
│  ├─ WavDecoder        └─► RingBuffer<double>  (lock-free)     │
│  └─ DsdDecoder (stub)                                         │
└────────────────────────┬─────────────────────────────────────┘
                         │ Oboe audio callback
┌────────────────────────▼─────────────────────────────────────┐
│                  OboeAudioEndpoint                            │
│  Persistent stream  ·  double→float  ·  silence on underrun   │
│  Hardware-native sample rate  ·  ~1–5 ms latency              │
└──────────────────────────────────────────────────────────────┘
                         │ (future)
┌────────────────────────▼─────────────────────────────────────┐
│                  UsbAudioEndpoint (stub)                      │
│  Direct isochronous USB transfer  ·  bypasses Android mixer   │
│  Raw PCM / DoP to external DAC  ·  192 kHz / 32-bit capable   │
└──────────────────────────────────────────────────────────────┘
```

### Signal Flow Detail

```
File on disk
    │
    ▼  dr_flac / dr_mp3 / dr_wav  (native decoder)
PCM samples as double[]
    │
    ▼  GainProcessor              (fade, volume — 64-bit)
    │
    ▼  BiquadFilter               (parametric EQ — 64-bit)
    │
    ▼  RingBuffer<double>         (lock-free, power-of-2)
    │
    ▼  OboeAudioEndpoint          (double → float32 conversion)
    │
    ▼  Oboe stream → Hardware DAC
```

---

## Building

### Prerequisites
- Android Studio Hedgehog (2023.1) or later **or** command-line tools only
- Android NDK r25c+ (`sdkmanager "ndk;25.2.9519653"`)
- JDK 17+
- CMake 3.22+

### Clone
```bash
git clone https://github.com/Vitalii-Khomenko/High-Fidelity-64-Bit-Audio-Engine.git
cd High-Fidelity-64-Bit-Audio-Engine
```

### Debug APK (development / sideload)
```bash
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk  (~29 MB, unoptimized)
```

### Release APK (signed, optimized — recommended)
The project ships with a pre-generated keystore (`hifi-player.jks`).
Signing credentials are in `local.properties` (excluded from git via `.gitignore`).

```bash
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk  (~17 MB)
```

### Install directly to a connected device
```bash
./gradlew installDebug
# or with adb:
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Clean build
```bash
./gradlew clean assembleRelease
```

### Windows (Command Prompt / PowerShell)
```bat
gradlew.bat assembleRelease
```

### Generate your own keystore
```bash
keytool -genkeypair -v \
  -keystore hifi-player.jks \
  -alias hifi \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass YOUR_PASSWORD -keypass YOUR_PASSWORD \
  -dname "CN=Your Name, O=YourOrg, C=UA"
```
Then update the four `KEYSTORE_*` lines in `local.properties`.

---

## Technical Notes

### MP3 VBR Double-Init Fix
Variable-bitrate MP3 files without an Xing/Info header caused tracks to stop after ~1 second.
Root cause: `drmp3_get_pcm_frame_count()` scans the entire file, leaving the fd at EOF.
`drmp3_seek_to_pcm_frame(0)` silently fails for such files.
**Fix:** `drmp3_uninit()` → `lseek(fd, 0, SEEK_SET)` → `drmp3_init()` — reinitialize the decoder cleanly for playback after the frame-count scan.

### Persistent Oboe Stream
The Oboe stream is created once at engine startup and kept alive for the entire app session.
Between tracks, only the `IAudioDecoder` pointer is swapped. This eliminates the click artifacts, ~100 ms startup latency, and occasional `AAUDIO_ERROR_TIMEOUT` errors that occur when closing and reopening a stream per track.

### Anti-Click Track Transitions
```
Old track ends / user presses Next
  → setVolume(0.0) on main thread
  → coroutine: delay(80 ms)           ← GainProcessor anti-zipper reaches 0
  → audioEngine.playTrack()           ← new decoder initialized at silence
  → seekTo(savedPositionMs)           ← restore chapter bookmark if any
  → fade-in loop: 0→100% in 300 ms   ← smooth entry
```

### Per-Track Audiobook Bookmarks
Each track's resume position is stored independently under key `pos_${uri.hashCode()}` in SharedPreferences. When switching chapters, the current position is saved before unloading. When returning to a chapter, `startPositionMs` is passed directly into the native `playTrack()` call, and the seek happens inside the C++ engine before the fade-in starts — so playback always begins from exactly the right frame.

### Race Condition Prevention
- `isLoadingTrack` (Compose state) debounces rapid UI taps
- `loadMutex` (Kotlin `Mutex`) in `PlaybackService` ensures only one native decoder load runs at a time
- `isManualStop` flag in the service distinguishes user-initiated pause from natural track end, preventing the auto-advance callback from firing on pause
- `fadeJob` reference cancels any in-progress fade before starting a new one

---

## Project Structure

```
MusicPlayerPro/
├── app/src/main/
│   ├── java/com/aiproject/musicplayer/
│   │   ├── MainActivity.kt        # Compose UI, playlist, transport controls
│   │   ├── PlaybackService.kt     # Foreground service, MediaSession, fade engine
│   │   ├── AudioEngine.kt         # Kotlin JNI wrapper
│   │   └── db/                    # Room database — playlist entities + DAOs
│   ├── res/
│   │   ├── drawable/              # Vector launcher icon (equalizer bars)
│   │   ├── mipmap-anydpi-v26/     # Adaptive icon XMLs
│   │   └── values/                # strings.xml · themes.xml
│   └── AndroidManifest.xml
│
└── src/                           # Pure C++ audio engine (no Android dependencies)
    ├── core/
    │   └── AudioPlayer.h          # Engine: decoder swap · DSP chain · ring buffer
    ├── decoders/
    │   ├── IAudioDecoder.h        # Abstract interface: openFd · readFrames · seekToFrame
    │   ├── Mp3Decoder.h           # dr_mp3 + VBR double-init fix
    │   ├── FlacDecoder.h          # dr_flac — lossless 32-bit/192 kHz
    │   ├── WavDecoder.h           # dr_wav — PCM + IEEE float
    │   └── DsdDecoder.h           # DSD stub — DoP/raw architecture placeholder
    ├── dsp/
    │   ├── GainProcessor.h        # Anti-zipper volume smoothing
    │   └── BiquadFilter.h         # All 7 RBJ EQ Cookbook filter types
    ├── hw/
    │   ├── IAudioEndpoint.h       # Abstract output: initialize · write · terminate
    │   ├── OboeAudioEndpoint.h    # Persistent Oboe stream · double→float · underrun fill
    │   └── UsbAudioEndpoint.h     # USB DAC stub — isochronous transfer architecture
    └── jni/
        └── audio_engine_jni.cpp   # JNI bridge: fd-based loading · play/pause/seek/speed
```

---

## Requirements

| Component | Minimum |
|-----------|---------|
| Android OS | 8.0 (API 26) |
| Target SDK | 35 (Android 15) |
| Architecture | arm64-v8a, armeabi-v7a, x86, x86_64 |
| NDK | r25c (C++17) |
| Gradle | 8.x |

### Dependencies
| Library | Purpose |
|---------|---------|
| [Oboe](https://github.com/google/oboe) `1.8.1` | Low-latency audio HAL |
| [dr_libs](https://github.com/mackron/dr_libs) | Single-header MP3/FLAC/WAV decoders |
| AndroidX Media `1.6.0` | MediaSessionCompat, MediaButtonReceiver |
| Jetpack Compose + Material3 | Declarative UI |
| Room `2.6.1` | Playlist SQLite database |

---

## Roadmap

- [x] **Full DSD playback** — DSF (.dsf) and DSDIFF (.dff) file decoding; 16× decimation box filter converts DSD64 → 176.4 kHz PCM / DSD128 → 352.8 kHz PCM; DoP-rate output ensures bit-transparency on USB DACs at these sample rates; DSD badge (DSD64/DSD128) shown in player UI; USB isochronous bypass (`UsbAudioEndpoint`) architecture ready for libusb integration
- [x] **Bluetooth codec detection** — connects to `BluetoothA2dp` profile proxy at runtime; reads active codec (LDAC / aptX HD / aptX / AAC / SBC) and displays it in the headset info bar alongside device name and sample-rate/bit-depth
- [x] **Gapless playback** — `m_nextDecoder` slot in `AudioPlayer`; pre-loaded ~8 s before EOF; seamless C++ decoder swap with zero stream interruption
- [x] **ReplayGain** — scans first 64 KB of FLAC/MP3 for `REPLAYGAIN_TRACK_GAIN` tag; applied automatically as a `GainProcessor` multiplier; displayed as `±X.X dB` in the player UI
- [x] **Spectrum analyzer** — 2048-point Hann-windowed FFT (Cooley-Tukey radix-2); 32 logarithmic bands with attack/decay smoothing; rendered as a live gradient bar chart in the player card
- [x] **DLNA / UPnP network source** — SSDP M-SEARCH discovers MediaServer:1 devices on the local network; ContentDirectory:Browse SOAP fetches audio track listings; tracks are added to the playlist and streamed via HTTP with local cache; works with Plex, Emby, Jellyfin, Kodi, and any UPnP media server

---

## License

MIT License — see [LICENSE](LICENSE) for details.
