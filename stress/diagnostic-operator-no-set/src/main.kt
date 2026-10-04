package fixtures

// A read through `operator get` is a *value*, not a place (specs/functions.md): `x[i] = v`
// needs `operator set`, and a type that declares only the getter is a diagnostic (the
// lowering could only report the shape as inexpressible).

data class Box(var n: Int)

operator fun Box.get(index: Int): Int {
    return this.n
}

fun main(): Int {
    val box: Box = Box(1)
    box[0] = 2
    return box[0]
}
