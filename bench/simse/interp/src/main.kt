package bench_interp

// Build an expression tree of the given depth, evaluate it eight times. Port
// of aardappel/goose bench/interp.goose: tag dispatch over an arena, which is
// the shape of the C++ expert row (bench/cpp/interp.cpp VARIANT 2). The pass
// index enters at the leaves so the eight evaluations differ; otherwise the
// whole pure call hoists out of the loop.

import kit
import unsafe

val DEPTH: Int = 16 // BENCH_N
val PASSES: Int = 8
val M: Int64 = 1000003

// 0 Num, 1 Add, 2 Mul, 3 Neg; `a`/`b` are child indices into the pool.
data class INode(var tag: Int, var v: Int, var a: Int, var b: Int)

var pool: List<INode> = List<INode>()

fun build(depth: Int, seed: UInt64): Int {
    val s: UInt64 = xsNext(seed)
    if (depth == 0) {
        pool.append(INode(0, s.uMod(UInt64(1000)).toInt(), 0, 0))
        return pool.size() - 1
    }
    val k: Int = s.uMod(UInt64(8)).toInt()
    if (k < 1) {
        val a: Int = build(depth - 1, s)
        pool.append(INode(3, 0, a, 0))
        return pool.size() - 1
    }
    val a: Int = build(depth - 1, s)
    val b: Int = build(depth - 1, xsNext(s))
    if (k < 5) {
        pool.append(INode(1, 0, a, b))
    } else {
        pool.append(INode(2, 0, a, b))
    }
    return pool.size() - 1
}

fun evalAt(i: Int, p: Int64): Int64 {
    val n: INode = pool[i]
    when (n.tag) {
        0 -> {
            return (Int64(n.v) + p) % M
        }
        1 -> {
            return (evalAt(n.a, p) + evalAt(n.b, p)) % M
        }
        2 -> {
            return (evalAt(n.a, p) * evalAt(n.b, p)) % M
        }
        else -> {
            return (M - evalAt(n.a, p)) % M
        }
    }
}

fun main(): Int {
    val root: Int = build(DEPTH, UInt64(12345))
    var total: Int64 = 0
    for (p in 0 until PASSES) {
        total = (total + evalAt(root, Int64(p))) % M
    }
    println(pool.size())
    println(total)
    return 0
}
