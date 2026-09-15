// xml.kt
//
// The XmlNode surface (specs/xml-node.md). Declarations only: the concrete types
// and their constructors live in cppsrc/rtl/xml.hpp. This file is loaded as part
// of the prelude set, so programs can build XmlNode trees without an import and
// the transpiler maps `Attribute`, `XmlNode`, and `Array<XmlNode>` onto the RTL
// types.

package rtl

// Attribute pairs an attribute/element name with a Str value.
data class Attribute(var name: Str, var value: Str)

// XmlNode is the recursive parse/debug tree node. `Children` is an
// `Array<XmlNode>` (specs/built-in-types.md): one reference-counted block whose
// first element is the child count, so the type stays finite (the node holds a
// handle to the block, not child values inline) and a node with no children
// points at the shared empty array instead of allocating.
data class XmlNode(var name: Str, var attributes: List<Attribute>, var Children: Array<XmlNode>)
