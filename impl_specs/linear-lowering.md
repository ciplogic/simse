# Lowering to linear control flow

`linear::lowerBody` rewrites every function-like body (a function/method body or
a lambda body) into a linear sequence of labels, jumps and blocks, so the C++
emitter has no structured-control-flow cases at all. A future `for` would be
desugared to `while` first; this pass owns `while` → `goto` and everything above
it.

- C++ ring: `cppsrc/linear/Linear.{h,cpp}` (`linear::lowerBody`).
- Simse ring: `cppsrc/linear/Linear.simse` (`linLowerBody`), called by
  `cppsrc/codegen/Codegen.simse`.

The pass runs **after sema and before emission**. Sema stays the only place that
checks `break`/`continue` legality and case labels, and it still sees the
structured AST. Emission calls the pass per body, so anything the emitter emits
is already linear; if a structured statement ever reaches `emitStmt`, the
emitter fails with an internal error instead of guessing.

## The primitives

| Node | Emitted C++ | Meaning |
| --- | --- | --- |
| `Stmt.Label` | `name:;` | label definition (`name` is the target text) |
| `Stmt.Goto` | `goto name;` | unconditional jump |
| `Stmt.IfTrue` | `if (cond) goto name;` | conditional jump (`Cond` child) |
| `Stmt.IfFalse` | `if (!(cond)) goto name;` | negated conditional jump (`Cond` child) |
| `Stmt.Block` | `{ ... }` | scope wrapper for a lowered body (`Body` child) |

`Stmt.Label`, `Stmt.Goto`, `Stmt.IfTrue`, `Stmt.IfFalse` and `Stmt.Block` are
produced only by this pass; the parser never emits them (`impl_specs/
ast-xmlnode.md`).

## Shapes

```
if (c) { T } else { E }
    if (c) goto L1;
    goto L2;
    L1:;
    T'                                # spliced flat (see below)
    goto L3;
    L2:;
    E'
    L3:;

while (c) { B }                       # the condition is evaluated once per
    L1:;                              # iteration, at the top
    if (!(c)) goto L2;
    B'
    goto L1;
    L2:;

switch (e) { case A: ... default: ... }   # arms keep source order and
    auto simse_sw_1 = e;                  # fall through exactly as in C
    if (simse_sw_1 == A) goto L3;
    ...
    goto L5;                              # the default arm, or the end label
    L3:;
    arm0'
    L4:;                                  # fallthrough target
    arm1'
    L5:;
    default'
    L2:;
```

- `break` lowers to a jump to the innermost loop's or switch's end label;
  `continue` to the innermost loop's condition label (a switch context does not
  capture `continue`), matching `specs/functions.md`.
- The switch subject is hoisted into an untyped `Stmt.VarDecl` so it is
  evaluated exactly once, as a C++ `switch` subject would be. The name is
  `simse_sw_<n>`; it is generated text and could in principle collide with a
  source-level identifier of the same name (an accepted, documented risk).
- **A region is wrapped in `Stmt.Block` only when it declares a variable at its
  own level.** A C++ jump may not bypass an initialization that is still in
  scope at the target, so a body whose lowered statements contain a `VarDecl`
  keeps its own `{ ... }`; a body that declares nothing is spliced flat into the
  enclosing sequence. The check runs on the already-lowered statements, so a
  `switch` subject (a synthesized `VarDecl`) also forces the surrounding region's
  scope. Splicing is safe because labels are never placed inside a nested block:
  a jump can enter a region but never a scope. The language already scopes each
  body separately (`sema` pushes a scope per body), so keeping the wrapper where
  declarations exist preserves semantics rather than changing them.
- `return` is left as-is; expressions are not touched.

## Simplification stage

`linear::simplifyBody` (`cppsrc/linear/Simplify.{h,cpp}`, `linSimplifyBody` in
`cppsrc/linear/Simplify.simse`) runs on the lowered body, right after
`lowerBody` and before emission. It is a small fixed-point peephole pass whose
only job is to keep the linear form close to the structured code it came from:

```
goto L; L:;                     -> L:;                       (nothing to jump to)
if (c) goto L; L:;              -> L:;                       (same, conditional)
ifTrue (c) goto A; goto B; A:;  -> ifFalse (c) goto B;       (the if/else fold)
goto L; <unreachable> L:;       -> goto L; L:;
L:; (nothing jumps to it)       -> (removed)
```

The fold turns the four-statement if/else prologue into one inverted jump, and
the dead-code rule removes the `goto` that follows a `return`/`goto` up to the
next label (labels are kept: a `break`/`continue` may still target them).
Dropping a jump can make a label unused and removing statements can expose
another fold, so the pass repeats until nothing changes (bounded by a guard).

Applied to the shapes above, `if (c) { T } else { E }` becomes:

```
if (!(c)) goto L2;      # the else entry (L2), with L1's label gone
    T'
    goto L3;
L2:;
    E'
L3:;
```

and a `while` whose body declares nothing keeps only `L1:; if (!(c)) goto L2;`
in front of the flat body. Nothing here is required for correctness: skipping
the pass yields a correct (larger) program, which is what makes it easy to
verify against the un-simplified output.

## Label numbering

Labels are `L1`, `L2`, ... from a counter that restarts at 1 for every body.
Labels are function-scoped in C++, so per-body numbering cannot collide, and
re-emitting a body (a generic instantiation) always produces the same names. The
counter is never reused across constructs inside one body. Exact numbers are not
part of any contract; the differentials only require the two rings to agree.

## Non-goals

No dead-jump elimination, no jump threading, no constant folding of conditions,
and no attempt to minimise the number of labels/jumps. The goal is a single
statement vocabulary for the emitter and a place where such optimisations can
later live.
