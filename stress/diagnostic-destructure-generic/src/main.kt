package fixtures

// A generic data class is not destructured yet (specs/declarations.md, "Destructuring
// bind"): the binders would need the instantiation's field types.

data class Box<T>(var value: T)

fun main(): Int {
    val (v) = Box<Int>(5)
    println(v.toString())
    return 0
}
