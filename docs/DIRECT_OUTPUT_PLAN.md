# Direct output plan: getting past the Android mixer

Status: **phase 1 implemented in 0.16.0** (bit-perfect mixer attributes,
integer output with dither, the path indicator), **phase 2 in 0.18.0** (own
UAC1/UAC2 driver, PCM; DoP and hardware volume still to do). Both are verified
on the host against simulated devices, not yet on real phones and DACs. This page records
where the audio goes, why "bypassing Android" is not as simple as it sounds, and
the order in which real bit-perfect output is built.

## Where the audio goes today

```
decoder → 64-bit DSP → ring buffer → Oboe (shared stream, float)
        → AudioFlinger mixer → HAL → DAC
```

- The engine opens an Oboe stream in **shared mode**. Every shared stream is
  mixed by AudioFlinger together with all other apps.
- The mixer **resamples** to the rate the output is running at (usually
  48 kHz on phones, often fixed at 48 kHz for USB on Android ≤ 13) and applies
  the **system volume**.
- The engine itself is exact: decoding and DSP run in 64-bit floating point,
  ReplayGain/EQ are bypassed when off, and the stream is handed over as 32-bit
  float. The loss happens after the hand-over.

This has been the case since the first commit (`5f20e0e`, 2026-04-02): the
original `OboeAudioEndpoint` used `SharingMode::Shared`. The files
`UsbAudioEndpoint.h` and `ALSAEndpoint` (in `IAudioEndpoint.h`) were empty
architecture stubs that were never called; they were removed in 0.9.0. The old
README claim "no hidden Android-mixer resampling" was not accurate. The engine
bypassed the **Java** audio stack (MediaPlayer / AudioTrack), not the mixer.

## Why an app cannot simply "talk to ALSA"

- `/dev/snd/*` belongs to the `audio` group and is protected by SELinux. A
  normal app cannot open it, rooted devices aside.
- The internal speaker and wired headphones on the phone's own codec are only
  reachable through the HAL, which only AudioFlinger talks to.
- **Bluetooth** always goes through the mixer and is then re-encoded (SBC, AAC,
  aptX, LDAC), so it can never be bit-perfect.
- The one output an app *can* own completely is an **external USB DAC**,
  through the USB host API.

## Options

| | Option | What it gives | Limits | Effort |
|---|---|---|---|---|
| 1 | **Bit-perfect mixer attributes** (Android 14+, API 34): `AudioManager.setPreferredMixerAttributes(usbDevice, AudioMixerAttributes(MIXER_BEHAVIOR_BIT_PERFECT))` | Official. The mixer passes our samples to the USB DAC unchanged, at the file's own rate (44.1 / 96 / 192 kHz …) and up to 32-bit. No system volume. | Android 14+, USB devices only, and the OEM must support it (Pixel does; others vary). Other apps' sounds are muted while active. | Days |
| 2 | **Own USB Audio Class driver** (UsbManager permission → file descriptor → libusb `libusb_wrap_sys_device`, isochronous transfers) | Complete bypass on any Android version: any rate the DAC offers, integer PCM, native DSD (DoP and raw where the DAC supports it). This is how USB Audio Player Pro works. | USB DACs only. UAC1 and UAC2, feedback endpoints, clock sources, quirks of individual DACs. Needs a lot of real-hardware testing. | Weeks |
| 3 | AAudio **exclusive / MMAP** stream | Lower latency; on some devices skips the mixer. | Only at the device's native rate (usually 48 kHz), so hi-res files are still resampled. Not a hi-res solution. | Small |

Option 3 is not worth pursuing for hi-res. The plan is option 1, then option 2.

## Phase 1: bit-perfect USB on Android 14+

Implemented: `BitPerfectOutput.kt` (device query, format choice, mixer
attributes), `OboeOutput` direct mode (exact rate and format, no Oboe
conversion, fallback to the mixer), `PcmEncoder.h` (integer output, dither only
for rounded samples), `SignalPath.kt` (indicator), remote volume on the media
session while direct. Deviations from the steps below: the engine asks the app
for the format through a callback just before each stream opens, so gapless
rate changes and reconnects get the right mixer attributes too; mono files use
the mixer unless the DAC lists a mono format.

Goal: when a USB DAC is connected and the user enables "Bit-perfect USB", the
DAC receives the decoded samples untouched at the track's own rate.

1. **Detect** the USB output (`AudioManager.getDevices(GET_DEVICES_OUTPUTS)`,
   `TYPE_USB_DEVICE` / `TYPE_USB_HEADSET`) and query
   `getSupportedMixerAttributes(device)`. Show the setting only when a
   `MIXER_BEHAVIOR_BIT_PERFECT` entry exists.
2. **Per track**, pick the mixer attributes that match the source: sample rate
   equal to the file's rate, channel mask stereo, encoding the best supported
   (`ENCODING_PCM_FLOAT`, `PCM_32BIT`, `PCM_24BIT_PACKED`, `PCM_16BIT`).
   Call `setPreferredMixerAttributes(attributes, device, mixerAttributes)`
   before the stream opens, and `clearPreferredMixerAttributes` when leaving
   the mode.
3. **Engine changes** (`src/hw/OboeOutput.h`):
   - Open the stream at the source rate with the matching format, and
     **disable Oboe's own conversion** (`setSampleRateConversionQuality(None)`,
     no format conversion), so a mismatch fails instead of resampling silently.
   - Integer output with **TPDF dither** when the DAC takes 16 or 24 bit
     (32-bit float or integer passes untouched).
   - In bit-perfect mode the system volume does nothing. The engine volume
     becomes the only volume (64-bit, applied before dither), with a clear
     warning in the UI about a loud default.
   - The **limiter and fades stay** (they only act on overs and on
     start / pause), but the "bit-perfect" indicator must show when volume,
     EQ, ReplayGain, crossfeed or speed are active, because then the output
     is no longer the file's samples.
4. **Format changes**: a new track with a different rate reopens the stream
   (the drain-then-reopen path already exists for gapless format changes).
5. **Honest indicator**: show the actual path on the Player screen and in
   Settings → Signal path: `BIT-PERFECT 96 kHz / 24-bit` in one colour,
   `MIXED → 48 kHz` in another. Read the real stream rate back from Oboe and
   the routed device from `AudioDeviceCallback`.
6. **Testing**: Pixel 8 or newer with a UAC2 DAC that shows its input rate.
   Play 44.1, 48, 88.2, 96, 176.4 and 192 kHz files and check the DAC display.
   Check that a 24-bit file is bit-exact with a loop-back recording.

## Phase 2: own USB driver (full bypass)

Goal: the original idea of the project, a player that owns the DAC.

Implemented (0.18.0), with one change to the plan: **no libusb**. The driver
needs only claiming interfaces, a few control requests and isochronous OUT /
feedback IN transfers, which the kernel's usbfs `ioctl`s (what libusb itself
uses on Linux) cover in a few hundred lines. That keeps everything MIT, avoids
vendoring and an LGPL library, and puts the transport behind an interface so
the whole driver is tested on the host with a simulated DAC.

| Part | File |
|---|---|
| UAC1 / UAC2 descriptors: AudioControl, clock source / selector / multiplier, every PCM type I alternate setting, data and feedback endpoints | `src/usb/UacDescriptors.h` |
| usbfs transport (claim with kernel-driver detach, release with re-attach, control, isochronous URBs, reap via `poll`) | `src/usb/UsbTransport.h` |
| Rates (UAC2 `GET RANGE` on the clock, UAC1 lists), rate setting, alternate setting, packet schedule in Q16.16, explicit feedback (Q16.16 / Q10.14, unit found against the nominal rate like Linux), 12 × 2 ms transfers from an urgent-audio thread, drain on pause, unplug detection | `src/usb/UacStreamer.h` |
| Engine: the USB stream is one more device stream of `OboeOutput`, so gain, fades, limiter, dither and conversion are shared; a rate the DAC lacks is converted in 64-bit; a lost DAC falls back without playing on by itself | `src/hw/OboeOutput.h` |
| App: finding the DAC, permission, hot-plug, settings, indicator (`… · USB DRIVER`), volume keys | `UsbDacDriver.kt`, `PlaybackService.kt` |

Not yet: DoP / native DSD (step 6), hardware volume through the feature unit
(step 7: software volume with dither is used), implicit feedback (async DACs
without a feedback endpoint get the nominal rate, which can slip by a sample
now and then), clock selectors other than their first input.

1. **USB access**: `UsbManager` device list, permission request, a
   `USB_DEVICE_ATTACHED` intent filter with a device filter for audio-class
   interfaces, `openDevice()` → file descriptor.
2. **libusb** built with the NDK (LGPL-2.1, as a separate shared library so
   it can be replaced, with the licence text in `THIRD_PARTY_NOTICES.md`),
   `libusb_set_option(LIBUSB_OPTION_NO_DEVICE_DISCOVERY)` and
   `libusb_wrap_sys_device(fd)`.
3. **Descriptor parsing**: UAC1 and UAC2 control interfaces, clock source /
   selector / multiplier units, AudioStreaming alternate settings (formats,
   bit resolutions, sample rates), feature units (hardware volume).
4. **Streaming**: claim the AudioStreaming interface, select the alternate
   setting for the format, set the sampling frequency (UAC1 endpoint request,
   UAC2 clock source request), run isochronous OUT transfers from a real-time
   thread. Handle **asynchronous feedback endpoints** so the packet sizes
   follow the DAC clock (no drift, no clicks).
5. **Engine integration**: a second output class next to `OboeOutput` with the
   same interface (`configure`, `start`, `stop`, `write`, `consumedFrames`), so
   the player, gapless logic and position model are unchanged.
6. **DSD**: DoP (DSD over PCM markers 0x05/0xFA) for DACs that support it, and
   native DSD alternate settings where offered; otherwise the existing DSD→PCM
   conversion.
7. **Volume**: hardware volume through the feature unit when present, otherwise
   64-bit software volume with dither.
8. **Robustness**: hot-unplug, permission loss, the phone going to sleep (the
   service keeps a partial wake lock only while playing), Android's own audio
   still going to the phone speaker while the DAC is ours, and handing the
   device back on stop.
9. **Testing matrix**: at least one UAC1 DAC, two UAC2 DACs from different
   vendors (XMOS and C-Media / Savitech based), one DAC with a hardware volume
   control and one with native DSD. Long-run test (hours) for clock drift.

## Out of scope

- Root-only ALSA access.
- Bluetooth "bit-perfect" (not possible; the best is LDAC 990 kbps).
- Upsampling as a feature. Resampling above the file's rate adds no
  information; the goal is to avoid resampling, not to add it.

## Order of work

1. Phase 1 (bit-perfect mixer attributes) with the honest path indicator.
2. Integer output and dither (needed by both phases).
3. Phase 2 (own USB driver), starting with UAC2 PCM, then UAC1, then DoP.
   UAC2 and UAC1 PCM are done; next DoP and hardware volume, then real-device
   testing (step 9).
