package bench_blur

// 16 passes of a 3x3 blur over a WxW 8-bit image, ping-ponged between two
// buffers. Port of aardappel/goose bench/blur.goose: flat scalar data and nine
// loads per pixel, the row that exists to measure what bounds checks and
// missing aliasing information give up. The border pixels keep the values
// they were initialised with.

import kit

val W: Int = 1024 // BENCH_N
val PASSES: Int = 16

fun blur(src: *Str, dst: *Str, w: Int): Unit {
    var y: Int = 1
    while (y < w - 1) {
        var x: Int = 1
        while (x < w - 1) {
            val i: Int = y * w + x
            val s: Int = byteAt(src, i - w - 1) + byteAt(src, i - w) * 2 + byteAt(src, i - w + 1)
                + byteAt(src, i - 1) * 2 + byteAt(src, i) * 4 + byteAt(src, i + 1) * 2
                + byteAt(src, i + w - 1) + byteAt(src, i + w) * 2 + byteAt(src, i + w + 1)
            dst[i] = Char((s >> 4) & 255)
            x++
        }
        y++
    }
}

fun main(): Int {
    var a: Str = Str()
    var b: Str = Str()
    var r: Int64 = 12345
    var i: Int = 0
    while (i < W * W) {
        r = xsNext(r)
        a.append(Char(Int(xsMod(r, 256))))
        b.append(Char(0))
        i++
    }
    for (p in 0 until PASSES / 2) {
        blur(a, b, W)
        blur(b, a, W)
    }
    var total: Int64 = 0
    var k: Int = 0
    while (k < a.size()) {
        total += Int64(byteAt(a, k))
        k++
    }
    println(W)
    println(total)
    return 0
}
