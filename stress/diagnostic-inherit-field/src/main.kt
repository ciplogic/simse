package app

// The layout is one flat list of field names, so an inherited name cannot repeat: `x` is
// already a field of B, and the derived declaration cannot shadow it with another field.

data class B(val x: Int)
data class D(val x: Int) : B

fun main(): Int {
    return 0
}
