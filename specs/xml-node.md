# `XmlNode`, parse/debug tree nodes

Status: design baseline — a shared recursive tree node type for parse trees and
debugging.

`XmlNode` is a small recursive tree node built from the value types in this spec set,
used both to represent parse trees and to render/debug hierarchies. The language subset
has no inheritance or dynamic dispatch, so one node type is composed from existing
declared types; its tag and attributes carry what would otherwise live in a subtype.

## `Attribute`

```text
data class Attribute(var name: Str, var value: Str)
```

`Attribute` pairs a name with a `Str` value: `name` identifies the attribute, `value` is
its text.

## `XmlNode`

```text
data class XmlNode(
    var name: Str;
    var attributes: List<Attribute>;
    var Children: Array<XmlNode>
)
```

The three parts of a node are stored as follows:

- `name: Str` — the tag or element name as an inline `Str` value. `Str` is a
  value type and is deep-copied when the node is copied.
- `attributes: List<Attribute>` — a value `List`: each node owns its own attribute list and
  copying the node deep-copies it.
- `Children: Array<XmlNode>` — a **fixed-length, reference-counted block** of
  child nodes (`built-in-types.md`). The node holds a handle to the block, so
  the child *values* are never embedded in the node; assignment shares the block
  and increments its count. A node with no children holds the **shared empty
  array**, so a leaf node allocates nothing at all.

`Children` is spelled with a leading uppercase letter deliberately and otherwise follows
the normal `var`/`val` field rules. All three fields are `var`: a tree is built by
assigning children (see *Construction* below), and the name and attributes may be set too.

### Why `Children` is an array handle, not a plain `List`

A value `List<XmlNode>` for the children would make `XmlNode` recursive-by-value:
`XmlNode` contains `List<XmlNode>` contains `XmlNode` ..., which has no finite size.
`Array<XmlNode>` breaks the cycle: it is a reference type, so the node contains a
counted handle rather than the child values.

- a node's children are fixed once the node is built, and the run-time shape is one
  allocation holding the child count first and the children after it
  (`built-in-types.md`) — not a growable buffer per node;
- a leaf node costs no allocation: its handle points at the shared empty array of
  `XmlNode`;
- a node with children costs exactly one allocation for them, made when the children
  are frozen from a list (below).

Copying an `XmlNode` value deep-copies `name` (`Str` value semantics) and `attributes`
(`List` value semantics), and shares `Children` by incrementing the block's reference
count instead of recursively copying the subtree.

### Mutability and sharing consequences

`Array<T>` is a reference type with a fixed length: the array's *elements* are
mutable, the *count* is not, and there is no array operation that grows one.
Adding a child is therefore an assignment to `Children`, through a list frozen back:

```text
fun addChild(node: *XmlNode, child: XmlNode) {
    var children: List<XmlNode> = node.Children.toList()
    children.append(child)
    node.Children = children.toArray()
}

var root = XmlNode("module", List<Attribute>(), arrayEmpty<XmlNode>())
addChild(&root, XmlNode("declaration", List<Attribute>(), arrayEmpty<XmlNode>()))
```

The append above replaces `root.Children` with a new block, so a handle taken earlier
still points at the old children.

A batch of children is built as a list and frozen once (one allocation for the whole
set). To share the whole node (name, attributes, and its children) rather than only the
children block, take an explicit counted reference with `&XmlNode`:

```text
val alias: &XmlNode = &root   // boxed copy sharing identity via the & box
```

A well-formed parse or debug tree is acyclic; rendering is a recursive walk over
`Children` that terminates because each child is a distinct subtree. Cycles are not
handled specially.

## Construction

The children field is initialized with `arrayEmpty<XmlNode>()`, the shared
zero-length array of `XmlNode`, which allocates nothing:

```text
var leaf = XmlNode("identifier", List<Attribute>(), arrayEmpty<XmlNode>())
var branch = XmlNode("expression", List<Attribute>(), arrayEmpty<XmlNode>())

branch.attributes.append(Attribute("line", "42"))
branch.Children = oneChild(leaf)      // a list frozen with toArray()
```

## Reification

As with every generic, `List<Attribute>` and `Array<XmlNode>` are distinct
reified concrete types in the generated output (`generics.md`). `Attribute` and
`XmlNode` are each distinct `data class` types with inline value layout
(`declarations.md`); `Array<XmlNode>` is a counted handle to one block, so the
generated `XmlNode` does not recursively embed `XmlNode` values.