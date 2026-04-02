This Technical Specification (TS) outlines the requirements for expanding our high-fidelity C/C++ based audio engine into a full-featured Android music application.

## 1. Project Overview
- **Core Engine:** High-Fidelity 64-Bit C/C++ Audio Engine.
- **Bridge Layer:** JNI (Java Native Interface) for rock-solid Native-to-JVM communication.
- **UI Framework:** Kotlin & Jetpack Compose (Declarative, high-performance UI).
- **Target Platform:** Android (ARM64 optimized).

## 2. System Architecture
The application follows a three-tier architecture to maintain strictly 64-bit audio integrity:
1. **Presentation Layer (Kotlin/Jetpack Compose):** Handles UI, user input, Android system integrations (MediaSession, Audio Focus), and state management.
2. **Bridge Layer (JNI):** Low-latency communication between Kotlin and C++.
3. **Native Audio Layer (C/C++):** Real-time signal processing, continuous buffer management (Lock-Free RingBuffer), decoder callbacks, and Oboe hardware abstraction.

## 3. Functional Requirements

#### 3.1 Advanced Playback Control
- **Transport Commands:** Play, Pause, Stop, Next, Previous.
- **Precise Seeking:** Scrubbing with millisecond precision without audio artifacts (pops/clicks), utilizing the engine's 64-bit buffers.
- **Playback Speed:** Implement time-stretching (0.5x to 2.5x) without altering pitch using a C++ DSP library (e.g., SoundTouch or RubberBand) running in the 64-bit floating point space.

#### 3.2 Media & Playlist Management
- **Database:** Room (SQLite) for local persistence of tracks, playlists, and playback positions.
- **CRUD Operations:** Create, Rename, Update, and Delete playlists.
- **Automatic Scanning:** Recursive directory scanning via MediaStore for high-res formats (FLAC, WAV, MP3, DSD).

#### 3.3 System Integration & Bluetooth
- **MediaSession API:** Integration with Android to allow control via:
    - Bluetooth headsets (AVRCP protocol).
    - System notification shade.
    - Lock screen controls.
- **Audio Focus:** Handle interruptions (incoming calls, GPS instructions) by ducking (lowering volume via C++ GainProcessor) or pausing audio.

#### 3.4 Utility Features
- **Sleep Timer:** A Kotlin-based countdown mechanism that triggers a "Fade-out and Stop" command to the JNI engine.
- **Volume Management:** Independent software volume gain (64-bit precision) to prevent clipping, already implemented natively.

## 4. UI/UX Requirements
- **High-Res Waveform:** Real-time visualization of the 64-bit audio stream. The C++ engine pushes amplitude arrays to Kotlin for Compose Canvas rendering.
- **Metadata Extraction:** Display album art, sample rate (kHz), and bit depth (bit) via Android's MediaMetadataRetriever.
- **Gapless Transition:** The C++ background loop decodes the next file into the RingBuffer natively, avoiding JVM-layer pause gaps.

## 5. Implementation Phases
1. **Native Seeking & Transport (C++ / JNI):** Millisecond-exact seek() implementations across FLAC, MP3, WAV decoders.
2. **System Integration (Kotlin):** MediaSession, Notifications, Audio Focus.
3. **Database & File Scanning (Kotlin):** Room DB, MediaStore queries, Playlists.
4. **Advanced DSP & UI (C++ / UI):** Pitch-independent time-stretching, gapless transitions, and waveform visualization.
