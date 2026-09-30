// xml.kt
//
// The XmlNode surface (specs/xml-node.md): the recursive parse/debug tree a program builds,
// mapped onto the RTL types so a program can use it without an import. `Attribute` and
// `XmlNode` are *generated* from these declarations (specs/attributes.md's type
// materialization), like the compiler's own `AstXmlNode` (cppsrc/rtl/astxml.kt) - there is no
// hand-written header for them.

package rtl

// An attribute/element name paired with its `Str` value.
data class Attribute(var name: Str, var value: Str)

// `Children` is a ref-counted `Array<XmlNode>` (child count first): the reference is what
// breaks the recursion, so the type is finite and a node with no children points at the shared
// empty array instead of allocating (specs/built-in-types.md, specs/xml-node.md).
data class XmlNode(var name: Str, var attributes: List<Attribute>, var Children: Array<XmlNode>)
