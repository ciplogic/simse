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
data class Attribute(var name: Str; var value: Str)
```

`Attribute` pairs an attribute/element name with a `Str` value. Both fields are
mutable so a parser or debugger can update them. `name` identifies the
attribute; `value` is its text.

## `XmlNode`

```text
data class XmlNode(
    var name: Str;
    var attributes: List<Attribute>;
    var Children: PList<XmlNode>
)
```

The three parts of a node are stored as follows:

- `name: Str` — the tag or element name as an inline `Str` value. `Str` is a
  value type and is deep-copied when the node is copied.
- `attributes: List<Attribute>` — a plain value `List` of `Attribute`. Because
  it is a value `List`, each node owns its own attribute list and copying the
  node deep-copies it. `Attribute` is small, so this is cheap.
- `Children: PList<XmlNode>` — a **shared** list of child nodes. `PList<T>` is
  the alias `&List<T>` declared in `memory-model.md`: a counted, ref-counted
  reference to a `List<T>`. `Children` therefore points at a heap-backed child
  list rather than containing it inline.

The leading-uppercase `Children` field name is intentional and matches the
requested spelling; it otherwise follows the normal `var`/`val` field rules.
All three fields are `var`: parse trees are built up incrementally, so the name,
attributes, and children may all be mutated as the tree is constructed and
rewritten.

### Why `Children` is a shared reference, not a plain `List`

Using a plain value `List<XmlNode>` for the children would make `XmlNode`
recursive-by-value: `XmlNode` contains `List<XmlNode>` contains `XmlNode` ...,
which cannot be given a finite size. Making the children a counted reference
`&List<XmlNode>` breaks that cycle: the node holds a handle to a boxed list
instead of embedding the children inline.

A second, intentional consequence follows from sharing. Copying an `XmlNode`
value:

- deep-copies `name` (`Str` value semantics) and `attributes` (`List` value
  semantics); but
- **shares** `Children` by incrementing the reference count of the child list,
  rather than recursively deep-copying the whole subtree.

This is the behavior wanted for parse trees and debug output: parent nodes
reference the same child subtrees, and inserting or rebuilding one node does not
trigger a deep copy of everything beneath it. `Attribute` is not shared because
it is small and belongs to exactly one node.

### Mutability and sharing consequences

Because `Children` is a counted reference to a mutable `List`, mutating it
through one handle is visible through every other handle to the same child
list:

```text
var root = XmlNode("module", List<Attribute>(), &List<XmlNode>())

var sharedWithRoot: PList<XmlNode> = root.Children
root.Children.append(XmlNode("declaration", List<Attribute>(), &List<XmlNode>()))
// sharedWithRoot observes the new child too: both reference the same list
```

To share the whole node (name, attributes, and its child list) rather than only
the children list, take an explicit counted reference with `&XmlNode`:

```text
val alias: &XmlNode = &root   // boxed copy sharing identity via the & box
```

A well-formed parse or debug tree is acyclic. Rendering a node is a recursive
walk over `Children` that terminates because each child is a distinct subtree;
cycles are not expected and are not handled specially by the baseline
definition.

## Construction

The children field is initialized with `&List<XmlNode>()`: the `&` operator
boxes a copy of the empty `List<XmlNode>()` value into a fresh, non-null
counted reference, producing a `PList<XmlNode>`.

```text
var leaf = XmlNode("identifier", List<Attribute>(), &List<XmlNode>())
var branch = XmlNode("expression", List<Attribute>(), &List<XmlNode>())

branch.attributes.append(Attribute("line", "42"))
branch.Children.append(leaf)
```

## Reification

As with every generic, `List<Attribute>` and `List<XmlNode>` are distinct
reified concrete types in the generated output (`generics.md`), and
`PList<XmlNode>` (`&List<XmlNode>`) is a counted reference to the reified
`List<XmlNode>` type. `Attribute` and `XmlNode` are each distinct `data class`
types with inline value layout (`declarations.md`). Because `Children` is a
reference type, the generated `XmlNode` does not recursively embed `XmlNode`
values; its children are reached through the counted child-list reference.