package fixtures
// Negative fixture: a case label that is not a constant expression.
fun f(): Int {
    return 1
}

fun pick(n: Int): Int {
    switch (n) {
        case f():
            return 1
        default:
            return 0
    }
}
