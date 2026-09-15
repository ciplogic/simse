# `for`: a `while` in disguise (C++ ring)

`for` is a **syntactic** construct. The parser desugars it, so no stage downstream -
sema's checker, the linear pass, the emitters, the IL - has a `for` statement kind to
know about, and `break`/`continue` are the `while`'s own machinery.

```text
for (v in m) { body }              var _sm_for1 = m
                                   while (true) {
                                       var _sm_step1 = _sm_for1.next()
                                       if (!_sm_step1.hasValue()) { break }
                                       val v = _sm_step1.value()
                                       body
                                   }

for ((v, i) in m) { body }         var _sm_for1 = m
                                   var _sm_index1: Int = -1
                                   while (true) {
                                       _sm_index1 = _sm_index1 + 1
                                       var _sm_step1 = _sm_for1.next()
                                       if (!_sm_step1.hasValue()) { break }
                                       val v = _sm_step1.value()
                                       val i = _sm_index1
                                       body
                                   }
```

## Where it runs, and why there

`Parser::parseFor` (`cppsrc/parser/Parser.cpp`), reached through `parseStmtInto` - the
one statement slot that can expand to *several* statements, because the machine has to be
declared outside the loop it runs. Everything the template generates carries the `for`
token's position, so a diagnostic - from sema or from the C++ compiler - points at the
line the user wrote.

Two details of the shape are load-bearing:

- **The advance is the first statement of the body, not the loop's condition.**
  `continue` jumps to the condition, so a `_sm_for1.next()` in the condition would leave
  `continue` re-reading the same value forever. Being first in the body, it runs on every
  iteration including a `continue`d one: `continue` means "skip the rest of the body".
- **The index starts at `-1` and is pre-incremented at the same place**, for the same
  reason: an index incremented at the *end* of the body would miss every iteration that
  `continue` skipped. `-1` is what makes the pre-increment hand out `0` first.

The index the user names (`i`) is bound per iteration from the counter
(`val i = _sm_index1`), so the loop's variables are fresh per iteration, cannot be
assigned, and the counter itself is the template's. The counter's extra increment on the
iteration that finds the machine exhausted is invisible: it is never read again.

## The machine's interface, and the one rule sema needed

`..T` is deliberately **not spellable** (`spellable()` refuses it), because a machine's
C++ type is the class the *creating function* got - two functions yielding `Int` have two
machine classes, and `..T` names neither. A name bound to a machine therefore stays
`auto`. That used to end the story for everything derived from it, which broke `for`:
`v.toString()` on an untyped receiver resolves to the `StrView` native, and
`simse_strView_toString(Int)` does not compile.

So the lowering-time type pass (`cppsrc/sema/TypeInfer.cpp`) now knows the *two* methods
of a machine, which is all the template calls:

| receiver | method | type |
| --- | --- | --- |
| `..T` | `next()` | `Opt<T>` |
| `..T` | `advance(*T)` | `Bool` |

With that, `_sm_step1` is `Opt<Int>`, `v` is `Int`, and every temporary the linear pass
made from them is typed too. `TypeKind::Yield` also had to start substituting like a
pointer does (`substituteBindings`), or a generic function's `..T` would have lost its
element type before reaching that rule.

## A container is not iterable, and the compiler says so

The parser cannot tell what a `for` iterates, so the check lives in sema's checker
(`Analyzer::checkForIterable`): the template's machine name is recognizable (`_sm_for<n>`,
the same convention as the lowering's `_sm_expr<n>` slots), and when the iterated
expression's type is *known* and is not a `..T`, that is the error - at the `for`, not at
a generated statement:

```text
main.simse:7:5: a `for` iterates a machine (`..T`), and List<Int> is not one;
                iterate a container with `while` and an index
```

An unknown type stays silent (the C++ compiler gets the last word, as it does for any
other member), so the check never fires on something the checker cannot name.

## Where this is going: `smToYield`

The construct stays two forms - `for (v in x)` and `for ((v, i) in x)` - but *what can
be iterated* becomes a convention instead of "a machine": the iterated expression is
wrapped in a call to a function named **`smToYield`**, so

```simse
for (item in x) { ... }      // is the same program as
for (item in smToYield(x)) { ... }
```

and anything the language can find a `smToYield` for is iterable:

- **`List<T>` (and the other containers), in the prelude**, written as the language's
own `yield` - a classical in-order walk:

  ```simse
  fun List<T>.smToYield(): ..T {
      var i: Int = 0
      while (i < this.size()) {
          yield this[i]
          i = i + 1
      }
  }
  ```

- **A user's own type**, by the same convention (an extension function returning `..T`),
  which is the static-interface idea: iteration is *satisfied* by a function the type's
author writes, not by a runtime interface.
- **A range, later**: `for (i in (2 .. 5))` becomes an iterator over the two bounds, i.e.
one more `smToYield` whose machine holds `2` and `5` as its parameters.
- **A machine**, which is already what `for` wants: `smToYield(m)` is the *identity* for
  a `..T` argument (no wrapper object, no extra step).

What has to change when this lands:

- the parser's desugar wraps the iterated expression (`smToYield(<expr>)`) - and the
  wrap has to be *invisible*: the diagnostic for a non-iterable type names the type, not
  the generated call;
- the sema check becomes "is there a `smToYield` for this receiver?" instead of "is this
  a machine?", and `stress/diagnostic-for-not-a-machine` becomes
  `diagnostic-not-iterable` (a `for` over an `Int`, or over a type with no `smToYield`);
- the loop variable's type comes from `smToYield`'s bound `T`, so a `for` over a
  `List<Str>` binds a `Str` without the machine-typing special cases the type pass
  carries today;
- `specs/functions.md` and `specs/containers.md` say a container *is* iterable, with the
  order being the container's own (and `Span<T>`/index loops staying the way to iterate
  *storage* without allocating an iterator).

It is the last step of the IL work (`impl_specs/linear-il.md`, "Dropping the statement
emitter"): a `smToYield` in the prelude is a `yield`ing function, so it needs `yield` to
work in both rings first - otherwise the Simse ring cannot emit the prelude's own
functions and every program that uses `for` stops transpiling there.

## Status

- Implemented in the **C++ ring**: `Parser::parseFor`/`parseStmtInto`, the two type rules
  above, the sema check, and `docs/examples/yield/src/main.simse`, which runs both forms
  plus `continue`/`break` (verified by transpiling, compiling and running).
- Ported to the **Simse ring** so far: `Scanner.simse` (`..`), `Parser.simse`
  (`parseFor`, `parseStmtInto`, the node builders), and `Sema.simse`
  (`checkForIterable` + `semaTypeText`). Scanner, parser and sema are **byte-identical**
  between the rings on `tools/_ring/probe.simse`, and
  `stress/diagnostic-for-not-a-machine` passes in *both* rings (a rejected program needs
  no codegen, which is why the diagnostic is the first half of this feature to be
  end-to-end in the Simse ring). Codegen does not agree yet: the Simse ring has no
  `yield` lowering, so a program that *runs* a machine is still C++-ring only.
- Both rings of the *IL* agree: `--linearCodegen` reports the `for`-heavy example as
  identical once blocks are folded, and `--linearCodegenEmit` compiles and runs it.
- **Still to port** for the Simse ring, in this order:
  1. `linear/Yield.simse` - the state-machine rewrite of `linear/Yield.cpp` (423 lines);
  2. `Codegen.simse` - `emitYieldable`, the factory, and the `..T` return type (the C++
     ring's `Codegen.cpp` has ~30 places that know about machines);
  3. `TypeInfer.simse` - the two machine rules and the `Yield` case of `semSubstitute`;
  4. then a `stress/` case that *runs* both forms, which is the proof that the port is
     done - and the `tests/fixtures` entry that makes all five differentials cover it.
  (Separately, `linear/LinearForm.cpp` - the IL, 1358 lines - has no Simse mirror at
  all; it is behind `--linearCodegen*` flags, so it is a gap of its own.)
- The template's names are recognizable, which is what makes the sema check possible;
  they are documented as not a user's to take, like the lowering's `_sm_expr<n>` slots.
- **Not supported yet**, both reported as diagnostics rather than left to the C++ compiler:
  - a `for` (or any machine local) inside a body that yields - the machine would have to be
    a *field*, and a field needs a nameable type ("collect the values into a `List`
    first"). Making it work means giving a machine a spellable name, e.g. from the creating
    function's class at the declaration site;
  - `yield` in a **generic** function: the machine class is not a template yet, so its
    fields could not be the type parameters.
