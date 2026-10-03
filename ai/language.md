# Using Simse

The full tutorial is `docs/language-tour.md`; `docs/state-of-the-field.md` is the honest
status; `specs/` is normative. This file is the short version for writing or reviewing code.

## A program

```text
package fixtures                // mandatory, first declaration in every file

fun main(): Int {               // the entry point; also `fun main(args: List<Str>): Int`
    println("hello")
    return 0
}
```

A **module is a directory**, a **package is a namespace**; `import pkg` only makes `pkg`
visible (it never adds files). `rtl` is implicit. Files are compiled with `--root <dir>`
(whole tree) or `--module <dir>` (repeatable; duplicates merge). A `simse.md` manifest can
name a project's modules instead. Compile a program with
`./simse.exe --root my/src -o out.cpp`, then compile `out.cpp` with any C++20 compiler.

## Values and types

`Int8 Int16 Int32 Int64 Float32 Float64 Char Bool Str`, with `Int` = `Int32`. `Str` is a
mutable, inline byte string (`s[i]`, `s.size()`, `s.substr(...)`, ...). Containers:
`List<T>` (a `SmallVector<T, 4>`), `Array<T>`, `SmallVector<N, T>`, `Dictionary<K, V>`,
`Span<T>` (a view), `Opt<T>` and `Res<T>` (no exceptions; `value()`/`hasValue()`,
`ok()`/`isOk()`/`error()`, `x!!` propagates a failure). `val` binds once, `var` reassigns;
locals infer from the initializer, or write the type. `null` is for handles only.

Data classes carry fields and methods; enums have explicit values and `toInt()`/`fromInt`.
A `union class U(var A: T, var B: U)` is a discriminated union: one field is live at a time
under an implicit `SmUTypes` tag enum. `U()` is `None`, `U(v)` picks the arm by `v`'s type,
and `getTypeOf()`, `isOfType(t)`, `getA(): Opt<T>` and `setA(v)` are generated; direct field
reads and writes are allowed (a direct write does not move the tag). No generic form yet,
and no exhaustive `when` - matching is `when` over `getTypeOf()` (`specs/declarations.md`,
`stress/unions`).
`typealias` names a type or a callable (`typealias IntFn = (Int) -> Int`). `native class` is
the same value type with the host's alignment instead of the 4-byte packing (for mirroring a
native layout); `ref class` has no value form - it is built as `&C(...)` and held by `&C`/`*C` -
and a class with an `unInit` destructor must be declared `ref class`.

```text
data class Point(var x: Int, var y: Int) {
    fun shift(dx: Int): Point { return Point(x+dx, y) }
}
enum class Color { Red, Green = 5 }
```

## Control flow

`if`/`else` is an expression; `while`; `when` evaluates its subject once (the subject must be
a plain local no arm assigns to) and has a string-dispatch lowering. `for (x in c)` works over
`List`/`Array`/`Span` (and machines — `..T`); `for ((x, i) in c)` adds the index and
`for (*x in c)` binds the place instead of a copy. **Not implemented**: ranges
(`for (i in 2..5)`), `Dictionary` iteration, `break`/`continue` labels, `switch`.

String interpolation is a backtick string: `` `n=@n` `` and `` `@(a+b)` ``; a literal `@` in an
interpolating string is a diagnostic. `+` concatenates `Str`; `fmtStr("|.|", a, b)` builds one
buffer (prefer it to a chain of `+`).

## Functions, extensions, lambdas

Receiver syntax is an explicit `this` parameter: `fun Str.shout(): Str`. `data fun` marks a
pure function the compiler may reuse. A lambda is `(x: Int) -> x + 1` or a block body starting
on the arrow's line. Lambdas **capture by value**, and `this` inside a lambda is a diagnostic;
to mutate the enclosing object, capture a raw pointer (`var p: *T = *this`). A lambda is a
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

- A lambda body must start on the arrow's line (or open a block there), and a `when` subject
  must be a local.
- A method on a temporary (`"a b".split(" ")`) fails: bind it to a `val` first.
- A method on a bare type-parameter receiver (`fun <T> f(x: T) { x.toString() }`) has no type
  to resolve against — bind it to a typed local or annotate the call's type arguments.
- `println` of a float is C++'s default formatting; enums print their integer value.
