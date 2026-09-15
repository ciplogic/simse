package fixtures

// `for` over the containers the prelude gives a `smToYield` (impl_specs/for.md): a
// `List<T>` and an `Array<T>`.
//
// What this fixture pins is the *emission* around iteration. A machine's class is named
// after its receiver (`List_smToYield_yieldable`, `Array_smToYield_yieldable`), because
// the prelude writes one `smToYield` per container; and a prelude body is emitted for the
// receiver the program names, so the `Span` machine - the prelude has one - must not
// appear in the emitted C++ (tests/golden/for_iteration.kt.cpp.expected).

fun total(items: *List<Int>): Int {
    var sum: Int = 0
    for (value in items) {
        sum = sum + value
    }
    return sum
}

fun arrayTotal(numbers: *List<Int>): Int {
    val arr: Array<Int> = numbers.toArray()
    var sum: Int = 0
    for ((value, index) in arr) {
        if (index == 0) {
            continue
        }
        sum = sum + value
    }
    return sum
}

fun main(): Int {
    val numbers: List<Int> = List<Int>()
    numbers.append(1)
    numbers.append(2)
    println(total(*numbers).toString())
    println(arrayTotal(*numbers).toString())
    return 0
}
