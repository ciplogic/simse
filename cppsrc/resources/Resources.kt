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
        i = i + 1
    }
    return ResMarked(raw.substr(i, raw.size() - i).trim(), compileOnly, binary)
}

// The format's two byte-level helpers, generated from the `resfmt` resource section
// (impl_specs/generators.md): hex dump to bytes, and bytes back out as a C++ literal.
@SmGen("res", "resfmt", "simse_resHexToBytes")
fun resHexToBytes(hex: Str): Str

@SmGen("res", "resfmt", "simse_resQuoteBinary")
fun resQuoteBinary(bytes: Str): Str

// True when `line` is a section underline: non-blank once trimmed, and all `=`.
fun resIsUnderline(line: *Str): Bool {
    val text: Str = line.trim()
    if (text.size() == 0) {
        return false
    }
    var i: Int = 0
    while (i < text.size()) {
        if (text[i] != '=') {
            return false
        }
        i = i + 1
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
    return fmtStr("|:|", section, key)
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
            i = i + 1
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
            i = i + 1
            continue
        }

        // Nothing after the colon: the value is the fenced block that follows, blank lines
        // before it skipped. A line that is not a fence leaves the value empty (the scan
        // resumes there).
        var value: Str = ""
        var j: Int = i + 1
        while (j < lines.size() && lines[j].trim().size() == 0) {
            j = j + 1
        }
        if (j < lines.size() && resIsFenceStart(lines[j])) {
            j = j + 1
            while (j < lines.size()) {
                if (resIsFenceEnd(lines[j])) {
                    j = j + 1
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
                j = j + 1
            }
            i = j
        } else {
            i = i + 1
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
    var i: Int = 0
    while (i < entries.size()) {
        last.insert(entries[i].key, entries[i])
        i = i + 1
    }
    var out: List<ResourceItem> = List<ResourceItem>()
    var seen: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    i = 0
    while (i < entries.size()) {
        val key: Str = entries[i].key
        if (!seen.has(key)) {
            seen.insert(key, true)
            // The winner's own text *and* marker: a key written in a marked section and again
            // in an unmarked one is stored or not by whichever entry won.
            val won: *ResourceItem = last.getPtr(key)
            out.append(ResourceItem(key, won.value, won.compileOnly, won.binary))
        }
        i = i + 1
    }
    return out
}

// Every `_res.md` under each module root, recursively, each canonical path once, in
// canonical-path order (the rule `driverGatherFiles` applies to the `*.kt` files).
fun resResourceFiles(moduleRoots: *List<Str>): List<Str> {
    var candidates: List<Str> = List<Str>()
    var r: Int = 0
    while (r < moduleRoots.size()) {
        val found: List<Str> = listFiles(moduleRoots[r], ".md")
        var f: Int = 0
        while (f < found.size()) {
            if (found[f].endsWith("_res.md")) {
                candidates.append(found[f])
            }
            f = f + 1
        }
        r = r + 1
    }
    var chosen: List<Str> = List<Str>()
    var seen: List<Str> = List<Str>()
    var c: Int = 0
    while (c < candidates.size()) {
        val canon: Str = pathCanonical(candidates[c])
        if (!seen.contains(canon)) {
            seen.append(canon)
            chosen.append(candidates[c])
        }
        c = c + 1
    }
    // After dedup the canonical keys are unique, so this sort is total.
    chosen.sort((left: Str, right: Str) -> pathCanonical(left) < pathCanonical(right))
    return chosen
}

// Reads and parses every resource file in order and joins them into the one flat list.
fun resLoadFiles(files: *List<Str>): List<ResourceItem> {
    var all: List<ResourceItem> = List<ResourceItem>()
    var f: Int = 0
    while (f < files.size()) {
        val parsed: List<ResourceItem> = resParseText(readFile(files[f]))
        var p: Int = 0
        while (p < parsed.size()) {
            all.append(parsed[p])
            p = p + 1
        }
        f = f + 1
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
    var i: Int = 0
    while (i < entries.size()) {
        if (!entries[i].compileOnly) {
            out.append(resQuoteLiteral(entries[i].key))
            if (entries[i].binary) {
                out.append(resQuoteBinary(entries[i].value))
            } else {
                out.append(resQuoteLiteral(entries[i].value))
            }
        }
        i = i + 1
    }
    return out
}

// The text `entries` holds for `key`, or "" when absent. The compiler's own lookup, for the
// places that run before the program can call `Resources.get`; keys are already unique.
fun resValueOf(entries: *List<ResourceItem>, key: *Str): Str {
    var i: Int = 0
    while (i < entries.size()) {
        if (entries[i].key == key) {
            return entries[i].value
        }
        i = i + 1
    }
    return ""
}

// True when `entries` carries `key`. `resValueOf` cannot answer it: an *empty* value and an
// absent key both read as "", so a caller that must tell them apart asks this first
// (cppsrc/sourcegen/ResGen.kt).
fun resHas(entries: *List<ResourceItem>, key: *Str): Bool {
    var i: Int = 0
    while (i < entries.size()) {
        if (entries[i].key == key) {
            return true
        }
        i = i + 1
    }
    return false
}

// `text` as the C++ narrow string literal the emitter pools it as. The escapes must be
// exactly the ones `cgLiteralByteLength` counts as one byte each, since the pool and the
// program's view of the text are built from this same spelling.
fun resQuoteLiteral(text: *Str): Str {
    var out: Str = "\""
    var i: Int = 0
    while (i < text.size()) {
        val ch: Char = text[i]
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
        i = i + 1
    }
    out.append('\"')
    return out
}
