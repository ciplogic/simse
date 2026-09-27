package fixtures

// A `native` declaration with no library is rejected by name at collection: there is nothing to
// resolve the symbol from (impl_specs/native-interop.md).

@SmGen("native")
fun missingSymbol(): Int

fun main(): Int {
    println(missingSymbol())
    return 0
}
