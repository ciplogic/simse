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
data class Point(var x: Int; var y: Int)
```

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

For the initial implementation, constructors must receive one argument for
each declared field, in declaration order. Default field values, named
arguments, inheritance, and generated methods beyond construction/copying are
not part of this baseline.

## `enum`

`enum` declares a named integer-backed type, similar to a C# enum. Each member
has an integer representation. If no explicit value is supplied, the first
member is `0` and each following member increments by one.

```text
enum Color {
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

`fromInt` is checked: an integer that is not one of the declared enum values
produces `Opt<Color>.none()` rather than an invalid enum value. Converting an
enum to `Int` is always valid. Implicit conversion between `Int` and an enum is
not allowed; use `toInt()` or `fromInt(...)` explicitly.

Enum members are immutable constants. Duplicate integer values are allowed, so
multiple names may represent the same value; `fromInt` returns the enum value
but does not promise which alias name is preferred for display.
