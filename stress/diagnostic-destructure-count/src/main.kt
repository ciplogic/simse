package fixtures

// The binder count is the field count, base fields included (specs/declarations.md,
// "Destructuring bind") - a two-binder pattern does not fit three fields.

data class Point(var x: Int, var y: Int)

data class Point3D(var z: Int) : Point

fun main(): Int {
    val (a, b) = Point3D(1, 2, 3)
    println(a.toString() + b.toString())
    return 0
}
