package fixtures

// A compound assignment on a handle-typed parameter is its *pointee's* operator
// (impl_specs/operator-surface.md): `counter += 1` on a `counter: *Int` reads and writes
// through the pointer - the short spelling of a deref the statement position cannot parse
// (`*p += x` is not a statement) - and never C++ pointer arithmetic, which the language
// does not have. The rule is the one every value position already applies to a handle.

fun bump(counter: *Int): Unit {
    counter += 1
}

fun scale(value: *Float64, by: Float64): Unit {
    value += by
}

fun halve(value: *Int): Unit {
    value += 0 - value / 2
}

fun main(): Int {
    var n: Int = 41
    bump(*n)
    println(n)
    var f: Float64 = 1.5
    scale(*f, 0.25)
    println(f)
    var big: Int = 100
    halve(*big)
    println(big)
    bump(*n)
    println(n)
    return 0
}
