package fixtures

// A `for` over a *temporary*: `makeWords()` is a value that dies at the end of the
// declaration's initializer unless something keeps it alive. The lowering hoists the
// iterated expression into a function-scope slot (`_sm_exprN`), so the machine's receiver
// points at storage that outlives the loop - this case pins that, and pins it with more
// than four elements so a dangling receiver would read a *freed heap buffer* rather than a
// stale inline buffer.
//
// It is the guard for the "iterate a container through its span" refactor: `spanOf(list)`
// puts a view in the iterated position, and that view must be kept alive the same way.

fun makeWords(): List<Str> {
    var xs: List<Str> = List<Str>()
    xs.append("alpha")
    xs.append("beta")
    xs.append("gamma")
    xs.append("delta")
    xs.append("epsilon")
    return xs
}

fun main(): Int {
    // The value form: the loop variable is a fresh copy per iteration.
    for (word in makeWords()) {
        println(word)
    }

    // The pointer form: the same temporary, iterated by place. A write through the loop
    // variable reaches the temporary (which is fine - it dies with the loop).
    for (*word in makeWords()) {
        println(*word)
    }
    return 0
}
