package diaglambdacontractparam

// The parameter side of the same contract: the lambda's own annotation against the
// callable position's parameter type.

fun width(s: Str): Int {
    return s.size()
}

fun apply(f: (Int) -> Int, n: Int): Int {
    return f(n)
}

fun main(): Int {
    println(apply((s: Str) -> width(s), 4))
    return 0
}
