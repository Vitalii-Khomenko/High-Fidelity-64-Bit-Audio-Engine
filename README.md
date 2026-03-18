# MusicPlayerPro - High-Fidelity 64-Bit Audio Engine

An audiophile-grade, Bit-Perfect audio player for Android designed from the ground up in C/C++.

## 🚀 True 64-Bit Precision Architecture
Unlike standard Android players that rely on `AudioTrack` and the Android OS Mixer (AudioFlinger)—which forces all audio to be resampled to 48kHz (losing quality)—MusicPlayerPro bypasses the OS limits.
* **100% 64-bit float DSP pipeline** ensuring no quantization errors when altering volume or EQ.
* **TPDF Dithering** with a mathematically fast Lock-Free algorithm before down-quantizing.
* **Direct Hardware Access** via Oboe, WASAPI, or ALSA for EXCLUSIVE hardware audio pushing.
* **Bit-Perfect Playback** implementing "Follow Source Frequency" logic.

## 🛠️ Project Complete: MVP Audio Playback ready!

The entire audio stack is fully mapped from the Kotlin Jetpack Compose UI all the way down to the Oboe hardware level.

### Completed Modules:
- [x] Lock-Free `RingBuffer` using `Power-of-2` logic for real-time safe threading.
- [x] 64-bit `GainProcessor` (Volume) with anti-zipper smoothing.
- [x] 64-bit `BiquadFilter` (Parametric EQ basics).
- [x] TPDF Dither mapping to 16/24/32-bit limits.
- [x] Background `AudioPlayer` thread handling continuous file decoding and filling lock-free buffers.
- [x] **File Descriptor Decoding:** Android 11+ compatible Scoped Storage access passing Native FDs securely via `/proc/self/fd/`.
- [x] **Hardware Audio Output:** Linked fully to Google's Native `Oboe` C++ framework for `Float32` low-latency, EXCLUSIVE hardware audio pushing.
- [x] Android Jetpack Compose UI (Playlist, Native File Picker, Start/Stop/Volume controls).

## 🎛️ How it plays:
1. Kotlin UI opens the File Picker.
2. User selects an audio file.
3. Kotlin opens the URI and extracts an OS-level `File Descriptor (FD)`.
4. The `FD` integer is shipped to the `audioengine` `JNI`.
5. C++ grabs the file natively over `/proc/self/fd/X` and starts the `Oboe` stream.
6. The background loop feeds 64-bit floats into the Audio DSP, processes the volume, and pushes to a Lock-Free RingBuffer.
7. Oboe Hardware Callback reads chunks from the Lock-Free RingBuffer to the DAC seamlessly!

## ⚙️ Building the UI
Open the folder in Android Studio. Gradle will automatically pull Jetpack Compose and Google Oboe framework dependencies. CMake will compile the NDK native C++ libraries.
./gradlew assembleDebug