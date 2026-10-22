package bench_calc

// Generate, parse and evaluate N small arithmetic expressions, a quarter of
// them malformed, so the error path is taken from deep inside the recursion on
// every fourth input. Port of aardappel/goose bench/calc.goose. The AST pool
// is reused across inputs with a high-water length (what the C++ arena row's
// `clear` does, keeping its capacity - bench/cpp/calc.cpp VARIANT 1), and the
// error travels out of the recursion as a code threaded through every return,
// against the idiomatic C++ row's exceptions. Evaluation is in wrapping u64 -
// two's-complement `Int64` here.

import kit

val N: Int = 100000 // BENCH_N

val E_EOF: Int = 1
val E_PAREN: Int = 2
val E_TOKEN: Int = 3
val E_DIV0: Int = 4

// 0 Num, 1 Neg, 2 Paren, 3 Bin.
data class CExpr(var kind: Int, var op: Int, var v: Int, var l: Int, var r: Int)
data class POut(var idx: Int, var err: Int)
data class EOut(var value: Int64, var err: Int)

var text: Str = Str()
var pos: Int = 0
var nodes: Int64 = 0
var pool: List<CExpr> = List<CExpr>()
var poolLen: Int = 0

fun emitNum(v: Int): Unit {
    if (v >= 10) {
        text.append(Char(48 + v / 10))
    }
    text.append(Char(48 + v % 10))
}

fun gen(depth: Int, seed: Int64): Int64 {
    var r: Int64 = xsNext(seed)
    val k: Int64 = xsMod(r, 8)
    if (depth == 0 || k == Int64(0)) {
        emitNum(1 + Int(xsMod(r >> 8, 99)))
        return r
    }
    if (k == 1) {
        text.append('-')
        return gen(depth - 1, r)
    }
    if (k == 2) {
        text.append('(')
        r = gen(depth - 1, r)
        text.append(')')
        return r
    }
    r = gen(depth - 1, r)
    val op: Int64 = xsMod(r >> 16, 4)
    text.append(' ')
    if (op == 0) {
        text.append('+')
    } else if (op == 1) {
        text.append('-')
    } else if (op == 2) {
        text.append('*')
    } else {
        text.append('/')
    }
    text.append(' ')
    return gen(depth - 1, xsNext(r))
}

// One in four inputs is damaged after generation.
fun corrupt(seed: Int64): Unit {
    val kind: Int64 = xsMod(seed, 16)
    if (kind == 0) {
        var i: Int = text.size()
        while (i > 0) {
            i--
            if (byteAt(text, i) == 41) {
                text[i] = ' '
                return
            }
        }
        text[0] = '#'
        return
    }
    if (kind == 1) {
        text[Int(xsMod(seed >> 8, Int64(text.size())))] = '#'
        return
    }
    if (kind == 2) {
        resize(text, text.size() / 2)
        return
    }
    if (kind == 3) {
        text.appendStr(" / 0")
    }
}

fun skip(): Unit {
    while (pos < text.size() && byteAt(text, pos) == 32) {
        pos++
    }
}

// The arena slot for the next node: over the high-water mark, or a fresh one.
fun pushNode(e: CExpr): Int {
    if (poolLen < pool.size()) {
        pool[poolLen] = e
    } else {
        pool.append(e)
    }
    poolLen++
    return poolLen - 1
}

fun parseFactor(): POut {
    skip()
    if (pos >= text.size()) {
        return POut(0, E_EOF)
    }
    val c: Int = byteAt(text, pos)
    if (c >= 48 && c <= 57) {
        var v: Int = 0
        while (pos < text.size() && byteAt(text, pos) >= 48 && byteAt(text, pos) <= 57) {
            v = v * 10 + (byteAt(text, pos) - 48)
            pos++
        }
        nodes += 1
        return POut(pushNode(CExpr(0, 0, v, 0, 0)), 0)
    }
    if (c == 45) {
        pos++
        val f: POut = parseFactor()
        if (f.err != 0) {
            return f
        }
        nodes += 1
        return POut(pushNode(CExpr(1, 0, 0, f.idx, 0)), 0)
    }
    if (c != 40) {
        return POut(0, E_TOKEN)
    }
    pos++
    val e: POut = parseExpr()
    if (e.err != 0) {
        return e
    }
    skip()
    if (pos >= text.size() || byteAt(text, pos) != 41) {
        return POut(0, E_PAREN)
    }
    pos++
    nodes += 1
    return POut(pushNode(CExpr(2, 0, 0, e.idx, 0)), 0)
}

fun parseTerm(): POut {
    val f: POut = parseFactor()
    if (f.err != 0) {
        return f
    }
    var lhs: Int = f.idx
    while (true) {
        skip()
        if (pos >= text.size() || (byteAt(text, pos) != 42 && byteAt(text, pos) != 47)) {
            break
        }
        val op: Int = byteAt(text, pos)
        pos++
        val rhs: POut = parseFactor()
        if (rhs.err != 0) {
            return rhs
        }
        nodes += 1
        lhs = pushNode(CExpr(3, op, 0, lhs, rhs.idx))
    }
    return POut(lhs, 0)
}

fun parseExpr(): POut {
    val t: POut = parseTerm()
    if (t.err != 0) {
        return t
    }
    var lhs: Int = t.idx
    while (true) {
        skip()
        if (pos >= text.size() || (byteAt(text, pos) != 43 && byteAt(text, pos) != 45)) {
            break
        }
        val op: Int = byteAt(text, pos)
        pos++
        val rhs: POut = parseTerm()
        if (rhs.err != 0) {
            return rhs
        }
        nodes += 1
        lhs = pushNode(CExpr(3, op, 0, lhs, rhs.idx))
    }
    return POut(lhs, 0)
}

fun evalExpr(i: Int): EOut {
    val e: CExpr = pool[i]
    if (e.kind == 0) {
        return EOut(Int64(e.v), 0)
    }
    if (e.kind == 1) {
        val a: EOut = evalExpr(e.l)
        if (a.err != 0) {
            return a
        }
        return EOut(Int64(0) - a.value, 0)
    }
    if (e.kind == 2) {
        return evalExpr(e.l)
    }
    val a: EOut = evalExpr(e.l)
    if (a.err != 0) {
        return a
    }
    val b: EOut = evalExpr(e.r)
    if (b.err != 0) {
        return b
    }
    if (e.op == 43) {
        return EOut(a.value + b.value, 0)
    }
    if (e.op == 45) {
        return EOut(a.value - b.value, 0)
    }
    if (e.op == 42) {
        return EOut(a.value * b.value, 0)
    }
    if (b.value == Int64(0)) {
        return EOut(0, E_DIV0)
    }
    return EOut(a.value / b.value, 0)
}

// One input: the tree occupies the pool's front, reset by the next input.
fun runOne(): EOut {
    poolLen = 0
    pos = 0
    val e: POut = parseExpr()
    if (e.err != 0) {
        return EOut(0, e.err)
    }
    skip()
    if (pos != text.size()) {
        return EOut(0, E_TOKEN)
    }
    return evalExpr(e.idx)
}

fun main(): Int {
    var seed: Int64 = 12345
    var ok: Int64 = 0
    var chars: Int64 = 0
    var errsEof: Int64 = 0
    var errsParen: Int64 = 0
    var errsToken: Int64 = 0
    var errsDiv0: Int64 = 0
    var sum: Int64 = 0
    var i: Int = 0
    while (i < N) {
        seed = xsNext(seed)
        resize(text, 0)
        val depth: Int = 3 + Int(xsMod(seed, 4))
        val s: Int64 = gen(depth, seed >> 8)
        corrupt(s)
        chars += Int64(text.size())
        val out: EOut = runOne()
        if (out.err == 0) {
            ok += 1
            sum = sum * 31 + out.value
        } else if (out.err == E_EOF) {
            errsEof += 1
        } else if (out.err == E_PAREN) {
            errsParen += 1
        } else if (out.err == E_TOKEN) {
            errsToken += 1
        } else {
            errsDiv0 += 1
        }
        i++
    }
    println(chars)
    println(nodes)
    println(ok)
    println(errsEof)
    println(errsParen)
    println(errsToken)
    println(errsDiv0)
    println(uToString(sum))
    return 0
}
