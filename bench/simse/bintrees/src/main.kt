package bench_bintrees

// The Benchmarks Game's binary-trees: build, check and discard many short-
// lived complete trees, with one long-lived tree kept across the run. Port of
// aardappel/goose bench/bintrees.goose. Each tree is a local pool of 8-byte
// nodes linked by child indices, released when the function returns - the
// shape of the C++ vector-arena row (bench/cpp/bintrees.cpp VARIANT 1); the
// C++ idiomatic row pays a malloc and a free per node instead. Index 0 is the
// null sentinel.

val MAXDEPTH: Int = 15 // BENCH_N
val MINDEPTH: Int = 4

data class BTNode(var l: Int, var r: Int)

fun make(pool: *List<BTNode>, depth: Int): Int {
    if (depth == 0) {
        pool.append(BTNode(0, 0))
        return pool.size() - 1
    }
    val l: Int = make(pool, depth - 1)
    val r: Int = make(pool, depth - 1)
    pool.append(BTNode(l, r))
    return pool.size() - 1
}

fun check(pool: *List<BTNode>, i: Int): Int64 {
    val l: Int = pool[i].l
    if (l == 0) {
        return 1
    }
    val r: Int = pool[i].r
    if (r == 0) {
        return 1
    }
    return 1 + check(pool, l) + check(pool, r)
}

// Build, check, discard: the pool is a local, so the discard is one free.
fun treeCheck(depth: Int): Int64 {
    var pool: List<BTNode> = List<BTNode>()
    pool.append(BTNode(0, 0))
    val root: Int = make(pool, depth)
    return check(pool, root)
}

fun main(): Int {
    println(treeCheck(MAXDEPTH + 1))
    var kept: List<BTNode> = List<BTNode>()
    kept.append(BTNode(0, 0))
    val lroot: Int = make(kept, MAXDEPTH)
    var d: Int = MINDEPTH
    while (d <= MAXDEPTH) {
        val iters: Int = 1 << (MAXDEPTH - d + MINDEPTH)
        var sum: Int64 = 0
        var i: Int = 0
        while (i < iters) {
            sum += treeCheck(d)
            i++
        }
        println(iters)
        println(d)
        println(sum)
        d += 2
    }
    println(check(kept, lroot))
    return 0
}
