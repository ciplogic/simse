package bench_respond

// Build and render N JSON responses, each a DTO holding a name and a list of
// line items, checksum the rendered bytes with FNV-1a and throw everything
// away. Port of aardappel/goose bench/respond.goose: the typed-DTO shape of
// the idiomatic C++ row (bench/cpp/respond.cpp VARIANT 0). One name in eight
// carries a quote or a backslash, so the escaping path is real.

import kit
import unsafe

val N: Int = 100000 // BENCH_N

data class Item(var sku: Str, var qty: Int, var price: Int)
data class Resp(var id: Int64, var user: Str, var items: List<Item>)
data class HOut(var h: UInt64, var len: Int)

// A user name of 3..10 letters; one in eight carries a quote or a backslash.
fun nameOf(seed: UInt64): Str {
    var s: Str = Str()
    var r: UInt64 = xsNext(seed)
    val n: Int = 3 + r.uMod(UInt64(8)).toInt()
    val odd: Bool = r.shr(8).uMod(UInt64(8)) == UInt64(0)
    var i: Int = 0
    while (i < n) {
        r = xsNext(r)
        if (odd && i == 1) {
            if (r.uMod(UInt64(2)) == UInt64(0)) {
                s.append('"')
            } else {
                s.append('\\')
            }
        } else {
            s.append(Char(97 + r.uMod(UInt64(26)).toInt()))
        }
        i++
    }
    return s
}

// A stock keeping unit: two letters and four digits.
fun skuOf(seed: UInt64): Str {
    var s: Str = Str()
    var r: UInt64 = xsNext(seed)
    s.append(Char(65 + r.uMod(UInt64(26)).toInt()))
    s.append(Char(65 + r.shr(8).uMod(UInt64(26)).toInt()))
    var i: Int = 0
    while (i < 4) {
        r = xsNext(r)
        s.append(Char(48 + r.uMod(UInt64(10)).toInt()))
        i++
    }
    return s
}

fun makeItems(seed: UInt64, n: Int): List<Item> {
    var t: List<Item> = List<Item>()
    var r: UInt64 = seed
    var i: Int = 0
    while (i < n) {
        r = xsNext(r)
        t.append(Item(skuOf(r), 1 + r.shr(8).uMod(UInt64(5)).toInt(), 100 + r.shr(16).uMod(UInt64(99900)).toInt()))
        i++
    }
    return t
}

fun emitInt(out: *Str, v: Int64): Unit {
    if (v == Int64(0)) {
        out.append('0')
        return
    }
    var digits: Str = Str()
    var d: Int64 = v
    while (d > Int64(0)) {
        digits.append(Char(48 + Int(d % 10)))
        d /= 10
    }
    var i: Int = digits.size() - 1
    while (i >= 0) {
        out.append(digits[i])
        i--
    }
}

fun emitStr(out: *Str, s: *Str): Unit {
    out.append('"')
    var i: Int = 0
    while (i < s.size()) {
        val c: Int = byteAt(s, i)
        if (c == 34 || c == 92) {
            out.append('\\')
        }
        out.append(s[i])
        i++
    }
    out.append('"')
}

fun render(out: *Str, r: *Resp): Unit {
    out.appendStr("{\"id\":")
    emitInt(out, r.id)
    out.appendStr(",\"user\":")
    emitStr(out, r.user)
    out.appendStr(",\"items\":[")
    var total: Int64 = 0
    var first: Bool = true
    var i: Int = 0
    while (i < r.items.size()) {
        val it: Item = r.items[i]
        if (!first) {
            out.append(',')
        }
        first = false
        out.appendStr("{\"sku\":")
        emitStr(out, it.sku)
        out.appendStr(",\"qty\":")
        emitInt(out, Int64(it.qty))
        out.appendStr(",\"price\":")
        emitInt(out, Int64(it.price))
        out.append('}')
        total += Int64(it.qty * it.price)
        i++
    }
    out.appendStr("],\"total\":")
    emitInt(out, total)
    out.append('}')
}

// FNV-1a, the checksum the C++ and Rust rows compute.
fun fnv(s: *Str): UInt64 {
    // 0xcbf29ce484222325, as the signed spelling of its bit pattern.
    var h: UInt64 = UInt64(-3750763034362895579)
    var i: Int = 0
    while (i < s.size()) {
        h = h.xorWith(UInt64(byteAt(s, i))).times(UInt64(1099511628211))
        i++
    }
    return h
}

// One request: the response object and its rendering are both locals.
fun handle(seed: UInt64): HOut {
    val r: UInt64 = xsNext(seed)
    val n: Int = 1 + r.uMod(UInt64(8)).toInt()
    val resp: Resp = Resp(r.shr(4).uMod(UInt64(1000000)).toInt64(), nameOf(r.shr(8)), makeItems(r.shr(16), n))
    var out: Str = Str()
    render(out, resp)
    return HOut(fnv(out), out.size())
}

fun main(): Int {
    var seed: UInt64 = UInt64(12345)
    var h: UInt64 = UInt64(0)
    var bytes: Int64 = 0
    var i: Int = 0
    while (i < N) {
        seed = xsNext(seed)
        val r: HOut = handle(seed)
        h = h.xorWith(r.h.times(UInt64(31)).plus(UInt64(i)))
        bytes += Int64(r.len)
        i++
    }
    println(N)
    println(bytes)
    println(h.toString())
    return 0
}
