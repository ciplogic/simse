package zip

import gzip
import io
import unsafe

// The `zip` module: Zip (APPNOTE 4.3) *extraction* - `unzip` reads an archive and writes
// its entries under an output folder, the library half of a command-line unzip. Packing a
// Zip is not here yet.
//
// A Zip entry's data is either stored or a *raw* deflate stream (the same bytes a gzip
// member carries, without the wrapper), so the deflate decoder and the CRC32 are the
// `gzip` module's, reached through `import gzip`: the codec stays in one place and this
// module is the container format around it. The end-of-central-directory record, the
// central directory and the local headers are walked here; every entry's CRC32 and size
// are checked against the record, entry names are normalized (a `\` separator becomes
// `/`, which some Windows writers use) and refused when they would escape the output
// folder, and Zip64 and encrypted entries are reported as unsupported rather than
// misread.
//
// A program that names this module passes the roots it is built over: `--module
// modules/zip --module modules/gzip --module modules/unsafe --module modules/io`.

// The archive's whole content as bytes. Declared here rather than reached through the
// `io` module's `writeFile`-style surface: the `fileio` section also holds the reader
// (`simse_native_readFile`), but the compiler's own `common` module already declares a
// `readFile` for that symbol, and a second same-named declaration is shadowing trouble
// for the compiler's call sites (they import both packages). A `zip` name cannot collide.
@SmGen("res", "fileio", "simse_native_readFile")
fun zipReadFile(path: Str): Str

// Extracts every entry of the archive at `archivePath` under `outputDir` (created with its
// parents when missing) and answers the number of files written; directory entries only
// create their directory. `err` names what was wrong: a file that cannot be read, a
// missing end-of-central-directory record, a truncated or malformed directory, an
// encrypted entry, a Zip64 one, an entry name that would leave `outputDir`, an unsupported
// compression method, a data error in an entry, or a file that cannot be written.
fun unzip(archivePath: Str, outputDir: Str): Res<Int> {
    val content: Str = zipReadFile(archivePath)
    if (content.size() == 0) {
        return Res<Int>.err("zip: the archive is empty or cannot be read")
    }
    val bytes: List<Int> = zipBytes(content)
    val eocd: Int = zipFindEocd(bytes)
    if (eocd < 0) {
        return Res<Int>.err("zip: no end-of-central-directory record (not a zip archive?)")
    }
    val total: Int = zipUInt16(bytes, eocd + 10)
    if (total == 65535) {
        return Res<Int>.err("zip: Zip64 archives are not supported")
    }
    val directoryAt: Int = zipUInt32(bytes, eocd + 16)
    val directorySize: Int = zipUInt32(bytes, eocd + 12)
    if (directoryAt < 0 || directorySize < 0 || directoryAt + directorySize > bytes.size()) {
        return Res<Int>.err("zip: the central directory is outside the archive")
    }
    if (!createDirectories(outputDir)) {
        return Res<Int>.err("zip: cannot create the output folder " + outputDir)
    }

    var written: Int = 0
    var at: Int = directoryAt
    var entry: Int = 0
    while (entry < total) {
        if (at < 0 || at + 46 > bytes.size()) {
            return Res<Int>.err("zip: the central directory is truncated")
        }
        if (!zipSignature(bytes, at, 80, 75, 1, 2)) {
            return Res<Int>.err("zip: a malformed central directory record")
        }
        val flags: Int = zipUInt16(bytes, at + 8)
        val method: Int = zipUInt16(bytes, at + 10)
        val crc: Int = zipUInt32(bytes, at + 16)
        val compressedSize: Int = zipUInt32(bytes, at + 20)
        val size: Int = zipUInt32(bytes, at + 24)
        val nameLen: Int = zipUInt16(bytes, at + 28)
        val extraLen: Int = zipUInt16(bytes, at + 30)
        val commentLen: Int = zipUInt16(bytes, at + 32)
        val localAt: Int = zipUInt32(bytes, at + 42)
        if (at + 46 + nameLen > bytes.size()) {
            return Res<Int>.err("zip: the central directory is truncated")
        }
        val name: Str = zipNormalizedName(zipText(bytes, at + 46, nameLen))
        at = at + 46 + nameLen + extraLen + commentLen
        entry = entry + 1

        if (flags & 1 != 0) {
            return Res<Int>.err("zip: the entry " + name + " is encrypted")
        }
        if (compressedSize == -1 || size == -1 || localAt == -1) {
            return Res<Int>.err("zip: the entry " + name + " is a Zip64 one, which is not supported")
        }
        if (!zipNameIsSafe(name)) {
            return Res<Int>.err("zip: the entry name is not safe to write: " + name)
        }
        if (zipNameIsDirectory(name)) {
            if (!createDirectories(outputDir + "/" + name)) {
                return Res<Int>.err("zip: cannot create the folder " + outputDir + "/" + name)
            }
            continue
        }
        if (method != 0 && method != 8) {
            return Res<Int>.err("zip: the entry " + name + " uses compression method "
                + method.toString() + "; only stored (0) and deflate (8) are read")
        }

        // The data starts after the local header (its own name and extra lengths, which a
        // valid writer keeps equal to the directory record's).
        if (localAt < 0 || localAt + 30 > bytes.size()) {
            return Res<Int>.err("zip: the local header of " + name + " is outside the archive")
        }
        if (!zipSignature(bytes, localAt, 80, 75, 3, 4)) {
            return Res<Int>.err("zip: the local header of " + name + " is malformed")
        }
        val dataAt: Int = localAt + 30 + zipUInt16(bytes, localAt + 26) + zipUInt16(bytes, localAt + 28)
        if (dataAt + compressedSize > bytes.size()) {
            return Res<Int>.err("zip: the data of " + name + " is truncated")
        }

        var plain: List<Int> = List<Int>()
        if (method == 0) {
            var i: Int = 0
            while (i < compressedSize) {
                plain.append(bytes[dataAt + i])
                i = i + 1
            }
        } else {
            var reader: GzBitReader = GzBitReader(bytes, dataAt * 8, false)
            val inflated: Res<List<Int>> = gzInflate(reader)
            if (!inflated.isOk()) {
                return Res<Int>.err("zip: the entry " + name + ": " + inflated.error)
            }
            plain = inflated.value
        }
        if (plain.size() != size) {
            return Res<Int>.err("zip: the entry " + name + " unpacks to " + plain.size().toString()
                + " bytes, not the recorded " + size.toString())
        }
        if (gzCrc32(plain) != UInt32(crc)) {
            return Res<Int>.err("zip: the CRC32 of " + name + " does not match the record")
        }

        val path: Str = outputDir + "/" + name
        if (!createDirectories(zipParent(outputDir, name))) {
            return Res<Int>.err("zip: cannot create the folder for " + path)
        }
        if (!writeFile(path, zipDataStr(plain))) {
            return Res<Int>.err("zip: cannot write " + path)
        }
        written = written + 1
    }
    return Res<Int>.ok(written)
}

// The archive's bytes, read as the 0..255 the format's fields are.
fun zipBytes(content: Str): List<Int> {
    var bytes: List<Int> = List<Int>()
    var i: Int = 0
    while (i < content.size()) {
        val wide: Int = content.charAt(i)
        bytes.append(wide & 255)
        i = i + 1
    }
    return bytes
}

// The offset of the end-of-central-directory record: the last signature in the file,
// found by scanning back over its comment (which may be up to 65535 bytes).
fun zipFindEocd(bytes: List<Int>): Int {
    if (bytes.size() < 22) {
        return -1
    }
    var at: Int = bytes.size() - 22
    var floor: Int = bytes.size() - 22 - 65535
    if (floor < 0) {
        floor = 0
    }
    while (at >= floor) {
        if (zipSignature(bytes, at, 80, 75, 5, 6)) {
            return at
        }
        at = at - 1
    }
    return -1
}

fun zipSignature(bytes: List<Int>, at: Int, b0: Int, b1: Int, b2: Int, b3: Int): Bool {
    return bytes[at] == b0 && bytes[at + 1] == b1 && bytes[at + 2] == b2 && bytes[at + 3] == b3
}

fun zipUInt16(bytes: List<Int>, at: Int): Int {
    return bytes[at] | (bytes[at + 1] << 8)
}

// The little-endian 32 bits at `at`; the top bit reads as a negative `Int`, which the
// callers treat as the Zip64 sentinel (`-1`).
fun zipUInt32(bytes: List<Int>, at: Int): Int {
    val value: UInt32 = UInt32(bytes[at]).orWith(UInt32(bytes[at + 1]).shl(8)).orWith(
        UInt32(bytes[at + 2]).shl(16)).orWith(UInt32(bytes[at + 3]).shl(24))
    return value.toInt()
}

fun zipText(bytes: List<Int>, at: Int, count: Int): Str {
    var text: Str = Str()
    var i: Int = 0
    while (i < count) {
        text.append(bytes[at + i])
        i = i + 1
    }
    return text
}

fun zipDataStr(plain: List<Int>): Str {
    var text: Str = Str()
    var i: Int = 0
    while (i < plain.size()) {
        text.append(plain[i])
        i = i + 1
    }
    return text
}

// A name's separators as `/`: some Windows writers (PowerShell's `Compress-Archive`)
// spell the path with backslashes, and `a\b` must become the folder `b` under `a`, not a
// file with a stray character in its name.
fun zipNormalizedName(name: Str): Str {
    var out: Str = Str()
    var i: Int = 0
    while (i < name.size()) {
        if (name.charAt(i) == '\\') {
            out.append('/')
        } else {
            out.append(name.charAt(i))
        }
        i = i + 1
    }
    return out
}

// Whether `name` may be written under the output folder: not empty, no `..` segment, no
// absolute or drive-relative start, and no `:` (a name that could mean another location on
// some platform).
fun zipNameIsSafe(name: Str): Bool {
    if (name.size() == 0) {
        return false
    }
    var i: Int = 0
    while (i < name.size()) {
        val ch: Char = name.charAt(i)
        if (ch == ':') {
            return false
        }
        i = i + 1
    }
    if (name.charAt(0) == '/') {
        return false
    }
    var start: Int = 0
    while (start < name.size()) {
        var end: Int = start
        while (end < name.size() && name.charAt(end) != '/') {
            end = end + 1
        }
        if (end - start == 2 && name.charAt(start) == '.' && name.charAt(start + 1) == '.') {
            return false
        }
        start = end + 1
    }
    return true
}

fun zipNameIsDirectory(name: Str): Bool {
    return name.charAt(name.size() - 1) == '/'
}

// The folder an entry's parents live in: `dir` itself for a top-level name, or `dir` plus
// the name's own leading path.
fun zipParent(dir: Str, name: Str): Str {
    var slash: Int = -1
    var i: Int = 0
    while (i < name.size()) {
        if (name.charAt(i) == '/') {
            slash = i
        }
        i = i + 1
    }
    if (slash <= 0) {
        return dir
    }
    return dir + "/" + name.substr(0, slash)
}
