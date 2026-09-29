# Escape analysis, borrowing, and refcount promotion

Status: **both landed** - refcount promotion (`cppsrc/linear/PromoteRefs.kt`) and a proof-of-concept
auto-borrow (`cppsrc/parser/BorrowParams.kt`); what is deliberately left out is named in each
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

`cppsrc/linear/PromoteRefs.kt`, run per unit beside the reuse passes
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

## Auto-borrow: a read-only parameter becomes a `*T` (landed, proof of concept)

`cppsrc/parser/BorrowParams.kt`, run from the driver right after `cpFoldConstParams` - the same
whole-program, **before-sema** AST rewrite, so the checker, the lowering, the emitter and every call
site see the borrowed declaration and nothing downstream knows the optimization exists (the
`specs/functions.md` handle inference already turns a value argument into the address for a `*T`
parameter). The parameter becomes `*T`, the author's choice: `const T&` would forbid a mutation the
language's borrow spelling permits, and would be a second style of code.

The rule is the simplest one that is sound, and it refuses by default - *unsure means it escapes*:

- a body may call only a **pure** callee: `data` (`IsPure`). Any other call borrows nothing. The
  length accessors are declarations too - `lenOf` for `Str`/`List`, and `Array.count`,
  `Dictionary.size`, `StrView.size()` (`cppsrc/rtl/rtl.kt`) - so no name is seeded: `pure` is
  exactly the set of `data` marks, the same set the value reuse reads (`linear/ReusePure.kt`). A
  callee can write through an alias this analysis cannot see, and a `data` promise is the one
  "writes nothing" fact the language has. A *construction* (`Point(1, 2)`) is not a call on the
  parameter and is allowed;
- **any** assignment whose target is not a plain name (`x.f = ...`, `x[i] = ...`, `*x = ...`)
  borrows nothing, anywhere in the body - the author's own rule, and the cheap guard against a write
  through an alias the analysis would otherwise have to hunt for;
- a parameter under `&`/`*`, an assignment target, or captured by a lambda is not borrowed. A
  capture would copy the *pointer* into the closure, which is exactly an escape;
- `this` is never borrowed - the emitter already passes a value receiver as `T* self`;
- a candidate is a function or a `data class` method with a Simse body, not `main`, not native, and
  declared exactly once (a name over two declarations has no signature to attribute), whose name is
  never used as a *value* (that would put the signature into a function type).

Everything else a parameter can do is a *read*: `p.f`, `p[i]`, `p.size()`, `p` as a value, and
`return p` (a value return copies, so the pointer does not escape). That is the authored shape -
"string->size() and return" - and few bodies qualify, which is the point: the proof is "this body
only reads".

**Where it pays, and where it is neutral.** In a body that only views or re-borrows the parameter
the copy disappears on *both* sides: `fun width(s: Str): Int { return s.size() }` becomes
`Int ns1_width(Str* s) { ... = s->size(); }` and the caller passes `&w`, where `spanOfStr`/`size`
take the pointer straight through. A body that uses the parameter in a *value* position (a `+`, an
arithmetic operand) reads through - `pair(Str* a, Str* b)` called with addresses, but its
`a + "-" + b` still materializes `copy(a)` (`_sm_base1 = *(a)`). The caller's copy moved into the
callee, so that shape is neutral rather than better. A literal argument materializes the temporary
the call site needs (`_sm_base2 = __sm_stringTable[k]; _sm_base1 = &_sm_base2;`) - the "patch the
call place with a new temporary" shape, at the cost the callee used to pay.

**The proof of concept deliberately stops there**: no interprocedural summary (a call is trusted
only by its `data` mark), no size model, and only a type it can name as heavy (`Str`, a `data
class`, a container) - never a scalar, a handle, or `Span`/`Array`.

## Decisions

1. **Auto-borrow exposes `*T`, not `const T&`.** Settled by the author: `*T` is the language's
   borrow spelling, so there is one style of code. The proof "the body only reads" is what makes it
   observationally equal to the copy it replaces.
2. **The "heavy" test is a type test, not a size.** `Str`, a `data class`, or a container
   (`List`/`Dictionary`/`Opt`/`Res`) is heavy; a scalar, a handle, `Span` and `Array` are not. A
   `sizeof`-driven threshold is the eventual refinement.
3. **Still open: how aggressive the read-only proof should be.** The pure-calls-only rule is what
   keeps it sound without dataflow, and it is what limits the win; an interprocedural purity
   fixpoint (`impl_specs/expr-reuse.md`, "Future: inferring purity") would let a plain helper be
   trusted too.

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

Auto-borrow: `stress/pure-function` grew the three shapes and pins them - `Int ns1_sum(ns1_Point* p)`
and `Int ns1_width(Str* s)` borrowed (the body only reads, and `s->size()` takes the pointer with no
copy either side), `Int ns1_bumpPoint(ns1_Point p)` and `ns1_impureTwice(Str s)` left by value (a
field write; a call the analysis cannot trust), and the call sites taking addresses
(`_sm_base2 = &p; ns1_sum(_sm_base2);`). Five corpus programs' `.cpp` moved when the borrow first
fired, all with unchanged stdout.

Note the coverage gap this closes: before this work **nothing** in `cppsrc` or `stress` used a
counted reference (`&T`), so the whole `&T` path was untested; `ref-promote` is the first case that
pins it.
