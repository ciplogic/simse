package fixtures

// A program naming a *generated* declaration: `@SmGen("cpp", "simse_str_trim")` says the
// C++ is the `strops` section's, which the program does not carry unless something names
// it - so this case is the `res` text being reached by a program that is not the RTL
// (`impl_specs/generators.md`, `impl_specs/native-interop.md`).

@SmGen("cpp", "simse_str_trim")
fun trimmedText(text: Str): Str

fun main(): Int {
    println(trimmedText("  padded  "))
    return 0
}
