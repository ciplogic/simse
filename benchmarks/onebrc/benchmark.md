# Naive 1BRC: C++ STL vs Simse, measured

Two straight-line implementations of the same naive algorithm: read a
`station;temperature` line, aggregate min/mean/max per station in a hash map, sort,
print. No mmap, no chunked parsing, no per-station arrays, no threads - the first
thing one writes, in both languages.

| | |
| --- | --- |
| Simse | `src/main.simse` - `readLineView(): Opt<StrView>` parses each line **in place** |
| C++ | `brc_naive.cpp` - `std::ifstream` + `std::getline` + `std::stod` + `std::unordered_map<std::string, Stats>` |
| Reference | `onebrc.mjs check` - the aggregate in JavaScript, which both reports are compared against |

Data: `onebrc.mjs gen 10000000 ... --seed 42` - **10,000,000 lines**, 133,931,538
bytes (127.7 MiB), 100 stations.

## Result

Windows on ARM64, MSVC (C++20) with `/O2 /Ob3 /DNDEBUG`, single-threaded. Each
binary reports its own time over the read-and-aggregate loop (printing excluded);
4 interleaved pairs (baseline, Simse, baseline, Simse, ...) because this machine
throttles under load, with the Bun reference aggregate in the same loop as a canary
for the machine's state; min/median of the four:

| Implementation | Time (min/median) | Throughput | Peak working set |
| --- | --- | --- | --- |
| **Simse, parsing in place (`readLineView`)** | **1093 / 1106 ms** | **123 / 121 MB/s** | 6.5 MB |
| C++ STL baseline | 1390 / 1405 ms | 96 / 95 MB/s | 5.9 MB |
| Bun reference aggregate (`check`) | 712 ms | 188 MB/s | — |

**The Simse program is 1.27x faster than the naive C++ one** on the same data, and
1.54x behind the JavaScript reference. Both programs' reports are byte-identical to
the reference (100 stations, values in exact tenths).

The ratio is one session's number, not a constant: measured in the same style on the
same data it has run **1.26x-1.40x**, because the C++ baseline varies more than the
Simse program does (1390-1580 ms against 1056-1177 ms across sessions, each with the
reference leg as a canary - 705-727 ms in the cool sittings). The Simse side is the
stable one.

## Where the time goes

- **The reader is the difference in this pair.** The Simse program copies nothing
  per line: `readLineView()` returns a `StrView` into the stream's 256 KiB
  readahead buffer (the newline is found with `memchr`), and the `;` split and the
  digits are read straight out of the buffer. The C++ baseline copies each line
  twice - `getline` into a `std::string`, then `substr` for the temperature - and
  pays `std::stod` per row.
- **What the Simse path still copies:** the station name (it is the dictionary's
  key type, so it has to become a `Str`) and the temperature (`tenths` takes a
  `Str`). Both are small enough to stay in `Str`'s inline buffer, but they are the
  obvious next thing to remove - `StrView.toInt()` and a `StrView`-keyed lookup
  would do it. See `guide4ai.md` section 9.
- **The dictionary costs two lookups per line.** `get` then `insert`, because the
  API has no in-place access to a stored value. The C++ baseline does one. That is
  a library gap, not a language one, and is the single largest remaining item.
- **Memory is a wash.** 6.5 MB against 5.9 MB peak working set: neither program
  holds the file, and the dictionary of 100 stations dominates both.

For context, the reader alone is worth **1.88x** on this workload. Measured the
same way on the same data while the benchmark still had all three modes (the
readers all remain in the RTL, `impl_specs/rtl-abi.md`):

| Reader | Time (min/median) | What it costs per line |
| --- | --- | --- |
| `readLine(): Opt<Str>` | 2040 / 2048 ms | `std::getline` + one `Str` per line |
| `readLineInto(*line)` | 1156 / 1161 ms | one `memcpy` into a recycled `Str` |
| `readLineView(): Opt<StrView>` | 1087 / 1088 ms | nothing - a span into the buffer |

(The in-place row was measured before the view became a `StrView`: the same code
path the table above runs, which re-measured at 1093/1106 ms as the whole program.)

## Reproducing it

```bat
:: the data (127.7 MiB, 10M rows, 100 stations)
bun benchmarks\onebrc\onebrc.mjs gen 10000000 benchmarks\onebrc\data\measurements.txt --seed 42

:: the two implementations
bun build.js --release --root benchmarks\onebrc --out benchmarks\onebrc\onebrc.cpp --exe benchmarks\onebrc\onebrc.exe
benchmarks\onebrc\build_naive.bat

:: each reports its own time; compare the reports against the reference
benchmarks\onebrc\onebrc.exe benchmarks\onebrc\data\measurements.txt
benchmarks\onebrc\brc_naive.exe benchmarks\onebrc\data\measurements.txt
bun benchmarks\onebrc\onebrc.mjs check benchmarks\onebrc\data\measurements.txt
```

`bun tools\memrun.exe`-style peak-working-set numbers come from `tools/memrun.cpp`
(`cmd //c "tools\_probe.bat memrun /O2 /DNDEBUG"`, then `tools\memrun.exe <exe> <args>`).

## Notes on the comparison

- **Tenths, not doubles.** Both programs aggregate `Int` tenths of a degree. That
  is exact, it is the unit the report is in, and it avoids the fact that the
  language has no `format`: `println` would print a `Float64` with C++'s default
  six digits.
- **The mean's rounding rule.** `x.x5` is rounded half toward positive infinity,
  the rule the challenge's Java reference uses (`Math.round(mean * 10) / 10`),
  implemented exactly on integers. The C++ baseline prints with `printf("%.1f")`,
  which rounds the binary double half-to-even; on this data set the two agree, but
  `check` is the reference if they ever disagree.
- **CRLF vs LF.** `println` goes through `std::cout` in text mode, so on Windows
  the Simse report has CRLF line endings while `check` writes LF. The comparison
  strips `\r` first (`tr -d '\r'`). The C++ baseline switches stdout to binary mode
  so its report is directly comparable.
- **The view's lifetime.** A `StrView` from `readLineView()` is valid until the
  next read on that stream, because a refill moves the bytes. The 1BRC uses each
  view inside its loop iteration, which is the shape the rule asks for; a line that
  must outlive the next read is `view.toString()`.
- **Borrowing the dictionary.** `Dictionary` is a value type, so the aggregation
  takes a `*Dictionary` (a raw pointer) - `&counts` would box a *copy* and the
  aggregates would be written into the box instead of the local. This is the one
  place where the language's memory model has to be respected to get the obvious
  program: `*x` borrows, `&x` boxes a copy.
- **Station names.** The built-in list is 100 ASCII names; `--stations <csv>` takes
  the official `weather_stations.csv`. Sorting is byte order on both sides, so
  non-ASCII names would need the same collation everywhere.
