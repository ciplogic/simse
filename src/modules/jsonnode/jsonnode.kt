package jsonnode

// The `json` module's *dynamic* reader and writer, a module of its own so a program that only
// wants the generator's typed writer (`import json`, `value.toJson()`) does not carry the
// parser: `--module src/modules/jsonnode` is what opts in, and the express example names it
// beside `src/modules/json`.
//
// A request's payload is JSON whose shape the program does not know at compile time, so it
// lands in `JsonNode` first: a tagged union (`union class`, specs/declarations.md) where one
// of the six JSON shapes is live - `None` is JSON `null`, the other five are the arms.
// `parseJson` is the reader (`text -> Res<JsonNode>`), `JsonNode.toJson()` is the writer
// (`JsonNode -> text`), and the accessors below are how a handler reaches into a node. That
// is the pair the generator's typed writer sits beside: decode to a node, transform, encode.
//
// Arrays and objects recurse through `Array<T>` rather than `List<T>`: `Array` is a
// ref-counted *block* reached through a pointer, so `JsonNode` can name itself and
// `JsonMember` without embedding an incomplete type (a `List<JsonNode>` field would ask for
// `JsonNode`'s size while it is still being defined). Build a node's arms with `jsonArray`/
// `jsonObject`, which take the growable `List` and hand back the block.

// ---- the value ----

union class JsonNode(
    var Boolean: Bool,
    var Number: Float64,
    var Text: Str,
    var Items: Array<JsonNode>,
    var Members: Array<JsonMember>
)

data class JsonMember(var key: Str, var value: JsonNode)

// ---- reading a node ----

// JSON `null`, and the tag every fresh node starts on.
fun JsonNode.isNull(): Bool {
    return this.isOfType(SmJsonNodeTypes.None)
}

fun JsonNode.isArray(): Bool {
    return this.isOfType(SmJsonNodeTypes.Items)
}

fun JsonNode.isObject(): Bool {
    return this.isOfType(SmJsonNodeTypes.Members)
}

fun JsonNode.isBool(): Bool {
    return this.isOfType(SmJsonNodeTypes.Boolean)
}

fun JsonNode.isNumber(): Bool {
    return this.isOfType(SmJsonNodeTypes.Number)
}

fun JsonNode.isText(): Bool {
    return this.isOfType(SmJsonNodeTypes.Text)
}

// The string arm, or `""` when another arm is live.
fun JsonNode.text(): Str {
    if (this.isOfType(SmJsonNodeTypes.Text)) {
        return this.Text
    }
    return ""
}

// The number arm, or `0` when another arm is live.
fun JsonNode.number(): Float64 {
    if (this.isOfType(SmJsonNodeTypes.Number)) {
        return this.Number
    }
    return 0.0
}

// The boolean arm, or `false` when another arm is live.
fun JsonNode.boolean(): Bool {
    if (this.isOfType(SmJsonNodeTypes.Boolean)) {
        return this.Boolean
    }
    return false
}

// The element count of an array or object, and `0` for anything else.
fun JsonNode.size(): Int {
    if (this.isOfType(SmJsonNodeTypes.Items)) {
        return this.Items.count()
    }
    if (this.isOfType(SmJsonNodeTypes.Members)) {
        return this.Members.count()
    }
    return 0
}

// The array's element at `index`, or JSON `null` when this is not an array or the index is
// out of range - so a chain of reads never fails, it just stops at a `null` node.
fun JsonNode.at(index: Int): JsonNode {
    if (this.isOfType(SmJsonNodeTypes.Items) && index >= 0 && index < this.Items.count()) {
        return this.Items[index]
    }
    return JsonNode()
}

// The object's member named `key`, or JSON `null`. A `Str` comparison is the language's.
fun JsonNode.get(key: Str): JsonNode {
    if (!this.isOfType(SmJsonNodeTypes.Members)) {
        return JsonNode()
    }
    var i: Int = 0
    while (i < this.Members.count()) {
        if (this.Members[i].key == key) {
            return this.Members[i].value
        }
        i = i + 1
    }
    return JsonNode()
}

fun JsonNode.has(key: Str): Bool {
    if (!this.isOfType(SmJsonNodeTypes.Members)) {
        return false
    }
    var i: Int = 0
    while (i < this.Members.count()) {
        if (this.Members[i].key == key) {
            return true
        }
        i = i + 1
    }
    return false
}

// ---- building a node ----

fun jsonNull(): JsonNode {
    return JsonNode()
}

fun jsonBool(value: Bool): JsonNode {
    var node: JsonNode = JsonNode()
    node.setBoolean(value)
    return node
}

fun jsonNumber(value: Float64): JsonNode {
    var node: JsonNode = JsonNode()
    node.setNumber(value)
    return node
}

fun jsonText(value: Str): JsonNode {
    var node: JsonNode = JsonNode()
    node.setText(value)
    return node
}

fun jsonArray(items: List<JsonNode>): JsonNode {
    var node: JsonNode = JsonNode()
    node.setItems(items.toArray())
    return node
}

fun jsonObject(members: List<JsonMember>): JsonNode {
    var node: JsonNode = JsonNode()
    node.setMembers(members.toArray())
    return node
}

fun jsonMember(key: Str, value: JsonNode): JsonMember {
    return JsonMember(key, value)
}

// ---- writing a node ----

// A number is a `Float64` here, but a JSON number should read back the way it was written:
// C++'s own `to_string` always prints six decimals (`12.000000`), so an integral value loses
// its trailing zeros and a fractional one keeps only its significant digits.
fun jsonNumberText(value: Float64): Str {
    val printed: Str = value.toString()
    if (printed.find(".") < 0) {
        return printed
    }
    var end: Int = printed.size()
    while (end > 0 && printed.charAt(end - 1) == '0') {
        end = end - 1
    }
    if (end > 0 && printed.charAt(end - 1) == '.') {
        end = end - 1
    }
    return printed.substr(0, end)
}

// One JSON string, quoted and escaped, appended to `out`.
fun jsonWriteQuoted(out: *Str, value: Str): Unit {
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
}

// The writer walks a node into one buffer, so a nested structure costs one `Str` and no
// intermediate node text.
data class JsonWriter(var out: Str) {
    fun write(node: JsonNode): Unit {
        when (node) {
            None -> {
                this.out.appendStr("null")
            }

            Boolean -> {
                if (node.Boolean) {
                    this.out.appendStr("true")
                } else {
                    this.out.appendStr("false")
                }
            }

            Number -> {
                this.out.appendStr(jsonNumberText(node.Number))
            }

            Text -> {
                jsonWriteQuoted(*this.out, node.Text)
            }

            Items -> {
                this.out.append('[')
                var i: Int = 0
                while (i < node.Items.count()) {
                    if (i > 0) {
                        this.out.append(',')
                    }
                    this.write(node.Items[i])
                    i = i + 1
                }
                this.out.append(']')
            }

            Members -> {
                this.out.append('{')
                var i: Int = 0
                while (i < node.Members.count()) {
                    if (i > 0) {
                        this.out.append(',')
                    }
                    jsonWriteQuoted(*this.out, node.Members[i].key)
                    this.out.append(':')
                    this.write(node.Members[i].value)
                    i = i + 1
                }
                this.out.append('}')
            }
        }
    }
}

// The node's JSON text.
fun JsonNode.toJson(): Str {
    var writer: JsonWriter = JsonWriter(Str())
    writer.write(this)
    return writer.out
}

// ---- the parser ----

// The reader: a cursor over the text. `Res` carries a failure with the byte it happened at,
// so a malformed body answers a diagnostic instead of a crash (there are no exceptions).
data class JsonReader(var text: Str, var at: Int) {
    fun fail(message: Str): Res<JsonNode> {
        return Res<JsonNode>.err(fmtStr("json: | at byte |", message, this.at.toString()))
    }

    fun failText(message: Str): Res<Str> {
        return Res<Str>.err(fmtStr("json: | at byte |", message, this.at.toString()))
    }

    fun failChar(message: Str): Res<Char> {
        return Res<Char>.err(fmtStr("json: | at byte |", message, this.at.toString()))
    }

    fun skipSpace(): Unit {
        while (this.at < this.text.size()) {
            val ch: Char = this.text.charAt(this.at)
            if (!ch.isSpace()) {
                break
            }
            this.at = this.at + 1
        }
    }

    // Whether `word` is here, and the cursor past it when it is.
    fun consume(word: Str): Bool {
        if (this.at + word.size() > this.text.size()) {
            return false
        }
        if (this.text.substr(this.at, word.size()) == word) {
            this.at = this.at + word.size()
            return true
        }
        return false
    }

    fun parseValue(): Res<JsonNode> {
        this.skipSpace()
        if (this.at >= this.text.size()) {
            return this.fail("unexpected end of input")
        }
        val ch: Char = this.text.charAt(this.at)
        if (ch == '{') {
            return this.parseObject()
        }
        if (ch == '[') {
            return this.parseArray()
        }
        if (ch == '"') {
            val parsedText: Res<Str> = this.parseString()
            val text: Str = parsedText!!
            return Res<JsonNode>.ok(jsonText(text))
        }
        if (ch == 't' || ch == 'f' || ch == 'n') {
            return this.parseLiteral()
        }
        return this.parseNumber()
    }

    fun parseLiteral(): Res<JsonNode> {
        if (this.consume("true")) {
            return Res<JsonNode>.ok(jsonBool(true))
        }
        if (this.consume("false")) {
            return Res<JsonNode>.ok(jsonBool(false))
        }
        if (this.consume("null")) {
            return Res<JsonNode>.ok(jsonNull())
        }
        return this.fail("expected a value")
    }

    fun parseNumber(): Res<JsonNode> {
        val start: Int = this.at
        while (this.at < this.text.size()) {
            val ch: Char = this.text.charAt(this.at)
            if (ch.isDigit() || ch == '-' || ch == '+' || ch == '.' || ch == 'e' || ch == 'E') {
                this.at = this.at + 1
            } else {
                break
            }
        }
        if (this.at == start) {
            return this.fail("expected a value")
        }
        val token: Str = this.text.substr(start, this.at - start)
        val value: Opt<Float64> = token.toFloat()
        if (!value.hasValue()) {
            return this.fail(fmtStr("bad number '|'", token))
        }
        return Res<JsonNode>.ok(jsonNumber(value.value()))
    }

    // The cursor is on the opening quote.
    fun parseString(): Res<Str> {
        this.at = this.at + 1
        var out: Str = Str()
        while (this.at < this.text.size()) {
            val ch: Char = this.text.charAt(this.at)
            this.at = this.at + 1
            if (ch == '"') {
                return Res<Str>.ok(out)
            }
            if (ch != '\\') {
                out.append(ch)
                continue
            }
            if (this.at >= this.text.size()) {
                break
            }
            val esc: Char = this.text.charAt(this.at)
            this.at = this.at + 1
            if (esc == '"') {
                out.append('"')
            } else if (esc == '\\') {
                out.append('\\')
            } else if (esc == '/') {
                out.append('/')
            } else if (esc == 'n') {
                out.append('\n')
            } else if (esc == 'r') {
                out.append('\r')
            } else if (esc == 't') {
                out.append('\t')
            } else if (esc == 'b') {
                out.append('\b')
            } else if (esc == 'f') {
                out.append('\f')
            } else if (esc == 'u') {
                val parsedHex: Res<Char> = this.parseUnicodeEscape()
                val decoded: Char = parsedHex!!
                out.append(decoded)
            } else {
                return this.failText("bad escape")
            }
        }
        return this.failText("unterminated string")
    }

    fun parseUnicodeEscape(): Res<Char> {
        if (this.at + 4 > this.text.size()) {
            return this.failChar("bad unicode escape")
        }
        var code: Int = 0
        var i: Int = 0
        while (i < 4) {
            val ch: Char = this.text.charAt(this.at)
            this.at = this.at + 1
            val digit: Int = jsonHexValue(ch)
            if (digit < 0) {
                return this.failChar("bad unicode escape")
            }
            code = code * 16 + digit
            i = i + 1
        }
        // `Char` is one byte: a code point above ASCII becomes `?` (surrogate pairs and
        // non-Latin text need the UTF-8 work the module does not do yet).
        if (code > 127) {
            return Res<Char>.ok('?')
        }
        return Res<Char>.ok('\0' + code)
    }

    // The cursor is on `[`.
    fun parseArray(): Res<JsonNode> {
        this.at = this.at + 1
        var items: List<JsonNode> = List<JsonNode>()
        this.skipSpace()
        if (this.at < this.text.size() && this.text.charAt(this.at) == ']') {
            this.at = this.at + 1
            return Res<JsonNode>.ok(jsonArray(items))
        }
        while (true) {
            val parsedValue: Res<JsonNode> = this.parseValue()
            val value: JsonNode = parsedValue!!
            items.append(value)
            this.skipSpace()
            if (this.at >= this.text.size()) {
                return this.fail("unterminated array")
            }
            val ch: Char = this.text.charAt(this.at)
            this.at = this.at + 1
            if (ch == ']') {
                return Res<JsonNode>.ok(jsonArray(items))
            }
            if (ch != ',') {
                return this.fail("expected ',' or ']'")
            }
        }
    }

    // The cursor is on `{`.
    fun parseObject(): Res<JsonNode> {
        this.at = this.at + 1
        var members: List<JsonMember> = List<JsonMember>()
        this.skipSpace()
        if (this.at < this.text.size() && this.text.charAt(this.at) == '}') {
            this.at = this.at + 1
            return Res<JsonNode>.ok(jsonObject(members))
        }
        while (true) {
            this.skipSpace()
            if (this.at >= this.text.size() || this.text.charAt(this.at) != '"') {
                return this.fail("expected a string key")
            }
            val parsedKey: Res<Str> = this.parseString()
            val key: Str = parsedKey!!
            this.skipSpace()
            if (this.at >= this.text.size() || this.text.charAt(this.at) != ':') {
                return this.fail("expected ':'")
            }
            this.at = this.at + 1
            val parsedValue: Res<JsonNode> = this.parseValue()
            val value: JsonNode = parsedValue!!
            members.append(JsonMember(key, value))
            this.skipSpace()
            if (this.at >= this.text.size()) {
                return this.fail("unterminated object")
            }
            val ch: Char = this.text.charAt(this.at)
            this.at = this.at + 1
            if (ch == '}') {
                return Res<JsonNode>.ok(jsonObject(members))
            }
            if (ch != ',') {
                return this.fail("expected ',' or '}'")
            }
        }
    }
}

fun jsonHexValue(ch: Char): Int {
    if (ch >= '0' && ch <= '9') {
        return ch - '0'
    }
    if (ch >= 'a' && ch <= 'f') {
        return ch - 'a' + 10
    }
    if (ch >= 'A' && ch <= 'F') {
        return ch - 'A' + 10
    }
    return -1
}

// The reader's entry point: the whole body, or a `Res` error naming the byte the parse
// stopped at. Trailing whitespace is allowed; trailing text is not (a partial parse is a
// malformed body).
fun parseJson(text: Str): Res<JsonNode> {
    var reader: JsonReader = JsonReader(text, 0)
    val parsed: Res<JsonNode> = reader.parseValue()
    val node: JsonNode = parsed!!
    reader.skipSpace()
    if (reader.at != text.size()) {
        return Res<JsonNode>.err(fmtStr("json: trailing text at byte |", reader.at.toString()))
    }
    return Res<JsonNode>.ok(node)
}
