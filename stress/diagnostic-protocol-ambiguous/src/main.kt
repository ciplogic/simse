package fixtures

// Two protocols of one type parameter declaring the same method leave the call with no
// single implementation (specs/declarations.md, "Protocols").

protocol A fun <T> T.toString(): Str

protocol B fun <T> T.toString(): Str

fun show<T>(value: *T) when T: A, B {
    print(value.toString())
}

fun main(): Int {
    return 0
}
