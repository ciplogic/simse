// Diagnostics.kt
//
// The compiler's diagnostic renderer. The positioned errors the scanner, the parser, the `!!`
// rewrite and the checker produce travel as one line, `<file>:<line>:<column>: <message>` (the
// scanner and the rewrite leave one space after the file's colon; both spellings parse), and
// the driver prints them through `diagRender`, which shows the source line under the message
// with a caret at the reported column:
//
//   src/main.kt:8:5: error: expected ')'
//   8 |     if (p.x > 0) {
//     |     ^
//
// A message may carry hint lines after a `\n` (`diagHint`); they are printed verbatim after
// the caret - a small sample, or what was found instead of what was expected. Sources are
// registered as they are read (`diagRememberSource`); a diagnostic whose file was never
// registered, or whose prefix does not parse (an `unsupported:` extractor error, say), is
// returned unchanged.

package errors

// Static storage (specs/statics.md): the registered sources as two parallel lists, so a
// lookup is a scan by name (one compilation reads a handful of files; a name is looked up
// once per diagnostic). The last registration of a name wins, matching how the driver reads
// a file it is about to parse.
var diagSourceFiles: List<Str> = List<Str>()
var diagSourceLines: List<List<Str>> = List<List<Str>>()

// The line `fileName` was registered with, or null when it was not: what the caret is
// placed into. Registered last-wins, so `getPtr`-style early exits are not needed.
fun diagFindLines(fileName: *Str): *List<Str> {
    var i: Int = diagSourceFiles.size() - 1
    while (i >= 0) {
        if (diagSourceFiles[i] == fileName) {
            return *diagSourceLines[i]
        }
        i--
    }
    return null
}

// The source text of one file, kept for the renderer. Called with the same name the
// diagnostics will carry (the path the driver was given, or a `<generated>/...` name).
fun diagRememberSource(fileName: *Str, text: *Str): Unit {
    diagSourceFiles.append(fileName)
    diagSourceLines.append(text.split("\n"))
}

// `message` with one hint line under it: what the renderer prints after the caret. Hints
// keep the message's own first line intact, so expectations that match the message (the
// stress corpus) are unaffected by the suggestion.
fun diagHint(message: *Str, hint: *Str): Str {
    return message + "\n" + "hint: " + hint
}

// A parsed `<file>:<line>:<column>: <message>` prefix: what says where the caret goes.
data class DiagSite(
    var file: Str,

    var line: Int,

    var column: Int,

    var message: Str
)

// The prefix of `diagnostic` when it is a positioned one: `<file>:<line>:<column>: <message>`
// (one optional space after the file's colon). The split colon is the first one whose
// followers match two integers and `: ` - not simply the first colon, so a Windows drive
// letter (`C:\src\main.kt:8:5: ...`) still splits at the real prefix. Anything else is not a
// position - `unsupported:` extractor errors, `suspend:` machine errors, the driver's own
// usage errors - and answers none.
fun diagParseSite(diagnostic: *Str): Opt<DiagSite> {
    val text: Str = diagnostic
    var colon: Int = text.indexOf(":")
    while (colon > 0) {
        var i: Int = colon + 1
        if (i < text.size() && text[i] == ' ') {
            i++
        }
        val lineDigits: Str = diagDigits(text, i)
        i += lineDigits.size()
        if (lineDigits.size() > 0 && i < text.size() && text[i] == ':') {
            i++
            val columnDigits: Str = diagDigits(text, i)
            i += columnDigits.size()
            if (columnDigits.size() > 0 && i + 1 < text.size() && text[i] == ':' && text[i + 1] == ' ') {
                val lineText: Opt<Int> = lineDigits.toInt()
                val columnText: Opt<Int> = columnDigits.toInt()
                if (lineText.hasValue() && columnText.hasValue()) {
                    return Opt<DiagSite>.some(
                        DiagSite(
                            text.substr(0, colon), lineText.value(), columnText.value(),
                            text.substr(i + 2, -1)
                        )
                    )
                }
            }
        }
        // The next colon after a candidate that did not match.
        var scan: Int = colon + 1
        colon = 0 - 1
        while (scan < text.size()) {
            if (text[scan] == ':') {
                colon = scan
                break
            }
            scan++
        }
    }
    return Opt<DiagSite>.none()
}

// The run of digits at `start`, possibly empty.
fun diagDigits(text: *Str, start: Int): Str {
    var digits: Str = ""
    var i: Int = start
    while (i < text.size() && text[i] >= '0' && text[i] <= '9') {
        digits.append(text[i])
        i++
    }
    return digits
}

// `text` with every tab widened to four spaces: the scanner counts a tab as one column, so
// widening both the shown line and the caret's padding by the same rule keeps the caret on
// the byte the position names, however the source is indented.
fun diagExpandTabs(text: *Str): Str {
    if (text.find("\t") < 0) {
        return text
    }
    var out: Str = ""
    out.reserve(text.size() * 4)
    for (ch in text) {
        if (ch == '\t') {
            out.appendStrPtr("    ")
        } else {
            out.append(ch)
        }
    }
    return out
}

// `text` without one trailing `\r` (a CRLF source split on `\n` keeps it) and without the
// trailing blanks the caret never reaches.
fun diagTrimLineEnd(text: Str): Str {
    var end: Int = text.size()
    while (end > 0) {
        val ch: Char = text[end - 1]
        if (ch == '\r' || ch == ' ' || ch == '\t') {
            end--
            continue
        }
        break
    }
    if (end == text.size()) {
        return text
    }
    return text.substr(0, end)
}

// `text` without one trailing `\r` only: the caret's padding keeps every blank the position
// was reached through, or the caret would slide left of the byte it names.
fun diagStripCr(text: Str): Str {
    if (text.size() > 0 && text[text.size() - 1] == '\r') {
        return text.substr(0, text.size() - 1)
    }
    return text
}

// The rendered diagnostic: the positioned message with `error:` spelled after the position,
// the source line under it, a caret at the column, and the message's hint lines after that.
// A diagnostic the renderer cannot position is returned as it is.
fun diagRender(diagnostic: *Str): Str {
    val site: Opt<DiagSite> = diagParseSite(diagnostic)
    if (!site.hasValue()) {
        return diagnostic
    }
    val parsed: DiagSite = site.value()
    val lines: *List<Str> = diagFindLines(parsed.file)
    if (lines == null) {
        return diagnostic
    }
    var lineText: Str = ""
    if (parsed.line >= 1 && parsed.line <= lines.size()) {
        lineText = lines[parsed.line - 1]
    }
    val shown: Str = diagExpandTabs(diagTrimLineEnd(lineText))

    // The caret's padding: the source line's own prefix up to the column, widened by the
    // same tab rule as the shown line and clamped to the line's end (a position the scanner
    // reported at the end of the file, say).
    var cut: Int = parsed.column - 1
    if (cut > lineText.size()) {
        cut = lineText.size()
    }
    if (cut < 0) {
        cut = 0
    }
    val caretPad: Str = diagExpandTabs(diagStripCr(lineText.substr(0, cut)))

    // The gutter holds the line number once; every other row lines up under it.
    val numberText: Str = parsed.line.toString()
    val columnText: Str = parsed.column.toString()
    var numberPad: Str = ""
    var n: Int = 0
    while (n < numberText.size()) {
        numberPad.append(' ')
        n++
    }

    val messageLines: List<Str> = parsed.message.split("\n")
    val firstMessage: Str = messageLines[0]
    val fileText: Str = parsed.file
    var out: Str = `@fileText:@numberText:@columnText: error: @firstMessage`
    out.append('\n')
    out.appendStrPtr(numberText)
    out.appendStrPtr(" | ")
    out.appendStrPtr(shown)
    out.append('\n')
    out.appendStrPtr(numberPad)
    out.appendStrPtr(" | ")
    out.appendStrPtr(caretPad)
    out.append('^')
    var i: Int = 1
    while (i < messageLines.size()) {
        out.append('\n')
        out.appendStrPtr(messageLines[i])
        i++
    }
    return out
}
