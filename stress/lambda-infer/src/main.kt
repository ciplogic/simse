package fixtures

// A lambda's parameters, proved by its own body when neither an annotation nor an expected
// callable type names them (impl_specs/linear-lowering.md, `sema/LambdaInfer.kt`): the
// closure is monomorphic, so each constraint site is a *proof* - every arity-matching
// declaration must agree - and an ambiguous site is a refusal that keeps the emitter's own
// diagnostic (`lambda-infer-refuse` pins that side).
//
//   - pass-through: `n` is argument 0 of `width`, whose declaration names it `Str`;
//   - a field selector: `p` reads `.x`, and `Point` is the only class that owns an `x`;
//   - a member call's argument: `seed` is `xs.contains`'s item, `Int` once `xs: List<Int>`
//     binds the extension's `T`.

data class Point(var x: Int, var y: Int)

fun width(s: Str): Int {
    return s.size()
}

fun main(): Int {
    val w = (n) -> width(n)
    println(w("hey").toString())
    val px = (p) -> p.x + 1
    println(px(Point(2, 3)).toString())
    var xs: List<Int> = List<Int>(0)
    xs.append(1)
    xs.append(2)
    val has = (seed) -> xs.contains(seed)
    println(has(2).toString())
    return 0
}
