# Declarations: data classes, variables, and enums

Status: design baseline — first declaration forms for the self-hosted
implementation.

## `data class`

`data class` declares a named value/layout type. Its fields are stored inline,
and copying a data class copies each field according to that field's type
semantics. A data class is not implicitly heap allocated or reference counted;
use `&T` when shared identity is required.

```text
data class Point(var x: Int, var y: Int)
```

Two layout words may replace `data`: `native class C(...)` is the same value/layout type, but
its generated struct is emitted with the host's own alignment - the language's 4-byte packing
is not applied to it - because the class exists to mirror a native layout
(`memory-model.md`, "Alignment and packing"). `ref class C(...)` declares a **handle-only**
type: it has no value form, so the only construction is the boxed `&C(...)` - any other
construction is a diagnostic (`'C' is a ref class: build it as '&C(...)' - a ref class is
held by '&C' or '*C'`), and a value of it is reached through `&C` (counted) or `*C` (raw).
The two reasons the word exists: a recursive type cannot hold itself by value (a tree's child
is `&Node`/`*Node`, not `Node`), and a class that owns a resource is destroyed once, by the
box (`unInit` below makes the same guarantee through a destructor).

Fields are separated by `,` (Kotlin's spelling; the list may be wrapped across lines,
and a `;` is accepted there too). The declaration is otherwise line-oriented: a field
list that runs over several lines continues until the `)`.

`Point(1, 2)` constructs a value, not a counted reference; `&point` is a fresh, non-null
boxed copy (boxing copies the value), and a `&T` variable may later be `null`.

### Construction: `initByValue`

A type may declare an extension named `initByValue` - `fun Point.initByValue(x: Int, y: Int)` -
that sets the receiver's fields and returns nothing. Two forms then read as the value in the
parentheses:

- `return (a, b)` in a function whose return type is `Point` builds a default `Point` and
  calls `Point.initByValue(a, b)` on it; `return ()` is the zero-argument form. A lambda has no
  declared return type, so a parenthesized `return (a)` there is just the value `a`.
- `var p = Point(a, b)`, with no declared type, does the same, taking `p`'s type from the name
  in the parentheses.

With an explicit type - `var p: Point = Point(a, b)` - the ordinary constructor runs, and a plain
assignment `p = Point(a, b)` is an ordinary construction too. When the type declares no
`initByValue`, `return (e)` is the ordinary value `e` (and a multi-value `return (a, b)` has no
plain-return spelling). `Opt<T>` and `Str` are built the `initByValue` way in the RTL
(`src/rtl/rtl.kt`).

### Fields and `var`/`val`

Each data-class field is declared with either `var` or `val`:

- `var name: T` is mutable after construction.
- `val name: T` is immutable after construction.

The same keywords apply to local variables and parameters. `val` prevents rebinding
or mutation through that variable. It does not make a
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
are compiled as static receiver functions; see `functions.md` - `unInit` below
is the one exception, because it is a destructor and not a callable.

### Destructors: `unInit`

Status: implemented (the by-value rule, the destructor, in-place boxing of a
constructed box, the `ref class` requirement, and the shape checks; `stress/uninit`,
`stress/diagnostic-uninit-value`, `stress/diagnostic-uninit-data-class`).

A `ref class` may declare one method named `unInit` - its **destructor** (a `data class`
that declares one is a diagnostic: the destructor makes the class handle-only, and
`ref class` is the word for that):

```
ref class Res(var id: Int) {
    fun unInit(): Unit {
        println("close " + this.id.toString())
    }
}
```

It takes no parameters and returns nothing, and it is emitted as the type's C++
destructor (`Res::~Res()`, declared in the struct and defined with the bodies), so its
body runs when the value is destroyed. It is not callable: there is no function of that
name to call. Each of these is a diagnostic - a second `unInit` on one class, a parameter,
a returned value, and a call `x.unInit()`.

Such a type may be held only through a **handle** (`&T`) or a **raw pointer** (`*T`). A
value of it is a copy, and every copy would run the destructor - the same resource closed
once per copy - so a declaration, field, parameter or return type that names the type (or
a container of it) is a diagnostic:

```
val r: &Res = &Res(7)     // ok: the box's last owner destroys it, once
val p: *Res = *r          // ok: a raw pointer owns nothing
val bad: Res = Res(7)     // error: 'Res' has an unInit: hold it by '*Res' or '&Res'
```

`&T` is what gives the single destruction: boxing owns one count, copying the handle adds
an owner, and the last one to go destroys the box (`specs/memory-model.md`). Because the
destructor makes a *copy* observable, `&Ctor(args)` builds the box **in place**
(`makeRef<C>(args...)`) instead of constructing a temporary and copying it into the box -
the temporary's own destructor would otherwise run as well.

## `enum class`

`enum class` declares a named integer-backed type. The `class` keyword is required: a
bare `enum Name` is a syntax error. Each member has an integer representation; if no
explicit value is supplied, the first member is `0` and each following member increments
by one.

```text
enum class Color {
    Red,          // 0
    Green,        // 1
    Blue = 4,     // 4
    Purple        // 5
}
```

The enum is a distinct type for declarations and function signatures, but its
runtime representation is `Int` and its size/alignment are those of `Int`.

Implicit conversion between `Int` and an enum is not allowed; use `toInt()` or
`fromInt(...)` explicitly. Enum members are immutable constants, and duplicate integer
values are allowed, so several names may represent the same value.

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

```text
var code: Int = Color.Green.toInt()          // 4
var other: Color = Color.fromInt(4)         // Color.Green
var any: Color = Color.fromInt(9)           // the cast's value, no member named
```

## `union class`

Status: implemented (`stress/unions`, `stress/diagnostic-union-same-type`).

A `union class` declares a **discriminated union** (a tagged union): a value type with
several fields of which one is live at a time, selected by an implicit tag enum. It is
otherwise a data class - fields, methods, `this` - and its generated C++ is the tag beside
the fields' anonymous union, in a generated storage base (`Sm<Name>Storage`) that the class
derives from. When every arm is trivially copyable the base declares no special member, so
the whole class stays a plain trivially copyable aggregate: no destructor, no copy, nothing
to run. When an arm owns storage (`Str`, `List`, `Dictionary`, a handle, ...) the compiler
emits the **managed form** in the base: a destructor that destroys the live arm by tag,
copy/move constructors and assignments, and setters that destroy the arm they replace and
place the new one. For a concrete union a field type the compiler cannot prove trivially
copyable is treated as managed; that only costs the class its triviality, never correctness.

A `union class` may declare **type parameters** (`union class Res2<T>(var Value: T, var
Error: Str)`). One non-generic `SmRes2Types` tag enum is shared by every instantiation, the
struct and its generated members are C++ templates, and a construction's explicit type
arguments bind the fields for type matching (`Res2<Int>(5)` matches `Value: T` as `Int`).
A generic union's form is chosen **per instantiation**, from the arm types the instantiation
actually has: a one-arm union over `T` is a trivially copyable aggregate at `T = Int` and
takes the managed form at `T = Str`. Known gap: an instantiation whose type argument equals
another arm's type (`Res2<Str>` with `Error: Str`) collides in the generated `initByValue`
overloads at C++ level - the non-generic duplicate-field diagnostic cannot see it - so such
an instance cannot be constructed yet.

```text
union class DoubleOrFloat(var IntValue: Int, var DoubleValue: Float64)
```

For a `union class U`, the compiler defines beside the class:

- an enum `SmUTypes` - a normal enum in the same package, members `None` (0) and then the
  field names in declaration order;
- `getTypeOf(): SmUTypes` - the live arm's tag;
- `isOfType(t: SmUTypes): Bool` - the tag test;
- `setNone(): Unit` - back to the empty tag;
- per field `A: T`, `setA(value: T): Unit` (writes the tag and the arm) and
  `getA(): Opt<T>` (the value when the tag is `A`, an empty `Opt` otherwise).

The generated members are ordinary declarations to the checker and to `when`; a user
method that reuses one of their names is a diagnostic.

A comparison against a union value is a **tag comparison**: `u == A` and `u != A` (with `A`
a field name or `None`; a name bound as a local, parameter or static stays that value), and
the already-qualified `u == SmUTypes.A`, both compile to the tag test - the generated
operators compare the struct with its tag enum. `when (u)` with bare arm names is therefore
the same `when` as `when (u.getTypeOf())` with qualified labels; a bare name that is no arm
is a diagnostic with the arm list.

### Construction

`U()` is the `None` value, and `U(v)` picks the arm whose field type *is* `v`'s type: two
fields of one type are a declaration diagnostic (the construction could not tell them
apart), while two distinct types - two enums, say - are fine. The construction follows the
`initByValue` convention: `var u = U(v)`, the explicit-type `var u: U = U(v)` and
`return (v)` all build the arm through its generated `initByValue`. Unlike a data class,
an expression position has no lowering yet - `f(U(v))`, `u = U(v)` and a static
initializer are reported rather than mis-built. Direct field access is allowed: a read is
unchecked like a C++ union member, and a direct write moves the storage without moving the
tag (`setA` is the tag-aware write). On a managed union a direct write is raw storage as
well - it neither destroys the arm it replaces nor places a new one - so `setA` is the write
to use whenever the old arm owns anything.

The language does not check a `when` over the tag for exhaustiveness.

## Package declarations

Status: required for the first implementation.

A file declares exactly one package as the first declaration: `package a.b.c`,
before its imports and other declarations. `package` is a reserved keyword. It is
namespacing/grouping only and gives no visibility semantics; `import a.b.c`
brings package `a.b.c` into unqualified scope. See `specs/modules.md` for the
full rules.

## Hoisting

Status: required for the first implementation.

Module-level declarations (functions, `data class`, `union class` with its implicit tag
enum, `enum class`, `typealias`, and the
file-level `var`/`val` of `specs/statics.md`) are hoisted: visible throughout the module
regardless of textual order. Declarations may be referenced before their textual
definition, and mutually recursive functions need no forward declaration. Methods within
a class body are likewise order-independent relative to one another and to the class's
fields.

Local variables are **not** hoisted: a local is visible only from its declaration
onward, so a local use-before-declaration is an error.
