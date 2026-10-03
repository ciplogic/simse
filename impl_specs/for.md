# `for`: a `while` in disguise

`for` is a **syntactic** construct. The parser desugars it, so no stage downstream - sema's
checker, the linear pass, the emitters, the IL - has a `for` statement kind to know about, and
`break`/`continue` are the `while`'s own machinery.

```text
for (v in m) { body }              var _sm_for1 = m.iter()
                                   while (_sm_for1.advance()) {
                                       val v = _sm_for1.current
                                       body
                                   }

for ((v, i) in m) { body }         var _sm_for1 = m.iter()
                                   var _sm_index1: Int = -1
                                   while (_sm_for1.advance()) {
                                       _sm_index1 = _sm_index1 + 1
                                       val v = _sm_for1.current
                                       val i = _sm_index1
                                       body
                                   }
```

A `*` on the variable is the same template with the *pointer* wrap
(`m.iterPtr()`), whose element type is `*T` - so `v` is the element's place, not a
copy of it ("`iterPtr`" below).

## Where it runs, and why there

`parseFor` (`src/parser/Parser.kt`), reached through `parseStmtInto` - the one statement
slot that can expand to *several* statements, because the machine has to be declared outside
the loop it runs. Everything the template generates carries the `for` token's position, so a
diagnostic - from sema or the C++ compiler - points at the line the user wrote.

- **The advance is the loop's condition, and the body starts with the value.** `advance()` is
  the `while` condition, so the loop moves on where the step used to be, and the body's first
  statement reads what it left in `current` (`val v = _sm_for1.current`, one field read).
  `continue` jumps to the condition, the machine's own step, so a skipped iteration still moves
  the machine on.
- **The index starts at `-1` and is pre-incremented as the body's first statement**, for the
  same reason: an index incremented at the *end* of the body would miss every `continue`-skipped
  iteration. `-1` makes the pre-increment hand out `0` first.

The index the user names (`i`) is bound per iteration from the counter (`val i = _sm_index1`),
so the loop's variables are fresh per iteration, cannot be assigned, and the counter is the
template's. Its extra increment on the exhausted iteration is never read again.

## The machine's interface, and the one rule sema needed

`..T` is deliberately **not spellable on its own** (`spellable()` refuses a nameless one),
but the type pass *names* the machine at the call site (`semMachineType`): the class is the
creating function's, qualified by its package and prefixed with the receiver's outer type
(`Span_iterPtr_yieldable<T>`), so a binding's declaration spells the class rather than falling
back to `auto`. A name bound to a machine therefore stays `auto`.

So the lowering-time type pass (`src/sema/TypeInfer.kt`) knows the *one* method of a
machine's protocol and its one field, which is all the template reads:

| receiver | name | type |
| --- | --- | --- |
| `..T` | `advance()` | `Bool` |
| `..T` | `current` (field) | `T` (the `..T`'s inner) |

With that, `_sm_for1` is the machine, `v` is `Int`, and every temporary the linear pass made
from them is typed too. Reading `current` directly makes `v` a typed binding; the emitter needs
no new IL op (`_sm_for1.current` is a plain `GetField`) and the machine has no `value()` to
call, so the element is copied *once* into `v` rather than twice (field into the method's
return, return into `v` - `impl_specs/yield.md`, "One protocol"). `TypeKind::Yield` also
substitutes like a pointer does (`substituteBindings`), or a generic function's `..T` loses its
element type before reaching that rule.

## What the compiler says about a `for`

The parser cannot tell what a `for` iterates, so sema's checker (`Analyzer::checkForIterable`)
uses the recognizable machine name (`_sm_for<n>`, the lowering's `_sm_expr<n>` convention) and
the invisible `iter()` wrap: a known receiver that is neither a machine, a type with an `iter`,
nor a container with a span view (below) is the error - at the `for`, naming the type the user
wrote, not the generated call:

```text
stress/diagnostic-not-iterable/src/main.kt:11:5: a `for` iterates a machine (`..T`)
or a type with an `iter`, and Int has neither; iterate a container with `while` and
an index
```

An unknown receiver type stays silent (the C++ compiler gets the last word).

## `iter`: what can be iterated

The construct stays two forms - `for (v in x)` and `for ((v, i) in x)` - but *what can be
iterated* is a convention instead of "a machine": the iterated expression is wrapped in an
invisible call to a function named **`iter`**, so

```simse
for (item in x) { ... }      // is the same program as
for (item in x.iter()) { ... }
```

and anything the language can find an `iter` for is iterable. The call is a *member* call
(`x.iter()`, not `iter(x)`): the type pass binds a receiver function's type parameter from the
receiver, which is what types the loop variable.

**The prelude declares exactly one `iter`, on the span**, and a *container* is rewritten to
iterate its span first (`SemaCall.spanForAt`, in sema, on the desugared `_sm_for<n>`):

| receiver | rewrite | element |
| --- | --- | --- |
| `List<T>` | `spanOf(x)` | `T` |
| `Array<T>` | `spanOfArray(x)` | `T` |
| `Str` | `spanOfStr(x)` | `Char` |
| `Span<T>` / `StrView` | already a span | `T` |

so `for (x in list)`, `for (x in array)` and `for (ch in text)` walk the *same* machine
(`Span<T>.iter` below), and a program carries one iterator class (`Span_iter_yieldable<T>`)
rather than one per container. The lowering hoists the view into a function-scope slot, so
`for (x in makeList())` iterates storage that outlives the loop (`stress/collections`'s
`for-temporary` part).

```simse
fun Span<T>.iter<T>(): ..T {
    var i: Int = 0
    val len = this.size();
    while (i < len) {
        yield this[i]
        i = i + 1
    }
}
```

The length is read **once**, before the loop, and lives in a machine field (`len`): the `while`
ends up inside `advance()`, so a `this.size()` in its condition would be a call per element
(re-read on every resume) where the length is a constant of the walk.

**A machine's class carries its receiver's name** (`Span_iter_yieldable<T>`, and for a
container the receiver *is* the rewritten span): `semMachineType` builds it from the function
and the receiver's outer type, which is what makes the `_sm_for<n>` slot spellable. The
rewritten call resolves like any other (`Span<T>.iter`), so a program that iterates a list
carries the span machine and nothing of the container.

For the rewrite to fire, the checker has to know the iterated expression's type. `exprType`
(`src/sema/SemaCall.kt`) resolves the shapes a loop nest actually reads through: a tracked
binding (with `this`), a literal (`val s = "x"` is a `Str`), a field read (`this.functions`,
`box.items`), a machine's element (`_sm_for1.current`), a member call (`xs.toArray()`,
`words.keys()`), and a construction. A loop variable binds the element of the machine its
`...current` comes from, so `for (line in lines) for (ch in line.text)` resolves the inner
receiver through the outer variable.

- **A user's own type**, by the same convention (an extension function returning `..T`),
  which is the static-interface idea: iteration is *satisfied* by a function the type's
  author writes (`fun Point.walk(): ..Point`), not by a runtime interface. The machine is
  reified per element type like any other generic function, so the "interface" is the
  *shape* and its witness is a concrete class.
- **A machine**, which is already what `for` wants: `x.iter()` on a `..T` receiver is
  the *identity* (no wrapper object, no extra step). `..T` is not a spellable type, so the
  identity is the compiler's rather than a function's.
- **A range, later**: `for (i in (2 .. 5))` becomes an iterator over the two bounds, i.e.
  one more `iter` whose machine holds `2` and `5` as its parameters.

What remains: `iter` for `Dictionary<K, V>` (a `while` over `keys()` walks it; what a
dictionary's element should be - its keys, or a key/value pair - is the open question), and the
range above.

## `iterPtr`: iterating without copying

`iter` hands out *values*, so `for (v in xs)` copies each element into `v` - a copy per
iteration for a container of aggregates, where a hand-written `while (i < xs.size())` +
`*xs[i]` loop borrows the element in place. The pointer form closes that gap and takes the
index bookkeeping with it:

```simse
for (*cell in cells) {            // cells: List<Cell>
    cell.value = cell.value + 1   // reads and writes the Cell in the list
}
for ((*cell, i) in cells) { ... }  // the same, plus the iteration index
```

It is one more *wrap*, not a second `for`: `parseFor` sees the `*` and wraps what is iterated
in `iterPtr()` instead of `iter()`. Everything downstream is existing machinery, because the
machine is generic over its element type and `..*T` is a `..T` whose element is `*T`:

- the element type is the `..T`'s `Inner` (`Codegen`'s `emitYieldable`), so `..*T` gives
  `*T` for the `current` field with no special case;
- `TypeInfer` types `current` as that same `Inner`, so the loop
  variable is typed `*T` - a pointer variable the emitter reads *through* (`cell.value` is
  `cell->value`), which is exactly what a `*T` parameter does everywhere else;
- a machine is still the identity for `iter`, and has none for `iterPtr`: it
  hands out values, not places, so `for (*v in someMachine)` is a diagnostic;
- sema's gate (`checkForIterable`) takes the wrap *name* from the call the parser wrote,
  so it reports the right one (`hasWrap`).

```simse
fun Span<T>.iterPtr<T>(): ..*T {
    var i: Int = 0
    val len = this.size();
    while (i < len) {
        yield *this[i]        // the element's place, not a copy
        i = i + 1
    }
}
```

A container reaches this one through the same span rewrite its value twin uses, so the
pointer form covers every iterable shape with the one machine. The `linq` module
(`src/modules/linq/linq.kt`) builds its operators on this machine directly: a
`..*T.select<T, U>` receiver is itself a pointer machine, so a chain copies no element
(`impl_specs/yield.md`, "A machine as a receiver").

`yield *this[i]` is the language's borrow: `*place` is the place's address and it keeps
the place in place (`ExpressionLowering` never binds a `Deref`'s operand to a value
temporary), so the machine hands out `&(*self)[i]` - into the container's storage.

**The measurement.** The three shapes, on the compiler's own hot loops
(`Linear.kt`'s `declares`/`lowerStmts`/`containsShortCircuit`, release `./simse.exe`
transpiling `--root src`, interleaved A/B, 11 pairs):

| shape | min | median |
| --- | --- | --- |
| `while (i < xs.size())` + `*xs[i]` | 824.3 ms | 839.5 ms |
| `for (*x in xs)` | 826.2 ms | 835.8 ms |
| `for (x in xs)` (the value form) | 810.8 ms | 826.8 ms |

The pointer form is the hand-written loop's cost, without its index - which is why the
compiler's own statement/child walks use it (`stress/collections`' `for-pointer` part
covers the semantics: a write through the loop variable reaches the container, and a
scalar element is read with `*value`).

### Auto-promotion (the value form, where it costs nothing)

The parser picks the wrap before types are known, so the choice is made in sema
(`SemaCall.promoteForLoops`): a `for (x in c)` becomes `iterPtr` when

- the receiver resolves to a `List`/`Array`/`Span` (a container is already its span by this
  point in the pass: the rewrite above runs first) with an `iterPtr` (a machine has none), and
- the element is *deep* - a `Str`, or a data class with a `Str` field (`bpDeepElement`, from
  the borrow pass's `bpDeepClasses` fixpoint) - and
- the body only reads `x` (`bpLoopReadOnly`, the parameter rule: no write through it, no
  `&`/`*`, no capture, no call the fixpoint cannot trust), and `--no-borrow` is off.

The gate matters. On `List<Cell>` (a class with a `Str` field) the value form deep-copies — and
allocates — per element; a read-only walk in the harness probe goes **1,255,621 us -> 27,897 us**
(~45x) once promoted. On `List<Small>` (four `Int`s) the two forms are a wash, and on
`List<AstXmlNode>` the table above has the value form slightly *ahead*, so a scalar, a handle, a
container and a class of those keep the value wrap. A body that writes through the variable is
not promoted (the pointer form would mutate the list). The rewrite is invisible: the same reads,
the same values, one fewer copy. `--no-borrow` disables it with the rest of the rewrite.

Reach is the same coarse one the parameter rule has: a body that calls something the fixpoint
cannot trust (an `append`, an emitter helper) keeps the value form, which is most of the
compiler's own loops — so the promotion is a win for a program's read-only walks, not a
compiler-wide speedup.

## Status

`for (x in source)` is `source.iter()` plus the `while` the parser writes (`parseFor`); the
prelude provides `Span<T>.iter()` (a `yield`ing function in Simse) and a `List`/`Array`/`Str`
receiver is rewritten to its span view first (`spanForAt`); a *machine* is its own identity,
so `for (x in m)` iterates `m` itself.

- The machine's shape and lowering rules are in `impl_specs/yield.md` (`<fn>_yieldable`
  carrying the arguments, `_sm_for<n>` typed by `semMachineType`, the receiver field
  `_sm_self` / `linear::yieldReceiverField`, `emitMachine`, the dispatcher's `C2362` placement,
  `ExpressionLowering`'s `StmtYield`).
- **the wrap is a member call** (`source.iter()`): the type pass binds a receiver function's
  type parameter from the receiver (`memberReturn` -> `bindTypes`), so the loop variable is
  typed; a plain `iter(source)` would leave it untyped. `Yield` patterns unify and bind like a
  pointer's pointee (`sema::unifyType`, `bindTypes`).
- **the gate is `Sema.kt`'s** (`checkForIterable`): it asks "is this a machine, a type with
  an `iter`, or a container with a span view?", names the receiver's type otherwise
  (`stress/diagnostic-not-iterable`), and compares receiver *names* (`List<T>` takes any
  `List<...>`); an unknown receiver stays silent (the C++ compiler gets the last word). The
  call itself resolves with the full unification in `codegen`.
- **the rewrite runs per scope, before the scope pops** (`promoteForLoops` is called at the
  end of a function body, a lambda body, an `if` arm and a `while` body): resolving
  `val xs = ...; for (x in xs)` needs the binding that a body's own scope holds, so a pass
  that ran only at function level could not see it. The walk also reaches the `Body`,
  `Then` and `Else` children, which is what lets it find a `for` inside an `if` arm.
- **a prelude body is emitted when the program reaches it by name**: a name reachability over
  the calls (a callee is a `Name`, `GenericName` or `Member`), closed over the prelude bodies
  themselves emitted, so a program that never iterates carries none of it.

Still open:

- **other containers**: `Dictionary<K, V>` has no `iter` yet (a `while` over `keys()` walks it);
  it is also the one common container with no span view to fall back on.
- **ranges**: `for (i in (2 .. 5))` - one more `iter` whose machine holds the two bounds.
- **a `for` inside a yielding body**: the machine would have to be a *field*, and a field needs
  a nameable type (`impl_specs/yield.md`).
