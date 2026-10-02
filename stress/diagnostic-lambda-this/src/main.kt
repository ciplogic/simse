package fixtures

// `this` inside a lambda: the closure captures by value and the enclosing receiver is not
// one of the captures, so the compiler reports it rather than silently naming the closure.

fun Int.makeAdder(): (Int) -> Int {
    return (v: Int) -> v+this
}

fun main(): Int {
    val f: (Int) -> Int = 10.makeAdder()
    println(f(5).toString())
    return 0
}
