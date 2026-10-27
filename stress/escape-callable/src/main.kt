package escapecallable

// A call to a callable-by-value *parameter* no longer dirties the enclosing body's proof
// (impl_specs/escape-analysis-plan.md, "Smaller cleanups"): `repeat`'s `body(i)` and
// `forEach`'s `f(x)` were opaque callees, so their own declarations never proved and every
// caller of them was borrow-dirty - `walk` copied its whole list at every call. The
// signature check is the soundness: a callable whose parameters are all values cannot
// write through anything the enclosing body lent, so the call imposes no obligation.
//
// `shout`'s block names no parameter, so it ignores the index: the closure takes `repeat`'s
// own contract whole (`Func<void(Int)>`, the argument an unnamed `_sm_arg`), the shape a
// zero-name block is - not a zero-parameter lambda.

fun shout(base: Str, times: Int): Str {
    repeat(times) {
        println(base)
    }
    return base + "!"
}

fun walk(words: List<Str>): Int {
    words.forEach {
        println(it)
    }
    return words.size()
}

// The user's own combinator proves the same way: `step` is callable-by-value, so `twice`
// joins the trusted set and its callers borrow through it.
fun twice(n: Int, step: (Int) -> Unit): Unit {
    step(n)
    step(n)
}

fun main(): Int {
    println(shout("hey", 2))
    println(walk(listOf<Str>("a", "b")))
    twice(3) {
        println("hit")
    }
    return 0
}
