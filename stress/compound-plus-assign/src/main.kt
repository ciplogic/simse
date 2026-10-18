package fixtures

// `+=` resolved as an operator before the fold (impl_specs/operator-surface.md): a
// `plusAssign` declaration matching the receiver is the in-place append - `out += part`
// on a Str (either operand width, one C++ overload set), `parts += item` on a List - and
// where none matches, the plain binary fold (`n += 1`, `f += 0.25`, `n -= 1`), exactly
// the emission those always had.

fun main(): Int {
    var out: Str = "a"
    out += "b"
    out += 'c'
    out += ""
    println(out)
    var lines: List<Str> = List<Str>(0)
    lines += "one"
    lines += "two"
    println(lines.size())
    println(lines[1])
    var nums: List<Int> = List<Int>(0)
    var i: Int = 0
    while (i < 5) {
        nums += i * i
        i += 1
    }
    println(nums.size())
    println(nums[4])
    var n: Int = 41
    n += 1
    println(n)
    n -= 2
    println(n)
    var f: Float64 = 1.5
    f += 0.25
    println(f)
    return 0
}
