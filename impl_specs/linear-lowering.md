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

## Expression lowering

`linear::lowerExprs` (`cppsrc/linear/ExpressionLowering.{h,cpp}`, `linLowerExprs` in
`cppsrc/linear/ExpressionLowering.simse`) is the second half of the same idea: after
the linear pass the emitter has one *statement* vocabulary, and after this pass one
*expression* vocabulary. It runs on the lowered body, **after**
`linear::simplifyBody` (so the peephole pass never sees the temporaries) and before
emission.

Every **value position** the emitter sees is then one operation deep: a literal, a
name, a qualified name, a lambda, or a single operation over those. Anything deeper
is bound to a `_sm_expr<n>` local, numbered by a per-body counter that restarts for
each body (like the labels) - and that includes a conditional jump's condition and a
`return`'s value, so a jump or a return reads one name:

```
var a = (b + c) * d;        ->  Int _sm_expr1 = b + c;
                                var a = _sm_expr1 * d;

x = a[i + 2].toString();    ->  Int _sm_expr1 = i + 2;
                                x = a[_sm_expr1].toString();

return a[i + 2].toString(); ->  Int _sm_expr1 = i + 2;
                                Str _sm_expr2 = a[_sm_expr1].toString();
                                return _sm_expr2;

if (i < 5) { ... }          ->  Bool _sm_expr1 = i < 5;
                                ifTrue (_sm_expr1) goto L;

return p.x + 1;             ->  Int _sm_expr1 = p.x;
                                Int _sm_expr2 = _sm_expr1 + 1;
                                return _sm_expr2;
```

A *read* of a path is a value like any other, which is why `p.x` above gets its own
temporary. Aliases only survive where they are load-bearing - a call **receiver**
(`a[i].append(x)` stays a call on `a[i]`, whose index is flattened), an
**assignment target**, and the operand of `&`/`*` (or the address of a temporary
would be taken) - and those positions are what the pass calls `Path`:

```
a[i + 2].append(x);         ->  Int _sm_expr1 = i + 2;
                                a[_sm_expr1].append(x);
```

One binding is not just a copy, and the pass leaves it where it is: a **borrow of
a temporary**. `*f()` is `simse_addressOf(f())`, whose contract is that the pointer
lasts for the call it is passed to (`cppsrc/rtl/types.hpp`) - hoisting it into a
variable would outlive the temporary it points at. A borrow whose operand *is* an
lvalue (`*p`, `*self.field`, `*(list[i])`) is a place, points at storage that
outlives the statement, and is bound like any other value.

Temporaries are `VarDecl`s inserted in front of the statement that needed them and -
except for a `var` declaration, which must keep its name visible for the rest of its
region - scoped with `Stmt.Block`, so no jump can cross their initialization. The
semantic step below then gives each of them the type it can prove; a declaration the
inference cannot type keeps the emitter's `auto`.

Two boundaries are deliberate:

- **a path in a `Path` position keeps its alias.** Binding a call receiver or an
  assignment target to a value temporary would copy what is behind it, so a
  mutating call on the copy would be lost. Making the *path itself* a temporary in
  those positions would need a reference binding (`auto&&`), which re-binding on
  every iteration of a goto-loop makes awkward; it is a possible follow-up, not a
  requirement.
- **`&&`/`||` are left alone** (and so is the `?:` shorthand if it is ever added):
  their operands are evaluated conditionally, so hoisting anything out of them would
  change the program. They are the third statement shape this design will need, not
  an expression one - see "Short-circuit operators, ternary" below.

One thing this pass *cannot* decide alone is a call's arguments, and that is the
next step. A value parameter is a value (bind it), but a parameter declared `this:
T` is a **by-reference receiver**, so an argument that is a path must stay a path or
the callee's mutations would land in a temporary; and an argument written `null`
needs the parameter's type to be spelled at all (`nullptr` vs `Opt<T>::none()`).
Both need the callee's signature, which this pass does not resolve. A call-aware
lowering step - the same program facts the inference already reads, but shaping each
argument from the parameter it binds to - is the natural place for that, and the
pass above would then leave call arguments to it instead of binding them blind.

## Type inference on the lowered body

`linear::lowerExprs` gives the emitter one *expression* vocabulary; the semantic
step that follows gives its declarations a *type*, so the emitter neither guesses
one while it emits nor falls back to `auto`. It is `sema::inferTypes`
(`cppsrc/sema/TypeInfer.{h,cpp}`, `semInferTypes` in
`cppsrc/sema/TypeInfer.simse`) and it runs last, on the body the emitter is about to
emit:

```
inferTypes(lowerExprs(simplifyBody(lowerBody(body))), facts, body)
```

The facts it reads - the declared types, enum names, functions and methods (with
their receiver patterns), native extensions and file-level statics - are the ones
the emitter has already collected; they are threaded to the emitter as a parameter
rather than stored on it, because a data-class *field* would have to name another
package's type and the amalgamated file emits the packages in its own order.

The pass walks the statements in scope order (one type per name, shadowing
included) and fills in the type of every untyped `VarDecl` it can *prove*:

```
val a = (b + c) * d;        ->  Int _sm_expr1 = b + c;
                                Int a = _sm_expr1 * d;

val n = identity<Int>(7);   ->  Int n = identity<Int>(7);
```

- **Generics stay symbolic.** A type parameter in scope is a perfectly good type to
  spell: the emitted C++ is a template, so reification is still the C++ compiler's
  job. An explicit instantiation substitutes its type arguments into the call's
  result, and a *member* call binds the extension's parameters from its receiver
  (`Box<Int>.get()` with `get(): T` is `Int`). A type parameter nothing binds leaves
  no type to spell, and the declaration keeps its `auto`.
- **A type the emitter cannot spell is not written.** The pass checks that every
  name in the type is a type parameter in scope, a declared type, or an RTL type;
  anything else - a type parameter out of scope, an unknown name - stays `auto`
  rather than making the emitter fail later.
- **Four initializer shapes are deliberately left alone**: a lambda (its type comes
  from the callable type it is used against), `null` (no type of its own), and `&x`,
  `*x`, `copy(x)` (they change representation - a shared handle, a raw pointer, a
  copy - in ways this pass does not model). Those declarations keep the `auto` they
  had before the pass existed, and so does anything whose operand was one of them.

In the compiler's own 12.4k-line output this leaves **42** declarations untyped out
of ~1,400 (it was 1,402 `auto`s before the pass), and they are exactly the four
shapes above plus three smaller gaps: a prelude *struct method*
(`Span.size()`, `Span.isEmpty()` - prelude data-class methods are not collected as
facts, only their native extensions are), a native extension called as a plain
function (`spanOf(list)`), and a call through a function-typed local (a lambda
parameter). Closing those means teaching the pass about struct-method facts and
about callable types; none of them needs a new idea.

One Simse-ring wrinkle is worth recording, because it cost a debugging session: in
that ring a type *is* a node, and a node carries the role it was read from
(`ReturnType` in a signature, `TypeArg` in an argument list, `Type` in a field).
The emitter looks children up by role, so a type lifted out of a declaration and
put back somewhere else has to be **re-rooted** - `semReRole` is this pass's
`renameRole`. Without it the pass annotated correctly and the emitter simply did
not see the annotation: a `ReturnType`-rooted node placed as a statement's type
child is invisible to `xmlChild(stmt, AstNodeKind.Type)`.

## Short-circuit operators, ternary (not implemented)

The two-value operators `&&` and `||` (and a `?:` conditional expression, which the
grammar does not have yet) are *not* expressions the emitter should ever see. Each
one is a conditional whose branches are evaluated lazily, so it lowers to the
control-flow primitives this pass already produces - with the result materialised in
a temporary when the value is used:

```
var x = a && b;
    L1:;
    auto _sm_expr1 = a;
    if (!(_sm_expr1)) goto L2;      # short-circuit: b is not evaluated
    _sm_expr1 = b;
    L2:;
    auto x = _sm_expr1;

var y = c || d;
    L1:;
    auto _sm_expr1 = c;
    if (_sm_expr1) goto L2;         # short-circuit: d is not evaluated
    _sm_expr1 = d;
    L2:;
    auto y = _sm_expr1;

var z = p ? q : r;                  # the same shape with ifTrue/ifFalse swapped
    L1:;
    if (p) goto L2;
    auto _sm_expr1 = r;
    goto L3;
    L2:;
    _sm_expr1 = q;
    L3:;
    auto z = _sm_expr1;
```

So `ifTrue`, `ifFalse`, `goto` and `label` cover the whole language, and a `&&` in a
*condition* position needs no temporary at all: `if (a && b) { T }` becomes
`if (!(a)) goto end; if (!(b)) goto end; T'` in the same pass that owns `if`.
Until that lands, this pass is the one that must *not* flatten inside them - which
is why `exprIsShortCircuit` exists.

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
