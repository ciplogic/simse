# 1 Billion Row Challenge (naive)

The [1BRC](https://github.com/gunnarmorling/1brc): read a file of
`station;temperature` lines, aggregate min/mean/max per station, print the report
sorted by station. This folder holds the *naive* Simse implementation, the C++ STL
baseline it is compared against, and the generator/reference that both are checked
against.

Nothing here is optimized: no mmap, no chunked parsing, no per-station arrays, no
threads. The point is a straight-line implementation in each language, measured on
the same data.

| File | What it is |
| --- | --- |
| `src/main.simse` | the Simse implementation (package `onebrc`) |
| `brc_naive.cpp` | the C++ baseline: `std::ifstream` + `std::getline` + `std::unordered_map<std::string, Stats>` + `std::stod` |
| `build_naive.bat` | compiles the baseline with the release flags (`/O2 /Ob3 /DNDEBUG`) |
| `onebrc.mjs` | `gen` writes a measurement file, `check` is the reference aggregate, `--selftest` covers its rounding/chunk logic |

## Running it

```bat
:: data (127.7 MiB, 10M rows, 100 stations): ~0.5 s to generate
bun benchmarks\onebrc\onebrc.mjs gen 10000000 benchmarks\onebrc\data\measurements.txt --seed 42

:: the Simse implementation: convenient reader, then the recycled-buffer reader
bun build.js --release --root benchmarks\onebrc --out benchmarks\onebrc\onebrc.cpp --exe benchmarks\onebrc\onebrc.exe
benchmarks\onebrc\onebrc.exe benchmarks\onebrc\data\measurements.txt
benchmarks\onebrc\onebrc.exe benchmarks\onebrc\data\measurements.txt into

:: the C++ baseline
benchmarks\onebrc\build_naive.bat
benchmarks\onebrc\brc_naive.exe benchmarks\onebrc\data\measurements.txt

:: the reference report (stdout = the report, so it can be diffed)
bun benchmarks\onebrc\onebrc.mjs check benchmarks\onebrc\data\measurements.txt
```

The data file and the built binaries are ignored by git
(`benchmarks/onebrc/data/`, `*.exe`, `*.obj`).

## Results

10,000,000 rows, 133,931,538 B (127.7 MiB), 100 stations, one ARM64 laptop,
release builds, wall clock (min/median of 4 interleaved runs for the Simse
versions; the C++ baseline reports its own time over 3 runs, min/median),
throughput over the **min** time in decimal MB/s (133.93 MB / seconds):

| Implementation | Time | Throughput | Peak working set |
| --- | --- | --- | --- |
| Simse, `readLine(): Opt<Str>` (convenient) | 2075 / 2127 ms | 64 MB/s | 6.6 MB |
| **Simse, `readLineInto(*line)` (recycled buffer)** | **1156 / 1211 ms** | **116 MB/s** | 6.5 MB |
| C++ STL baseline (`getline` + `stod`) | 1550 / 1570 ms | ~86 MB/s | 5.9 MB |
| `onebrc.mjs check` (Bun, reference) | 732 ms | 183 MB/s | — |

All four reports are **byte-identical** (the Simse and C++ writers are compared
after stripping the `\r` that Windows text-mode stdout adds; see the notes).

What the numbers say:

- **The reader dominates, not the parsing.** The convenient path hands back a
  fresh `Opt<Str>` per line (one `Str` copy, and an allocation once a line is
  longer than the 23-byte inline capacity); it lands 1.34x *behind* the C++
  baseline. Reading into one recycled `Str` (one `memcpy` per line, no
  allocation) lands 1.34x *ahead* of it - a 1.8x difference between two ways of
  reading the same bytes.
- The JS reference is still 1.6x faster than the best Simse path: it never builds
  a per-line object, aggregates in place, and its inner loop is a byte scan.
- The Simse dictionary costs two lookups per line here (`get` then `insert`,
  because the API has no in-place access to a stored value), where the C++
  baseline does one. Fixing that is a library improvement, not a language one.

## Notes for whoever reads the report

- **Tenths, not doubles.** Temperatures are aggregated as `Int` tenths of a degree
  in both the Simse program and the reference `check`. That is exact, it is the
  unit the report is in, and it avoids the fact that the language has no `format`:
  `println` would print a `Float64` with C++'s default six digits.
- **The mean's rounding rule.** `x.x5` is rounded half toward positive infinity,
  the rule the challenge's Java reference uses (`Math.round(mean * 10) / 10`),
  implemented exactly on integers. The C++ baseline prints with `printf("%.1f")`,
  which rounds the binary double half-to-even; on this data set the two agree, but
  `check` is the reference if they ever disagree.
- **CRLF vs LF.** `println` goes through `std::cout` in text mode, so on Windows
  the Simse report has CRLF line endings while `check` writes LF. The comparison
  strips `\r` first (`tr -d '\r'`). The C++ baseline switches stdout to binary mode
  so its report is directly comparable.
- **Borrowing the dictionary.** `Dictionary` is a value type, so the aggregation
  takes a `*Dictionary` (a raw pointer) - `&counts` would box a *copy* and the
  aggregates would be written into the box instead of the local. This is the one
  place where the language's memory model has to be respected to get the obvious
  program: `*x` borrows, `&x` boxes a copy.
- **Station names.** The built-in list is 100 ASCII names; `--stations <csv>`
  takes the official `weather_stations.csv`. Sorting is byte order on both sides,
  so non-ASCII names would need the same collation everywhere.
