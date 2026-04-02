# HiFi Player — 64-Bit Native Audio Engine for Android

> A professional-grade music and audiobook player built on a fully custom 64-bit C++ audio engine.
> No hidden Android-mixer resampling. No bit-crushing. No compromises.

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
| **Track switching** | Immediate cut — audible click | **Mute-first native-smoothed transition** — silence first, then load/swap, then ramp back up |
| **Same-track re-tap** | Full stop + reload | **Instant seek to 0** — no reload, no click, no interruption |
| **Audiobook resume** | Per-app, single position saved | **Per-track bookmarks** — every chapter remembers its own position independently |
| **UI freeze on load** | Common — file scan blocks main thread | **IO coroutine + Mutex** — UI never freezes, even on large FLAC files |
| **Bit depth** | Downsampled to 16-bit by Android mixer | **32-bit float output** to Oboe, preserving full dynamic range |
| **EQ** | None, or basic preset-only | **User-adjustable 5-band EQ** — native RBJ shelves/peaks with persisted settings |
| **USB DAC** | Not supported | Not implemented |
| **Output device detection** | Not shown | **Live headset info** — shows device name, connection type (USB / Bluetooth A2DP / Wired), max supported sample rate and bit depth |
| **Playback speed** | None or MediaPlayer-based (degrades quality) | **Native Sonic time-stretch** in the custom C++ decode loop |
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
- **Fade-out on pause/stop** — regular user pause and explicit stop now flush to silence faster, and the native ring buffer is cleared on pause/stop so buffered tail audio no longer lingers after the button press
- **Fade-in on track start** — new tracks start muted and ramp up through the native gain smoother, preventing a loud first buffer
- **Edge de-click shaping** — explicit track starts and non-gapless endings get a short native edge ramp, reducing discontinuity clicks at buffer boundaries
- **Native variable-speed playback** — Sonic-backed pitch-preserving time-stretch in the decode loop, 0.75×–2.0×, executed inside the custom C++ engine
- **Lock-screen stability hardening** — Oboe reconnect/reopen no longer nests stream mutex teardown during device-change recovery, which reduces Xiaomi/HyperOS lock-screen crash risk when routing changes during screen-off transitions
- **5-band user EQ** — native 64-bit shelves/peaks at 60 Hz / 230 Hz / 910 Hz / 3.6 kHz / 14 kHz with live updates and persistent settings
- **ReplayGain** — automatic loudness normalization; scans FLAC vorbis comments and MP3 ID3v2 for `REPLAYGAIN_TRACK_GAIN`; applied as a `GainProcessor` multiplier; displayed in UI as `±X.X dB`
- **Gapless playback** — `m_nextDecoder` slot pre-loads the next track ~8 s before EOF; at EOF the C++ engine swaps decoders atomically, applies a short native boundary blend, and continues with no stream interruption; repeat-aware preloading supports **Repeat One** and **Repeat All**
- **Spectrum analyzer** — 2048-point Hann-windowed Cooley-Tukey FFT; 32 logarithmic bands with per-band attack/decay smoothing; rendered at **30 fps** as a live hue-gradient bar chart (blue → red by amplitude); bars decay smoothly to zero on pause

### Supported Formats
| Format | Decoder | Notes |
|--------|---------|-------|
| **FLAC** | dr_flac | Lossless, up to 32-bit / 192 kHz |
| **MP3**  | dr_mp3  | CBR and VBR — full Xing/Info-less VBR fix applied |
| **WAV**  | dr_wav  | PCM 8/16/24/32-bit and IEEE float |
| **DSD**  | DsdDecoder | DSF + DSDIFF (DFF) — 16× decimation → DoP-rate PCM; DSD64/DSD128/DSD256/DSD512 |

### Playback & UX
- **Smooth track transitions** — silence-first transition: fade target to zero → load / seek at mute → short preroll → native ramp-up, no audible clicks
- **Samsung-friendly de-clicking** — transport ramps and preroll windows were lengthened, and native edge ramps were extended to better suppress paper-pop artifacts during Stop / Next / track switches on devices like Galaxy A55
- **Playback mode switch** — `Books` keeps per-track resume positions and completed-chapter markers; `Music` disables long-term track memory and keeps only the current paused position for quick resume
- **Same-track re-tap** — tapping the currently playing track seeks instantly to the beginning with no decoder reload
- **Position restore guard** — returning to the app after it was backgrounded never causes a seek-back glitch; position is only restored from saved state if the engine is freshly loaded (< 3 s in)
- **URI-precise restore** — current track restore matches by exact `Uri`, not just title, so duplicate filenames from different folders restore correctly
- **Auto-advance** — plays next track automatically at end; respects repeat mode
- **Repeat modes** — Off / Repeat One / Repeat All (button in transport controls)
- **Shuffle playback** — dedicated shuffle button with a prebuilt preview queue, stable next-track order for gapless preloading, and history-aware Previous
- **Stop -> Next in shuffle** — manual Stop no longer destroys the pending random-next flow; pressing Next after Stop still advances and starts playback
- **Variable speed** — 0.75×–2.0× with Sonic-based pitch-preserving processing plus finer near-1x presets: 0.75× / 0.9× / 1.0× / 1.1× / 1.25× / 1.5× / 1.75× / 2.0×; includes `Music` and `Speech` modes as Sonic quality profiles
- **Acceleration stability** — stretched playback now keeps a larger native headroom buffer, primes Sonic with a higher low-water mark, and softens callback underruns with a short ramp instead of an abrupt zero-fill
- **Equalizer** — top-bar Equalizer dialog with on/off toggle, 5 bands, reset-to-flat, and instant live updates while playback continues
- **Sleep timer** — 5 / 10 / 15 / 30 / 60 minutes with 30-second volume fade-out; battery-optimised (CPU only woken in final 30-second window, not every 500 ms for the entire duration); expiry now performs only a minimal final pause fade in `PlaybackService`, avoiding a second long fade after the countdown already reached silence; active timer is shown as a high-contrast chip that stays readable in dark theme
- **Playlist summary** — header now shows track count and total playlist duration when metadata is available
- **Playlist sorting** — manual sort by plain name or by detected chapter/file numbers; `Name` ignores leading prefixes like `01 - Intro`, while `Number` recognizes digits anywhere in the filename

### Audiobook Features
- **Per-track position bookmarks** — every track independently remembers where you stopped; switching chapters and returning resumes at the exact second
- **Played-track indicators** — green check mark on every track you've actually finished; a track is no longer marked as played just because it was tapped once; resets on explicit Clear
- **Persistent state across restarts** — playlist, current track, position, shuffle mode, shuffle history, queued shuffle order, and EQ settings survive app kill and device reboot (SharedPreferences + Room DB)
- **Library folders** — SAF root folders can now be managed separately from the current playlist and serve only as persistent access grants; they do not auto-scan or auto-populate the playlist
- **Auto-scroll** — playlist smoothly scrolls to the current chapter on every track change

### Android System Integration
- **Foreground service — always alive** — the foreground service is never demoted during pauses; `stopForeground()` is only called on explicit user stop. Prevents OS (especially Xiaomi MIUI/HyperOS) from killing the service the moment audio focus is lost
- **MediaSession + MediaStyle notification** — lock-screen and notification-shade controls with Previous / Play-Pause / Next; progress bar auto-advances without polling
- **Hardware media button support** — headset buttons, Bluetooth remotes, car audio via `MediaButtonReceiver`
- **Audio focus state machine** — full `AudioFocusRequest` lifecycle:
  - `AUDIOFOCUS_LOSS` (voice message, call, another player): immediate UI update + target volume to zero, then pause after the native fade settles; focus request kept alive for auto-resume on `AUDIOFOCUS_GAIN`
  - `AUDIOFOCUS_LOSS_TRANSIENT` (microphone tap): shorter zero-target fade for faster silence handoff to recording apps; auto-resume when mic released
  - `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` (notification): duck/unduck uses the same native gain smoother instead of a separate Kotlin step loop
  - `AUDIOFOCUS_GAIN`: resume from mute, short preroll, native ramp-up, then completion monitor restart; safety-net branch handles engine unexpectedly stopped
- **Immediate UI feedback** — pause button and notification update state instantly on user tap, not after the 200–300 ms fade completes
- **Stops on swipe-away** — when user removes app from recents, playback stops and notification is cleared
- **Async loading** — `Dispatchers.IO` coroutine + `Mutex` serializes JNI loads; UI is always responsive
- **Playlist restore without UI stall** — opening a saved playlist now closes the picker immediately and moves heavy playlist-state persistence off the main thread, reducing false "app is not responding" warnings on large libraries
- **JNI lifetime hardening** — all native state queries and transport calls are synchronized against engine shutdown, preventing races around the global C++ player instance
- **Recursive folder scan** — Add Folder grants one persistent URI permission for a root folder; all subfolders scanned automatically up to 10 levels deep; no repeated permission dialogs
- **Lazy SAF duration hydration** — large document-tree imports no longer block on `MediaMetadataRetriever` per file; tracks are listed immediately and missing durations are filled in asynchronously afterward

### About Playback Speed
- Playback speed still runs through the **custom native engine** via `MainActivity` → `PlaybackService` → `AudioEngine` JNI → `AudioPlayer::setSpeed()`.
- The app does **not** switch to Android's standard `MediaPlayer` or Java `AudioTrack` path when speed changes.
- Current implementation vendors **Sonic** directly into the native build and uses it as the active time-stretch engine in the decode loop.
- The speed path now keeps a deeper pre-rendered output reserve and softens any residual underrun in the Oboe callback, which reduces remaining crackle during heavier acceleration.
- `Speech` and `Music` modes remain wired end-to-end through Kotlin → service → JNI → native engine and now map to distinct Sonic quality profiles.
- The UI and native clamp use a **0.75×–2.0×** range with finer preset steps around `1.0×`.
- This means speed change is now optimized for **better quality without leaving the custom engine path**: pitch is preserved and the processing stays inside the native DSP chain.

### About Equalizer
- The equalizer runs in the **same native DSP chain** as the rest of playback, before gain and ring-buffer handoff.
- The UI exposes a **5-band graphic EQ** tuned for practical device listening: `60 Hz`, `230 Hz`, `910 Hz`, `3.6 kHz`, `14 kHz`.
- Each band is processed by a 64-bit RBJ shelf/peak filter, updated live from Compose while audio is playing.
- EQ on/off state and band gains are stored in `SharedPreferences` and re-applied when the service binds or a new track starts.

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
- **Safe HTTP cache** — DLNA tracks are validated via HTTP status code, downloaded to a `.tmp` file first, then renamed to final path only on successful completion; cancelled or failed downloads never leave a corrupt cache file
- Access via **⋮ → Browse Network (DLNA)** — scan starts automatically on open

### Playlist Management
- **Add Folder** — grant persistent URI permission once to a root folder; **all subfolders are scanned recursively** (up to 10 levels deep) and added automatically — one permission tap covers your entire music library
- **Library Folders manager** — top-bar menu stores and removes persistent SAF root access independently from playlist actions, and now lets you browse already-authorized roots before explicitly adding a chosen subfolder or track to the playlist
- **Scan MediaStore** — discover all audio files on the device
- **Sort by Name / Number** — playlist menu can resort tracks alphabetically or by parsed numeric tokens from filenames such as `01 Intro`, `Chapter 12`, or `Part_7_Final`
- **Save / Load / Rename / Delete** playlists — backed by Room SQLite database, preserving folder label, cached duration metadata, and shuffle-enabled state
- **Folder path** shown under each track name in the playlist
- **Load saved playlist with partial recovery** — if some stored SAF items are no longer readable, the app loads the remaining readable tracks and reports how many items were skipped
- **Clear** — removes the current playlist and resets audiobook progress markers, but keeps configured library folders intact and does not auto-rebuild the playlist from them later

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
│  ├─ FlacDecoder       ├─ GraphicEqProcessor (5-band EQ)       │
│  ├─ Mp3Decoder        ├─ GainProcessor (anti-zipper)          │
│  ├─ WavDecoder        ├─ Sonic time-stretch                   │
│  ├─ DsdDecoder        └─► RingBuffer<double>  (lock-free)     │
│                                                               │
└────────────────────────┬─────────────────────────────────────┘
                         │ Oboe audio callback
┌────────────────────────▼─────────────────────────────────────┐
│                  OboeAudioEndpoint                            │
│  Persistent stream  ·  double→float  ·  silence on underrun   │
│  Hardware-native sample rate  ·  ~1–5 ms latency              │
│  Auto-reconnect on ErrorDisconnected (routing change)         │
└──────────────────────────────────────────────────────────────┘
```

### Signal Flow Detail

```
File on disk
    │
    ▼  dr_flac / dr_mp3 / dr_wav / DsdDecoder  (native decoder)
PCM samples as double[]
    │
    ▼  GraphicEqProcessor         (5-band EQ — 64-bit)
    │
    ▼  GainProcessor              (fade, volume — 64-bit, anti-zipper)
    │
    ▼  Sonic                      (pitch-preserving speed change)
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
When the audio output device changes (screen recording with system audio, headphone unplug, Bluetooth switch, some Xiaomi lock-screen transitions), Oboe fires `onErrorAfterClose(ErrorDisconnected)`. The endpoint restarts the stream after a 150 ms settling delay from a managed reconnect thread. Stream teardown and re-open are serialized with a mutex, shutdown joins the reconnect thread before destroying the endpoint, and reconnect teardown no longer attempts nested stream-lock shutdown from inside `initialize()`, which removes a deadlock/crash-prone path during aggressive routing churn.

### Anti-Click Track Transitions
Every track switch now follows a single-envelope transition path — no double fade logic, no loud first buffer, no abrupt cuts:
```
User taps track / autoadvance / Next button
  │
  ▼ isManualStop = true  (blocks stray completionJob callback during transition)
  │ check audioEngine.isPlaying() — skip fade if already silent
  ▼ audioEngine.setVolume(0.0)
  │ wait ~160 ms so the native GainProcessor reaches silence
  audioEngine.pause()                      ← engine stops only after silence
  │
  ▼ requestAudioFocus()
  isManualStop = false
  updatePlaybackState(STATE_BUFFERING)  + showNotification()
  │
  ▼ IO thread (no UI block)
  audioEngine.setVolume(0.0)              ← keep target muted before load
  loadMutex.withLock { audioEngine.playTrack(uri) }  ← decoder starts from silence
  seekTo(savedPositionMs)                  ← restore chapter bookmark if any
  │
  ▼ short preroll (~20 ms) while first buffer stays muted
  audioEngine.clearBufferedAudio()       ← drop silent preroll already queued in the native ring buffer
  audioEngine.setVolume(userVolume)       ← native GainProcessor performs the ramp-up
  ← new track audible, no click, no gap
```
The key improvement is that `PlaybackService` no longer builds a second manual fade envelope with
10–15 Kotlin volume steps. `GainProcessor` is now the single source of truth for smoothing, so
start / stop / switch / duck all share the same sample-level gain path.

### Audio Focus: Microphone & Voice Messages
When a chat app or voice recorder takes `AUDIOFOCUS_LOSS_TRANSIENT`:
- Music uses a shorter zero-target fade before pause — recording app gets silence almost immediately
- `pausedByFocusLoss = true`, audio focus request kept alive (NOT abandoned)
- When the mic is released and `AUDIOFOCUS_GAIN` fires, playback resumes from mute and ramps up through the same native gain smoother automatically

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

### Exact Track Restore
Activity recreation now restores the current item by exact `Uri`, not by display title. This avoids restoring the wrong file when multiple folders contain tracks with the same filename, which is common in audiobook chapter sets and multi-disc albums.

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
│   │   ├── PlaybackQueueFlow.kt   # Pure-Kotlin helper for repeat-aware auto-advance / gapless next-index
│   │   ├── PlaybackRestore.kt     # Pure-Kotlin restore-by-URI helper
│   │   ├── PlaybackSpeed.kt       # Pure-Kotlin speed clamp + preset snapping helper
│   │   ├── PlaybackShuffle.kt     # Pure-Kotlin shuffle queue/next/prev/history helper
│   │   ├── PlaybackTransitions.kt # Pure-Kotlin timing policy for stop/switch/focus transitions
│   │   ├── PlaylistSummary.kt     # Pure-Kotlin playlist count + total-duration summary helper
│   │   ├── MainActivitySections.kt# Extracted composables for top bar, player card, playlist and DLNA dialog
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
    │   └── OboeAudioEndpoint.h    # Persistent stream · auto-reconnect · lifetime-safe thread
    └── jni/
        └── audio_engine_jni.cpp   # JNI bridge: fd-based loading · play/pause/seek/speed
```

---

## Unit Test Suite

Critical logic is covered by pure-JVM unit tests that run without a device or emulator:

```bash
./gradlew test          # ~15 seconds, no device needed
```

- `PlaybackStateMachineTest`, `PlaybackTransitionsTest`, `VolumeRampTest` — transport, fade, and audio-focus regressions
- `PlaylistNavigatorTest`, `PlaybackQueueFlowTest`, `PlaybackShuffleTest`, `PlaylistMergeTest`, `PlaylistSummaryTest`, `PlaylistOrderingTest` — playlist behavior, sorting, queueing, and deduplication
- `PlaybackRestoreTest`, `PlaybackContentModeTest`, `LibraryFolderEntryTest` — restore rules, `Music` / `Books` mode semantics, and library-folder persistence
- `PlaybackSpeedTest`, `PlaybackSpeedModeTest`, `DsdInfoTest` — speed clamps, speed modes, and DSD label correctness

### Named regression tests (bugs that already happened)

| Test name | Bug it prevents |
|---|---|
| `BUG track-stops-after-1sec` | `requestAudioFocus()` must abandon old request first or Android fires LOSS on the new track |
| `BUG mic-triggers-next-song` | `AUDIOFOCUS_LOSS_TRANSIENT` must set `isManualStop=true` or `completionJob` fires `onTrackCompleted` |
| `BUG voice-msg-stops-song` | `AUDIOFOCUS_LOSS` must keep `pausedByFocus=true` and NOT abandon focus, or auto-resume never fires |
| `BUG ui-playing-no-sound` | `AUDIOFOCUS_GAIN` must call `play()` when `pausedByFocus=true`, not just `setVolume()` |
| `BUG duplicate titles restore by URI picks exact track` | Activity/service restore must match the current track by exact `Uri`, not by display title |
| `BUG DSD128 must NOT be labelled DSD256` | Threshold off-by-one in DSD label `when` block |
| `BUG DSD256 must NOT be labelled DSD512` | Same threshold bug, one tier up |

---

## Security & Stability Audit

The following issues were identified and fixed during a dedicated code audit:

| Issue | Severity | Fix |
|-------|----------|-----|
| **JNI global-player race during shutdown** | CRITICAL | every JNI transport/state call now takes `g_playerMutex`, so `shutdownEngine()` cannot free the native player while UI polling is reading it |
| **Oboe detached-thread use-after-free** | CRITICAL | detached reconnect thread replaced with a managed reconnect thread joined during shutdown; stream restart is serialized with `m_streamMutex` |
| **Oboe reconnect nested-lock during reopen** | HIGH | reconnect path now closes the stream only under a single external lock and no longer re-enters stream teardown from `initialize()`, reducing Xiaomi lock-screen crash risk |
| **Spectrum data race** (C++) | HIGH | `updateSpectrum()` now holds `m_specMutex` during writes; no more UB between audio-callback thread and JNI reader |
| **Activity memory leak via callbacks** | HIGH | All 5 Service callbacks (`skipToNext`, `onTrackCompleted`, etc.) explicitly nulled in `MainActivity.onDestroy()` |
| **DLNA corrupt or hung cache download** | MEDIUM | download now validates HTTP status, uses connect/read timeouts, writes to `.tmp` first, and cleans partial files on failure |
| **Toast Activity context leak** | MEDIUM | All `Toast.makeText()` calls use `applicationContext` instead of `this@MainActivity` |
| **Service killed by Xiaomi on focus loss** | HIGH | `stopForeground(false)` removed from `showNotification()`; service stays foreground until `stopPlayback()` |
| **Sleep timer state desync** | MEDIUM | timer expiry now pauses through `PlaybackService`, so `MediaSession`, notification, focus and engine state remain aligned |

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

Current approach is simple: this file describes what is already in the app. New work is added step by step after implementation and validation.

- [x] Native Oboe playback engine with gapless decoder handoff
- [x] FLAC / MP3 / WAV / DSD playback
- [x] ReplayGain, 5-band EQ, and Sonic-based speed control
- [x] Shuffle, repeat, sleep timer, and lock-screen controls
- [x] `Books` / `Music` playback modes
- [x] Playlist save/load, Library Folders manager, and in-app folder browsing
- [x] MediaStore scan and DLNA browsing
- [x] Device-output info and Bluetooth codec display
- [x] Real-device stability fixes around audio focus, reconnect, and transport flow

---

## License

MIT License — see [LICENSE](LICENSE) for details.
