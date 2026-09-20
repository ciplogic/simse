package fixtures

// The FFI path a *program* takes: naming a symbol the runtime already has, with the
// attribute that says the C++ is elsewhere (`@SmGen("cpp", sym)`, the form the deleted
// `native(sym)` spelled - impl_specs/native-interop.md). `simse_native_readFile` is the
// `fileio` section's always-emitted reader, which is what `emit: always` is for: no
// declaration in the prelude names it, so nothing would reach that section otherwise.
@SmGen("cpp", "simse_native_readFile")
fun readFile(path: Str): Str

fun main(): Int {
    val text: Str = readFile("stress/native-read-file/native_data.txt")
    println(text.size())
    return 0
}
