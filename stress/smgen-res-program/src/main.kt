package fixtures

// A generated implementation the *program* carries: `src/_res.md` holds the `triple`
// section, and the emitter reads the tree's own resources before the compiler's
// (impl_specs/generators.md, "The `res` generator"). The symbol is the attribute's third
// argument, and the resource's own `symbol:` says the same thing - the two spellings of
// one declaration, not two symbols.
@SmGen("res", "triple", "fixtures_triple")
fun triple(value: Int): Int

fun main(): Int {
    println(triple(14))
    return 0
}
