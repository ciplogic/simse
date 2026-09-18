package fixtures

// The `@SmGen("cpp", "defined-in-headers", "sym")` spelling of a generated
// declaration (impl_specs/generators.md). Its twin, stress/smgen-native, writes the
// same declaration as `native("sym")`; the two amalgamations must agree byte for byte
// (`bun tools/smgen.js`), so the two files are line for line identical except for the
// one declaration line, line 9: this one spells the attribute out.

@SmGen("cpp", "defined-in-headers", "simse_str_trim") fun trimmedText(text: Str): Str

fun main(): Int {
    println(trimmedText("  padded  "))
    return 0
}
