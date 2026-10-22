package bench_particles

// Integrate N particles for 200 steps of f32 vector math, array of structs.
// Port of aardappel/goose bench/particles.goose. All the arithmetic is exact
// in binary32 - positions are integers, velocities and gravity are multiples
// of 1/16, the only multiply is by 0.5 - so every implementation must produce
// a bit-identical checksum however the backend reassociates.

import kit

val N: Int = 100000 // BENCH_N
val STEPS: Int = 200

data class F3(var x: Float32, var y: Float32, var z: Float32)
data class P(var pos: F3, var vel: F3)

val GY: Float32 = -0.0625
val SIXTEEN: Float32 = 16.0

fun main(): Int {
    var ps: List<P> = List<P>()
    var r: Int64 = 12345
    var i: Int = 0
    while (i < N) {
        r = xsNext(r)
        val px: Float32 = Float32(xsMod(r, 1024))
        val py: Float32 = Float32(xsMod(r >> 12, 1024))
        val pz: Float32 = Float32(xsMod(r >> 24, 1024))
        val vx: Float32 = Float32((Float64(xsMod(r >> 36, 256)) - 128.0) / 16.0)
        val vy: Float32 = Float32((Float64(xsMod(r >> 44, 256)) - 128.0) / 16.0)
        val vz: Float32 = Float32((Float64(xsMod(r >> 52, 256)) - 128.0) / 16.0)
        ps.append(P(F3(px, py, pz), F3(vx, vy, vz)))
        i++
    }
    for (s in 0 until STEPS) {
        for (*p in ps) {
            p.vel.y = p.vel.y + GY
            p.pos.x = p.pos.x + p.vel.x
            p.pos.y = p.pos.y + p.vel.y
            p.pos.z = p.pos.z + p.vel.z
            if (p.pos.y < 0.0) {
                p.pos.y = Float32(0.0) - p.pos.y
                p.vel.y = Float32(0.0) - p.vel.y * Float32(0.5)
            }
        }
    }
    var total: Int64 = 0
    for (*p in ps) {
        total += Int64(Int(p.pos.x * SIXTEEN)) + Int64(Int(p.pos.y * SIXTEEN))
            + Int64(Int(p.pos.z * SIXTEEN))
    }
    println(ps.size())
    println(total)
    return 0
}
