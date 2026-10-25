package for_walk_yield

// The for-walk's keep-the-machine side of the contract (impl_specs/for-walk.md): a generator
// receiver (`..T`, `iterValues` the identity on it) keeps the machine under `--for-walk` -
// the walk never lowers it. The yielding bodies here iterate with `while`, the supported
// spelling: a `for` inside a yielding body is for.md's own open problem (the inner machine
// would have to become a task field) and miscompiles in debug builds with the machine path
// too - this case pins what works, not that.

fun countTo(limit: Int): ..Int {
    var i: Int = 0
    while (i < limit) {
        yield i
        i++
    }
}

fun evens(vals: List<Int>): ..Int {
    var i: Int = 0
    val view: Span<Int> = spanOf(vals)
    while (i < view.len) {
        val x: Int = view.ptr[i]
        if (x % 2 == 0) {
            yield x
        }
        i++
    }
}

fun partGeneratorReceiver(): Int {
    var total: Int = 0
    for (n in countTo(6)) {
        total += n * n
    }
    println(total)

    // The pointer wrap has no machine form; a generator is iterated by value only.
    var texts: Str = ""
    for (n in countTo(4)) {
        texts = texts + Int(n).toString()
    }
    println(texts)
    return 0
}

fun partYieldingBody(): Int {
    var vals: List<Int> = List<Int>()
    var i: Int = 0
    while (i < 10) {
        vals.append(i)
        i++
    }

    // A generator receiver inside a yielding body: the suspension machinery keys on the
    // machine structure (`impl_specs/yield.md`), so the walk must not touch it - the outer
    // `for` over `evens(vals)` keeps its machine, and the generator drains lazily.
    var picked: Str = ""
    for (e in evens(vals)) {
        picked = picked + Int(e).toString()
    }
    println(picked)
    return 0
}

fun main(): Int {
    partGeneratorReceiver()
    partYieldingBody()
    return 0
}
