# Native tests

```bash
bash tests/native/run.sh          # ASan + UBSan
TSAN=1 bash tests/native/run.sh   # plus ThreadSanitizer
```

- `decoder_tests.cpp` — decoders, DSD decimator quality, ReplayGain tags, ring buffer, Sonic, EQ, downmix.
- `player_tests.cpp` — `AudioPlayer` + `OboeOutput` scenarios against the simulated stream in `stubs/oboe/Oboe.h`.
- `test_util.h` — `CHECK` macros, fixture writers, sine fitting.

Fixtures and binaries live in a temporary directory removed after the run.
Details: [docs/TESTING.md](../../docs/TESTING.md).
