package fixtures
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
fun bump(span: Span<Int>, index: Int): Unit {
    val slot: *Int = span.atPtr(index)
    slot[0] = slot[0] + 100
}

// A view *is* a `Span<Char>`, so the span's own extension is reached through it.
fun headByte(text: *Str): *Char {
    return spanOfStr(text).atPtr(0)
}

fun main(): Int {
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
    bump(all, 1)
    println(items[1])
    var word: Str = "hey"
    println(headByte(*word)[0])
    return 0
}
