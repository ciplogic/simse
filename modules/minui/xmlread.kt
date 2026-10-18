package minui

import xml

// The XML text reader.
//
// The `xml` module supplies the tree it parses into (`XmlNode`/`Attribute`, `modules/xml/api.kt`)
// but not a reader, so this file is the reader - for the subset a screen needs and no more:
//
//   - elements with attributes in quotes, self-closing (`<label .../>`) or with content,
//   - text content between the tags (trimmed; it becomes a `text` attribute, since `XmlNode` has
//     no text slot - and `text` is the attribute the loader reads anyway),
//   - comments (`<!-- ... -->`), which is what makes a hand-written screen readable,
//   - the five predefined entities in text and attribute values (`&amp;` `&lt;` `&gt;`
//     `&quot;` `&apos;`); anything else is an error, not silence.
//
// Every element carries a `line` attribute, first in its list: XML text has no line numbers, and
// the loader's error messages are worth little without them. That attribute name is reserved -
// the reader refuses a document that writes it.
//
// What is deliberately absent: namespaces, CDATA, processing instructions, a DOCTYPE, attribute
// values without quotes, and entity *declarations*. A screen is a small, hand-written document;
// a document that needs more than this should say so loudly, which it does - every failure is a
// `Res` error with the line whose problem it is.

// The reader's cursor: the text, where it is, and the line that position is on. A data class so a
// helper can take it by pointer and advance it (the same shape the parser for the language itself
// uses: `Scanner` over a `StrView`).
data class XmlCursor(var text: Str, var at: Int, var line: Int)

// The first `+`, so the reader never allocates a StrView: `Str` indexes to `Char` directly.
fun xmlAtEnd(c: *XmlCursor): Bool {
    return c.at >= c.text.size()
}

fun xmlPeek(c: *XmlCursor): Char {
    return c.text[c.at]
}

// Step over one character, counting the lines it ends.
fun xmlStep(c: *XmlCursor): Unit {
    if (xmlPeek(c) == '\n') {
        c.line++
    }
    c.at++
}

fun xmlSkipSpace(c: *XmlCursor): Unit {
    while (!xmlAtEnd(c)) {
        val ch: Char = xmlPeek(c)
        if (ch != ' ' && ch != '\t' && ch != '\r' && ch != '\n') {
            return
        }
        xmlStep(c)
    }
}

// An error with the line in front of the message. The payload is `Int` and always 0: these helpers
// only ever have a message to carry, and `Res<Unit>` - which is what they want to be - emits an
// invalid `Res<void>` in C++ (the transpiler accepts it; the compiler of the generated code does
// not, because a `void` field cannot exist).
fun xmlFail<T>(line: Int, message: Str): Res<T> {
    return Res<T>.err("line " + line.toString() + ": " + message)
}

// A name character: letters, digits and the punctuation XML allows in a name. Deliberately
// generous - the *element* names are checked against the loader's list, so the reader does not
// have to be the one that knows them.
fun xmlNameChar(ch: Char): Bool {
    if (ch >= 'a' && ch <= 'z') {
        return true
    }
    if (ch >= 'A' && ch <= 'Z') {
        return true
    }
    if (ch >= '0' && ch <= '9') {
        return true
    }
    return ch == '-' || ch == '_' || ch == ':' || ch == '.'
}

fun xmlName(c: *XmlCursor): Res<Str> {
    val start: Int = c.at
    while (!xmlAtEnd(c) && xmlNameChar(xmlPeek(c))) {
        xmlStep(c)
    }
    if (c.at == start) {
        return xmlFail<Str>(c.line, "expected a name")
    }
    return Res<Str>.ok(c.text.substr(start, c.at - start))
}

// The predefined entities, and nothing else.
fun xmlEntity(name: Str): Opt<Str> {
    if (name == "amp") {
        return Opt<Str>.some("&")
    }
    if (name == "lt") {
        return Opt<Str>.some("<")
    }
    if (name == "gt") {
        return Opt<Str>.some(">")
    }
    if (name == "quot") {
        return Opt<Str>.some("\"")
    }
    if (name == "apos") {
        return Opt<Str>.some("'")
    }
    return Opt<Str>.none()
}

// Read text into `out` up to (not including) `stop`. `xmlStepPasted`... no: the caller decides what
// stops it - a quote for an attribute value, `<` for content - and this reads one run, decoding
// entities. `what` names the run in an error ("attribute", "text").
fun xmlReadRun(c: *XmlCursor, stop: Char, out: *Str, what: Str): Res<Int> {
    while (!xmlAtEnd(c)) {
        val ch: Char = xmlPeek(c)
        if (ch == stop) {
            return Res<Int>.ok(0)
        }
        if (ch == '&') {
            val line: Int = c.line
            xmlStep(c)
            val start: Int = c.at
            while (!xmlAtEnd(c) && xmlPeek(c) != ';') {
                xmlStep(c)
            }
            if (xmlAtEnd(c)) {
                return xmlFail<Int>(line, "unterminated entity in " + what)
            }
            val name: Str = c.text.substr(start, c.at - start)
            xmlStep(c)
            val decoded: Opt<Str> = xmlEntity(name)
            if (!decoded.hasValue()) {
                return xmlFail<Int>(line, "unknown entity &" + name + "; in " + what)
            }
            out.appendStr(decoded.value())
        } else {
            out.append(ch)
            xmlStep(c)
        }
    }
    return xmlFail<Int>(c.line, "unterminated " + what)
}

// `<` `name`, the open half of an element. The caller has seen `<` and is past it.
fun xmlOpenTag(c: *XmlCursor): Res<Str> {
    xmlStep(c)
    val name: Res<Str> = xmlName(c)
    if (!name.isOk()) {
        return Res<Str>.err(name.error())
    }
    return name
}

// One element, cursor at its `<`. Recurses for children; the cursor ends after its `>`.
fun xmlElement(c: *XmlCursor): Res<XmlNode> {
    val open: Int = c.line
    val name: Res<Str> = xmlOpenTag(c)
    if (!name.isOk()) {
        return Res<XmlNode>.err(name.error())
    }
    val tag: Str = name.value()
    var attributes: List<Attribute> = List<Attribute>()
    // The source line first, so the loader can report one for anything in the element.
    attributes.append(Attribute("line", open.toString()))
    while (true) {
        xmlSkipSpace(c)
        if (xmlAtEnd(c)) {
            return xmlFail<XmlNode>(open, "unterminated <" + tag + ">")
        }
        val ch: Char = xmlPeek(c)
        if (ch == '>') {
            xmlStep(c)
            break
        }
        if (ch == '/') {
            // `/>`: an element with no content.
            xmlStep(c)
            if (xmlAtEnd(c) || xmlPeek(c) != '>') {
                return xmlFail<XmlNode>(c.line, "expected `/>` in <" + tag + ">")
            }
            xmlStep(c)
            return Res<XmlNode>.ok(XmlNode(tag, attributes, Array<XmlNode>()))
        }
        // An attribute: name, `=`, a quoted value.
        val attrLine: Int = c.line
        val attrName: Res<Str> = xmlName(c)
        if (!attrName.isOk()) {
            return Res<XmlNode>.err(attrName.error())
        }
        val key: Str = attrName.value()
        xmlSkipSpace(c)
        if (xmlAtEnd(c) || xmlPeek(c) != '=') {
            return xmlFail<XmlNode>(attrLine, "expected `=` after attribute " + key)
        }
        xmlStep(c)
        xmlSkipSpace(c)
        if (xmlAtEnd(c)) {
            return xmlFail<XmlNode>(attrLine, "attribute " + key + " has no value")
        }
        val quote: Char = xmlPeek(c)
        if (quote != '"' && quote != '\'') {
            return xmlFail<XmlNode>(c.line, "attribute " + key + " needs a quoted value")
        }
        xmlStep(c)
        var value: Str = Str()
        val read: Res<Int> = xmlReadRun(c, quote, *value, "attribute " + key)
        if (!read.isOk()) {
            return Res<XmlNode>.err(read.error())
        }
        xmlStep(c)
        if (key == "line") {
            return xmlFail<XmlNode>(attrLine, "`line` is the reader's own attribute")
        }
        var seen: Int = 0
        while (seen < attributes.size()) {
            if (attributes[seen].name == key) {
                return xmlFail<XmlNode>(attrLine, "attribute " + key + " written twice")
            }
            seen++
        }
        attributes.append(Attribute(key, value))
    }
    // Content: text runs and child elements, in order. Text accumulates and is trimmed at the end.
    var content: Str = Str()
    var children: List<XmlNode> = List<XmlNode>()
    while (true) {
        if (xmlAtEnd(c)) {
            return xmlFail<XmlNode>(open, "unterminated <" + tag + ">")
        }
        if (xmlPeek(c) == '<') {
            if (c.at + 1 < c.text.size() && c.text[c.at + 1] == '/') {
                xmlStep(c)
                xmlStep(c)
                val closeLine: Int = c.line
                val closeName: Res<Str> = xmlName(c)
                if (!closeName.isOk()) {
                    return Res<XmlNode>.err(closeName.error())
                }
                if (closeName.value() != tag) {
                    return xmlFail<XmlNode>(
                        closeLine,
                        "</" + closeName.value() + "> closes <" + tag + ">"
                    )
                }
                xmlSkipSpace(c)
                if (xmlAtEnd(c) || xmlPeek(c) != '>') {
                    return xmlFail<XmlNode>(c.line, "expected `>` closing </" + tag + ">")
                }
                xmlStep(c)
                val trimmed: Str = content.trim()
                if (trimmed.size() > 0) {
                    var hasText: Bool = false
                    var i: Int = 0
                    while (i < attributes.size()) {
                        if (attributes[i].name == "text") {
                            hasText = true
                        }
                        i++
                    }
                    if (hasText) {
                        return xmlFail<XmlNode>(open, "<" + tag + "> has content and a text attribute")
                    }
                    attributes.append(Attribute("text", trimmed))
                }
                return Res<XmlNode>.ok(XmlNode(tag, attributes, children.toArray()))
            }
            val child: Res<XmlNode> = xmlElement(c)
            if (!child.isOk()) {
                return Res<XmlNode>.err(child.error())
            }
            children.append(child.value())
        } else {
            val read: Res<Int> = xmlReadRun(c, '<', *content, "text")
            if (!read.isOk()) {
                return Res<XmlNode>.err(read.error())
            }
        }
    }
}

// Comments and whitespace between elements. A comment may not nest or contain `--`; the reader
// stops at the first `-->`.
fun xmlSkipMisc(c: *XmlCursor): Res<Int> {
    while (true) {
        xmlSkipSpace(c)
        if (c.at + 3 < c.text.size() && c.text[c.at] == '<' && c.text[c.at + 1] == '!'
            && c.text[c.at + 2] == '-' && c.text[c.at + 3] == '-'
        ) {
            val line: Int = c.line
            xmlStep(c)
            xmlStep(c)
            xmlStep(c)
            xmlStep(c)
            while (true) {
                if (xmlAtEnd(c)) {
                    return xmlFail<Int>(line, "unterminated comment")
                }
                if (xmlPeek(c) == '-' && c.at + 2 < c.text.size()
                    && c.text[c.at + 1] == '-' && c.text[c.at + 2] == '>'
                ) {
                    xmlStep(c)
                    xmlStep(c)
                    xmlStep(c)
                    break
                }
                xmlStep(c)
            }
        } else {
            return Res<Int>.ok(0)
        }
    }
}

// A whole document: one root element, comments and whitespace around it, nothing else.
fun parseXmlText(text: Str): Res<XmlNode> {
    var cursor: XmlCursor = XmlCursor(text, 0, 1)
    val start: Res<Int> = xmlSkipMisc(*cursor)
    if (!start.isOk()) {
        return Res<XmlNode>.err(start.error())
    }
    if (xmlAtEnd(*cursor)) {
        return xmlFail<XmlNode>(cursor.line, "no element")
    }
    val root: Res<XmlNode> = xmlElement(*cursor)
    if (!root.isOk()) {
        return root
    }
    val rest: Res<Int> = xmlSkipMisc(*cursor)
    if (!rest.isOk()) {
        return Res<XmlNode>.err(rest.error())
    }
    if (!xmlAtEnd(*cursor)) {
        return xmlFail<XmlNode>(cursor.line, "text after the root element")
    }
    return root
}
