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
            count = count + 1
            i = i + 1
            continue
        }
        i = i + 1
        if (i >= end) {
            break
        }
        val escape: Char = text[i]
        i = i + 1
        if (escape == 'x') {
            while (i < end && litIsHexDigit(text[i])) {
                i = i + 1
            }
        } else if (escape >= '0' && escape <= '7') {
            var digits: Int = 1
            while (digits < 3 && i < end && text[i] >= '0' && text[i] <= '7') {
                i = i + 1
                digits = digits + 1
            }
        }
        count = count + 1
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
