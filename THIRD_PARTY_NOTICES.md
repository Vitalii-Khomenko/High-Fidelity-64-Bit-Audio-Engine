# Third-party notices

| Component | Use | License |
|---|---|---|
| [dr_flac, dr_wav, dr_mp3](https://github.com/mackron/dr_libs) (David Reid) | FLAC, WAV/AIFF and MP3 decoding (`src/decoders/dr_*.h`) | Public domain or MIT-0 |
| [Sonic](https://github.com/waywardgeek/sonic) (Bill Cox) | Time-stretching (`src/third_party/sonic`); modified to process float samples | Apache License 2.0 |
| [libogg 1.3.5](https://github.com/xiph/ogg), [libvorbis 1.3.7](https://github.com/xiph/vorbis), [libopus 1.5.2](https://github.com/xiph/opus), [opusfile 0.12](https://github.com/xiph/opusfile) (Xiph.Org) | Ogg Vorbis and Ogg Opus decoding (`src/third_party/{ogg,vorbis,opus,opusfile}`) | BSD 3-clause — `COPYING` in each directory |
| [WavPack 5.7.0](https://github.com/dbry/WavPack) (David Bryant) | WavPack decoding (`src/third_party/wavpack`) | BSD 3-clause — `src/third_party/wavpack/COPYING` |
| [Monkey's Audio SDK 13.27](https://www.monkeysaudio.com) (Matthew T. Ashland) | APE decoding (`src/third_party/monkeys-audio`) | BSD 3-clause — `src/third_party/monkeys-audio/License.txt` |
| [libtta++ 2.3](https://sourceforge.net/projects/tta/) (Aleksander Djuric) | TTA decoding, built as the separate shared library `libtta.so` (`src/third_party/libtta`) | **GNU LGPL 3** — `src/third_party/libtta/COPYING`; changes listed in `src/third_party/libtta/CHANGES` |
| [Oboe](https://github.com/google/oboe) | Audio output | Apache License 2.0 |
| [bs2b](https://bs2b.sourceforge.net) (Boris Mikhaylov) | The crossfeed algorithm and presets, re-implemented in `src/dsp/Crossfeed.h` | MIT |
| [libebur128](https://github.com/jiixyj/libebur128) (Jan Kokemüller) | K-weighting filter design for any sample rate, re-implemented in `src/dsp/LoudnessMeter.h` | MIT |
| [AutoEQ](https://github.com/jaakkopasanen/AutoEq) (Jaakko Pasanen) | Headphone profiles, downloaded on request from GitHub (not bundled) | MIT |
| AndroidX, Jetpack Compose, Room, Kotlin coroutines | App | Apache License 2.0 |
| [Sora](https://github.com/sora-xor/sora-font) | Display font (`res/font/sora_*.ttf`, static instances) | SIL Open Font License 1.1 — [docs/licenses/Sora-OFL.txt](docs/licenses/Sora-OFL.txt) |
| [Source Sans 3](https://github.com/adobe-fonts/source-sans) | Body font (`res/font/source_sans_*.ttf`, Latin + Cyrillic subset) | SIL Open Font License 1.1 — [docs/licenses/SourceSans3-OFL.txt](docs/licenses/SourceSans3-OFL.txt) |

The modification to Sonic is described at the top of `sonic.c` (sample type
changed from `short` to `float`; the algorithm is unchanged).

libtta is the only copyleft component. It is linked dynamically as
`libtta.so`, built from the unmodified-except-as-noted source in this
repository, so it can be rebuilt and replaced in the APK. Its two changes
(an allocation call that is portable to older Android versions, and a
hand-written `config.h`) are described in `src/third_party/libtta/CHANGES`.

All other decoders are used unmodified; only the files needed for decoding
were copied (encoder-only parts of libvorbis and the upstream build systems
were left out). The linker keeps only the decoder code the engine uses.
