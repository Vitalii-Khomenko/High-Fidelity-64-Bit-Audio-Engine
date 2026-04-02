# HiFi Player Roadmap

This roadmap now reflects only what is already implemented and the current product rules. New items will be added later, step by step, after implementation and validation.

## Implemented

- [x] Native C++ playback engine with Oboe output and persistent stream lifetime
- [x] FLAC / MP3 / WAV / DSD decoding
- [x] Gapless playback, ReplayGain, spectrum analyzer, 5-band EQ, and Sonic-based speed control
- [x] MediaSession service, notification controls, shuffle, repeat, sleep timer, and audio-focus handling
- [x] `Books` mode with per-track progress memory and completed-track markers
- [x] `Music` mode with pause-only resume and no long-term played-track memory
- [x] MediaStore scan, recursive SAF folder import, Library Folders manager, and in-app browsing inside granted folders
- [x] Room playlists with save, load, rename, delete, and partial recovery for unavailable tracks
- [x] DLNA browse and add-to-playlist flow
- [x] Device-output detection, Bluetooth codec display, and real-device stability fixes around reconnect and transport

## Current Product Rules

- `Library Folders` are access roots only. They do not auto-scan and do not auto-rebuild the playlist.
- `Add Folder` is the explicit import action for building the working playlist.
- `Books` is for audiobooks and chapter-based listening.
- `Music` is for normal playlist playback without long-term bookmark clutter.
- `Sort by Name` ignores leading prefixes like `01 - Intro`; `Sort by Number` uses numeric tokens in the filename.

## Validation Baseline

- JVM tests must stay green.
- Release build must stay green.
- New playback changes must be manually checked on real Android hardware before being treated as stable.
- Every code change must also bump the Android app version. See [copilot-instructions.md](copilot-instructions.md).