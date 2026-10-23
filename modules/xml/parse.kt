package xml

// parse.kt - the recursive-descent XML parser over `XmlNode` (api.kt).
//
// The scope is the core grammar a data interchange needs: elements, attributes, text,
// comments (skipped), processing instructions and the XML declaration (skipped), DOCTYPE
// (skipped, internal subset included), CDATA, self-closing tags, and the five predefined
// entities plus decimal and hex character references. Namespaces are *not* resolved: a
// prefix stays part of the name the way it is spelled (`xlink:href` is one name), the
// DOM-lite choice for a tree this size.
//
// Text is a child node named `#text` whose `text` attribute carries the characters - the
// same spelling `modules/minui/xmlread.kt` reads - so mixed content round-trips:
// `<p>a<b/>c</p>` parses to three children. Whitespace-only text *between* elements is
// dropped, because the indentation a hand-written or pretty-printed document carries is
// layout, not content; whitespace inside a text run is kept verbatim. CDATA becomes a
// `#text` child with its content already unescaped, which is what its contents mean.
//
// `XmlNode`'s `Children` is an `Array` and an array's count is immutable
// (specs/built-in-types.md), so both trees - the attributes and the children - build in a
// `List` and freeze with `toArray()` when the element closes.
//
// The parser is a data class over the input `Str` plus a cursor, so every helper takes it
// by pointer and the position an error reports is one field away. Every failure is a
// `Res` error with the line whose problem it is; a malformed document is a value, not a
// crash.

// The parse cursor: the whole input, where we are, and the line that position is on.
data class XmlParser(var text: Str, var at: Int, var line: Int)

fun xmlParse(text: Str): Res<XmlNode> {
    var p: XmlParser = XmlParser(text, 0, 1)
    xmlSkipMisc(*p)
    if (xmlAtEnd(*p)) {
        return Res<XmlNode>.err("xml: the document holds no element")
    }
    if (xmlPeek(*p) != '<') {
        return Res<XmlNode>.err("xml: expected '<', found " + xmlPeek(*p).toString() + " at line " + p.line.toString())
    }
    val root: Res<XmlNode> = xmlParseElement(*p)
    if (!root.isOk()) {
        return root
    }
    xmlSkipMisc(*p)
    if (!xmlAtEnd(*p)) {
        return Res<XmlNode>.err("xml: content after the root element at line " + p.line.toString())
    }
    return root
}

// ---- the element recursion -------------------------------------------------

// At `<name ...>`: the attributes, then either a self-close or the children and the close
// tag. The tag name is matched on the way out, so a mismatched close is a positioned error
// and not silent recovery.
fun xmlParseElement(p: *XmlParser): Res<XmlNode> {
    val openLine: Int = p.line
    xmlStep(p) // the '<'
    val name: Res<Str> = xmlParseName(p)
    if (!name.isOk()) {
        return Res<XmlNode>.err(name.error())
    }
    var attributes: List<Attribute> = List<Attribute>()
    var children: List<XmlNode> = List<XmlNode>()
    while (true) {
        xmlSkipSpace(p)
        if (xmlAtEnd(p)) {
            return Res<XmlNode>.err("xml: <" + name.value() + "> opened at line " + openLine.toString() + " never closes")
        }
        val ch: Char = xmlPeek(p)
        if (ch == '>') {
            xmlStep(p)
            break
        }
        if (ch == '/') {
            xmlStep(p)
            if (xmlAtEnd(p) || xmlPeek(p) != '>') {
                return Res<XmlNode>.err("xml: expected '>' after the '/' of <" + name.value() + "> at line " + p.line.toString())
            }
            xmlStep(p)
            return Res<XmlNode>.ok(XmlNode(name.value(), attributes, children.toArray()))
        }
        val attrName: Res<Str> = xmlParseName(p)
        if (!attrName.isOk()) {
            return Res<XmlNode>.err(attrName.error())
        }
        xmlSkipSpace(p)
        if (xmlAtEnd(p) || xmlPeek(p) != '=') {
            return Res<XmlNode>.err("xml: attribute " + attrName.value() + " in <" + name.value() + "> lacks '=' at line " + p.line.toString())
        }
        xmlStep(p)
        xmlSkipSpace(p)
        val value: Res<Str> = xmlParseQuoted(p)
        if (!value.isOk()) {
            return Res<XmlNode>.err(value.error())
        }
        attributes.append(Attribute(attrName.value(), value.value()))
    }
    // The content: children until the matching close tag.
    while (true) {
        if (xmlAtEnd(p)) {
            return Res<XmlNode>.err("xml: <" + name.value() + "> opened at line " + openLine.toString() + " never closes")
        }
        if (xmlPeek(p) != '<') {
            val text: Res<Str> = xmlParseText(p)
            if (!text.isOk()) {
                return Res<XmlNode>.err(text.error())
            }
            // Whitespace-only between elements is layout, not content.
            if (text.value().trim().size() > 0) {
                children.append(xmlTextNode(text.value()))
            }
            continue
        }
        // A markup construct: comment, CDATA, processing instruction, close tag, child.
        if (xmlHas(p, "<!--")) {
            if (!xmlSkipComment(p)) {
                return Res<XmlNode>.err("xml: the comment at line " + p.line.toString() + " never closes")
            }
            continue
        }
        if (xmlHas(p, "<![CDATA[")) {
            xmlSkip(p, 9)
            val cdata: Str = xmlUpTo(p, "]]>")
            if (xmlAtEnd(p)) {
                return Res<XmlNode>.err("xml: the CDATA in <" + name.value() + "> at line " + p.line.toString() + " never closes")
            }
            xmlSkip(p, 3)
            children.append(xmlTextNode(cdata))
            continue
        }
        if (xmlHas(p, "<?")) {
            xmlUpTo(p, "?>")
            if (xmlAtEnd(p)) {
                return Res<XmlNode>.err("xml: the processing instruction at line " + p.line.toString() + " never closes")
            }
            xmlSkip(p, 2)
            continue
        }
        if (xmlHas(p, "</")) {
            xmlSkip(p, 2)
            xmlSkipSpace(p)
            val close: Res<Str> = xmlParseName(p)
            if (!close.isOk()) {
                return Res<XmlNode>.err(close.error())
            }
            xmlSkipSpace(p)
            if (close.value() != name.value()) {
                return Res<XmlNode>.err("xml: </" + close.value() + "> closes <" + name.value() + "> opened at line " + openLine.toString())
            }
            xmlSkipSpace(p)
            if (xmlAtEnd(p) || xmlPeek(p) != '>') {
                return Res<XmlNode>.err("xml: expected '>' after </" + name.value() + "> at line " + p.line.toString())
            }
            xmlStep(p)
            return Res<XmlNode>.ok(XmlNode(name.value(), attributes, children.toArray()))
        }
        val child: Res<XmlNode> = xmlParseElement(p)
        if (!child.isOk()) {
            return child
        }
        children.append(child.value())
    }
}

// A `#text` node: the name says what it is, the `text` attribute says what it holds.
fun xmlTextNode(text: Str): XmlNode {
    var attributes: List<Attribute> = List<Attribute>()
    attributes.append(Attribute("text", text))
    return XmlNode("#text", attributes, Array<XmlNode>())
}

// ---- the small scanners ------------------------------------------------------

fun xmlParseName(p: *XmlParser): Res<Str> {
    if (xmlAtEnd(p) || !xmlIsNameStart(xmlPeek(p))) {
        return Res<Str>.err("xml: expected a name at line " + p.line.toString())
    }
    val start: Int = p.at
    while (!xmlAtEnd(p) && xmlIsNameChar(xmlPeek(p))) {
        xmlStep(p)
    }
    return Res<Str>.ok(p.text.substr(start, p.at - start))
}

fun xmlIsNameStart(ch: Char): Bool {
    if (ch == '_' || ch == ':') {
        return true
    }
    if (ch >= 'a' && ch <= 'z') {
        return true
    }
    if (ch >= 'A' && ch <= 'Z') {
        return true
    }
    return Int(ch) < 0
}

fun xmlIsNameChar(ch: Char): Bool {
    if (xmlIsNameStart(ch)) {
        return true
    }
    if (ch >= '0' && ch <= '9') {
        return true
    }
    return ch == '-' || ch == '.'
}

// A quoted attribute value with entities decoded. Both quotes are legal; the value ends at
// its own quote character.
fun xmlParseQuoted(p: *XmlParser): Res<Str> {
    if (xmlAtEnd(p) || (xmlPeek(p) != '"' && xmlPeek(p) != '\'')) {
        return Res<Str>.err("xml: an attribute value at line " + p.line.toString() + " is not quoted")
    }
    val quote: Char = xmlPeek(p)
    xmlStep(p)
    var out: Str = Str()
    while (!xmlAtEnd(p) && xmlPeek(p) != quote) {
        if (xmlPeek(p) == '&') {
            val decoded: Res<Str> = xmlDecodeEntity(p)
            if (!decoded.isOk()) {
                return decoded
            }
            out.appendStr(decoded.value())
            continue
        }
        xmlAppendStep(p, *out)
    }
    if (xmlAtEnd(p)) {
        return Res<Str>.err("xml: a quoted value at line " + p.line.toString() + " never closes")
    }
    xmlStep(p) // the closing quote
    return Res<Str>.ok(out)
}

// Character data up to the next '<', entities decoded.
fun xmlParseText(p: *XmlParser): Res<Str> {
    var out: Str = Str()
    while (!xmlAtEnd(p) && xmlPeek(p) != '<') {
        if (xmlPeek(p) == '&') {
            val decoded: Res<Str> = xmlDecodeEntity(p)
            if (!decoded.isOk()) {
                return decoded
            }
            out.appendStr(decoded.value())
            continue
        }
        xmlAppendStep(p, *out)
    }
    return Res<Str>.ok(out)
}

// One entity at the cursor: the five predefined ones plus `&#dd;` and `&#xhh;`. An unknown
// or malformed reference is an error - silently copying the raw bytes would turn a typo
// into data.
fun xmlDecodeEntity(p: *XmlParser): Res<Str> {
    val startLine: Int = p.line
    xmlStep(p) // the '&'
    var body: Str = Str()
    while (!xmlAtEnd(p) && xmlPeek(p) != ';' && body.size() < 12) {
        body.append(xmlPeek(p))
        xmlStep(p)
    }
    if (xmlAtEnd(p) || xmlPeek(p) != ';') {
        return Res<Str>.err("xml: an '&' at line " + startLine.toString() + " never closes with ';'")
    }
    xmlStep(p) // the ';'
    if (body == "amp") {
        return Res<Str>.ok("&")
    }
    if (body == "lt") {
        return Res<Str>.ok("<")
    }
    if (body == "gt") {
        return Res<Str>.ok(">")
    }
    if (body == "quot") {
        return Res<Str>.ok("\"")
    }
    if (body == "apos") {
        return Res<Str>.ok("'")
    }
    if (body.startsWith("#x") || body.startsWith("#X")) {
        return xmlCodePoint(body.substr(2, body.size() - 2), 16, startLine)
    }
    if (body.startsWith("#")) {
        return xmlCodePoint(body.substr(1, body.size() - 1), 10, startLine)
    }
    return Res<Str>.err("xml: unknown entity &" + body + "; at line " + startLine.toString())
}

fun xmlCodePoint(digits: Str, base: Int, line: Int): Res<Str> {
    var value: Int = 0
    var i: Int = 0
    while (i < digits.size()) {
        val d: Int = xmlDigitValue(digits[i], base)
        if (d < 0) {
            return Res<Str>.err("xml: bad character reference " + digits + " at line " + line.toString())
        }
        value = value * base + d
        i++
    }
    var out: Str = Str()
    out.append(Char(value))
    return Res<Str>.ok(out)
}

fun xmlDigitValue(ch: Char, base: Int): Int {
    if (ch >= '0' && ch <= '9') {
        val d: Int = Int(ch) - 48
        if (d < base) {
            return d
        }
        return -1
    }
    if (base > 10) {
        if (ch >= 'a' && ch <= 'f') {
            return Int(ch) - 87
        }
        if (ch >= 'A' && ch <= 'F') {
            return Int(ch) - 55
        }
    }
    return -1
}

// ---- cursor helpers -----------------------------------------------------------

fun xmlAtEnd(p: *XmlParser): Bool {
    return p.at >= p.text.size()
}

fun xmlPeek(p: *XmlParser): Char {
    return p.text[p.at]
}

// Step over one character, counting the line it ends.
fun xmlStep(p: *XmlParser): Unit {
    if (xmlPeek(p) == '\n') {
        p.line++
    }
    p.at++
}

// Step over `count` characters, counting the lines they end.
fun xmlSkip(p: *XmlParser, count: Int): Unit {
    var i: Int = 0
    while (i < count) {
        xmlStep(p)
        i++
    }
}

fun xmlAppendStep(p: *XmlParser, out: *Str): Unit {
    out.append(xmlPeek(p))
    xmlStep(p)
}

fun xmlSkipSpace(p: *XmlParser): Unit {
    while (!xmlAtEnd(p)) {
        val ch: Char = xmlPeek(p)
        if (ch != ' ' && ch != '\t' && ch != '\r' && ch != '\n') {
            return
        }
        xmlStep(p)
    }
}

// Whatever may sit before the root or after it: the declaration, comments, DOCTYPE,
// processing instructions, whitespace.
fun xmlSkipMisc(p: *XmlParser): Unit {
    while (true) {
        xmlSkipSpace(p)
        if (xmlAtEnd(p) || xmlPeek(p) != '<') {
            return
        }
        if (xmlHas(p, "<?")) {
            xmlUpTo(p, "?>")
            if (xmlAtEnd(p)) {
                return
            }
            xmlSkip(p, 2)
            continue
        }
        if (xmlHas(p, "<!--")) {
            xmlSkipComment(p)
            continue
        }
        if (xmlHas(p, "<!")) {
            // DOCTYPE: skip to '>', but an internal subset in brackets comes first.
            xmlStep(p)
            var depth: Int = 0
            while (!xmlAtEnd(p)) {
                val ch: Char = xmlPeek(p)
                if (ch == '[') {
                    depth++
                }
                if (ch == ']') {
                    depth--
                }
                xmlStep(p)
                if (ch == '>' && depth == 0) {
                    break
                }
            }
            continue
        }
        return
    }
}

// Over the `-->`; false when the input ends first.
fun xmlSkipComment(p: *XmlParser): Bool {
    xmlSkip(p, 4)
    while (!xmlAtEnd(p)) {
        if (xmlHas(p, "-->")) {
            xmlSkip(p, 3)
            return true
        }
        xmlStep(p)
    }
    return false
}

// Consumes to the first `end` (not consumed); the caller checks the cursor.
fun xmlUpTo(p: *XmlParser, end: Str): Str {
    var out: Str = Str()
    while (!xmlAtEnd(p) && !xmlHas(p, end)) {
        xmlAppendStep(p, *out)
    }
    return out
}

fun xmlHas(p: *XmlParser, prefix: Str): Bool {
    if (p.at + prefix.size() > p.text.size()) {
        return false
    }
    return p.text.substr(p.at, prefix.size()) == prefix
}
