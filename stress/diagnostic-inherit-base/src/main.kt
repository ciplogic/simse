package app

// A base must be a plain data class: a `union class` is a tagged one-of, not a layout to
// append fields to, and the declaration is rejected where it is written
// (specs/declarations.md, "Inheritance"). See `stress/inheritance` for the working shape.

union class U(var A: Int)

data class D(val z: Int) : U

fun main(): Int {
    return 0
}
