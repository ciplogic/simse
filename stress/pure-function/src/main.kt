package purefunction

// `data` marks a function *pure*: no side effects, the result a function of its receiver and
// arguments. The compiler trusts the mark, and a repeated call of an unchanged argument is
// then one call (`impl_specs/linear-il.md`, "ReusePure"). A function that is *not* marked
// keeps both calls, however alike they look - the pass never guesses.

data fun Str.toLen(): Int {
    return this.size()
}

var bumps: Int = 0

// Not marked: writing the static is a side effect, so the two calls must both run.
fun Str.bump(): Int {
    bumps = bumps + 1
    return this.size() + bumps
}

// Both calls name the same pure callee with the same unchanged argument: one call, and the
// second reads its slot, so this is 5 + 5.
fun pureTwice(s: Str): Int {
    return s.toLen() + s.toLen()
}

// `bump` is not marked, so both calls run: 5 + 1 then 5 + 2 - the sum proves it.
fun impureTwice(s: Str): Int {
    return s.bump() + s.bump()
}

fun main(args: List<Str>): Int {
    var s: Str = "hello"
    if (args.size() > 1) {
        s = args[1]
    }
    println(pureTwice(s))
    println(impureTwice(s))
    return 0
}
