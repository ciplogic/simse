package bench_sexp

// Generate N s-expression forms, parse them into a tree, walk it four times.
// Port of aardappel/goose bench/sexp.goose: one node pool, symbol text held as
// offsets into the source text (what goose stores inline), children linked
// first-child / previous-sibling so every node is complete when it is pushed.
// That is the shape of the C++ arena row (bench/cpp/sexp.cpp VARIANT 1); its
// idiomatic row allocates a node and a string per symbol. The walk sees each
// list's children in reverse source order, which the C++ rows reproduce.

import kit

val N: Int = 20000 // BENCH_N
val DEPTH: Int = 4
val PASSES: Int = 4

// 0 sym, 1 num, 2 list; symbol bytes are text[off..off+len).
data class SNode(var kind: Int, var v: Int, var off: Int, var len: Int,
                 var first: Int, var next: Int)

var text: Str = Str()
var pos: Int = 0
var pool: List<SNode> = List<SNode>()
var roots: List<Int> = List<Int>()

fun emitSym(seed: Int64): Unit {
    var r: Int64 = seed
    val n: Int = 3 + Int(xsMod(r, 12))
    var i: Int = 0
    while (i < n) {
        r = xsNext(r)
        text.append(Char(97 + Int(xsMod(r, 26))))
        i++
    }
}

fun emitNum(v: Int): Unit {
    if (v == 0) {
        text.append('0')
        return
    }
    var digits: Str = Str()
    var d: Int = v
    while (d > 0) {
        digits.append(Char(48 + d % 10))
        d /= 10
    }
    var i: Int = digits.size() - 1
    while (i >= 0) {
        text.append(digits[i])
        i--
    }
}

fun gen(depth: Int, seed: Int64): Int64 {
    var r: Int64 = xsNext(seed)
    if (depth == 0 || xsMod(r, 4) == Int64(0)) {
        if (xsMod(uShr(r, 8), 2) == Int64(0)) {
            emitSym(r)
        } else {
            emitNum(Int(xsMod(uShr(r, 16), 100000)))
        }
        text.append(' ')
        return r
    }
    text.append('(')
    val kids: Int = 2 + Int(xsMod(uShr(r, 24), 4))
    var k: Int = 0
    while (k < kids) {
        r = gen(depth - 1, r)
        k++
    }
    text.append(')')
    text.append(' ')
    return r
}

fun skipSpace(): Unit {
    while (pos < text.size() && byteAt(text, pos) == 32) {
        pos++
    }
}

fun parseNode(prev: Int): Int {
    skipSpace()
    val c: Int = byteAt(text, pos)
    if (c == 40) {
        pos++
        var chain: Int = 0
        while (true) {
            skipSpace()
            if (byteAt(text, pos) == 41) {
                pos++
                break
            }
            chain = parseNode(chain)
        }
        pool.append(SNode(2, 0, 0, 0, chain, prev))
        return pool.size() - 1
    }
    val start: Int = pos
    if (c >= 48 && c <= 57) {
        var v: Int = 0
        while (pos < text.size() && byteAt(text, pos) >= 48 && byteAt(text, pos) <= 57) {
            v = v * 10 + (byteAt(text, pos) - 48)
            pos++
        }
        pool.append(SNode(1, v, 0, 0, 0, prev))
        return pool.size() - 1
    }
    while (pos < text.size() && byteAt(text, pos) != 32 && byteAt(text, pos) != 40 && byteAt(text, pos) != 41) {
        pos++
    }
    pool.append(SNode(0, 0, start, pos - start, 0, prev))
    return pool.size() - 1
}

fun walk(i: Int, p: Int64): Int64 {
    val n: SNode = pool[i]
    when (n.kind) {
        0 -> {
            return Int64(n.len) + Int64(byteAt(text, n.off)) + p
        }
        1 -> {
            return Int64(n.v) ^ p
        }
        else -> {
            return 1 + walkChain(n.first, p)
        }
    }
}

fun walkChain(c: Int, p: Int64): Int64 {
    if (c == 0) {
        return 0
    }
    return walk(c, p) + walkChain(pool[c].next, p)
}

fun main(): Int {
    var seed: Int64 = 12345
    var i: Int = 0
    while (i < N) {
        seed = gen(DEPTH, seed)
        i++
    }
    pool.append(SNode(1, 0, 0, 0, 0, 0))
    while (true) {
        skipSpace()
        if (pos >= text.size()) {
            break
        }
        roots.append(parseNode(0))
    }
    var total: Int64 = 0
    for (p in 0 until PASSES) {
        var r: Int = 0
        while (r < roots.size()) {
            total += walk(roots[r], Int64(p))
            r++
        }
    }
    println(text.size())
    println(pool.size() - 1)
    println(roots.size())
    println(0)
    println(total)
    return 0
}
