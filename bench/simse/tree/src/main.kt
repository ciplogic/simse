package bench_tree

// Build a complete binary tree of the given depth, sum it eight times. Port of
// aardappel/goose bench/tree.goose. Goose links 12-byte pool nodes with
// relative references; Simse's containers cannot hold raw pointers, so the
// tree is an arena of nodes linked by child indices - the same shape the C++
// arena row writes by hand (bench/cpp/tree.cpp VARIANT 2). Index 0 is the
// null sentinel; children are pushed before their parent.

import kit
import unsafe

val DEPTH: Int = 16 // BENCH_N
val PASSES: Int = 8

data class TNode(var v: Int, var l: Int, var r: Int)

var pool: List<TNode> = List<TNode>()

fun build(depth: Int, seed: UInt64): Int {
    if (depth == 0) {
        pool.append(TNode(seed.uMod(UInt64(1000)).toInt(), 0, 0))
        return pool.size() - 1
    }
    val l: Int = build(depth - 1, xsNext(seed))
    val r: Int = build(depth - 1, xsNext(seed.plus(UInt64(1))))
    pool.append(TNode(seed.uMod(UInt64(1000)).toInt(), l, r))
    return pool.size() - 1
}

// The pass index is folded into every node's contribution so the eight passes
// are eight different computations.
fun sumTree(n: Int, p: Int64): Int64 {
    val t: TNode = pool[n]
    var s: Int64 = Int64(t.v) ^ p
    if (t.l != 0) {
        s += sumTree(t.l, p)
    }
    if (t.r != 0) {
        s += sumTree(t.r, p)
    }
    return s
}

fun main(): Int {
    pool.append(TNode(0, 0, 0))
    val root: Int = build(DEPTH, UInt64(12345))
    var total: Int64 = 0
    for (p in 0 until PASSES) {
        total += sumTree(root, Int64(p))
    }
    println(pool.size() - 1)
    println(total)
    return 0
}
