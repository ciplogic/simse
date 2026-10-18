package fixtures

// A program naming a *generated* declaration: `@SmGen("cpp", "simse_str_toFloat")` says the
// C++ is the `strops` section's, which the program does not carry unless something names
// it - so this case is the `res` text being reached by a program that is not the RTL
// (`impl_specs/generators.md`, `impl_specs/native-interop.md`).

@SmGen("cpp", "simse_str_toFloat")
fun parse(text: Str): Opt<Float64>

fun main(): Int {
    println(parse("2.5").hasValue())
    println(parse("nope").hasValue())
    println(parse("2.5").value())
    return 0
}
