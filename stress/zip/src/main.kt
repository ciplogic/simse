package fixtures

import io
import zip

// The `zip` module (modules/zip/api.kt): Zip extraction. The case names `zip`, `gzip` (its
// deflate decoder and CRC32), `unsafe` (their types) and `io` in `compiler-args`.
//
// The fixture (src/fixture.kt) is a Zip written by .NET's `ZipArchive`: two deflate
// entries, a stored one, a directory entry and an empty file. The program writes it under
// stress/.work/zip/out (the harness's own folder), unpacks it, reads every file back and
// compares the bytes, then feeds `unzip` four broken archives - a truncated one, one whose
// first entry's recorded CRC32 is flipped, one whose first entry names an unsupported
// compression method, and one whose entry name tries to leave the output folder - and one
// written by PowerShell's `Compress-Archive`, whose names use `\` separators.

// The reader the checks use, declared the way the FFI path is (`@SmGen("cpp", sym)`, like
// stress/native-read-file): the `fileio` section's always-emitted reader.
@SmGen("cpp", "simse_native_readFile")
fun readFile(path: Str): Str

fun fromHex(text: Str): List<Int8> {
    var out: List<Int8> = List<Int8>()
    var i: Int = 0
    while (i + 1 < text.size()) {
        out.append(hexDigit(text.charAt(i)) * 16 + hexDigit(text.charAt(i + 1)))
        i = i + 2
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

fun toBytes(text: Str): List<Int8> {
    var out: List<Int8> = List<Int8>()
    var i: Int = 0
    while (i < text.size()) {
        out.append(text.charAt(i))
        i = i + 1
    }
    return out
}

fun toStr(bytes: List<Int8>): Str {
    var text: Str = Str()
    var i: Int = 0
    while (i < bytes.size()) {
        text.append(bytes[i])
        i = i + 1
    }
    return text
}

fun byteAt(bytes: List<Int8>, at: Int): Int {
    val wide: Int = bytes[at]
    return wide & 255
}

fun withByteAt(bytes: List<Int8>, at: Int, value: Int): List<Int8> {
    var out: List<Int8> = List<Int8>()
    var i: Int = 0
    while (i < bytes.size()) {
        if (i == at) {
            out.append(value)
        } else {
            out.append(bytes[i])
        }
        i = i + 1
    }
    return out
}

fun firstBytes(bytes: List<Int8>, count: Int): List<Int8> {
    var out: List<Int8> = List<Int8>()
    var i: Int = 0
    while (i < count) {
        out.append(bytes[i])
        i = i + 1
    }
    return out
}

// The little-endian 32 bits at `at`, through `Int64` so the top bit cannot sign-extend.
fun leUInt32(bytes: List<Int8>, at: Int): Int {
    var value: Int64 = byteAt(bytes, at)
    val b1: Int64 = byteAt(bytes, at + 1)
    val b2: Int64 = byteAt(bytes, at + 2)
    val b3: Int64 = byteAt(bytes, at + 3)
    value = value | (b1 << 8) | (b2 << 16) | (b3 << 24)
    return value
}

fun findEocd(bytes: List<Int8>): Int {
    var at: Int = bytes.size() - 22
    while (at >= 0) {
        if (byteAt(bytes, at) == 80 && byteAt(bytes, at + 1) == 75
            && byteAt(bytes, at + 2) == 5 && byteAt(bytes, at + 3) == 6
        ) {
            return at
        }
        at = at - 1
    }
    return -1
}

fun equalBytes(got: List<Int8>, want: List<Int8>): Bool {
    if (got.size() != want.size()) {
        return false
    }
    var i: Int = 0
    while (i < got.size()) {
        if (got[i] != want[i]) {
            return false
        }
        i = i + 1
    }
    return true
}

fun equalText(got: Str, want: Str): Bool {
    if (got.size() != want.size()) {
        return false
    }
    var i: Int = 0
    while (i < got.size()) {
        if (got.charAt(i) != want.charAt(i)) {
            return false
        }
        i = i + 1
    }
    return true
}

// The 0..255 bytes the fixture's stored entry holds.
fun counterBytes(): List<Int8> {
    var out: List<Int8> = List<Int8>()
    var i: Int = 0
    while (i < 256) {
        out.append(i)
        i = i + 1
    }
    return out
}

// The fixture's nested text: the lazy-dog line thirty times.
fun nestedText(): Str {
    var text: Str = Str()
    var i: Int = 0
    while (i < 30) {
        text.appendStr("the quick brown fox jumps over the lazy dog. ")
        i = i + 1
    }
    return text
}

fun fileBytes(path: Str): List<Int8> {
    return toBytes(readFile(path))
}

fun main(): Int {
    if (!createDirectories("stress/.work/zip/out")) {
        println("setup: cannot create the work folder")
        return 1
    }
    val fixture: List<Int8> = fromHex(zipFixture())
    if (!writeFile("stress/.work/zip/out/test.zip", toStr(fixture))) {
        println("setup: cannot write the archive")
        return 1
    }

    val done: Res<Int> = unzip("stress/.work/zip/out/test.zip", "stress/.work/zip/out/plain")
    if (!done.isOk()) {
        println("unzip error " + done.error)
        return 1
    }
    println("unzip " + done.value.toString())

    // The four files, byte for byte.
    println("hello " + readFile("stress/.work/zip/out/plain/hello.txt"))
    println("hello-ok " + equalText(
        readFile("stress/.work/zip/out/plain/hello.txt"), "hello hello hello hello").toString())
    println("nested-ok " + equalBytes(
        fileBytes("stress/.work/zip/out/plain/sub/nested.txt"), toBytes(nestedText())).toString())
    println("stored-ok " + equalBytes(
        fileBytes("stress/.work/zip/out/plain/stored.bin"), counterBytes()).toString())
    println("empty-ok " + (readFile("stress/.work/zip/out/plain/empty.txt").size() == 0).toString())
    println("sub-is-dir " + pathIsDirectory("stress/.work/zip/out/plain/sub").toString())

    // A truncated archive: the end-of-central-directory record is gone.
    val clipped: List<Int8> = firstBytes(fixture, fixture.size() - 4)
    writeFile("stress/.work/zip/out/short.zip", toStr(clipped))
    val shortResult: Res<Int> = unzip("stress/.work/zip/out/short.zip", "stress/.work/zip/out/s")
    println("truncated " + shortResult.isOk().toString() + " " + shortResult.error)

    // The first entry's recorded CRC32 byte, flipped: the data unpacks, the check fails.
    val eocd: Int = findEocd(fixture)
    val directoryAt: Int = leUInt32(fixture, eocd + 16)
    val crcFlipped: List<Int8> = withByteAt(fixture, directoryAt + 16, byteAt(fixture, directoryAt + 16) ^ 255)
    writeFile("stress/.work/zip/out/crc.zip", toStr(crcFlipped))
    val crcResult: Res<Int> = unzip("stress/.work/zip/out/crc.zip", "stress/.work/zip/out/c")
    println("crc " + crcResult.isOk().toString() + " " + crcResult.error)

    // The first entry's method field, set to 12 (bzip2, which this module does not read).
    val methodFlipped: List<Int8> = withByteAt(fixture, directoryAt + 10, 12)
    writeFile("stress/.work/zip/out/method.zip", toStr(methodFlipped))
    val methodResult: Res<Int> = unzip("stress/.work/zip/out/method.zip", "stress/.work/zip/out/m")
    println("method " + methodResult.isOk().toString() + " " + methodResult.error)

    // An entry name that would leave the output folder.
    val evil: List<Int8> = fromHex(zipEvil())
    writeFile("stress/.work/zip/out/evil.zip", toStr(evil))
    val evilResult: Res<Int> = unzip("stress/.work/zip/out/evil.zip", "stress/.work/zip/out/e")
    println("evil " + evilResult.isOk().toString() + " " + evilResult.error)
    println("evil-absent " + (!pathExists("stress/.work/zip/out/evil.txt")).toString())

    // An archive from PowerShell's Compress-Archive: its names use `\` separators, which
    // `unzip` normalizes into folders.
    val backslash: List<Int8> = fromHex(zipBackslash())
    writeFile("stress/.work/zip/out/back.zip", toStr(backslash))
    val backResult: Res<Int> = unzip("stress/.work/zip/out/back.zip", "stress/.work/zip/out/back")
    println("backslash " + backResult.isOk().toString() + " " + backResult.value.toString())
    println("back-one " + readFile("stress/.work/zip/out/back/one.txt"))
    println("back-two " + readFile("stress/.work/zip/out/back/deep/two.txt"))
    return 0
}
