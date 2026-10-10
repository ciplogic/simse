package fixtures

import gzip
import unsafe

// The `gzip` module (modules/gzip/api.kt): Gzip encoding of a `List<Int8>`. The case names
// the module and `unsafe` (whose types it is built on) in `compiler-args` and packs a few
// shapes: a small text (its whole stream is printed - it is a known answer), a run, `all`
// the byte values, a pseudo-random stream, a 100 KiB text, and the empty input.
//
// The trailer is read back and checked at the end: the last four bytes are the ISIZE, and
// the four before them the CRC32, so this pins both without a second implementation.

fun toBytes(text: Str): List<Int8> {
    var bytes: List<Int8> = List<Int8>()
    var i: Int = 0
    while (i < text.size()) {
        bytes.append(text[i])
        i = i + 1
    }
    return bytes
}

fun byteAt(bytes: List<Int8>, at: Int): Int {
    val wide: Int = bytes[at]
    return wide & 255
}

// The little-endian `Int` the four bytes at `at` spell.
fun leInt(bytes: List<Int8>, at: Int): Int {
    return byteAt(bytes, at) | (byteAt(bytes, at + 1) << 8) | (byteAt(bytes, at + 2) << 16)
        | (byteAt(bytes, at + 3) << 24)
}

fun hex(bytes: List<Int8>): Str {
    val digits: Str = "0123456789abcdef"
    var out: Str = Str()
    var i: Int = 0
    while (i < bytes.size()) {
        val byte: Int = byteAt(bytes, i)
        out.append(digits.charAt(byte >> 4))
        out.append(digits.charAt(byte & 15))
        i = i + 1
    }
    return out
}

fun report(name: Str, input: List<Int8>, level: Int): List<Int8> {
    val packed: Res<List<Int8>> = compressLevel(input, level)
    if (!packed.isOk()) {
        println(name + " error " + packed.error)
        return List<Int8>()
    }
    println(name + " " + input.size().toString() + " " + packed.value.size().toString())
    return packed.value
}

fun main(): Int {
    // The known answer: the whole stream, header to trailer.
    val hello: List<Int8> = toBytes("hello hello hello hello")
    val packedHello: Res<List<Int8>> = compress(hello)
    println("hello " + packedHello.value.size().toString() + " " + hex(packedHello.value))

    // A run of one byte: one long back-reference.
    var run: List<Int8> = List<Int8>()
    var i: Int = 0
    while (i < 1000) {
        run.append('a')
        i = i + 1
    }
    val packedRun: List<Int8> = report("run", run, 6)

    // The lazy-dog text twenty times: matches at a 45-byte distance.
    var text: Str = Str()
    var round: Int = 0
    while (round < 20) {
        text.appendStr("the quick brown fox jumps over the lazy dog. ")
        round = round + 1
    }
    val fox: List<Int8> = toBytes(text)
    val packedFox: List<Int8> = report("fox", fox, 6)

    // Every byte value once: the 9-bit literal codes.
    var all: List<Int8> = List<Int8>()
    var value: Int = 0
    while (value < 256) {
        all.append(value)
        value = value + 1
    }
    report("all", all, 6)

    // A deterministic stream: mostly literals.
    var noise: List<Int8> = List<Int8>()
    var state: Int = 1
    var n: Int = 0
    while (n < 5000) {
        state = (state * 1103515245 + 12345) & 2147483647
        noise.append((state >> 16) & 255)
        n = n + 1
    }
    report("noise", noise, 6)

    // 100 KiB: candidates past the 32 KiB window must be left alone.
    var bigText: Str = Str()
    var line: Int = 0
    while (line < 2000) {
        bigText.appendStr("line ")
        val digits: Int = line % 100
        if (digits >= 10) {
            bigText.append('0' + digits / 10)
        }
        bigText.append('0' + digits % 10)
        bigText.appendStr(": the quick brown fox jumps over the lazy dog\n")
        line = line + 1
    }
    report("big", toBytes(bigText), 6)

    // The empty input: the end-of-block symbol alone.
    val empty: List<Int8> = List<Int8>()
    val packedEmpty: List<Int8> = report("empty", empty, 6)

    // The level is zlib's 1..9; 0 and 10 are errors.
    val low: Res<List<Int8>> = compressLevel(hello, 0)
    println("level0 " + low.isOk().toString() + " " + low.error)
    val high: Res<List<Int8>> = compressLevel(hello, 10)
    println("level10 " + high.isOk().toString())
    report("level1", fox, 1)
    report("level9", fox, 9)

    // The trailer, read back: the first two bytes are the magic, the last four the ISIZE,
    // and the four before them the CRC32.
    println("magic " + byteAt(packedHello.value, 0).toString() + " "
        + byteAt(packedHello.value, 1).toString())
    println("run-isize " + leInt(packedRun, packedRun.size() - 4).toString())
    println("run-crc " + UInt32(leInt(packedRun, packedRun.size() - 8)).toString())
    println("fox-isize " + leInt(packedFox, packedFox.size() - 4).toString())
    println("fox-crc " + UInt32(leInt(packedFox, packedFox.size() - 8)).toString())
    println("empty-isize " + leInt(packedEmpty, packedEmpty.size() - 4).toString())
    return 0
}
