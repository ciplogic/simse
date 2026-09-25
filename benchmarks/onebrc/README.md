# 1 Billion Row Challenge (naive)

The [1BRC](https://github.com/gunnarmorling/1brc): read a file of
`station;temperature` lines, aggregate min/mean/max per station, print the report
sorted by station. This folder holds the *naive* Simse implementation, the C++ STL
baseline it is compared against, and the generator/reference that both are checked
against. **The measured result is in [`benchmark.md`](benchmark.md)** - the Simse
program is 1.16x faster than the naive C++ one on 10M rows.

Nothing here is optimized: no mmap, no chunked parsing, no per-station arrays, no
threads. The point is a straight-line implementation in each language, measured on
the same data.

| File | What it is |
| --- | --- |
| `src/main.kt` | the Simse implementation (package `onebrc`): a line at a time, each line parsed in place as a `StrView`, each station's aggregate updated in place through `Dictionary.getPtr` |
| `brc_naive.cpp` | the C++ baseline: `std::ifstream` + `std::getline` + `std::unordered_map<std::string, Stats>` + `std::stod` |
| `build_naive.bat` | compiles the baseline with the release flags (`/O2 /Ob3 /DNDEBUG`) |
| `onebrc.mjs` | `gen` writes a measurement file, `check` is the reference aggregate, `--selftest` covers its rounding/chunk logic |
| `benchmark.md` | the measurement: environment, method, numbers, where the time goes, how to reproduce |

## Running it

```bat
:: data (127.7 MiB, 10M rows, 100 stations): ~0.5 s to generate
bun benchmarks\onebrc\onebrc.mjs gen 10000000 benchmarks\onebrc\data\measurements.txt --seed 42

:: the Simse implementation (release build; it reports its own time on stderr)
bun build.js --release --root benchmarks\onebrc --module cppsrc\modules\io --out benchmarks\onebrc\onebrc.cpp --exe benchmarks\onebrc\onebrc.exe
benchmarks\onebrc\onebrc.exe benchmarks\onebrc\data\measurements.txt

:: the C++ baseline
benchmarks\onebrc\build_naive.bat
benchmarks\onebrc\brc_naive.exe benchmarks\onebrc\data\measurements.txt

:: the reference report (stdout = the report, so it can be diffed)
bun benchmarks\onebrc\onebrc.mjs check benchmarks\onebrc\data\measurements.txt
```

The data file and the built binaries are ignored by git
(`benchmarks/onebrc/data/`, `onebrc.cpp`, `*.exe`, `*.obj`).

## The reader, in the RTL

The benchmark uses the in-place reader, `FileStream.readLineView(): Opt<StrView>`,
which hands back a view into the stream's 256 KiB readahead buffer instead of
copying the line. The RTL also has the two earlier reads - `readLine(): Opt<Str>`
(one `Str` per line) and `readLineInto(*Str)` (one recycled buffer) - and
`benchmark.md` records what each of them costs; all three are covered by
`stress/read-lines` and documented in `impl_specs/rtl-abi.md`.
