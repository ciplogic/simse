package walk_machine

// The for-walk probe, machine leg (impl_specs/for-walk.md, "Measurement"): the flat scan
// exactly as today's lowering spells it - `for` over a List, the span machine. The counter
// and pair legs spell the same loop the way the walk would emit it; all three must print
// the same checksum, and the A/B decides whether the walk exists.

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
        // The `^ p` keeps the passes from collapsing into one multiply.
        for (x in vals) {
            total += Int64(x) ^ Int64(p)
        }
    }
    println(vals.size())
    println(total)
    return 0
}
