# A typed linear form, one operation at a time

Status: proposal - the plan the span-hoist attempt motivated (2026-10-23).

## Why

The optimizations this repo has shipped lately (`for`-promotion, place promotion, the
attempted span hoist) keep hitting the same wall, and it is not their own logic: it is the
linear form's *untyped seams*. A span hoist that rewrites `c[i]` to a bound `*T` pointer's
index is blocked because the extractor synthesizes a receiver slot it cannot type, and the
failure surfaces far away - at C++ emission, as `the slot '_sm_base1' has neither a type nor
an initializer` - after the cause is gone from view. Debugging it by walker-patching is the
loop this spec exists to end: each patch fixes one shape and quietly breaks another,
because nothing checks the IL's well-formedness between the rewrite and the emission.

The reference is **MSIL/CIL minus the stack**: our ops are register (slot) based, but the
property to copy is that *every operand and result is typed by construction, or deducible
from the op*, and a body that does not type is rejected at the IL boundary - not by the C++
compiler reading our output.

## The inventory (what is untyped today)

The live sites, all of them:

- `Unsupported` ops carry a fresh `?` slot (`LinearFormFrame.unsupported`) - a body that
  lowers through any `unsupported(...)` arm continues to emission with a hole in it.
- `operandOf`/`into` fall back to `freshSlotText("?")` for an expression whose type the
  rules cannot name (the extractor's unknown-expression arm, the type-name-in-value arm).
- `nameOf`'s static fallback: a name that is neither frame slot nor known static becomes a
  `GetStatic` into a slot typed from `exprType` - empty when inference missed, so `?`.
- `SetVar_Null` materializes `null` untyped on purpose (the destination decides), and
  `receiverOf`'s place path can build an address slot whose element type is `?` when the
  base's inference missed - the exact shape that stalled the span hoist.

The `unsupported(...)` categories are few: `expression` (twice), `statement`,
`expression statement`, `type name in a value position`, `assignment target`,
`compound assignment target`, `a cast with no destination`, `constructor call with no
destination`, `structured statement reached the IL`. That list is the migration's
worklist.

## The method: inline, one operation at a time

No wholesale rewrite, no second bytecode kept alive beside the first. Take **one** untyped
shape, define it as a typed operation category, rewrite just it, validate with the loop
(`dotnet run --project tools/iterate -- --full`), commit. The reduced-case discipline this
session used is the template: one small program that spells the shape, red before, green
after, pinned as a stress case.

**Every step lands under a flag** (`--typed-il`, default off - the `--no-borrow`/
`--no-escape` convention): the off path is the old lowering, byte for byte, so a broken
step never poisons the bootstrap chain or needs the two-build recovery dance - the
published bootstrap always compiles a tree whose default behavior is the last green one.
The loop validates both sides while the flag is off-by-default (the corpus with and
without the flag, the fixed point on the default path); the default flips only when a
step has been green on both paths, and the flag (and the old lowering arm behind it) is
deleted only when the next step's history gives reason to trust it. Breakage under the
flag costs a revert of one commit; breakage of the default never happens.

Each step is one of:

1. **Type an existing op.** E.g. `GetIndex` over a pointer-typed base names its element
   type from the pointee - the receiver slot is typed `*T`, so the result is `T`, and the
   `?` fallback in that path is deleted, not papered over.
2. **Define a missing op.** Where a source shape has no typed lowering, add one - the
   candidate the span hoist needs is a **`Span<T>` concept in the bytecode**: a
   `SpanOf(list)` op whose result slot is `Span<T>` (a pointer-and-length register pair),
   and index/read/store ops over it, so "hoist the buffer" is a typed `SpanOf` outside the
   loop instead of a synthesized declaration the walkers must survive.
3. **Turn an `Unsupported` into a diagnostic.** The arms above become type errors at the
   IL boundary (below) instead of holes that reach cl.exe.

## The verifier

A stage after linearization (and after every optimization pass - it is the passes' test
harness too) that walks each body's ops and checks: every slot read is typed, every op's
result type matches what the op defines, every jump target exists. A failure errors out
with the op, the slot, and the source line - *before* emission, so "the slot has neither a
type nor an initializer" is a compile-time diagnostic naming the responsible op, not a
post-mortem on generated C++. The verifier starts permissive (it only rejects `?` slots
that survive to emission) and tightens as ops gain types; when it is strict, the
`Unsupported` op kind can be deleted.

## Status

- **Step 1 (the verifier) is in** (`315dda1`): jump targets checked before emission; the
  slot-without-type failure names its line.
- **Step 2 (typed pointer indexing) is in** (`ba0304c`): `ps[i]` over a `*Cell` types as
  `Cell`, below the container shapes.
- **The buffer hoist is one typing edge from done (v3, stashed)**: the stability walker
  (`bpSpanStableNode`) and the declaration-free rewrite (`hoistSpanAt`: every `c[i]` in a
  storage-stable loop becomes `spanOf(c)[i]`, the emitter spelling the span index through
  its pointer; no injected declarations, so no placement or reordering hazard - the v2
  body-prepend miscompiled gzip exactly that way, the span landing after its first read
  once the short-circuit lowering reordered the body). The reduced battery and an
  rtl-only transpile pass; the full self-transpile fails typing `toArray` (generic
  context: `Span<T>` element over `spanOfArray(out)` in the write target) - the open
  edge, and the resume point. Mechanics learned on the way, all in the stash: synthetic
  AST nodes need their role names (`Callee`, `Receiver`) and positions or the extractor
  drops them; `LinLowerer` descends into `StmtBlock` (blocks are transparent); and
  operator get/set are templates whose declarations can trail uses, so span reads and
  writes spell `.ptr[i]` directly (the `spanDirect` guards).

## Order of work

1. The verifier, permissive (reject `?` at emission; print op + slot + line). It is safe
   unflagged - a `?` slot at emission is already a hard failure today, so this only
   replaces a late, opaque message with an early, named one. Strictness beyond that rides
   `--typed-il`.
2. `GetIndex`/`SetIndex` over a `*T` base: typed from the pointee (the span-hoist blocker;
   the hoist itself resumes after this lands). Under the flag.
3. `SpanOf` + span-typed index ops (`Span<T>` in the bytecode). Under the flag.
4. The static-fallback and null-materialization slots. Under the flag.
5. The remaining `unsupported` categories, cheapest first, each becoming a diagnostic or a
   typed op.

The flip/deletion plan from the earlier discussion (a SmaliLike shadow backend, migrate,
flip the default, delete the old form) stays as the fallback if a step proves too
entangled to do inline - the bet here is that none does: the inventory is small, and every
entry has a test shape already sitting in `stress/` or in this session's reduced cases.
