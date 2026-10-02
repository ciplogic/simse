package fixtures

// Two declarations share the name and arity, and a lambda fits both: the plain `T` takes the
// closure as its value (an exact C++ match, no conversion) while the callable parameter is
// what the call plainly means. The compiler reports the call instead of letting C++ silently
// take the plain one; `pick<Int>(...)` disambiguates (`stress/generic-callable`).
fun pick<T>(x: T): Str {
    return "value"
}

fun pick<T>(x: (T) -> T): Str {
    return "callable"
}

fun main(): Int {
    println(pick((x: Int) -> x+1))
    return 0
}
