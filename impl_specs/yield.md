# `yield`: a state machine, produced by lowering

`yield` is **not a semantic feature**. The parser produces one statement kind for it, and
everything else is a *rewrite*, so nothing in sema, the type pass or the emitter knows what
a yield is:

```text
fun everyOther(n: Int): ..Int {        struct ns1_everyOther_yieldable {
    var i: Int = 0                         Int branch{};   // where the machine is
    while (i < n) {                        Int current{};  // what it last yielded
        if (i % 2 == 0) {                  Int n{};
            yield i                        Int i{};
        }                              };
        i = i + 1                      Bool advance(ns1_everyOther_yieldable* self) {
    }                                      if (self->branch == -1) goto LYend;
}                                          if (self->branch == 1) goto LY1;
                                           self->i = 0;        // branch 0: the start
                                       L1:;
                                       _sm_expr1 = self->i < self->n;
                                       if (!(_sm_expr1)) goto L2;
                                       ...                     // the loop, as labels
                                       self->current = self->i;
                                       self->branch = 1;
                                       return true;
                                   LY1:;                       // the resumption point
                                       ...
                                   LYend:;
                                       self->branch = -1;
                                       return false;
                                   }
                                   ns1_everyOther_yieldable ns1_everyOther(Int n) { ... }
```

The class carries the values that cross a yield and nothing else: it is a plain data class.
The step that advances it is a **free extension function** (`advance(M* self)`), the shape a
lambda's call has (`<sym>_invoke`, `impl_specs/memory-model.md`), so the fields are reached
through `self->` exactly as any receiver function reaches its receiver's. A *generic* machine
is a class template, and the method is a template beside it (`advance(M<T>* self)`); a call
site spells `advance(&m)`.

## The two pieces of syntax

- `yield <expr>` - a statement, parsed like `return`.
- `..T` in a return position - a marker type: "this body yields `T`". It is **not a
  value type** on its own (`spellable()` refuses a nameless one), but the type pass names the
  machine's class from the creating function (`semMachineType`: `everyOther_yieldable`,
  `Span_iter_yieldable<T>`), so a binding's declaration spells the class rather than an
  `auto`. The parser produces it, the emitter maps it to the machine's class.

## Where the rewrite runs, and why there

**On the linear body** - after `lowerForEmission` (control flow is already labels and gotos,
`if`/`while` are gone, the yielded value is one operand) and after the type pass (a local has
the type its field needs), in `linear::lowerYield` (`src/linear/Yield.kt`):

1. **The fields** are `branch`, `current`, the receiver of an extension function, then the
   parameters and every local the body declares - except the lowering's own storage
   (`isSlotName`), which is per-statement and re-initialised on every entry, so it stays a
   local of the method.
2. **The dispatcher** is a chain of conditional jumps: `if (branch == -1) goto LYend;` then
   `if (branch == n) goto LYn;` for every yield. Branch `0` falls through, so it is the start.
   There is no `switch`: a `when` is already an `if`/`else` chain by this stage.
3. **`yield e`** becomes a named marker, `sm_suspend_point(n, e)`, which `YldMachinery.method`
   then expands to `current = e; branch = n; return true; LYn:;` - the label *is* the
   resumption point. Keeping the suspension distinct from what it lowers to
   (`linear::yldSuspendPoint` / `yldExpandSuspend`) means the control flow around it and the
   suspension itself are lowered separately, so the shape a suspend point takes lives in one
   place and a later pass can see where a body suspends before those points become gotos.
4. **A `return`**, or the end of the body, finishes the machine:
   `branch = -1; return false;` - `yield break`.
5. **A reference of a field** - read or written - is `this.<name>`, so a name that lives
   across a yield lives in the instance. A body name that would collide with one of the
   machine's own members is emitted under a mangled one (`linear::yieldFieldName`,
   `_sm_f_current`): `branch`, `current`, the receiver field and `advance` are the machine's.

## One protocol

```kt
val evens = everyOther(10)          // a machine on the stack
while (evens.advance()) {           // step it, and ask whether it yielded
    println(evens.current.toString())
}
```

`advance()` steps the machine and answers whether there was a value, leaving what it yielded
in `current`, the machine's field. Nothing is constructed per element - no `Opt<T>` to build,
ask `hasValue()` of, and unwrap. All four `for` forms use it (`impl_specs/for.md`).

`current` has the *element* type (`..T`'s inner), not `Opt<T>`: the type pass (`sema::Infer`)
types it that way, which makes a `for`'s loop variable a typed binding rather than an `auto`
the emitter would resolve the wrong native for. For the pointer wrap (`iter`) the element
type *is* `*T`, so `current` holds the place, which makes `for (*v in xs)` a borrow rather
than a copy. The two prelude conversions between the forms - `toValues`/`toPtrs` - are
machines themselves, so they change a chain's element without draining it (`impl_specs/for.md`,
"The two conversions").

**There is no `value()`.** `current` is a field of every element type (a scalar, an aggregate,
`*T`), so one member read is the whole conversion; a `value()` accessor would copy the element
twice (field into the temporary, temporary into the loop variable).

## A machine as a receiver

A yielding *extension* can take a machine as its receiver
(`fun ..*T.select<T, U>(f: (*T) -> U): ..*U`), which is the shape a LINQ-style pipeline is
built from (`src/modules/linq/linq.kt`). Two rules make it express:

- **The receiver's type parameter is listed in the function's own `<...>`**
  (`..*T.select<T, U>`): the parser reads type parameters from that list, and the receiver
  spelling contributes none - the same rule `Span<T>.iter<T>` follows.
- **The receiver's machine class is a C++ template parameter.** The class belongs to the
  *caller* (`Span_iter_yieldable<Int>`), and no declaration can name it, so a machine
  receiver makes the function a template over it:
  `template <class T, class U, class _SmIter> ... select(_SmIter* self, ...)`
  (`Emitter.machineIter`/`fnTemplateParams`, `type`'s nameless-`TypeYield` case). The call
  spells its template arguments, because C++ deduces neither the function's own parameters
  (they live in the machine's class, or in a callable argument) nor the machine's class:
  `ilCallNode` attaches them to the callee from what the type pass made - the result type's
  arguments in order, then the receiver's machine type (`attachMachineCallArgs`) - and the
  linear pass skips its own C++-deducibility check for such a call
  (`semMachineReceiverDecl`). A lambda argument's *result* binds the pattern's return
  parameter (`select`'s `U`), which is why `SemInfer.infer` types a lambda with its body's
  result.

Inside the body `this.advance()` steps the caller's machine (`advance(self)` - the receiver
already *is* the machine's address) and `this.current` reads its element; `yield *this.current`
hands out its place (`toPtrs`, `src/rtl/rtl.kt`). A `for` over
`this` is still out (the machine would have to be a field); the adapters step by hand.

## What the caller gets

The function becomes a **factory** that builds the machine on the stack and returns it by value:

```cpp
ns1_everyOther_yieldable ns1_everyOther(Int n) {
    ns1_everyOther_yieldable machine{};
    machine.n = n;
    machine.branch = 0;
    return machine;
}
```

A longer life is the language's `&T`: `&everyOther(10)` boxes a copy
(`std::make_shared<...>`), and the box is what is advanced. The parameters and the locals are
fields, so nothing about the machine points into the frame that built it.

## Status

- `yield`/`..T` in the parser, the rewrite in `linear::lowerYield`
  (`src/linear/Yield.kt`), the class and factory in `Emitter::emitYieldable`
  (`src/codegen/Codegen.kt`), and the cases `stress/yield` (a machine advanced by hand,
  one advanced after it finished, a body that names every machine member, both `for` forms)
  and `stress/generic-yield` (a generic yielding extension over `List<Int>` and `List<Str>`),
  verified by transpiling, compiling and running them through the self-hosted compiler and
  by byte-identical emission.
- **The class and the step are separate declarations.** The machine is a fields-only data class
  (`struct M { ... };`) and `advance` is a free *extension* function (`Bool advance(M* self)`, a
  template beside a generic machine's class) - the shape a lambda has (a data class plus a free
  invoke). The lowering still writes field accesses as `this.<name>`; the extension frame spells
  them `self-><name>` (the emitter's `inClosureMethod = false`), and a call site spells
  `advance(&m)` (`CgCall.kt`).
- **The machine's method is optimized like any other body**: the rewrite runs *after* the
  body's own half of the pipeline, so its output (the dispatcher, the label runs) had never
  been through `src/optimizations`. `emitMachine` runs `linOptimizeBody` over each method
  body before emitting it, folding the contiguity (`L2:; LYend:;` is one label) and the jumps
  around it.
- **A `yield` lowers through a named suspend point.** `linear::yldSuspendPoint` leaves a
  `sm_suspend_point(<branch>, <value>)` marker where the yield was, and `linear::yldExpandSuspend`
  is the one place it becomes `current`/`branch`/`return true`/`LYn:` - so the transformation is
  named and reusable rather than inlined per statement, and a later pass can see a body's suspend
  points before they are gotos. Emitted output is unchanged.
- The machine's shape:
  - **a generic function can yield**: the machine is a class template, and its name carries
    the function's type parameters wherever it is a *type*.
  - **an extension function can yield**: the receiver is a field of the machine (`_sm_self`,
    `linear::yieldReceiverField`) holding what the emitted `self` parameter holds (a pointer for
    a value receiver), so `this.x` reads the caller's object across a yield. In a *base*
    position `this` stays that field (spellings dereference it: `this._sm_self->size()`,
    `(*this._sm_self)[i]`); a bare `this` in a value position is read back out of it. A receiver
    written as a parameter (`fun f(this: T)`) cannot yield - `this` cannot name a C++ member.
  - **the dispatcher goes after the hoisted declarations**: a jump that skips a declaration is
    `C2362`, and for a generic machine a declaration *is* `T`, non-trivial for `Str`.
  - **the machine's fields are registered as a data class** in the emitter's type table
    (`emitMachine`), which tells `memberAccess` and the index spelling what `this.<field>` is;
    the type pass never saw the class the lowering synthesizes.
- Constraints: a condition a *lowering* builds carries the `Expr` role while the emitter finds
a statement's condition under `Cond` (`linCondJump` re-roots it); and a `yield`'s value is
hoisted like a `return`'s (`ExpressionLowering.kt`'s `StmtYield` case).
- **A machine can be a receiver** (`fun ..*T.select<T, U>(...)`): the operator's machine is a
  template over the source machine's class (`_SmIter`), and the call site spells its arguments
  (`ilCallNode`). `src/modules/linq/linq.kt` is the library built on it (`select`, `where`,
  `take`, `skip`, `toList` - pointer machines end to end, so a chain copies no element), with
  `stress/linq` pinning the lambdas and the chains.
- Not supported (reported, not left to the C++ compiler):
  - a local that the type pass could not spell (a field needs a type, and the pass says so
    instead of guessing) - which is what a machine local is, so a machine **cannot live
    across a yield**, and a `for` inside a yielding body is diagnosed with that;
  - a receiver written as a `this:` parameter (a `this` cannot be a field).
