# Known limitations and next steps

## Limitations

- **Shared-mode output.** Android mixes all apps; the system may resample and
  applies its own volume. The plan for bit-perfect USB output (Android 14
  mixer attributes, then an own USB Audio Class driver) is in
  [DIRECT_OUTPUT_PLAN.md](DIRECT_OUTPUT_PLAN.md).
- **DSD is converted to PCM.** No DoP or native DSD output.
- **Formats.** AAC and ALAC depend on the phone's MediaCodec (almost every
  phone has AAC; ALAC from Android 10 on most devices). DSD inside WavPack and
  password-protected TTA are not supported. CUE sheets are read from folders
  added through the Storage Access Framework, not from the MediaStore scan, and
  CUE sheets embedded in FLAC files are not read yet.
- **DLNA** streams FLAC, WAV / AIFF, WavPack and TTA (playback starts after
  512 KiB); MP3, Ogg, APE and MP4 still download completely first because their
  decoders read the end of the file when opening (2 GiB limit, 512 MiB cache).
  The renderer works while the app is open or playing; Android does not allow
  a background app to start playback from the network at any time.
- **Time-stretch** uses Sonic, which is tuned for speech; at speeds far from 1×
  dense music can sound phasey.
- **EQ changes** become audible after the ~300 ms decode buffer (volume is immediate).
- **Process death.** The service does not restart itself after the system
  kills the process (`START_NOT_STICKY`); the queue and position are restored on
  the next start.
- **Metadata.** The library is built from saved SAF folders only (not from the
  MediaStore scan or DLNA). Tags cannot be edited. DLNA tracks have no covers.
- **Real-device coverage.** The engine is verified on the host (sanitizers,
  simulated output). Behaviour with real HALs, Bluetooth stacks and OEM power
  management must be confirmed on phones (see [TESTING.md](TESTING.md)).

## Next steps

2. Bit-perfect USB output, see [DIRECT_OUTPUT_PLAN.md](DIRECT_OUTPUT_PLAN.md).
3. CUE sheets embedded in FLAC (`CUESHEET` block / `CUESHEET` comment).
4. Streaming DLNA playback (HTTP range reads through `FileSource`).
5. A higher-quality music time-stretcher (phase vocoder / WSOLA in double precision).
6. Instrumented playback tests on CI with an emulator.
