// ResComments.kt
//
// A resource section's text is C++ written for a reader: the comments in `_res.md` explain the
// runtime, and the `res` generator copies the text into every program that reaches it. They
// are dropped where the text enters the amalgamation (`resGenAddSection`), so the emitted C++
// carries the code without the prose. A `//` inside a string, character or raw-string literal
// is data and is preserved (`stress/raw-strings`).

package compiler

// `text` with its C++ comments removed. A line comment alone on its line takes the line with
// it (indentation included); after code it takes only itself. Block comments become one space
// between tokens, or nothing when they hold a line of their own. Literals are copied verbatim.
fun resCppStripComments(text: Str): Str {
    var out: Str = Str()
    var i: Int = 0
    val n: Int = text.size()
    var lineStart: Int = 0
    var lineOnly: Bool = true
    while (i < n) {
        val c: Char = text.charAt(i)
        if (c == '\"' || c == '\'') {
            i = resCopyQuoted(text, i, *out)
            lineOnly = false
            continue
        }
        if (c == 'R' && i + 1 < n && text.charAt(i + 1) == '\"') {
            i = resCopyRawString(text, i, *out)
            lineOnly = false
            continue
        }
        if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
            while (i < n && text.charAt(i) != '\n') {
                i = i + 1
            }
            if (lineOnly) {
                out.resize(lineStart)
                if (i < n) {
                    i = i + 1
                }
            }
            continue
        }
        if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
            i = resSkipBlockComment(text, i)
            if (lineOnly) {
                // The comment was the whole line so far: if only blanks follow, the line goes.
                var j: Int = i
                while (j < n && (text.charAt(j) == ' ' || text.charAt(j) == '\t'
                            || text.charAt(j) == '\r')
                ) {
                    j = j + 1
                }
                if (j >= n || text.charAt(j) == '\n') {
                    out.resize(lineStart)
                    if (j < n) {
                        i = j + 1
                    } else {
                        i = j
                    }
                    continue
                }
            }
            out.append(' ')
            lineOnly = false
            continue
        }
        out.append(c)
        if (c == '\n') {
            lineStart = out.size()
            lineOnly = true
        } else if (c != ' ' && c != '\t' && c != '\r') {
            lineOnly = false
        }
        i = i + 1
    }
    return out
}

// Copies the literal starting at `start` (a `"` or `'`): escapes are consumed with the byte
// they escape, so a `\"` inside does not end it and a `\`-newline continuation stays inside.
// Returns the index after the closing quote, or the end of the line for an unterminated one.
fun resCopyQuoted(text: Str, start: Int, out: *Str): Int {
    val quote: Char = text.charAt(start)
    out.append(quote)
    var i: Int = start + 1
    val n: Int = text.size()
    while (i < n) {
        val c: Char = text.charAt(i)
        out.append(c)
        i = i + 1
        if (c == '\\') {
            if (i < n) {
                out.append(text.charAt(i))
                i = i + 1
            }
            continue
        }
        if (c == quote) {
            break
        }
        if (c == '\n') {
            break
        }
    }
    return i
}

// Copies the raw string starting at `start` (the `R` of `R"delim(...)delim"`): everything
// through the closing sequence is data, newlines and `//` included. A `R"` that is not a raw
// string copies the `R` alone.
fun resCopyRawString(text: Str, start: Int, out: *Str): Int {
    val n: Int = text.size()
    var open: Int = -1
    var i: Int = start + 2
    while (i < n) {
        val c: Char = text.charAt(i)
        if (c == '(') {
            open = i
            break
        }
        if (c == '\n' || c == ')' || c == '\\' || c == ' ' || c == '\t' || c == '\"') {
            break
        }
        i = i + 1
    }
    if (open < 0) {
        out.append('R')
        return start + 1
    }
    out.appendStr(text.substr(start, open - start + 1))
    val close: Str = ")" + text.substr(start + 2, open - start - 2) + "\""
    var j: Int = open + 1
    while (j < n) {
        if (j + close.size() <= n && text.substr(j, close.size()) == close) {
            out.appendStr(close)
            return j + close.size()
        }
        out.append(text.charAt(j))
        j = j + 1
    }
    return j
}

// The index after the `*/` of the block comment starting at `start`, or the end of `text`.
fun resSkipBlockComment(text: Str, start: Int): Int {
    val n: Int = text.size()
    var i: Int = start + 2
    while (i < n) {
        if (text.charAt(i) == '*' && i + 1 < n && text.charAt(i + 1) == '/') {
            return i + 2
        }
        i = i + 1
    }
    return n
}
