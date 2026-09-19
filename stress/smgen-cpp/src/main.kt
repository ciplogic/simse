package fixtures

// The `@SmGen("cpp", "sym")` spelling of a generated declaration
// (impl_specs/generators.md). Its twin, stress/smgen-native, writes the same
// declaration as `native("sym")`; the two files are identical except for these
// comments and the shape of those two declaration lines - the `fun` is on line 10 in
// both, so the two amalgamations can be compared byte for byte (`bun tools/smgen.js`).

@SmGen("cpp", "simse_str_trim")
fun trimmedText(text: Str): Str

fun main(): Int {
    println(trimmedText("  padded  "))
    return 0
}
