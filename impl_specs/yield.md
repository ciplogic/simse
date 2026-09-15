# `yield`: a state machine, produced by lowering (C++ ring)

`yield` is **not a semantic feature**. The parser produces one statement kind for it,
and everything else is a *rewrite* - which is why nothing in sema, in the type pass or
in the emitter knows what a yield is:

```text
fun everyOther(n: Int): ..Int {        struct ns1_everyOther_yieldable {
    var i: Int = 0                         Int branch{};
    while (i < n) {                        Int n{};
        if (i % 2 == 0) {                  Int i{};
            yield i                        Opt<Int> next() {
        }                                      if (this->branch == -1) goto LYend;
        i = i + 1                              if (this->branch == 1) goto LY1;
    }                                          this->i = 0;            // branch 0: the start
}                                          L1:;
                                           _sm_expr1 = this->i < this->n;
                                           if (!(_sm_expr1)) goto L2;
                                           ...                          // the loop, as labels
                                           this->branch = 1;
                                           return Opt<Int>::some(this->i);
                                       LY1:;                            // the resumption point
                                           ...
                                       LYend:;
                                           this->branch = -1;
                                           return Opt<Int>::none();
                                       }
                                   };
                                   ns1_everyOther_yieldable ns1_everyOther(Int n) { ... }
```

## The two pieces of syntax

- `yield <expr>` - a statement, parsed like `return`.
- `..T` in a return position - a marker type: "this body yields `T`". It is **not a
  value type**: `spellable()` rejects it, so a name bound to a call of such a function
  stays untyped and the C++ compiler types it (`auto`). The parser produces it, the
  emitter maps it to the machine's class.

## Where the rewrite runs, and why there

**On the linear body** - after `lowerForEmission` (so the control flow is already
labels and gotos, `if`/`while`/`switch` are gone, and the yielded value is already one
operand) and after the type pass (so a local has the type its field needs), in
`linear::lowerYield` (`cppsrc/linear/Yield.{h,cpp}`):

1. **The fields** are `branch`, the parameters and every local the body declares -
   except the lowering's own storage (`isSlotName`), which is per-statement and is
   re-initialised on every entry, so it stays a local of the method.
2. **The dispatcher** is a chain of conditional jumps: `if (branch == -1) goto LYend;`
   then `if (branch == n) goto LYn;` for every yield. Branch `0` falls through, so it
   is the start. There is no `switch` anywhere - it would only be lowered to these
   jumps anyway.
3. **`yield e`** becomes `branch = n; return Opt<T>.some(e); LYn:;` - the label *is*
   the resumption point. In `advance` (see below) it is `*value = e; return true;`.
4. **A `return`**, or the end of the body, finishes the machine:
   `branch = -1; return Opt<T>.none();` - `yield break`.
5. **A reference of a field** - read or written - is `this.<name>`, so a name that
   lives across a yield lives in the instance.

## Two ways to advance a machine

```simse
val evens = everyOther(10)          // a machine on the stack
var step: Opt<Int> = evens.next()   // the optional form
while (step.hasValue()) { ... }
```

`advance(value: *T): Bool` is the same machine without the copy: it writes through the
caller's pointer and answers whether there was a value. Both share the `branch` field,
so one machine is advanced either way; both are generated from the same rewrite, with
only what a yield *does* with the value differing.

## What the caller gets

The function becomes a **factory** that builds the machine on the stack and returns it
by value, which is what makes a local iterator a plain local struct:

```cpp
ns1_everyOther_yieldable ns1_everyOther(Int n) {
    ns1_everyOther_yieldable machine{};
    machine.n = n;
    machine.branch = 0;
    return machine;
}
```

A longer life is the language's `&T`, as everywhere else: `&everyOther(10)` boxes a
copy (`std::make_shared<...>`), and the box is what is advanced. The parameters and
the locals are fields, so nothing about the machine points into the frame that built
it.

## Status

- Implemented in the **C++ ring**: `yield`/`..T` in the parser, the rewrite in
  `linear::lowerYield`, the class and factory in `Emitter::emitYieldable`, and the
  example `docs/examples/yield/src/main.simse` (a `while` loop around the yield, both
  `next()` and `advance()`, a machine advanced after it finished, and both `for`
  forms). Verified by transpiling, compiling and running it.
- `for` (`impl_specs/for.md`) is this feature's consumer: it is desugared in the
  parser to a `while` over `next()`. Because the machine's element type is what makes
  a loop variable a typed binding rather than an `auto`, the lowering-time type pass
  carries the machine's two methods (`..T.next()` -> `Opt<T>`, `..T.advance()` ->
  `Bool`).
- The example lives under `docs/examples/`, **not** in `tests/fixtures`, because the
  Simse ring cannot lower a machine yet: a fixture is parsed *and* emitted by both rings,
  and the mirror has to catch up first (`Parser.simse` and `Sema.simse` already have;
  `linear/Yield.simse` and the emitter's `emitYieldable` are what is left).
- The machine's method bodies are emitted by the statement path (like the closure
  classes were before they moved onto the IL).
- Not supported yet, and now reported rather than left to the C++ compiler:
  - a yielding **method** (a body that uses `this` is reported);
  - a local that the type pass could not spell (a field needs a type, and the pass says
    so instead of guessing) - which is what a machine local is, so a machine **cannot
    live across a yield**, and a `for` inside a yielding body is diagnosed with that;
  - `yield` in a **generic** function (the machine class is not a template, so its
    fields could not be the type parameters).
