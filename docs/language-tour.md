# A tour of Simse

Every fragment below is real syntax: the runnable versions are the programs under
[examples/](examples/) and [`stress/`](../stress/) (each stress folder is a
complete program with its expected output). Nothing here is aspirational - the
[roadmap](../impl_specs/user-language-roadmap.md) lists what does not exist yet.
Simse sources carry the **`.kt` extension** (Kotlin's: Simse is a Kotlin-flavored
dialect, so an editor's Kotlin mode highlights them - the language is Simse).

## A program

A file declares a package, then declarations. `main` is the entry point; its
`return` value becomes the process exit code.

```simse
package hello

fun main(): Int {
    println("hello, simse")
    return 0
}
```

The other `main` form takes the command-line arguments (`stress/main-args`):

```simse
package app

fun main(args: List<Str>): Int {
    println(args.size())
    return 0
}
```

## Values and types

Scalars are `Int8`, `Int16`, `Int32`, `Int64`, `Float32`, `Float64`, `Char`
(one byte) and `Bool`; `Int` is the default integer (`Int32`) and `Str` is the
mutable, inline byte-string type. `val` binds once, `var` is reassignable;
locals are inferred from the initializer or declared explicitly.

```simse
val name: Str = "box"
var count: Int = 0
count = count + 1
val ratio: Float64 = 0.5
val letter: Char = 'a'
val truth: Bool = true
```

Strings carry the usual library: `find`/`indexOf`, `substr`, `startsWith`,
`endsWith`, `replace`, `trim`, `split`, `toUpper`, `toLower`, `charAt`,
`isEmpty`, `size`, `+`, and the `Opt`-returning parses `toInt()`/`toFloat()`.

```simse
val text: Str = "the quick brown fox"
println(text.startsWith("the"))          // true
println(text.replace("fox", "cat"))
println(text.split(" ").size())          // 4
val n: Opt<Int> = "42".toInt()
if (n.hasValue()) {
    println(n.value() + 1)               // 43
}
```

## Control flow

`if`/`else`, `when` (the only selection statement - Kotlin's), `while`, `for` (over
a state machine, below), `break` and `continue`; conditions are `Bool` expressions
(`&&`, `||`, `!`). A `when` arm tests `subject == label`, several labels on one arm
run that body once, `else` must be last, and arms never fall through.

```simse
fun classify(n: Int): Str {
    when (n) {
        0 -> {
            return "zero"
        }
        1 -> {
            return "one"
        }
        else -> {
            return "many"
        }
    }
}

fun main(): Int {
    var row: Int = 0
    while (row < 4) {
        row = row + 1
        if (row == 2) {
            continue
        }
        if (row == 3) {
            break
        }
        println(row)
    }
    println(classify(9))                 // many
    return 0
}
```

There is no `foreach` over containers: storage is walked with an index and `while`,
or with `Span<T>` (`stress/collections`) - a borrowed view (pointer plus length) with
`size()`, `isEmpty()`, `at(i)`/`span[i]` and `slice(start)`/`slice(start, count)`:

```simse
fun sum(items: &List<Int>): Int {
    var total: Int = 0
    var span: Span<Int> = spanOf(*items)
    while (!span.isEmpty()) {
        total = total + span[0]
        span = span.slice(1)
    }
    return total
}
```

`for` iterates **whatever has an `iter`** in one of exactly two forms: a state
machine - the value a function whose body `yield`s produces - or a container, which the
prelude gives one per container (`List<T>`, `Array<T>`, `Span<T>`; a `Dictionary` and a
range are not iterable yet). (A `iter` is an ordinary extension function returning
`..T`, so your own type can have one too; see `specs/functions.md`.)

```simse
fun everyOther(n: Int): ..Int {
    var i: Int = 0
    while (i < n) {
        if (i % 2 == 0) {
            yield i
        }
        i = i + 1
    }
}

fun main(): Int {
    for (v in everyOther(10)) {            // the value
        println(v.toString())
    }
    for ((v, i) in everyOther(10)) {       // the value and its iteration index
        println(i.toString() + ": " + v.toString())   // 0:0, 1:2, 2:4, ...
    }

    val words: List<Str> = listOf<Str>("one", "two")
    for (w in words) {                     // a container, in its own order
        println(w)
    }
    return 0
}
```

The loop variable is a fresh `val` per iteration, typed by the element type (`v` is an
`Int` above, `w` a `Str`); `index` is a counter the compiler declares, starting at `0`,
so a `continue` still counts the iteration it skipped. Both forms are a `while` over a
machine in the generated C++ (`impl_specs/for.md`).

A `*` in front of the variable binds a **pointer to the element** instead of a copy of
it - the form for a container of values, where a copy per iteration is waste and the loop
may want to write through what it walks:

```simse
data class Cell(var value: Int)

fun bumpAll(cells: *List<Cell>): Unit {
    for (*cell in cells) {          // no copy: `cell` is a *Cell
        cell.value = cell.value + 1 // the write reaches the element in the list
    }
    for ((*cell, i) in cells) {     // ... and the index is available too
        println(i.toString() + ":" + cell.value.toString())
    }
}
```

An aggregate reads through itself (`cell.value`); a pointer to a scalar is read with
`*value`. It costs what the same loop written with `while` and an index costs, so it is
the shape to prefer in a hot loop (`specs/functions.md`).

A variable - or any other place - is updated in place with `+=`, `-=`, `*=`, `/=`, `%=`,
and stepped with `i++` / `i--`:

```simse
var i: Int = 1
i += 2                          // i is 3 now

data class Cell(var value: Int)
val cells: List<Cell> = listOf(Cell(1))
cells[0].value++                // the element's field, updated where it lives
```

The target's **place** is located once, so an index with an effect runs once
(`cells[next()].value += 1` calls `next()` once) and nothing is copied on the way: a
field of an element and a `*T` parameter both update what they name
(`specs/memory-model.md`). `i++` is `i += 1` and `i--` is `i -= 1`, and because an
assignment has no value they stand on their own as a statement - the prefix form
(`++i`) and a step inside an expression (`x = i++`) are diagnostics.

The same places take the bitwise operators `& | ^ << >>` and their compound forms
(`&= |= ^= <<= >>=`), on integers:

```simse
val high: Int = 2
val low: Int = 5
val packed: Int = (high << 4) | low     // 37
val masked: Bool = packed & 0xF != 0    // the mask first: `&` binds tighter than `==`
```

The bitwise pair binds *tighter* than a comparison - Python's order, not C's, where
`flags & mask == 0` silently means `flags & (mask == 0)` - and the shifts sit between
`+` and `&`, so `1 << 2 + 1` is `1 << 3` (`specs/built-in-types.md`, "Operators").
Because `>>` is a shift, a nested type written `List<List<Int>>` ends in a `>>` the
parser splits, so `>>` closes any depth of nesting.

## Functions, extensions, lambdas

Functions are top-level or methods; the receiver may be declared as an
extension, which is how the standard library is written. Generics are reified:
`identity<Int>(7)` calls a function specialized for `Int`.

```simse
fun identity<T>(value: T): T {
    return value
}

fun Str.words(): List<Str> {
    return this.split(" ")
}

typealias Mapper = (Int) -> Int

fun apply(f: Mapper, value: Int): Int {
    return f(value)
}

fun makeAdder(factor: Int): Mapper {
    return (v: Int) -> v + factor        // captures `factor` by value
}

fun main(): Int {
    println(identity<Int>(7))
    val text: Str = "a b c"
    println(text.words().size())         // 3  (a literal receiver does not compile, see the gotchas)
    println(apply((v: Int) -> v * 2, 21))// 42
    val add10: Mapper = makeAdder(10)
    println(add10(5))                    // 15
    return 0
}
```

A lambda body may also be a block, written on the same line as the arrow:

```simse
val big: Mapper = (v: Int) -> {
    if (v > 0) {
        return v * 100
    }
    return 0
}
```

A block body is a body like any other: it has its own scope, its own locals, and its
own control flow, so it can loop over what it captured. Two lambdas may even each
declare a local of the same name, because neither is naming the other's:

```simse
typealias Taker = (Int) -> Unit

fun main(): Int {
    val items: List<Int> = listOf(10, 20)

    val addUp: Taker = (n: Int) -> {
        for (value in items) {           // `value` is this lambda's own
            println((value + n).toString())
        }
    }
    addUp(1)

    val onlyEvens: Taker = (n: Int) -> {
        for ((value, index) in items) {  // a different `value`, at that
            if (index % 2 == 0) {
                println((value - n).toString())
            }
        }
    }
    onlyEvens(1)
    return 0
}
```

## Data classes and enums

A `data class` is a value type with named fields (separated by `,`, as in Kotlin), an
implicit constructor, value semantics, and methods that may use `this`. Fields are
accessed with `.`.

```simse
data class Point(var x: Int, var y: Int) {
    fun manhattan(): Int {
        var total: Int = this.x
        if (total < 0) {
            total = 0 - total
        }
        if (this.y < 0) {
            total = total - this.y
        } else {
            total = total + this.y
        }
        return total
    }
}

fun main(): Int {
    val p: Point = Point(3, 4)
    println(p.manhattan())               // 7
    p.x = 10                             // fields may be reassigned when declared `var`
    return 0
}
```

Enums are integer-valued; members may carry explicit values, and `toInt()` /
`fromInt()` convert between the enum and its `Int` representation - `fromInt` is the
direct cast back, so it asks for no `Opt` and checks nothing. There is no automatic
member *name* yet, so a `when` function is the way to print one
(`stress/objects`).

```simse
enum class Color {
    Red,
    Green = 4,
    Blue
}

fun label(c: Color): Str {
    when (c) {
        Color.Red -> {
            return "red"
        }
        Color.Green -> {
            return "green"
        }
        else -> {
            return "other"
        }
    }
}
```

## Generics and collections

`List<T>` is a growable, deep-copying sequence; `Array<T>` is a fixed-length
reference-counted block (one allocation, count first) that supports `count()`,
indexing, `toArray()`/`toList()` and the shared `arrayEmpty<T>()`;
`SmallVector<4, T>` keeps up to four elements inline.

```simse
val values: List<Int> = listOf(10, 20)     // one instruction, elements inline
values.removeAt(0)
println(values.size())                   // 1

val arr: Array<Int> = values.toArray()
println(arr[0])                          // 20
var again: List<Int> = arr.toList()
again.append(30)
```

A list is built from its elements with `listOf(a, b, c)` - one instruction, and up to
four elements live inside the list, so a short literal allocates nothing - or from a
count with the type's own construction:

```simse
val keywords: List<Str> = listOf<Str>("static", "var", "val")
val primes: List<Int> = listOf(2, 3, 5, 7)          // the type is inferred
val zeros: List<Int> = List<Int>(3)                 // three default elements
val flags: List<Bool> = List<Bool>(4, false)        // four copies of false
val none: List<Str> = listOf<Str>()
```

And a call **packs its trailing arguments** into a last parameter that is a list, so a
function can take "all the rest" the way `printf` does:

```simse
fun addAll(values: *List<Int>): Int {
    var total: Int = 0
    for (*value in values) {
        total = total + *value
    }
    return total
}

println(addAll(1, 2, 3))                         // 6 - one list, built in place
println(addAll())                                // 0 - the empty list
println(addAll(*values))                         // the list itself, not a list of a list
```

So a `fun format(shape: Str, items: *List<Str>)` is called as
`format("Hello!")` or `format("Hello {0}!", "world")`: everything past the shape
packs into one list.

The `*List<T>` form is the zero-copy one: the packed list is a temporary of the
caller's own frame and the parameter is its address, so each element is copied once and
the list not at all. A by-value `List<T>` parameter packs too, and takes its copy as
value semantics require. The **counted** forms (`&List<T>`, `PList<T>`) are deliberately
not pack targets - a control block and a reference count for a temporary that dies at
the end of the statement would be cost with no use - so `sum(1, 2, 3)` against a
`&List<Int>` parameter is the arity error it always was; write `*List<Int>`.

The handle in a **call argument** is inferred when both sides are the same type, which
is what lets a parameter move from a copy to a borrow without breaking its callers:

```simse
val xs: List<Int> = listOf(4, 5)
addAll(xs)      // the compiler passes `*xs`: the list itself, borrowed
addAll(*xs)     // the same call, written out
sum(xs)         // a by-value parameter takes its copy
sum(*xs)        // ... and reads through a pointer the same way
boxed(xs)       // a `&List<Int>` parameter takes a copy *inside* the reference
boxed(*xs)      // not this one: a raw pointer cannot become a reference in place
                // (make one first: `var ref: &List<Int> = &xs`)
```

That last line is the one conversion the compiler refuses, and it is the reason a `*T`
*argument* still has to be spelled nowhere else: the type it is passed to (a copy, a
borrow, a box) is the parameter's business, and only a box needs the writer's word.

`Dictionary<K, V>` is the hash dictionary (`get`/`has`/`insert`/`remove`/`size`/
`keys`/`values`/`clear`); `get` returns an `Opt<V>`. Iteration order is an
implementation detail, so sort the keys when order matters.

```simse
val counts: Dictionary<Str, Int> = dictionaryOf<Str, Int>()
counts.insert("b", 2)
counts.insert("a", 1)
println(counts.get("a").value())         // 1
println(counts.has("z"))                 // false

val keys: List<Str> = counts.keys()
keys.sort((left: Str, right: Str) -> left < right)
println(keys[0] + " " + keys[1])         // a b
```

## Absence and failure

`Opt<T>` is an optional value (`hasValue()`, `value()`); `Res<T>` is a result
whose `Value` or `Error` hold the outcome (`isOk()`), used throughout the
compiler for parsing and file work. `null` is a literal for `&T` and `*T` in a
nullable context, so `x == null` tests a handle.

Both are arms of one tagged union in the runtime (`Variant2<A, B>`,
`cppsrc/rtl/variant2.hpp`): an `Opt<T>` is `Variant2<T, VoidEnum>` and a `Res<T>`
is `Variant2<T, Str>`, and the tag - not the message - says which arm is live, so
a result whose message happens to be empty is still a failure.

```simse
fun describe(n: Int): Opt<Str> {
    if (n < 0) {
        return Opt<Str>.none()
    }
    return Opt<Str>.some("ok")
}
```

```simse
// The failure half is a value too: `ok` carries the payload, `err` a message.
fun parse(text: Str): Res<Int> {
    val n: Opt<Int> = text.toInt()
    if (!n.hasValue()) {
        return Res<Int>.err("not a number")
    }
    return Res<Int>.ok(n.value())
}

fun main(): Int {
    val good: Res<Int> = parse("42")
    println(good.isOk())                 // true
    println(good.Value)                  // 42
    val bad: Res<Int> = parse("nope")
    println(bad.isOk())                  // false
    println(bad.Error)                   // not a number
    return 0
}
```

Propagating a failure is the common case, so a postfix `!!` on a `Res` is the payload or
an early `return` of the failure from the enclosing function:

```simse
fun readValue(text: Str): Res<Str> {
    val parsed: Res<Int> = parse(text)
    val n: Int = parsed!!              // the payload, or `parse`'s failure, returned here
    return Res<Str>.ok("value=" + n.toString())
}
```

The value is evaluated once. When the operand's declared type is the function's own return
type the failure path returns the operand itself (a move); otherwise the message is carried
into a rebuilt `Res<T>` - nothing is remapped on the way, because a `Res`'s error arm is
always a `Str`. `!!` must be the whole right-hand side of a `val`/`var`, an assignment, or a
statement of its own, and what it propagates into must be a `Res`: the enclosing function's
return type, or - inside a lambda, which has no declared return type - the result type of the
parameter the lambda is passed to. A `!!` anywhere else is a positioned diagnostic.

## Memory: values, handles, pointers

Assignment copies values. `&value` boxes a value in a reference-counted handle
(copying the value *into* the box), and copies of one handle share that box;
`*value` is a raw pointer, which is the *borrowing* form: it points at the
original, so a write through it is visible there. Member access, indexing and
calls auto-dereference both.

```simse
data class Box(var value: Int)

fun maybeRef(flag: Bool): &Box {
    if (flag) {
        return &Box(7)                   // boxes a fresh value
    }
    return null                          // a null handle, tested with == null
}

fun main(): Int {
    val boxed: &Box = &Box(3)
    println(boxed.value)                 // 3, auto-dereferenced
    val raw: *Box = *boxed               // a raw pointer to the boxed value
    raw.value = 4
    println(boxed.value)                 // 4: the pointer borrows, it does not copy

    var items: List<Int> = List<Int>()
    val handle: &List<Int> = &items      // boxes a *copy* of the list
    handle.append(1)
    println(items.size())                // 0: the handle owns its own copy
    val shared: &List<Int> = handle      // handles copy the box, not the contents
    shared.append(2)
    println(handle.size())               // 2: `handle` and `shared` are the same box

    val borrow: *List<Int> = *items      // borrows the local, no copy
    items.append(3)
    println(borrow.size())               // 1: the pointer sees the original
    return 0
}
```

So `&T` is for *shared identity* (a value several handles point at, allocated
once) and `*T` is for *borrowing* (passing a big value to a function without
copying it - that is what the compiler's own code does with the AST). Handles are
reference counts, so a cycle of `&T` values is not collected: use values, `Array`
blocks, or an explicit `null`-out to break one.

## Modules and statics

A module is a directory; a package is the `package` name declared at the top of
each file. The compiler scans a module root and links every file it finds;
`import` only brings a package's names into unqualified scope. File-level `var`
declarations are statics, initialized before `main` runs.

```simse
// src/util/util.kt
package util

data class Point(var x: Int, var y: Int)

fun twice(value: Int): Int {
    return value + value
}
```

```simse
// src/app/main.kt
package app

import util

var hits: Int = 7

fun main(): Int {
    val p: Point = Point(3, 4)
    println(twice(p.x) + hits)           // 13
    return 0
}
```

## Printing

`print` and `println` take a value and print it, with `Bool` as `true`/`false`;
scalars have `toString()`, and `Str + Str` concatenates. There is no string
interpolation or formatting function yet, and `println` of your own types is not
supported (a `Printable` protocol is planned), so build strings explicitly.

```simse
println("count = " + count.toString())
println(2.5.toString())
println(min(3, 9))
```

## Gotchas worth knowing on day one

These are known rough edges, not design decisions to admire
([state-of-the-field.md](state-of-the-field.md) has the full list):

- **`&local` boxes a *copy*, `*local` borrows.** `val h: &List<Int> = &items`
  copies the list into a box; `h.append(1)` leaves `items` empty. Use
  `val p: *List<Int> = *items` to pass or mutate the original without copying.
- **A method call on a literal or a temporary does not compile.** The receiver is
  emitted as a non-const reference, so `"a b".words()` fails; bind it first:
  `val text: Str = "a b"` then `text.words()`.
- **A method call chained onto a generic call loses its type.**
  `counts.get(k).value().toString()` does not compile; assign the middle step to a
  typed `val` first.
- **A lambda body must start on the arrow's line.** `(x: Int) ->` followed by a
  newline and the expression is a syntax error; keep the body on the same line or
  open a block there.
- `println` of a float uses the C++ default formatting, and `println` of an enum
  prints its integer value.
- There is no `foreach` keyword (`for` is it, and a container has an `iter` so
  `for (x in list)` works), no `when` pattern labels (`is Type`, `in 1..5`) and no
  subjectless `when`, no string interpolation, no default parameter values, no
  capture-by-reference, and no `Set`.
