# The instrumented profiler (`--profile`)

A flag on the transpiler that makes the *emitted program* measure itself: every emitted body
starts with an RAII timer, and the **call tree** lands in a text file when the program exits. A
sampling profile says where the *samples* are, not where the *calls* are; this one counts calls,
and every line is one exact call stack - a child's total is time inside its parent's, never
more. The tree is what a flat table cannot show: not just *that* `xmlAttr` is hot, but which
path to it is.

## What is emitted

With `--profile` (and **nothing at all** without it):

1. **the prologue** gains the runtime - `<cstdio>`, `simse_profiling::CallNode`,
   `simse_profiling::ProfileScope`, `simse_profiling::ProfileApp`, the one `profileApp`, and the
   static `profileReport` whose destructor writes the tree;
2. **every emitted body** gains one first statement,
   `auto __smProfile = profileApp.measure(<index>);`, where `<index>` is a dense `Int` constant;
3. **the names** are one table of constants, `simse_profiling::kMethodNames[]`, written with the
   bodies - index `k` is the constant that body `k` measures with, and the name is the body's
   *package-qualified* one (`ns1_emitFunction` is written `codegen.emitFunction`);
4. **`main` needs nothing**: the report is a static's destructor, so it runs once the program
   leaves `main` - whichever `return` it took, and also when it falls off the end.

```cpp
Int ns1_bump(ns1_Counter* self) {
    auto __smProfile = profileApp.measure(12);
    ...
}
```

An entry lands on a node of a call tree, and a node is one *exact call stack*: the runtime keeps
the ordinals of the nodes it is in, and the node for this body under that stack - keyed
`(parent ordinal, body id)` in one `std::unordered_map`, created the first time that stack
reaches that body. Two call sites of one body are two nodes, so a node's totals are exactly the
calls that reached it. The constructor pushes the node and bumps its call count; the destructor
adds `simse_nowMicros() - start_` (or `simse_nowNanos()` - see *Units*) to the node and pops.
A measurement is one dictionary lookup and two clock reads; nothing is allocated after the
first sight of a path.

Every total and every count is `Int64` (`CallNode.total`, `CallNode.calls`,
`ProfileScope::start_`, and both clocks). A nanosecond count is ~1000x a microsecond one, so the
wider unit has to be `Int64` to stay meaningful - it would overflow an `Int32` in about two
seconds - and `total`/`calls`/`start_` are all `Int64` for exactly that reason, in both units.

## The report

The report opens with the two summaries, then marks the tree with a `tree:` line:

```text
top 25 methods by total:
  1. codegen.emitProgram                  28475834 us:        1 calls
  2. codegen.emitFunction                 28179729 us:     2690 calls
  ...
top 25 methods by self:
  1. common.xmlAttr                        ... us: 20219264 calls
  ...
tree:
main():32284475 us: 1 calls
+codegen.emitProgram():28475834 us: 1 calls
 +codegen.run():28475590 us: 1 calls
  +codegen.emitFunctions():28180686 us: 2 calls
   +codegen.emitFunction():28179729 us: 2690 calls
    +codegen.emitBodyAt():19407474 us: 1306 calls
```

A summary is one row per body summed over every path it ran on: `total` is the inclusive time
(the number a flat table has), `self` is the same minus the totals of the nodes under it - the
time the body itself carried, its own code plus whatever no body measured. Each list is the
biggest 25, biggest first; a body the run never entered is not listed. They answer "what is
hot", while the tree answers "through where".

The tree is depth first, largest child first: one line per node,
`name():<total> <unit>: <calls> calls`, indented one space per level with a `+` on every line
below the first (there is no line for the root itself, the frame outside `main`). The numbers
are the node's own - that path's inclusive time and call count - so a line's total is at least
the sum of everything printed under it, and a recursive body appears once per *depth* it
reached: the recursion is the nesting.

A body the run never entered has no node and no line. The names are the body's symbols
with the compiler's `nsN_` package prefix spelled out (`Emitter.prettySymbol`: `ns1_` is
`codegen.`), Simse-spelled - a namespace separates with `.`, not C++'s `::` (`profDots` folds
the `::` a lambda symbol carries) - so a line names a package and a function a reader can find;
`rtl` and `main` are unprefixed and unchanged. A lambda's synthesized `<owner>_closure<N>`
class is named for what was written: `ns1_foo_closure1::operator()` reads `pkg.foo.lambda1`.

## Where it goes

The default is the file **`simse_profile.txt`** in the working directory; `--profile-file <path>`
overrides it, and `--profile-file -` (an empty path too) keeps it on **stderr**. A path that
cannot be opened falls back to stderr.

The path is baked into the program at transpile time (the constant
`simse_profiling::kProfileFile`), not read from the program's own arguments: the profiler is a
build mode, and `--profile-file` is a flag of the *transpiler*.

## Where it is written

`src/profiling/Profiling.kt` owns the flag, the path and the text. The hooks are:

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

The clock is the RTL's `simse_nowMicros` or `simse_nowNanos` (`timeops`, `src/rtl/_res.md`;
the prelude surfaces `nowMicros()` / `nowNanos()`), beside `simse_nowMillis` - all monotonic, and
all `Int64`.

## Units

`--profile-nanos` switches the emitted timer to `simse_nowNanos()` and the line's unit token from
`us` to `ns`; the default is microseconds and `us`. It is a display choice, not a range one:
the totals and the counts are `Int64` in both units, so nothing narrows or overflows by switching
(a nanosecond total is ~1000x the microsecond one, and an `Int32` would wrap it in ~2 s). The
resolution is the platform's `steady_clock` - on Windows typically ~100 ns, finer than the
microsecond reading needs.

## Enabling it

```sh
bun build.js --release --profile                          # a profiled compiler: simse.exe
bun build.js --release --profile --profile-file p.txt     # ... into a named file
bun build.js --release --profile --profile-nanos          # ... in nanoseconds
./simse_transpile.exe --root <dir> -o out.cpp --profile [--profile-file <path>] [--profile-nanos]
```

`bun build.js --profile` passes the flags (and a `--profile-file`) through to the transpile step,
so the compiler's own source set is transpiled with the runtime and the timers. The published
bootstrap (`src/simse_bootstrap.cpp`) is **not** profiled, so a profiled compiler is a local
artifact, never published.

## The proof file

`src/simse_profile.txt` sits beside the bootstrap: the tree a release, profiled compiler wrote
about its own `--root src` run. Its `calls` column is exact (the compiler's emitted bodies and
how often each ran); its totals are one machine on one day. Regenerate it with:

```sh
bun build.js --release --profile --exe build/digits/simse_prof.exe --out build/digits/prof_compiler.cpp
./build/digits/simse_prof.exe --root src -o build/digits/prof_self_out.cpp
cp simse_profile.txt src/simse_profile.txt
```

(The checked-in copy still predates the call-tree format; refresh it when a run is worth
pinning.)

## How to read a line

- **An entry is one hash and two clock reads.** The map holds one entry per *distinct path*, not
  per call, so a `calls` column is exact and a `total` is wall time; the instrumented run is
  several times the clean one, and its `main` is not the clean `main`.
- **Nothing is inlined.** The flag gives every body a real function, so the small helpers the
  optimizer would fold away are measured as calls. That is why a fix can be worth a lot in the
  instrumented run and little in the optimized one: a *time* is trustworthy for the big lines, a
  *share* of a small hot helper is not.
- **The lines are paths, not functions.** The same function appears once per distinct stack that
  reaches it, so "how expensive is `xmlAttr`" is a question for the sum over its lines, not for
  one line; what one line answers is "how expensive is `xmlAttr` *when called from here*".

## What it is not

- **Not a sampling profiler.** It measures every emitted body exactly, and is blind to
  everything that is not one: the scanner's per-character work before a body is entered, the
  `main` prologue, static initialization, and a state machine's *factory* (which has no IL
  body).
- **Not free.** A measurement costs two clock calls and one hash per *body entry* - `xmlKind`
  at millions of calls pays it millions of times - so a `--profile` build is slower than a clean
  one by construction. Read *shares* of the big lines, and *call counts* everywhere.
- **Not on by default, and not a cost when off**: with the flag off the emitter returns the
  empty string at every hook, so the emitted file is byte-identical to what it was before the
  flag existed (the goldens say so).

## Status

Implemented: the flag (`Request.profile`, `--profile` in both drivers), the report (the two
top-25 summaries and the call tree), `--profile-file` (default `simse_profile.txt`, `-` for
stderr) and `--profile-nanos`, the emitted runtime (path-keyed `CallNode`s), the dense `Int`
method table with package-qualified `kMethodNames[]`, `simse_nowMicros` / `simse_nowNanos`, and
`bun build.js --profile` / `--profile-file` / `--profile-nanos`.

Verified: a profiled `stress/linq` program writes the summaries and the tree (lambdas and all)
to a named file, and to stderr with `--profile-file -`, and in `ns` with `--profile-nanos`; a
profiled release compiler over `--root src` writes a 378,842-node tree (depth 143, `main()`
32.3 s instrumented); `tools/_check_tree.mjs <file> 0` walks a report and reports **0
violations** - the two summaries descend, every node's total is at least the sum of its
children's, and no child exceeds its parent; with the flag off `bun tools/stress.js` is
**88/88** and `bun tools/bootstrap.js`'s fixed point is byte for byte.
