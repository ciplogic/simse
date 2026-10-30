package diagyieldlambda

// A lambda declared in a yielding body would have to live across a `yield`, and a machine
// field needs a type - a closure class is not a type the field can name yet. The refusal
// is the yield machinery's own, at the function, whichever scope the lambda is declared in
// (the block a loop body keeps around it included - that shape used to die as
// `unsupported: statement` instead).

fun feed(limit: Int): ..Int {
    var i: Int = 0
    while (i < limit) {
        val f = (n: Int) -> n + 1
        yield f(i)
        i = i + 1
    }
}

fun main(): Int {
    for (v in feed(4)) {
        println(v.toString())
    }
    return 0
}
