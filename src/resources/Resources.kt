// Resources.kt
//
// The `_res.md` reader (specs/resources.md): text a program carries that is not code,
// embedded in its string table. A file is a flat list of (key, value) pairs - a title line
// underlined by `=` sets a key *prefix*, a `key: value` line is an entry - and a title
// opening with `!` marks its section compile-only, so code in a `.md` section is not also
// stored as text.

package resources

import common
import io

// One resource as the *compiler* read it: the key, the text, and the two markers.
//
// `compileOnly` (`!`): the compiler reads the entry, the program does not carry it.
// `binary` (`*`): the value was written as hex for the bytes and is already decoded here.
// Either marker may sit on a section title or an entry key, in either order, and is not part
// of the name.
//
// Named `ResourceItem`, not `ResourceEntry`: the latter is the RTL's `StrView` pair, and the
// emitter's type table is flat by name.
data class ResourceItem(
    var key: Str,

    var value: Str,

    var compileOnly: Bool,

    var binary: Bool
)

// One name with its markers read: the name itself and what the markers said
// (specs/resources.md, "Markers").
data class ResMarked(
    var name: Str,

    var compileOnly: Bool,

    var binary: Bool
)

// Reads a title's or a key's leading marker run (`!` compile-only, `*` binary, together in
// either order), consuming it - the name left is the name the file means. A marker cannot be
// unset: an entry's markers only add to its section's.
fun resMarkedName(raw: *Str): ResMarked {
    var compileOnly: Bool = false
    var binary: Bool = false
    var i: Int = 0
    while (i < raw.size()) {
        if (raw[i] == '!') {
            compileOnly = true
        } else if (raw[i] == '*') {
            binary = true
        } else {
            break
        }
        i++
    }
    return ResMarked(raw.substr(i, raw.size() - i).trim(), compileOnly, binary)
}

// The format's two byte-level helpers, written in the language (they were the `resfmt`
// section of the RTL's C++, `src/rtl/_res.md`, and are the compiler's alone): a hex dump to
// bytes, and bytes back out as a C++ literal.

// The bytes a `*`-marked value's hex stands for: every pair of hex digits is one byte, in
// order, so the value is a byte string like any other - a `Str` with a length, which may hold
// a `\0` in the middle.
//
// The hex is **lower case** and whitespace (spaces, tabs, newlines) is not part of it, so a
// dump may be wrapped however the file likes; decoding stops at the first character that is
// neither, and a last digit left unpaired is dropped. Stopping rather than skipping is
// deliberate: a value that is not lower-case hex decodes to nothing rather than to half its
// bytes, which is the loudest failure this format can give - it validates nothing anywhere, so
// the program's own output is where a bad dump shows up.
fun resHexToBytes(hex: Str): Str {
    var out: Str = ""
    out.reserve(hex.size() / 2)
    var high: Int = 0 - 1
    var i: Int = 0
    while (i < hex.size()) {
        val ch: Char = hex[i]
        if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') {
            i++
            continue
        }
        var digit: Int = 0 - 1
        if (ch >= '0' && ch <= '9') {
            digit = ch - '0'
        } else if (ch >= 'a' && ch <= 'f') {
            digit = ch - 'a' + 10
        }
        if (digit < 0) {
            break
        }
        if (high < 0) {
            high = digit
        } else {
            val byte: Char = high * 16 + digit
            out.append(byte)
            high = 0 - 1
        }
        i++
    }
    return out
}

// Bytes as the C++ literal the pool holds them as: printable bytes stand for themselves, and
// every other byte is an octal escape with all three digits written, so that a following
// character which happens to be an octal digit cannot run the escape on (`\x` would: it takes
// every hex digit that follows it). `cgLiteralByteLength` counts an octal escape as the one
// byte it is, which is what keeps the pool and its length index in step - and it is what makes
// a `\0` in the middle of a value an ordinary byte of the pool.
fun resQuoteBinary(bytes: Str): Str {
    var out: Str = "\""
    out.reserve(bytes.size() + 2)
    for (ch in bytes) {
        // The byte as the *unsigned* value the C++ spelled (`(unsigned char) ch`): a `Char`
        // reads back signed, so 0xFF is -56 until it is put back in range.
        var byte: Int = ch
        if (byte < 0) {
            byte = byte + 256
        }
        if (ch == '\\') {
            out.appendStr("\\\\")
        } else if (ch == '\"') {
            out.appendStr("\\\"")
        } else if (byte >= 32 && byte <= 126) {
            out.append(ch)
        } else {
            // The three octal digits, most significant first: `/64`, `/8 %8`, `%8` - the
            // shifts the C++ wrote, in the arithmetic the language has.
            val high: Char = '0' + byte / 64
            val mid: Char = '0' + byte / 8 % 8
            val low: Char = '0' + byte % 8
            out.append('\\')
            out.append(high)
            out.append(mid)
            out.append(low)
        }
    }
    out.append('"')
    return out
}

// True when `line` is a section underline: non-blank once trimmed, and all `=`.
fun resIsUnderline(line: *Str): Bool {
    val text: Str = line.trim()
    if (text.size() == 0) {
        return false
    }
    for (ch in text) {
        if (ch != '=') {
            return false
        }
    }
    return true
}

// True when `line` opens a fenced block (a prefix test, so a fence naming a language
// matches); the closing fence is exact.
fun resIsFenceStart(line: *Str): Bool {
    return line.trim().startsWith("```")
}

// True when `line` closes a fenced block: the bare fence.
fun resIsFenceEnd(line: *Str): Bool {
    return line.trim() == "```"
}

// The key a section prefixes: `Title` + `Key` is `Title:Key`; a key before the first title
// keeps its own name.
fun resQualifiedKey(section: *Str, key: Str): Str {
    if (section.size() == 0) {
        return key
    }
    return `@section:@key`
}

// `text` without one wrapping pair of backticks, so `` Key: `text` `` and `Key: text` hold
// the same value. Only one pair, and only when it wraps the whole text.
fun resUnquote(text: Str): Str {
    if (text.size() >= 2 && text[0] == '`' && text[text.size() - 1] == '`') {
        return text.substr(1, text.size() - 2)
    }
    return text
}

// One resource file's entries, in written order; blank lines, prose, and anything before the
// first title are ignored. An entry is a line whose first `:` has a non-empty key before it;
// when nothing follows the colon the value is the fenced block that follows (lines as
// written, each with a newline), and when no fence follows the value is empty.
fun resParseText(text: *Str): List<ResourceItem> {
    val lines: List<Str> = text.split("\n")
    var entries: List<ResourceItem> = List<ResourceItem>()
    var section: Str = ""
    var compileOnly: Bool = false
    var binary: Bool = false
    var i: Int = 0
    while (i < lines.size()) {
        // A title is a line *underlined* by the next one; both lines are spent, so the
        // underline is never read as an entry. A title whose name is empty after its markers
        // is not a title, and leaves the section and both markers as they were.
        if (i + 1 < lines.size() && resIsUnderline(lines[i + 1])) {
            val marked: ResMarked = resMarkedName(lines[i].trim())
            if (marked.name.size() > 0) {
                section = marked.name
                compileOnly = marked.compileOnly
                binary = marked.binary
            }
            i = i + 2
            continue
        }
        val line: Str = lines[i].trim()
        val colon: Int = line.indexOf(":")
        if (colon <= 0) {
            i++
            continue
        }
        // The key's own markers add to the section's: either marks the entry, combined with `||`.
        val markedKey: ResMarked = resMarkedName(line.substr(0, colon).trim())
        val key: Str = resQualifiedKey(section, markedKey.name)
        val entryCompileOnly: Bool = compileOnly || markedKey.compileOnly
        val entryBinary: Bool = binary || markedKey.binary
        val rest: Str = line.substr(colon + 1, line.size() - colon - 1).trim()
        if (rest.size() > 0) {
            entries.append(
                ResourceItem(key, resValueText(resUnquote(rest), entryBinary), entryCompileOnly, entryBinary)
            )
            i++
            continue
        }

        // Nothing after the colon: the value is the fenced block that follows, blank lines
        // before it skipped. A line that is not a fence leaves the value empty (the scan
        // resumes there).
        var value: Str = ""
        var j: Int = i + 1
        while (j < lines.size() && lines[j].trim().size() == 0) {
            j++
        }
        if (j < lines.size() && resIsFenceStart(lines[j])) {
            j++
            while (j < lines.size()) {
                if (resIsFenceEnd(lines[j])) {
                    j++
                    break
                }
                var body: Str = lines[j]
                // A `\r` before the newline is not part of the line, so a value does not
                // depend on the file's line endings.
                if (body.size() > 0 && body[body.size() - 1] == '\r') {
                    body = body.substr(0, body.size() - 1)
                }
                value.appendStr(body)
                value.append('\n')
                j++
            }
            i = j
        } else {
            i++
        }
        entries.append(ResourceItem(key, resValueText(value, entryBinary), entryCompileOnly, entryBinary))
    }
    return entries
}

// A value as the entry holds it: a `*`-marked one is hex for the bytes, decoded here once on
// the way in, so everything downstream reads bytes.
fun resValueText(value: Str, binary: Bool): Str {
    if (!binary) {
        return value
    }
    return resHexToBytes(value)
}

// The flat list a compilation keeps, from every file's entries in order: a repeated key takes
// the last value in the position it was *first* written. A section two files fill in merges,
// since the key already carries the prefix (specs/resources.md, "Discovery").
fun resDedup(entries: *List<ResourceItem>): List<ResourceItem> {
    var last: Dictionary<Str, ResourceItem> = Dictionary<Str, ResourceItem>()
    for (entry in entries) {
        last.insert(entry.key, entry)
    }
    var out: List<ResourceItem> = List<ResourceItem>()
    var seen: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    for (entry in entries) {
        val key: Str = entry.key
        if (!seen.has(key)) {
            seen.insert(key, true)
            // The winner's own text *and* marker: a key written in a marked section and again
            // in an unmarked one is stored or not by whichever entry won.
            val won: *ResourceItem = last.getPtr(key)
            out.append(ResourceItem(key, won.value, won.compileOnly, won.binary))
        }
    }
    return out
}

// Every `_res.md` under each module root, recursively, each canonical path once, in
// canonical-path order (the rule `driverGatherFiles` applies to the `*.kt` files).
fun resResourceFiles(moduleRoots: *List<Str>): List<Str> {
    var candidates: List<Str> = List<Str>()
    for (root in moduleRoots) {
        val found: List<Str> = listFiles(root, ".md")
        for (file in found) {
            if (file.endsWith("_res.md")) {
                candidates.append(file)
            }
        }
    }
    var chosen: List<Str> = List<Str>()
    var seen: List<Str> = List<Str>()
    for (candidate in candidates) {
        val canon: Str = pathCanonical(candidate)
        if (!seen.contains(canon)) {
            seen.append(canon)
            chosen.append(candidate)
        }
    }
    // After dedup the canonical keys are unique, so this sort is total.
    chosen.sort((left: *Str, right: *Str) -> pathCanonical(left) < pathCanonical(right))
    return chosen
}

// Reads and parses every resource file in order and joins them into the one flat list.
fun resLoadFiles(files: *List<Str>): List<ResourceItem> {
    var all: List<ResourceItem> = List<ResourceItem>()
    for (file in files) {
        val parsed: List<ResourceItem> = resParseText(readFile(file))
        for (item in parsed) {
            all.append(item)
        }
    }
    return resDedup(all)
}

// The whole discovery in one call: every `_res.md` under `moduleRoots`, parsed and joined.
fun resLoad(moduleRoots: *List<Str>): List<ResourceItem> {
    return resLoadFiles(resResourceFiles(moduleRoots))
}

// The same entries, each key and value spelled as the C++ string literal the emitter pools
// (and its length index counts). A `*`-marked value is bytes, not text, so it needs the
// different escape rule `resQuoteBinary` uses.
fun resStoredLiterals(entries: *List<ResourceItem>): List<Str> {
    var out: List<Str> = List<Str>()
    for (entry in entries) {
        if (!entry.compileOnly) {
            out.append(resQuoteLiteral(entry.key))
            if (entry.binary) {
                out.append(resQuoteBinary(entry.value))
            } else {
                out.append(resQuoteLiteral(entry.value))
            }
        }
    }
    return out
}

// The text `entries` holds for `key`, or "" when absent. The compiler's own lookup, for the
// places that run before the program can call `Resources.get`; keys are already unique.
fun resValueOf(entries: *List<ResourceItem>, key: *Str): Str {
    for (entry in entries) {
        if (entry.key == key) {
            return entry.value
        }
    }
    return ""
}

// True when `entries` carries `key`. `resValueOf` cannot answer it: an *empty* value and an
// absent key both read as "", so a caller that must tell them apart asks this first
// (src/compiler/ResGen.kt).
fun resHas(entries: *List<ResourceItem>, key: *Str): Bool {
    for (entry in entries) {
        if (entry.key == key) {
            return true
        }
    }
    return false
}

// `text` as the C++ narrow string literal the emitter pools it as. The escapes must be
// exactly the ones `cgLiteralByteLength` counts as one byte each, since the pool and the
// program's view of the text are built from this same spelling.
fun resQuoteLiteral(text: *Str): Str {
    var out: Str = "\""
    for (ch in text) {
        if (ch == '\\') {
            out.appendStr("\\\\")
        } else if (ch == '\"') {
            out.appendStr("\\\"")
        } else if (ch == '\n') {
            out.appendStr("\\n")
        } else if (ch == '\r') {
            out.appendStr("\\r")
        } else if (ch == '\t') {
            out.appendStr("\\t")
        } else {
            out.append(ch)
        }
    }
    out.append('\"')
    return out
}
