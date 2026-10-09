# Known limitations and next steps

## Limitations

- **Shared-mode output.** Android mixes all apps; the system may resample and
  applies its own volume. The plan for bit-perfect USB output (Android 14
  mixer attributes, then an own USB Audio Class driver) is in
  [DIRECT_OUTPUT_PLAN.md](DIRECT_OUTPUT_PLAN.md).
- **DSD is converted to PCM.** No DoP or native DSD output (part of the plan above).
- **Formats.** AAC and ALAC depend on the phone's MediaCodec (almost every
  phone has AAC; ALAC from Android 10 on most devices). DSD inside WavPack and
  password-protected TTA are not supported. CUE sheets are read from folders
  added through the Storage Access Framework, not from the MediaStore scan;
  CUE sheets embedded in FLAC are not read.
- **Library.** Built from saved SAF folders only (not from the MediaStore scan
  or DLNA). Tags cannot be edited. DLNA tracks have no covers.
- **Loudness.** Album gain from measurements is the duration-weighted mean of
  the tracks' loudness, not a full R128 album measurement (a few tenths of a
  dB apart for real albums).
- **DLNA.** MP3, Ogg, APE and MP4 download completely before playing (their
  decoders read the end of the file when opening). The renderer works while
  the app is open or playing; Android does not let a background app start
  playback from the network at any time.
- **Lyrics** next to the file are found in path-style document trees (internal
  storage, SD cards); some cloud providers only offer the tags.
- **Time-stretch** uses Sonic, which is tuned for speech; at speeds far from 1×
  dense music can sound phasey.
- **EQ, crossfeed and limiter changes** become audible after the ~300 ms
  decode buffer (volume is immediate).
- **Process death.** The service does not restart itself after the system
  kills the process (`START_NOT_STICKY`); the queue and position are restored on
  the next start.
- **Real-device coverage.** The engine is verified on the host (sanitizers,
  simulated output and codecs). Behaviour with real HALs, MediaCodec
  implementations, Bluetooth stacks and OEM power management must be confirmed
  on phones (see [TESTING.md](TESTING.md)).

## Next steps

1. Bit-perfect USB output, phase 1 (Android 14 mixer attributes) with an honest
   signal-path indicator — [DIRECT_OUTPUT_PLAN.md](DIRECT_OUTPUT_PLAN.md).
2. Integer output with dither, then the own USB driver (phase 2, incl. DoP).
3. CUE sheets embedded in FLAC; CUE and lyrics for MediaStore tracks.
4. A higher-quality music time-stretcher (phase vocoder / WSOLA in double precision).
5. Instrumented playback tests on CI with an emulator.
