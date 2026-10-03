package app

// A `union class` with two fields of one type: `U(5)` could not tell which arm is meant, so
// the declaration is the diagnostic (two *distinct* types - two enums, say - are fine). See
// `stress/unions` for the working shape.

union class U(var A: Int, var B: Int)

fun main(): Int {
    return 0
}
