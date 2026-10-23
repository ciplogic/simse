package xml

// query.kt - the readers: attribute and child lookups, an element's text, and the two
// walks. These are the functions a parsed tree is actually consumed with; the parser
// (parse.kt) and the writer (write.kt) are the plumbing around them.

// The attribute's value, or none. The `#text` node's `text` attribute is found the same
// way, which is why it is spelled like any other attribute.
fun XmlNode.attribute(name: Str): Opt<Str> {
    var i: Int = 0
    while (i < this.attributes.size()) {
        if (this.attributes[i].name == name) {
            return Opt<Str>.some(this.attributes[i].value)
        }
        i++
    }
    return Opt<Str>.none()
}

// The attribute's value or `fallback`: the shape most call sites want.
fun XmlNode.attributeOr(name: Str, fallback: Str): Str {
    val found: Opt<Str> = this.attribute(name)
    if (found.hasValue()) {
        return found.value()
    }
    return fallback
}

// The first child element with this name, or none. `#text` children never match - they
// are characters, not elements.
fun XmlNode.child(name: Str): Opt<XmlNode> {
    var i: Int = 0
    while (i < this.Children.count()) {
        if (this.Children[i].name == name) {
            return Opt<XmlNode>.some(this.Children[i])
        }
        i++
    }
    return Opt<XmlNode>.none()
}

// The element's own characters: every `#text` child concatenated, in document order. An
// element without text answers the empty string.
fun XmlNode.textOf(): Str {
    var out: Str = Str()
    var i: Int = 0
    while (i < this.Children.count()) {
        if (this.Children[i].name == "#text") {
            var a: Int = 0
            while (a < this.Children[i].attributes.size()) {
                if (this.Children[i].attributes[a].name == "text") {
                    out.appendStr(this.Children[i].attributes[a].value)
                }
                a++
            }
        }
        i++
    }
    return out
}

// The child elements, skipping `#text`: the walk a `for` over `Children` would need a
// name test for. A pointer machine like the `linq` module's sources, so a pipeline can
// start here (`node.elements().where { ... }`).
fun XmlNode.elements(): ..*XmlNode {
    var i: Int = 0
    while (i < this.Children.count()) {
        var child: XmlNode = this.Children[i]
        if (child.name != "#text") {
            yield *child
        }
        i++
    }
}

// A `#text` node's characters (its `text` attribute). An element answers its own text
// with `textOf()`; a `#text` node answers it with this.
fun XmlNode.textValue(): Str {
    return this.attributeOr("text", "")
}

// The child elements as a `List`: the copy a plain `for` walks. `elements()` is the
// zero-copy walk, but a source machine reads its best in a pipeline; a loop that just
// wants the children wants this.
fun XmlNode.elementList(): List<XmlNode> {
    var out: List<XmlNode> = List<XmlNode>()
    var i: Int = 0
    while (i < this.Children.count()) {
        if (this.Children[i].name != "#text") {
            out.append(this.Children[i])
        }
        i++
    }
    return out
}

// Every descendant element with this name, in document order - the recursive find. The
// matches are *copies* (a `List` value), since a machine cannot recurse; a subtree that
// must be kept is already a value.
fun XmlNode.collect(name: Str): List<XmlNode> {
    var out: List<XmlNode> = List<XmlNode>()
    xmlCollectInto(this, name, *out)
    return out
}

fun xmlCollectInto(node: XmlNode, name: Str, out: *List<XmlNode>): Unit {
    var i: Int = 0
    while (i < node.Children.count()) {
        val child: XmlNode = node.Children[i]
        if (child.name != "#text") {
            if (child.name == name) {
                out.append(child)
            }
            xmlCollectInto(child, name, out)
        }
        i++
    }
}
