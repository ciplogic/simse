# Results: Simse vs C++ over the Goose benchmark suite - Linux

Machine: Linux on Intel(R) Core(TM) i3-N305, 8 cores/8 threads. Same flags for every row
(`-std=c++20 -O2 -DNDEBUG`; the Simse-generated C++ adds `-I<repo>` for the runtime
headers). Compilers: g++ 13.3.0, clang++ 19.1.1 (1ubuntu1~24.04.2).
Process wall time, median of 3 runs, interleaved round-robin over all rows.
Sizes: small, medium. Every row of a benchmark must print the same checksum
or the size is failed, never timed. Small sizes sit near the process-startup floor -
read them for parity, not ratios. Unlike the Windows run there is no whole-program
flag here (no `-flto`, the analog of cl.exe's `/GL`).

## The bootstrap: compiling the compiler with both

`simse_bootstrap/builds/simse_bootstrap.cpp` (101,270 lines) compiled with each compiler,
same flags plus the `simse-linux-gcc/shim` includes that satisfy the amalgamation's
unreachable `http`/`minui` sections (unconditional `<windows.h>`/`<wininet.h>`/SDL
includes). Both produce a working compiler:

| compiler | wall time | peak RSS | binary |
| --- | --- | --- | --- |
| g++ 13.3.0 | 1:07 | 807 MB | runs (`--help`, transpile) |
| clang++ 19.1.1 | 1:02 | 465 MB | runs (`--help`, transpile) |

The two builds transpile byte-identical C++ (checked on `words`), so each benchmark
below transpiles once and the generated `out.cpp` is then compiled by *both* compilers:
the `simse g++` / `simse clang++` rows differ only in the C++ backend, not in the
compiler that generated them.

## sum

Build a flat i32 array, then scan it eight times.

| implementation | small (N=1000000) | medium (N=16000000) |
| --- | --- | --- |
| cpp0 g++ | 17 ms | 185 ms |
| cpp0 clang++ | 25 ms | 153 ms |
| cpp1 g++ | 23 ms | 157 ms |
| cpp1 clang++ | 24 ms | 126 ms |
| simse g++ | 28 ms | 265 ms |
| simse clang++ | 26 ms | 224 ms |

## push

Push N records while keeping a mark on every 64th.

| implementation | small (N=1000000) | medium (N=16000000) |
| --- | --- | --- |
| cpp0 g++ | 13 ms | 186 ms |
| cpp0 clang++ | 25 ms | 187 ms |
| cpp1 g++ | 23 ms | 126 ms |
| cpp1 clang++ | 24 ms | 128 ms |
| cpp2 g++ | 22 ms | 143 ms |
| cpp2 clang++ | 26 ms | 163 ms |
| simse g++ | 30 ms | 264 ms |
| simse clang++ | 29 ms | 264 ms |

## strlist

Split a text into N words, keep them, scan four times.

| implementation | small (N=200000) | medium (N=2000000) |
| --- | --- | --- |
| cpp0 g++ | 32 ms | 265 ms |
| cpp0 clang++ | 43 ms | 276 ms |
| simse g++ | 74 ms | 577 ms |
| simse clang++ | 70 ms | 639 ms |

## records

Build a log of N variant records, aggregate four times.

| implementation | small (N=500000) | medium (N=4000000) |
| --- | --- | --- |
| cpp0 g++ | 74 ms | 457 ms |
| cpp0 clang++ | 69 ms | 464 ms |
| cpp1 g++ | 66 ms | 463 ms |
| cpp1 clang++ | 67 ms | 440 ms |
| cpp2 g++ | 51 ms | 299 ms |
| cpp2 clang++ | 50 ms | 298 ms |
| simse g++ | 79 ms | 528 ms |
| simse clang++ | 78 ms | 544 ms |

## tree

Build a complete binary tree of the given depth, sum eight times.

| implementation | small (depth=16) | medium (depth=20) |
| --- | --- | --- |
| cpp0 g++ | 24 ms | 164 ms |
| cpp0 clang++ | 26 ms | 176 ms |
| cpp2 g++ | 17 ms | 85 ms |
| cpp2 clang++ | 19 ms | 85 ms |
| simse g++ | 21 ms | 108 ms |
| simse clang++ | 21 ms | 109 ms |

## interp

Build an expression tree of the given depth, evaluate eight times.

| implementation | small (depth=16) | medium (depth=20) |
| --- | --- | --- |
| cpp0 g++ | 16 ms | 42 ms |
| cpp0 clang++ | 17 ms | 46 ms |
| cpp2 g++ | 13 ms | 32 ms |
| cpp2 clang++ | 13 ms | 29 ms |
| simse g++ | 13 ms | 31 ms |
| simse clang++ | 13 ms | 34 ms |

## graph

Adjacency for V vertices, 8V edges, BFS from four sources.

| implementation | small (V=100000) | medium (V=500000) |
| --- | --- | --- |
| simse | segfault | segfault |

Checksum problems: small: exit 1 on rep 0: ; medium: exit 1 on rep 0: 

The Simse row dies with SIGSEGV at both sizes under both compilers (transpiler output
identical, so not a compiler-build difference). ASan on the generated C++ pins it: a
heap-use-after-free in the codegen for

```
pool.append(Edge(w, pool[u].next))
pool[u].next = pool.size() - 1
```

the generated C++ hoists `_sm_base2 = simse_addressOf(pool[u])` *before* the
`simse_list_append(pool, ...)` and writes `_sm_base2->next = ...` after it - once the
append grows past the capacity the old buffer is freed and the write lands in freed
memory. Same family as the static-`resize` gap in bench/README.md: element addresses do
not survive list growth. The C++ rows run fine; reference times for context:
small cpp idiomatic 100 ms (arena 180 ms), medium 830 ms (arena 1950 ms).

## words

Count word frequencies over a text of N words.

| implementation | small (N=500000) | medium (N=4000000) |
| --- | --- | --- |
| cpp0 g++ | 97 ms | 1831 ms |
| cpp0 clang++ | 99 ms | 1793 ms |
| cpp2 g++ | 69 ms | 799 ms |
| cpp2 clang++ | 67 ms | 831 ms |
| simse g++ | 123 ms | 1670 ms |
| simse clang++ | 125 ms | 1712 ms |

## particles

Integrate N particles for 200 steps of f32 math.

| implementation | small (N=100000) | medium (N=1000000) |
| --- | --- | --- |
| cpp0 g++ | 106 ms | 612 ms |
| cpp0 clang++ | 100 ms | 573 ms |
| cpp2 g++ | 118 ms | 690 ms |
| cpp2 clang++ | 99 ms | 575 ms |
| simse g++ | 116 ms | 638 ms |
| simse clang++ | 107 ms | 601 ms |

## sexp

Generate N s-expression forms, parse, walk four times.

| implementation | small (N=20000) | medium (N=100000) |
| --- | --- | --- |
| cpp0 g++ | 367 ms | 1780 ms |
| cpp0 clang++ | 351 ms | 1702 ms |
| cpp1 g++ | 218 ms | 1032 ms |
| cpp1 clang++ | 188 ms | 879 ms |
| simse g++ | 233 ms | 1088 ms |
| simse clang++ | 232 ms | 1080 ms |

## lru

An LRU cache of capacity N/8 under N skewed lookups.

| implementation | small (N=2000000) | medium (N=8000000) |
| --- | --- | --- |
| cpp0 g++ | 749 ms | 3913 ms |
| cpp0 clang++ | 644 ms | 3777 ms |
| cpp1 g++ | 130 ms | 877 ms |
| cpp1 clang++ | 124 ms | 901 ms |
| simse g++ | 162 ms | 1067 ms |
| simse clang++ | 152 ms | 971 ms |

## scene

A scene graph of the given depth, animated for 16 frames.

| implementation | small (depth=12) | medium (depth=14) |
| --- | --- | --- |
| cpp0 g++ | 59 ms | 454 ms |
| cpp0 clang++ | 60 ms | 366 ms |
| cpp1 g++ | 57 ms | 259 ms |
| cpp1 clang++ | 44 ms | 179 ms |
| simse g++ | 52 ms | 201 ms |
| simse clang++ | 53 ms | 202 ms |

## calc

Parse and evaluate N arithmetic expressions, a quarter malformed.

| implementation | small (N=100000) | medium (N=400000) |
| --- | --- | --- |
| cpp0 g++ | 178 ms | 701 ms |
| cpp0 clang++ | 192 ms | 734 ms |
| cpp1 g++ | 73 ms | 237 ms |
| cpp1 clang++ | 59 ms | 227 ms |
| simse g++ | failed | failed |
| simse clang++ | failed | failed |

Checksum problems: small: simse clang++ line 1: expected 4149826, got 4153873; medium: simse clang++ line 1: expected 16589727, got 16599342

The Simse rows disagree with C++ from the *first* checksum line, identically under both
compilers. `calc` carries the static-`resize` workaround (bench/README.md); what remains
wrong is not diagnosed - every output line drifts, so the per-input evaluation diverges
somewhere before the aggregation.

## bintrees

The Benchmarks Game binary-trees up to the given depth.

| implementation | small (depth=15) | medium (depth=17) |
| --- | --- | --- |
| cpp0 g++ | 136 ms | 657 ms |
| cpp0 clang++ | 152 ms | 728 ms |
| cpp1 g++ | 39 ms | 122 ms |
| cpp1 clang++ | 42 ms | 147 ms |
| simse g++ | 43 ms | 163 ms |
| simse clang++ | 48 ms | 202 ms |

## respond

Build and render N JSON responses.

| implementation | small (N=100000) | medium (N=400000) |
| --- | --- | --- |
| cpp0 g++ | 114 ms | 398 ms |
| cpp0 clang++ | 99 ms | 360 ms |
| cpp1 g++ | 81 ms | 282 ms |
| cpp1 clang++ | 78 ms | 266 ms |
| simse g++ | failed | failed |
| simse clang++ | failed | failed |

Checksum problems: small: simse clang++ line 2: expected 22983649, got 22983584; medium: simse clang++ line 2: expected 91800236, got 91800917

The request count (line 1) agrees; the byte total drifts by exactly the *same* 65 bytes
in 100k responses the Windows run saw (22983584 vs 22983649), with the FNV checksum
following - under both compilers on a different platform. That confirms the Windows
suspicion: a deterministic difference in the port's/transpiler's JSON rendering, not a
compiler, ISA or OS issue.

## blur

16 passes of a 3x3 blur over a WxW 8-bit image.

| implementation | small (W=1024) | medium (W=2048) |
| --- | --- | --- |
| cpp0 g++ | 41 ms | 156 ms |
| cpp0 clang++ | 19 ms | 56 ms |
| cpp1 g++ | 44 ms | 133 ms |
| cpp1 clang++ | 26 ms | 56 ms |
| simse g++ | 62 ms | 205 ms |
| simse clang++ | 52 ms | 170 ms |

## Ratios (median over sizes with both rows timed)

| benchmark | simse g++ / cpp idiomatic g++ | simse clang++ / idiomatic clang++ | cpp clang++ / g++ | simse clang++ / g++ |
| --- | --- | --- | --- | --- |
| sum | 1.45x | 1.41x | 0.88x | 0.85x |
| push | 1.48x | 1.38x | 1.07x | 0.99x |
| strlist | 2.19x | 2.23x | 1.07x | 1.09x |
| records | 1.14x | 1.17x | 1.00x | 1.03x |
| tree | 0.69x | 0.64x | 1.08x | 1.00x |
| interp | 0.76x | 0.74x | 1.11x | 1.07x |
| words | 0.93x | 0.97x | 0.98x | 1.02x |
| particles | 1.05x | 1.05x | 0.94x | 0.94x |
| sexp | 0.62x | 0.64x | 0.96x | 0.99x |
| lru | 0.26x | 0.25x | 0.95x | 0.91x |
| scene | 0.49x | 0.60x | 0.83x | 1.01x |
| bintrees | 0.26x | 0.28x | 1.11x | 1.21x |
| blur | 1.36x | 2.99x | 0.38x | 0.83x |

## Reading

- **Verified:** 13 of 16 benchmarks checksum-verified across all six rows (both C++
  variants × two compilers + Simse under both compilers). `graph` fails at run time and
  `calc`/`respond` fail their checksums - all three on *both* compilers identically, so
  they are port/compiler gaps, not backend differences. The Windows run's unverified rows
  (`records`, `sexp`, `calc`, `respond`, `graph`, `blur`, `scene`) now have verdicts:
  `records`, `sexp`, `scene`, `blur` verified; `graph` crashes on a codegen lifetime bug;
  `calc` and `respond` are genuinely wrong (respond with the same 65-byte drift Windows saw).
- **clang++ vs g++** on this suite: clang++ is the slightly faster backend on average,
  with the standout being flat byte loops it vectorizes and g++ does not - `blur` 0.38x
  on the C++ rows (56 vs 156 ms at medium). `sum`, `scene`, `particles` also favor
  clang++ (0.83-0.94x); `bintrees` idiomatic favors g++ (1.11x). On the Simse rows the
  spread is tighter (0.83-1.21x over the suite) - the generated C++ is plain enough that
  the compilers mostly agree.
- **Simse vs C++ is the Windows story again, on different silicon and backend:** big wins
  wherever the idiomatic C++ row allocates and tears down per node - `tree` 0.69x,
  `interp` 0.76x, `sexp` 0.62x, `scene` 0.49x, `lru` 0.26x, `bintrees` 0.26x - and
  roughly parity with the hand-written expert arena rows (tree 1.27x, interp 0.97x,
  sexp 1.05x, lru 1.22x, bintrees 1.34x of the arena row), which stays the honest
  like-for-like since Simse's containers cannot hold raw pointers. The losses stay in
  growth and string handling: `sum` 1.45x, `push` 1.48x, `strlist` 2.19x, `records`
  1.14x. `words` reaches parity here (0.93x medium) where Windows was 1.15x.
- **Comparability caveat:** these numbers are not comparable to [results.md](results.md)
  - different CPU class (Alder Lake-N E-cores vs an arm64 laptop), OS, and flag set
  (`-O2` without LTO vs `/O2 /Ob3 /GL`), different compilers. Read the two files
  internally, not against each other.

## Reproducing

```sh
# the compiler builds (from the simse checkout, ../simse_bootstrap and ../simse-linux-gcc siblings)
g++        -std=c++20 -O2 -DNDEBUG -I. -I../simse-linux-gcc/shim ../simse_bootstrap/builds/simse_bootstrap.cpp -o simse-g++
clang++-19 -std=c++20 -O2 -DNDEBUG -I. -I../simse-linux-gcc/shim ../simse_bootstrap/builds/simse_bootstrap.cpp -o simse-clang++
# the suite
bun bench/run-linux.js --sizes small,medium --reps 3
```


