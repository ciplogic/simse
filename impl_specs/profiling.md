# The instrumented profiler (`--profile`)

A flag on the transpiler that makes the *emitted program* measure itself: every emitted body
starts with an RAII timer, and the table lands on stderr when the program exits. A sampling
profile says where the *samples* are, not where the *calls* are; this one counts calls, and
its totals are inclusive, so a caller and its callees can be compared directly.

## What is emitted

With `--profile` (and **nothing at all** without it):

1. **the prologue** gains the runtime - `<cstdio>`, `simse_profiling::ProfileScope`,
   `simse_profiling::ProfileApp`, the one `profileApp`, and the static `profileReport`
   whose destructor prints the table;
2. **every emitted body** gains one first statement,
   `auto __smProfile = profileApp.measure("<symbol>");`
3. **`main` needs nothing**: the report is a static's destructor, so it runs once the
   program leaves `main` - whichever `return` it took, and also when it falls off the end.

```cpp
Int ns1_bump(ns1_Counter* self) {
    auto __smProfile = profileApp.measure("ns1_bump");
    ...
}
```

The scope is destroyed when the body leaves, through any `return`, so the total is that
body's *whole* run - nested calls included - and the row carries the call count beside it.
The report sorts by total, biggest first:

```text
profile (microseconds, inclusive, 401 method(s)):
         38317 us           1 calls  main
         22991 us           7 calls  ns3_driverParseFile
          5057 us           7 calls  ns6_parseRoot
```

## Where it is written

`cppsrc/profiling/Profiling.kt` owns the flag and the text. The hooks are one line each:

| place | what it does |
| --- | --- |
| `preludeText` / `prelude` | the runtime, after the three standard includes |
| `emitBodyAt` (the IL backend) | the `measure(...)` line, before the body's own storage |
| `emitClosureClass` | the same for a lambda's `operator()`, named `<closure>::operator()` |

The timer is the body's **first** statement on purpose: nothing precedes it, so no `goto`
can cross into its scope (the rule `ilJumpCrossing` exists for), and the frame's hoisted
declarations follow it.

The clock is the RTL's `simse_nowMicros` (the `timeops` section of `cppsrc/rtl/_res.md`; the
prelude surface `nowMicros()` in `cppsrc/rtl/rtl.kt`) - a monotonic microsecond clock, beside
`simse_nowMillis`.

## Enabling it

```sh
bun build.js --release --profile                    # a profiled compiler: simse.exe
./cmake-build-release/simse_transpile.exe --root <dir> -o out.cpp --profile
```

`bun build.js --profile` passes the flag through to the transpile step, so the compiler's own
source set is transpiled with the runtime and the timers. The published bootstrap
(`cppsrc/simse_bootstrap.cpp`) is **not** profiled, so a profiled compiler is a local artifact,
never published.

Read it for an inclusive total per emitted body plus the call count, which together separate
"called once, expensive" from "called a million times, cheap".

## The first reading

`bun build.js --release --profile` on the compiler, `--root cppsrc`, 526 emitted bodies.

- **It agrees with the sampling profile's shape.** `main` puts 88% in `emitProgram` ->
  `emitFunction` (1214 calls = 607 functions x 2 passes, prototype and definition), and inside
  one function: `linLowerForEmission` 891 ms + `linFinishForEmission` 1030 ms + `semInferTypes`
  295 ms = **2.22 s (53%)** against **`emitBodyAt` 1.90 s (46%)**, which splits `ilExtractUnit`
  0.99 s / `emitIlBodyText` 0.91 s. The "44% of `emitFunction` outside `emitBodyAt`" the
  sampling profile could not name is the lowering, the hoist/simplify passes and the type pass.
- **What only this tool shows: the calls.** `xmlAttr` 12.4M, `xmlKind` 9.5M,
  `xmlIsEmpty` 9.2M, `linIsBlock` 2.7M, `linIsGoto` 2.3M, `linIsCondJump` 2.3M,
  `linStmtJumpsTo` 1.85M, `expr` 44k, `xmlChild` 524k - and the yield machinery the
  machine-method exclusion keeps out: `advance` 44.7M, `value` 44.4M. Those counts are exact.
- **The first case it decided: `labelPass`.** The widest row was `linStmtJumpsTo`, reached
once per *label* through `linJumpsTo` - a scan of the sequence per label, with a `Str`
compare per statement. Collecting the jump targets in one walk (`linJumpTargets` /
`linCollectJumpTargets`) cut the *instrumented* run **4.73 s -> 3.64 s** and the *clean*
release run **734.7/754.5 -> 720.6/743.7 ms** (15 interleaved runs), with byte-identical
output on eight fixtures, every `.cpp` golden, and the fixed point.

## How to read a row

- **An entry costs ~40 ns.** `xmlKind` (a one-field compare) measures 42 ns a call and
  `xmlIsEmpty` 41 ns, so any row at 40-60 ns a call is a **call count**, not a time. The
  instrumented run is ~6x the clean one, and its `main` is not the clean `main`.
- **Nothing is inlined.** The flag gives every body a real function, so the small
  helpers the optimizer would fold away are measured as calls. That is why the same fix
  is worth 23% in the instrumented run and 1.5% in the optimized one: a *time* is
  trustworthy for the big rows, a *share* of a small hot helper is not. Use the table for
  who-calls-whom and how-often, and for the big rows' totals.

## What it is not

- **Not a sampling profiler.** It measures every emitted body exactly, and is blind to
  everything that is not one: the scanner's per-character work before a body is entered, the
  `main` prologue, static initialization, and a state machine's *factory* (which has no IL
  body).
- **Not free.** A measurement costs two `nowMicros()` calls and a `Dictionary` lookup per
  *body entry* - `xmlKind` at 9.5M calls pays it 9.5M times, and measures 42 ns a call for
  a one-field compare - so a `--profile` build is slower than a clean one by construction
  (6x on the compiler itself). Read *shares* of the big rows, and *call counts*
  everywhere; the shares of small hot helpers belong to the un-inlined build, not to the
  optimized one.
- **Not on by default, and not a cost when off**: with the flag off the emitter returns the
  empty string at every hook, so the emitted file is byte-identical to what it was before the
  flag existed (the goldens say so).

## Status

Implemented: the flag (`Request.profile`, `--profile` in both drivers), the emitted runtime and
per-body timers, `simse_nowMicros`, and `bun build.js --profile`. Two refinements came from the
tool's own first reading: a state machine's `advance`/`value` are not measured (44.7M/44.4M
entries, ~22% of the run, and `value` is a one-liner), and an entry is one `Dictionary` row
rather than two lookups.
Verified: a profiled `hello` program prints its table and nothing else changes; a profiled
compiler (**526 emitted bodies**) prints the same shape from the compiler's own source set, and
the fix its first reading chose (`labelPass`'s per-label scan -> one target walk) is
byte-identical and ~1.5% faster in the clean release build; `simse_tests.exe` 59/59 in release
and debug, `bun tools/stress.js` 44/44, and `bun tools/bootstrap.js`'s fixed point byte for
byte - all with the flag off.
