package fixtures

// `T` is named by no argument and the lambda writes none of its own parameter types: nothing
// can fix `T` (C++ cannot deduce a template parameter through a callable either), so the call
// is reported rather than left to fail in the emitted C++. `peek<Int>(...)` and an annotated
// lambda (`peek((x: Int) -> ...)`) are the two escapes (`stress/generic-callable`).
fun peek<T>(f: (T) -> T): Bool {
    return true
}

fun main(): Int {
    val b: Bool = peek((x) -> x+1)
    if (b) {
        println("yes")
    }
    return 0
}
