package fixtures

// The pre-migration protocol spelling (`protocol Name fun ...`) stays accepted while the
// tree moves the name after `fun` (`protocol fun Name<T> ...`). This case pins the bridge;
// delete it together with the old position (specs/declarations.md, "Protocols").

protocol Show fun <T> T.toString(): Str

data class Tag(var name: Str)

fun Tag.toString(): Str {
    return this.name
}

fun printOne<T>(value: *T) when T: Show {
    print(value.toString())
    print("\n")
}

fun main(): Int {
    val tag = Tag("legacy")
    printOne(*tag)
    return 0
}
