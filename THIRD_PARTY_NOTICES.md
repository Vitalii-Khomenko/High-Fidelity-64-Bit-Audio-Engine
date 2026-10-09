# Third-party notices

| Component | Use | License |
|---|---|---|
| [dr_flac, dr_wav, dr_mp3](https://github.com/mackron/dr_libs) (David Reid) | FLAC, WAV/AIFF and MP3 decoding (`src/decoders/dr_*.h`) | Public domain or MIT-0 |
| [Sonic](https://github.com/waywardgeek/sonic) (Bill Cox) | Time-stretching (`src/third_party/sonic`); modified to process float samples | Apache License 2.0 |
| [Oboe](https://github.com/google/oboe) | Audio output | Apache License 2.0 |
| AndroidX, Jetpack Compose, Room, Kotlin coroutines | App | Apache License 2.0 |
| [Sora](https://github.com/sora-xor/sora-font) | Display font (`res/font/sora_*.ttf`, static instances) | SIL Open Font License 1.1 — [docs/licenses/Sora-OFL.txt](docs/licenses/Sora-OFL.txt) |
| [Source Sans 3](https://github.com/adobe-fonts/source-sans) | Body font (`res/font/source_sans_*.ttf`, Latin + Cyrillic subset) | SIL Open Font License 1.1 — [docs/licenses/SourceSans3-OFL.txt](docs/licenses/SourceSans3-OFL.txt) |

The modification to Sonic is described at the top of `sonic.c` (sample type
changed from `short` to `float`; the algorithm is unchanged).
