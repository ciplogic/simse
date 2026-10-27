// literals.kt
//
// String-literal decoding, shared. Two callers need it and must agree: the emitter reads a
// literal's *byte* length for the string pool's length index and for the concatenation
// expansion, and the parser's `when` lowering reads the length (and, when it can spell it, the
// first byte) to guard a label's test with something cheaper than the whole comparison.
//
// The escape set is the language's own (specs/built-in-types.md): the single-character escapes,
// `\xNN` with any number of hex digits, and an octal escape of at most three digits - each one
// byte. Everything else is one byte per character, whatever the source's encoding.

package common

// Whether `ch` is a hexadecimal digit.
fun litIsHexDigit(ch: Char): Bool {
    return (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F')
}

// How many bytes a string literal's source text (quotes included) denotes: what the length
// index carries. The pool is the literal texts again, adjacent, and the C++ compiler
// decodes them, so this only has to agree about the cost of an escape - one byte each for
// the language's own set and a run, counted below, for the rest.
fun litByteLength(text: *Str): Int {
    if (text.size() < 2 || text[0] != '\"') {
        return text.size()
    }
    var count: Int = 0
    val end: Int = text.size() - 1
    var i: Int = 1
    while (i < end) {
        if (text[i] != '\\') {
            count++
            i++
            continue
        }
        i++
        if (i >= end) {
            break
        }
        val escape: Char = text[i]
        i++
        if (escape == 'x') {
            while (i < end && litIsHexDigit(text[i])) {
                i++
            }
        } else if (escape >= '0' && escape <= '7') {
            var digits: Int = 1
            while (digits < 3 && i < end && text[i] >= '0' && text[i] <= '7') {
                i++
                digits++
            }
        }
        count++
    }
    return count
}

// The `'c'` a string literal's first byte can be read as, or "" when it cannot be spelled as a
// character literal: an escape that denotes a control byte, a byte of a longer character, or
// anything outside printable ASCII. A spelling here is what lets the `when` lowering test a
// first byte directly instead of comparing the whole string.
//
// Only printable ASCII is spelled, so the byte never has to be converted to an integer and no
// value is ever out of a `Char`'s range. The quote and the backslash are escaped.
fun litCharSpelling(text: *Str): Str {
    if (text.size() < 2 || text[0] != '\"') {
        return ""
    }
    val end: Int = text.size() - 1
    if (1 >= end) {
        return ""
    }
    if (text[1] != '\\') {
        val ch: Char = text[1]
        if (ch < ' ' || ch > '~') {
            return ""
        }
        return litSpellChar(ch)
    }
    // A one-character escape whose byte is itself a printable ASCII character. The others
    // (`\n`, `\t`, `\0`, `\xNN`, octal) denote bytes no character literal should carry.
    if (2 >= end) {
        return ""
    }
    val escape: Char = text[2]
    if (escape == '\\' || escape == '\'' || escape == '\"') {
        return litSpellChar(escape)
    }
    return ""
}

// The quoted spelling of a backtick string (specs/built-in-types.md): its token text is the
// whole backtick run, and what the string pool carries must be an ordinary C++ string
// literal, so the bytes that need it are escaped - a backslash and a double quote - and
// every line ending becomes one newline escape (CRLF and a lone CR both). Nothing else is: a
// backtick string offers no escape of its own and its content is taken as written.
fun litRawString(text: *Str): Str {
    var out: Str = "\""
    val end: Int = text.size() - 1
    var i: Int = 1
    while (i < end) {
        val ch: Char = text[i]
        if (ch == '\\' || ch == '\"') {
            out.append('\\')
            out.append(ch)
        } else if (ch == '\r') {
            if (i + 1 < end && text[i + 1] == '\n') {
                i++
            }
            out.append('\\')
            out.append('n')
        } else if (ch == '\n') {
            out.append('\\')
            out.append('n')
        } else {
            out.append(ch)
        }
        i++
    }
    out.append('\"')
    return out
}

// The character literal for one printable character, `'` and `\` escaped.
fun litSpellChar(ch: Char): Str {
    var out: Str = "'"
    if (ch == '\'' || ch == '\\') {
        out.append('\\')
    }
    out.append(ch)
    out.append('\'')
    return out
}

// A hexadecimal digit's value (the caller has checked `litIsHexDigit`).
fun litHexValue(ch: Char): Int {
    val v: Int = ch - '0'
    if (v >= 0 && v <= 9) {
        return v
    }
    if (ch >= 'a' && ch <= 'f') {
        return ch - 'a' + 10
    }
    return ch - 'A' + 10
}

// An integer literal's value in `Int64` (`0x`/`0X` prefixes hexadecimal; decimal
// otherwise - the scanner has already refused what neither spells): the magnitude only,
// since the sign is the unary operator's. None when the text is malformed or the value
// outranks `Int64`'s own range.
fun litParseInt64(text: Str): Opt<Int64> {
    if (text.size() > 2 && text[0] == '0' && (text[1] == 'x' || text[1] == 'X')) {
        var value: Int64 = 0
        var overflow: Bool = false
        var i: Int = 2
        while (i < text.size()) {
            val ch: Char = text[i]
            if (!litIsHexDigit(ch)) {
                return simse_optNone<Int64>()
            }
            if (!overflow) {
                value = value * 16 + litHexValue(ch)
                if (value < 0) {
                    // The accumulate wrapped past `Int64`'s top: no literal there.
                    overflow = true
                }
            }
            i++
        }
        if (overflow) {
            return simse_optNone<Int64>()
        }
        return simse_optSome<Int64>(value)
    }
    val parsed: Opt<Int> = text.toInt()
    if (!parsed.hasValue()) {
        return simse_optNone<Int64>()
    }
    val widened: Int64 = parsed.value()
    return simse_optSome<Int64>(widened)
}

// Whether an integer literal's magnitude outranks `Int32` (an `Int64` literal): decimal
// by digit count against "2147483647", hexadecimal by value against 0x7FFFFFFF. A value
// past even `Int64` is the widest literal there is.
fun litIntLiteralIsInt64(text: Str): Bool {
    if (text.size() > 2 && text[0] == '0' && (text[1] == 'x' || text[1] == 'X')) {
        val parsed: Opt<Int64> = litParseInt64(text)
        if (!parsed.hasValue()) {
            return true
        }
        return parsed.value() > 2147483647
    }
    val digits: Int = text.size()
    if (digits < 10) {
        return false
    }
    if (digits > 10) {
        return true
    }
    return text > "2147483647"
}
