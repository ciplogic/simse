package bench_graph

// Build adjacency for V vertices and 8V edges one edge at a time, then BFS
// from four sources. Port of aardappel/goose bench/graph.goose: one edge pool
// whose per-vertex list heads are its first V entries, linked by index - the
// shape of the C++ arena row (bench/cpp/graph.cpp VARIANT 2). Head `next` of
// 0 means no edges yet; BFS distances are order-independent, so the checksum
// does not depend on the edge order out of the adjacency structure.

import kit

val V: Int = 100000 // BENCH_N
val DEG: Int = 8
val SOURCES: Int = 4

data class Edge(var to: Int, var next: Int)

fun main(): Int {
    val ne: Int = V * DEG
    var pool: List<Edge> = List<Edge>()
    var dist: List<Int> = List<Int>()
    var q: List<Int> = List<Int>()
    var i: Int = 0
    while (i < V) {
        dist.append(-1)
        pool.append(Edge(-1, 0))
        i++
    }
    var r: Int64 = 12345
    var e: Int = 0
    while (e < ne) {
        r = xsNext(r)
        val u: Int = Int(xsMod(r, Int64(V)))
        val w: Int = Int(xsMod(r >> 24, Int64(V)))
        pool.append(Edge(w, pool[u].next))
        pool[u].next = pool.size() - 1
        e++
    }
    var total: Int64 = 0
    var seed: Int64 = 999
    for (s in 0 until SOURCES) {
        seed = xsNext(seed)
        val src: Int = Int(xsMod(seed, Int64(V)))
        var v: Int = 0
        while (v < V) {
            dist[v] = -1
            v++
        }
        q = List<Int>()
        dist[src] = 0
        q.append(src)
        var qh: Int = 0
        while (qh < q.size()) {
            val u: Int = q[qh]
            qh++
            val d: Int = dist[u]
            total += Int64(d)
            var c: Int = pool[u].next
            while (c != 0) {
                val w: Int = pool[c].to
                if (dist[w] < 0) {
                    dist[w] = d + 1
                    q.append(w)
                }
                c = pool[c].next
            }
        }
    }
    println(V + ne)
    println(total)
    return 0
}
