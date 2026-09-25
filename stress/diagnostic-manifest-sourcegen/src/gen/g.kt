package gen

// A module that ships source generators (its own simse.md) and declares one the compiler does
// not carry. `sourcegen: true` no longer blocks the compilation by itself - the compiler carries
// the built-in generators, so such a module is usable - but a declaration whose generator nothing
// provides cannot be emitted, and the failure names it (`unknown source generator 'nope'`).

@SmGen("nope")
fun missing(value: Int): Str

fun main(): Int {
    println(1)
    return 0
}
