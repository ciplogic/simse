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
// reduces to a flat list of (key, value) pairs and nothing downstream needs a tree.
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

// One resource: the key the program looks it up by, and the text it holds. Both hold the
// file's own bytes - the pool and the C++ escapes are the emitter's business.
data class ResourceEntry(
    var key: Str,

    var value: Str
)

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
fun resParseText(text: Str): List<ResourceEntry> {
    val lines: List<Str> = text.split("\n")
    var entries: List<ResourceEntry> = List<ResourceEntry>()
    var section: Str = ""
    var i: Int = 0
    while (i < lines.size()) {
        // A title is a line *underlined* by the line below it; both lines are spent, so an
        // underline is never read as an entry of its own.
        if (i + 1 < lines.size() && resIsUnderline(lines[i + 1])) {
            val title: Str = lines[i].trim()
            if (title.size() > 0) {
                section = title
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
        val key: Str = resQualifiedKey(section, line.substr(0, colon).trim())
        val rest: Str = line.substr(colon + 1, line.size() - colon - 1).trim()
        if (rest.size() > 0) {
            entries.append(ResourceEntry(key, resUnquote(rest)))
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
        entries.append(ResourceEntry(key, value))
    }
    return entries
}

// The flat list a compilation keeps, from the entries of every file in order: a key
// written more than once takes the last value, in the position it was *first* written. A
// section two files both fill in merges because the key already carries the prefix, and
// the order is the file order (`specs/resources.md`, "Discovery").
fun resDedup(entries: List<ResourceEntry>): List<ResourceEntry> {
    var last: Dictionary<Str, Str> = Dictionary<Str, Str>()
    var i: Int = 0
    while (i < entries.size()) {
        last.insert(entries[i].key, entries[i].value)
        i = i + 1
    }
    var out: List<ResourceEntry> = List<ResourceEntry>()
    var seen: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    i = 0
    while (i < entries.size()) {
        val key: Str = entries[i].key
        if (!seen.has(key)) {
            seen.insert(key, true)
            out.append(ResourceEntry(key, last.get(key).value()))
        }
        i = i + 1
    }
    return out
}

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
fun resLoadFiles(files: List<Str>): List<ResourceEntry> {
    var all: List<ResourceEntry> = List<ResourceEntry>()
    var f: Int = 0
    while (f < files.size()) {
        val parsed: List<ResourceEntry> = resParseText(readFile(files[f]))
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
fun resLoad(moduleRoots: List<Str>): List<ResourceEntry> {
    return resLoadFiles(resResourceFiles(moduleRoots))
}

// The entries as the emitter's own shape: every key and value as the C++ literal its
// bytes are written into the program's pool as (`resQuoteLiteral`), key then value, in
// the order the files were read. This is what a driver hands codegen
// (`Codegen.emitProgram`), because a pool is a pool of literal texts and nothing
// downstream needs the pairs as pairs: entry `i` is the literals `2*i` and `2*i + 1`.
fun resLoadLiterals(moduleRoots: List<Str>): List<Str> {
    val entries: List<ResourceEntry> = resLoad(moduleRoots)
    var out: List<Str> = List<Str>()
    var i: Int = 0
    while (i < entries.size()) {
        out.append(resQuoteLiteral(entries[i].key))
        out.append(resQuoteLiteral(entries[i].value))
        i = i + 1
    }
    return out
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
