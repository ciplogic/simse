# Functions

Status: design baseline — top-level functions for the first implementation.

## Scope

Functions are declared at module/file scope or in a class body: an ordinary top-level
function, or a receiver function (with a receiver type, called with member-call syntax) -
at top level as an extension function or inside a class body as a class method. Extension
functions and class methods lower to the same static function shape, are not virtual, and
have no interface dispatch.

## Ordinary functions

The baseline declaration form is:

```text
fun functionName(parameter: Type, other: Type): ReturnType {
    // body
    return expression
}
```

For a function that returns no useful value, omit the return type or write `Unit`; both
forms mean the same thing. A non-`Unit` function must return a value on every reachable
path. A `Unit` function may use `return` without a value or reach the closing brace.

Functions use ordinary value semantics for parameters, so passing a `List<T>`, `Str`, or
data-class value copies it according to that type's value semantics. Use `&T` or `*T`
explicitly when sharing or borrowing is intended: `&T` keeps its box alive through the
call, while `*T` is non-owning and may be null or dangling, subject to the raw-pointer
rules in `memory-model.md`.

## Packing the trailing arguments

Status: implemented; the call site is one `Pack` instruction
(`impl_specs/linear-il.md`).

A call packs its **trailing arguments** into a temporary list when its callee's
**last parameter is a list** - by value (`List<T>`) or borrowed (`*List<T>`), so
`addAll(1, 2, 3)` passes one list, `addAll()` the empty list, and `format("k", "a", "b")`
packs everything past the shape.

- The list is built by **one instruction** (`Pack`, the RTL's initializer-list
  construction) and `List<T>` is `SmallVector<T, 4>`, so a **packed list of up to four
elements allocates nothing**: it lives in the temporary's inline buffer.
- A `*List<T>` parameter is passed the **address** of that temporary, which is a slot of
the caller's frame - so a packed call copies each element once and the list not at all.
- **Each element is a value**, so it converts the way a by-value parameter's argument does
  (`memory-model.md`, "Automatic dereference"): a handle among the trailing arguments is
  read through to its pointee, not stored as a pointer, so `format(template, a, b)` accepts
  a `*Str` `a`.
- **One argument for one parameter is the list itself**, whatever its handle form:
  `addAll(*xs)` passes the list, it does not build a one-element list of a list. That is
  what tells `addAll(*xs)` from `addAll(1)` when both have one argument.
- The **counted forms are not pack targets**: `&List<T>` and `PList<T>` (which *is*
  `&List<T>`, a `std::shared_ptr`) would make a packed temporary pay for a control block and
  a reference count. A call that would pack into one is the arity error it always was:

  ```text
  fun sum(values: &List<Int>): Int { ... }
  sum(1, 2, 3)   // error: no overload of 'sum' takes 3 argument(s)
  ```

  The zero-copy spelling of the same call is the borrow, `fun sum(values: *List<Int>)`.
- The rule is about the *last parameter* only: a call that already fits, such as `f(a, xs)`
  with counts matching, is passed as it is.
- A **list literal** is the same instruction spelled directly: `listOf<Str>("a", "b",
  "c")` builds the list from those values, in that order (`specs/containers.md`). It is
  the one call the compiler never emits a call for. `List<T>(...)` is the RTL's own
  construction, a *count* of elements, so a literal can never be mistaken for a size.
- A `List<T>` argument the callee takes **by value** is copied into the parameter, as
  value semantics require; the pack still builds the list once, and the copy is of a small
  list that usually lives inline.

## Handles at a call

A parameter that wants a handle accepts a value of the same type, and the compiler
converts the argument at the call (`memory-model.md` owns what the handles mean):

| parameter | argument | the call passes |
| --- | --- | --- |
| `*T` | `T` | the argument's **address** - a borrow, no copy |
| `*T` | `&T` | the reference's pointee |
| `T` | `*T` or `&T` | a **copy of the pointee** |
| `&T` | `T` | a **copy in a box** - which is what `&x` means |
| `&T` | `*T` | **nothing**: this is an error (below) |

- **The address is the argument's own place.** `addAll(xs)` borrows `xs`, `f(a[0])`
  borrows the element, `f(p.field)` the field - never the address of a temporary the
  compiler made on the way (a callee that writes through the pointer writes into the
  caller's object - `stress/pointer-place`). A temporary (`f(g())`) is the one case
  where the address is taken at the call, and it is valid for that call.
- **A parameter can change from a copy to a borrow.** Changing `fun addAll(values:
  List<Int>)` to `fun addAll(values: *List<Int>)` leaves every existing `addAll(xs)` call
  compiling, and copying nothing; the reverse change compiles too.
- **The types still have to match.** `addAll(aListOfStr)` against `*List<Int>` is not
  converted: only the *handle* is inferred, never the type, so the call is the type error
  it always was.
- **A construction converts the same way.** `Rect(w, h)` where a field is `Int` and `w`
  came back as a `*Int` from an accessor reads it through like any call argument - the
  constructor is a call boundary too. Where the callee's own signature cannot name the
  parameter's type (a generated extension's bare type parameter, `Dictionary<K, V>.has(key:
  K)`), the argument's own type is what the conversion reads, which makes
  `names.append(accessor(...))` an element copy rather than a complaint.
- **A `*T` binding is not converted.** `val p: *List<Int> = xs` still writes the `*`; the
  convenience above is for a call, whose borrow ends with it.
- **A raw pointer cannot become a counted reference in place.** `&T` counts a *box*, and a
  `*T` argument is a pointer to somebody's storage, so there is nothing to count. The call
  is reported rather than silently copied, because the two spellings differ (a copy of that
  storage, or a share the language has no box for); the writer says which one they mean,
  one line before the call:

  ```text
  fun printRef(v: &Int): Int { ... }

  var v: Int = 1
  var vptr: *Int = *v
  printRef(vptr)                 // error: a pointer cannot become a reference in place

  var boxed: &Int = &v           // a reference to a copy of `v`
  printRef(boxed)                // accepted
  printRef(v)                    // accepted: the compiler boxes the value
  ```

## Extension functions

An extension function places the receiver type before the function name:

```text
fun Str.firstByte(): Opt<uint8> { ... }
fun Point.magnitudeSquared(): Int { return this.x * this.x + this.y * this.y }
```

They are called with member syntax (`point.magnitudeSquared()`, `"hello".firstByte()`). The
receiver is an ordinary parameter in the generated function, and `this` refers to it.
Extension functions do not modify the receiver type, add fields, participate in dynamic
dispatch, or gain privileged access to private representation. A mutable receiver must be
declared with the appropriate reference/value form:

```text
fun List<Int>.clearInPlace(): Unit { this.clear() }
fun (*List<Int>).clearThroughPointer(): Unit { this.clear() }
```

The exact mutability checks for extension receivers follow normal `var`/`val`
and pointer rules; the extension declaration does not bypass them.

## Pure functions (`data`)

A `data` modifier on a function asserts that it is **pure**: no side effects, and its result
is a function of its receiver and its arguments alone. The compiler trusts the claim - it is
the only source of truth for a body-less `@SmGen` function - and uses it to fold a repeated
call of an unchanged argument into the first one, so

```simse
data fun Str.toLen(): Int {
    return this.size()
}

fun twice(s: Str): Int {
    return s.toLen() + s.toLen()   // one call: nothing writes `s` between them
}
```

emits one `toLen(s)` and adds the slot twice. A function without the mark keeps every call,
however alike two look - the mark is a promise, not a guess, and one that writes through a
pointer or a file-level `var` would make the reuse wrong.

The mark sits on the declaration: `data fun f(...)`, `data fun T.m(...)`, and an attribute
with it (`@SmGen(...)` on the line above, or `data` first). The language's own read-only
length accessors (`size`, `count`) are `data` declarations too (`src/rtl/rtl.kt`), so the
optimizer lists no name by hand. Purity is about
*observable* effects, so writes to the function's own locals do not matter. It is not
*checked* yet - a later pass computes purity from the body (only pure calls, no write through
a `*T`/`&T` parameter or a file-level `var`) and can then flag an over-claimed `data`.

## Read-only functions (`borrow`)

`borrow` asserts a *weaker* promise than `data`: the function reads its receiver and its
parameters and never writes through them, so a caller may hand any of them to it by pointer. It
says nothing about the result or about other effects - a `borrow fun` may write its own locals,
build and return a fresh value, or print (which is why it is not `data`).

```simse
@SmGen("res", "strops", "simse_str_charAt")
borrow fun charAt(this: Str, index: Int): Char
```

The compiler reads it where a pointer is handed to a callee. The auto-borrow proof
(`src/parser/BorrowParams.kt`, `impl_specs/escape-analysis.md`) trusts a call by *name* only
when **every** declaration with that name is borrow-clean - it writes nothing observable, so it
cannot write through what it is given. A body-less (`@SmGen`) declaration is borrow-clean only
by a mark, because its C++ is elsewhere; for a declaration *with* a body the same fact is
**proved** from the body (it writes nothing and calls only borrow-clean names), so the RTL's
Simse bodies carry no mark at all - the proof covers them. A `borrow` is written where the
proof cannot read the body: a body-less native, or an overload whose sibling is one.
The prelude is rewritten like a module; a prelude name hand-written C++ may call is the one
case that keeps its authored signature (`bpCppCalled`), because that C++ is compiled against
it. A declaration is rewritten only when its name is trusted and is never used as a value, so
a name the proof cannot cover keeps the signature its callers resolve against.

Like `data`, the mark is a promise the compiler trusts; unlike `data` it never lets a call be
folded. The RTL writes it on the declarations the proof cannot read (its bodyless natives); its
Simse bodies need no mark.

## Operator functions (`operator`)

`operator` marks an indexer - the Kotlin convention, and the modifier is contextual like
`borrow`: a program may still use `operator` as an ordinary name.

```kt
data class Span2<T>(var items: List<T>) {
    operator fun get(index: Int): T { return this.items[index] }

    operator fun set(index: Int, value: T): Unit { this.items[index] = value }
}

operator fun Point.get(index: Int): Int { ... }
operator fun Point.set(index: Int, value: Int): Unit { ... }
```

The two spellings an indexer has are the two an extension has: a method in the class body,
or an extension (`fun T.get(...)`, `this: T`). The names and their shapes are fixed:

- `get` takes the index and answers the element: `x[i]` is `x.get(i)`;
- `set` takes the index and the value and answers nothing: `x[i] = v` is `x.set(i, v)`,
  and `x[i] op= v` reads through `get` and writes through `set`.

A declaration whose receiver has no `operator get`/`set` is refused rather than silently
ignored (a name without a lowering, a `get` with the wrong number of parameters, or an
operator with no receiver). When a type declares neither, the index syntax keeps its
built-in meanings (a container's element, a `Str`'s `Char`).

An index read through `operator get` is a **value**: the getter answers a fresh `T`, so
`x[i]` is not a place into `x` - it cannot be assigned to through an alias and it may be
passed to a `borrow` parameter only as a materialized temporary. A write needs its own
`operator set`; `x[i] = v` on a type with only `get` is an error.

The prelude's view declares both indexers, so `span[i]` and `span[i] = v` on a
`Span<T>`/`StrView` resolve through `src/rtl/Span.kt`'s `operator fun get`/`set` (the
class body itself is the hand-written header's documentation). The place form is the
span's own `atPtr(i): *T`, which reads through the pointer field because the index read
is a value.

## Methods inside classes

Methods written inside a `class` or `data class` body are equivalent to extension
functions for code generation: static functions with an explicit receiver parameter, where
the containing class supplies only the receiver type and the method's qualified name. The
method is lowered to an extension function (`fun Point.magnitudeSquared(this: Point): Int`)
and called with member syntax. The exact generated C++ name is compiler-controlled. A
method does not receive an implicit object/vtable pointer, and the class does not acquire
runtime method metadata. Method lookup is static and resolved from the compile-time
receiver type.

The following features are not supported yet:

- virtual dispatch;
- interfaces or interface implementation;
- method overriding;
- dynamic method lookup; and
- abstract methods.

## Generic functions

Generic functions are reified at each used type-argument combination (`generics.md`): the
generated C++ contains a concrete specialization for each emitted instantiation. Type
parameters are declared after the name, must be declared before use, and are inferred
from arguments where possible:

```text
fun identity<T>(value: T): T { return value }
fun count<T>(items: List<T>): Int { return items.size() }
```

Generic extension functions use a type parameter list and a receiver parameter, the
receiver written as an explicit `this`:

```text
fun firstOrNone<T>(this: List<T>): Opt<T> { ... }
```

This is an extension function even though the receiver is written in the parameter list:
the special parameter name `this` enables member-call syntax. Generic function type
aliases are in `memory-model.md`, for example `typealias Action<T> = (T) -> Unit`.

A call fixes each type parameter from, in order: the receiver (for an extension), the
call's own explicit type arguments (`identity<Int>(7)`), and the types of the arguments.
The result is the callee's return type with those bindings substituted, so `twice(f, 5)`
is an `Int`, and a method called on it resolves against `Int`.

A **callable** parameter is the one position the arguments cannot fix a type parameter
through: `fun peek<T>(f: (T) -> T)` has a `T` that no argument names directly. The compiler
binds it from the lambda's own annotations (`peek((x: Int) -> x + 1)` is `T = Int`) or from
the type of a callable value passed there (`peek(fn)`), and emits the instantiation the C++
call cannot deduce (`peek<Int>(...)`). A call that leaves a type parameter nothing fixes at
all - most often a lambda with no parameter type written - is a positioned diagnostic
rather than a C++ error, with `peek<Int>(...)` as the escape.

A lambda argument that fits *several* declarations of one name at once - a plain `T`
parameter takes the closure as a value, a callable parameter as a lambda - is an `ambiguous
call` diagnostic as well, because C++ would silently prefer the plain parameter. An
explicit instantiation (`pick<Int>(lambda)`) chooses the callable one.

A `Str` literal passed where the parameter is a bare type parameter is a `Str`; it is not
narrowed to its view (`StrView`).

## Class-body limitations

Class bodies are parsed as balanced regions. Method declarations inside them are compiled
as static receiver functions, as described above. The fields in the `data class` header
remain part of the type; the body does not declare additional fields, initializers, nested
types, or arbitrary executable statements. Methods are the one supported class-body
declaration.

Implementations should either skip unsupported body constructs as balanced token regions
or report a clear unsupported-body diagnostic. They must not partially compile unsupported
non-method code.

## Modules and imports

A module is a directory and a package is a namespace declared per file. Imports
are by package name and affect name resolution only. The full rules for modules,
packages, imports, and resolution are specified in `specs/modules.md`.

## Native functions

Status: required for the first implementation (bootstrap fallback).

A declaration with no body and an `@SmGen` attribute introduces a function with a Simse
type/signature whose implementation is generated - hand-written C++ for the `cpp`
generator. An optional explicit-symbol argument `@SmGen("cpp", "Symbol")` may be used
when the source name and the C++ symbol differ. Bodies are absent from
Simse. The declaration form may be combined with a type-parameter list and the
explicit `this` receiver form.

```text
@SmGen("cpp") fun readFile(path: Str): Str
@SmGen("cpp", "simse_native_readFile") fun readFile(path: Str): Str
@SmGen("cpp", "simse_list_append") fun append<T>(this: List<T>, value: T): Unit
```

The exact symbol naming, linkage, and build integration are in
`impl_specs/native-interop.md`; the generators themselves are
`impl_specs/generators.md`.

## Control flow: `break` and `continue`

Status: required for the first implementation.

`break` and `continue` are reserved keywords. Both are valid inside a `while` loop or
a `for` loop: `break` leaves the innermost loop and `continue` skips to its next
iteration. Using either where it is not allowed is an error. There is no
`break`-out-of-a-`when`: an arm is an `if`/`else` chain arm, so a `break` or
`continue` written in one belongs to the enclosing loop.

## `when`

Status: implemented; lowered to `if`/`else` in the parser
(`Parser::parseWhen`).

`when` is the language's only selection statement (there is no `switch`). It compares a
subject against a value per arm; an ownerless `when` is not a form of it.

- `when`, `else`, and `->` are reserved.
- A `when` has a **subject** - always written, in parentheses - and its arms are
  `label[, label]* -> { ... }`. `label1, label2 ->` matches either label and runs the
  arm's body **once**; the labels are separate `==` operands of one condition.
- A label is an arbitrary expression, not a constant: `subj == label` is what the arm
  tests, so a variable or a call is as legal as an enum member or a literal.
- The body is always a **block**. `else -> { ... }` is the fallback arm and must be
  last; a `when` without one simply falls through to the statement after it.
- **Arms do not fall through.** Exactly one body runs: the first arm whose label
  matches or, failing every arm, the `else` body. Nothing is required to end an arm.
- The subject is evaluated **once**, however many arms test it.
- `when` is a statement, not an expression: it does not produce a value.

Everything below the surface is an `if`/`else` chain, which is what the parser builds
(`impl_specs/linear-lowering.md`):

```text
when (kind) {            var _sm_when1 = kind
    A, B -> { body1 }    if (_sm_when1 == A || _sm_when1 == B) { body1 }
    C -> { body2 }       else if (_sm_when1 == C) { body2 }
    else -> { body3 }    else { body3 }
}
```

The name is the parser's own (`_sm_when<n>` from the per-file template counter), as
`for`'s machine name is, so a program cannot take it.

**A `when` over string literals compares a view of the subject and guards its tests by
length.** When every label of every arm is a string literal, the parser binds a **view** of the
subject (`_sm_when<n>_v = spanOfStr(...)`, `src/rtl/StrView.kt`) and its length
(`_sm_when<n>_n`), and each label's test compares the view instead of the subject:
`<view> == <label>`, guarded by `<length> == <the label's byte length> && ...`, so a label whose
length cannot match is rejected by one integer compare instead of a `memcmp`. The view is what
keeps the subject from being *copied* per label - `subject == "..."` materializes the subject
as a `Str` temporary for every literal (a heap copy, for a text longer than the inline buffer),
while a view of it is a pointer and a length. A subject that already is a `StrView` views
itself (the `StrView` overload of `spanOfStr` is the identity), so the desugar never has to know
which of the two it got. A one-byte label's test *is* the byte (`view[0] == '|'`, no string
compare left) and an empty label's is the bare length test, since `""` is the only text of
length zero. `--when-first-char` adds the first-byte guard to a label of two or more bytes as
well (off by default: measured, it did not pay), and `--no-when-dispatch` turns the whole guard
off (the plain comparisons on the subject, as before). Every guard is a *necessary* condition of
`==`, and the arms, their order, their bodies and the `else` are untouched - so what matches
cannot change.

Not implemented, and reported rather than misparsed: Kotlin's pattern labels
(`is Type`, `in 1..5`), a subjectless `when { cond -> }`, and `when` as an expression.

## `for`

Status: implemented; lowered to `while` in the parser (`impl_specs/for.md`).

`for` iterates whatever has an **`iter`**: an `in`-scope extension function returning a
state machine (`..T` in its signature, `impl_specs/yield.md`). The prelude writes one on the
span (`Span<T>.iterValues`), and a `List`/`Array`/`Str` receiver is viewed as its span first
(`spanOf`/`spanOfArray`/`spanOfStr`, `impl_specs/for.md`), so every container shares the one
machine; `Dictionary<K, V>` and ranges are not iterable yet. A state machine is its own
identity, so both of these work:

```text
for (value in source) { ... }
for ((value, index) in source) { ... }
```

A `*` in front of the variable binds a pointer to the element instead of a copy, through
the container's `iter` (the same walk, yielding `*T`). The compiler may **promote** a value
form to this pointer form when the loop variable is only read and the element's copy is deep (a
`Str`, or a data class holding one) - the value form copies, and for a `Str` allocates, per
element (`impl_specs/for.md`, `impl_specs/escape-analysis.md`). The promotion is not observable:
it is applied only when no write through the variable and no call that could change the
container is in the body. `--no-borrow` turns it off.

```simse
for (v in everyOther(10)) { ... }        // a machine
for ((v, i) in words) { ... }            // a List<Str>, indexed from 0
for (*cell in cells) { ... }             // a List<Cell>: the write reaches the list
```

The construct is `source.iterValues()`, and the loop is the `while` the language writes around
it (`impl_specs/for.md`):

- `for` is a reserved keyword; `in` is only special in the header.
- What is iterated is the machine `source.iterValues()` produces, created once before the loop
  starts and advanced once per iteration; for a machine argument that call *is* the
  argument.
- `value` is bound once per iteration, and so is `index`. Both are fresh `val`s: the loop
  does not move along by assigning to them. The variable's type is the machine's element
  type, so a `for` over a `List<Str>` binds a `Str`.
- `index` is an `Int` counter the compiler declares and increments from `0`. It counts
  iterations of the loop, not steps of the machine, so an iteration skipped with
  `continue` keeps its index and the next one is one higher.
- `break` and `continue` behave as in a `while` loop. `continue` still advances the
  machine: it means "skip the rest of this body", never "re-read the same value".
- **`*value` binds the element's *place*, not a copy.** The variable's type is `*T`, so a
  container of aggregates is walked without copying them, and a write through the variable
  reaches the element in the container. An aggregate reads through itself (`cell.value` is
  the field of the pointed-to `Cell`); a scalar is read with `*value`. It costs no more
  than `while` with an index (`impl_specs/for.md`, the measurement there). In the indexed
  form `index` stays an `Int` copy; only the *value* is a pointer.
- **A type is iterable when it has an `iterValues`** (or an `iter`, for the `*v` forms): an
  extension returning `..T` / `..*T`, which any type may add by writing a `yield`ing
  function, so the shape is the interface, not a runtime one. A type with none is an error,
  reported at the `for`:

  ```text
  stress/diagnostic-not-iterable/src/main.kt:11:5: a `for` iterates a machine
  (`..T`) or a type with an `iterValues`, and Int has neither; iterate a container with
  `while` and an index
  ```

  A machine has no pointer form - it hands out values, not places - so
  `for (*v in someMachine)` is reported too.

- A `for` inside a body that itself yields is not supported yet; the diagnosed
  alternative is to collect the values into a `List` first (`impl_specs/yield.md`).
- Ranges are next: `for (i in (2 .. 5))` is one more `iterValues` whose machine holds the
  two bounds (`impl_specs/for.md`).

## Default parameter values

Status: deferred.

Default parameter values are not supported for now and must not be relied on by
the parser or the mirrors.

## Statement separation

Status: required for the first implementation.

A line ending (the `EndOfLine` token: LF, CR, or CRLF) separates statements; an
explicit `;` is optional and equivalent. Space and comment tokens are ignored
everywhere by the parser.
