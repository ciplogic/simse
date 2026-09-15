package fixtures

// `for` over the fixed-length sequence and over borrowed storage: `Array<T>` and
// `Span<T>` walk with their own `smToYield` in the prelude, written in the language's
// `yield` like the `List<T>` one (impl_specs/for.md).
//
// A per-container machine is why a machine class carries its receiver's name
// (`Array_smToYield_yieldable`), and why a program that iterates one container does not
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

fun main(): Int {
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
