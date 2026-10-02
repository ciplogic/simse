package fixtures

import xml

// The `xml` module supplies `XmlNode`/`Attribute` (src/modules/xml/api.kt). The types are
// not in the `rtl` prelude, so a program reaches them through the module - `import xml` makes
// them visible unqualified, and the module itself is named on the command line
// (`compiler-args`: `--module src/modules/xml`) or by a `simse.md` manifest. This case is
// what carries the language-level XmlNode coverage.

// Builds a node, then reads a field, an attribute value and a child.
fun main(): Int {
    val leaf: XmlNode = XmlNode("Leaf", List<Attribute>(), Array<XmlNode>())
    var kids: List<XmlNode> = List<XmlNode>()
    kids.append(leaf)
    val node: XmlNode = XmlNode("root", listOf<Attribute>(Attribute("id", "7")), kids.toArray())
    println(node.name)
    println(node.attributes[0].value)
    println(node.Children[0].name)
    partXmlTree()
    return 0
}

// ---- xml-tree ----
// Builds, by hand, the XmlNode tree for `data class Tile(var n: Int)` and dumps it in the
// canonical XmlNode format: attributes on one line, children indented two spaces per depth,
// the text escaped. `dumpNode` recurses over `Children`, an `Array<XmlNode>`
// (specs/xml-node.md).

fun attr(name: Str, value: Str): Attribute {
    return Attribute(name, value)
}

fun attrs3(a: Attribute, b: Attribute, c: Attribute): List<Attribute> {
    var list: List<Attribute> = List<Attribute>()
    list.append(a)
    list.append(b)
    list.append(c)
    return list
}

fun attrs4(a: Attribute, b: Attribute, c: Attribute, d: Attribute): List<Attribute> {
    var list: List<Attribute> = List<Attribute>()
    list.append(a)
    list.append(b)
    list.append(c)
    list.append(d)
    return list
}

// `Children` is an `Array<XmlNode>` (specs/xml-node.md): the two builders below
// are the language-level way to make one - an empty array is the shared
// zero-length block, and an array with elements is frozen from a list.
fun noChildren(): Array<XmlNode> {
    return Array<XmlNode>()
}

fun oneChild(child: XmlNode): Array<XmlNode> {
    var children: List<XmlNode> = List<XmlNode>()
    children.append(child)
    return children.toArray()
}

fun escapeText(text: Str): Str {
    var out: Str = Str()
    var i: Int = 0
    while (i < text.size()) {
        val ch: Char = text[i]
        if (ch == '\\') {
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
        } else if (ch == '\'') {
            out.append('\\')
            out.append('\'')
        } else {
            out.append(ch)
        }
        i = i + 1
    }
    return out
}

fun indentation(depth: Int): Str {
    var out: Str = Str()
    var i: Int = 0
    while (i < depth * 2) {
        out.append(' ')
        i = i + 1
    }
    return out
}

fun dumpNode(node: XmlNode, depth: Int): Str {
    var out: Str = indentation(depth) + node.name
    var i: Int = 0
    while (i < node.attributes.size()) {
        val attribute: Attribute = node.attributes[i]
        out = out + " " + attribute.name + "='" + escapeText(attribute.value) + "'"
        i = i + 1
    }
    out = out + "\n"

    val children: Array<XmlNode> = node.Children
    var j: Int = 0
    while (j < children.count()) {
        out = out + dumpNode(children[j], depth + 1)
        j = j + 1
    }
    return out
}

fun partXmlTree(): Int {
    val typeNode: XmlNode = XmlNode(
        "Type",
        attrs4(attr("kind", "Type.Named"), attr("line", "1"), attr("column", "24"), attr("name", "Int")),
        noChildren()
    )
    val fieldNode: XmlNode = XmlNode(
        "Field",
        attrs4(attr("name", "n"), attr("isVar", "true"), attr("line", "1"), attr("column", "17")),
        oneChild(typeNode)
    )
    val dataClassNode: XmlNode = XmlNode(
        "DataClass",
        attrs4(attr("kind", "DataClass"), attr("line", "1"), attr("column", "1"), attr("name", "Tile")),
        oneChild(fieldNode)
    )
    val moduleNode: XmlNode = XmlNode(
        "Module",
        attrs3(attr("kind", "Module"), attr("line", "1"), attr("column", "1")),
        oneChild(dataClassNode)
    )

    print(dumpNode(moduleNode, 0))
    return 0
}
