package fixtures

// The `native("sym")` spelling of a generated declaration (impl_specs/generators.md).
// Its twin, stress/smgen-cpp, writes the same declaration as
// `@SmGen("cpp", "defined-in-headers", "sym")`; the two amalgamations must agree byte
// for byte (`bun tools/smgen.js`), so the two files are line for line identical except
// for the one declaration line, line 9: this one is the sugar.

native("simse_str_trim") fun trimmedText(text: Str): Str

fun main(): Int {
    println(trimmedText("  padded  "))
    return 0
}
