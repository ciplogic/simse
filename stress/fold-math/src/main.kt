package foldmath

import math

// The math folds (impl_specs/fold-math.md), pinned two ways. The identities fold ahead
// of sema - `sin(0.0)` is `0.0`, `cos(0.0)` is `1.0`, `pow(x, 0.0)` is `1.0`,
// `log(1.0)` is `0.0` - and the emission golden shows the literals with no wrapper
// calls beside them; a call the identities cannot answer (`sin(0.75)`) reaches the
// library wrapper at run time, so the printed value is libm's own. The division
// peephole is bit-exact for power-of-two divisors: `x / 2.0` becomes `x * 0.5` and
// `x / 0.25` becomes `x * 4.0`.

fun halve(x: Float64): Float64 {
    return x / 2.0
}

fun widen(x: Float64): Float64 {
    return x / 0.25
}

fun main(): Int {
    val a: Float64 = sin(0.0)
    val b: Float64 = cos(0.0)
    val c: Float64 = pow(2.0, 0.0)
    val d: Float64 = log(1.0)
    val e: Float64 = sin(0.75)
    println(a + b + c + d)
    println(e)
    println(halve(5.0))
    println(widen(0.5))
    return 0
}
