# Declarations: data classes, variables, and enums

Status: design baseline — first declaration forms for the self-hosted
implementation.

## `data class`

`data class` declares a named value/layout type. Its fields are stored inline,
and copying a data class copies each field according to that field's type
semantics. A data class is not implicitly heap allocated or reference counted;
use `&T` when shared identity is required.

The first supported user-defined type is `Point`:

```text
data class Point(var x: Int, var y: Int)
```

Fields are separated by `,` (Kotlin's spelling; the list may be wrapped across lines,
and a `;` is accepted there too). The declaration is otherwise line-oriented: a field
list that runs over several lines continues until the `)`.

The constructor has the same field order as the declaration:

```text
val origin = Point(0, 0)
var point = Point(1, 2)

point.x = 10
point.y = 20
```

`Point(1, 2)` constructs a value, not a counted reference. Boxing is explicit:

```text
val point = Point(1, 2)
var refPoint: &Point = &point   // fresh, non-null boxed copy

refPoint.x = 3                  // mutates the boxed Point
point.x                         // still 1; boxing copied the value
refPoint = null                 // reference variables may later be null
```

### Fields and `var`/`val`

Each data-class field is declared with either `var` or `val`:

- `var name: T` is mutable after construction.
- `val name: T` is immutable after construction.

The same keywords apply to local variables and parameters:

```text
val immutablePoint = Point(1, 2)
var mutablePoint = Point(1, 2)

mutablePoint.x = 4
// immutablePoint.x = 4   // compile-time error: immutablePoint is a val
```

`val` prevents rebinding or mutation through that variable. It does not make a
referenced object immutable: a `val ref: &Point` cannot be assigned a different
reference, but the pointed-to `Point` may still be changed through another
mutable handle. A `val` data-class field likewise prevents assigning that field;
it does not recursively freeze a referenced value.

Declared outside any class, function or object, the same keywords declare
**static storage**: a `var`/`val` at file level outlives every call. See
`specs/statics.md` for the storage/initialization rules.

For the initial implementation, constructors must receive one argument for
each declared field, in declaration order. Default field values, named
arguments, inheritance, and generated methods beyond construction/copying are
not part of this baseline. Methods may be declared in the class body, but they
are compiled as static receiver functions; see `functions.md`.

## `enum class`

`enum class` declares a named integer-backed type, similar to a C# enum. The
`class` keyword is required - an enum is a class of constants, spelled the way
Kotlin spells it, so a bare `enum Name` is a syntax error. Each member has an
integer representation. If no explicit value is supplied, the first member is `0`
and each following member increments by one.

```text
enum class Color {
    Red,          // 0
    Green,        // 1
    Blue = 4,     // 4
    Purple        // 5
}
```

The enum is a distinct type for declarations and function signatures, but its
runtime representation is `Int` and its size/alignment are those of `Int`:

```text
var color: Color = Color.Green
var code: Int = color.toInt()     // 1
var other: Color = Color.fromInt(4)
```

`fromInt` is the **direct cast back** to the enum, unchecked: an enum's runtime
representation is `Int`, so an integer that names no member is still that value (the
casting conversion is defined for every `Int` - a scoped `enum class` holds the whole
range of its underlying type). Converting an enum to `Int` is always valid, and it is
the same kind of operation. Implicit conversion between `Int` and an enum is not
allowed; use `toInt()` or `fromInt(...)` explicitly.

Enum members are immutable constants. Duplicate integer values are allowed, so
multiple names may represent the same value; `fromInt` returns the enum value
but does not promise which alias name is preferred for display.

### Enum member qualification

Status: required for the first implementation.

Enum member access is qualified as `EnumType.Member`. An enum remains a distinct
type whose runtime representation is `Int`; unqualified member names are not
introduced into scope.

### Enum conversions

Status: required for the first implementation.

Every declared enum has two conversions, emitted by the compiler for that enum:

- `value.toInt(): Int` - the member's integer value (always valid); and
- `EnumType.fromInt(n: Int): EnumType` - the direct cast back, unchecked: an
  integer that names no member is still that value, because an enum's runtime
  representation *is* an `Int` and the cast is defined for every value of its
  underlying type. Duplicate member values are allowed, so `fromInt` may report a
  value that several names share; it does not promise which alias name is
  preferred.

The two are the two directions of one relation, which is what "the enum is `Int` at
runtime" means in the type system: `toInt` never fails, and `fromInt` is the cast it
inverts.

(Earlier drafts had `fromInt(n: Int): Opt<EnumType>`, the checked form. It is the cast
now: the runtime representation is an `Int`, so an `Opt` around the result would carry a
membership test the language does not perform anywhere else - `Color.fromInt(9)` is not
"no color", it is the value `9` seen as a `Color`.)

```text
var code: Int = Color.Green.toInt()          // 4
var other: Color = Color.fromInt(4)         // Color.Green
var any: Color = Color.fromInt(9)           // the cast's value, no member named
```

## Package declarations

Status: required for the first implementation.

A file declares exactly one package as the first declaration: `package a.b.c`,
before its imports and other declarations. `package` is a reserved keyword. It is
namespacing/grouping only and gives no visibility semantics; `import a.b.c`
brings package `a.b.c` into unqualified scope. See `specs/modules.md` for the
full rules.

## Hoisting

Status: required for the first implementation.

Module-level declarations (functions, `data class`, `enum class`, `typealias`, and the
file-level `var`/`val` of `specs/statics.md`) are **hoisted**. They are visible
throughout the module regardless of textual order,
like Kotlin, Java, or C#. Declarations may be referenced before their textual
definition, and mutually recursive functions need no source-level forward
declaration. Methods within a class body are likewise order-independent relative
to one another and to the class's fields.

Local variables are **not** hoisted: a local is visible only from its declaration
onward, so a local use-before-declaration is an error.
