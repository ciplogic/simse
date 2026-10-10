package fixtures

import gzip
import unsafe

// The `gzip` module (modules/gzip/api.kt): Gzip encoding and decoding of a `List<Int8>`.
// The case names the module and `unsafe` (whose types it is built on) in `compiler-args`.
//
// The encoder half packs a few shapes - a small text (its whole stream is printed, a known
// answer), a run, `all` the byte values, a pseudo-random stream, a 100 KiB text, and the
// empty input - and reads the trailer back: the last four bytes are the ISIZE and the four
// before them the CRC32, so both are pinned without a second implementation.
//
// The decoder half runs every one of those streams back through `decompress` and compares
// the bytes, then unpacks the streams in src/samples.kt - real fixed-Huffman, stored and
// dynamic-Huffman members written by .NET's `GZipStream` - and finally feeds it three
// broken streams (a bad magic, a missing trailer, a flipped CRC byte).

fun toBytes(text: Str): List<Int8> {
    var bytes: List<Int8> = List<Int8>()
    var i: Int = 0
    while (i < text.size()) {
        bytes.append(text[i])
        i = i + 1
    }
    return bytes
}

fun copyBytes(bytes: List<Int8>): List<Int8> {
    var out: List<Int8> = List<Int8>()
    var i: Int = 0
    while (i < bytes.size()) {
        out.append(bytes[i])
        i = i + 1
    }
    return out
}

fun byteAt(bytes: List<Int8>, at: Int): Int {
    val wide: Int = bytes[at]
    return wide & 255
}

// The little-endian 32 bits at `at`, read unsigned so the top bit cannot sign-extend.
fun leUInt32(bytes: List<Int8>, at: Int): UInt32 {
    val b0: UInt32 = UInt32(byteAt(bytes, at))
    val b1: UInt32 = UInt32(byteAt(bytes, at + 1))
    val b2: UInt32 = UInt32(byteAt(bytes, at + 2))
    val b3: UInt32 = UInt32(byteAt(bytes, at + 3))
    return b0.orWith(b1.shl(8)).orWith(b2.shl(16)).orWith(b3.shl(24))
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

fun hexDigit(ch: Char): Int {
    val code: Int = ch
    if (code >= 48 && code <= 57) {
        return code - 48
    }
    return code - 97 + 10
}

fun fromHex(text: Str): List<Int8> {
    var out: List<Int8> = List<Int8>()
    var i: Int = 0
    while (i + 1 < text.size()) {
        out.append(hexDigit(text.charAt(i)) * 16 + hexDigit(text.charAt(i + 1)))
        i = i + 2
    }
    return out
}

fun equalBytes(left: List<Int8>, right: List<Int8>): Bool {
    if (left.size() != right.size()) {
        return false
    }
    var i: Int = 0
    while (i < left.size()) {
        if (left[i] != right[i]) {
            return false
        }
        i = i + 1
    }
    return true
}

// `count` bytes of `state = (state * 1103515245 + 12345) & 2147483647` from `seed`, each
// read as `(state >> 16) & mask` (the samples' two generators).
fun lcgBytes(count: Int, seed: Int, mask: Int): List<Int8> {
    var state: Int64 = seed
    var out: List<Int8> = List<Int8>()
    var i: Int = 0
    while (i < count) {
        state = (state * 1103515245 + 12345) & 2147483647
        out.append((state >> 16) & mask)
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

// `compress` then `decompress`, against the input it started from.
fun roundTrip(name: Str, input: List<Int8>, level: Int): Unit {
    val packed: Res<List<Int8>> = compressLevel(input, level)
    if (!packed.isOk()) {
        println(name + " round-trip error " + packed.error)
        return
    }
    val plain: Res<List<Int8>> = decompress(packed.value)
    if (!plain.isOk()) {
        println(name + " round-trip error " + plain.error)
        return
    }
    if (!equalBytes(plain.value, input)) {
        println(name + " round-trip MISMATCH")
        return
    }
    println(name + " round-trip ok")
}

// A stream from another encoder, against the bytes it must unpack to.
fun sample(name: Str, packedHex: Str, expected: List<Int8>): Unit {
    val plain: Res<List<Int8>> = decompress(fromHex(packedHex))
    if (!plain.isOk()) {
        println(name + " error " + plain.error)
        return
    }
    if (!equalBytes(plain.value, expected)) {
        println(name + " MISMATCH")
        return
    }
    println(name + " ok " + plain.value.size().toString())
}

fun main(): Int {
    // ---- the encoder ----

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
    var foxText: Str = Str()
    var round: Int = 0
    while (round < 20) {
        foxText.appendStr("the quick brown fox jumps over the lazy dog. ")
        round = round + 1
    }
    val fox: List<Int8> = toBytes(foxText)
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
    val noise: List<Int8> = lcgBytes(5000, 1, 255)
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
    val big: List<Int8> = toBytes(bigText)
    report("big", big, 6)

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
    println("run-isize " + leUInt32(packedRun, packedRun.size() - 4).toString())
    println("run-crc " + leUInt32(packedRun, packedRun.size() - 8).toString())
    println("fox-isize " + leUInt32(packedFox, packedFox.size() - 4).toString())
    println("fox-crc " + leUInt32(packedFox, packedFox.size() - 8).toString())
    println("empty-isize " + leUInt32(packedEmpty, packedEmpty.size() - 4).toString())

    // ---- the decoder: our own streams, back ----
    roundTrip("hello", hello, 6)
    roundTrip("run", run, 6)
    roundTrip("fox", fox, 6)
    roundTrip("all", all, 6)
    roundTrip("noise", noise, 6)
    roundTrip("big", big, 6)
    roundTrip("empty", empty, 6)

    // ---- the decoder: another encoder's streams ----
    var blockText: Str = Str()
    var line2: Int = 0
    while (line2 < 4) {
        blockText.appendStr("the quick brown fox jumps over the lazy dog. ")
        line2 = line2 + 1
    }
    sample("sampleA", sampleA(), toBytes(blockText))
    sample("sampleB", sampleB(), lcgBytes(256, 1, 255))
    sample("sampleC", sampleC(), toBytes("abcabcabc"))
    sample("sampleE", sampleE(), lcgBytes(512, 7, 15))

    // ---- the decoder: broken streams ----
    var badMagic: List<Int8> = copyBytes(packedHello.value)
    badMagic[0] = 0
    val badMagicResult: Res<List<Int8>> = decompress(badMagic)
    println("bad-magic " + badMagicResult.isOk().toString() + " " + badMagicResult.error)

    var noTrailer: List<Int8> = List<Int8>()
    i = 0
    while (i < packedHello.value.size() - 8) {
        noTrailer.append(packedHello.value[i])
        i = i + 1
    }
    val noTrailerResult: Res<List<Int8>> = decompress(noTrailer)
    println("no-trailer " + noTrailerResult.isOk().toString() + " " + noTrailerResult.error)

    var badCrc: List<Int8> = copyBytes(packedFox)
    badCrc[badCrc.size() - 8] = 0
    val badCrcResult: Res<List<Int8>> = decompress(badCrc)
    println("bad-crc " + badCrcResult.isOk().toString() + " " + badCrcResult.error)
    return 0
}
