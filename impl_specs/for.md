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

## What the compiler says about a `for`

The parser cannot tell what a `for` iterates, so the check lives in sema's checker
(`Analyzer::checkForIterable`): the template's machine name is recognizable (`_sm_for<n>`,
the same convention as the lowering's `_sm_expr<n>` slots), the initializer is the
invisible `smToYield()` wrap, and when the *receiver's* type is known and is neither a
machine nor a type with a `smToYield`, that is the error - at the `for`, naming the type
the user wrote, not the generated call:

```text
stress/diagnostic-not-iterable/src/main.kt:11:5: a `for` iterates a machine (`..T`)
or a type with a `smToYield`, and Int has neither; iterate a container with `while` and
an index
```

An unknown receiver type stays silent (the C++ compiler gets the last word, as it does
for any other member), so the check never fires on something the checker cannot name.

## `smToYield`: what can be iterated

**Landed** (see "Status" below for what it needed). The construct stays two forms -
`for (v in x)` and `for ((v, i) in x)` - but *what can be iterated* is a convention
instead of "a machine": the iterated expression is wrapped in an invisible call to a
function named **`smToYield`**, so

```simse
for (item in x) { ... }      // is the same program as
for (item in x.smToYield()) { ... }
```

and anything the language can find a `smToYield` for is iterable. The call is a
*member* call (`x.smToYield()`, not `smToYield(x)`): the type pass binds a receiver
function's type parameter from the receiver, which is what types the loop variable.

The prelude's, as it is written, one per container - with `Array<T>` counting with
`count()` and `Span<T>` with `size()`:

```simse
fun List<T>.smToYield<T>(): ..T {
    var i: Int = 0
    while (i < this.size()) {
        yield this[i]
        i = i + 1
    }
}
```

**A machine's class carries its receiver's name** (`List_smToYield_yieldable`,
`Array_smToYield_yieldable`): the prelude has one `smToYield` per container, so the
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
- **A machine**, which is already what `for` wants: `x.smToYield()` on a `..T` receiver is
  the *identity* (no wrapper object, no extra step). `..T` is not a spellable type, so the
  identity is the compiler's rather than a function's.
- **A range, later**: `for (i in (2 .. 5))` becomes an iterator over the two bounds, i.e.
  one more `smToYield` whose machine holds `2` and `5` as its parameters.

What remains: `smToYield` for `Dictionary<K, V>` (a `while` over `keys()` is how it is
walked today; what a dictionary's element should be - its keys, or a key/value pair - is
the open question), and the range above.

## Status

**Implemented in both rings.** `for (x in source)` is `source.smToYield()` plus the
`while` the parser writes (`parseFor`), the prelude provides `List<T>.smToYield()` - a
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
- **the wrap is a member call** (`source.smToYield()`): the type pass binds a receiver
function's type parameter from the receiver (`memberReturn` -> `bindTypes`), so the loop
variable is typed, while a plain `smToYield(source)` would leave it untyped. `Yield`
patterns unify and bind like a pointer's pointee (`sema::unifyType`, `bindTypes`) for
the same reason.
- **the identity is the compiler's**: `..T` is not a spellable type, so no function can
take a machine. `TypeInfer` types `x.smToYield()` on a `..T` receiver as the receiver,
and the emitter emits the receiver itself - no wrapper object, no extra step.
- **the gate is `Sema.kt`'s**: the wrap must resolve, so the check asks "is this a
machine, or a type with a `smToYield`?" and names the receiver's type otherwise
(`stress/diagnostic-not-iterable`). It compares receiver *names* (`List<T>` takes any
`List<...>`), because that is all a diagnostic needs and the call itself is resolved
with the full unification in `codegen`.
- **a prelude body is emitted when the program reaches it by name**: prelude `fun`s were
declarations-only, and `List<T>.smToYield` is the first one with a body. The rule is a
name reachability over the calls (a callee is a `Name`, a `GenericName` or a `Member`),
closed over the prelude bodies that are themselves emitted - so a program that never
iterates carries none of it, and the goldens do not move.
- **two rules the machine needed, found by the two rings disagreeing**: the dispatcher
jumps go *after* the method's hoisted declarations (a jump that skips a `T` declaration
is `C2362`, and `T` is non-trivial for `Str`); and `ExpressionLowering` hoists a
`yield`'s value like a `return`'s (`ExpressionLowering.kt` was missing that case,
which meant the Simse ring inlined where the C++ ring hoisted).

Still open:

- **other containers**: `Dictionary<K, V>` has no `smToYield` yet (a `while` over
  `keys()` is the way to walk it today); `Array<T>` and `Span<T>` landed with it.
- **ranges**: `for (i in (2 .. 5))` - one more `smToYield` whose machine holds the two
  bounds.
- **a `for` inside a yielding body**: the machine would have to be a *field*, and a
  field needs a nameable type (`impl_specs/yield.md`).

What the earlier plan listed, and what happened to it:

- ~~the machine class must become a template~~ - done (above).
- ~~the wrap must be a member call~~ - done.
- ~~machine identity needs a `Yield` case in `unifyType`/`bindTypes`~~ - done (it is
  what makes `m.smToYield()` resolve; the identity itself turned out to be the
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
