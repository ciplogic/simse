// Resources.kt
//
// The resource files (`_res.md`, specs/resources.md): text a program carries that is not
// code - a template, a prompt, help text, the profiler's own generated C++ - read by the
// compiler and embedded in the program's string table. The mirror of
// cppsrc/resources/Resources.cpp.
//
// A resource file is Markdown-shaped because a person edits it and its diffs should read
// like the text they hold: a *section title* is a line underlined by a line of `=` only,
// and a line that reads as `key: value` is an entry. A section is not nesting - it is a
// *prefix* on the key (`Profiling` + `Name` gives `Profiling:Name`) - so a whole file
// reduces to a flat list of (key, value) pairs and nothing downstream needs a tree. A title
// that opens with `!` marks its whole section **compile-only**: the compiler reads it and
// the program does not carry it, which is what keeps *code* in a `.md` file - a `kt`
// section's Simse source, a `res` section's C++ - from being stored in the executable as
// text on top of being compiled in.
//
// The parse is deliberately small: keys are trimmed (a stray space or tab in a key would
// be a bug that only shows up at runtime), no escape is interpreted (what the file says is
// what the program gets), and the one place that escapes anything is the C++ literal the
// emitter writes a value into (`resQuoteLiteral`).
//
// Keys and values are pooled in the program's string table like any other literal
// (`Codegen.kt`), so at runtime a resource is a `StrView` over the pool and there is no
// second copy of its text anywhere.

package resources

import common

// One resource, as the *compiler* read it from a file: the key the program will look it
// up by, the text it holds, and the two flags a marker can set.
//
// `compileOnly` is the `!` marker (specs/resources.md): the compiler reads the entry, the
// program does not carry it. It is what keeps *code* in a `.md` file from being stored in
// the executable as text as well as compiled in.
//
// `binary` is the `*` marker: the value as written is *hex that stands for the bytes*, so
// by the time an entry reaches here it is already decoded - `value` holds the bytes
// themselves, and nothing downstream has to know that the file spelled them as digits.
//
// Both markers may be written on a section's title or on one entry's key, together and in
// either order; the marker characters are not part of the name, so a lookup by spelling is
// the same whether anything was marked.
//
// The text holds the file's own bytes (the pool and the C++ escapes are the emitter's
// business), and it is `Str` - the RTL's byte string - so a binary resource is an ordinary
// value here: bytes that happen not to be text.
//
// The name is deliberately not `ResourceEntry`: that is the RTL's type, the pair of
// `StrView`s the program carries (cppsrc/rtl/resources.hpp + resources.kt), and the
// emitter's type table is flat by name, so one of the two has to be spelled differently.
// This one is the reader's item; that one is the program's entry.
data class ResourceItem(
    var key: Str,

    var value: Str,

    var compileOnly: Bool,

    var binary: Bool
)

// One name with its markers read: the name itself, and what the markers said.
// `specs/resources.md`, "Markers".
data class ResMarked(
    var name: Str,

    var compileOnly: Bool,

    var binary: Bool
)

// Reads a section title's or an entry key's leading marker run: `!` makes what it marks
// compile-only, `*` makes its value binary, they may appear together in either order, and
// the run is consumed - the name that is left is the name the file means.
//
// There is no way to *unset* a marker: a marked section marks every entry under it, and an
// entry's own markers are on top of the section's (`resParseText` combines the two with
// `||`, which is the whole of the rule).
fun resMarkedName(raw: Str): ResMarked {
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

// The two byte-level helpers the format needs, whose C++ is the `resfmt` section of
// `cppsrc/rtl/_res.md` (impl_specs/generators.md): turning a hex dump into the bytes it
// stands for, and spelling bytes back out as the C++ literal the pool holds them as.
//
// They are here rather than in the prelude on purpose: this is the *format*'s machinery, and a
// program has no use for either. Their section is `!`-marked and of their own (not part of
// `strops`), so no program reaches them and none of this reaches a program's output.
@SmGen("res", "resfmt", "simse_resHexToBytes")
fun resHexToBytes(hex: Str): Str

@SmGen("res", "resfmt", "simse_resQuoteBinary")
fun resQuoteBinary(bytes: Str): Str

// ---- the format -----------------------------------------------------------

// True when `line` is a section underline: non-blank once trimmed, and nothing but `=`.
fun resIsUnderline(line: Str): Bool {
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

// True when `line` opens a fenced block. An opening fence may name a language
// (` ```cpp `), which is why this is a prefix test while the closing one is exact.
fun resIsFenceStart(line: Str): Bool {
    return line.trim().startsWith("```")
}

// True when `line` closes a fenced block: the bare fence.
fun resIsFenceEnd(line: Str): Bool {
    return line.trim() == "```"
}

// The key a section prefixes: `Title` + `Key` is `Title:Key`, and a key written before the
// first title keeps its own name.
fun resQualifiedKey(section: Str, key: Str): Str {
    if (section.size() == 0) {
        return key
    }
    return section + ":" + key
}

// `text` without one wrapping pair of single backticks, so `` Key: `text` `` and
// `Key: text` hold the same value. Only one pair, and only when it wraps the whole text.
fun resUnquote(text: Str): Str {
    if (text.size() >= 2 && text[0] == '`' && text[text.size() - 1] == '`') {
        return text.substr(1, text.size() - 2)
    }
    return text
}

// ---- the parse ------------------------------------------------------------

// One resource file's entries, in the order they are written. Everything else is ignored:
// blank lines, prose, and anything before the first title. A repeated key keeps its first
// position with its last value (`resDedup`).
//
// An entry is a line whose first `:` has a non-empty key before it. When nothing follows
// the colon the value is the fenced block that follows (its lines as written, each
// followed by a newline); when no fence follows, the value is empty.
fun resParseText(text: Str): List<ResourceItem> {
    val lines: List<Str> = text.split("\n")
    var entries: List<ResourceItem> = List<ResourceItem>()
    var section: Str = ""
    var compileOnly: Bool = false
    var binary: Bool = false
    var i: Int = 0
    while (i < lines.size()) {
        // A title is a line *underlined* by the line below it; both lines are spent, so an
        // underline is never read as an entry of its own. Its markers (`resMarkedName`) say
        // what every entry under it is; a title with nothing left after its markers is not a
        // title, and leaves the section and both markers as they were.
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
        // The key's own markers are on top of the section's - either one marks the entry, so
        // the two are combined with `||` and nothing can take a marker back.
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

        // Nothing after the colon: the value is the fenced block that follows. Blank lines
        // between the key and its fence are the file's own layout, so they are skipped -
        // and a line that is *not* a fence leaves the value empty rather than swallowing
        // it (the scan resumes there).
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
                // A `\r` before the newline is not part of the line - the same rule the
                // scanner applies - so a value does not depend on the file's line endings.
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

// A value as the entry holds it: a `*`-marked one is hex that stands for the bytes, so it is
// decoded here - once, on the way in - and everything downstream reads bytes (`resHexToBytes`,
// whose C++ is the `strops` resource section).
fun resValueText(value: Str, binary: Bool): Str {
    if (!binary) {
        return value
    }
    return resHexToBytes(value)
}

// The flat list a compilation keeps, from the entries of every file in order: a key
// written more than once takes the last value, in the position it was *first* written. A
// section two files both fill in merges because the key already carries the prefix, and
// the order is the file order (`specs/resources.md`, "Discovery").
fun resDedup(entries: List<ResourceItem>): List<ResourceItem> {
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
            // The winner's own text *and* marker: a key written in a marked section and
            // again in an unmarked one is stored or not by the entry that won it.
            val won: ResourceItem = last.get(key).value()
            out.append(ResourceItem(key, won.value, won.compileOnly, won.binary))
        }
        i = i + 1
    }
    return out
}

// The text `entries` holds for `key`, or "" when they do not carry it. The compiler's
// ---- discovery and loading ------------------------------------------------

// Every `_res.md` file under each module root, recursively, each canonical path once, in
// canonical-path order - the same rule `driverGatherFiles` applies to the `*.kt` files, so
// two rings that were handed the same roots agree on the file set and its order.
fun resResourceFiles(moduleRoots: List<Str>): List<Str> {
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
    // After dedup the canonical keys are unique, so this sort is total and both compiler
    // rings produce the same sequence.
    chosen.sort((left: Str, right: Str) -> pathCanonical(left) < pathCanonical(right))
    return chosen
}

// Reads and parses every resource file in order and joins them into the one flat list.
fun resLoadFiles(files: List<Str>): List<ResourceItem> {
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

// The whole discovery in one call, for a driver: every `_res.md` under `moduleRoots`,
// parsed and joined.
fun resLoad(moduleRoots: List<Str>): List<ResourceItem> {
    return resLoadFiles(resResourceFiles(moduleRoots))
}

// The same entries, in the same order, each key and value spelled as **the C++ string
// literal that holds its bytes** - which is what the emitter pools and what its length index
// is built from. The spelling happens here, once, rather than in the emitter: a `*`-marked
// value is bytes, not text, so it needs a different escape rule (`resQuoteBinary`, every
// non-printable byte written as an octal escape), and a spelling that is only ever written
// once cannot disagree with itself.
fun resStoredLiterals(entries: List<ResourceItem>): List<Str> {
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

// The text `entries` holds for `key`, or "" when they do not carry it. The compiler's
// own lookup, for the places that run before the program exists to call `Resources.get`
// (the driver's generated sources); `resDedup` already made the keys unique, so one
// scan is enough.
fun resValueOf(entries: List<ResourceItem>, key: Str): Str {
    var i: Int = 0
    while (i < entries.size()) {
        if (entries[i].key == key) {
            return entries[i].value
        }
        i = i + 1
    }
    return ""
}

// True when `entries` carries `key`. `resValueOf` cannot answer this: a key may hold an
// *empty* text - a section with nothing under it - and an absent key holds the same, so a
// reader that has to tell the two apart asks this first (the generator lookup does,
// `cppsrc/sourcegen/ResGen.kt`).
fun resHas(entries: List<ResourceItem>, key: Str): Bool {
    var i: Int = 0
    while (i < entries.size()) {
        if (entries[i].key == key) {
            return true
        }
        i = i + 1
    }
    return false
}

// ---- the C++ literal ------------------------------------------------------

// `text` as the C++ narrow string literal the emitter pools it as. Every resource's bytes
// are written from *here*, so the pool, its length index and the program's own view of the
// text agree: the escapes below are exactly the ones `cgLiteralByteLength` counts as one
// byte each, and nothing else in the text is touched (a control byte the language has no
// escape for is written as it is, which C++ accepts).
fun resQuoteLiteral(text: Str): Str {
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
