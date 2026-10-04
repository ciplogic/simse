package fixtures

// A protocol's receiver is its subject: the first type parameter when it declares any
// (specs/declarations.md, "Protocols").

protocol fun Bad<T, U> U.toString(): Str

data class Note(var text: Str)

fun main(): Int {
    return 0
}
