package bench_push

// Push N records while keeping a mark on every 64th one. Port of
// aardappel/goose bench/push.goose. Goose hands out references that stay valid
// across growth; Simse's list reallocates like a C++ vector, so the marks are
// indices - the same shape the idiomatic C++ row picks (bench/cpp/push.cpp).

import kit
import unsafe

val N: Int = 1000000 // BENCH_N

data class Item(var key: Int, var value: Int)

fun main(): Int {
    var items: List<Item> = List<Item>()
    var marks: List<Int> = List<Int>()
    var r: UInt64 = UInt64(12345)
    var i: Int = 0
    while (i < N) {
        r = xsNext(r)
        items.append(Item(r.uMod(UInt64(1000000)).toInt(), i % 97))
        if (i % 64 == 0) {
            marks.append(items.size() - 1)
        }
        i++
    }
    var total: Int64 = 0
    for (*it in items) {
        total += Int64(it.key)
    }
    var mt: Int64 = 0
    for (m in marks) {
        mt += Int64(items[m].value)
    }
    println(items.size())
    println(marks.size())
    println(total)
    println(mt)
    return 0
}
