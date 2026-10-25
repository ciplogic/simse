package bench_strlist

// Split a text of N words, keep them as a list of strings, scan it four
// times. Port of aardappel/goose bench/strlist.goose: `Str` is an inline byte
// string, so a `List<Str>` is the owning-words shape of the C++ row
// (bench/cpp/strlist.cpp VARIANT 0).

import kit
import unsafe

val N: Int = 200000 // BENCH_N
val PASSES: Int = 4

fun main(): Int {
    // One text buffer of space-separated random words.
    var text: Str = Str()
    var r: UInt64 = UInt64(12345)
    var i: Int = 0
    while (i < N) {
        r = xsNext(r)
        val wl: Int = 3 + r.uMod(UInt64(18)).toInt()
        var j: Int = 0
        while (j < wl) {
            r = xsNext(r)
            text.append(Char(97 + r.uMod(UInt64(26)).toInt()))
            j++
        }
        text.append(' ')
        i++
    }
    // Split it; the trailing separator's empty part is not a word.
    var words: List<Str> = List<Str>()
    val tl: Int = text.size()
    var k: Int = 0
    while (k < tl) {
        val start: Int = k
        while (k < tl && byteAt(text, k) != 32) {
            k++
        }
        words.append(text.substr(start, k - start))
        while (k < tl && byteAt(text, k) == 32) {
            k++
        }
    }
    // Scan: length and first/last character of each word.
    var total: Int64 = 0
    for (p in 0 until PASSES) {
        for (w in words) {
            total += Int64(w.size()) + Int64(byteAt(w, 0)) + Int64(byteAt(w, w.size() - 1))
        }
    }
    println(words.size())
    println(total)
    return 0
}
