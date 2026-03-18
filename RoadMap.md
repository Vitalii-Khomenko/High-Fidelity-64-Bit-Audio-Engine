# Technical Specification: High-Fidelity 64-Bit Audio Engine

## 1. Project Overview
Development of a professional-grade, proprietary audio engine in C/C++ engineered for audiophile-level sound quality. The engine must bypass standard OS audio mixers to deliver unaltered audio streams directly to the Digital-to-Analog Converter (DAC).

## 2. Core Requirements
* **64-Bit Precision:** All internal DSP operations (equalization, crossfeed, volume control) must strictly utilize `double` (64-bit float) precision to prevent quantization errors and preserve dynamic range.
* **Bit-Perfect Playback:** The engine must implement "Follow Source Frequency" logic to automatically switch the DAC's sample rate to match the source file, avoiding OS-level software resampling.
* **Direct Hardware Access:** Implementation of exclusive access modes to bypass OS audio limits.
* **Low Latency & Threading:** Lock-free ring buffers and dedicated real-time audio threads for uninterrupted playback.

## 3. System Architecture

### 3.1. Core Data Structures
* `AudioBuffer`: A structure holding 64-bit float audio arrays, frame counts, sample rate, and bit-depth metadata.
* `RingBuffer`: Lock-free FIFO queues for safe data transfer between the decoding thread and the hardware audio thread.

### 3.2. DSP Pipeline Layer
Modular architecture using an `IAudioProcessor` interface.
* **Parametric Equalizer (PEQ):** Cascaded Biquad filters operating in 64-bit space.
* **Resampling Engine:** High-quality Sinc interpolation algorithm for asynchronous/synchronous resampling (used only when DAC lacks native support for the source rate).
* **Dithering Module:** TPDF (Triangular Probability Density Function) algorithm applied dynamically before down-quantizing the 64-bit float signal to 16-bit, 24-bit, or 32-bit integer formats for the DAC.
* **DSD Processing:** Support for Native DSD routing, DoP (DSD over PCM) packaging, and real-time PCM-to-DSD conversion.

### 3.3. Hardware Abstraction Layer (HAL)
Cross-platform output endpoints:
* **Windows:** WASAPI (Exclusive Mode) / ASIO.
* **Android/Linux:** ALSA (Advanced Linux Sound Architecture) / Direct custom USB Audio Class 2.0 (UAC2) driver.
* **macOS/iOS:** CoreAudio (Hog Mode / Exclusive Access).

## 4. Acceptance Criteria for AI Code Generation
* Code must be written in modern C++ (C++17 or higher).
* Strict separation of concerns: DSP math must be completely decoupled from hardware I/O APIs.
* No memory leaks; prefer smart pointers (`std::unique_ptr`) and RAII principles.
* Avoid dynamic memory allocation (`new`/`malloc`) inside the real-time audio processing loop.

## 5. Implementation Roadmap
1. **Module A:** Core `AudioBuffer` definition and lock-free `RingBuffer`.
2. **Module B:** Abstract `IAudioProcessor` and 64-bit Gain/Volume controller.
3. **Module C:** 64-bit Biquad filter implementation (Low-pass, High-pass, Peak, Shelf).
4. **Module D:** TPDF Dithering algorithm for integer conversion.
5. **Module E:** Hardware endpoint initialization (e.g., dummy ALSA/WASAPI setup logic).