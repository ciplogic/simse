# Attributes

Status: design baseline - attributes and the `@Identifier` token. Implemented as
described, for the placement and repetition rules below.

## The `@Identifier` token

`@` immediately followed by an identifier is one token: an **attribute token**
(`TokenKind.Attribute`) whose name is the identifier (`@SmGen`, `@Json`). The token's
text keeps the `@` - it is the matched slice - and the parser strips it. `@` not
followed by an identifier is a scanner error ("Unexpected character"), because no other
rule accepts it. Attribute names are matched literally and are case-sensitive.

## Syntax

An attribute is written `@Name` or `@Name(arg, arg, ...)`. Each argument is a
**literal**: a string literal or an integer literal. Empty parentheses are allowed.
Whitespace inside the parentheses is insignificant, as everywhere else, so an argument
list may be wrapped across lines.

An attribute may sit on the declaration's own line or on the line above it: the
separators (`;`, end of line) between the attribute and the `fun` it belongs to are
skipped.

## Placement

An attribute precedes a declaration. In this baseline only **method** declarations take
attributes; attributes on types, fields, parameters, and statements are deferred, and
an attribute anywhere else (a file-level `var`, a `data class` member, ...) is a parse
error.

A method that carries an attribute **must omit its body**, ending at `;` or the line,
exactly like a `native` declaration: the generator owns the C++, so a body would be
dead code (diagnostic: "a method whose C++ is generated must not have a body"). The
converse also holds - a body-less method that is not attributed has no
implementation at all ("a body-less method needs an attribute").

## What the parser records

`Attribute` (the attribute's own name), `Generator` (the generator the first argument
names: `cpp`, `res`, `kt`) and `GeneratorArgs` (the remaining arguments, in order, joined by
`,`, a string literal without its quotes) on the `Function` node.

A generator whose text is not emitted at the declaration - `cpp` and `res` - names the C++
symbol a call reaches as one of its own arguments: `cpp`'s *second* (`res` names its section
first, so its symbol is the *third*), and the declaration carries it as
`NativeSymbol`/`HasNativeSymbol`: a pass that reads the
declaration without the emitter's tables reads the symbol there (the `listOf<T>` list
literal, whose call is a `Pack` rather than a call, is the one that does).

## Repetition

One attribute per declaration in this baseline. Stacking several attributes on one
declaration is deferred.

## Meaning

An attribute does not change the declaration's type or its visibility. It selects an
implementation strategy for the declaration, which the compiler resolves as described in
`impl_specs/generators.md`.
