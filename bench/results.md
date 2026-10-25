# Results: Simse vs C++ over the Goose benchmark suite

One file, two machines: the same sixteen benchmarks run on Windows (Snapdragon
X2 arm64 laptop, cl.exe `/O2 /Ob3 /GL`) and on Linux (Core i3-N305, g++ 13.3.0
and clang++ 19.1.1, `-O2 -flto` - the `-flto` the analog of the Windows `/GL`).
Every number below is a **speed ratio against that run's idiomatic C++ row
(VARIANT 0) = 1.00**: bigger is faster - a 1.6 means 1.6x the speed of
idiomatic C++, 0.7 means 70% of it. "tuned C++" is the best of the suite's
hand-optimized expert variants of that benchmark. Sizes: medium; process wall
time, median of 3 interleaved runs; every row of a benchmark must print the
same checksum or the size is failed, never timed. Checksums verified on **all
sixteen benchmarks, on both machines**.

The two sections are not comparable to each other in absolute terms - different
CPU, OS and compilers - each is internally consistent. The Goose column is an
estimate, explained below it.

## Windows - Snapdragon X2, Windows 11, cl.exe /O2 /Ob3 /GL (LTCG)

From `bun bench/run.js` (the Windows harness), medium size (October 25, 2026 -
after the u64 values moved onto `unsafe.UInt64` (raw `uint64_t` C++) and after
the span hoist, which binds a storage-stable index loop's container buffer once
through a span instead of re-deriving it per access; see "Why the numbers are
what they are").

| benchmark | tuned C++ | Simse | Goose (est.) |
| --- | ---: | ---: | ---: |
| sum | 1.20 | 1.31 | 1.60 |
| push | 1.04 | 1.24 | 3.13 |
| strlist | - | 1.00 | 3.52 |
| records | 1.35 | 1.00 | 2.77 |
| tree | 2.03 | 1.81 | 4.41 |
| interp | 1.36 | 1.54 | 5.51 |
| graph | 5.57 | 0.81 | 4.96 |
| words | 2.45 | 1.05 | 2.20 |
| particles | 0.96 | 1.11 | 1.20 |
| sexp | 1.57 | 1.13 | 3.72 |
| lru | 4.88 | 3.88 | 3.36 |
| scene | 1.74 | 1.92 | 5.78 |
| calc | 3.79 | 3.15 | 4.06 |
| bintrees | 4.36 | 4.05 | 15.58 |
| respond | 1.66 | 0.69 | 2.10 |
| blur | 2.17 | 0.95 | 3.59 |
| **geometric mean** | **2.05** | **1.43** | **3.51** |

(The tuned-C++ geomean is over the fifteen benchmarks with a tuned row; the
port runs `strlist`'s idiomatic C++ row only.)

## Linux - Core i3-N305, g++ 13.3.0 / clang++ 19.1.1, -O2 -DNDEBUG -flto

From `bun bench/run-linux.js --sizes small,medium --reps 3` (October 25, 2026).
Every row is built by both compilers; the Simse program transpiles once and the
two compiler builds emit byte-identical C++, so the two Simse columns differ
only in the backend. All columns are normalized on the **idiomatic g++ row** -
so the clang++ columns also carry the clang-vs-g++ backend ratio (this is
visible in itself: tuned C++ 3.16 under clang++ vs 1.12 under g++ on `blur` is
clang auto-vectorizing the byte loops, not a source difference). Measured after
the u64 move onto `unsafe.UInt64` and after the span hoist, so these Simse
columns carry neither the unsigned-modulo emulation nor the per-access buffer
derivation.

| benchmark | tuned C++ g++ | tuned C++ clang++ | Simse g++ | Simse clang++ | Goose (est.) |
| --- | ---: | ---: | ---: | ---: | ---: |
| sum | 1.50 | 1.52 | 0.97 | 1.19 | 1.60 |
| push | 1.50 | 1.46 | 1.01 | 0.95 | 3.13 |
| strlist | - | - | 0.91 | 0.88 | 3.52 |
| records | 1.67 | 1.64 | 1.02 | 0.94 | 2.77 |
| tree | 2.11 | 2.08 | 1.56 | 1.66 | 4.41 |
| interp | 1.54 | 1.39 | 1.26 | 1.34 | 5.51 |
| graph | 2.87 | 2.69 | 0.53 | 0.47 | 4.96 |
| words | 2.18 | 2.33 | 1.12 | 1.07 | 2.20 |
| particles | 0.87 | 1.02 | 0.92 | 1.00 | 1.20 |
| sexp | 1.81 | 2.14 | 1.84 | 1.86 | 3.72 |
| lru | 4.90 | 5.47 | 4.16 | 4.55 | 3.36 |
| scene | 1.73 | 2.41 | 2.38 | 2.31 | 5.78 |
| calc | 2.90 | 3.04 | 2.24 | 2.66 | 4.06 |
| bintrees | 5.41 | 4.60 | 4.19 | 3.71 | 15.58 |
| respond | 1.40 | 1.54 | 0.81 | 0.58 | 2.10 |
| blur | 1.12 | 3.16 | 0.75 | 0.88 | 3.59 |
| **geometric mean** | **1.96** | **2.19** | **1.33** | **1.34** | **3.51** |

## The Goose column (estimated)

The suite comes from the [Goose](https://github.com/aardappel/goose) language,
and its own `bench/results.md` (October 2026 run: Ryzen 9 9950X3D, Windows 11,
MSVC 19.51 / clang-cl 22.1.3, `/O2`) scores Goose against these same C++
reference rows, benchmark by benchmark, in exactly this unit - speed versus
idiomatic C++. The "Goose (est.)" column is his published "vs idiomatic"
factor (the MSVC column), chained onto our tables through the shared C++ row:
a ratio of ratios, both sides measured against the same baseline, so the
columns line up side by side.

It is an estimate, not a measurement on our machines, for two reasons: his
factors are at the suite's **large** size where ours are medium (allocation
costs loom larger at large - on his own tables Goose's `bintrees` edge is 6.7x
at medium but 15.6x at large), and his machine and toolchains differ from both
of ours. Read it as where Simse would sit in Goose's table, to first order:
Goose is estimated faster than Simse on fifteen of sixteen benchmarks, with
`lru` the exception - Simse ahead of the estimate on Windows (3.88 vs 3.36)
and on Linux g++ (4.16 vs 3.36).

## Why the numbers are what they are

- **Simse's wins are allocation wins, and they are structural.** Wherever the
  idiomatic C++ row allocates per node - `unique_ptr` trees (`tree`, `interp`,
  `scene`), owning sexp nodes, `list`+`unordered_map` (`lru`), `new`/`delete`
  (`bintrees`), exception-throwing parsers (`calc`) - Simse instead compiles
  its value types into pools: a `List` is a small-vector (inline buffer, then
  geometric heap growth, `memcpy` relocation) and the nodes inside it are
  plain structs linked by `Int32` indices, so "allocate a node" is a bump in
  one growing array and teardown is one free per pool. Indexing is unchecked.
  That is the same shape the suite's expert C++ rows hand-write, which is why
  Simse lands in the tuned-C++ band on exactly those rows (`tree` 1.81 vs
  tuned 2.03 on Windows; `interp`, `scene`, `bintrees`, `lru` all within ~30%
  of tuned on both machines) while both crush the idiomatic rows by 1.4-5x.
  Hash tables tell the same story: Simse's `Dictionary` is open addressing
  with the cached hash stored beside the key (one cache line per probe,
  growth re-masks instead of re-hashing) against `unordered_map`'s chained
  per-node allocation - hence `lru` 3.9-4.6x and `words` at parity-to-ahead
  of idiomatic while both expert rows go further.
- **The generator tax is paid off; the flat-scan rows followed.** `sum`, `push`
  and `strlist` spend most of their wall time *generating* the input: one
  xorshift64 plus one u64 modulo per element - per *character* in `strlist`.
  Those values travel as `unsafe.UInt64`, whose `uMod`/`uDiv`/`shr` are raw
  `uint64_t` C++ (modules/unsafe/_res.md, behind `@SmGen("res", "u64", ...)`
  declarations) - the same single hardware `%` the C++ rows get. The port's
  first pass emulated the unsigned modulo over a signed `Int64` bit pattern,
  and every negative pattern (about half) took a four-modulo slow path; that
  tax is gone, and the generator rows moved with it (`push` 0.88 -> 1.15,
  `records` 0.87 -> 1.00, `scene` 1.43 -> 1.71 against the idiomatic row).
  The span hoist then took the scan half the same direction: a
  storage-stable index loop binds its container's buffer once through a span
  (`spanOf(c)[i]`, the emitter spelling the index through the pointer), one
  load where every access used to re-derive the buffer through the
  inline-or-heap branch - `sum` 0.89 -> 1.31, `push` 1.15 -> 1.24, `blur`
  0.81 -> 0.95, `lru` 3.12 -> 3.88, `scene` 1.71 -> 1.92. `strlist` is the
  proof allocation was never the problem either way: every word fits Simse's
  24-byte inline `Str` (libstdc++'s small-string buffer is 15), so the Simse
  row heap-allocates *less* than the idiomatic C++ row it now sits beside.
  What is left is loop shape: `for` loops compile to per-element iterator
  state machines (`advance()` on a yieldable struct) that the auto-vectorizers
  do not flatten, where C++'s range-`for` over a flat array does vectorize -
  and the short rows (`sum`, `respond`) ride the process-startup floor, where
  a few milliseconds of scheduling moves the ratio by more than the code does.
- **`blur` is the backend, made visible.** The kernel is a flat byte loop;
  clang++ compiles the C++ rows 3x faster than g++ does (49 vs 139 ms at
  medium) purely by auto-vectorizing them. The Simse row's masked byte loads
  (`Char` is a signed `Int8`, every byte read is `& 0xFF`) and loop shape do
  not vectorize under either backend, so Simse sits at 0.75-0.95 while the gap
  between the tuned columns (1.12 vs 3.16) is entirely the vectorizer.
- **Windows and Linux tell the same story at different magnitudes.** The
  allocation wins and generator shape reproduce across machines; what moves
  is the flat-loop tail. MSVC with `/Ob3 /GL` goes ahead on `sum` (1.31 with
  the span hoist) where gcc sits at 0.97 (clang++ reaches 1.19 - its backend
  closes the gap the source leaves), and strlist improves under LTO on Linux
  (2.19x without it, 1.4-1.5x with it). `-flto` was added for comparability
  with the Windows `/GL` and mostly aligns flags rather than unlocking
  inlining - each row is already a single translation unit.
- **And Goose is faster still, on the suite it was built for.** These sixteen
  benchmarks are Goose's own, chosen by its author to exercise what Goose
  optimizes for - value-mode enums that pay only the payload used, grow-only
  pools, `checkfree` traversal, inline strings - and the Goose implementation
  of each row is tuned for its compiler's passes. Simse is a general-purpose
  transpiler that has not been tuned for this suite at all (its rows exist to
  be honest ports of the C++ shapes, and its remaining losses above are
  exactly the untuned part: loop shape and the flat-byte backend gap).
  Against that, Simse takes `lru` past Goose's estimate on both machines
  (3.88 vs 3.36 on Windows, 4.16 vs 3.36 on Linux g++) and lands at a
  1.3-1.4x geometric-mean speedup over idiomatic C++ on stock shape-for-shape
  code (1.43x on Windows, 1.33-1.34x on Linux after the span hoist) - roughly
  two-thirds of the
  tuned-C++ band (1.9-2.2x), with Goose's 3.5x the ceiling of a language
  working its own home field.

## Reproducing

```sh
# Windows: bun bench/run.js          # rewrites this file's Windows table (redo the merge after)
# Linux, from the simse checkout (../simse_bootstrap and ../simse-linux-gcc siblings):
g++        -std=c++20 -O2 -DNDEBUG -flto -I. -I../simse-linux-gcc/shim ../simse_bootstrap/builds/simse_bootstrap.cpp -o simse-g++
clang++-19 -std=c++20 -O2 -DNDEBUG -flto -I. -I../simse-linux-gcc/shim ../simse_bootstrap/builds/simse_bootstrap.cpp -o simse-clang++
bun bench/run-linux.js --sizes small,medium --reps 3    # raw per-size tables via --out
```
