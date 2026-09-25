package fixtures

// `!!` extracts a payload, so it is not a value a `return` can hand back: the failure path is
// the only half a `return` has a use for, and `return x` (no operator) is how that is spelled.

fun readNumber(text: Str): Res<Int> {
    return Res<Int>.ok(1)
}

fun bad(text: Str): Res<Int> {
    return readNumber(text)!!
}
