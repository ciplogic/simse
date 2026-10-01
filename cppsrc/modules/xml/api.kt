package xml

// The XmlNode surface (specs/xml-node.md): the recursive parse/debug tree a program builds.
// `Attribute` and `XmlNode` are *generated* from these declarations (specs/attributes.md's
// type materialization), like the compiler's own `AstXmlNode` (cppsrc/rtl/astxml.kt) - there
// is no hand-written header for them.
//
// This is the `xml` module's own copy: a program that `import xml` gets these declarations in
// place of the `rtl` prelude's (`cppsrc/rtl/xml.kt`), an explicit import winning over the
// implicit `rtl` (specs/modules.md, "Resolution").

// An attribute/element name paired with its `Str` value.
data class Attribute(var name: Str, var value: Str)

// `Children` is a ref-counted `Array<XmlNode>` (child count first): the reference is what
// breaks the recursion, so the type is finite and a node with no children points at the shared
// empty array instead of allocating (specs/built-in-types.md, specs/xml-node.md).
data class XmlNode(var name: Str, var attributes: List<Attribute>, var Children: Array<XmlNode>)
