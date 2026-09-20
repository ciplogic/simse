# Functions

Status: design baseline — top-level functions for the first implementation.

## Scope

Functions are declared at module/file scope or in a class body. The language
supports two forms:

1. ordinary top-level functions; and
2. receiver functions, which have a receiver type and are called with
   member-call syntax. A receiver function may be written at top level as an
   extension function or inside a class body as a class method.

This is intentionally a subset of Kotlin's function model: extension functions
and class methods lower to the same static function shape. They are not virtual
methods, and there is no interface dispatch.

## Ordinary functions

The baseline declaration form is:

```text
fun functionName(parameter: Type, other: Type): ReturnType {
    // body
    return expression
}
```

For a function that returns no useful value, omit the return type or write
`Unit`; both forms mean the same thing:

```text
fun logPoint(point: Point) {
    println(point.x)
    println(point.y)
}

fun logPoint2(point: Point): Unit {
    println(point.x)
    println(point.y)
}
```

A non-`Unit` function must return a value on every reachable path. A `Unit`
function may use `return` without a value or reach the closing brace.

Functions use ordinary value semantics for parameters. Passing a `List<T>`,
`Str`, or data-class value therefore copies it according to that type's value
semantics. Use `&T` or `*T` explicitly when sharing or borrowing is intended:

```text
fun copied(items: List<Int>): Int {
    return items.size()
}

fun shared(items: &List<Int>): Int {
    return items.size()
}

fun borrowed(items: *List<Int>): Unit {
    println(items.size())
}
```

`&T` keeps its box alive through the call. `*T` is non-owning and may be null or
dangling; using it is subject to the raw-pointer rules in `memory-model.md`.

## Packing the trailing arguments

Status: implemented; the call site is one `Pack` instruction
(`impl_specs/linear-il.md`).

A call packs its **trailing arguments** into a temporary list when its callee's
**last parameter is a list** - by value (`List<T>`) or borrowed (`*List<T>`):

```text
fun addAll(values: *List<Int>): Int { ... }
fun sum(values: List<Int>): Int { ... }
fun format(shape: Str, items: *List<Str>): Str { ... }

addAll(1, 2, 3)                  // one list: 1, 2, 3
addAll(10)                       // one list: 10
addAll()                         // the empty list
sum(6, 7, 8)                     // the callee's copy of one list
format("k", "a", "b")            // everything past the shape packs
```

- The list is built by **one instruction** (`Pack`, the RTL's initializer-list
  construction) and `List<T>` is `SmallVector<T, 4>`, so a **packed list of up to four
elements allocates nothing**: it lives in the temporary's inline buffer.
- A `*List<T>` parameter is passed the **address** of that temporary, which is a slot of
the caller's frame - so a packed call copies each element once and the list not at all.
- **Each element is a value**, so it converts the way a by-value parameter's argument does
  (`memory-model.md`, "Automatic dereference"): a handle among the trailing arguments is
  read through to its pointee instead of being stored as a pointer. An accessor's borrow can
  therefore be packed without the caller spelling `*a` - `format(template, a, b)` accepts a
  `*Str` `a` - and it is the shape `fmtStr`'s items have.
- **One argument for one parameter is the list itself**, whatever its handle form:
  `addAll(*xs)` passes the list, it does not build a one-element list of a list. That is
  what tells `addAll(*xs)` from `addAll(1)` when both have one argument.
- The **counted forms are not pack targets**: `&List<T>` and `PList<T>` (which *is*
  `&List<T>`, a `std::shared_ptr`) make a packed temporary pay for a control block and a
  reference count it would drop again at the end of the same statement, for no use at all.
  A call that would pack into one is the arity error it always was:

  ```text
  fun sum(values: &List<Int>): Int { ... }
  sum(1, 2, 3)   // error: no overload of 'sum' takes 3 argument(s)
  ```

  The zero-copy spelling of the same call is the borrow, `fun sum(values: *List<Int>)`.
- The rule is about the *last parameter* only, and a call that already fits is passed as
  it is: `f(a, xs)`, where `xs` is the list and the counts match, is that call.
- A **list literal** is the same instruction spelled directly: `listOf<Str>("a", "b",
  "c")` builds the list from those values, in that order
  (`specs/containers.md`). It is the one call the compiler never emits a call for.
  `List<T>(...)` is the RTL's own construction - a *count* of elements - so a literal
  can never be mistaken for a size, and vice versa.
- A `List<T>` argument that the callee takes **by value** is copied into the parameter,
  as value semantics require (the first section of this document); the pack itself
  still builds the list once, and the copy is of a small list that usually lives inline.

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

```text
fun addAll(values: *List<Int>): Int { ... }
fun sum(values: List<Int>): Int { ... }
fun boxed(values: &List<Int>): Int { ... }

val xs: List<Int> = listOf(1, 2, 3)
addAll(xs)      // == addAll(*xs): the list itself, borrowed, not a copy
sum(xs)         // the copy value semantics require
sum(*xs)        // the same copy, read through the pointer
boxed(xs)       // a copy inside the reference
```

- **The address is the argument's own place.** `addAll(xs)` borrows `xs`, `f(a[0])`
  borrows the element, `f(p.field)` the field - never the address of a temporary the
  compiler made on the way (a callee that writes through the pointer writes into the
  caller's object - `stress/pointer-place`). A temporary (`f(g())`) is the one case
  where the address is taken at the call, and it is valid for that call.
- **This is what lets a parameter change from a copy to a borrow.** An API that finds
  `fun addAll(values: List<Int>)` too expensive can become
  `fun addAll(values: *List<Int>)` and every existing `addAll(xs)` call still compiles
  - it copies nothing from then on. The reverse change compiles too.
- **The types still have to match.** `addAll(aListOfStr)` against `*List<Int>` is not
  converted: only the *handle* is inferred, never the type, so the call is the type
  error it always was.
- **A construction converts the same way.** `Rect(w, h)` where a field is `Int` and `w`
  came back as a `*Int` from an accessor reads it through like any call argument - the
  constructor is a call boundary too. And where the callee's own signature cannot name the
  parameter's type (a generated extension's bare type parameter, `Dictionary<K, V>.has(key:
  K)`), the argument's own type is what the conversion reads, which is what makes
  `names.append(accessor(...))` an element copy rather than a complaint.
- **A `*T` binding is not converted.** `val p: *List<Int> = xs` still writes the `*`:
  a pointer that outlives the expression it points into is asked for explicitly, and
  the convenience above is for a call, whose borrow ends with it.
- **A raw pointer cannot become a counted reference in place.** `&T` counts a *box*,
  and a `*T` argument is a pointer to somebody's storage, so there is nothing to count:
  the call is reported rather than silently copied, because the two spellings differ
  (a copy of that storage, or a share the language has no box for). The writer says
  which one they mean, one line before the call:

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
fun Str.firstByte(): Opt<uint8> {
    if (this.size() == 0) {
        return Opt<uint8>.none()
    }
    return Opt<uint8>.some(this[0])
}

fun Point.magnitudeSquared(): Int {
    return this.x * this.x + this.y * this.y
}
```

They are called with member syntax:

```text
val point = Point(3, 4)
val lengthSquared = point.magnitudeSquared()
val first = "hello".firstByte()
```

The receiver is an ordinary parameter in the generated function. Extension
functions do not modify the receiver type, add fields, participate in dynamic
dispatch, or gain privileged access to private representation. `this` refers
to the receiver. A mutable receiver must be declared with the appropriate
reference/value form:

```text
fun List<Int>.clearInPlace(): Unit {
    this.clear()
}

fun (*List<Int>).clearThroughPointer(): Unit {
    this.clear()
}
```

The exact mutability checks for extension receivers follow normal `var`/`val`
and pointer rules; the extension declaration does not bypass them.

## Methods inside classes

Methods written inside a `class` or `data class` body are equivalent to
extension functions for code generation. They are static functions with an
explicit receiver parameter; the containing class only supplies the receiver
type and the method's qualified name.

```text
data class Point(var x: Int, var y: Int) {
    fun magnitudeSquared(): Int {
        return this.x * this.x + this.y * this.y
    }
}
```

The call uses member syntax:

```text
val point = Point(3, 4)
val result = point.magnitudeSquared()
```

Conceptually, the method is lowered like this:

```text
fun Point.magnitudeSquared(this: Point): Int {
    return this.x * this.x + this.y * this.y
}
```

The exact generated C++ name is compiler-controlled. A method does not receive
an implicit object/vtable pointer, and the class does not acquire runtime
method metadata. Method lookup is static and resolved from the compile-time
receiver type.

The following features are not supported yet:

- virtual dispatch;
- interfaces or interface implementation;
- method overriding;
- dynamic method lookup; and
- abstract methods.

## Generic functions

Generic functions are reified at each used type-argument combination. See
`generics.md`; the generated C++ output contains a concrete specialization for
each emitted instantiation.

Functions may declare type parameters after their name. Type parameters must
be declared before use and are inferred from arguments where possible:

```text
fun identity<T>(value: T): T {
    return value
}

fun count<T>(items: List<T>): Int {
    return items.size()
}
```

Generic extension functions use a type parameter list and a receiver parameter.
The initial parser uses the explicit `this` parameter form:

```text
fun firstOrNone<T>(this: List<T>): Opt<T> {
    if (this.size() == 0) {
        return Opt<T>.none()
    }
    return Opt<T>.some(this[0])
}
```

This is an extension function even though the receiver is written in the
parameter list: the special parameter name `this` enables member-call syntax.
Generic function type aliases are specified in
`memory-model.md`, for example `typealias Action<T> = (T) -> Unit`.

## Class-body limitations

Class bodies are parsed as balanced regions. Method declarations inside them
are compiled as static receiver functions, as described above. Other class-body
content remains unsupported and must be ignored or rejected as a whole:

```text
data class Point(var x: Int, var y: Int) {
    fun distance(): Int { return x * x + y * y }
    // Fields, initializers, nested types, and executable statements are
    // unsupported in the class body for now.
}
```

The fields in the `data class` header remain part of the type. The body does
not declare additional fields, initializers, nested types, or arbitrary
executable statements. Methods are the one supported class-body declaration,
and they must follow the static receiver-function rules above.

Implementations should either skip unsupported body constructs as balanced token
regions or report a clear unsupported-body diagnostic. They must not partially
compile unsupported non-method code.

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

The keyword this was spelled with first, `native fun` / `native("Symbol") fun`, is gone
(T83): it was sugar for the attribute, and `@SmGen` is what the runtime and every program
write. The exact symbol naming, linkage, and build integration are in
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

`when` is the language's selection statement - Kotlin's, and the only one: the
language has no `switch`. It compares a **subject** against a value per arm, and an
ownerless `when` (the `switch` of other languages) is not a form of it:

```text
when (kind) {
    TokenKind.Eof -> {
        return "eof"
    }
    TokenKind.Space, TokenKind.Tab -> {
        return "space"
    }
    else -> {
        return "other"
    }
}
```

- `when`, `else`, and `->` are reserved.
- A `when` has a **subject** - always written, in parentheses - and its arms are
  `label[, label]* -> { ... }`. `label1, label2 ->` matches either label and runs the
  arm's body **once**; the labels are separate `==` operands of one condition.
- A label is an arbitrary expression, not a constant: `subj == label` is what the arm
  tests, so a variable or a call is as legal as an enum member or a literal (this is
  where the language differs from C's `switch`).
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

Not implemented, and reported rather than misparsed: Kotlin's pattern labels
(`is Type`, `in 1..5`), a subjectless `when { cond -> }`, and `when` as an expression.

## `for`

Status: implemented; lowered to `while` in the parser (`impl_specs/for.md`).
Both rings parse it, emit the machine (`yield` is implemented in both) and report a `for`
over a non-machine.

`for` iterates whatever has a **`smToYield`**: an `in`-scope extension function that
returns a state machine (`..T` in its signature, `impl_specs/yield.md`). A **container
is iterable** - the prelude writes one `smToYield` per container in Simse (`List<T>`,
`Array<T>` and `Span<T>` today; `Dictionary<K, V>` and ranges are not iterable yet) - and
a state machine is its own identity, so both of these work:

```text
for (value in source) { ... }
for ((value, index) in source) { ... }
```

A `*` in front of the variable binds a **pointer to the element** instead of a copy of
it, through the container's `smToYieldPtr` (the same walk, yielding `*T`):

```text
for (*value in source) { ... }
for ((*value, index) in source) { ... }
```

```simse
for (v in everyOther(10)) {              // a machine
    println(v.toString())
}

for (v in items) {                       // a List<Int>, in order
    println(v.toString())
}

for ((v, i) in words) {                  // a List<Str>, indexed from 0
    println(i.toString() + ": " + v)
}

for (v in numbers.toArray()) {            // an Array<Int>, in order
    println(v.toString())
}

for (v in spanOf(*items)) {               // borrowed storage
    println(v.toString())
}

for (*cell in cells) {                    // a List<Cell>: no copy per iteration
    cell.value = cell.value + 1           // ... and the write reaches the list
}
```

The construct is `source.smToYield()`, and the loop is the `while` the language
writes around it (`impl_specs/for.md`). So:

- `for` is a reserved keyword; `in` is only special in the header.
- What is iterated is the machine `source.smToYield()` produces - created **once**,
  before the loop starts, and advanced once per iteration. For a machine argument that
  call *is* the argument, so the machine is the one the source built.
- `value` is bound once per iteration, and so is `index`. Both are fresh `val`s
  (as in Kotlin's `for`): the loop does not move along by assigning to them. The
  variable's type is the machine's element type: a `for` over a `List<Str>` binds a
  `Str`.
- `index` is an `Int` counter the **compiler** declares and increments, starting at
  `0`. It counts *iterations* of the loop, not steps of the machine, so an iteration
  skipped with `continue` keeps its index and the next one is one higher.
- `break` and `continue` behave exactly as in a `while` loop. `continue` still
  advances the machine: it means "skip the rest of this body", never "re-read the
  same value".
- **`*value` binds the element's *place*, not a copy.** The variable's type is `*T`,
  so a container of aggregates is walked without copying them, and a write through the
  variable reaches the element in the container. An aggregate reads through itself
  (`cell.value` is the field of the pointed-to `Cell`); a scalar is read with `*value`.
  This is the form for a hot loop over a container of values - it costs no more than
  `while` with an index (`impl_specs/for.md`, the measurement there). `index` stays an
  `Int` copy in the indexed form; only the *value* is a pointer.
- **A type is iterable when it has a `smToYield`** (or a `smToYieldPtr`, for the `*v`
  forms): an extension returning `..T` / `..*T`, which any type may add
  (`fun Point.walk(): ..Point` writes a machine like any other `yield`ing function, so
  the *shape* is the interface, not a runtime one). A type with none is an error,
  reported at the `for`:

  ```text
  stress/diagnostic-not-iterable/src/main.kt:11:5: a `for` iterates a machine
  (`..T`) or a type with a `smToYield`, and Int has neither; iterate a container with
  `while` and an index
  ```

  A machine has no pointer form - it hands out values, not places - so
  `for (*v in someMachine)` is reported too.

- A `for` inside a body that itself yields is not supported yet; the diagnosed
  alternative is to collect the values into a `List` first (`impl_specs/yield.md`).
- Ranges are next: `for (i in (2 .. 5))` is one more `smToYield` whose machine holds the
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
