# bench/ - Simse against idiomatic C++

Sixteen real algorithms - array scans, trees, parsers, caches, hash tables,
blur - each shipped three ways: the C++ reference rows verbatim from
[aardappel/goose](https://github.com/aardappel/goose)'s `bench/` suite
(`cpp/`), one Simse program per benchmark ported shape-for-shape from the C++
(`simse/`), and one harness (`run.js`, `run-linux.js`) that builds every row
with the same compiler and flags, checks that every implementation of a
benchmark prints the same checksum, and times whole processes.

The C++ references come in two tiers, selected with `-DVARIANT`:

- **idiomatic** (VARIANT 0) - `unique_ptr`, `std::list`, `unordered_map`,
  exceptions: the code a C++ programmer writes first. This tier is the
  baseline every ratio is taken against.
- **expert** - arenas, indices, open addressing: the same algorithms
  hand-tuned by someone who knows exactly where the allocations are.

## What the suite says about Simse

Every Simse row is a direct port of the C++ reference - same algorithms, same
data-structure shapes - so a ratio measures the code the compiler generates,
not algorithmic differences. That is the comparison the suite is built for:
**Simse against idiomatic C++**, and the numbers in [results.md](results.md)
(the Windows and Linux tables, both normalized on the idiomatic row) put the
generated code at or above parity with idiomatic C++ on most of the suite,
with the geometric mean around 1.2-1.4x on both machines. The rows where
Simse pulls ahead are the allocation-heavy ones - trees, caches, parsers -
where Simse's value types compile into pools and its `Dictionary` into open
addressing, so the per-node allocation traffic the idiomatic rows pay simply
is not there. That band is also where the hand-tuned expert C++ rows live:
against *those*, the honest like-for-like since Simse containers cannot hold
raw pointers, Simse lands close on the same rows rather than ahead of them.

It is worth being plain about what this suite is not: the best case for
Simse. It comes from another language and was selected by that language's
author to showcase it - value-mode enums, grow-only pools, check-free
traversal - and its rows are tuned for that compiler's passes. The Simse rows
here are untuned ports of the C++ shapes, written to verify correctness
first. The flat scan rows (`sum`, `strlist`, `blur`) and `graph` are where
that shows; they are the honest remainder, not a hidden weakness.

## The suite as a compiler test

The suite doubles as a stress corpus, and it earned that role: the first full
pass crashed the compiler. The `graph` benchmark's
`pool.append(Edge(w, pool[u].next))` exposed a codegen use-after-free (an
element address reused across a call that grows the list), and reconciling
checksums surfaced three unsigned-arithmetic bugs in the port (logical vs
arithmetic shift, a seed shifted arithmetically, division in wrapping u64).
All four are fixed - the codegen fix is pinned by `stress/place-reuse`, the
arithmetic by the suite itself, now checksum-verified across **all sixteen
benchmarks on both Windows and Linux**. Running a suite written for another
language against a young compiler and ending green is the result this
directory exists to record.

## Layout

- `cpp/` - the goose suite's C++ references, verbatim (`bench.h` is their
  shared scaffolding: the xorshift64 PRNG, the checksum emitters, the
  `BENCH_N`/`VARIANT` defines).
- `simse/<name>/src/main.kt` - one Simse program per benchmark. The size is
  baked in by rewriting the one line carrying `// BENCH_N` (the goose suite's
  own arrangement; Simse has no preprocessor).
- `simse/kit/kit.kt` - the suite's shared scaffolding. The generators and
  checksums are written against C++ `uint64_t`; the port carries them over
  `unsafe.UInt64` (modules/unsafe), whose operations compile to the same raw
  instructions the C++ rows get, plus two suite-specific helpers (`xsNext`,
  the xorshift64 of `bench.h`, and `byteAt`, a `Str` byte as 0..255).
- `run.js` - the Windows harness; `results.md` - the measured ratios
  (hand-merged from a `run.js` run and a `run-linux.js` run; `run.js`
  rewrites only its own table, so a fresh Windows run needs the merge
  redone).
- `run-linux.js` - the Linux twin: every row built twice, with g++ and
  clang++ (Simse transpiles once - the two compiler builds emit
  byte-identical C++ - and both compile it), under `-flto` the analog of the
  Windows `/GL`; its raw per-size output is regenerated with `--out` and is
  not kept in the repo.

## Running

```sh
bun bench/run.js                                  # medium only, 3 reps, ./simse.exe
bun bench/run.js --sizes small,medium,large       # the goose suite's full schedule
bun bench/run.js --filter tree --filter blur      # a subset
bun bench/run.js --simse some/other/simse.exe     # another compiler build
bun bench/run.js --reps 5
```

Each benchmark at each size builds the C++ rows (`-DBENCH_N=n -DVARIANT=v`)
and the Simse row (transpile with `--root <src> --root bench/simse/kit
--module modules/unsafe`, compile with the release flags), then times the
exes with the runs interleaved round-robin - on a laptop, turbo/thermal
history lands evenly on every row instead of piling boost on the first and
heat on the last. A row whose output disagrees with the C++ baseline is
failed, never timed.

Note for this machine: Smart App Control intermittently blocks fresh unsigned
executables, so every child goes through `cmd /d /c` with retries, and a row
can still fail with a bare nonzero exit when a block slips through - rerun the
filter before believing it.

## Porting decisions

- **No raw pointers in containers.** Simse value types cannot hold `*T`
  fields, so every goose design built on stable references into growing pools
  becomes an arena of nodes linked by `Int32` indices - exactly the shape the
  suite's C++ *expert* rows write by hand (`tree` VARIANT 2, `graph`
  VARIANT 2, `lru` VARIANT 1, `bintrees` VARIANT 1, ...). Those rows are the
  honest like-for-like; the idiomatic owning-pointer rows are the headline.
- **`words`** is ported from `words_dictionary.goose` (the std-dictionary
  row): `Dictionary<Str, Int>`, whose output depends only on the word
  multiset, so checksums agree whatever the table internals.
- **`scene`** spells a 3x3 matrix as nine `Float32` fields - Simse data
  classes have no fixed-size array members. The transforms stay AoS like the
  goose node.
- **`calc`** keeps the per-input pool as a high-water arena (reset a length,
  keep the capacity) and threads an error code out of the recursion - the C++
  expert row's shape - against the idiomatic row's exceptions.
- **`blur`** is the flat-indexing shape over two `Str` byte buffers.
