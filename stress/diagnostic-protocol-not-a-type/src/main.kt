package fixtures

// A protocol is a constraint, not a type: it cannot be named where a type is expected
// (specs/declarations.md, "Protocols"). There is no existential form.

protocol fun Printable<T> T.toString(): Str

fun describe(value: Printable): Str {
    return "x"
}

fun main(): Int {
    return 0
}
