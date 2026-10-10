package gzip

import unsafe

// The `gzip` module: Gzip (RFC 1952) encoding of a byte string - the `List<Int8> ->
// Res<List<Int8>>` counterpart of C#'s `GZipStream`, minus the stream: one call packs the
// whole input and hands the bytes back.
//
// The comparison level is zlib's (1 = fastest, 9 = best; 6 is the default), and it picks
// how hard the matcher looks for a back-reference: the deflate data itself is always a
// single fixed-Huffman block (RFC 1951 3.2.6), so every level emits a stream any gzip
// reader decompresses - a deeper search only buys a smaller one. The CRC32 and the ISIZE
// of the trailer are the RFC 1952 ones.
//
// The types are the `unsafe` module's unsigned reads, and this module names it, so a
// program building the encoder passes both roots: `--module modules/gzip --module
// modules/unsafe`, and `import gzip`.

// ---- the bit sink ----

// Deflate's bit order (RFC 1951 3.1.1): a byte is filled from its least-significant bit
// up, data elements (block headers, extra bits) go in low bit first, and Huffman codes go
// in *high* bit first - so `writeCode` reverses the code and every write below is plain
// LSB-first. `acc` holds the `pending` bits not yet flushed.
data class GzBitWriter(var acc: Int, var pending: Int, var out: List<Int8>) {
    fun writeBits(count: Int, value: Int): Unit {
        this.acc = this.acc | ((value & ((1 << count) - 1)) << this.pending)
        this.pending = this.pending + count
        while (this.pending >= 8) {
            this.out.append(this.acc & 255)
            this.acc = this.acc >> 8
            this.pending = this.pending - 8
        }
    }

    // A Huffman code of `count` bits, MSB-first in the stream.
    fun writeCode(code: Int, count: Int): Unit {
        this.writeBits(count, gzReverseBits(code, count))
    }

    // Flushes the last partial byte, zero-padded. The stream is byte-aligned afterwards.
    fun finish(): Unit {
        if (this.pending > 0) {
            this.out.append(this.acc & 255)
        }
        this.acc = 0
        this.pending = 0
    }
}

// The first `count` bits of `code`, read backwards (`0b110` -> `0b011`).
fun gzReverseBits(code: Int, count: Int): Int {
    var reversed: Int = 0
    var value: Int = code
    var i: Int = 0
    while (i < count) {
        reversed = (reversed << 1) | (value & 1)
        value = value >> 1
        i = i + 1
    }
    return reversed
}

// ---- the fixed tables ----

// The fixed literal/length code of a symbol, and its bit count (RFC 1951 3.2.6).
fun gzLitCode(symbol: Int): Int {
    if (symbol <= 143) {
        return 48 + symbol
    }
    if (symbol <= 255) {
        return 400 + symbol - 144
    }
    if (symbol <= 279) {
        return symbol - 256
    }
    return 192 + symbol - 280
}

fun gzLitBits(symbol: Int): Int {
    if (symbol <= 143) {
        return 8
    }
    if (symbol <= 255) {
        return 9
    }
    if (symbol <= 279) {
        return 7
    }
    return 8
}

// The length symbol (257..285) whose range covers `length` (3..258), and that symbol's
// base and extra-bit count (RFC 1951 3.2.5): 257..264 are 3..10 directly, then the widths
// double every four symbols.
fun gzLengthSymbol(length: Int): Int {
    var symbol: Int = 285
    while (symbol > 257 && gzLengthBase(symbol) > length) {
        symbol = symbol - 1
    }
    return symbol
}

fun gzLengthBase(symbol: Int): Int {
    if (symbol <= 264) {
        return 3 + symbol - 257
    }
    if (symbol <= 268) {
        return 11 + (symbol - 265) * 2
    }
    if (symbol <= 272) {
        return 19 + (symbol - 269) * 4
    }
    if (symbol <= 276) {
        return 35 + (symbol - 273) * 8
    }
    if (symbol <= 280) {
        return 67 + (symbol - 277) * 16
    }
    if (symbol == 281) {
        return 131
    }
    if (symbol == 282) {
        return 163
    }
    if (symbol == 283) {
        return 195
    }
    if (symbol == 284) {
        return 227
    }
    return 258
}

fun gzLengthExtra(symbol: Int): Int {
    if (symbol <= 264) {
        return 0
    }
    if (symbol <= 268) {
        return 1
    }
    if (symbol <= 272) {
        return 2
    }
    if (symbol <= 276) {
        return 3
    }
    if (symbol <= 280) {
        return 4
    }
    if (symbol <= 284) {
        return 5
    }
    return 0
}

// The distance symbol (0..29) whose range covers `distance` (1..32768), and that symbol's
// base and extra-bit count (RFC 1951 3.2.5). Symbols 0..3 are 1..4 directly; 4 and up
// cover two ranges per width (`4a` and `4a+1`), which is where the parity in the base
// below comes from.
fun gzDistanceSymbol(distance: Int): Int {
    var symbol: Int = 29
    while (symbol > 0 && gzDistanceBase(symbol) > distance) {
        symbol = symbol - 1
    }
    return symbol
}

fun gzDistanceBase(symbol: Int): Int {
    if (symbol <= 3) {
        return symbol + 1
    }
    val extra: Int = symbol / 2 - 1
    var base: Int = (1 << (extra + 1)) + 1
    if (symbol & 1 == 1) {
        base = base + (1 << extra)
    }
    return base
}

fun gzDistanceExtra(symbol: Int): Int {
    if (symbol <= 3) {
        return 0
    }
    return symbol / 2 - 1
}

// ---- the matcher ----

// A back-reference: `length` bytes at `distance` back. `length` 0 means "none found".
data class GzMatch(var length: Int, var distance: Int)

// Two chain heads per bucket: the newest position whose three bytes hash there, and each
// inserted position's predecessor in the same bucket. Walking `prev` visits the older
// candidates newest-first, so a distance past the 32 KiB window ends the walk.
fun gzHash3(a: Int, b: Int, c: Int): Int {
    return ((a << 10) ^ (b << 5) ^ c) & 65535
}

// The level's chain depth: how many candidates a search may try. Deeper finds longer
// matches on repetitive input at the cost of time.
fun gzChainLimit(level: Int): Int {
    return 32 * level
}

// Inserts `at` into the chains. The last two positions have no three-byte prefix and are
// never candidates, so they are skipped.
fun gzInsert(head: *List<Int>, prev: *List<Int>, bytes: List<Int>, at: Int): Unit {
    if (at + 2 >= bytes.size()) {
        return
    }
    val bucket: Int = gzHash3(bytes[at], bytes[at + 1], bytes[at + 2])
    prev.append(head[bucket])
    head[bucket] = at
}

// The longest match at `at` within the window, `chain` candidates deep at most.
fun gzFindMatch(bytes: List<Int>, head: List<Int>, prev: List<Int>, at: Int, chain: Int): GzMatch {
    var best: GzMatch = GzMatch(0, 0)
    if (at + 2 >= bytes.size()) {
        return best
    }
    var maxLength: Int = bytes.size() - at
    if (maxLength > 258) {
        maxLength = 258
    }
    val bucket: Int = gzHash3(bytes[at], bytes[at + 1], bytes[at + 2])
    var candidate: Int = head[bucket]
    var remaining: Int = chain
    while (candidate >= 0 && remaining > 0) {
        if (at - candidate > 32768) {
            break
        }
        var length: Int = 0
        while (length < maxLength && bytes[candidate + length] == bytes[at + length]) {
            length = length + 1
        }
        if (length > best.length) {
            best = GzMatch(length, at - candidate)
            if (length >= maxLength) {
                break
            }
        }
        candidate = prev[candidate]
        remaining = remaining - 1
    }
    return best
}

// ---- the deflate block ----

fun gzWriteLength(writer: *GzBitWriter, length: Int): Unit {
    val symbol: Int = gzLengthSymbol(length)
    writer.writeCode(gzLitCode(symbol), gzLitBits(symbol))
    val extra: Int = gzLengthExtra(symbol)
    if (extra > 0) {
        writer.writeBits(extra, length - gzLengthBase(symbol))
    }
}

fun gzWriteDistance(writer: *GzBitWriter, distance: Int): Unit {
    val symbol: Int = gzDistanceSymbol(distance)
    writer.writeCode(symbol, 5)
    val extra: Int = gzDistanceExtra(symbol)
    if (extra > 0) {
        writer.writeBits(extra, distance - gzDistanceBase(symbol))
    }
}

// The input as the 0..255 byte reads the encoder works on.
fun gzBytes(input: List<Int8>): List<Int> {
    var bytes: List<Int> = List<Int>()
    var i: Int = 0
    while (i < input.size()) {
        val wide: Int = input[i]
        bytes.append(wide & 255)
        i = i + 1
    }
    return bytes
}

// The whole deflate data as one fixed-Huffman block: the three-bit header, then a literal
// per unmatched byte and a length/distance pair per match, then the end-of-block symbol.
fun gzDeflateFixed(bytes: List<Int>, level: Int, writer: *GzBitWriter): Unit {
    writer.writeBits(1, 1)
    writer.writeBits(2, 1)

    val chain: Int = gzChainLimit(level)
    var head: List<Int> = List<Int>()
    var i: Int = 0
    while (i < 65536) {
        head.append(-1)
        i = i + 1
    }
    var prev: List<Int> = List<Int>()

    val n: Int = bytes.size()
    var at: Int = 0
    while (at < n) {
        val match: GzMatch = gzFindMatch(bytes, head, prev, at, chain)
        if (match.length >= 3) {
            gzWriteLength(writer, match.length)
            gzWriteDistance(writer, match.distance)
            var j: Int = at
            while (j < at + match.length) {
                gzInsert(*head, *prev, bytes, j)
                j = j + 1
            }
            at = at + match.length
        } else {
            val symbol: Int = bytes[at]
            writer.writeCode(gzLitCode(symbol), gzLitBits(symbol))
            gzInsert(*head, *prev, bytes, at)
            at = at + 1
        }
    }

    // The fixed end-of-block code, symbol 256.
    writer.writeCode(gzLitCode(256), gzLitBits(256))
    writer.finish()
}

// ---- the gzip wrapper ----

// The 10-byte header (RFC 1952 2.3): magic, deflate, no flags, mtime 0, XFL 0, OS 10
// (Unix). The XFL byte is advisory and left 0 for every level.
fun gzHeader(writer: *GzBitWriter): Unit {
    writer.out.append(31)
    writer.out.append(139)
    writer.out.append(8)
    writer.out.append(0)
    writer.out.append(0)
    writer.out.append(0)
    writer.out.append(0)
    writer.out.append(0)
    writer.out.append(0)
    writer.out.append(10)
}

// The 8-byte trailer: the CRC32 of the uncompressed bytes, then ISIZE (their count mod
// 2^32), both little-endian (RFC 1952 2.3.1).
fun gzTrailer(bytes: List<Int>, writer: *GzBitWriter): Unit {
    val crc: Int = gzCrc32(bytes).toInt()
    writer.out.append(crc & 255)
    writer.out.append((crc >> 8) & 255)
    writer.out.append((crc >> 16) & 255)
    writer.out.append((crc >> 24) & 255)
    val size: Int = bytes.size()
    writer.out.append(size & 255)
    writer.out.append((size >> 8) & 255)
    writer.out.append((size >> 16) & 255)
    writer.out.append((size >> 24) & 255)
}

// The IEEE CRC32 (polynomial 0xEDB88320, the reflected form gzip names), built the way
// every implementation builds it: a table of the 256 byte-wise steps, then the running
// register, initialized and finalized with all ones.
fun gzCrc32(bytes: List<Int>): UInt32 {
    val table: List<UInt32> = gzCrcTable()
    var crc: UInt32 = UInt32(-1)
    var i: Int = 0
    while (i < bytes.size()) {
        val index: Int = (crc.toInt() ^ bytes[i]) & 255
        crc = table[index].xorWith(crc.shr(8))
        i = i + 1
    }
    return crc.xorWith(UInt32(-1))
}

fun gzCrcTable(): List<UInt32> {
    var table: List<UInt32> = List<UInt32>()
    var i: Int = 0
    while (i < 256) {
        var entry: UInt32 = UInt32(i)
        var bit: Int = 0
        while (bit < 8) {
            if ((entry.toInt() & 1) == 1) {
                entry = entry.shr(1).xorWith(UInt32(-306674912))
            } else {
                entry = entry.shr(1)
            }
            bit = bit + 1
        }
        table.append(entry)
        i = i + 1
    }
    return table
}

// ---- the API ----

// Packs `input` into a gzip stream at the default level (6 - zlib's `Z_DEFAULT_COMPRESSION`,
// the balance point of speed and size).
fun compress(input: List<Int8>): Res<List<Int8>> {
    return compressLevel(input, 6)
}

// Packs `input` into a gzip stream at the standard level, zlib's `1` (fastest) to `9`
// (best); a level outside that range answers `err`. The output is a complete gzip member:
// header, one fixed-Huffman deflate block, CRC32 and ISIZE.
fun compressLevel(input: List<Int8>, level: Int): Res<List<Int8>> {
    if (level < 1 || level > 9) {
        return Res<List<Int8>>.err("gzip: the compression level must be 1 (fastest) to 9 (best)")
    }
    var writer: GzBitWriter = GzBitWriter(0, 0, List<Int8>())
    gzHeader(writer)
    val bytes: List<Int> = gzBytes(input)
    gzDeflateFixed(bytes, level, writer)
    gzTrailer(bytes, writer)
    return Res<List<Int8>>.ok(writer.out)
}
