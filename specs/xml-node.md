# `XmlNode`, parse/debug tree nodes

Status: design baseline — a shared recursive tree node type for parse trees and
debugging.

`XmlNode` is a small recursive tree node built from the value types already
described in this spec set. It is used both to represent parse trees and to
render/debug hierarchies. Because the current language subset has no
inheritance or dynamic dispatch, one node type is composed from existing
declared types instead of a hierarchy of specialized subclasses. The node's tag
and attributes carry the information that would otherwise live in a subtype.

## `Attribute`

```text
data class Attribute(var name: Str, var value: Str)
```

`Attribute` pairs an attribute/element name with a `Str` value. Both fields are
mutable so a parser or debugger can update them. `name` identifies the
attribute; `value` is its text.

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
- `attributes: List<Attribute>` — a plain value `List` of `Attribute`. Because
  it is a value `List`, each node owns its own attribute list and copying the
  node deep-copies it. `Attribute` is small, so this is cheap.
- `Children: Array<XmlNode>` — a **fixed-length, reference-counted block** of
  child nodes (`built-in-types.md`). The node holds a handle to the block, so
  the child *values* are never embedded in the node; assignment shares the block
  and increments its count. A node with no children holds the **shared empty
  array**, so a leaf node allocates nothing at all.

The leading-uppercase `Children` field name is intentional and matches the
requested spelling; it otherwise follows the normal `var`/`val` field rules.
All three fields are `var`: a tree is built by assigning children to the node
(see *Construction* below), and the name and attributes may be set as well.

### Why `Children` is an array handle, not a plain `List`

A plain value `List<XmlNode>` for the children would make `XmlNode`
recursive-by-value: `XmlNode` contains `List<XmlNode>` contains `XmlNode` ...,
which cannot be given a finite size. `Array<XmlNode>` breaks that cycle: it is
itself a reference type (a counted handle to one block), so the node contains a
handle rather than the child values.

An array rather than a list is a statement about how a tree is shaped and how it
is built:

- a node's children are **fixed once the node is built**, and the shape that
  matters at run time is one allocation holding the child count first and the
  children after it (`built-in-types.md`) — not a growable buffer per node;
- a leaf node costs **no allocation**: its handle points at the shared empty
  array of `XmlNode`;
- a node with children costs **exactly one allocation** for them, made when the
  children are frozen from a list (below).

Copying an `XmlNode` value deep-copies `name` (`Str` value semantics) and
`attributes` (`List` value semantics), and **shares** `Children` by incrementing
the block's reference count instead of recursively copying the subtree.

### Mutability and sharing consequences

`Array<T>` is a reference type with a fixed length: the array's *elements* are
mutable, the *count* is not, and there is no array operation that grows one.
Adding a child to a node is therefore an assignment to `Children`, and the
idiom is the documented one for arrays — go through a list and freeze it back:

```text
fun addChild(node: *XmlNode, child: XmlNode) {
    var children: List<XmlNode> = node.Children.toList()
    children.append(child)
    node.Children = children.toArray()
}

var root = XmlNode("module", List<Attribute>(), arrayEmpty<XmlNode>())
addChild(&root, XmlNode("declaration", List<Attribute>(), arrayEmpty<XmlNode>()))
```

A consequence of value semantics: the append above replaces `root.Children` with
a **new** block, so a handle taken earlier still points at the old children:

```text
var before: Array<XmlNode> = root.Children
addChild(&root, leaf)
before.count()            // unchanged: the children as they were
root.Children.count()     // one more
```

A batch of children is built as a list and frozen once, which is one allocation
for the whole set:

```text
var children: List<XmlNode> = List<XmlNode>()
children.append(first)
children.append(second)
var node = XmlNode("expression", attrs, children.toArray())
```

To share the whole node (name, attributes, and its children) rather than only
the children block, take an explicit counted reference with `&XmlNode`:

```text
val alias: &XmlNode = &root   // boxed copy sharing identity via the & box
```

A well-formed parse or debug tree is acyclic. Rendering a node is a recursive
walk over `Children` that terminates because each child is a distinct subtree;
cycles are not expected and are not handled specially by the baseline
definition.

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