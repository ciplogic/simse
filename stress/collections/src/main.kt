package collections

// ---- array-layout ----

// `Array<T>` is one allocation with the element count first and the elements
// after it (specs/built-in-types.md). This case pins the surface that goes with
// that layout: `count()`, indexing, `toArray`/`toList`, and `arrayEmpty<T>()`,
// whose empty array is the shared one rather than a fresh allocation.

data class Point(var x: Int, var y: Int)

fun texts(): List<Str> {
    var texts: List<Str> = List<Str>()
    texts.append("alpha")
    texts.append("beta")
    return texts
}

fun partArrayLayout(): Int {
    var values: List<Int> = List<Int>()
    values.append(10)
    values.append(20)
    values.append(30)

    val arr: Array<Int> = values.toArray()
    println(arr.count())
    println(arr[0] + arr[1] + arr[2])

    // The handle shares the block: a write through one name is visible through
    // the other.
    var alias: Array<Int> = arr
    alias[1] = 99
    println(arr[1])

    // The empty array is shared and zero-length: nothing is allocated for it.
    val empty: Array<Int> = arrayEmpty<Int>()
    println(empty.count())

    // The empty *list* converges on the same empty array: `toArray` of a list with
    // no elements allocates nothing (`simse_list_toArray`).
    var nothing: List<Int> = List<Int>()
    val emptyAgain: Array<Int> = nothing.toArray()
    println(emptyAgain.count())

    // An array is fixed length, so adding an element goes through a list.
    var growable: List<Int> = arr.toList()
    growable.append(40)
    val grown: Array<Int> = growable.toArray()
    println(grown.count())
    println(grown[3])

    // Element types with fields, and element types that own heap bytes.
    var points: List<Point> = List<Point>()
    points.append(Point(1, 2))
    points.append(Point(3, 4))
    val pointArray: Array<Point> = points.toArray()
    println(pointArray.count())
    println(pointArray[0].x + pointArray[1].y)

    val names: Array<Str> = texts().toArray()
    println(names.count())
    println(names[1])
    return 0
}

// ---- bulk-list ----

// Ten thousand elements: the list crosses from the inline buffer to the heap on
// the way, so this pins the growth path and the sums that read it back.

fun partBulkList(): Int {
    var values: List<Int> = List<Int>()
    var i: Int = 0
    while (i < 10000) {
        values.append(i % 97)
        i = i + 1
    }
    println(values.size())

    var total: Int = 0
    i = 0
    while (i < values.size()) {
        total = total + values[i]
        i = i + 1
    }
    println(total)
    println(values[0])
    println(values[9999])
    return 0
}

// ---- containers ----
fun partContainers(): Int {
    val xs: List<Int> = List<Int>()
    xs.append(10)
    xs.append(20)
    xs.append(30)
    println(xs.size())
    xs.removeAt(1)
    println(xs.size())
    println(xs[1])
    xs.removeRange(0, 1)
    println(xs.size())
    return 0
}

// ---- dictionary ----
// Exercises the Dictionary surface and the List extras (T20): insert, get, getPtr, has,
// size, keys/values (sorted for determinism), contains, sort, remove, clear.

fun partDictionary(): Int {
    var counts: Dictionary<Str, Int> = dictionaryOf<Str, Int>()
    counts.insert("b", 2)
    counts.insert("a", 1)
    counts.insert("c", 3)
    counts.insert("b", 20)

    println(counts.size())
    println(counts.get("a").value())
    println(counts.has("z"))

    // `getPtr` hands out the value's place: `null` when the key is absent, and a write
    // through it reaches the entry. The write is the *first* statement of its block on
    // purpose: a statement starting with `*` after another statement is read as a
    // multiplication continuation (`*p = v` on its own line means `... * p = v`), so a
    // deref write belongs first in a block or in parentheses.
    val present: *Int = counts.getPtr("b")
    if (present != null) {
        *present = 21
        println(*present)
    }
    println(counts.get("b").value())
    println(counts.getPtr("z") == null)

    var names: List<Str> = counts.keys()
    names.sort((left: Str, right: Str) -> left < right)
    var i: Int = 0
    while (i < names.size()) {
        println(names[i])
        i = i + 1
    }
    println(names.contains("c"))
    println(names.contains("z"))

    var values: List<Int> = counts.values()
    values.sort((left: Int, right: Int) -> left < right)
    var j: Int = 0
    while (j < values.size()) {
        println(values[j])
        j = j + 1
    }

    counts.remove("a")
    println(counts.size())
    counts.clear()
    println(counts.size())
    return 0
}

// ---- for-array ----

// `for` over the fixed-length sequence and over borrowed storage: `Array<T>` and
// `Span<T>` walk with their own `iter` in the prelude, written in the language's
// `yield` like the `List<T>` one (impl_specs/for.md).
//
// A per-container machine is why a machine class carries its receiver's name
// (`Array_iter_yieldable`), and why a program that iterates one container does not
// carry another's machine: a prelude body is emitted for the receiver a program names.

fun arraySum(items: *List<Int>): Int {
    val arr: Array<Int> = items.toArray()
    var sum: Int = 0
    for (value in arr) {
        sum = sum + value
    }
    return sum
}

// The same numbers through borrowed storage: a span over the list's elements must not
// outlive it, and `for` walks it in order like any other container.
fun spanSum(items: *List<Int>): Int {
    val span: Span<Int> = spanOf(items)
    var sum: Int = 0
    for (value in span) {
        sum = sum + value
    }
    return sum
}

fun partForArray(): Int {
    val numbers: List<Int> = List<Int>()
    numbers.append(3)
    numbers.append(4)
    numbers.append(99)
    numbers.append(5)

    // An array walks in order, without an index of its own.
    for (value in numbers.toArray()) {
        println(value.toString())
    }

    // `continue` and `break` over an array are the loop's own - the advance is the first
    // statement of the body, so a skipped iteration still moves the machine on.
    for ((value, index) in numbers.toArray()) {
        if (index == 1) {
            continue
        }
        if (value == 99) {
            break
        }
        println(index.toString() + "=" + value.toString())
    }

    println(arraySum(*numbers).toString())
    println(spanSum(*numbers).toString())
    return 0
}

// ---- for-container ----

// T42: `for` over a container. The two forms are the same `while` the parser writes,
// with the iterated expression wrapped in an invisible `iter()` call: `List<T>`
// has one in the prelude, and it is written in the language's own `yield` - a machine
// that walks the list in order (impl_specs/for.md).
//
// The loop variable is typed by the element type, so `value.toString()` picks the
// `Int` spelling on a `List<Int>` and the `Str` one on a `List<Str>`; the index in the
// indexed form is the compiler's counter, starting at 0.

fun total(items: *List<Int>): Int {
    var sum: Int = 0
    for (value in items) {
        sum = sum + value
    }
    return sum
}

fun partForContainer(): Int {
    val numbers: List<Int> = List<Int>()
    numbers.append(10)
    numbers.append(20)
    numbers.append(30)

    // The plain form.
    for (value in numbers) {
        println(value.toString())
    }

    // The indexed form, over `Str` elements.
    val words: List<Str> = List<Str>()
    words.append("one")
    words.append("two")
    words.append("three")
    for ((word, index) in words) {
        println(index.toString() + ":" + word)
    }

    // `continue` and `break` are the loop's own: the advance is what the loop head runs,
    // so a skipped iteration still moves the machine on.
    for ((value, index) in numbers) {
        if (index == 1) {
            continue
        }
        if (value > 25) {
            break
        }
        println(index.toString() + "=" + value.toString())
    }

    // A container passed by pointer, so the loop is not over a copy.
    println(total(*numbers).toString())
    return 0
}

// ---- for-pointer ----

// `for (*x in c)` and `for ((*x, i) in c)`: the pointer forms of `for`. The loop
// variable is a *pointer to the element* (a `*T`, the prelude's `iterPtr`) rather
// than a copy of it, so a loop over a container of aggregates copies nothing per
// iteration and a mutation through the loop variable reaches the container
// (impl_specs/for.md). A pointer to an aggregate reads through itself (`cell.value`),
// a pointer to a scalar is read with `*value`.

data class Cell(var value: Int)

// The plain pointer form: the mutation through `cell` reaches the list.
fun bump(cells: *List<Cell>): Int {
    var total: Int = 0
    for (*cell in cells) {
        cell.value = cell.value + 1
        total = total + cell.value
    }
    return total
}

// The indexed pointer form: the place and the index in one loop.
fun report(cells: *List<Cell>): Unit {
    for ((*cell, i) in cells) {
        println(i.toString() + ":" + cell.value.toString())
    }
}

// Over an array: the array is a copy of the list, so its mutations stay in it.
fun arrayBump(items: *List<Cell>): Int {
    val cells: Array<Cell> = items.toArray()
    var total: Int = 0
    for (*cell in cells) {
        cell.value = cell.value * 2
        total = total + cell.value
    }
    return total
}

// Over borrowed storage: a scalar element is read through the pointer with `*`.
fun spanSumPointer(items: *List<Int>): Int {
    val span: Span<Int> = spanOf(items)
    var total: Int = 0
    for (*value in span) {
        total = total + * value
    }
    return total
}

fun partForPointer(): Int {
    val cells: List<Cell> = List<Cell>()
    cells.append(Cell(1))
    cells.append(Cell(2))
    cells.append(Cell(3))

    println(bump(*cells).toString())
    report(*cells)
    println(cells[0].value.toString())

    println(arrayBump(*cells).toString())
    println(cells[0].value.toString())

    val numbers: List<Int> = List<Int>()
    numbers.append(4)
    numbers.append(7)
    numbers.append(9)
    println(spanSumPointer(*numbers).toString())
    return 0
}

// ---- lambda-for ----

// T51: a `for` inside a lambda. A lambda body is a frame of its own - its parameters
// plus the values it captures - and it is *typed* like any other body, so the loop
// variable has a type (`value.toString()` is the integer spelling) and the `iter`
// wrap a `for` puts around what it iterates is the identity on a machine.
//
// Both forms are here on purpose: over a container, and over a machine the program
// built itself.

typealias Taker = (Int) -> Unit

fun List<Int>.everyNth(step: Int): ..Int {
    var i: Int = 0
    while (i < this.size()) {
        yield this[i]
        i = i + step
    }
}

fun partLambdaFor(): Int {
    val items: List<Int> = List<Int>()
    items.append(10)
    items.append(20)
    items.append(30)

    // Over a container: the frame has to know `value` is an `Int`.
    val sumWith: Taker = (n: Int) -> {
        var total: Int = 0
        for (value in items) {
            total = total + value
        }
        println((total + n).toString())
    }
    sumWith(1)

    // Over a machine: `items.everyNth(2)` already *is* iterable, so the wrap is the
    // identity - and only the receiver's type can say so.
    val eachNth: Taker = (n: Int) -> {
        for (value in items.everyNth(2)) {
            println((value + n).toString())
        }
    }
    eachNth(1)

    // The indexed form, still inside the lambda.
    val indexed: Taker = (n: Int) -> {
        for ((value, index) in items.everyNth(2)) {
            println((index + n).toString() + ":" + value.toString())
        }
    }
    indexed(7)
    return 0
}

// ---- pack-args ----

// List literals and packed calls (specs/functions.md, specs/containers.md).
//
// - `listOf<T>(a, b, c)` builds a list from its values in ONE instruction (`Pack`),
//   and up to four elements live in the list itself, so a short literal allocates
//   nothing. Written without type arguments, `T` is the type of the first value.
// - `List<T>(n)` and `List<T>(n, value)` stay the RTL's *count* constructions: a
//   literal is never mistaken for a size.
// - A call packs its trailing arguments into a last parameter that is a `List<T>`
//   (by value) or a `*List<T>` (borrowed). The counted `&List<T>` is deliberately not
//   a pack target (stress/diagnostic-pack-counted) - it takes a boxed copy instead.
// - A parameter that wants a handle accepts a value of the same type: `*T` takes the
//   argument's address at the call, a by-value `T` reads through a `*T`, and `&T`
//   boxes a copy. Types that differ (`List<Int>` against `*List<Str>`) are refused.

// The list borrowed: the packed list is a slot of the *caller's* frame and the
// parameter is its address, so nothing but the elements themselves is copied.
fun addAll(values: *List<Int>): Int {
    var total: Int = 0
    for (*value in values) {
        total = total + * value
    }
    return total
}

// The list by value: the callee owns a copy. Called with a `*List<Int>` argument the
// compiler reads through the pointer; called with values it packs them.
fun sumValues(values: List<Int>): Int {
    var total: Int = 0
    var i: Int = 0
    while (i < values.size()) {
        total = total + values[i]
        i = i + 1
    }
    return total
}

// The counted reference: a value argument is *boxed* - a copy in a `std::shared_ptr`,
// which is what `&x` means everywhere else in the language.
fun countBoxed(values: &List<Int>): Int {
    return values.size()
}

// The template-and-items shape: everything after the template packs.
fun format(shape: Str, items: *List<Str>): Str {
    var out: Str = ""
    var i: Int = 0
    while (i < items.size()) {
        out = out + shape + "[" + i.toString() + "]=" + items[i] + " "
        i = i + 1
    }
    return out
}

fun count(values: *List<Str>): Int {
    return values.size()
}

fun partPackArgs(): Int {
    // The borrow form: any number of trailing values, including none.
    println(addAll(1, 2, 3).toString())
    println(addAll(10).toString())
    println(addAll().toString())

    // A variable of the pointee's type is passed as its address - no `*` to write.
    val numbers: List<Int> = listOf<Int>(4, 5)
    println(addAll(numbers).toString())
    println(addAll(*numbers).toString())

    // The by-value form packs too, and reads through a pointer when given one.
    println(sumValues(6, 7, 8).toString())
    println(sumValues(numbers).toString())
    println(sumValues(*numbers).toString())

    // A counted parameter takes a boxed copy of a value.
    println(countBoxed(numbers).toString())

    // A literal list, in a variable, in an argument and in a call.
    val keywords: List<Str> = listOf<Str>("static", "var", "val")
    println(keywords.size().toString())
    println(keywords[2])
    println(count(*keywords))
    println(format("k", "a", "b"))
    println(count(*listOf<Str>()))          // the empty list, written out

    // A literal without type arguments takes its element type from the first value.
    val primes: List<Int> = listOf(2, 3, 5, 7)
    println(addAll(*primes).toString())

    // The count constructions are the RTL's, and are not literals.
    val zeros: List<Int> = List<Int>(3)
    println(addAll(*zeros).toString())
    val falses: List<Bool> = List<Bool>(4, false)
    println(falses.size().toString())
    return 0
}

// ---- span ----
// Iteration with `Span<T>` instead of range-for: a span over the list's storage,
// indexing into it, `slice` to advance, and `spanOfStr` for a view over a string.

fun sum(items: *List<Int>): Int {
    var total: Int = 0
    var span: Span<Int> = spanOf(items)
    while (!span.isEmpty()) {
        total = total + span[0]
        span = span.slice(1)
    }
    return total
}

fun tailSum(items: *List<Int>): Int {
    val full: Span<Int> = spanOf(items)
    var tail: Span<Int> = full.slice(2)
    var total: Int = 0
    while (!tail.isEmpty()) {
        total = total + tail.at(0)
        tail = tail.slice(1)
    }
    return total
}

// The same list twice: `slice(start, count)` is the C# two-argument form.
fun middleSum(items: *List<Int>): Int {
    val full: Span<Int> = spanOf(items)
    val middle: Span<Int> = full.slice(1, 2)
    var total: Int = 0
    var i: Int = 0
    while (i < middle.size()) {
        total = total + middle[i]
        i = i + 1
    }
    return total
}

// A span over a string's bytes: find, slice it, and copy the piece out.
fun afterColon(text: Str): Str {
    val bytes: StrView = spanOfStr(*text)
    val at: Int = bytes.find(":")
    if (at < 0) {
        return ""
    }
    return bytes.slice(at + 1, bytes.size() - at - 1).toString()
}

// `atPtr(i)`: the element as a *place* - `at`'s `*T` twin, so nothing is copied and a
// write through it reaches what the span borrows. The body is the language's own
// (`Span<T>.atPtr`, cppsrc/rtl/Span.kt), which is also why a call site has a type.
fun bumpSpan(span: Span<Int>, index: Int): Unit {
    val slot: *Int = span.atPtr(index)
    slot[0] = slot[0] + 100
}

// A view *is* a `Span<Char>`, so the span's own extension is reached through it.
fun headByte(text: *Str): *Char {
    return spanOfStr(text).atPtr(0)
}

fun partSpan(): Int {
    var items: List<Int> = List<Int>()
    items.append(4)
    items.append(7)
    items.append(9)

    println(sum(*items))
    println(tailSum(*items))
    println(middleSum(*items))

    val all: Span<Int> = spanOf(*items)
    println(all.size())

    var text: Str = "name:value"
    println(afterColon(text))
    println(spanOfStr(*text).startsWith("name"))
    println(text.substr(0, 4))

    // The place form: the write lands in the list the span borrows.
    bumpSpan(all, 1)
    println(items[1])
    var word: Str = "hey"
    println(headByte(*word)[0])
    return 0
}

// ---- the category's entry ----
fun main(): Int {
    partArrayLayout()
    partBulkList()
    partContainers()
    partDictionary()
    partForArray()
    partForContainer()
    partForPointer()
    partLambdaFor()
    partPackArgs()
    partSpan()
    return 0
}
