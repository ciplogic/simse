package fixtures

// The `native("sym")` spelling of a generated declaration (impl_specs/generators.md).
// Its twin, stress/smgen-cpp, writes the same declaration as `@SmGen("cpp", "sym")`,
// which the formatter keeps on a line of its own: the two files are identical except
// for these comments and the shape of those two declaration lines - the `fun` is on
// line 10 in both, so every source-map comment the emitted C++ carries agrees and the
// two amalgamations can be compared byte for byte (`bun tools/smgen.js`).

native("simse_str_trim") fun trimmedText(text: Str): Str

fun main(): Int {
    println(trimmedText("  padded  "))
    return 0
}
