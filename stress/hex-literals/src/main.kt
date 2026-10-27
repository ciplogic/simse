package hexliterals

// Hexadecimal `Int` literals (specs/built-in-types.md): `0x`/`0X` and the hexadecimal
// digits, the spelling a C flag or mask wants (`SQLITE_OPEN_READWRITE` is 0x00000002).
// A magnitude past `Int32` is an `Int64` literal exactly as a long decimal is, the value
// folds like any integer, and `0x` alone stays the decimal `0` beside a name.

fun main(): Int {
    val mask: Int = 0xFF
    println(mask.toString())
    val flags: Int = 0X00000005
    println((flags & mask).toString())
    println((0x10 + 0x7).toString())
    val big: Int64 = 0xFFFFFFFF
    println(big.toString())
    val zero: Int = 0x0
    println(zero.toString())
    return 0
}
