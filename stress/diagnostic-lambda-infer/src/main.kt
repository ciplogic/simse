package fixtures

// `T` is named by no argument: the callable parameter's own types do not infer it (C++ cannot
// deduce a template parameter through a callable either), so the call is reported rather than
// left to fail in the emitted C++. `peek<Int>(...)` is the escape (`stress/generic-callable`).
fun peek<T>(f: (T) -> T): Bool {
    return true
}

fun main(): Int {
    val b: Bool = peek((x: Int) -> x+1)
    if (b) {
        println("yes")
    }
    return 0
}
