package diaglambdacontract

// A lambda used against a callable type must agree with it: the body's proved answer
// against the position's declared return. The disagreement used to surface as a C++
// conversion error at the call; it is the compiler's report now, at the lambda.

fun apply(f: (Int) -> Str, n: Int): Str {
    return f(n)
}

fun main(): Int {
    println(apply((n: Int) -> n + 1, 4))
    return 0
}
