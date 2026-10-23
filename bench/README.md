# bench/ - Simse against the Goose benchmark suite

A port of [aardappel/goose](https://github.com/aardappel/goose)'s `bench/`
suite: the same sixteen algorithms, the C++ references verbatim, Simse
implementations beside them, one harness that builds both with the same
`cl.exe` and flags, checks that every implementation of a benchmark prints the
same checksum, and times the processes.

Per scope agreed for this pass: multi-core workloads were dropped (the goose
suite has none to drop), and work went into benchmarks that translate directly
or with trivial fixes - see "State of the port" for what that left.

## Layout

- `cpp/` - the goose suite's C++ references, verbatim (`bench.h` is their
  shared scaffolding: the xorshift64 PRNG, the checksum emitters, the
  `BENCH_N`/`VARIANT` defines). A `-DVARIANT` selects the implementation tier
  of each file: idiomatic (VARIANT 0: `unique_ptr`, `std::list`, exceptions)
  or expert (arenas, indices, open addressing - the shapes Simse's rows take).
- `simse/<name>/src/main.kt` - one Simse program per benchmark. The size is
  baked in by rewriting the one line carrying `// BENCH_N` (the goose suite's
  own arrangement; Simse has no preprocessor).
- `simse/kit/kit.kt` - shared u64-over-`Int64` support (below).
- `run.js` - the harness; `results.md` - the current ratios (the idiomatic C++ row is
  the 1.00 baseline; small sizes are startup-floor noise and run only with --sizes).
- `run-linux.js` - the Linux twin: every row built twice, with g++ and clang++
  (Simse transpiles once - the two compiler builds emit byte-identical C++ - and
  both compile it); `results-linux-i3.md` - its numbers (i3-N305).

## Running

```sh
bun bench/run.js                                  # medium only, 3 reps, ./simse.exe
bun bench/run.js --sizes small,medium,large       # the goose suite's full schedule
bun bench/run.js --filter tree --filter blur      # a subset
bun bench/run.js --simse some/other/simse.exe     # another compiler build
bun bench/run.js --reps 5
```

Each benchmark at each size builds the C++ rows (`-DBENCH_N=n -DVARIANT=v`)
and the Simse row (transpile with `--root <src> --root bench/simse/kit`,
compile with the release flags), then times the exes with the runs interleaved
round-robin - on a laptop, turbo/thermal history lands evenly on every row
instead of piling boost on the first and heat on the last. A row whose output
disagrees with the C++ baseline is failed, never timed.

Note for this machine: Smart App Control intermittently blocks fresh unsigned
executables, so every child goes through `cmd /d /c` with retries, and a row
can still fail with a bare nonzero exit when a block slips through - rerun the
filter before believing it.

## The u64 question

The suite's generators and checksums are written against C++ `uint64_t`; all
of Simse's scalars are signed. `simse/kit/kit.kt` carries the bits in an
`Int64` and recovers what the signed surface lacks:

- `xsNext` - the xorshift64 of `bench.h`; the left shifts are the modulo-2^64
  ones C++20 defines, the logical right shift is masked out of the arithmetic
  one (`uShr`);
- `xsMod` - the unsigned modulo. A negative `Int64` names the pattern
  x + 2^64, and the wrap is invisible only modulo powers of two, so the
  general case adds 2^64 mod k, built from 2^32 mod k;
- addition, subtraction and multiplication need no helper: the generated C++
  computes them in two's complement, which is the u64 wrap bit for bit;
- `uDiv`/`uGe` - the unsigned division `calc`'s evaluator needs over the bit
  patterns (a signed `/` answers a different quotient for negative patterns);
  both non-negative operands take the machine's own division;
- `uToString` - the `%llu` spelling, by binary long division;
- `byteAt` - a `Str` byte as 0..255: `Char` is a *signed* `Int8`, so every
  byte read the C++ rows get from `uint8_t` needs the mask.

(`modules/unsafe` has the `UInt64` wrapper class this work predates; the kit
predates its current shape and stays over plain `Int64` for zero-overhead hot
loops.)

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

## State of the port

Checksums verified against C++ at small and medium sizes: **all sixteen
benchmarks**. Numbers are in [results.md](results.md), one table per
benchmark as the harness writes it from a run.

Four bugs were found and fixed while reconciling checksums:

- **Logical shift before unsigned mod.** `xsMod(r >> s, k)` reads the
  arithmetic shift, where C++ shifts a `uint64_t` logically; the sign
  extension changes the value by `2^(64-s) * (2^s - 1)`, which is a multiple
  of `k` only when `k` is a power of two (why the power-of-two benchmarks
  passed while `records`, `sexp`, `graph`, `scene` drifted). Every shifted
  modulus now goes `xsMod(uShr(r, s), k)`.
- **A seed shifted arithmetically.** `calc` feeds `gen` with `seed >> 8` and
  `respond` builds its request from `r >> 8`/`r >> 16` - arithmetic shifts of
  an `Int64`, where C++ shifts its `uint64_t` logically, so every value with
  the top bit set generated different text (`respond` drifted only bytes:
  the lengths matched, the letters did not).
- **Division in wrapping u64.** `calc` evaluates with `/` over `uint64_t`;
  a signed `Int64` division answers a different quotient whenever an operand
  is negative. The kit gains `uDiv` (and `uGe`, the pattern order it needs)
  beside `uShr`/`xsMod`.
- **A place address reused across a call.** The `graph` benchmark crashed:
  the expression-reuse pass merged the two `&pool[u]` computations across an
  `append`, and the store went through the freed buffer. An element address
  no longer merges across a call (impl_specs/expr-reuse.md); the
  `place-reuse` case in the stress corpus pins it. `resize` on a static
  `Str` also does not land (a compiler gap; `calc` reassigns instead -
  `text = Str()`, `text = text.substr(...)` - at the cost of the reused
  capacity the C++ arena row keeps).
