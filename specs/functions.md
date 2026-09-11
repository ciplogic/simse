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
data class Point(var x: Int; var y: Int) {
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
data class Point(var x: Int; var y: Int) {
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

## Packages and imports

Status: required for the first implementation.

A file may begin with an optional, single, file-level package declaration:

```text
package a.b.c
```

It must appear after any leading blank lines and before imports and every other
declaration. A file with no `package` is in the unnamed (root) package.
`package` is a reserved keyword.

`package` is namespacing and grouping only: it does not introduce visibility or
access-control semantics, and it does not change how names resolve inside the
declaring file. Its sole effect is to let `import` select a set of files.

`import a.b.c` imports every top-level declaration of every file whose declared
package is exactly `a.b.c`. Imports are transitive and diamond-deduplicated
(importing the same package through several paths merges it once); import cycles
are detected and reported rather than followed indefinitely. Resolution is by
package name, decoupled from folder layout, so it does not depend on the working
directory:

```text
package cppsrc.lex

import cppsrc.common
```

As a silent fallback, when no file declares the imported package, the dotted path
is treated as a directory relative to the resolution root and every `*.simse` file
directly under it is merged. The fallback exists only for the bootstrap and
reports nothing extra; new code should declare packages.

## Native functions

Status: required for the first implementation (bootstrap fallback).

A declaration `native fun name(params): Ret` introduces a function with a Simse
type/signature but no body; its implementation is provided by hand-written C++.
An optional explicit-symbol form `native("Symbol") fun name(...)` may be used
when the source name and the C++ symbol differ. Native bodies are absent from
Simse. The declaration form may be combined with a type-parameter list and the
explicit `this` receiver form.

```text
native fun readFile(path: Str): Str
native("simse_native_readFile") fun readFile(path: Str): Str
native("simse_list_append") fun append<T>(this: List<T>, value: T): Unit
```

The exact symbol naming, linkage, and build integration are deferred; see
`impl_specs/native-interop.md`.

## Control flow: `break` and `continue`

Status: required for the first implementation.

`break` and `continue` are reserved keywords. `break` is valid inside a `while`
loop or a `switch` and leaves the innermost of those; `continue` is valid only
inside a loop and skips to the next iteration. Using either where it is not
allowed is an error.

## `switch`

Status: required for the first implementation.

The language has a C-like `switch` statement. It selects one arm by comparing the
switch expression against constant case labels, with an optional `default` arm:

```text
switch (kind) {
    case TokenKind.Eof:
        return "eof"
    case TokenKind.Space:
        return "space"
    default:
        return "other"
}
```

- `switch`, `case`, and `default` are reserved keywords.
- A `case` label must be a **constant expression**: a literal, a name, an
  enum-qualified member (`EnumType.Member`), or a unary negation of one. A call or
  any other dynamic expression is an error.
- Each arm holds the statements up to the next `case`/`default` label or the
  closing brace. As in C, control falls through to the following arm unless the
  arm ends in `break` or `return`. `break` inside a `switch` leaves the switch.
- `default` is optional and may appear anywhere among the arms; at most one is
  meaningful.

Like every other control-flow construct, an arm body is a statement sequence and
line endings or `;` separate its statements.

## Default parameter values

Status: deferred.

Default parameter values are not supported for now and must not be relied on by
the parser or the mirrors.

## Statement separation

Status: required for the first implementation.

A line ending (the `EndOfLine` token: LF, CR, or CRLF) separates statements; an
explicit `;` is optional and equivalent. Space and comment tokens are ignored
everywhere by the parser.
