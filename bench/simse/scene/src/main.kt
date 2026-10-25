package bench_scene

// Build a scene graph of the given depth, then animate it and recompute world
// transforms for 16 frames. Port of aardappel/goose bench/scene.goose: an
// arena of nodes whose child links live inline in the node, mutated every
// frame - the shape of the C++ arena row (bench/cpp/scene.cpp VARIANT 1).
// Every transform is an axis-aligned 90-degree rotation with an integer
// translation, so all the f32 math is exact and the checksum is bit-identical.
// A 3x3 matrix is spelled as nine fields: Simse data classes have no
// fixed-size array members.

import kit
import unsafe

val DEPTH: Int = 12 // BENCH_N
val FRAMES: Int = 16
val MAXKIDS: Int = 4

data class Xf(var m00: Float32, var m01: Float32, var m02: Float32,
              var m10: Float32, var m11: Float32, var m12: Float32,
              var m20: Float32, var m21: Float32, var m22: Float32,
              var tx: Float32, var ty: Float32, var tz: Float32)

data class SNode(var id: Int, var local: Xf, var world: Xf,
                 var k0: Int, var k1: Int, var k2: Int, var k3: Int, var nkids: Int)

var pool: List<SNode> = List<SNode>()

fun ident(): Xf {
    return Xf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0)
}

// One of twelve axis-aligned rotations, from the seed. `zf` keeps the
// negations in Float32: `0.0 - s` would promote through the Float64 literal
// and the braced initializer rejects the narrowing.
fun rotation(seed: UInt64): Xf {
    val zf: Float32 = 0.0
    val axis: Int = seed.uMod(UInt64(3)).toInt()
    val quarter: Int = seed.shr(8).uMod(UInt64(4)).toInt()
    var c: Float32 = 1.0
    var s: Float32 = 0.0
    if (quarter == 1) {
        c = 0.0
        s = 1.0
    } else if (quarter == 2) {
        c = -1.0
        s = 0.0
    } else if (quarter == 3) {
        c = 0.0
        s = -1.0
    }
    if (axis == 0) {
        return Xf(1.0, 0.0, 0.0, 0.0, c, zf - s, 0.0, s, c, 0.0, 0.0, 0.0)
    }
    if (axis == 1) {
        return Xf(c, 0.0, s, 0.0, 1.0, 0.0, zf - s, 0.0, c, 0.0, 0.0, 0.0)
    }
    return Xf(c, zf - s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0)
}

fun kidAt(n: SNode, k: Int): Int {
    if (k == 0) {
        return n.k0
    }
    if (k == 1) {
        return n.k1
    }
    if (k == 2) {
        return n.k2
    }
    return n.k3
}

// The node is pushed before its children so its id is the pre-order creation
// index; the arena may move under the recursion, so children are re-linked by
// index after every build.
fun build(depth: Int, seed: UInt64): Int {
    val r: UInt64 = xsNext(seed)
    val self: Int = pool.size()
    pool.append(SNode(self, rotation(r), ident(), 0, 0, 0, 0, 0))
    pool[self].local.tx = Float32(r.shr(16).uMod(UInt64(9)).toInt64())
    pool[self].local.ty = Float32(r.shr(24).uMod(UInt64(9)).toInt64())
    pool[self].local.tz = Float32(r.shr(32).uMod(UInt64(9)).toInt64())
    if (depth == 0) {
        return self
    }
    val n: Int = 1 + r.shr(40).uMod(UInt64(MAXKIDS)).toInt()
    var i: Int = 0
    while (i < n) {
        val c: Int = build(depth - 1, xsNext(r.plus(UInt64(i))))
        val cnt: Int = pool[self].nkids
        if (cnt == 0) {
            pool[self].k0 = c
        } else if (cnt == 1) {
            pool[self].k1 = c
        } else if (cnt == 2) {
            pool[self].k2 = c
        } else {
            pool[self].k3 = c
        }
        pool[self].nkids = cnt + 1
        i++
    }
    return self
}

// world = parent o local: a 3x3 product and a transformed translation.
fun compose(p: Xf, l: Xf): Xf {
    return Xf(
        p.m00 * l.m00 + p.m01 * l.m10 + p.m02 * l.m20,
        p.m00 * l.m01 + p.m01 * l.m11 + p.m02 * l.m21,
        p.m00 * l.m02 + p.m01 * l.m12 + p.m02 * l.m22,
        p.m10 * l.m00 + p.m11 * l.m10 + p.m12 * l.m20,
        p.m10 * l.m01 + p.m11 * l.m11 + p.m12 * l.m21,
        p.m10 * l.m02 + p.m11 * l.m12 + p.m12 * l.m22,
        p.m20 * l.m00 + p.m21 * l.m10 + p.m22 * l.m20,
        p.m20 * l.m01 + p.m21 * l.m11 + p.m22 * l.m21,
        p.m20 * l.m02 + p.m21 * l.m12 + p.m22 * l.m22,
        p.m00 * l.tx + p.m01 * l.ty + p.m02 * l.tz + p.tx,
        p.m10 * l.tx + p.m11 * l.ty + p.m12 * l.tz + p.ty,
        p.m20 * l.tx + p.m21 * l.ty + p.m22 * l.tz + p.tz)
}

fun update(i: Int, parent: Xf): Unit {
    pool[i].world = compose(parent, pool[i].local)
    val nkids: Int = pool[i].nkids
    var k: Int = 0
    while (k < nkids) {
        update(kidAt(pool[i], k), pool[i].world)
        k++
    }
}

fun main(): Int {
    val root: Int = build(DEPTH, UInt64(12345))
    var total: Int64 = 0
    for (f in 0 until FRAMES) {
        // A seventh of the nodes move each frame: the animation.
        val phase: Int = f % 7
        val step: Float32 = Float32(f % 5)
        var j: Int = 0
        while (j < pool.size()) {
            if (pool[j].id % 7 == phase) {
                pool[j].local.tx = step
                pool[j].local.tz = Float32(8.0) - step
            }
            j++
        }
        update(root, ident())
        // Where everything ended up, as an integer: exact in f32, summed in i64.
        j = 0
        while (j < pool.size()) {
            val w: Xf = pool[j].world
            total += Int64(Int(w.tx + w.ty + w.tz))
            j++
        }
    }
    println(pool.size())
    println(total)
    return 0
}
