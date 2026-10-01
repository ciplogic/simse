package fixtures

import xml

// The `xml` module supplies `XmlNode`/`Attribute` (cppsrc/modules/xml/api.kt). The `rtl`
// prelude declares the same names (`cppsrc/rtl/xml.kt`), so this case is what proves the
// shadowing: the explicit `import xml` must win over the implicit `rtl`, and the program must
// build and run against the module's declarations.

fun main(): Int {
    val leaf: XmlNode = XmlNode("Leaf", List<Attribute>(), Array<XmlNode>())
    var kids: List<XmlNode> = List<XmlNode>()
    kids.append(leaf)
    val node: XmlNode = XmlNode("root", listOf<Attribute>(Attribute("id", "7")), kids.toArray())
    println(node.name)
    println(node.attributes[0].value)
    println(node.Children[0].name)
    return 0
}
