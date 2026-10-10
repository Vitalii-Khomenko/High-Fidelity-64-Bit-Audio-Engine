# Changelog

## 0.17.0 — 2026-10-10

- **64-bit conversion to the mixer's rate.** In shared mode (speaker, Bluetooth, and USB without bit-perfect) the stream now opens at the mixer's own rate (usually 48 kHz) and the engine converts files at other rates (44.1, 88.2, 96, 192 kHz, DSD) itself, so Android's mixer no longer resamples. Exact rational polyphase filter, Kaiser-windowed sinc in double precision: flat to 20 kHz (±0.001 dB), images and aliases at least 130 dB down, 2 ms delay, about 1–2 % of one core. Ratios that would need an impractically large filter fall back to Oboe's converter.
- The output line shows it (`Out 48 kHz float · 64-bit SRC · mixer`); the indicator for the shared path reads `MIXER 48 kHz`.
- Tests: level, residual (< −120 dB), passband, 96 → 48 kHz alias rejection, identical output for any callback size; a player run at 44.1 kHz into a 48 kHz device with pause / resume and exact position.

## 0.16.0 — 2026-10-10

### Bit-perfect USB output (phase 1 of docs/DIRECT_OUTPUT_PLAN.md)

- **Settings → Signal path → Bit-perfect USB** (Android 14+): with a USB DAC that offers bit-perfect formats, every track's stream opens at the file's own rate past Android's mixer (`setPreferredMixerAttributes` with `MIXER_BEHAVIOR_BIT_PERFECT`). The best format the DAC offers at that rate is used (32-bit, 24-bit, float, 16-bit). Rates the DAC does not offer, and phones that refuse, fall back to the mixer. Oboe's own conversions are disabled on this path, so a mismatch can never resample silently.
- **Integer output with dither**: the engine writes 16, 24 or 32-bit integers itself. Samples the format holds exactly pass unchanged (an untouched 16-bit file reaches a 24-bit DAC bit for bit; silence stays digital silence); only rounded samples get TPDF dither.
- **Honest indicator** on the player and in Settings: `BIT-PERFECT 96 kHz / 24-bit`, `DIRECT … · PROCESSED` (volume below 100 %, EQ, ReplayGain, crossfeed, speed, DSD, downmix, or the engine rounding samples, e.g. the limiter catching a peak), or `MIXED · SYSTEM MIXER` (`MIXER 48 kHz` from 0.17.0).
- On the direct path the system volume does nothing, so the volume keys drive the engine volume (remote volume on the media session). The output limiter only acts on real overs there (full scale instead of −0.1 dBFS).
- Plugging in or removing a DAC while playing, and switching the setting, reopen the stream without losing the position or queued audio.
- Native tests: bit-exact 16 → 24-bit output, exact −6 dB volume without dither, dither statistics, fallback, rate change across tracks, mode switch while playing. JVM tests for the format choice and the indicator rules.

## 0.15.0 — 2026-10-09

- **Lyrics**: a *Lyrics* panel on the player. Synced LRC (several time tags per line, `[offset:]`, word timings) follows the position and highlights the current line; plain lyrics scroll. Taken from `<name>.lrc` next to the file, else from the tags (`USLT`, `LYRICS`, `©lyr`); UTF-8 and Windows-1251 files.
- **Home-screen widget**: cover, title, artist and previous / play-pause / next, working with the app closed.
- Screenshots in the README show the current interface (covers, albums, parametric EQ).
- Roadmap rewritten for the current state.

## 0.14.0 — 2026-10-09

### DLNA

- **Streaming**: FLAC, WAV / AIFF, WavPack and TTA from a media server start playing after 512 KiB while the rest downloads. The engine waits for bytes that have not arrived; a pause or seek during a stall is handled without losing or repeating audio, and a broken download ends the track instead of hanging. One download per URL is shared by playback and the gapless pre-load; downloads that are no longer needed are cancelled.
- **Renderer** (Settings → *Play to this phone*): the phone appears on the Wi-Fi as a UPnP / DLNA MediaRenderer. Control points (BubbleUPnP, foobar2000, Windows "Cast to device", Kodi…) can send music to it, with gapless next track (`SetNextAVTransportURI`), play / pause / stop / seek / next / previous, volume and mute, and state events (GENA `LastChange`). Local network only, http(s) URLs only, off by default.

## 0.13.0 — 2026-10-09

### Sound

- **Parametric EQ** (up to 20 filters: peak, shelves, low / high pass) replaces the fixed five-band EQ in the engine; the five sliders remain as the *5 bands* mode. Headroom comes from the real peak of the response, so an EQ never clips.
- **AutoEQ**: search ~9000 measured headphones from the app and apply their correction, or import any AutoEQ / Equalizer APO `ParametricEQ.txt`.
- **Crossfeed** for headphones (bs2b, Chu Moy, Jan Meier presets), in double precision; mono passes at unity, *Off* is bit-exact.
- **True-peak limiter** (−1 dBTP, 4x oversampled detection, 1.5 ms look-ahead, 50 ms release) in the decode path: no inter-sample overs after gain and EQ, bit-exact below the ceiling, no samples added or lost (its latency is accounted for at gapless boundaries).
- **EBU R128 loudness**: files without ReplayGain tags are measured in the background (the current and next track, or the whole library on request) and normalised to −18 LUFS with their true peak, like tagged files. Album gain from the album's measured tracks.

### Engine

- Native tests for all of it (`tests/native/dsp_tests.cpp`, EBU Tech 3341 reference sine) and a player test that switches the limiter and crossfeed while playing and across a gapless boundary.
- Database version 7 (loudness measurements).

## 0.12.0 — 2026-10-09

### Library

- **Albums** (cover grid) and **Artists** pages built from the tags of the saved folders, with album and artist pages (play, shuffle, add) and **search** across albums, artists and tracks (Cyrillic case-insensitive). Folders, the device scan, DLNA and playlists moved to **Sources**.
- The index is incremental: unchanged files are not read again, removed files and folders disappear. It is built in the background on first start, after adding a folder and from *Update library*; progress is shown.
- Track lengths come from the file headers without decoding (FLAC, MP4, MP3 Xing/VBRI/CBR, Ogg, and a headers-only open for WAV, AIFF, DSD, WavPack, APE, TTA), so thousands of files index quickly.
- Albums group by album artist and album across folders, or per folder for compilations without an album artist.

### Covers

- Embedded pictures (FLAC, ID3, MP4, APE, Ogg) and folder pictures (`cover.jpg`, `folder.jpg`, `front.jpg`, …), scaled and cached once per picture.
- Shown in the player, the mini player, the library, on the **lock screen**, in the **notification** and in **Android Auto** (album grid; via a read-only content provider that only serves library tracks).

### Player and queue

- The player shows the cover, artist and album; queue rows show the artist.
- Queued tracks get their tags and lengths in the background (from the library or the file); in Books mode titles keep the file names.
- Android Auto: new **Albums** and **Artists** nodes; voice search also finds albums, artists and tracks of the library.

## 0.11.0 — 2026-10-09

### Formats

- New own decoders: **WavPack** (libwavpack 5.7), **Monkey's Audio / APE** (official SDK 13.27), **TTA** (libtta++ 2.3, as the separate LGPL library `libtta.so`), **Ogg Vorbis** (libvorbis 1.3.7) and **Ogg Opus** (libopus 1.5.2 + opusfile 0.12). Lossless formats decode bit-exact; all seeks are sample-exact (Opus: sample-aligned, the decoder converges within 100 ms).
- **AAC / HE-AAC (M4A, M4B, MP4, ADTS), ALAC** and anything else the phone decodes, through `MediaExtractor` + `MediaCodec`: float output where the codec offers it, encoder delay and padding trimmed (gapless AAC albums), HE-AAC / parametric stereo handled, sample-exact seeks.
- **CUE sheets**: a folder with `album.cue` + `album.flac` (or `.ape`, `.wv`, …) shows the sheet's tracks instead of the big file. Tracks of one file join without a gap; the pregap stays with the previous track. UTF-8, Windows-1251 and Windows-1252 sheets are detected; a sheet that says `.wav` finds the `.flac` next to it.
- Container detection skips an ID3v2 tag in front of any format and tells Ogg Opus / Vorbis / FLAC apart; multichannel Vorbis and Opus are reordered to the WAVE channel order.

### Tags

- One native tag reader for everything: Vorbis comments (FLAC, Ogg), ID3v2.2–2.4 (with unsynchronisation, data length indicators, UTF-16 BOM quirks), ID3v1, APEv2, MP4 `ilst` (incl. freeform items), WAV `LIST/INFO`, covers (FLAC `PICTURE`, `APIC`, `covr`, APE binary items, `METADATA_BLOCK_PICTURE`). Cyrillic text stored as "Latin-1" by Windows tools is shown correctly.
- ReplayGain now also from Opus R128 gains, APEv2 and MP4 tags.
- The media session (lock screen, car, Bluetooth) shows artist and album when known.

### App

- New queue order **Album** (album, disc, track number, then file name; CUE tracks in sheet order), the default for new installs. The sort button cycles A–Z → 1–9 → Album.
- Track durations for APE, WavPack, TTA and DSD come from the engine when Android's metadata reader does not know the format.
- Playlists keep artist, album and track numbers (database version 5, migrated in place).

### Docs

- New [docs/DIRECT_OUTPUT_PLAN.md](docs/DIRECT_OUTPUT_PLAN.md): where the audio goes today (the Android mixer, since the first version), and the plan for bit-perfect USB output.
- Engine, testing, third-party notices updated.

## 0.10.0 — 2026-10-09

### Android Auto

- `PlaybackService` is now a media browser service: Android Auto (and Android Automotive, Assistant, AVRCP browsing) shows **Queue**, **Playlists** and **Folders** (saved SAF folders with subfolders) and plays what is picked there.
- The session publishes the queue (up to 300 tracks around the current one), the active item, and supports play-from-media-id and voice search ("play … on HiFi Player"): queue titles, then playlist names, then folder names; an empty query resumes.
- Errors (a file that cannot be opened) are shown on the car screen.
- Only trusted hosts (Android Auto, Assistant, system UI, Bluetooth, the app itself) may read the library; any app can still control playback through the media session, as before.
- The exported service ignores everything except media-button intents (the unused internal stop action was removed).
- Voice search also works on the phone (`MEDIA_PLAY_FROM_SEARCH`).

## 0.9.2 — 2026-10-09

Fixes for the code audit of 0.9.1 (IDs from `AUDIT_CODE_2026-10-09.md`).

### Engine

- **A01** `play()` / `seekToMs()` no longer read the decoder owned by the decode thread (data race found by TSan); an atomic "loaded" flag is used instead.
- **A02** A pause that lands while a block is being decoded keeps the processed block and writes it on resume — no 21 ms gap.
- **A03** A next track shorter than the look-ahead: the previous decoder is kept until its boundary is heard, and only one look-ahead switch happens at a time, so seeks and `clearNext()` still apply to the audible track.
- **A04** For a format-change transition the next track is taken only after the output drained; clearing it during the drain now cancels the transition.
- **A05** Pre-loads: the JNI generation check and publishing the decoder happen under one lock with `clearNext()` / `load()`; the service invalidates an in-flight pre-load (not only a finished one) and verifies the track by URI.
- **A07** Format and spectrum queries no longer wait for a stream being opened (lock-free "configured" flag).
- **A08** NaN/Inf samples (e.g. a damaged float WAV) become silence before the DSP, with a final guard in the output callback; finite over-full-scale samples are kept.

### App

- **A06** Foreground promotion before requesting audio focus (Android 15+ refuses focus to background services); a started command keeps the service foreground until it is handled.
- **A09** DLNA picks the first resource the engine can decode (by extension, else by the `protocolInfo` MIME); items with only unsupported resources are skipped.
- **A10** DLNA responses are size-limited (1 MiB description, 8 MiB per Browse page, 100 pages).
- **A11** DLNA cache: hit checked before pruning, room made before and after a download, the playing file is never pruned, only really deleted files are counted.
- **A12** Selecting a track without playing ends playback cleanly (focus, foreground, state).
- **A13** Folders with a session-only SAF grant are usable until restart and the user is told so.
- Concurrent library scans keep the progress indicator until the last one finishes; playlist database errors show a message instead of failing silently.

## 0.9.1 — 2026-10-09

### Fixed

- **Adding a folder added nothing.** The system folder picker stops the activity, which unbound the playback service and tore down the screen waiting for the picker's result. The service now stays bound for the activity's lifetime, and folder / device scans run in the service, so switching tabs during a scan no longer loses it.
- **Engine instances are per service.** A destroyed service could release the native player a new service had just started using (JNI now addresses instances by id).
- **Device reconnect** counts as recovered only when the new stream actually started; if the device stays unavailable the player reports an error instead of silently "playing".
- **Gapless look-ahead.** While the end of a track is still audible and the decoder already runs on the next one, a seek applies to the audible track and clearing the next track undoes the switch.
- **Failed track open** silences the previous track and releases audio focus instead of leaving it playing behind a paused UI.
- **Ducking** is reset when focus is granted again, so playback can no longer stay at a quarter of the volume.
- **Bookmarks from 0.8.x** (stored by hash) are migrated on first read.

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
