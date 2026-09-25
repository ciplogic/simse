# Lowering to linear control flow

`linLowerBody` (`cppsrc/linear/Linear.kt`, called by `cppsrc/codegen/Codegen.kt`) rewrites
every function-like body (function/method or lambda) into a linear sequence of labels, jumps
and blocks, so the C++ emitter has no structured-control-flow cases. `for` and `when` are
desugared while parsing (`impl_specs/for.md`, `specs/functions.md`), so this pass owns `while`
→ `goto` and everything above it.

The pass runs **after sema and before emission**. Sema stays the only place that checks
`break`/`continue` legality, and it still sees the structured AST. Emission calls the pass per
body, so anything the emitter emits is already linear; if a structured statement ever reaches
the emitter it fails with an internal error instead of guessing.

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
```

A `when` never reaches this pass: the parser has already turned it into the `if`/`else` chain
it means, with the subject bound to one `_sm_when<n>` declaration (`specs/functions.md`), so
the pass only lowers `if`, `while`, `break` and `continue`.

- `break` lowers to a jump to the innermost loop's end label; `continue` to the
  innermost loop's condition label. Both always target a loop: with no `switch`,
  and a `when` already an `if`/`else` chain, no other construct captures them
  (`specs/functions.md`).
- **A region is wrapped in `Stmt.Block` only when it declares a variable at its
  own level.** A C++ jump may not bypass an initialization still in scope at the target,
  so a body whose lowered statements contain a `VarDecl` keeps its own `{ ... }`; a body
  that declares nothing is spliced flat. The check runs on the already-lowered statements,
  and splicing is safe because labels are never placed inside a nested block: a jump can
  enter a region but never a scope. `sema` already pushes a scope per body, so keeping the
  wrapper where declarations exist preserves semantics. The block folding below re-decides
  the wrapper on the final statement sequence.
- `return` is left as-is; expressions are not touched.

## Simplification stage

`linSimplifyBody` (`cppsrc/linear/Simplify.kt`) runs on the lowered body, after
`linLowerBody` in every round of the pipeline below. It is a small fixed-point
peephole pass whose only job is to keep the linear form close to the structured
code it came from:

```
goto L; L:;                     -> L:;                       (nothing to jump to)
if (c) goto L; L:;              -> L:;                       (same, conditional)
ifTrue (c) goto A; goto B; A:;  -> ifFalse (c) goto B;       (the if/else fold)
goto L; <unreachable> L:;       -> goto L; L:;
L:; (nothing jumps to it)       -> (removed)
```

The dead-code rule removes the `goto` that follows a `return`/`goto` up to the next label
(labels are kept: a `break`/`continue` may still target them). The pass repeats to a fixed
point (bounded by a guard), since dropping a jump can free a label and removing statements can
expose another fold.

A label is *used* when any jump in the sequence names it - at any depth: a jump
inside a nested block can legally target a label of the enclosing sequence (the
expression lowering wraps a jump in the block that carries its temporaries, and
`break`/`continue` jump out of the body they are written in), so the scan looks
through blocks. Scanning only the sequence's own statements would delete a label
whose only jump the lowering had just wrapped - a `goto` to a missing label.

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

`linLowerExprs` (`cppsrc/linear/ExpressionLowering.kt`) runs on the lowered body after
`linSimplifyBody` in the same round, so the structured form's folds happen before the
temporaries exist; the peephole runs again only after the block folding, its only chance to
see a temporary. After it, every **value position** the emitter sees is one operation deep:
a literal, a name, a qualified name, a lambda, or a single operation over those. Anything
deeper is bound to a `_sm_expr<n>` local (a per-body counter, like the labels) - including a
conditional jump's condition and a `return`'s value, so a jump or a return reads one name:

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

A *read* of a path is a value like any other (hence `p.x` above gets a temporary). Aliases
survive only where they are load-bearing - a call **receiver** (`a[i].append(x)` stays a
call on `a[i]`, whose index is flattened), an **assignment target**, and the operand of
`&`/`*` (or the address of a temporary would be taken) - which the pass calls a `Path`:

```
a[i + 2].append(x);         ->  Int _sm_expr1 = i + 2;
                                a[_sm_expr1].append(x);
```

One binding stays where it is: a **borrow of a temporary**. `*f()` is
`simse_addressOf(f())`, whose contract is that the pointer lasts for the call it is passed
to (`cppsrc/rtl/types.hpp`) - hoisting it would outlive the temporary. A borrow whose
operand *is* an lvalue (`*p`, `*self.field`, `*(list[i])`) is a place, outlives the
statement, and is bound like any other value.

Temporaries are `VarDecl`s inserted before the statement that needed them and - except for
a `var` declaration, which must keep its name visible for the rest of its region - scoped
with `Stmt.Block`, so no jump can cross their initialization; the type pass below then types
each, and one it cannot type keeps the emitter's `auto`.

Two deliberate boundaries:

- **a path in a `Path` position keeps its alias.** Binding a call receiver or an assignment
  target to a value temporary would copy what is behind it, so a mutating call on the copy
  would be lost. Making the *path itself* a temporary would need a reference binding
  (`auto&&`), awkward to re-bind on every iteration of a goto-loop; a possible follow-up.
- **`&&`/`||` are left alone** (and a `?:` shorthand if added): their operands are
  evaluated conditionally, so hoisting anything out would change the program. They are a
  *statement* shape, not an expression one - see "Short-circuit operators, ternary" below.

One thing this pass *cannot* decide alone is a call's arguments. A value parameter is a value
(bind it), but a parameter declared `this: T` is a **by-reference receiver**, so a path
argument must stay a path or the callee's mutations land in a temporary; and an argument
written `null` needs the parameter's type to be spelled at all (`nullptr` vs
`Opt<T>::none()`). Both need the callee's signature, which this pass does not resolve; a
call-aware lowering step - the same facts the inference already reads, shaping each argument
from the parameter it binds to - is the natural place for that.

## Block folding

`linFlattenBlocks` (`cppsrc/linear/Simplify.kt`) folds a nested block into its parent
sequence, so a block survives only where one is needed:

```
{                               Int _sm_expr1 = p.x;
    {                           return _sm_expr1;
        Int _sm_expr1 = p.x;
        return _sm_expr1;
    }
}
```

It is the last stage of a round (below), which is why it sees the blocks the
expression lowering wraps a statement and its temporaries in. In the compiler's own
output that folding takes 2,895 blocks - 1,260 of them nested in another block - down
to 1,339; the slot hoisting below then takes it to **758 blocks, 94 of them nested**
(a 96% cut, and the ones left are the *program's* own scopes - a source-level `var`
whose declaration a jump bypasses).

A block is *not* folded when splicing it would move one of its declarations across a
jump, because C++ rejects a jump that skips an initialization still in scope at the
label ([stmt.dcl]/3, MSVC C2362). The rule is exact: with the block's own statements
in the parent's place, a jump `J` and a label `L` at that level make the splice
illegal exactly when

```
pos (J) < pos (D) <= pos (L)
```

for a declaration `D` the block brings up - a jump before `D` that lands past it. `J`
may sit inside another block (it runs after everything before the block it is written
in), so the check looks through blocks just like the label scan above. Children fold
first, so a parent is judged on the body its children leave behind, and a sequence
with no jumps at all - a straight line of statements - always folds.

The fold has one non-obvious rule: a block's flattened body is computed before any
splice decision, kept *out* of the item list, and its node is built in the one branch
where the block survives. Deciding and emitting in a single loop looks equivalent and is
not - a test for block `i` reads the bodies of the *other* items in the sequence (every
jump inside them, through nested blocks), so the decisions have to be a batch, and no body
may be handed to a survivor before they are all in. An as-you-go version produced a
byte-identical IL with *different shadowing renames*: emptying a body had taken a jump
away from a later test, so a different set of blocks survived, and the renames follow the
surviving scopes. (The IL was identical, which is why only the emitted text caught it.)

The temporaries of one arm are crossed by the jump to the next arm's label, so the arm keeps a
scope:

```
{
    Bool _sm_expr8 = op == "!=";
    if (_sm_expr8) goto L7;
}
```

Slot hoisting below moves the declaration out of the way, leaving the block nothing to hold.

## Slot hoisting: one scope per body

`linHoistSlots` (`cppsrc/linear/Simplify.kt`) moves **every** declaration of a body to the top
of it - the lowering's own temporaries (`_sm_expr<n>`) and the program's `val`/`var` alike (the
parser's `_sm_when<n>` subject bindings move with them) - and turns each initializer into an
assignment where the declaration stood:

```
{ Bool _sm_expr2 = i == 3; if (_sm_expr2) goto L4; }     var total = 0;
    ->                                                     ->
Bool _sm_expr2;                     # at the top         Int total;          # at the top
...                                                        ...
_sm_expr2 = i == 3;                                        total = 0;
if (_sm_expr2) goto L4;
```

A declaration at the top is one no jump can bypass (the folding's requirement, C2362), so
after this a body has **one scope** and no block remains for a declaration's sake. The
initializer stays where it was, so **evaluation order and side effects do not move** - what
moves is where the storage is declared. Every slot of the body is then live for the whole
body: the slots of a bytecode frame, without liveness reuse. That is the cost of the flatness,
and on the 1BRC it measured at nothing (`benchmarks/onebrc/benchmark.md`: 1323 ms against
1322 ms for the same
program emitted by the compiler before this pass).

One scope is also what makes a name unique **in the body**, and the language lets two scopes
reuse a name (shadowing). `renameShadowed` resolves that before anything moves: the first
declaration of a name keeps it, every later one is renamed, and the uses that resolve to the
renamed declaration move with it. A generated name is `_sm_<name>_<n>` - the reserved `_sm_`
prefix, and the counter *after an underscore* so a rename can never collide with a
compiler-generated name (`base2` renamed to `_sm_base2` would be the extractor's own place
slot - a real bug, and why the underscore is not decoration). `finishForEmission` takes the
names already declared in the body's own C++ scope - its parameters, `self` - as `reserved`.

A **lambda is a body of its own for this pass too**: the enclosing body does not name the
declarations inside a lambda body, it only follows the captures through it (a name the lambda
does not bind is read from the enclosing frame, so the enclosing rename still decides it).
Each lambda body is named by its own `finishForEmission`, with its own parameters as
`reserved` - which lets two lambdas in one body each declare a local of the same name and
both keep it (`tests/fixtures/lambda_scopes.kt` pins the shape).

It runs **after the type pass**, because a declaration has to keep the type that pass
proved: `auto x;` is not a declaration. A declaration the inference could not spell
in full - an untyped slot, or a partly unknown type such as the `*?` of a synthesized
place - keeps its declaration in place, and the block around it with it, which is the
only reason the emitted C++ still has a block anywhere.

The pass is pure and idempotent: a declaration without an initializer that already
stands at the top is not a declaration to move, so a second run finds nothing (what
the round's `changed` flag reports). The slot prefix is `_sm_expr` (8 characters),
and `Str::compare(pos, count, ...)` takes the *count* - a wrong count silently
compares different bytes and the pass does nothing.

## The pipeline

The stages run in a loop, because each leaves work for the others: the linear pass feeds the
peephole, the peephole exposes a jump the expression lowering no longer needs, and the folding
hands the round a flatter body - a jump a block hid is one the peephole can fold, and a folded
jump can free a label.

```
while (canChange) {
    while (canExtractExpressionsOrLabels) {
        canExtractExpressionsOrLabels =
            lowerBody() || simplifyBody() || lowerExprs();
    }
    canChange = flattenBlocks();
}
```

`linLowerForEmission` (`cppsrc/linear/Linear.kt`) is that loop, and it is what the
emitter calls per body. Every stage returns the body *and* whether it changed
anything (`LinLowered`), which is what the loop tests: a stage that made no change
has to say so, or the round would never end. Progress is monotone - no stage adds a
statement - so the loop terminates on its own; the guard in the code bounds a bug,
not the work.

That is the first half. The declarations the lowering introduced have no type yet, and
the folding cannot spell `auto x;`, so the pipeline has a second half, and
`sema::inferTypes` sits between them:

```
lowered = lowerForEmission(body)       # rewrite rounds, folding as it goes
typed   = inferTypes(lowered, facts, body)
ready   = finishForEmission(typed)     # hoist the slots, fold what that frees
```

`linFinishForEmission` (`cppsrc/linear/Linear.kt`) runs the same shape again - the
hoisting in the place of the rewriting stages (there is nothing left to rewrite), the
peephole, and the folding - until a round changes nothing. From there the body is one
flat sequence of labels, jumps and assignments, which is what the goldens record and
what the emitter prints.

## Type inference on the lowered body

`linLowerExprs` gives the emitter one *expression* vocabulary; the semantic step that
follows gives its declarations a *type*, so the emitter neither guesses one while it
emits nor falls back to `auto`. That step is `semInferTypes`
(`cppsrc/sema/TypeInfer.kt`) and it runs last, on the body the emitter is about to
emit:

```
inferTypes(lowerForEmission(body), facts, body)   # and then finishForEmission
```

(`lowerForEmission` is the loop above: `lowerBody` / `simplifyBody` / `lowerExprs` to a
fixed point, then `flattenBlocks`, until a round changes nothing.)

The facts it reads - declared types, enum names, functions and methods (with their receiver
patterns), native extensions and file-level statics - are the ones the emitter has already
collected; they are threaded to the emitter as a parameter, not stored on it, because one
value serves the whole program.

The pass runs on **every** function-like body, lambda bodies included: a lambda has no
declaration, so its frame is its own parameters plus its captures, which the language models
as closure fields (`specs/memory-model.md`). A `sema::Body` carries those
(`paramNames`/`paramTypes`/`captures`), and the extractor runs the pass on the lambda's
statements between lowering and finishing them (`LinearForm.kt`: `ilLambdaLower` ->
`inferTypes` -> `finishForEmission`), the same three steps a function body goes through. What
the pass proves travels into the IL body (`linear-il.md`, `IlBody.inferred`), so a backend
seeds its spelling frame from the body instead of a statement walk.

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
- **Two initializer shapes are deliberately left alone**: a lambda (its type comes
  from the callable type it is used against) and `null` (no type of its own). `&x`,
  `*x` and `copy(x)` *are* typed - a counted reference, the address of what the
  operand denotes, the value behind a handle - which is what took the `program_expr`
  golden's `auto reference`/`auto dereferenced` to `std::shared_ptr<Int>`/`Int*`.
- **A proven type is returned even when it cannot be spelled.** A name holding a
  state machine is `..T`, which the emitted C++ writes `auto`; `Stmt.type` keeps its
  other meaning ("a type a declaration can be written with"), so it stays out of the
  declaration - `cppsrc/linear/Yield.kt` relies on that to reject a `for` over a machine
  crossing a `yield`. The frame still needs it, so the pass reports every binding it
  proved and the IL carries them (`impl_specs/linear-il.md`).

In the compiler's own output the pass types every declaration it can see except three
recorded gaps: a prelude *struct method* (`Span.size()`, `Span.isEmpty()` - prelude
data-class methods are not collected as facts, only their native extensions are), a native
extension called as a plain function (`spanOf(list)`), and a call through a function-typed
local (a lambda parameter). Closing them means teaching the pass about struct-method facts
and callable types; none needs a new idea.

Typing the closure frame fixed two silent errors: a `for` inside a lambda over a machine used
to emit `iter(simse_addressOf(_sm_expr1))` for a receiver that was already a machine - a C++
type error - and `v.toString()` on the loop variable picked the `StrView` overload, because
the *type* decides which `toString` is meant. `stress/lambda-for` is the regression case (both
`for` forms, over a container and over a machine, plus the indexed form), and
`tests/fixtures/lambda_scopes.kt` pins the rename case.

A type *is* a node, and a node carries the role it was read from (`ReturnType` in a
signature, `TypeArg` in an argument list, `Type` in a field). The emitter looks children
up by role, so a type lifted out of a declaration and put back somewhere else has to be
**re-rooted** - `semReRole` is this pass's `renameRole`. Otherwise the pass annotates
correctly and the emitter does not see the annotation: a `ReturnType`-rooted node placed
as a statement's type child is invisible to `xmlChild(stmt, AstNodeKind.Type)`.

## Short-circuit operators, ternary

The two-value operators `&&` and `||` (and a `?:` conditional expression, which the
grammar does not have yet) are not *expressions*: each is a conditional whose
operands are evaluated lazily, so it belongs to the control-flow primitives this pass
produces.

**In a condition** that is what happens, one leaf at a time. `if (a && b && !c)`
becomes, for each operand, a test of that operand straight to the end of the `if`:

```
if (a != 1 && b != 2 && !names.contains(pkg)) { T }
    ->
Bool _sm_expr1 = a != 1;    if (!(_sm_expr1)) goto L2;   # false -> past T
Bool _sm_expr2 = b != 2;    if (!(_sm_expr2)) goto L2;
Bool _sm_expr3 = names.contains(pkg); if (_sm_expr3) goto L2;
L2:;                                                     # T', when it is reached
```

`&&` jumps to the *false* target when an operand does not hold, `||` jumps to the
*true* target when one does (and its last operand falls through to a `goto` of the
false target). A leaf is spelled with whichever of `ifTrue`/`ifFalse` matches its
value - the lowering emits *both* jumps and lets the peephole fold the pair into one
- so a negation in the source is never turned into a negated expression, and a `!`
just swaps the two outcomes. A condition the pass cannot decompose (a `&&` inside a
call argument, say) stays one jump whose condition holds the operator, which the
emitter spells as C++ `&&` - `containsShortCircuit`/`isDecomposable` are exactly that
boundary.

**In a value position** the shape is the one below and it is still open: the result
has to be materialised in a temporary, and that means binding into a slot from two
places, which the expression lowering does not do (it is why `exprIsShortCircuit`
keeps the operators out of its reach).

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

## Label numbering

Labels are `L1`, `L2`, ... from a counter that restarts at 1 for every body.
Labels are function-scoped in C++, so per-body numbering cannot collide, and
re-emitting a body (a generic instantiation) always produces the same names. The
counter is never reused across constructs inside one body. Exact numbers are not part of
any contract; the corpus only requires the output to be reproducible.

## Non-goals

No dead-jump elimination, no jump threading, no constant folding of conditions,
and no attempt to minimise the number of labels/jumps. The goal is a single
statement vocabulary for the emitter and a place where such optimisations can
later live.
