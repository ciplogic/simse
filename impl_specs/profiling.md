# The instrumented profiler (`--profile`)

A flag on the transpiler that makes the *emitted program* measure itself: every emitted body
starts with an RAII timer, and the table lands in a CSV file when the program exits. A sampling
profile says where the *samples* are, not where the *calls* are; this one counts calls, and
its totals are inclusive, so a caller and its callees can be compared directly.

## What is emitted

With `--profile` (and **nothing at all** without it):

1. **the prologue** gains the runtime - `<cstdio>`, `simse_profiling::FunctionData`,
   `simse_profiling::ProfileScope`, `simse_profiling::ProfileApp`, the one `profileApp`, and the
   static `profileReport` whose destructor writes the table;
2. **every emitted body** gains one first statement,
   `auto __smProfile = profileApp.measure(<index>);`, where `<index>` is a dense `Int` constant;
3. **the names** are one table of constants, `simse_profiling::kMethodNames[]`, written with the
   bodies - index `k` is the constant that body `k` measures with, and the name is the body's
   *package-qualified* one (`ns1_emitFunction` is written `codegen::emitFunction`);
4. **`main` needs nothing**: the report is a static's destructor, so it runs once the program
   leaves `main` - whichever `return` it took, and also when it falls off the end.

```cpp
Int ns1_bump(ns1_Counter* self) {
    auto __smProfile = profileApp.measure(12);
    ...
}
```

A measurement is one array index and two clock reads. The constructor increments
`functions[index].calls`, the destructor adds `simse_nowMicros() - start_` (or
`simse_nowNanos()` - see *Units*) to `functions[index].total`; `measure` grows the
`List<FunctionData>` to the index the first time it sees it. There is no dictionary, no `Str`,
no per-entry allocation and no string compare - the runtime holds one `List<FunctionData>`
indexed by the constant. The scope is destroyed when the body leaves through any `return`, so a
total is that body's *whole* run - nested calls included - and the row carries its call count
beside it.

Every total and every count is `Int64` (`FunctionData.total`, `FunctionData.calls`,
`ProfileScope::start_`, and both clocks). A nanosecond count is ~1000x a microsecond one, so the
wider unit has to be `Int64` to stay meaningful - it would overflow an `Int32` in about two
seconds - and `total`/`calls`/`start_` are all `Int64` for exactly that reason, in both units.

## The table

The report is **CSV**, biggest total first, with one header line:

```csv
name,total_us,calls
main,6013955,1
codegen::emitProgram,5327680,1
linear::linFinishForEmission,2415083,901
codegen::emitBodyAt,1998447,896
optimizations::foldExprsUnder,1392667,1867455
```

A row with `calls == 0` (a measured body the run never entered) is left out. The `calls` column
is exact; `total_us` is wall time and machine-dependent. The name column is the body's symbol
with the compiler's `nsN_` package prefix spelled out (`Emitter.prettySymbol`: `ns1_` is
`codegen::`), so a row names a package and a function a reader can find; `rtl` and `main` are
unprefixed and unchanged.

## Where it goes

The default is the file **`simse_profile.txt`** in the working directory; `--profile-file <path>`
overrides it, and `--profile-file -` (an empty path too) keeps it on **stderr**. A path that
cannot be opened falls back to stderr.

The path is baked into the program at transpile time (the constant
`simse_profiling::kProfileFile`), not read from the program's own arguments: the profiler is a
build mode, and `--profile-file` is a flag of the *transpiler*.

## Where it is written

`cppsrc/profiling/Profiling.kt` owns the flag, the path and the text. The hooks are:

| place | what it does |
| --- | --- |
| `preludeText` / `emitProfileText` | the runtime, after the three standard includes |
| `Emitter.profIndexOf` | hands a measured body its dense `Int` constant, once |
| `Emitter.emitProfileNames` | writes `kMethodNames[]`, with the bodies |
| `emitBodyAt` (the IL backend) | the `measure(<index>)` line, before the body's own storage |
| `emitClosureClass` | the same for a lambda's `operator()`, indexed like any body |

The timer is the body's **first** statement on purpose: nothing precedes it, so no `goto` can
cross into its scope (the rule `ilJumpCrossing` exists for), and the frame's hoisted
declarations follow it. The name table is written once every body is written - it is only then
that the last index exists (`Emitter.emitProfileNames`, after `emitFunctions(false)`).

The clock is the RTL's `simse_nowMicros` or `simse_nowNanos` (`timeops`, `cppsrc/rtl/_res.md`;
the prelude surfaces `nowMicros()` / `nowNanos()`), beside `simse_nowMillis` - all monotonic, and
all `Int64`.

## Units

`--profile-nanos` switches the emitted timer to `simse_nowNanos()` and the CSV column to
`total_ns`; the default is microseconds and `total_us`. It is a display choice, not a range one:
the totals and the counts are `Int64` in both units, so nothing narrows or overflows by switching
(a nanosecond total is ~1000x the microsecond one, and an `Int32` would wrap it in ~2 s). The
resolution is the platform's `steady_clock` - on Windows typically ~100 ns, finer than the
microsecond reading needs.

## Enabling it

```sh
bun build.js --release --profile                          # a profiled compiler: simse.exe
bun build.js --release --profile --profile-file p.csv     # ... into a named file
bun build.js --release --profile --profile-nanos          # ... in nanoseconds
./simse_transpile.exe --root <dir> -o out.cpp --profile [--profile-file <path>] [--profile-nanos]
```

`bun build.js --profile` passes the flags (and a `--profile-file`) through to the transpile step,
so the compiler's own source set is transpiled with the runtime and the timers. The published
bootstrap (`cppsrc/simse_bootstrap.cpp`) is **not** profiled, so a profiled compiler is a local
artifact, never published.

## The proof file

`cppsrc/simse_profile.txt` sits beside the bootstrap: it is the CSV a release, profiled compiler
wrote about its own run of `--root cppsrc`, in the default microseconds. Its `calls` column is
exact (the compiler's emitted bodies and how often each ran); its `total_us` column is one machine
on one day. Reproduce it with:

```sh
bun build.js --release --profile --exe build/digits/simse_prof.exe --out build/digits/prof_compiler.cpp
./build/digits/simse_prof.exe --root cppsrc -o build/digits/prof_self_out.cpp
cp simse_profile.txt cppsrc/simse_profile.txt
```

## How to read a row

- **An entry is now ~two clock reads.** The dictionary lookup that used to make an entry cost
  ~40 ns is gone; `xmlKind` (a one-field compare) still measures tens of nanoseconds a call, so
  any row at that scale is a **call count**, not a time. The instrumented run is several times
  the clean one, and its `main` is not the clean `main`.
- **Nothing is inlined.** The flag gives every body a real function, so the small helpers the
  optimizer would fold away are measured as calls. That is why a fix can be worth a lot in the
  instrumented run and little in the optimized one: a *time* is trustworthy for the big rows, a
  *share* of a small hot helper is not. Use the table for who-calls-whom and how-often, and for
  the big rows' totals.

## What it is not

- **Not a sampling profiler.** It measures every emitted body exactly, and is blind to
  everything that is not one: the scanner's per-character work before a body is entered, the
  `main` prologue, static initialization, and a state machine's *factory* (which has no IL
  body).
- **Not free.** A measurement costs two clock calls and one array index per *body
  entry* - `xmlKind` at millions of calls pays it millions of times - so a `--profile` build is
  slower than a clean one by construction. Read *shares* of the big rows, and *call counts*
  everywhere.
- **Not on by default, and not a cost when off**: with the flag off the emitter returns the
  empty string at every hook, so the emitted file is byte-identical to what it was before the
  flag existed (the goldens say so).

## Status

Implemented: the flag (`Request.profile`, `--profile` in both drivers), the CSV report,
`--profile-file` (default `simse_profile.txt`, `-` for stderr) and `--profile-nanos`, the emitted
runtime, the dense `Int` method table with package-qualified `kMethodNames[]`, `simse_nowMicros` /
`simse_nowNanos`, and `bun build.js --profile` / `--profile-file` / `--profile-nanos`.

Verified: a profiled `stress/strings` program writes a correct CSV with `strings::partStrings`
names (and the `-` file prints it to stderr, and `--profile-nanos` writes `total_ns` with
`simse_nowNanos()`); a profiled release compiler over `--root cppsrc` writes the checked-in
`cppsrc/simse_profile.txt` (769 measured bodies, `codegen::emitProgram` 5.3 s inclusive); with the
flag off `bun tools/stress.js` is **41/41** and `bun tools/bootstrap.js`'s fixed point is byte for
byte.
