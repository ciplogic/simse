package xml

// write.kt - the serializer: an `XmlNode` tree back to XML text.
//
// `xmlWrite` is the compact form, one element per line of output; `xmlWritePretty`
// indents two spaces per depth, and an element whose children are all `#text` nodes -
// in practice, one - keeps its text inline so `<title>hi</title>` does not spread over
// three lines. Text and attribute values go through `xmlEscape`, which escapes the five
// characters the five predefined entities cover and nothing else; a parse-write round
// trip of a parsed document is byte-stable against `xmlWrite`'s own output.
//
// What the writer does not do: namespaces (the prefix is part of the name, so it writes
// verbatim), entity *references* beyond the five (a parse normalizes them to characters
// first), and the XML declaration (a document that wants one prepends it - the writer
// cannot know which encoding claim is honest).

// The five characters XML makes meaningful, escaped for text and attribute values alike.
// The attribute form quotes with `"`, so `"` must go; escaping it in text too is harmless
// and keeps one table.
fun xmlEscape(text: Str): Str {
    var out: Str = Str()
    var i: Int = 0
    while (i < text.size()) {
        val ch: Char = text[i]
        if (ch == '&') {
            out.appendStr("&amp;")
        } else if (ch == '<') {
            out.appendStr("&lt;")
        } else if (ch == '>') {
            out.appendStr("&gt;")
        } else if (ch == '"') {
            out.appendStr("&quot;")
        } else if (ch == '\'') {
            out.appendStr("&apos;")
        } else {
            out.append(ch)
        }
        i++
    }
    return out
}

// The compact form: every element on its own line, children unindented.
fun xmlWrite(node: XmlNode): Str {
    return xmlWritePretty(node, "")
}

// The pretty form: `indent` per depth (call it with "  "), a `#text`-only element inline.
// Each recursion returns its own text - an output parameter would have to be a `*Str`,
// and the docs are small enough that one concat per level is not worth the signature.
fun xmlWritePretty(node: XmlNode, indent: Str): Str {
    return xmlWriteLevel(node, indent, 0)
}

fun xmlWriteLevel(node: XmlNode, indent: Str, depth: Int): Str {
    var pad: Str = Str()
    var i: Int = 0
    while (i < depth) {
        pad.appendStr(indent)
        i++
    }
    var out: Str = Str()
    out.appendStr(pad)
    out.append('<')
    out.appendStr(node.name)
    var a: Int = 0
    while (a < node.attributes.size()) {
        val attribute: Attribute = node.attributes[a]
        out.append(' ')
        out.appendStr(attribute.name)
        out.appendStr("=\"")
        out.appendStr(xmlEscape(attribute.value))
        out.append('"')
        a++
    }
    // A `#text` child is the element's own characters: written inline, unindented, and
    // the only content an element with no element children carries.
    var texts: List<Str> = List<Str>()
    var elements: Int = 0
    var c: Int = 0
    while (c < node.Children.count()) {
        val child: XmlNode = node.Children[c]
        if (child.name == "#text") {
            texts.append(child.textValue())
        } else {
            elements++
        }
        c++
    }
    if (elements == 0) {
        if (texts.size() == 0) {
            out.appendStr("/>\n")
            return out
        }
        out.append('>')
        var t: Int = 0
        while (t < texts.size()) {
            out.appendStr(xmlEscape(texts[t]))
            t++
        }
        out.appendStr("</")
        out.appendStr(node.name)
        out.appendStr(">\n")
        return out
    }
    out.appendStr(">\n")
    var k: Int = 0
    while (k < node.Children.count()) {
        val child: XmlNode = node.Children[k]
        if (child.name == "#text") {
            out.appendStr(pad)
            out.appendStr(indent)
            out.appendStr(xmlEscape(child.textValue()))
            out.append('\n')
        } else {
            out.appendStr(xmlWriteLevel(child, indent, depth + 1))
        }
        k++
    }
    out.appendStr(pad)
    out.appendStr("</")
    out.appendStr(node.name)
    out.appendStr(">\n")
    return out
}
