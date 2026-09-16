package fixtures

// The bitwise operators (`& | ^ << >>`) and their compound forms (`&= |= ^= <<= >>=`),
// specs/built-in-types.md, "Operators".
//
// Their precedence is Python's and Rust's - the bitwise pair binds *tighter* than a
// comparison, the shifts tighter still - so a bit test needs no parentheses and
// `flags & mask == 0` cannot silently mean `flags & (mask == 0)`. The shifts sit between
// `+` and `&`, so `1 << 2 + 1` is `1 << 3`.
//
// `>>` is one token (the shift), so the *type* parser splits it when it closes a nested
// type-argument list: this case declares a `List<List<Int>>` and a
// `Dictionary<Str, List<Int>>`, whose closers scan as `>>` and have to be taken one `>` at
// a time.

data class Bits(var flags: Int, var count: Int)

fun pack(low: Int, high: Int): Int {
    return (high << 4) | low
}

// An *operand* is a position the value/handle conversion applies to as well: `by` is a
// `*Int`, so `from + by` is `from + *by` (specs/memory-model.md) - the same rule that lets
// a parameter be a `*T` without every call spelling the `*`, read where the operation
// needs a value.
fun addThrough(from: Int, by: *Int): Int {
    return from + by
}

fun inner(rows: List<List<Int>>): Int {
    return rows[1][0]
}

fun named(tables: Dictionary<Str, List<Int>>): Int {
    return tables.get("a").value()[1]
}

fun main(): Int {
    // The operators.
    println((6 & 3).toString())
    println((6 | 1).toString())
    println((6 ^ 3).toString())
    println((1 << 5).toString())
    println((32 >> 2).toString())

    // Precedence: the shift binds tighter than `+` (so `1 << 2 + 1` is `1 << 3`), and the
    // bitwise pair binds tighter than `==` (so this test is on the mask, not on the
    // comparison).
    println((1 << 2 + 1).toString())
    println(((0 << 4) | 5).toString())
    println((6 & 3 == 3).toString())
    var flags: Int = 0
    flags = (1 << 2) | 1
    println((flags & 4 != 0).toString())

    // The compound forms: the place is located once, read, folded and written back
    // (specs/memory-model.md), exactly as the arithmetic ones are.
    var bits: Int = 1
    bits <<= 3
    println(bits.toString())
    bits >>= 1
    println(bits.toString())
    bits |= 8
    println(bits.toString())
    bits &= 12
    println(bits.toString())
    bits ^= 5
    println(bits.toString())

    // ... on a field, on an element, and through the pointer a `*T` parameter is.
    val b: Bits = Bits(1, 0)
    b.flags <<= 4
    println(b.flags.toString())
    b.flags |= 3
    println(b.flags.toString())
    val xs: List<Int> = listOf<Int>(1, 2, 3)
    xs[1] <<= 2
    println(xs[1].toString())
    xs[1] &= 6
    println(xs[1].toString())

    // A nested type-argument list: its closer is the `>>` the scanner makes of the shift
    // operator, and the parser takes one `>` out of it per list.
    val rows: List<List<Int>> = listOf<List<Int>>(listOf<Int>(1, 2), listOf<Int>(9, 8))
    println(inner(rows).toString())
    val tables: Dictionary<Str, List<Int>> = Dictionary<Str, List<Int>>()
    tables.insert("a", listOf<Int>(7, 11))
    println(named(tables).toString())

    // A handle as an operand: read through, exactly as a call argument is.
    var step: Int = 4
    println(addThrough(10, *step).toString())
    return 0
}
