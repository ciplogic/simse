package walk_counter

// The for-walk probe, counter leg (impl_specs/for-walk.md, variant B): the same scan as the
// machine leg, spelled the way `forWalkAt` lowers it - the span bound once, a counter over
// `span.ptr[i]`, the element read before the counter steps.

import kit
import unsafe

val N: Int = 1000000
val PASSES: Int = 8

fun main(): Int {
    var vals: List<Int> = List<Int>()
    var r: UInt64 = UInt64(12345)
    var i: Int = 0
    while (i < N) {
        r = xsNext(r)
        vals.append(r.uMod(UInt64(1000)).toInt())
        i++
    }
    var total: Int64 = 0
    for (p in 0 until PASSES) {
        val w: Span<Int> = spanOf(vals)
        var n: Int = 0
        while (n < w.len) {
            total += Int64(w.ptr[n]) ^ Int64(p)
            n++
        }
    }
    println(vals.size())
    println(total)
    return 0
}
