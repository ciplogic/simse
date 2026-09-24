# `for`: a `while` in disguise (C++ ring)

`for` is a **syntactic** construct. The parser desugars it, so no stage downstream -
sema's checker, the linear pass, the emitters, the IL - has a `for` statement kind to
know about, and `break`/`continue` are the `while`'s own machinery.

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

`Parser::parseFor` (`cppsrc/parser/Parser.cpp`), reached through `parseStmtInto` - the
one statement slot that can expand to *several* statements, because the machine has to be
declared outside the loop it runs. Everything the template generates carries the `for`
token's position, so a diagnostic - from sema or from the C++ compiler - points at the
line the user wrote.

Two details of the shape are load-bearing:

- **The advance is the loop's condition, and the body starts with the value.** The
  machine's `advance()` is the `while` condition, so the loop moves on exactly where the
  step used to be, and the body's first statement reads what it left in `current`
  (`val v = _sm_for1.current`, one field read). `continue` jumps to the condition, which
  is the machine's own step, so a skipped iteration still moves the machine on.
- **The index starts at `-1` and is pre-incremented as the body's first statement**, for
  the same reason: an index incremented at the *end* of the body would miss every
  iteration that `continue` skipped. `-1` is what makes the pre-increment hand out `0`
  first.

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

So the lowering-time type pass (`cppsrc/sema/TypeInfer.cpp`) now knows the *one* method of
a machine's protocol and its one field, which is all the template reads:

| receiver | name | type |
| --- | --- | --- |
| `..T` | `advance()` | `Bool` |
| `..T` | `current` (field) | `T` (the `..T`'s inner) |

With that, `_sm_for1` is the machine, `v` is `Int`, and every temporary the linear pass
made from them is typed too. Reading `current` directly is what makes `v` a typed
binding; the emitter needs no new IL op for it (`_sm_for1.current` is a plain `GetField`)
and the machine has no `value()` to call, so the element is copied *once* into `v`
rather than twice (field into the method's return, return into `v` -
`impl_specs/yield.md`, "One protocol"). `TypeKind::Yield` also had to start substituting
like a pointer does (`substituteBindings`), or a generic function's `..T` would have lost
its element type before reaching that rule.

## What the compiler says about a `for`

The parser cannot tell what a `for` iterates, so the check lives in sema's checker
(`Analyzer::checkForIterable`): the template's machine name is recognizable (`_sm_for<n>`,
the same convention as the lowering's `_sm_expr<n>` slots), the initializer is the
invisible `iter()` wrap, and when the *receiver's* type is known and is neither a
machine nor a type with an `iter`, that is the error - at the `for`, naming the type
the user wrote, not the generated call:

```text
stress/diagnostic-not-iterable/src/main.kt:11:5: a `for` iterates a machine (`..T`)
or a type with an `iter`, and Int has neither; iterate a container with `while` and
an index
```

An unknown receiver type stays silent (the C++ compiler gets the last word, as it does
for any other member), so the check never fires on something the checker cannot name.

## `iter`: what can be iterated

**Landed** (see "Status" below for what it needed). The construct stays two forms -
`for (v in x)` and `for ((v, i) in x)` - but *what can be iterated* is a convention
instead of "a machine": the iterated expression is wrapped in an invisible call to a
function named **`iter`**, so

```simse
for (item in x) { ... }      // is the same program as
for (item in x.iter()) { ... }
```

and anything the language can find an `iter` for is iterable. The call is a
*member* call (`x.iter()`, not `iter(x)`): the type pass binds a receiver
function's type parameter from the receiver, which is what types the loop variable.

The prelude's, as it is written, one per container - with `Array<T>` counting with
`count()` and `Span<T>` with `size()`:

```simse
fun List<T>.iter<T>(): ..T {
    var i: Int = 0
    val len = this.size();
    while (i < len) {
        yield this[i]
        i = i + 1
    }
}
```

The length is read **once**, before the loop, and lives in a machine field (`len`): the
`while` ends up inside `advance()`, so a `this.size()` in its condition is a call per
element - re-read on every resume - where the length of a container the loop does not
change is a constant of the walk.

**A machine's class carries its receiver's name** (`List_iter_yieldable`,
`Array_iter_yieldable`): the prelude has one `iter` per container, so the
function name alone would name every container's machine the same way. And because the
name is the receiver's, **a prelude body is emitted for the receiver the program names**:
the reachability over the program's calls (by name) is closed over the types it spells,
which includes the signatures of the prelude functions it calls - `xs.toArray()` reaches
an `Array` because the prelude says `toArray` returns one. A program that iterates a list
therefore carries the list machine only, not every container's.

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

What remains: `iter` for `Dictionary<K, V>` (a `while` over `keys()` is how it is
walked today; what a dictionary's element should be - its keys, or a key/value pair - is
the open question), and the range above.

## `iterPtr`: iterating without copying

`iter` hands out *values*, so `for (v in xs)` copies each element into `v` - for a
container of aggregates that is a copy per iteration, while the hand-written
`while (i < xs.size())` + `*xs[i]` loop the compiler used to write borrowed the element in
place. The pointer form closes that gap and takes the index bookkeeping with it:

```simse
for (*cell in cells) {            // cells: List<Cell>
    cell.value = cell.value + 1   // reads and writes the Cell in the list
}
for ((*cell, i) in cells) { ... }  // the same, plus the iteration index
```

It is one more *wrap*, not a second `for`: the parser's `parseFor` sees the `*` and wraps
what is iterated in `iterPtr()` instead of `iter()`. Everything downstream is
the machinery that already existed, because the machine is generic over its element type
and `..*T` is a `..T` whose element is `*T`:

- the element type is the `..T`'s `Inner` (`Codegen`'s `emitYieldable`), so `..*T` gives
  `*T` for the `current` field with no special case;
- `TypeInfer` types `current` as that same `Inner`, so the loop
  variable is typed `*T` - a pointer variable the emitter reads *through* (`cell.value` is
  `cell->value`), which is exactly what a `*T` parameter does everywhere else;
- a machine is still the identity for `iter`, and has none for `iterPtr`: it
  hands out values, not places, so `for (*v in someMachine)` is a diagnostic;
- sema's gate (`checkForIterable`) takes the wrap *name* from the call the parser wrote,
  so it reports the right one (`hasWrap`).

The prelude writes one per container, next to its value twin:

```simse
fun List<T>.iterPtr<T>(): ..*T {
    var i: Int = 0
    val len = this.size();
    while (i < len) {
        yield *this[i]        // the element's place, not a copy
        i = i + 1
    }
}
```

`yield *this[i]` is the language's borrow: `*place` is the place's address and it keeps
the place in place (`ExpressionLowering` never binds a `Deref`'s operand to a value
temporary), so the machine hands out `&(*self)[i]` - into the container's storage.

**The measurement.** The three shapes, on the compiler's own hot loops
(`Linear.kt`'s `declares`/`lowerStmts`/`containsShortCircuit`, release `./simse.exe`
transpiling `--root cppsrc`, interleaved A/B, 11 pairs):

| shape | min | median |
| --- | --- | --- |
| `while (i < xs.size())` + `*xs[i]` | 824.3 ms | 839.5 ms |
| `for (*x in xs)` | 826.2 ms | 835.8 ms |
| `for (x in xs)` (the value form) | 810.8 ms | 826.8 ms |

The pointer form is the hand-written loop's cost, without its index - which is why the
compiler's own statement/child walks use it (`stress/for-pointer` covers the semantics:
a write through the loop variable reaches the container, and a scalar element is read
with `*value`).

## Status

**Implemented in both rings.** `for (x in source)` is `source.iter()` plus the
`while` the parser writes (`parseFor`), the prelude provides `List<T>.iter()` - a
`yield`ing function, written in Simse - and a *machine* is its own identity, so
`for (x in m)` still iterates `m` itself. What that needed beyond the wrap, and where
it lives:

- **a generic function can yield**: the machine class is a template when the function has
type parameters (`template <class T> struct <fn>_yieldable`), and its *name* carries the
arguments wherever it is a type (the factory's return type and prototype, its
`machine{}` local). Inside the class the injected-class-name covers `self`, and a call
site's `_sm_for<n>` slot is `auto`, so nothing else names it.
- **the receiver lives in the machine** (`_sm_self`, `linear::yieldReceiverField`): an
extension function's `this` crosses a yield like any other value, so `this.x` reads the
caller's object through a field that holds exactly what the emitted `self` parameter
holds - a pointer for a value receiver. `this` in a *base* position stays that field
(the spellings dereference where they have to: `this._sm_self->size()`,
`(*this._sm_self)[i]`), and a bare `this` in a value position is read back out of it.
The machining methods' bodies are typed against it: `emitMachine` registers the machine
as a data class in the emitter's type table, which is what tells `memberAccess` and the
index spelling what `this._sm_self` is.
- **the wrap is a member call** (`source.iter()`): the type pass binds a receiver
function's type parameter from the receiver (`memberReturn` -> `bindTypes`), so the loop
variable is typed, while a plain `iter(source)` would leave it untyped. `Yield`
patterns unify and bind like a pointer's pointee (`sema::unifyType`, `bindTypes`) for
the same reason.
- **the identity is the compiler's**: `..T` is not a spellable type, so no function can
take a machine. `TypeInfer` types `x.iter()` on a `..T` receiver as the receiver,
and the emitter emits the receiver itself - no wrapper object, no extra step.
- **the gate is `Sema.kt`'s**: the wrap must resolve, so the check asks "is this a
machine, or a type with an `iter`?" and names the receiver's type otherwise
(`stress/diagnostic-not-iterable`). It compares receiver *names* (`List<T>` takes any
`List<...>`), because that is all a diagnostic needs and the call itself is resolved
with the full unification in `codegen`.
- **a prelude body is emitted when the program reaches it by name**: prelude `fun`s were
declarations-only, and `List<T>.iter` is the first one with a body. The rule is a
name reachability over the calls (a callee is a `Name`, a `GenericName` or a `Member`),
closed over the prelude bodies that are themselves emitted - so a program that never
iterates carries none of it, and the goldens do not move.
- **two rules the machine needed, found by the two rings disagreeing**: the dispatcher
jumps go *after* the method's hoisted declarations (a jump that skips a `T` declaration
is `C2362`, and `T` is non-trivial for `Str`); and `ExpressionLowering` hoists a
`yield`'s value like a `return`'s (`ExpressionLowering.kt` was missing that case,
which meant the Simse ring inlined where the C++ ring hoisted).

Still open:

- **other containers**: `Dictionary<K, V>` has no `iter` yet (a `while` over
  `keys()` is the way to walk it today); `Array<T>` and `Span<T>` landed with it.
- **ranges**: `for (i in (2 .. 5))` - one more `iter` whose machine holds the two
  bounds.
- **a `for` inside a yielding body**: the machine would have to be a *field*, and a
  field needs a nameable type (`impl_specs/yield.md`).

What the earlier plan listed, and what happened to it:

- ~~the machine class must become a template~~ - done (above).
- ~~the wrap must be a member call~~ - done.
- ~~machine identity needs a `Yield` case in `unifyType`/`bindTypes`~~ - done (it is
  what makes `m.iter()` resolve; the identity itself turned out to be the
  compiler's, since `..T` cannot be a parameter type).
- ~~the prelude needs both functions above~~ - one function, plus the reachability rule.
- the wrap is invisible in diagnostics - it is: the gate reports the *receiver's* type.
- `specs/functions.md` and `specs/containers.md` say a container *is* iterable - done.
- **Not supported yet**, both reported as diagnostics rather than left to the C++ compiler:
  - a `for` (or any machine local) inside a body that yields - the machine would have to be
    a *field*, and a field needs a nameable type ("collect the values into a `List`
    first"). Making it work means giving a machine a spellable name, e.g. from the creating
    function's class at the declaration site;
  - `yield` in a **generic** function: the machine class is not a template yet, so its
    fields could not be the type parameters.
