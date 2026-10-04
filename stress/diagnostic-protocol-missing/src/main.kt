package fixtures

// A concrete type that does not have the protocol's method is a diagnostic *at the call*,
// not a C++ error on the emitted dispatch (specs/declarations.md, "Protocols").

protocol fun Printable<T> T.toString(): Str

data class Rock(var weight: Int)

fun show<T>(value: *T) when T: Printable {
    print(value.toString())
}

fun main(): Int {
    val rock = Rock(3)
    show(*rock)
    return 0
}
