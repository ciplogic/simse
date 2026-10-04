package fixtures

// Two protocols of one type parameter declaring the same method leave the call with no
// single implementation (specs/declarations.md, "Protocols").

protocol fun A<T> T.toString(): Str

protocol fun B<T> T.toString(): Str

fun show<T>(value: *T) when T: A, B {
    print(value.toString())
}

fun main(): Int {
    return 0
}
