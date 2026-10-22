package bench_sum

// Control: build a flat i32 array, then scan it eight times. Port of
// aardappel/goose bench/sum.goose (the C++ reference is bench/cpp/sum.cpp).

import kit

val N: Int = 1000000 // BENCH_N
val PASSES: Int = 8

fun main(): Int {
    var vals: List<Int> = List<Int>()
    var r: Int64 = 12345
    var i: Int = 0
    while (i < N) {
        r = xsNext(r)
        vals.append(Int(xsMod(r, 1000)))
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
