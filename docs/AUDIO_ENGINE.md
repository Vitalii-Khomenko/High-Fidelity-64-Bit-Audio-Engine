# The audio engine

The engine is header-only C++17 under `src/`, compiled into `libaudioengine.so`
together with the JNI bridge (`src/jni/audio_engine_jni.cpp`) and Sonic.

```
src/
  core/AudioPlayer.h        transport, decode thread, position model, gapless
  core/AudioBuffer.h        planar double buffer
  core/RingBuffer.h         lock-free SPSC ring with a safe flush
  hw/OboeOutput.h           ring -> volume/fade/limiter -> Oboe float stream
  decoders/                 FLAC, WAV/AIFF, MP3, DSD, ReplayGain tag reader
  dsp/                      EQ (biquads), time-stretch, FIR design, downmix, spectrum
  third_party/sonic/        Sonic time-stretcher, converted to float processing
```

## Signal path

```
 file ─► decoder ─► EQ (+auto preamp) ─► ReplayGain ─► [time-stretch] ─► edge ramp ─► downmix ─► ring
        (double)        (double)            (double)       (float)                    (if needed)   │
                                                                                                    ▼
                         device ◄── float ◄── limiter ◄── fade ◄── volume ◄──────── Oboe callback
```

- **Decoders** deliver planar `double`. Integer PCM is scaled by 2⁻³¹ (exact),
  64-bit float WAV is read bit-exact, MP3 comes from dr_mp3 as float.
- **Decode thread** (`AudioPlayer::decodeLoop`) keeps about 300 ms queued in the
  ring (sized for 750 ms) and sleeps in 4 ms steps otherwise. It runs at
  `nice -16` where permitted.
- **Oboe callback** (`OboeOutput::onAudioReady`) pulls from the ring without
  locks or allocation and applies, per frame: the transport fade, a linear volume
  ramp (a full-scale change takes 30 ms), and a peak limiter with a −0.1 dBFS
  ceiling (instant attack, 150 ms release, inactive below the ceiling).

Volume and fades sit after the buffer on purpose: they react within one
callback, while EQ and ReplayGain belong to the source track and must change
exactly at track boundaries.

## Transport

| Call | Effect |
|---|---|
| `load(decoder, gain)` | Stops the decode thread, fades out (25 ms) if playing, reconfigures the stream only if the format changed, resets DSP and position. State becomes `Paused`. |
| `play()` | Starts the decode thread, then the stream with a 12 ms fade-in. From `Ended` it restarts at 0. |
| `pause()` | Stops the decode thread, fades out over 40 ms, stops consuming at that frame and pauses the stream (`requestPause`) so the audio path can power down. The ring keeps its content: `play()` continues with the next sample — no decoder seek. |
| `seekToMs(ms)` | While playing: handled by the decode thread (flush, decoder seek, 4 ms start ramp). While stopped: applied synchronously. |
| `stop()` | `pause()` plus a seek to 0. |

States: `Idle` → `Paused` ⇄ `Playing` → `Ended`; `Error` if no output stream
could be opened. The Kotlin service polls `state()` instead of guessing from
"is playing" transitions.

## Position model

Position is derived from frames the device callback has actually consumed. A
small list of **segments** maps output frames to source frames:

```
segment = { startOut (consumed-frame index), startSource (source frame), rate, track info }
```

A new segment starts when a seek flushes the ring, when the time-stretch speed
changes (at the current write position), and at a gapless switch (at the frame
where the next track begins in the ring). Readers promote segments once
`consumed >= startOut`. Consequences:

- the reported position and duration follow what is heard, not what was decoded;
- the "track changed" event (`consumeTrackAdvanced`) fires when the boundary
  reaches the device, so UI and notification switch at the audible moment;
- underruns and paused output do not advance the clock.

All queries (`positionMs`, `durationMs`, `trackInfo`, `state`) only touch atomics
and a short mutex; they never wait for decoding, file I/O or seeking.

## Gapless and format changes

`setNext()` stores a pre-opened decoder. When the current decoder ends:

- **Same sample rate and channel count** — decoders are swapped inside the
  decode loop; the next sample written to the ring is the first sample of the
  new track. In the time-stretch path the stretcher keeps running across the
  boundary. EQ state is preserved, ReplayGain switches at the boundary.
- **Different format** — the ring is drained, the stream is reopened for the
  new format and playback continues (a short gap of the stream start latency).
- **No next track** — the ring is drained, the stream paused, state `Ended`.

Because the decoder runs ~300 ms ahead, it switches before the boundary is
audible. Until then the finished decoder is kept: a seek in that window applies
to the track being heard (the next track is rewound and re-queued), and
`clearNext()` undoes the switch and continues the current track from the
audible frame. Only one such look-ahead switch exists at a time: if the next
track is shorter than the look-ahead, the decode thread waits until the earlier
boundary is heard before moving on. A format-change transition takes the next
track only after the output has drained, so it can still be cancelled.

A pause that arrives while a block is being decoded keeps the processed block
and writes it first on resume. NaN and infinite samples are replaced by silence
before the EQ (they would otherwise poison its filter state) and again in the
output callback.

The JNI layer guards the next slot with a generation counter, so a pre-load that
finishes after `load()` or `clearNext()` is discarded.

## Output stage (`hw/OboeOutput.h`)

Stream settings: shared mode, `PerformanceMode::None` (normal mixer path, lower
power than low-latency), usage *media* / content *music*, float format.
Format, channel and sample-rate conversion by Oboe are allowed
(`SampleRateConversionQuality::High`), so a device that refuses e.g. 352.8 kHz
still plays correctly. If a multichannel layout cannot be opened the engine
opens stereo and folds down (`dsp/ChannelMixer.h`: centre and surrounds at
−3 dB, LFE dropped, normalised).

**Reconnect:** Oboe reports a disconnect through `onErrorAfterClose`. The error
callback only holds a shared signal object (never a pointer to the output, which
may already be gone). A worker thread reopens the stream with the *same* format,
keeps the queued audio, and restarts it if playback was running; recovery counts
only once the stream has started. It retries for about 10 seconds; after that
the player reports `Error` instead of appearing to play. A healthy stream that
was already replaced is left alone.

**Callback state** (ring pointer, scratch buffers, channel count) changes only
while no stream is open; sample rate, channel count and the "configured" flag
are atomics, so queries never wait for a stream being opened or reconnected.

## Ring buffer (`core/RingBuffer.h`)

Single producer (decode thread), single consumer (callback), power-of-two
capacity, acquire/release indices. `clear()` (seek, load) is the only operation
that touches both indices: it raises a reset gate, waits for in-flight copies
to finish and resets; reads and writes that start during the reset return 0.
The callback never blocks on it.

## Decoders

| Decoder | Library | Notes |
|---|---|---|
| `FlacDecoder` | dr_flac | FLAC and Ogg FLAC; s32 output, exact |
| `WavDecoder` | dr_wav | RIFF, RIFX, RF64, W64, AIFF/AIFC; integer PCM via s32, 64-bit float read natively, others (A-law, µ-law, ADPCM, 32-bit float) via f32 |
| `Mp3Decoder` | dr_mp3 | One pass over the frame headers at open: exact frame count and a seek point about every second. Encoder delay and padding from the LAME header are honoured. |
| `DsdDecoder` | own | DSF and uncompressed DSDIFF, see below |
| `WavPackDecoder` | libwavpack 5.7 | Lossless and hybrid (without `.wvc`), integer up to 32-bit and float; bit-exact |
| `ApeDecoder` | Monkey's Audio SDK 13.27 | All compression levels, 8–32-bit, float; bit-exact |
| `TtaDecoder` | libtta++ 2.3 (`libtta.so`, LGPL) | 16/24-bit; libtta seeks to frame starts (~1 s), the rest is decoded and dropped, so seeks are sample-exact |
| `VorbisDecoder` | libvorbis 1.3.7 + vorbisfile | Sample-exact seeks |
| `OggOpusDecoder` | libopus 1.5.2 + opusfile 0.12 | Always 48 kHz; pre-skip, end trimming and header gain applied, so Opus albums are gapless |
| `MediaCodecDecoder` | Android `MediaExtractor` + `MediaCodec` | AAC / HE-AAC (M4A, MP4, ADTS), ALAC, and whatever else the phone decodes (Matroska / WebM, AMR, …). See below. |
| `RangeDecoder` | — | A `[start, end)` section of another decoder: a CUE sheet track |

Every decoder reads through `FileSource`: a private `dup()` of the descriptor
with its own offset and `pread()`, so decoders never share a file position with
each other or with the tag reader. The libraries' I/O callbacks (vorbisfile,
opusfile, WavPack stream reader, `IAPEIO`, `TTA_io_callback`) are thin adapters
over it.

**Container sniffing** (`decoders/DecoderFactory.h`): an ID3v2 tag at the start
is skipped, then the signature decides — `fLaC`, `OggS` (the first packet tells
Opus, Vorbis and FLAC apart), `RIFF`/`RF64`/`FORM`, `DSD `/`FRM8`, `wvpk`,
`MAC `, `TTA1`, `ftyp` (MP4), EBML (Matroska), ADTS sync (layer bits 00) and
MPEG audio sync. Unknown headers fall back to FLAC, WAV and MP3, and finally
MediaCodec. Multichannel Vorbis and Opus are reordered from the Vorbis channel
order to the WAVE order the rest of the engine uses.

**MediaCodec** decoders are asked for float PCM; ones that ignore it deliver
16-, 24- or 32-bit integers, which are converted exactly. The decoder's own
output format wins over the container (HE-AAC doubles the rate, parametric
stereo turns mono into stereo). Encoder delay and padding from the container
(`encoder-delay` / `encoder-padding`, i.e. iTunSMPB and edit lists) are
trimmed, so AAC albums are gapless. A seek starts 100 ms early and drops
everything before the target, which avoids the MDCT warm-up glitch and makes it
sample-exact. Format keys are spelled out instead of `AMEDIAFORMAT_KEY_*`
because several of those constants only exist from API 28/29. The decoder
reports its first block while opening, so a stream the phone cannot decode
fails to load instead of failing mid-playback.

**CUE sheets**: the app sends a start and an end time with the file
(microseconds; the CUE's 1/75 s frames are exact at the sample level). The
engine wraps the decoder in a `RangeDecoder`; consecutive tracks of one file
share their cut point, so the normal gapless path joins them without a gap or
an overlap.

## DSD to PCM

A 1-bit stream at 2.8224 MHz (DSD64) and above is decimated in two linear-phase
stages:

1. **Byte-LUT FIR**, 128 taps (Kaiser, 120 dB), one output per DSD byte, i.e. at
   fs/8 (352.8 kHz for DSD64). Sixteen 256-entry tables turn the filter into 16
   additions per byte. Its stopband (> ~0.08 fs) removes the modulator noise that
   would otherwise fold into the audio band.
2. **Decimating FIR**, 16 taps per output phase (Kaiser, 110 dB), down to
   **88.2 kHz** (44.1 kHz family) or **96 kHz** (48 kHz family). The passband is
   flat to 24 kHz; everything that would alias below 24 kHz is ≥ 110 dB down.
   The filter is symmetric, so mirrored taps share one multiply.

Gain is unity: an all-ones stream decodes to +1.0, so SACD 0 dB (50 %
modulation) peaks near −6 dBFS. Seeking pre-rolls enough bytes to fill both
filter histories, so a seek reproduces the continuous decode sample for sample.
Filter histories start from the DSD idle pattern `0x69`, not zero.

Throughput on a desktop core (single thread, stereo): DSD64 81×, DSD128 39×,
DSD256 18×, DSD512 9× real time.

## Tags and ReplayGain (`tags/TagReader.h`)

One reader serves the library (title, artist, album, album artist, track and
disc numbers, year, genre, lyrics, cover) and the engine (ReplayGain). It uses
`pread` only, so the descriptor's file position is untouched:

- FLAC: `VORBIS_COMMENT` and `PICTURE` blocks;
- Ogg Vorbis / Opus / FLAC: the comment packet (reassembled across pages),
  `METADATA_BLOCK_PICTURE`; Opus `R128_TRACK_GAIN` / `R128_ALBUM_GAIN`
  (Q7.8 dB at −23 LUFS) become ReplayGain values (+5 dB to the −18 LUFS
  reference);
- ID3v2.2 / 2.3 / 2.4 (MP3, and inside WAV, AIFF, DSF, DFF, TTA, APE):
  text frames, `TXXX`, `USLT`, `APIC` / `PIC`; Latin-1, UTF-16 (with repeated
  BOMs), UTF-16BE, UTF-8; tag-level and frame-level unsynchronisation, data
  length indicators, extended headers; compressed or encrypted frames are
  skipped;
- APEv2 (APE, WavPack, TTA, MP3) including binary cover items; ID3v1 as the
  last fallback;
- MP4 / M4A: `moov/udta/meta/ilst` (`©nam`, `©ART`, `aART`, `©alb`, `©day`,
  `trkn`, `disk`, `gnre`, `covr`, `©lyr`, and `----` freeform items such as
  `replaygain_track_gain`);
- WAV `LIST/INFO`;
- a plain-text scan of the first 64 KiB as the last resort for ReplayGain.

Several tag blocks may exist in one file; the first one read fills a field and
later ones only fill what is still empty. Text marked as Latin-1 is decoded as
Windows-1251 when most of its letters are in the upper half (Cyrillic tags
written by Windows software), and stays Latin-1 for Western text with a few
accented letters. A front cover beats any other picture. Large pictures are
read only when asked for.

`REPLAYGAIN_TRACK_GAIN`, `_PEAK`, `ALBUM_GAIN`, `_PEAK` are parsed locale-
independently. The applied linear gain is limited to `1 / peak` when a peak is
known. Album mode falls back to track gain and vice versa.

## Equaliser

Five RBJ biquads in `double`: low shelf 60 Hz, peaks at 230 Hz / 910 Hz / 3.6 kHz
(Q 0.9), high shelf 14 kHz; ±12 dB. When enabled, the largest boost is
subtracted as a preamp so boosted bands do not clip. Changes are picked up by
the decode thread (they are heard after the ~300 ms buffer).

## Time-stretch

Sonic (PICOLA with AMDF pitch detection, Apache-2.0) changes speed with pitch
preserved, in a *Music* (higher quality) or *Speech* profile. The bundled copy
was converted from 16-bit integer to float sample processing — the algorithm is
unchanged, but quiet passages keep their resolution (−66 dBFS tone: SNR 149.5 dB
instead of 31 dB) and signals above full scale are no longer clipped. At exactly
1.00× the stretcher is bypassed. Switching between the bypass and the stretcher
restarts decoding at the audible frame.

## Spectrum

The callback writes a mono mix (after the fade, before volume) into a
4096-sample ring of atomics. `spectrum()` takes the newest 2048 samples, applies
a Hann window and an FFT and returns up to 64 log-spaced bands (20 Hz – 20 kHz,
60 dB range, fast attack / slow decay). The display therefore matches what is
heard rather than what was decoded 300 ms earlier.

## JNI API

| Kotlin (`AudioEngine`) | Native |
|---|---|
| `load(context, uri, replayGain)` / `loadNext(...)` | Takes ownership of a detached fd; opens the decoder **outside** any global lock (a CUE range is split off the URI and passed as start / end), then calls `load()` / `setNext()` |
| `play()`, `pause()`, `stop()`, `seekTo(ms)` | Transport |
| `setVolume`, `setSpeed`, `setSpeedMode`, `setEqEnabled`, `setEqBand` | Controls |
| `state()`, `positionMs()`, `durationMs()`, `format()`, `consumeTrackAdvanced()`, `spectrum()` | Non-blocking queries |

`NativeTags` (no instance; the caller keeps the fd): `readTags(fd)` returns the
fields as NUL-separated UTF-8 bytes (JNI `NewStringUTF` cannot take 4-byte
UTF-8), `readPicture(fd)` the cover bytes, `probeDurationMs(fd)` the length
without decoding (`decoders/DurationProbe.h`: FLAC STREAMINFO, MP4 `mvhd`,
Xing / VBRI frame counts or the CBR byte count for MP3, the last Ogg granule
position, and a headers-only decoder open for the other formats), used to
index thousands of files quickly.

A per-instance lock makes "check the pre-load generation, then publish the
decoder" atomic with `clearNext()` and `load()`, so a cancelled pre-load can
never land in the next slot.

Each Kotlin `AudioEngine` owns a native instance addressed by an id
(`nativeCreate` / `nativeRelease`). A registry mutex only guards the id map;
each call copies the instance's `shared_ptr`, so a slow call never blocks the
others, release cannot free an object still in use, and one service can never
release another service's player.

## Threads and locks

| Thread | Does | Locks |
|---|---|---|
| Kotlin `engine-control` | load / play / pause / seek, in order | `m_control` |
| Decode thread | decoding, DSP, ring writes, format changes | `m_infoMutex` (short), `m_nextMutex`, `m_eqMutex` |
| Oboe callback | ring reads, fades, limiter | none |
| Reconnect worker | reopening the stream | stream mutex |
| Any (UI, service) | queries | `m_infoMutex` (short), spectrum mutex |

`tests/native/player_tests.cpp` exercises these paths concurrently and is run
under ThreadSanitizer (`TSAN=1 bash tests/native/run.sh`).
