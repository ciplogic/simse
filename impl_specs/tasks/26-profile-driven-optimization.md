# T26 - Profile-driven optimization

Status: Done
Phase: E - Performance
Depends on: -
Blocks: none

## Goal

Cut the compiler's self-transpile time using the `--profile` report
(`impl_specs/profiling.md`): start from the report's two top-25 lists, follow a hot body's path
down the tree, and change one thing at a time. A candidate is only a change once a *clean*
release A/B shows it.

## Motivation

The release profiled compiler over `--root src` (378,842 nodes, depth 143; `main()` 31.5 s
instrumented) puts ~87% of the run under `codegen.emitProgram` (27.6 s). The leaders:

| by self | self | calls | by total | total | calls |
| --- | --- | --- | --- | --- | --- |
| `atPtr` | 4.96 s | 163.5 M | `codegen.emitProgram` | 27.6 s | 1 |
| `common.xmlAttr` | 4.41 s | 29.0 M | `codegen.emitFunction` | 27.3 s | 2690 |
| `equals` | 2.96 s | 96.4 M | `codegen.emitBodyAt` | 18.9 s | 1316 |
| `codegen.call` | 1.70 s | 15.0 k | `codegen.expr` | 14.4 s | 95.4 k |
| `common.xmlIsEmpty` | 1.64 s | 53.9 M | `codegen.ilEmitOps` | 14.2 s | 1329 |
| `size` | 1.42 s | 46.2 M | `codegen.ilValueText` | 13.0 s | 34.6 k |
| `linear.callTarget` | 1.38 s | 15.5 k | `common.xmlAttr` | 8.2 s | 29.0 M |
| `codegen.findExtensionFn` | 1.05 s | 13.3 k | `parser.walk` | 6.2 s | 1.26 M |
| `common.xmlKind` | 1.02 s | 33.3 M | `linear.linFinishForEmission` | 5.0 s | 1329 |
| `common.xmlChildPtr` | 0.42 s | 4.1 M | `linear.run` | 4.6 s | 9410 |

Caveat that governs everything below: the instrumented run has **no inlining**, so a small
helper's `self` is mostly profiler and call overhead (`atPtr` at 163.5 M calls is the extreme).
Use **call counts** to find paths that are walked too often, and confirm every win in a clean
build.

## Scope

In:

- the emitter's per-node attribute reads (`xmlAttr` and its `xml*` / `equals` / `atPtr` cluster);
- repeated name resolution during emission (`findExtensionFn`, `findFunction`,
  `functionPackage`, `memberCallReturn`);
- the per-body linear passes (`linFinishForEmission`, `linLowerForEmission`,
  `linOptimizeBody`, `foldExprsUnder`, `linUseDefWalk`);
- the parser (`parser.walk` 6.2 s inclusive) if its subtree shows a pattern.

Out:

- language or semantics changes;
- optimizing a function because it tops the *instrumented* self list without a clean A/B;
- further linq rewrites (the earlier round measured neutral-to-slightly-slower; see the
  capability matrix).

## Deliverables

- A clean-release A/B (`tools/_bench_ab.mjs`, interleaved, min/median) for each attempt.
- The change itself, with `bun tools/iterate.js --full` green (88/88 and the fixed point);
  emission goldens re-captured only when the emitted C++ moved.
- The numbers in the commit message; a report only if a run is worth keeping (reports are
  gitignored, always regenerate).

## Acceptance criteria

- [x] At least one workstream below shows a clean-build win outside the interleaved noise, with
      the fixed point intact.
- [x] No unexplained golden churn, and the stress corpus stays green.

## Implementation notes

Six changes, each a clean-release A/B with `bun tools/iterate.js --full` green (88/88 and both
bootstrap fixed points); the numbers are in the commit messages (`83d8a52`, `bb46399`,
`344b800`, `53f9cff`, `16f7524`, `42918af`). Cumulative against the pre-task compiler:
self-transpile min/median **2516.9/2720.7 ms -> 1737.9/1847.8 ms (~31-32%)**, 13 interleaved
pairs.

- **Collected resolution tables.** `addFunction` records `plainFunctionNames` and
  `functionPackages`, so the two `hasPlainFunction` scans per named call and `functionPackage`'s
  walk are dictionary reads. The call path's 20M `xmlAttr` reads were the single largest profile
  path.
- **Name indices.** `SemFacts.functionsByName` and `Emitter.functionsByName` hold each name's
  indices in collection order; `callTarget`, `functionReturn`, `memberReturn`, the operator
  returns, `isInitByValue*`, `ilConfusingLambdaOverload`, `findFunction`, `memberCallReturn`,
  `findExtensionFn(ByType)`, `findReceiverFnByName`, `functionReturn`, `operatorBinaryFn`'s view
  fallback, `protocolImpls` and `computeMachineSuffixes` start from a name's declarations
  instead of the whole program.
- **Escape analysis scans each body once.** `epPreScanDecls` records the table-independent
  escapes and one event per call-argument mention; the fixpoint rounds re-evaluate the events
  instead of walking every body again. A two-build comparison over an identical source snapshot
  emits byte-identical C++.
- **The fold walk copies children only when one folds.** `foldExprsUnder` builds the child list
  lazily (children are read through their places), where it used to copy every visited node's
  children. In-place mutation was deliberately not used: ref-counted arrays can be shared, so it
  would invite aliasing bugs for a couple of percent more.
- **One use-def table per body, shared across the linear passes.** The three use-def passes
  rebuilt `linUseDefsOf` per pass per round; a pass that found nothing leaves the body
  byte-identical, so the next pass now reads the body's cache box instead. The box is keyed by
  the emitted signature (function signature, destructor symbol, machine class, closure symbol,
  task declaration) and is a counted reference, so it cannot dangle; `linOptimizeBody` drops it
  at entry and after any pass that reports a change.
- **Attempted and reverted:** a jump-free fast path in `flattenPass` that skips
  `linSpliceIsSafe` - neutral in the clean A/B, so it was not kept (`flattenPass`'s cost is
  mostly profiler call overhead; the instrumented tree overstates it).

Next candidates, in profile order: `linFoldConstBody` walks every statement twice plus a third
walk per candidate; `epAnalyze` still walks each body once up front (the event lists removed the
per-round walks); parsing (`lex.nextToken` + `parseModule`) is the largest untouched block.

## Steps

1. **Read a node's attributes once.** `common.xmlAttr` is 29.0 M calls (8.2 s total, 4.4 s
   self), and `xmlIsEmpty` (53.9 M), `xmlKind` (33.3 M), `equals` (96.4 M), `size` (46.2 M) and
   `atPtr` (163.5 M) are mostly *inside* it: the AST is a uniform attribute list, so every read
   is a scan (`ai/contributing.md` already asks for the collected-struct shape). Find the
   emitter paths that read several attributes of the same node (`Codegen.kt`'s `call`,
   `exprInner`, `functionPackage`, `memberCallReturn`), collect once per node visit, and re-read
   the counts. Mind that transforms rewrite attributes between passes.
2. **Cache the emission-time resolutions.** `findExtensionFn` (13.3 k calls, 1.05 s self),
   `findFunction` (6.9 k, 0.31 s), `linear.callTarget` (15.5 k, 1.38 s) and `functionPackage`
   (8.0 k, 0.39 s) resolve the same shapes repeatedly. A program-scoped `Dictionary` keyed by
   the inputs (receiver type, name, arity / call shape) is the shape to try - but only where the
   key covers every input the result depends on.
3. **The per-body linear passes.** `linFinishForEmission` is 5.0 s over 1329 bodies
   (~3.7 ms/body) - the second block under `emitFunction` - and `linOptimizeBody` 4.2 s (2960
   calls) runs the fold fixpoint, with `foldExprsUnder` alone at 755 k calls. Read what the
   finish pass walks before touching it: a pass that can share one traversal with the lowering
   (or memoize a per-body fact) is the candidate.
4. **Parser.** `parser.walk` is 6.2 s inclusive over 1.26 M calls with 0.22 s self: expand its
   subtree in the tree and decide whether it is the same attribute-read pattern or a
   per-statement cost.

## Risks / notes

- **Two-build rule for the profiler itself**: an edited runtime text takes effect in the *next*
  compiler build, so a profiled release compiler built by the old `simse.exe` carries the old
  runtime (the first A/B of this work compared three identical runtimes before that was
  noticed). Rebuild `simse.exe` first whenever the profile runtime changes.
- Machine noise on the profiling box is ±5%; always interleave (`tools/_bench_ab.mjs`,
  `tools/_profile_ab.mjs`) and use min/median, never one run.
- `atPtr` / `size`-style helpers may already inline in clean LTO builds: removing their *calls*
  is real, replacing the helper body is not necessarily a win.
- The report's `self` for a leaf in the emitter path is inflated by the profiler itself; prefer
  the call count and the clean A/B over the instrumented seconds.

## References

- `impl_specs/profiling.md` - the report, the summaries, where the file goes.
- `tools/_check_tree.mjs` (verifies a report), `tools/_profile_ab.mjs` (profiled A/B),
  `tools/_bench_ab.mjs` (clean A/B).
- `ai/contributing.md` - the attribute-read guidance; the emitter is
  `src/codegen/Codegen.kt`, the linear passes `src/linear/`.
- Regenerate the report: `bun build.js --release --profile --exe build/digits/simse_prof.exe
  --out build/digits/prof_compiler.cpp`, then run it over `--root src`.
