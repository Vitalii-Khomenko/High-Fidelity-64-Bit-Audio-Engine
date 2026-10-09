# Changelog

## 0.9.0 — 2026-10-09

A rewrite of the playback core and the app around it.

### Engine

- **Output stage moved into the Oboe callback**: volume (30 ms linear ramp), pause/resume fades and a −0.1 dBFS peak limiter act after the buffer and are heard immediately.
- **Pause/resume is sample-exact**: consumption stops at a frame and the stream is paused; resume continues with the next sample, no decoder seek. The stream powers down while paused.
- **Buffering by time** (300 ms target, 750 ms ring) instead of a fixed 8192 frames, which was only 46 ms for DSD output.
- **Position model by segments** of consumed output frames: position, duration and the "track changed" event follow what is heard, also across gapless switches and speed changes.
- **Format-change transitions**: a next track with another rate or channel count plays after the queue drains and the stream reopens, instead of stopping.
- **New DSD decimator**: two-stage linear-phase FIR to 88.2/96 kHz (110 dB alias rejection, flat to 24 kHz, unity gain) replacing a 16-sample box filter; sample-exact seeking; DSD64–DSD512.
- **Sonic converted to float**: time-stretching no longer quantises to 16 bit or clips above full scale (−66 dBFS tone: SNR 149.5 dB instead of 31 dB). EQ and ReplayGain now run before the stretcher so gain changes land on the right sample at gapless boundaries.
- **MP3 seek table** built in the same pass that counts frames: seeking in long audiobooks is instant instead of decoding from the start; encoder delay handled.
- **ReplayGain tag reader**: FLAC Vorbis comments, ID3v2 TXXX (Latin-1/UTF-16/UTF-8) in MP3, WAV, AIFF and DSF, track/album/off, peak protection. MP3 ReplayGain was never found before.
- **Robust output**: usage media / content music, normal-power mode, Oboe format/channel/rate conversion allowed, stereo fold-down when a multichannel layout is refused, reconnect keeps the format and queued audio.
- **Non-blocking queries**: position/duration/format never wait for decoding or seeking (previously a long MP3 seek could freeze the UI).
- **Spectrum from the output**: shows what is being heard.
- Decoders read through a private `pread` file source; no shared file offsets. AIFF and 64-bit float WAV read natively.
- Removed dead code: ALSA/USB endpoint stubs, dither processor, unused helpers.
- Tests rewritten: DSD quality measured with a sigma-delta modulator, ReplayGain fixtures, Sonic resolution, 14 player scenarios on a simulated Oboe stream; ASan, UBSan and ThreadSanitizer clean.

### App

- **Playback owned by the service**: queue, shuffle/repeat, gapless pre-load, auto-advance, focus, notification and media session work without the UI; the activity only renders state.
- New queue model: shuffle as a permutation (next/previous/pre-load always agree), stable while adding, removing and sorting.
- Library: SAF folders with a browser, device scan limited to playable formats, DLNA with folder navigation and paging, saved playlists.
- Sound: volume, speed presets and profile, 5-band EQ with automatic preamp, ReplayGain mode. Settings: theme, Music/Books mode, sleep timer, signal path.
- Broken files are skipped during continuous playback (up to five in a row).
- Speed, volume, ReplayGain and repeat are remembered; data from 0.8.x installs is kept.

### Design

- New UI in the airwitech.com style: ink/paper palette with violet, amber and cyan, Sora and Source Sans 3 (Latin + Cyrillic), square shapes, hairline rules, glowing pixel accents, pixel transport icons, dark and light themes. New launcher and notification icons.
- English and Russian translations.

### Documentation

- New README (English and Russian), architecture, engine, building, testing and roadmap documents, third-party notices.

## 0.8.7 — 2026-10-08

Audit release: DSF header and seek fixes, DFF channel interleaving, EOF draining, reconnect thread safety, JNI generation guards, Android 7/8 compatibility fixes, NDK 28 and Oboe 1.9.3 for 16 KiB pages.
