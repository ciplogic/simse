# Using Simse

The full tutorial is `docs/language-tour.md`; `docs/state-of-the-field.md` is the honest
status; `specs/` is normative. This file is the short version for writing or reviewing code.

## A program

```kt
package fixtures                // mandatory, first declaration in every file

fun main(): Int {               // the entry point; also `fun main(args: List<Str>): Int`
    println("hello")
    return 0
}
```

A **module is a directory**, a **package is a namespace**; `import pkg` only makes `pkg`
visible (it never adds files). `rtl` is implicit. Files are compiled with `--root <dir>`
(whole tree, repeatable) or `--module <dir>` (repeatable; duplicates merge). A `simse.md` manifest can
name a project's modules instead. Compile a program with
`./simse.exe --root my/src --module modules/io -o out.cpp`, then compile `out.cpp` with any C++20 compiler.

## Values and types

`Int8 Int16 Int32 Int64 Float32 Float64 Char Bool Str`, with `Int` = `Int32`. `Str` is a
mutable, inline byte string (`s[i]`, `s.size()`, `s.substr(...)`, ...). Containers:
`List<T>` (a `SmallVector<T, 4>`), `Array<T>`, `SmallVector<N, T>`, `Dictionary<K, V>`,
`Span<T>` (a view), `Opt<T>` and `Res<T>` (no exceptions; `value()`/`hasValue()`,
`ok()`/`isOk()`/`error()`, `x!!` propagates a failure). `val` binds once, `var` reassigns;
locals infer from the initializer, or write the type. `null` is for handles only.

Data classes carry fields and methods; enums have explicit values and `toInt()`/`fromInt`.
A `data class` may **derive** from one other plain data class (`specs/declarations.md`,
"Inheritance"): the header's `: Base` appends the base's fields *ahead* of the class's own,
so `Point3D(val z: Int) : Point2D` has fields `x, y, z`, is constructed `Point3D(1, 2, 3)`,
reads `p3.x` like its own, and resolves `p3.movedBy(...)` on a `Point2D` method. A derived
value stands where its base is expected: a value position copies the prefix
(`Base{ d.x, d.y }`), a `*Base` parameter is the same address reinterpreted (no copy, writes
land in the derived object), and a `&Base` is a *snapshot box* of the prefix (a box cannot
share a derived object - no vtables, no implicit `&Derived` -> `&Base` sharing). The base
must be a visible, non-generic plain data class without `unInit`; the chain is acyclic, field
names - or a method at the same arity - may not repeat down it, and `native`/`ref`/`union`
classes cannot derive.
Containers are the gap: `xs.append(p3)` on a `List<Point2D>` needs the base value bound first
(`val p2: Point2D = p3`).
A `union class U(var A: T, var B: U)` is a discriminated union: one field is live at a time
under an implicit `SmUTypes` tag enum. `U()` is `None`, `U(v)` picks the arm by `v`'s type,
and `getTypeOf()`, `isOfType(t)`, `getA(): *T` and `setA(v)` are generated. A getter is a raw
pointer to the live arm - `null` when the tag says another arm - so reading never copies the
payload; `null` is for handles only. Direct field
reads and writes are allowed (a direct write does not move the tag, and on a managed union
it does not destroy what it replaces either - `setA` does). Two fields may share a type: the
earlier one is the arm a by-value construction builds, the later is reached by name. Arms that own storage (`Str`,
`List`, a handle) are managed: the generated destructor destroys the live arm by tag, and
copy/move keep a copied or returned union's data alive. Matching is `when (u)`
with bare arm names (`IntValue`, `None`, `else`), or `when (u.getTypeOf())` with qualified
labels: a comparison against a union value is its tag comparison. Type parameters work
(`union class Res2<T>(var Value: T, var Error: Str)`; `Res2<Int>(5)` binds `T`), and one
non-generic tag enum is shared by every instantiation. No exhaustive `when`
(`specs/declarations.md`, `stress/unions`).
`typealias` names a type or a callable (`typealias IntFn = (Int) -> Int`). `native class` is
the same value type with the host's alignment instead of the 4-byte packing (for mirroring a
native layout); `ref class` has no value form - it is built as `&C(...)` and held by `&C`/`*C` -
and a class with an `unInit` destructor must be declared `ref class`.

```kt
data class Point(var x: Int, var y: Int) {
    fun shift(dx: Int): Point { return Point(x+dx, y) }
}
enum class Color { Red, Green = 5 }
```

## Control flow

`if`/`else` is an expression; `while`; `when` evaluates its subject once (the subject must be
a plain local no arm assigns to) and has a string-dispatch lowering. `for (x in c)` works over
`List`/`Array`/`Span` (and machines — `..T`); `for ((x, i) in c)` adds the index and
`for (*x in c)` binds the place instead of a copy. Ranges are `a..b` (inclusive), `a until b`,
`a downTo b`, and `r step n` — `for (i in 1..5)` iterates one (`Range`/`SteppedRange` are
prelude values with `iterValues`; `specs/built-in-types.md`, "Ranges"), and `x in c` /
`x !in c` is the membership test (`c.contains(x)`). **Not implemented**: `Dictionary`
iteration (walk `keys()`), `break`/`continue` labels, `switch`.

String interpolation is a backtick string: `` `n=@n` `` and `` `@(a+b)` ``; a literal `@` in an
interpolating string is a diagnostic. `+` concatenates `Str`; `fmtStr("|.|", a, b)` builds one
buffer (prefer it to a chain of `+`).

## Functions, extensions, lambdas

Receiver syntax is an explicit `this` parameter: `fun Str.shout(): Str`. `data fun` marks a
pure function the compiler may reuse. A lambda is `(x: Int) -> x + 1` or a block body starting
on the arrow's line; a call's final argument may be a **trailing brace lambda** — `apply(5) { x -> x + 1 }`,
`twice { 41 }` — whose body spans lines, and whose last statement is the lambda's value. A
brace lambda with no declared parameter that names `it` gets it as the single implicit
parameter (`xs.forEach { println(it) }`; the prelude's `repeat(n) { }` and `List`/`Array`
`forEach` are the canonical consumers). Lambdas **capture by value**, and `this` inside a
lambda is a diagnostic; to mutate the enclosing object, capture a raw pointer
(`var p: *T = *this`). A lambda is a
data class with a free `<sym>_invoke` method (`ai/contributing.md` has the codegen rules).

## Generics and call inference

Generics are **reified**: every instantiation is its own C++ type. A call fixes a callee's
type parameters from the receiver, an explicit instantiation (`f<Int>(x)`), and the argument
types; the result is typed, so `twice(f, 5).toString()` resolves. A callable parameter is the
one position arguments cannot fix directly — the compiler uses the lambda's annotations or a
callable value's type and spells the instantiation (`peek((x: Int) -> ...)` emits
`peek<Int>`); a lambda with no annotation and no other fixing argument is a positioned
`cannot infer type parameter` report. A lambda that fits both a callable and a plain `T`
declaration of the same name is an `ambiguous call` report (`pick<Int>(...)` disambiguates).

## Protocols

A **protocol** is a named method signature a type parameter can require - a static
interface, not a type and not an existential:

```kt
protocol fun Printable<T> T.toString(): Str
protocol fun Equality<T, TDest> T.equalsWith(other: *TDest): Bool

fun printBoth<T>(value: *T) when T: Printable, Countable {
    val text: Str = value.toString()
    val count: Int = value.countItems()
    print(text)
}
```

The type parameters after the name are the protocol's, the first one being the subject (the
receiver); the protocol is unnamed when the method's name is enough (`protocol fun <T>
T.toString(): Str`). A type satisfies it structurally when an extension or class-body
method matching the signature exists - no `implements` clause. The `when` clause after a
function's signature lists what each type parameter must satisfy; several constraints
separate on commas (`when T: Printable, Countable`). A constrained call resolves
`value.toString()` through the protocol, and every call of the function checks the concrete
type argument (`'Rock' does not satisfy protocol 'Printable': ...`). `val x: Printable` is
an `unknown type`: a protocol never names a value's type (`specs/declarations.md`).

## Handles, pointers, ownership

`*x` is the address of a value; a `*T` raw pointer is unchecked and unowned. `&x` boxes a copy
into a counted reference (`&T`, shared, freed when the last handle drops) — it is *not* an
alias of `x`. `copy(x)` is the value. Parameters may be values, `*T` borrows or `&T` handles;
the compiler infers the argument's handle at the call when the types match, and an automatic
borrow rewrite (`--no-borrow` turns it off) passes by reference where it is provably safe.
A `*T` outlives nothing: keep the pointee alive. There is no GC, no exceptions.

## Statics, resources, attributes

File-level `var`/`val` are supported statics; `object` declarations are specified but not
implemented. A `_res.md` carries text a program compiles in: `Resources.get("section:key")`
reads it at run time, and `@SmGen("res", section[, symbol])` hangs generated C++ on a
declaration. `@SmGen("cpp", symbol)` names hand-written C++; `@SmGen("native", library[, symbol])`
is the Windows P/Invoke form. A `simse.md` names modules and per-module generator flags.

## Async, yield, `for`

`suspend` is a declaration modifier: a call to a suspending function colors the caller
transitively up to `main`, and `!!` carries the failure through; there is no `await` and no
`Async<T>`. The task-machine lowering is still being built. `yield` turns a function into a
state machine and is what powers `for` over `..T` (`impl_specs/yield.md`, `impl_specs/for.md`).

## Day-one gotchas

- A parenthesized lambda's body must start on the arrow's line (or open a block there); a
  multi-line body is the trailing brace form, `f(x) { ... }`. A `when` subject must be a
  local.
- A method on a temporary (`"a b".split(" ")`) fails: bind it to a `val` first.
- A method on a bare type-parameter receiver (`fun <T> f(x: T) { x.toString() }`) needs a
  protocol: constrain the parameter (`when T: Printable`) or the call has nothing to
  resolve against (a diagnostic).
- `println` of a float is C++'s default formatting; enums print their integer value.
