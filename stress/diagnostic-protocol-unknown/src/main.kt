package fixtures

// A `when` clause naming a protocol nobody declared is reported where the clause is
// (specs/declarations.md, "Protocols").

protocol fun Printable<T> T.toString(): Str

fun show<T>(value: *T) when T: Printable, Comparable {
    print(value.toString())
}

fun main(): Int {
    return 0
}
