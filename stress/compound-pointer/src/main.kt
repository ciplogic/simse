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
    part2()
    return 0
}

// The same rule through a matching `plusAssign`: `out += x` on an `out: *Str` is the
// in-place append *through* the pointer (the emitter's native resolution adapts the
// receiver, exactly as `out.append(ch)` always has), and a `*List` appends the same way.
// A `*Int` keeps the fold - no `plusAssign` matches it, which is the fallback's point.
fun appendLine(out: *Str, text: Str): Unit {
    out += text
    out += '\n'
}

fun addAll(parts: *List<Int>, item: Int): Unit {
    parts += item
}

fun part2(): Int {
    var text: Str = "hello"
    appendLine(*text, "world")
    print(text)
    var nums: List<Int> = List<Int>(0)
    addAll(*nums, 7)
    addAll(*nums, 8)
    println(nums.size())
    println(nums[1])
    return 0
}
