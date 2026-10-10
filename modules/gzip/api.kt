package gzip

import unsafe

// The `gzip` module: Gzip (RFC 1952) encoding and decoding of a byte string - the
// `List<Int8> -> Res<List<Int8>>` counterpart of C#'s `GZipStream`, minus the stream: one
// call packs or unpacks the whole input.
//
// `compress` and `compressLevel` write one fixed-Huffman deflate block (RFC 1951 3.2.6)
// into a complete member: header, block, CRC32 and ISIZE. The level is zlib's (1 =
// fastest, 9 = best; 6 is the default) and picks how hard the matcher looks for a
// back-reference, so every level emits a stream any gzip reader decompresses - a deeper
// search only buys a smaller one. `decompress` reads the whole deflate format back -
// stored, fixed and dynamic blocks - so it unpacks what other encoders write too, and
// answers `err` when the stream is malformed or its CRC32/ISIZE do not check out.
//
// The types are the `unsafe` module's unsigned reads, and this module names it, so a
// program building either side passes both roots: `--module modules/gzip --module
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
    writer.out.append(-117) // 0x8b
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

// ---- the inflater ----

// The bit source: `bitPos` counts bits into `data`'s bytes, least-significant bit first -
// the order `GzBitWriter` fills them in. A read past the end raises `error` and answers
// zeros, so a truncated stream decodes to something and the caller reports it.
data class GzBitReader(var bytes: List<Int>, var bitPos: Int, var error: Bool) {
    fun readBits(count: Int): Int {
        var value: Int = 0
        var i: Int = 0
        while (i < count) {
            val at: Int = this.bitPos + i
            val byteAt: Int = at >> 3
            if (byteAt >= this.bytes.size()) {
                this.error = true
                return value
            }
            val bit: Int = (this.bytes[byteAt] >> (at & 7)) & 1
            value = value | (bit << i)
            i = i + 1
        }
        this.bitPos = this.bitPos + count
        return value
    }

    fun readBit(): Int {
        return this.readBits(1)
    }

    // One whole byte; only called at a byte boundary (a stored block, after `align`).
    fun readByte(): Int {
        if (this.bitPos % 8 != 0) {
            this.error = true
            return 0
        }
        val byteAt: Int = this.bitPos >> 3
        if (byteAt >= this.bytes.size()) {
            this.error = true
            return 0
        }
        this.bitPos = this.bitPos + 8
        return this.bytes[byteAt]
    }

    fun align(): Unit {
        val drop: Int = this.bitPos % 8
        if (drop != 0) {
            this.bitPos = this.bitPos + 8 - drop
        }
    }
}

// A canonical Huffman decoding table (puff.c's shape): `counts[len]` is how many codes are
// `len` bits long, and `symbols` lists the symbols in (length, symbol) order, so a walk
// from length 1 up can decide a code by its rank within its length.
data class GzHuffman(var counts: List<Int>, var symbols: List<Int>)

// The `counts` of a code-length array, and how many codes the lengths leave unassigned:
// 0 is a complete code, negative an over-subscribed one.
data class GzHuffmanCount(var left: Int, var counts: List<Int>)

fun gzCountCodes(lengths: List<Int>): GzHuffmanCount {
    var counts: List<Int> = List<Int>()
    var i: Int = 0
    while (i < 16) {
        counts.append(0)
        i = i + 1
    }
    i = 0
    while (i < lengths.size()) {
        counts[lengths[i]] = counts[lengths[i]] + 1
        i = i + 1
    }
    var left: Int = 1
    var len: Int = 1
    while (len < 16) {
        left = (left << 1) - counts[len]
        if (left < 0) {
            return GzHuffmanCount(-1, counts)
        }
        len = len + 1
    }
    return GzHuffmanCount(left, counts)
}

fun gzHuffmanFromLengths(lengths: List<Int>): GzHuffman {
    val counted: GzHuffmanCount = gzCountCodes(lengths)
    var total: Int = 0
    var len: Int = 1
    while (len < 16) {
        total = total + counted.counts[len]
        len = len + 1
    }
    var offsets: List<Int> = List<Int>()
    len = 0
    while (len < 16) {
        offsets.append(0)
        len = len + 1
    }
    len = 1
    while (len < 15) {
        offsets[len + 1] = offsets[len] + counted.counts[len]
        len = len + 1
    }
    var symbols: List<Int> = List<Int>()
    var i: Int = 0
    while (i < total) {
        symbols.append(0)
        i = i + 1
    }
    var symbol: Int = 0
    while (symbol < lengths.size()) {
        val width: Int = lengths[symbol]
        if (width != 0) {
            symbols[offsets[width]] = symbol
            offsets[width] = offsets[width] + 1
        }
        symbol = symbol + 1
    }
    return GzHuffman(counted.counts, symbols)
}

// The symbol the next code names, or -1 when the bits are no code at all (truncated, or
// a code the table does not hold).
fun gzDecode(reader: *GzBitReader, table: GzHuffman): Int {
    var code: Int = 0
    var first: Int = 0
    var index: Int = 0
    var len: Int = 1
    while (len < 16) {
        code = code | reader.readBit()
        val count: Int = table.counts[len]
        if (code - count < first) {
            val at: Int = index + code - first
            if (at < 0 || at >= table.symbols.size()) {
                return -1
            }
            return table.symbols[at]
        }
        index = index + count
        first = (first + count) << 1
        code = code << 1
        len = len + 1
    }
    return -1
}

// The fixed literal/length code as a decoding table: the lengths `gzLitBits` answers.
fun gzFixedLitHuffman(): GzHuffman {
    var lengths: List<Int> = List<Int>()
    var symbol: Int = 0
    while (symbol < 288) {
        lengths.append(gzLitBits(symbol))
        symbol = symbol + 1
    }
    return gzHuffmanFromLengths(lengths)
}

// The fixed distance code: 30 five-bit codes (30 and 31 are not in the code).
fun gzFixedDistHuffman(): GzHuffman {
    var lengths: List<Int> = List<Int>()
    var symbol: Int = 0
    while (symbol < 30) {
        lengths.append(5)
        symbol = symbol + 1
    }
    return gzHuffmanFromLengths(lengths)
}

// RFC 1951 3.2.7's permutation: the symbol of the `index`th code-length code.
fun gzCodeOrder(index: Int): Int {
    if (index == 0) {
        return 16
    }
    if (index == 1) {
        return 17
    }
    if (index == 2) {
        return 18
    }
    if (index == 3) {
        return 0
    }
    if (index == 4) {
        return 8
    }
    if (index == 5) {
        return 7
    }
    if (index == 6) {
        return 9
    }
    if (index == 7) {
        return 6
    }
    if (index == 8) {
        return 10
    }
    if (index == 9) {
        return 5
    }
    if (index == 10) {
        return 11
    }
    if (index == 11) {
        return 4
    }
    if (index == 12) {
        return 12
    }
    if (index == 13) {
        return 3
    }
    if (index == 14) {
        return 13
    }
    if (index == 15) {
        return 2
    }
    if (index == 16) {
        return 14
    }
    if (index == 17) {
        return 1
    }
    return 15
}

// One block's symbols up to its end-of-block marker. Literals append, and a length/distance
// pair copies (`length` may pass the distance: the copy overlaps itself, which is exactly
// a run).
fun gzInflateBlock(reader: *GzBitReader, out: *List<Int>, lit: GzHuffman, dist: GzHuffman): Str {
    while (true) {
        val symbol: Int = gzDecode(reader, lit)
        if (reader.error) {
            return "gzip: truncated deflate data"
        }
        if (symbol < 0) {
            return "gzip: invalid deflate literal/length code"
        }
        if (symbol < 256) {
            out.append(symbol)
            continue
        }
        if (symbol == 256) {
            return ""
        }
        if (symbol > 285) {
            return "gzip: invalid deflate length code"
        }
        val length: Int = gzLengthBase(symbol) + reader.readBits(gzLengthExtra(symbol))
        val distanceSymbol: Int = gzDecode(reader, dist)
        if (reader.error) {
            return "gzip: truncated deflate data"
        }
        if (distanceSymbol < 0 || distanceSymbol > 29) {
            return "gzip: invalid deflate distance code"
        }
        val distance: Int = gzDistanceBase(distanceSymbol) + reader.readBits(gzDistanceExtra(distanceSymbol))
        if (reader.error) {
            return "gzip: truncated deflate data"
        }
        if (distance > out.size()) {
            return "gzip: the deflate distance goes past the start of the output"
        }
        var copied: Int = 0
        while (copied < length) {
            val byte: Int = out[out.size() - distance]
            out.append(byte)
            copied = copied + 1
        }
    }
}

// A stored block (BTYPE 00): LEN bytes copied as they stand, after the byte alignment.
fun gzInflateStored(reader: *GzBitReader, out: *List<Int>): Str {
    reader.align()
    val len: Int = reader.readByte() | (reader.readByte() << 8)
    val complement: Int = reader.readByte() | (reader.readByte() << 8)
    if (reader.error) {
        return "gzip: truncated deflate data"
    }
    if (len != (complement ^ 65535)) {
        return "gzip: invalid stored block length"
    }
    var i: Int = 0
    while (i < len) {
        val byte: Int = reader.readByte()
        if (reader.error) {
            return "gzip: truncated deflate data"
        }
        out.append(byte)
        i = i + 1
    }
    return ""
}

// A dynamic block (BTYPE 10): the code-length code, the literal/length and distance codes
// it spells out (RLE included), then the block itself.
fun gzInflateDynamic(reader: *GzBitReader, out: *List<Int>): Str {
    val literalCount: Int = reader.readBits(5) + 257
    val distanceCount: Int = reader.readBits(5) + 1
    val codeCount: Int = reader.readBits(4) + 4
    if (reader.error) {
        return "gzip: truncated deflate data"
    }
    if (literalCount > 286 || distanceCount > 30) {
        return "gzip: invalid deflate code counts"
    }
    var codeLengths: List<Int> = List<Int>()
    var i: Int = 0
    while (i < 19) {
        codeLengths.append(0)
        i = i + 1
    }
    i = 0
    while (i < codeCount) {
        codeLengths[gzCodeOrder(i)] = reader.readBits(3)
        i = i + 1
    }
    if (reader.error) {
        return "gzip: truncated deflate data"
    }
    if (gzCountCodes(codeLengths).left != 0) {
        return "gzip: invalid deflate code-length code"
    }
    val codeTable: GzHuffman = gzHuffmanFromLengths(codeLengths)

    // The literal/length lengths first, then the distance ones; 16 repeats the previous
    // length, 17 and 18 a run of zeros.
    val total: Int = literalCount + distanceCount
    var lengths: List<Int> = List<Int>()
    while (lengths.size() < total) {
        val symbol: Int = gzDecode(reader, codeTable)
        if (reader.error) {
            return "gzip: truncated deflate data"
        }
        if (symbol < 0) {
            return "gzip: invalid deflate code lengths"
        }
        if (symbol < 16) {
            lengths.append(symbol)
            continue
        }
        var repeat: Int = 0
        var value: Int = 0
        if (symbol == 16) {
            if (lengths.size() == 0) {
                return "gzip: invalid deflate code lengths"
            }
            repeat = reader.readBits(2) + 3
            value = lengths[lengths.size() - 1]
        } else if (symbol == 17) {
            repeat = reader.readBits(3) + 3
        } else {
            repeat = reader.readBits(7) + 11
        }
        if (reader.error) {
            return "gzip: truncated deflate data"
        }
        if (lengths.size() + repeat > total) {
            return "gzip: invalid deflate code lengths"
        }
        i = 0
        while (i < repeat) {
            lengths.append(value)
            i = i + 1
        }
    }

    var litLengths: List<Int> = List<Int>()
    i = 0
    while (i < literalCount) {
        litLengths.append(lengths[i])
        i = i + 1
    }
    var distLengths: List<Int> = List<Int>()
    i = 0
    while (i < distanceCount) {
        distLengths.append(lengths[literalCount + i])
        i = i + 1
    }
    if (gzCountCodes(litLengths).left != 0) {
        return "gzip: invalid deflate literal/length code"
    }
    // A distance code may be incomplete when it is the single one-code case; over-subscribed
    // is never valid.
    val distCounted: GzHuffmanCount = gzCountCodes(distLengths)
    if (distCounted.left < 0) {
        return "gzip: invalid deflate distance code"
    }
    if (distCounted.left > 0 && distanceCount != distCounted.counts[0] + distCounted.counts[1]) {
        return "gzip: invalid deflate distance code"
    }
    return gzInflateBlock(reader, out, gzHuffmanFromLengths(litLengths), gzHuffmanFromLengths(distLengths))
}

// The whole deflate stream, block by block, until a block says it is the last.
fun gzInflate(reader: *GzBitReader): Res<List<Int>> {
    var out: List<Int> = List<Int>()
    while (true) {
        val isLast: Bool = reader.readBit() == 1
        val type: Int = reader.readBits(2)
        if (reader.error) {
            return Res<List<Int>>.err("gzip: truncated deflate data")
        }
        var reason: Str = ""
        if (type == 0) {
            reason = gzInflateStored(reader, out)
        } else if (type == 1) {
            reason = gzInflateBlock(reader, out, gzFixedLitHuffman(), gzFixedDistHuffman())
        } else if (type == 2) {
            reason = gzInflateDynamic(reader, out)
        } else {
            return Res<List<Int>>.err("gzip: invalid deflate block type")
        }
        if (reason != "") {
            return Res<List<Int>>.err(reason)
        }
        if (isLast) {
            return Res<List<Int>>.ok(out)
        }
    }
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

// Unpacks one gzip member: the header (extra fields, name, comment and header CRC are
// skipped), the deflate stream (stored, fixed or dynamic blocks), and the trailer, whose
// CRC32 and ISIZE must match what came out. `err` names what was wrong: a stream that is
// not gzip, a truncated or malformed deflate stream, or a mismatched trailer.
fun decompress(input: List<Int8>): Res<List<Int8>> {
    val bytes: List<Int> = gzBytes(input)
    if (bytes.size() < 18) {
        return Res<List<Int8>>.err("gzip: the stream is too short to be a gzip member")
    }
    if (bytes[0] != 31 || bytes[1] != 139) {
        return Res<List<Int8>>.err("gzip: not a gzip stream (the magic is 1f 8b)")
    }
    if (bytes[2] != 8) {
        return Res<List<Int8>>.err("gzip: unsupported compression method (only deflate, 8)")
    }
    val flags: Int = bytes[3]
    if (flags & 224 != 0) {
        return Res<List<Int8>>.err("gzip: the reserved header flags are set")
    }
    var at: Int = 10
    if (flags & 4 != 0) {
        if (at + 2 > bytes.size()) {
            return Res<List<Int8>>.err("gzip: the header ends inside an extra field")
        }
        at = at + 2 + (bytes[at] | (bytes[at + 1] << 8))
    }
    if (flags & 8 != 0) {
        at = gzSkipZero(bytes, at)
        if (at < 0) {
            return Res<List<Int8>>.err("gzip: the header ends inside the file name")
        }
        at = at + 1
    }
    if (flags & 16 != 0) {
        at = gzSkipZero(bytes, at)
        if (at < 0) {
            return Res<List<Int8>>.err("gzip: the header ends inside the comment")
        }
        at = at + 1
    }
    if (flags & 2 != 0) {
        at = at + 2
    }
    if (at > bytes.size()) {
        return Res<List<Int8>>.err("gzip: the header is truncated")
    }

    var reader: GzBitReader = GzBitReader(bytes, at * 8, false)
    val inflated: Res<List<Int>> = gzInflate(reader)
    if (!inflated.isOk()) {
        return Res<List<Int8>>.err(inflated.error)
    }
    val plain: List<Int> = inflated.value

    // The trailer starts at the byte after the deflate stream's last byte (its end-of-block
    // marker may leave the bit stream mid-byte, so the position is rounded up).
    val trailerAt: Int = (reader.bitPos + 7) / 8
    if (trailerAt + 8 > bytes.size()) {
        return Res<List<Int8>>.err("gzip: the trailer is truncated")
    }
    if (gzLeUInt32(bytes, trailerAt) != gzCrc32(plain)) {
        return Res<List<Int8>>.err("gzip: the CRC32 does not match the trailer")
    }
    if (gzLeUInt32(bytes, trailerAt + 4).value() != plain.size()) {
        return Res<List<Int8>>.err("gzip: the ISIZE does not match the output")
    }

    var out: List<Int8> = List<Int8>()
    var i: Int = 0
    while (i < plain.size()) {
        out.append(plain[i])
        i = i + 1
    }
    return Res<List<Int8>>.ok(out)
}

// The index of the first zero byte from `at` (a header string's terminator), or -1 when it
// is missing.
fun gzSkipZero(bytes: List<Int>, at: Int): Int {
    var i: Int = at
    while (i < bytes.size() && bytes[i] != 0) {
        i = i + 1
    }
    if (i >= bytes.size()) {
        return -1
    }
    return i
}

// The little-endian 32 bits at `at`, read unsigned so the top bit cannot sign-extend.
fun gzLeUInt32(bytes: List<Int>, at: Int): UInt32 {
    val b0: UInt32 = UInt32(bytes[at])
    val b1: UInt32 = UInt32(bytes[at + 1])
    val b2: UInt32 = UInt32(bytes[at + 2])
    val b3: UInt32 = UInt32(bytes[at + 3])
    return b0.orWith(b1.shl(8)).orWith(b2.shl(16)).orWith(b3.shl(24))
}
