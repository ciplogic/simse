# Attributes

Status: design baseline - attributes and the `@Identifier` token. Implemented as
described, for the placement and repetition rules below.

## The `@Identifier` token

`@` immediately followed by an identifier is one token: an attribute token
(`TokenKind.Attribute`) whose name is the identifier (`@SmGen`, `@Json`). The token's
text keeps the `@` and the parser strips it. `@` not followed by an identifier is a
scanner error ("Unexpected character"). Attribute names are matched literally and are
case-sensitive.

## Syntax

An attribute is written `@Name` or `@Name(arg, arg, ...)`. Each argument is a literal: a
string literal or an integer literal. Empty parentheses are allowed. Whitespace inside
the parentheses is insignificant, so an argument list may be wrapped across lines.

An attribute may sit on the declaration's own line or on the line above it: the
separators (`;`, end of line) between the attribute and the `fun` it belongs to are
skipped.

## Placement

An attribute precedes a declaration: a method or a **type** (`data class`, `enum class`).
In this baseline attributes on fields, parameters, and statements are deferred, and an
attribute anywhere else (a file-level `var`, a `data class` field, a parameter, ...) is a
parse error.

A method that carries an attribute must omit its body, ending at `;` or the line: the
generator owns the C++ (diagnostic: "a method whose C++ is generated must not have a
body"). The converse holds: a body-less method that is not attributed has no
implementation ("a body-less method needs an attribute").

## Marker attributes

Beside the generator attributes there are **marker attributes** - `@Test`
(`specs/testing.md`) is the first - which annotate a declaration instead of naming a
C++ source generator. A marker takes no arguments (diagnostic: "'@Test' takes no
arguments"), marks a function only, not a type ("'@Test' marks a function"), and the
marked function keeps its body ("a function marked '@Test' needs a body"): it is an
ordinary function whose `Attribute` records the mark and whose `Generator` is empty, so
no generator, native symbol, or materialization is involved. A build that does not read
the mark ignores it - `@Test` compiles in an ordinary program and the function is never
called unless something calls it.

## What the parser records

The parser records on the `Function` node: `Attribute` (the attribute's own name),
`Generator` (the generator the first argument names: `cpp`, `res`, `kt`, `json`), and
`GeneratorArgs` (the remaining arguments, in order, joined by `,`, a string literal
without its quotes).

A type declaration records the same three on its `DataClass`/`Enum` node.

A generator whose text is not emitted at the declaration - `cpp` and `res` - names the C++
symbol a call reaches as one of its own arguments: `cpp`'s second (`res` names its section
first, so its symbol is the third), and the declaration carries it as
`NativeSymbol`/`HasNativeSymbol`, so a pass that reads the declaration without the
emitter's tables reads the symbol there (the `listOf<T>` literal, whose call is a `Pack`
rather than a call, is the one that does).

A trailing argument `"mutates"` (either generator) is the author's word that the callee's
writes reach **only its receiver** - the three-argument form
`@SmGen("res", "listops", "simse_str_append", "mutates")`, for instance. The auto-borrow
proof reads it (`impl_specs/escape-analysis.md`, "The borrowness fixpoint"): a body calling
such a callee *on a local the body itself built* is not dirtied by the call, because the
storage the callee writes is the body's own and no caller lent it. It says nothing about
the callee's purity and nothing about calls whose receiver is borrowed - both stay exactly
as they were.

## Repetition

One attribute per declaration in this baseline. Stacking several attributes on one
declaration is deferred.

## Meaning

An attribute does not change the declaration's type or its visibility. It selects an
implementation strategy for the declaration, which the compiler resolves as described in
`impl_specs/generators.md`.

On a **type**, the attribute selects the type's *materialization*. `@SmGen("cpp")` says the
C++ is a hand-written header that is already included, and `@SmGen("res", "section")` that
it is a resource section; either way the emitter does **not** generate the struct, and the
header/section defines it. A `data class`/`enum class` **without** an attribute is
*generated* from its declaration like a program's, so a type whose layout only C++ can
express (`Str`, `Span`, `FileStream`, ...) carries its C++ explicitly, while a
type Simse can express (the compiler's own `AstXmlNode`, in `modules/compiler/astxml.kt`, or
the language-level `XmlNode`, in `modules/xml/api.kt`) is written in Simse alone. A prelude type is generated only when the program *reaches* it
(naming it, or naming a type it holds), never into a program that does not use it.
