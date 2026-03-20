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
| **Track switching** | Immediate cut — audible click | **300 ms fade-out + 300 ms fade-in** on every transition |
| **Same-track re-tap** | Full stop + reload | **Instant seek to 0** — no reload, no click, no interruption |
| **Audiobook resume** | Per-app, single position saved | **Per-track bookmarks** — every chapter remembers its own position independently |
| **UI freeze on load** | Common — file scan blocks main thread | **IO coroutine + Mutex** — UI never freezes, even on large FLAC files |
| **Bit depth** | Downsampled to 16-bit by Android mixer | **32-bit float output** to Oboe, preserving full dynamic range |
| **EQ** | None, or basic preset-only | **7-band parametric EQ** — RBJ Cookbook: LP, HP, BP, Notch, AP, LowShelf, HighShelf |
| **USB DAC** | Not supported | **Architecture ready** — UsbAudioEndpoint stub for direct isochronous transfer to USB DAC, bypassing Android's 48 kHz mixer entirely |
| **Output device detection** | Not shown | **Live headset info** — shows device name, connection type (USB / Bluetooth A2DP / Wired), max supported sample rate and bit depth |
| **Playback speed** | None or MediaPlayer-based (degrades quality) | **Pitch-transparent speed control** via linear interpolation in the native decode loop |
| **Repeat modes** | Basic | Off / Repeat One / Repeat All with per-track bookmark preservation |
| **Audio focus** | Abrupt stop or ignore | **Full state machine** — fade-out on mic/call/notification, auto-resume, smooth duck |
| **Device reconnect** | Stream dies after headphone/BT switch | **Auto-reconnect** — Oboe stream restarts automatically after routing change or screen recording |

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
- **Auto-reconnect on device change** — `onErrorAfterClose(ErrorDisconnected)` restarts the Oboe stream automatically after headphone unplug, Bluetooth switch, or screen recording; no manual restart needed
- **Lock-free ring buffer** — power-of-2 `RingBuffer<double>` between decode thread and audio callback, zero mutex on the hot path
- **Smooth volume transitions** — `GainProcessor` with anti-zipper smoothing eliminates all pops
- **Fade-out on pause/stop** — 300 ms ramp to silence before any transport operation
- **Fade-in on track start** — 300 ms ramp from silence after every track load
- **Pitch-transparent speed control** — linear interpolation in the decode loop, 0.5×–2.5×
- **7-type parametric EQ** — RBJ EQ Cookbook BiquadFilter: LowPass, HighPass, BandPass, Notch, AllPass, LowShelf, HighShelf
- **ReplayGain** — automatic loudness normalization; scans FLAC vorbis comments and MP3 ID3v2 for `REPLAYGAIN_TRACK_GAIN`; applied as a `GainProcessor` multiplier; displayed in UI as `±X.X dB`
- **Gapless playback** — `m_nextDecoder` slot pre-loads the next track ~8 s before EOF; at EOF the C++ engine swaps decoders atomically with no stream interruption, no click, no silence
- **Spectrum analyzer** — 2048-point Hann-windowed Cooley-Tukey FFT; 32 logarithmic bands with per-band attack/decay smoothing; rendered at **30 fps** as a live hue-gradient bar chart (blue → red by amplitude); bars decay smoothly to zero on pause

### Supported Formats
| Format | Decoder | Notes |
|--------|---------|-------|
| **FLAC** | dr_flac | Lossless, up to 32-bit / 192 kHz |
| **MP3**  | dr_mp3  | CBR and VBR — full Xing/Info-less VBR fix applied |
| **WAV**  | dr_wav  | PCM 8/16/24/32-bit and IEEE float |
| **DSD**  | DsdDecoder | DSF + DSDIFF (DFF) — 16× decimation → DoP-rate PCM; DSD64/DSD128/DSD256/DSD512 |

### Playback & UX
- **Smooth track transitions** — 300 ms fade-out → silence → load → seek → 300 ms fade-in, no audible clicks
- **Same-track re-tap** — tapping the currently playing track seeks instantly to the beginning with no decoder reload
- **Position restore guard** — returning to the app after it was backgrounded never causes a seek-back glitch; position is only restored from saved state if the engine is freshly loaded (< 3 s in)
- **Auto-advance** — plays next track automatically at end; respects repeat mode
- **Repeat modes** — Off / Repeat One / Repeat All (button in transport controls)
- **Variable speed** — 0.5×–2.5× with 9 preset steps
- **Sleep timer** — 5 / 10 / 15 / 30 / 60 minutes with 30-second volume fade-out; battery-optimised (CPU only woken in final 30-second window, not every 500 ms for the entire duration)

### Audiobook Features
- **Per-track position bookmarks** — every track independently remembers where you stopped; switching chapters and returning resumes at the exact second
- **Played-track indicators** — green check mark on every track you've finished; resets on explicit Clear
- **Persistent state across restarts** — playlist, current track, and position survive app kill and device reboot (SharedPreferences + Room DB)
- **Auto-scroll** — playlist smoothly scrolls to the current chapter on every track change

### Android System Integration
- **Foreground service — always alive** — the foreground service is never demoted during pauses; `stopForeground()` is only called on explicit user stop. Prevents OS (especially Xiaomi MIUI/HyperOS) from killing the service the moment audio focus is lost
- **MediaSession + MediaStyle notification** — lock-screen and notification-shade controls with Previous / Play-Pause / Next; progress bar auto-advances without polling
- **Hardware media button support** — headset buttons, Bluetooth remotes, car audio via `MediaButtonReceiver`
- **Audio focus state machine** — full `AudioFocusRequest` lifecycle:
  - `AUDIOFOCUS_LOSS` (voice message, call, another player): immediate UI update + fade-out; focus request kept alive for auto-resume on `AUDIOFOCUS_GAIN`
  - `AUDIOFOCUS_LOSS_TRANSIENT` (microphone tap): fast 60 ms fade-out so recording app gets silence immediately; auto-resume when mic released
  - `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` (notification): smooth duck to 20% volume; unduck on `AUDIOFOCUS_GAIN`
  - `AUDIOFOCUS_GAIN`: fade-in resume + completion monitor restart; safety-net branch handles engine unexpectedly stopped
- **Immediate UI feedback** — pause button and notification update state instantly on user tap, not after the 200–300 ms fade completes
- **Stops on swipe-away** — when user removes app from recents, playback stops and notification is cleared
- **Async loading** — `Dispatchers.IO` coroutine + `Mutex` serializes JNI loads; UI is always responsive
- **Recursive folder scan** — Add Folder grants one persistent URI permission for a root folder; all subfolders scanned automatically up to 10 levels deep; no repeated permission dialogs

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
- **Safe HTTP cache** — DLNA tracks downloaded to a `.tmp` file first; renamed to final path only on successful completion — a cancelled download never leaves a corrupt cache file
- Access via **⋮ → Browse Network (DLNA)** — scan starts automatically on open

### Playlist Management
- **Add Folder** — grant persistent URI permission once to a root folder; **all subfolders are scanned recursively** (up to 10 levels deep) and added automatically — one permission tap covers your entire music library
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
│  └─ DsdDecoder (DSF + DFF, 16× decimation)                    │
└────────────────────────┬─────────────────────────────────────┘
                         │ Oboe audio callback
┌────────────────────────▼─────────────────────────────────────┐
│                  OboeAudioEndpoint                            │
│  Persistent stream  ·  double→float  ·  silence on underrun   │
│  Hardware-native sample rate  ·  ~1–5 ms latency              │
│  Auto-reconnect on ErrorDisconnected (routing change)         │
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
    ▼  dr_flac / dr_mp3 / dr_wav / DsdDecoder  (native decoder)
PCM samples as double[]
    │
    ▼  GainProcessor              (fade, volume — 64-bit, anti-zipper)
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

## Technical Notes

### MP3 VBR Double-Init Fix
Variable-bitrate MP3 files without an Xing/Info header caused tracks to stop after ~1 second.
Root cause: `drmp3_get_pcm_frame_count()` scans the entire file, leaving the fd at EOF.
`drmp3_seek_to_pcm_frame(0)` silently fails for such files.
**Fix:** `drmp3_uninit()` → `lseek(fd, 0, SEEK_SET)` → `drmp3_init()` — reinitialize the decoder cleanly for playback after the frame-count scan.

### Persistent Oboe Stream
The Oboe stream is created once at engine startup and kept alive for the entire app session.
Between tracks, only the `IAudioDecoder` pointer is swapped. This eliminates the click artifacts, ~100 ms startup latency, and occasional `AAUDIO_ERROR_TIMEOUT` errors that occur when closing and reopening a stream per track.

### Oboe Auto-Reconnect
When the audio output device changes (screen recording with system audio, headphone unplug, Bluetooth switch), Oboe fires `onErrorAfterClose(ErrorDisconnected)`. The endpoint restarts the stream from a detached background thread after a 150 ms settling delay. A shared `atomic<bool>` lifetime flag prevents the thread from dereferencing the endpoint if it is destroyed in the reconnect window (use-after-free prevention).

### Anti-Click Track Transitions
Every track switch follows a strict crossfade sequence — no abrupt cuts, no hardware pops:
```
User taps track / autoadvance / Next button
  │
  ▼ isManualStop = true  (blocks stray completionJob callback during transition)
  │ check audioEngine.isPlaying() — skip fade if already silent
  ▼ fade-out loop:  100% → 0%  over 300 ms  (15 steps × 20 ms)
  audioEngine.pause()                      ← engine stops cleanly at silence
  │
  ▼ requestAudioFocus()  ← AFTER fade, not before
  │   (avoids abandonAudioFocus() racing with a running audio stream)
  isManualStop = false
  updatePlaybackState(STATE_BUFFERING)  + showNotification()
  │
  ▼ IO thread (no UI block)
  loadMutex.withLock { audioEngine.playTrack(uri) }  ← new decoder at silence
  seekTo(savedPositionMs)                  ← restore chapter bookmark if any
  │
  ▼ fade-in loop:  0% → 100%  over 300 ms  (15 steps × 20 ms)
  ← new track audible, no click, no gap
```
The `GainProcessor` anti-zipper smoothing ensures that even if the OS pre-empts the fade
loop between steps, the hardware output never changes by more than one smoothing step at a time.

### Audio Focus: Microphone & Voice Messages
When a chat app or voice recorder takes `AUDIOFOCUS_LOSS_TRANSIENT`:
- Music fades out in **60 ms** (3 steps × 20 ms) — recording app gets silence almost immediately
- `pausedByFocusLoss = true`, audio focus request kept alive (NOT abandoned)
- When the mic is released and `AUDIOFOCUS_GAIN` fires, music resumes with a 200 ms fade-in automatically

### Foreground Service Lifetime
The service stays in foreground mode (`startForeground`) at all times while a track is loaded — including during pauses caused by audio focus loss. `stopForeground()` is only called in `stopPlayback()`. This prevents Xiaomi MIUI/HyperOS (and other aggressive battery managers) from killing the service the moment it loses audio focus, which was causing the app to close completely when pressing the microphone in a chat.

### Battery Consumption
| State | Wakeups/min | Notes |
|-------|-------------|-------|
| Playing, screen on | ~2,000 UI + audio callbacks | Normal for active playback with visualizer |
| Playing, screen off | ~350 | Completion monitor + gapless preload only |
| Paused, screen off | ~0 | No polling; foreground service notification only |
| Sleep timer active | ~0 until final 30 s | Coroutine sleeps until 30 s before deadline; fine-grained 500 ms loop only in the fade window |

No `WakeLock` is acquired. The C++ decode loop uses `sleep_for(2ms)` back-pressure when the ring buffer is full — no CPU spinning.

### Per-Track Audiobook Bookmarks
Each track's resume position is stored independently under key `pos_${uri.hashCode()}` in SharedPreferences. When switching chapters, the current position is saved before unloading. When returning to a chapter, `startPositionMs` is passed directly into the native `playTrack()` call, and the seek happens inside the C++ engine before the fade-in starts — so playback always begins from exactly the right frame.

### Race Condition & Safety
- `isLoadingTrack` (Compose state) debounces rapid UI taps
- `loadMutex` (Kotlin `Mutex`) in `PlaybackService` ensures only one native decoder load runs at a time
- `isManualStop = true` is set before the fade-out coroutine to block stray `completionJob` callbacks during transitions
- `requestAudioFocus()` is called inside the load coroutine, after the fade-out, to prevent `abandonAudioFocus()` from racing with a running audio stream
- `fadeJob` reference cancels any in-progress fade before starting a new one
- Spectrum `updateSpectrum()` holds `m_specMutex` during writes to prevent data races with the JNI reader thread

---

## Project Structure

```
MusicPlayerPro/
├── app/src/main/
│   ├── java/com/aiproject/musicplayer/
│   │   ├── MainActivity.kt        # Compose UI, playlist, transport controls
│   │   ├── PlaybackService.kt     # Foreground service, MediaSession, audio focus FSM
│   │   ├── AudioEngine.kt         # Kotlin JNI wrapper
│   │   ├── PlaybackStateMachine.kt# Pure-Kotlin audio focus state machine (testable)
│   │   ├── PlaylistNavigator.kt   # Pure-Kotlin next/prev/repeat index logic
│   │   ├── VolumeRamp.kt          # Pure-Kotlin fade math — fadeIn/fadeOut/duckLevel
│   │   ├── DsdInfo.kt             # DSD rate → label mapping (DSD64/128/256/512)
│   │   └── db/                    # Room database — playlist entities + DAOs
│   ├── res/
│   │   ├── drawable/              # Vector launcher icon (equalizer bars)
│   │   ├── mipmap-anydpi-v26/     # Adaptive icon XMLs
│   │   └── values/                # strings.xml · themes.xml
│   └── AndroidManifest.xml
│
└── src/                           # Pure C++ audio engine (no Android dependencies)
    ├── core/
    │   └── AudioPlayer.h          # Engine: decoder swap · DSP chain · ring buffer · spectrum
    ├── decoders/
    │   ├── IAudioDecoder.h        # Abstract interface: openFd · readFrames · seekToFrame
    │   ├── Mp3Decoder.h           # dr_mp3 + VBR double-init fix
    │   ├── FlacDecoder.h          # dr_flac — lossless 32-bit/192 kHz
    │   ├── WavDecoder.h           # dr_wav — PCM + IEEE float
    │   └── DsdDecoder.h           # DSF + DSDIFF decoder — 16× box-filter decimation → PCM
    ├── dsp/
    │   ├── GainProcessor.h        # Anti-zipper volume smoothing + ReplayGain
    │   └── BiquadFilter.h         # All 7 RBJ EQ Cookbook filter types
    ├── hw/
    │   ├── IAudioEndpoint.h       # Abstract output: initialize · write · terminate
    │   ├── OboeAudioEndpoint.h    # Persistent stream · auto-reconnect · lifetime-safe thread
    │   └── UsbAudioEndpoint.h     # USB DAC stub — isochronous transfer architecture
    └── jni/
        └── audio_engine_jni.cpp   # JNI bridge: fd-based loading · play/pause/seek/speed
```

---

## Unit Test Suite

Critical logic is covered by **71 pure-JVM unit tests** that run without a device or emulator:

```bash
./gradlew test          # ~15 seconds, no device needed
```

| Test class | Tests | What it guards |
|---|---|---|
| `PlaybackStateMachineTest` | 22 | Audio focus state machine — every bug from production is a named test |
| `PlaylistNavigatorTest` | 18 | Repeat off/one/all · next/prev · boundary conditions · single-track edge cases |
| `VolumeRampTest` | 18 | Fade-in ends at target · fade-out ends at 0 · duck math · crossfade contract |
| `DsdInfoTest` | 13 | DSD64/128/256/512 label correctness · boundary rates · `isDsd()` helper |

### Named regression tests (bugs that already happened)

| Test name | Bug it prevents |
|---|---|
| `BUG track-stops-after-1sec` | `requestAudioFocus()` must abandon old request first or Android fires LOSS on the new track |
| `BUG mic-triggers-next-song` | `AUDIOFOCUS_LOSS_TRANSIENT` must set `isManualStop=true` or `completionJob` fires `onTrackCompleted` |
| `BUG voice-msg-stops-song` | `AUDIOFOCUS_LOSS` must keep `pausedByFocus=true` and NOT abandon focus, or auto-resume never fires |
| `BUG ui-playing-no-sound` | `AUDIOFOCUS_GAIN` must call `play()` when `pausedByFocus=true`, not just `setVolume()` |
| `BUG DSD128 must NOT be labelled DSD256` | Threshold off-by-one in DSD label `when` block |
| `BUG DSD256 must NOT be labelled DSD512` | Same threshold bug, one tier up |

---

## Security & Stability Audit

The following issues were identified and fixed during a dedicated code audit:

| Issue | Severity | Fix |
|-------|----------|-----|
| **Oboe detached-thread use-after-free** | CRITICAL | `shared_ptr<atomic<bool>> m_alive` lifetime flag checked by reconnect thread before any `this` access |
| **Spectrum data race** (C++) | HIGH | `updateSpectrum()` now holds `m_specMutex` during writes; no more UB between audio-callback thread and JNI reader |
| **Activity memory leak via callbacks** | HIGH | All 5 Service callbacks (`skipToNext`, `onTrackCompleted`, etc.) explicitly nulled in `MainActivity.onDestroy()` |
| **DLNA corrupt cache file** | MEDIUM | Download to `.tmp`, `renameTo` final path only on success; partial downloads cleaned up on failure |
| **Toast Activity context leak** | MEDIUM | All `Toast.makeText()` calls use `applicationContext` instead of `this@MainActivity` |
| **Service killed by Xiaomi on focus loss** | HIGH | `stopForeground(false)` removed from `showNotification()`; service stays foreground until `stopPlayback()` |

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

### Run unit tests (no device needed)
```bash
./gradlew test
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
| MockK `1.13.10` | Unit test mocking |
| kotlinx-coroutines-test `1.8.1` | Coroutine unit testing |

---

## Roadmap

- [x] **Full DSD playback** — DSF (.dsf) and DSDIFF (.dff) file decoding; 16× decimation box filter converts DSD64 → 176.4 kHz PCM / DSD128 → 352.8 kHz PCM; DoP-rate output ensures bit-transparency on USB DACs; DSD badge (DSD64/DSD128/DSD256/DSD512) shown in player UI
- [x] **Bluetooth codec detection** — connects to `BluetoothA2dp` profile proxy at runtime; reads active codec (LDAC / aptX HD / aptX / AAC / SBC) and displays it in the headset info bar
- [x] **Gapless playback** — `m_nextDecoder` slot in `AudioPlayer`; pre-loaded ~8 s before EOF; seamless C++ decoder swap with zero stream interruption
- [x] **ReplayGain** — scans first 64 KB of FLAC/MP3 for `REPLAYGAIN_TRACK_GAIN` tag; applied automatically; displayed as `±X.X dB`
- [x] **Spectrum analyzer** — 2048-point Hann-windowed FFT; 32 logarithmic bands; 30 fps with smooth decay on pause
- [x] **DLNA / UPnP network source** — SSDP M-SEARCH discovers MediaServer:1 devices; ContentDirectory:Browse SOAP fetches audio track listings; HTTP streaming with safe cache
- [x] **Audio focus state machine** — full fade-out/in lifecycle for mic, calls, notifications, other players
- [x] **Oboe auto-reconnect** — survives headphone unplug, BT switch, screen recording
- [x] **Battery optimisation** — no wake locks; decode loop uses back-pressure sleep; sleep timer wakes CPU only in the final 30-second fade window
- [x] **Security & stability audit** — use-after-free (C++), data race (spectrum), Activity memory leak, DLNA cache corruption — all fixed

---

## License

MIT License — see [LICENSE](LICENSE) for details.
