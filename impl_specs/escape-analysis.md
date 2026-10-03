# Escape analysis, borrowing, and refcount promotion

Status: **both landed** - refcount promotion (`src/linear/PromoteRefs.kt`) and a proof-of-concept
auto-borrow (`src/parser/BorrowParams.kt`); what is deliberately left out is named in each
section. This is the architecture for the two things the language should do by itself, without
asking an author to write `*`:

1. **Auto-borrow** - a value that does not escape is passed/held by pointer instead of by value,
   so no deep copy is made. The language already converts a value to a `*T` at a call whose
   parameter is `*T` (`specs/memory-model.md`, "Automatic dereference"), so an escape-free local
   whose storage is never *observed* to change can be represented by a pointer internally.
2. **Refcount promotion** - a counted reference (`&T`) is a heap box plus a refcount
   (`specs/ref-counted-layout.md`). When the handle never escapes and is bound once, the box can
   be built on the **stack** and the handle made a raw pointer to it, so the refcount traffic
   disappears and the value is destroyed with the scope.

The evidence that the target shape is expressible and correct is one hand-written program
(`impl_specs/expr-reuse.md`'s sibling): `var c: &Cell = &Cell(7); c.value = 1` emits

```cpp
Ref<ns1_Cell> c;  c = makeRef<ns1_Cell>(7);  c->value = _sm_base1;
```

while the promoted shape emits

```cpp
ns1_Cell _sm_stk3;  ns1_Cell* c;  _sm_stk3 = ns1_Cell{7};  c = &_sm_stk3;  c->value = _sm_base1;
```

- one heap allocation and its refcount traffic gone, the value destroyed at scope end - and every
  existing use of `c` (`c.value`, `c[i]`, a method receiver) spells the same C++ either way
  (`cx->value`), because the emitter already auto-derefs both a counted reference and a raw
  pointer.

## The escape property

Escape is a property of a **slot's storage**, computed per body over the linear IL. A slot does
not escape when every occurrence of it is one of a fixed set of *safe* uses; the *first* use that
is not, marks it. The rules, and why each is one:

| use | escapes? | why |
| --- | --- | --- |
| a base of `x.f`, `x[i]`, `&x.f`, `&x[i]` (`GetField`/`SetField`/`GetIndex`/`SetIndex`/`FieldAddr`/`IndexAddr`, the base operand) | no | the emitter auto-derefs a `&T` and a `*T` to the same `x->f` |
| a receiver of a call whose receiver is a **value** parameter (`T* self`) | no | `receiverArg` unwraps a `&T` with `.get()` and passes a `*T` as it is |
| an argument of a call whose parameter is `*T` or a value `T` | no | automatic dereference reads through / takes the address |
| `*x` (`Deref`) | **yes** | `*` on a `&T` is `.get()`; on a `*T` it is the value - a different meaning |
| an argument of a call whose parameter is `&T` | yes | a raw pointer cannot become a counted reference |
| a field/array/static store of the handle, a `Pack` element | yes | the box would outlive the frame once stored |
| a handle copy (`x = y` where `x: &T`), a `return` of a `&T` | yes | same |
| a lambda capture | yes | the closure outlives the frame |
| a second definition of the slot | yes | `var h: &Cell = &Cell(1); h = &Cell(2)` re-binds: the author means a longer-lived object, and `h = null` deliberately |

The last row is the user's rule: **a doubly-assigned handle is not promoted** - assigning `null`
is the author saying the handle has a life of its own.

The purity rule of `linear/ReusePure.kt` (`ilReuseCall`) is what makes "a call to a *pure*
function does not count as escape" true: a `data` function's argument is not written through, so
it cannot be. That is `ilReuseArgSafe`.

## Slices 1-2: stack promotion of a `&T` local (landed)

`src/linear/PromoteRefs.kt`, run per unit beside the reuse passes
(`IlCodeGen.emitBodyAt`/`dumpIl`, after `ilFuseConcatUnit`, before `ilReuseUnit`).

A slot `c` is promoted when:

- its declared type is a counted reference (`TypeReference`, i.e. `&T`);
- it is written **exactly once**, and that write is one of two shapes:
  - a `CallCtor` (the `&Ctor(args)` box, `ilBoxedCtorText`) standing in the body's **first block**
    (`ilReuseFirstBlockEnd`), so the stack value is built on every path before any branch and no
    jump crosses its declaration; or
  - a `Box c, src` (`&src`) whose source `src` is a value slot written once and read *nowhere*
    else - pointing at `src`'s own storage then drops the copy the box made, and no temporary is
    created, so the destructor story is unchanged;
- every occurrence of `c` is a **safe** position:
  - a **base** (`GetField`/`GetIndex`/`FieldAddr`/`IndexAddr` operand 1, `SetField`/`SetIndex`
    operand 0); or
  - a **receiver** of a call whose receiver is declared by value (or as a raw pointer) - the
    emitter's `T* self`, which a raw pointer already is. A counted-reference receiver is the handle
    itself and refuses. `IlMethod.recvIsValue` carries the distinction, recorded once per method in
    `IlExtractor.methodIndex`;
- its payload type declares no `unInit` **and** is a plain named type the facts know (a `CallCtor`
  payload becomes a fresh temporary, so a destructor would run twice; the `Box` shape reuses an
  existing value and so is exempt).

The `CallCtor` rewrite is four edits on the IL, no new opcodes:

1. a `*T` type entry (`ilPointerNode`) is appended and becomes `c`'s type, in `vars[c].typeIndex`
   **and** in `inferredTypes[c.name]` - the emitter seeds its `localTypes` from the latter, and
   `receiverArg`/`ExprIndex` read a receiver's type from there;
2. a value slot for the box's payload is appended, typed by the `&T`'s **inner** node. The box's
   own `Type` operand is the generic spelling `Cell<>`, which only a `makeRef` can name - a value
   declaration spells it `ns1_Cell<>`, which is not a type. The inner node is the plain `Cell`,
   which both the declaration and the construction (`T{args}`) spell correctly;
3. the `CallCtor`'s destination becomes the value slot. **The box-vs-value spelling is driven by
   the destination's type** (`ilBoxedCtorText` returns nothing for a non-`&T` destination), so the
   construction becomes `T{args}` with no other change;
4. a `Declare` for the value slot joins the hoisted declarations and a `Deref c, stackSlot` follows
   the construction - the `*x`/borrow spelling, `c = &stack`.

Everything after is the emitter doing what it already does: `c->value` and `c->add(x)` for both
handle kinds, and the value destroyed with the C++ scope.

The `Box` rewrite is smaller still: `Box c, src` becomes `Deref c, src` (`c = &src`) and `c`'s type
becomes `*T`. There is no new slot and no type entry for the payload - `src` already is the stack
value (`var valList = List<Int>(); var list = *valList;`).

### What it does not model (refuses, so stays a box)

- `c` as a `&T` argument, a store of `c` into a field/array/static, a `Pack` element, a `return`,
  a capture, and any second definition;
- a receiver whose declared type is a counted reference (`&T`/`PList`);
- a `Box` whose source is read anywhere else (the box's copy is observable there);
- an in-place `CallCtor` payload with an `unInit`, or one the facts cannot name;
- a `&T` **parameter** (the argument side is the call site's, and the promotion is intraprocedural).

Each is a *refusal*, so an unmodeled use is never silently promoted - the failure mode is a box
that could have been a stack value, never a dangling pointer.

## Auto-borrow: a read-only parameter becomes a `*T` (landed)

`src/parser/BorrowParams.kt`, run from the driver right after `cpFoldConstParams` - the same
whole-program, **before-sema** AST rewrite, so the checker, the lowering, the emitter and every call
site see the borrowed declaration and nothing downstream knows the optimization exists (the
`specs/functions.md` handle inference already turns a value argument into the address for a `*T`
parameter). The parameter becomes `*T`, the author's choice: `const T&` would forbid a mutation the
language's borrow spelling permits, and would be a second style of code.

The rule is the simplest one that is sound, and it refuses by default - *unsure means it escapes*:

- a body may call only a **borrow-clean** callee: one that writes nothing observable, so it cannot
  write through a parameter it is handed. The fact is a *mark* for a body-less declaration - `data`
  (pure) or `borrow` (read-only, the weaker of the two - `specs/functions.md`) - and is **proved**
  from the body by the borrow-clean fixpoint below for one that has a body, so a plain helper is
  trusted too. A *construction* (`Point(1, 2)`) is not a call on the parameter and is allowed;
- **the `for` lowering's machine step is trusted** (`_sm_for<n>.advance()`, `bpMachineStep`): the
  prefix is the compiler's own and a machine can only come from an `iterValues`/`iter` call in the
  same body, so the call that built it has already been weighed and `advance` reads the container it was
  built over;
- **any** assignment whose target is not a plain name (`x.f = ...`, `x[i] = ...`, `*x = ...`), **or
  is a file-level `var`**, borrows nothing, anywhere in the body - the author's own rule, and the
  cheap guard against a write through an alias the analysis would otherwise have to hunt for. The
  file-level half is what keeps a *caller's* pointer into a global valid: a caller may pass
  `&shared[i]`, and a callee that wrote `shared` would dangle it;
- a parameter under `&`/`*`, an assignment target, or captured by a lambda is not borrowed. A
  capture would copy the *pointer* into the closure, which is exactly an escape;
- `this` is never borrowed - the emitter already passes a value receiver as `T* self`;
- a candidate is a function or a `data class` method with a Simse body, in the prelude or a
  module; not `main`, not native.
  The rewrite touches a declaration only when its **name** is trusted by the fixpoint and the name
  is never used as a *value* (that would put the signature into a function type); a name with a
  declaration the pass cannot read (a bodyless prototype beside a generated body, say) keeps every
  signature, which is what keeps a call site and its definition in step.

Everything else a parameter can do is a *read*: `p.f`, `p[i]`, `p.size()`, `p` as a value, and
`return p` (a value return copies, so the pointer does not escape). That is the authored shape -
"string->size() and return" - and few bodies qualify, which is the point: the proof is "this body
only reads".

### The borrowness fixpoint (two flags, not one)

`bpInferReads` (`src/parser/BorrowParams.kt`) grows the trusted set from *below*, so the proof no
longer rests on the author remembering a mark:

- a declaration *with a body* is borrow-clean when it writes nothing observable (no field/index/deref
  store, no file-level `var`) and every call it makes is to a name already in the set;
- a declaration *without* a body (a `@SmGen` native) is trusted only by a mark - `data` (pure) or
  `borrow` (read-only, the weaker promise) - because its C++ is elsewhere and cannot be checked;
- a name joins only once **every** declaration with that name is trusted or proved, and the fixpoint
  starts from the marks and iterates to the least fixed point. Growing from below is the sound
  direction: a name left out is simply not trusted, and a recursive cycle is never assumed clean.
  An overloaded name is therefore a conjunction, not a coin toss: `slice` is trusted only when both
  `slice` bodies prove, and a bodyless overload of it blocks the name unless it carries a mark.

Both sides are rewritten - the prelude's Simse bodies borrow like a module's. The one exception is
**a prelude name hand-written C++ may call** (`bpCppCalled`): the resource bodies the emitted
program carries (the `_res.md` sections) and the runtime headers are scanned for `<name>(`, and a
name the scan finds keeps its authored signature, because that C++ is compiled against it. The scan
is coarse on purpose (a member call on another type counts), so it can only cost a borrow, never
make a wrong one: `startsWith`/`endsWith`/`indexOf` borrow, `find` does not - the `strops` section
calls `self.find(` (src/rtl/_res.md), and `--showBorrow` prints the refusal as
`borrow- find C++ calls it (prelude)`.

The same proof covers a `for` variable: `SemaCall.promoteForLoops` promotes the value form of a
`for` over a deep element (`bpDeepElement`) to the pointer wrap when `bpLoopReadOnly` (the
parameter rule, over the loop body) holds - `impl_specs/for.md`, "Auto-promotion". It runs in
sema, where the receiver's type is known (a machine has no `iter`), against the `reads`/
`statics`/`types` sets the pass leaves for it (`bpTrustedNames` and friends).

Two facts are at play and they are **not the same flag**:

- **borrowness** (this pass): the callee cannot write through a parameter, so a caller may hand it a
  pointer. It says nothing about the *result*, so a helper returning a fresh `Str`, a `List` or a
  yield-machine is borrow-clean;
- **purity** (`data`, `Codegen.kt`'s `pureCallees`, `linear/ReusePure.kt`): the result is a function
  of the argument, so a repeated call folds. Folding additionally needs the result to be
  identity-free and the body to read no file-level `var` (an intervening call could change a global),
  which the reuse pass owns - so the emitter keeps its own set and this pass does not feed it.

The split matters because the two are satisfied *differently*: a borrow proof needs only "writes
nothing", while a fold needs "the same value, and shareable". One flag would have to be the weaker
promise for borrowing and the stronger one for folding, and would be wrong for one of them.

### Switches

- **`--no-borrow`**: the analysis still runs (so the dump is available), the declaration rewrite does
  not - the emitted C++ is exactly what the author wrote. It also turns off the `for` promotion
  (`SemaCall.promoteForLoops`), so it is the escape hatch for either. It is what `stress/collections`
  builds with, so the *off* side is pinned by a golden.
- **`--showBorrow`**: one line per candidate on stderr - the prelude first, then the modules, -
  `borrow+ <name> <params>` or `borrow- <name> <why>` - the view that turned the next steps from
  guesses into a histogram (`grep '^borrow-' | ...`). `bpNote` collects the lines and the driver
  prints them, so the pass keeps no stderr of its own.

**Where it pays, and where it is neutral.** In a body that only views or re-borrows the parameter
the copy disappears on *both* sides: `fun width(s: Str): Int { return s.size() }` becomes
`Int ns1_width(Str* s) { ... = s->size(); }` and the caller passes `&w`, where `spanOfStr`/`size`
take the pointer straight through. A body that uses the parameter in a *value* position (a `+`, an
arithmetic operand) reads through - `pair(Str* a, Str* b)` called with addresses, but its
`a + "-" + b` still materializes `copy(a)` (`_sm_base1 = *(a)`). The caller's copy moved into the
callee, so that shape is neutral rather than better. A literal argument materializes the temporary
the call site needs (`_sm_base2 = __sm_stringTable[k]; _sm_base1 = &_sm_base2;`) - the "patch the
call place with a new temporary" shape, at the cost the callee used to pay.

**Reach.** A body-less declaration is trusted by its mark, so the RTL's read-only operations carry
`borrow` (`src/rtl/rtl.kt`: the string and character reads and conversions, the `List`/
`Dictionary` reads, `iterValues`/`iter`), as do the compiler's own read-only `common` accessors
(`src/common/xmlutil.kt`) and `fmtStr`. Two shapes an earlier pass got wrong are fixed:
`bpIsConstruct` recognizes a construction with **no type argument** (`AstXmlNode(...)`, `Str(...)`,
and the `Opt`/`Res` statics) - the `specs` always said a construction is not a call on the
parameter, the pass weighed a bare `ExprName` callee as a call - and `Opt.hasValue()` is a
**declared** operation (`src/rtl/_res.md`'s `optops`) instead of a built-in member with nothing to
mark, the `lenOf` treatment applied to the `Opt`/`Res` surface.

What is still out of reach, in the order a histogram of the `borrow-` lines puts it:

- **a body that writes its own local through an untrusted call** (`append`, `insert`, `appendStr`):
  the rule bails on any call it cannot trust, and a writing callee cannot be marked. Over half the
  candidates now, and the irreducible part of the coarse rule.
- **a body that writes any field or index at all** (`writes a field, an index or a pointer`, 112):
  the bail is *body-wide*, so a method that writes `this.<field>` never borrows a parameter it only
  reads. The next lever is to make it *per-parameter*: a write blocks only the parameters its target
  subtree mentions - a write to `this.f` or to a local cannot reach a by-value parameter, whose only
  alias would come from an `&p`/`*p`, which already excludes it - while a target that mentions a
  module-level `var` blocks everything, for the reason the file-level write rule exists.
- **the one built-in member that cannot be declared yet.** A receiver may now be a type with an
  `unInit` - it is emitted as a pointer (`T* self`), never a copy, so the checker allows it
  (src/sema/Sema.kt) - and `Res<T>.initByValue` is declared that way. `Res<T>.isOk()` could be
  declared too; it stays built-in for that one reason only. `value()` still cannot: it answers the
  type argument, and `memberCallReturn` returns a declaration's return type without substituting
  it, so a `T`-returning declaration would emit `T` where a concrete type belongs - a latent
  emitter gap to close first.
- and the cascade behind them - `eprintln`, and the emitter's own helpers (`line`, `emit`,
  `inferType`, `operandOf`, ...), each blocked by one of the above. Nothing is inferred from a
  whole-program call graph beyond the name-keyed fixpoint, no size model exists, and only a type it
  can name as heavy (`Str`, a `data class`, a container) is borrowed - never a scalar, a handle, or
  `Span`/`Array`.

## Decisions

1. **Auto-borrow exposes `*T`, not `const T&`.** Settled by the author: `*T` is the language's
   borrow spelling, so there is one style of code. The proof "the body only reads" is what makes it
   observationally equal to the copy it replaces.
2. **The "heavy" test is a type test, not a size.** `Str`, a `data class`, or a container
   (`List`/`Dictionary`/`Opt`/`Res`) is heavy; a scalar, a handle, `Span` and `Array` are not. A
   `sizeof`-driven threshold is the eventual refinement.
3. **Decided: the read-only proof looks through bodies, and borrowness is its own flag.** A
   name-keyed fixpoint (`bpInferReads`) proves which callees write nothing, so a plain helper is
   trusted; `data` stays the *declared* input, and the emitter keeps the *purity* flag the reuse
   pass reads.
4. **`borrow` is the mark for what the proof cannot see** (the author's suggestion, and the
   weaker sibling of `data` in `specs/functions.md`): a body-less declaration can only be trusted by
   a mark, and the RTL's read-only natives are exactly that. Marking them was worth it because
   `size`-style `data` was the wrong promise - a read-only `toString` returns a fresh `Str` and must
   not be folded, but a caller may still borrow into it.
5. **Two switches, not a mode.** `--no-borrow` disables the rewrite (the escape hatch, and what one
   corpus case builds with so the off side is pinned); `--showBorrow` prints the per-candidate
   decision. Both are the shape the other passes already use (`--no-concat`,
   `--showLinearRepresentation`).

## Validation

Proved by the compiler's own build (`bun build.js --release`, twice, since the change is visible in
the emitted C++), the corpus (`bun tools/stress.js`, **53/53**) and the bootstrap fixed point
(`bun tools/bootstrap.js`, byte for byte).

Promotion: `stress/ref-promote`'s `expected.cpp` pins every shape - `bump()`'s `Cell _sm_stk0;
Cell* c; _sm_stk0 = Cell{7}; c = &_sm_stk0;`, the same with a receiver (`ns1_add(c, 4)`), `*c`
(`p = c`), the `Box` shape `List<Int>* xs; _sm_base4 = List<Int>(); xs = &_sm_base4;`, and `make()`'s
returned handle (plus `main`'s `p`) left a `makeRef`. `stress/uninit` is the other half: its
`oneOwner` is a promotable `&Res` box, and the `unInit` refusal is what keeps it a box (a stack
value would run `~Res()` twice).

Auto-borrow: `stress/pure-function` pins every shape - `Int ns1_sum(ns1_Point* p)` and
`Int ns1_width(Str* s)` borrowed (the body only reads, and `simse_lenOf` takes the pointer with no
copy either side), `Int ns1_viaSum(ns1_Point* p)` borrowed through the *unmarked* `sum` the fixpoint
proves, `Str ns1_viaLabel(ns1_Point* p)` borrowed through `label` - a body the fixpoint *cannot*
prove (it builds a local string with `appendStr`) that only carries a `borrow` mark - while
`Str ns1_label(ns1_Point p)` keeps its copy (the mark is the caller's side, not the declaration's),
`Int ns1_bumpPoint(ns1_Point p)` and `ns1_impureTwice(Str s)` stay by value (a field write; a call
to `bump`, which writes the file-level `bumps`) - and the call sites taking addresses
(`_sm_base4 = &p; ns1_sum(_sm_base4);`). `stress/collections` builds with `--no-borrow`, so its
golden is the *unborrowed* shape: the flag is pinned by a case.

On the compiler's own tree the borrowed set is 25 parameters (`--showBorrow`; the count is of the
disposition, not of the win - the shapes above are what unblock the *candidates*); the
self-transpile measured ~1748 ms before the borrowness work and ~1433 ms after
(`bun tools/bootstrap.js`, wall clock). The corpus is
**53/53** and the bootstrap fixed point holds byte for byte.

Note the coverage gap this closes: before this work **nothing** in `src` or `stress` used a
counted reference (`&T`), so the whole `&T` path was untested; `ref-promote` is the first case that
pins it.
