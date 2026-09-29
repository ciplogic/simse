package fixtures

// A program naming a *generated* declaration: `@SmGen("cpp", "simse_str_replace")` says the
// C++ is the `strops` section's, which the program does not carry unless something names
// it - so this case is the `res` text being reached by a program that is not the RTL
// (`impl_specs/generators.md`, `impl_specs/native-interop.md`).

@SmGen("cpp", "simse_str_replace")
fun replaceAll(text: Str, from: Str, to: Str): Str

fun main(): Int {
    println(replaceAll("banana", "na", "NA"))
    return 0
}
