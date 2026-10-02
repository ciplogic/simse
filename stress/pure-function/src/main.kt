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

// `sum` is not marked `data` either, but its body writes nothing and calls only borrow-clean
// names, so the fixpoint (`impl_specs/escape-analysis.md`) proves it and this call does not make
// the parameter escape: `p` is borrowed. Before the fixpoint the unmarked call kept the copy.
fun viaSum(p: Point): Int {
    return sum(p) + 1
}

// `borrow` is the author's word for a body the fixpoint *cannot* prove: this one builds a local
// string with `appendStr` (a write, so the proof stops there), but it only reads `p`. The mark is
// about the *caller's* side - it does not borrow `label`'s own parameter (`label` keeps its copy).
borrow fun label(p: Point): Str {
    var out: Str = "p="
    out.appendStr(p.x.toString())
    return out
}

// Calls `label`, trusted by its mark, so `p` is borrowed through the call.
fun viaLabel(p: Point): Str {
    return label(p)
}

// The language's own length accessors are `data` declarations now (`lenOf`, src/rtl/rtl.kt),
// not a name the optimizer lists: two `size()` calls on one unchanged value fold to one
// `simse_lenOf`, and a `List` counts through the same operation as a `Str`.
fun lenTwice(s: Str, xs: List<Int>): Int {
    return s.size() + s.size() + xs.size() + xs.size()
}

// Auto-borrow (impl_specs/escape-analysis.md) trusts the same mark: a parameter of a heavy value
// type a body only *reads* becomes a `*T`, so the call stops copying the argument. A body that
// calls anything the fixpoint cannot prove borrow-clean borrows nothing ("unsure means it
// escapes").

data class Point(var x: Int, var y: Int)

// Read-only fields and no call: `p` is borrowed (`*Point`), so `sum(p)` passes the address.
fun sum(p: Point): Int {
    return p.x + p.y
}

// The view-and-size shape: `spanOfStr`/`size` take the pointer straight through, so neither side
// copies - which is the borrow at its best.
fun width(s: Str): Int {
    return s.size()
}

// `p.x = ...` is a write through the parameter, so the body does not only read and the copy stays:
// `bumpPoint(p)` leaves `p` alone.
fun bumpPoint(p: Point): Int {
    p.x = p.x + 1
    return p.x
}

fun main(args: List<Str>): Int {
    var s: Str = "hello"
    if (args.size() > 1) {
        s = args[1]
    }
    println(pureTwice(s))
    println(impureTwice(s))
    val xs: List<Int> = listOf<Int>(1, 2)
    println(lenTwice(s, xs))

    val p: Point = Point(1, 2)
    println(sum(p))
    println(viaSum(p))
    println(viaLabel(p))
    val w: Str = "world"
    println(width(w))
    println(bumpPoint(p))
    println(p.x)
    return 0
}
