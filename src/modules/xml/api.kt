package xml

// The XmlNode surface (specs/xml-node.md): the recursive parse/debug tree a program builds.
// `Attribute` and `XmlNode` are *generated* from these declarations (specs/attributes.md's
// type materialization), like the compiler's own `AstXmlNode` (src/modules/compiler/astxml.kt) - there
// is no hand-written header for them.
//
// This is the *only* declaration of the two types: they are not in the `rtl` prelude, so a
// program that wants them writes `import xml` and names this module on the compiler command
// line (`--module src/modules/xml`) or in a `simse.md` manifest (specs/modules.md).

// An attribute/element name paired with its `Str` value.
data class Attribute(var name: Str, var value: Str)

// `Children` is a ref-counted `Array<XmlNode>` (child count first): the reference is what
// breaks the recursion, so the type is finite and a node with no children points at the shared
// empty array instead of allocating (specs/built-in-types.md, specs/xml-node.md).
data class XmlNode(var name: Str, var attributes: List<Attribute>, var Children: Array<XmlNode>)
