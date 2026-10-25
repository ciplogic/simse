package bench_records

// Build a log of N variant records, aggregate it four times. Port of
// aardappel/goose bench/records_var.goose. Goose stores each record at its own
// variant's size; Simse's union class pads every element to the largest arm,
// which is the shape the C++ variant rows write (bench/cpp/records.cpp
// VARIANT 1/2), and the string payload is an inline `Str`.

import kit
import unsafe

val N: Int = 500000 // BENCH_N
val PASSES: Int = 4

data class TickK()
data class MoveK(var id: Int, var dx: Int, var dy: Int)
data class SayK(var id: Int, var text: Str)
data class QuitK(var id: Int, var code: Int)

union class Event(var M: MoveK, var S: SayK, var Q: QuitK, var T: TickK)

// A word of 4..15 letters from its own chain, so the caller's stream advances
// once per event whatever the variant is.
fun word(seed: UInt64): Str {
    var w: Str = Str()
    var r: UInt64 = xsNext(seed)
    val n: Int = 4 + r.uMod(UInt64(12)).toInt()
    var i: Int = 0
    while (i < n) {
        r = xsNext(r)
        w.append(Char(97 + r.uMod(UInt64(26)).toInt()))
        i++
    }
    return w
}

fun main(): Int {
    var log: List<Event> = List<Event>()
    var r: UInt64 = UInt64(12345)
    var i: Int = 0
    while (i < N) {
        r = xsNext(r)
        val k: UInt64 = r.uMod(UInt64(8))
        val id: Int = r.shr(3).uMod(UInt64(100000)).toInt()
        if (k < UInt64(4)) {
            val dx: Int = r.shr(20).uMod(UInt64(201)).toInt() - 100
            val dy: Int = r.shr(40).uMod(UInt64(201)).toInt() - 100
            log.append(Event(MoveK(id, dx, dy)))
        } else if (k < UInt64(6)) {
            log.append(Event(SayK(id, word(r))))
        } else if (k < UInt64(7)) {
            log.append(Event(QuitK(id, r.shr(20).uMod(UInt64(256)).toInt())))
        } else {
            log.append(Event(TickK()))
        }
        i++
    }
    var total: Int64 = 0
    for (p in 0 until PASSES) {
        for (*e in log) {
            when (e) {
                M -> {
                    val m: *MoveK = e.getM()
                    total += Int64(m.id + m.dx + m.dy)
                }
                S -> {
                    val s: *SayK = e.getS()
                    total += Int64(s.id) + Int64(s.text.size()) + Int64(byteAt(s.text, 0))
                }
                Q -> {
                    val q: *QuitK = e.getQ()
                    total += Int64(q.id + q.code)
                }
                T -> {
                    total += 1
                }
                else -> {
                    total += 0
                }
            }
        }
    }
    println(log.size())
    println(total)
    return 0
}
