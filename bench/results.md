# Results: Simse vs C++ over the Goose benchmark suite

Machine: Windows 11 arm64 laptop, same `cl.exe` and flags for both sides
(`/std:c++20 /EHsc /O2 /Ob3 /DNDEBUG /GL /MD`). Process wall time, median of 3
runs, interleaved round-robin so turbo/thermal history lands evenly on the
rows. Small sizes sit on the ~25-35 ms process-startup floor - read them for
parity, not for ratios; medium is where the work is.

Checksums: every row of a benchmark prints the same values or the row is
marked. `Simse/C++` divides the Simse row by the idiomatic C++ row
(VARIANT 0); the expert arena rows are the honest like-for-like for the
pointer-heavy benchmarks.

## Verified rows (checksums agree with C++)

| benchmark | size (param) | cpp idiomatic | cpp expert rows | Simse | Simse/C++ idiomatic |
| --- | --- | --- | --- | --- | --- |
| sum | small (N=1M) | 30 ms (reserve 27) | - | 33 ms | 1.10 |
| sum | medium (N=16M) | 144 ms (reserve 106) | - | 209 ms | 1.45 |
| push | small (N=1M) | 34 ms (res 29, deque 52) | - | 34 ms | 1.00 |
| push | medium (N=16M) | 186 ms (res 94, deque 483) | - | 179 ms | 0.96 |
| strlist | medium (N=2M) | 272 ms | - | 399 ms | 1.47 |
| tree | small (depth=16) | 35 ms | arena 27 | 27 ms | 0.77 |
| tree | medium (depth=20) | 243 ms | arena 97 | 105 ms | 0.43 |
| interp | small (depth=16) | 30 ms | tagged arena 29 | 29 ms | 0.97 |
| interp | medium (depth=20) | 53 ms | tagged arena 39 | 41 ms | 0.77 |
| words | small (N=500k) | 68 ms | open-address 71 | 93 ms | 1.37 |
| words | medium (N=4M) | 791 ms | open-address 643 | 910 ms | 1.15 |
| particles | small (N=100k) | 45 ms (per-comp 43) | - | 40 ms | 0.89 |
| particles | medium (N=1M) | 519 ms (per-comp 499) | - | 523 ms | 1.05 |
| lru | medium (N=8M) | 2458 ms | arena+open-addr 461 | 651 ms | 0.26 |
| bintrees | small (depth=15) | 210 ms | arena 53 | 65 ms | 0.31 |
| bintrees | medium (depth=17) | 744 ms | arena 150 | 223 ms | 0.30 |

## Rows not compared

- **records, sexp, calc, respond** - the Simse port runs and prints, but its
  checksum disagrees with C++ at both sizes; per the agreed scope these were
  not debugged further. C++ reference times for context: records 79/448 ms,
  sexp 384/1876 ms, calc 169/592 ms, respond 97 ms (small).
- **graph, blur** - the exes were policy-blocked at run time on this machine
  (Smart App Control denies fresh unsigned binaries intermittently); the
  harness retries but these sizes did not complete a clean interleaved pass.
- **scene** - build error in the generated C++: `0.0 - s` promotes to
  `Float64` and the braced initializer refuses the narrowing into `Float32`
  fields; one-line fix, not applied within this pass's scope.

## Reading

- Flat contiguous work is a wash: `push` is parity, `particles` within noise -
  the control cases behave like controls.
- Wherever the idiomatic C++ row allocates and tears down per node or per
  string, Simse's arena-and-index shape wins big: `tree` 2.3x, `bintrees` 3.3x,
  `lru` 3.8x over `std::list`/`unique_ptr` rows, and roughly parity (0.9-1.5x)
  with the hand-written expert arena rows - which is the honest comparison,
  since Simse's containers cannot hold raw pointers and the indices are
  mandatory, not chosen.
- The losses are in growth and hashing: `sum` 1.45x (List doubling growth, no
  `reserve`), `strlist` 1.47x and `words` 1.15x (per-entry `Str` copies in
  `Dictionary<Str, Int>`, `SmDictionary` lookups).
