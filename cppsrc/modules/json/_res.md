Generated Simse
====
The `json` module's fixed text, read by cppsrc/modules/json/generators/JsonGen.kt - the quoting
helper every generated string serializer calls. It is Simse, not C++, and the driver compiles it
with the program. A prose line here is ignored only when it holds no colon.

!json
====
The quoting helper the generated string serializers call. A prose line here is ignored only when
it holds no colon.

helpers:
```kt
// JSON string quoting: the four escapes JSON requires inside a string, plus the surrounding
// quotes. `out.append('\\')` is one backslash; the resource format interprets no escapes, so
// what this block says is what the compiler parses.
fun jsonQuoted(value: Str): Str {
    var out: Str = Str()
    out.append('"')
    var i: Int = 0
    while (i < value.size()) {
        val ch: Char = value.charAt(i)
        if (ch == '"') {
            out.append('\\')
            out.append('"')
        } else if (ch == '\\') {
            out.append('\\')
            out.append('\\')
        } else if (ch == '\n') {
            out.append('\\')
            out.append('n')
        } else if (ch == '\r') {
            out.append('\\')
            out.append('r')
        } else if (ch == '\t') {
            out.append('\\')
            out.append('t')
        } else {
            out.append(ch)
        }
        i = i + 1
    }
    out.append('"')
    return out
}
```
