# Known limitations and next steps

## Limitations

- **Shared-mode output.** Android mixes all apps; the system may resample and
  applies its own volume. Bit-perfect USB output would need exclusive / MMAP
  streams where available or a dedicated USB audio driver.
- **DSD is converted to PCM.** No DoP or native DSD output.
- **Formats.** AAC/M4A, Ogg Vorbis, Opus, ALAC, APE and WavPack are not decoded.
  Files in these formats are not offered by the library.
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

1. Decode AAC / Opus / Vorbis / ALAC through the NDK MediaCodec API behind the existing decoder interface.
2. Read tags (Vorbis comments, ID3v2, MP4) for title, artist, album and cover art.
3. USB DAC exclusive output (AAudio MMAP / exclusive mode where supported) with sample-rate following.
4. Streaming DLNA playback (HTTP range reads through `FileSource`).
5. A higher-quality music time-stretcher (phase vocoder / WSOLA in double precision).
6. Instrumented playback tests on CI with an emulator.
