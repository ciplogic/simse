# XmlNode AST schema

Status: proposed and implemented for the C++ AST (T15). A Simse parser that
builds this schema directly is the next round.

The AST in `cppsrc/ast/Ast.h` is converted to an `XmlNode` tree
(`specs/xml-node.md`) by `ast::toXmlNode`, and `ast::dumpXmlNode` renders it
deterministically. This document is the schema; goldens live in
`tests/golden/<fixture>.astxml.expected` and are checked by `simse_tests`.

## Node model

Every AST element is one `XmlNode` with three parts:

- **name** - the structural role of the node (for example `Module`, `DataClass`,
  `Stmt`, `Type`, `Arg`, `Init`). Names are stable and come from a fixed set.
- **attributes** - an ordered `List<Attribute>` of string key/value pairs. All
  scalars are stringly typed: numbers are decimal text, booleans are `true` or
  `false`, and `kind` distinguishes node categories.
- **Children** - a `PList<XmlNode>` holding sub-nodes in source order.

Attributes are emitted in a fixed order, so the dump is deterministic. Every node
that corresponds to a source construct with a position carries `line` and
`column` attributes first (after `kind`, when present).

## Kinds

`kind` is one of:

- declarations: `Module`, `Import`, `DataClass`, `Enum`, `TypeAlias`, `Function`;
- statements: `Stmt.VarDecl`, `Stmt.Assign`, `Stmt.If`, `Stmt.While`,
  `Stmt.Switch`, `Stmt.Return`, `Stmt.Break`, `Stmt.Continue`, `Stmt.ExprStmt`;
- linear forms (produced only by the lowering pass, `impl_specs/linear-lowering.md`,
  never by the parser): `Stmt.Label`, `Stmt.Goto`, `Stmt.IfTrue`, `Stmt.IfFalse`,
  `Stmt.Block`;
- expressions: `Expr.` plus `IntLit`, `FloatLit`, `StrLit`, `CharLit`, `BoolLit`,
  `NullLit`, `Name`, `GenericName`, `Member`, `Call`, `Index`, `Unary`, `Binary`,
  `Lambda`, `Ref`, `Deref`, `Copy`;
- types: `Type.` plus `IntLit`, `Named`, `Generic`, `Reference`, `Pointer`,
  `Function`.

Container nodes (`Then`, `Else`, `Body`, `Case`) have no `kind`; they exist only
to group statements and to carry a role.

## `Module` and `Import`

- `Module` - attributes `kind`, `line`, `column`, and `package` (the dotted package name, e.g. `lex`). Every file declares exactly one package, so `package` is always present; a programmatically built module with no package emits an empty value. Children: zero or more `Import` nodes, then the declaration nodes, in source order.
- `Import` - attributes `path` (the dotted import, e.g. `lex`), `line`,
  `column`. No children.

## Declarations

- `DataClass` - attributes `kind`, `line`, `column`, `name`. Children:
  `TypeParam` nodes (attribute `name`), then `Field` nodes, then `Function`
  method nodes.
- `Enum` - attributes `kind`, `line`, `column`, `name`. Children: `TypeParam`
  nodes, then `EnumMember` nodes (attributes `name`, `hasValue`, `value`, `line`,
  `column`).
- `TypeAlias` - attributes `kind`, `line`, `column`, `name`. Children:
  `TypeParam` nodes, then a `TargetType` type node.
- `Function` - attributes `kind`, `line`, `column`, `name`, `isNative`,
  `hasBody`, `hasReceiver`, `hasNativeSymbol` (and `nativeSymbol` when present).
  Children in order: a `Receiver` type node (when `hasReceiver`), `TypeParam`
  nodes, `Param` nodes, a `ReturnType` type node (when present), and a `Body`
  container (when `hasBody`).
- `Field` - attributes `name`, `isVar`, `line`, `column`. Child: a `Type` type
  node (when a type is written).
- `Param` - attributes `name`, `line`, `column`. Child: a `Type` type node.

## Statements

Statements appear as `Stmt` nodes (name `Stmt`, attribute `kind`). Their children
use role names:

- `Stmt.VarDecl` - attributes `name`, `isVar`. Children: `Type` (when annotated),
  `Init` (when initialized).
- `Stmt.Assign` - attribute `op`. Children: `Target`, `Value`.
- `Stmt.If` - children: `Cond`, `Then` (a container of `Stmt` nodes), `Else`
  (when present).
- `Stmt.While` - children: `Cond`, `Body`.
- `Stmt.Switch` - child `Cond`, then one `Case` node per arm. A `Case` has
  attribute `isDefault`, an optional `Label` expression child, then its `Stmt`
  children.
- `Stmt.Return` - child `Value` (when a value is returned).
- `Stmt.Break`, `Stmt.Continue` - no children.
- `Stmt.ExprStmt` - child `Expr`.

After the linear lowering pass (`impl_specs/linear-lowering.md`) a body contains
only these statement forms:

- `Stmt.Label` - attribute `name` (the label text).
- `Stmt.Goto` - attribute `name` (the jump target).
- `Stmt.IfTrue`/`Stmt.IfFalse` - attribute `name` (the jump target), child `Cond`.
- `Stmt.Block` - child `Body` (a container of statements), emitted as `{ ... }`.
- the plain statements above (VarDecl, Assign, Return, ExprStmt).

## Expressions

Expressions are `kind`-tagged nodes named by their role. Scalar attributes:

- `Expr.IntLit`/`Expr.FloatLit`/`Expr.StrLit`/`Expr.CharLit` - attribute `text`
  (the raw token text, including quotes for strings/chars).
- `Expr.BoolLit` - attribute `value` (`true`/`false`).
- `Expr.NullLit` - no scalar attributes.
- `Expr.Name` - attribute `name`.
- `Expr.GenericName` - attribute `name`; `TypeArg` type children.
- `Expr.Member` - attribute `name`; child `Receiver`.
- `Expr.Call` - child `Callee`, then one `Arg` per argument.
- `Expr.Index` - children `Receiver`, `Index`.
- `Expr.Unary` - attribute `op`; child `Operand`.
- `Expr.Binary` - attribute `op`; children `Lhs`, `Rhs`.
- `Expr.Lambda` - attribute `params` (comma-joined names); `ParamType` type
  children and a `Body` container.
- `Expr.Ref`/`Expr.Deref`/`Expr.Copy` - child `Operand`.

## Types

A type node is named by its role (`Type`, `Inner`, `TypeArg`, `ParamType`,
`ReturnType`, `TargetType`, `Receiver`) and carries `kind`:

- `Type.IntLit` - attribute `text` (an integer type argument such as `4`).
- `Type.Named` - attribute `name`.
- `Type.Generic` - attribute `name`; one `TypeArg` child per type argument.
- `Type.Reference` - child `Inner`.
- `Type.Pointer` - child `Inner`.
- `Type.Function` - one `ParamType` child per parameter, then `ReturnType` (when
  present).

## Dump format

`dumpXmlNode` renders one line per node, two spaces of indent per depth:

```
<indent><name> <key>='<value>' <key>='<value>' ...
```

Values are escaped like the token goldens, plus `'` as `\'`. Children follow,
indented one level deeper. Example for `data class Tile(var n: Int)`:

```
Module kind='Module' line='1' column='1' package='fixtures'
  DataClass kind='DataClass' line='1' column='1' name='Tile'
    Field name='n' isVar='true' line='1' column='17'
      Type kind='Type.Named' line='1' column='24' name='Int'
```

## Tradeoffs and limits

- **Attributes are stringly typed.** Every scalar becomes text, so a consumer
  must parse numbers/booleans back out. This keeps one uniform node type with no
  inheritance, matching the current language subset, at the cost of losing the
  compiler's static typing at the AST boundary.
- **Roles are carried by element names, not types.** Ambiguity is avoided by the
  fixed role set above; there is no schema validation in the language yet.
- **Positions are line/column only.** The byte `offset` from `SourcePos` is not
  carried (it is derivable from the text but not recorded).
- **Everything fits.** All current AST constructs have a schema form; nothing had
  to be dropped. The only awkward part is that role information (for example
  `Init` vs `Value`) lives in the element name, so a consumer switches on the
  name as well as the `kind` attribute.
