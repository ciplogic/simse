package fixtures

// T42: `for` over a container. The two forms are the same `while` the parser writes,
// with the iterated expression wrapped in an invisible `smToYield()` call: `List<T>`
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

fun main(): Int {
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
