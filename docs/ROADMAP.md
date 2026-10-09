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
- **DLNA** downloads a track completely before it plays (2 GiB limit, 512 MiB
  cache). There is no streaming playback.
- **Time-stretch** uses Sonic, which is tuned for speech; at speeds far from 1×
  dense music can sound phasey.
- **EQ changes** become audible after the ~300 ms decode buffer (volume is immediate).
- **Process death.** The service does not restart itself after the system
  kills the process (`START_NOT_STICKY`); the queue and position are restored on
  the next start.
- **Metadata.** Titles come from file names; tags and cover art are not read.
- **Real-device coverage.** The engine is verified on the host (sanitizers,
  simulated output). Behaviour with real HALs, Bluetooth stacks and OEM power
  management must be confirmed on phones (see [TESTING.md](TESTING.md)).

## Next steps

1. Library by artist and album from the tags the engine already reads (title, artist, album, numbers, cover).
2. Bit-perfect USB output, see [DIRECT_OUTPUT_PLAN.md](DIRECT_OUTPUT_PLAN.md).
3. CUE sheets embedded in FLAC (`CUESHEET` block / `CUESHEET` comment).
4. Streaming DLNA playback (HTTP range reads through `FileSource`).
5. A higher-quality music time-stretcher (phase vocoder / WSOLA in double precision).
6. Instrumented playback tests on CI with an emulator.
