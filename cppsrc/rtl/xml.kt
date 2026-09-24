// xml.kt
//
// The XmlNode surface (specs/xml-node.md): prelude declarations for the recursive
// parse/debug tree, mapped onto the RTL types so programs can build one without an import.

package rtl

data class Attribute(var name: Str, var value: Str)

// `Children` is a ref-counted `Array<XmlNode>` (child count first), so the type is finite
// and a node with no children points at the shared empty array instead of allocating
// (specs/built-in-types.md).
data class XmlNode(var name: Str, var attributes: List<Attribute>, var Children: Array<XmlNode>)
