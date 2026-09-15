package fixtures
fun Str.firstByte(): Char {
    return this[0]
}

fun (*Int).valueAt(): Int {
    return *this
}

fun firstOrNone(this: List<Int>): Opt<Int> {
    return Opt<Int>()
}
