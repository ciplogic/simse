package app

// `&&` and `||` short-circuit: the right operand is not evaluated once the result is
// decided. The operands here have a side effect (`calls`), so an eager lowering shows up as
// an extra call, not just wasted work - the shape the compiler's own `isCompoundAssignOp`
// chain has, minus the observable effect.

var calls: Int = 0

fun tick(value: Bool): Bool {
    calls = calls + 1
    return value
}

fun main(): Int {
    calls = 0
    val a = tick(false) && tick(true)
    val afterAnd = calls
    calls = 0
    val b = tick(true) || tick(false)
    val afterOr = calls
    println(afterAnd.toString())
    println(afterOr.toString())
    println(a.toString())
    println(b.toString())
    return 0
}
