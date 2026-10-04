# Protocols: static interfaces, no vtables

Status: implemented (the first protocol form; `specs/declarations.md`, "Protocols").

## The model

A protocol is a **constraint on a type parameter**, not a type. `fun <T> T.toString(): Str`
declares the contract; `fun f<T>(v: *T) when T: P` requires it; a concrete type satisfies it
when a receiver function matching the signature exists. Nothing is existential: there is no
`P` value, no boxing, no runtime type, and no vtable. Because generics are reified, a call
that instantiates `f<Point>` resolves `v.toString()` to `Point`'s declaration statically.

The compile pipeline carries that in four small pieces:

- **Parser** (`src/parser/ParserDecl.kt`, `ParserType.kt`): `parseProtocol` builds the
  protocol as an ordinary `Function` node with no body plus an `AstNodeAttributeKind.Protocol`
  attribute (its value is the protocol's name; the node's `Name` is the method). The type
  parameters sit after `fun` (`protocol P fun <T, TDest> T.equalsWith(...)`) or after the
  method name, the function spelling (`T.equalsWith<TDest>(...)`); both merge.
  `parseProtocolConstraints` reads a function's `when T: P, Q, U: R` clause into the
  `AstNodeAttributeKind.Protocols` attribute as `param:protocol` items joined by commas.
- **Checker** (`src/sema/Protocols.kt`, `SemaCollect.kt`, `SemaAnalyze.kt`): protocols are
  collected into one program-wide `globalProtocols` table (they are *not* functions or types,
  so neither call resolution nor `resolveType` can see one - that is what makes `val x: P`
  an `unknown type`). `checkProtocolDeclShape` pins the receiver to the first type parameter
  and requires parameter types; `checkProtocolConstraints` reports unknown protocols, unknown
  subjects, and two protocols of one subject declaring the same method; `checkProtocolCall`
  checks every call of a constrained function whose bindings the call site fixes, reporting
  `'Rock' does not satisfy protocol 'Printable': no 'toString' matching '...' is in scope`.
- **Emitter** (`src/codegen/CgProtocol.kt`): the protocol's *dispatch overload set*.

## Dispatch overloads

A protocol call inside a generic body cannot name the implementation: `T` is not known until
C++ instantiates the template. So the emitter writes one overload per implementation under a
fixed name - the protocol's package prefix and name plus `_proto_<method>`, e.g.
`ns1_Printable_proto_toString` - with the implementation's own C++ signature:

```cpp
Str ns1_Printable_proto_toString(ns1_Point* self);           // fun Point.toString()
template <class TOther>                                      // fun Point.equalsWith<TOther>
Bool ns1_Equality_proto_equalsWith(ns1_Point* self, TOther* other);
```

The body forwards to the implementation - its emitted name with its own template arguments,
or a native's symbol (the receiver read through the dispatch pointer, because a native takes
it by value: `simse_int_toString(*self)`). The generic body calls the set by name
(`ns1_Printable_proto_toString(value)`, the receiver via the usual `receiverArg` rules), and
C++ overload resolution picks the overload whose receiver matches the instantiated `T`. That
keeps the call direct: no runtime dispatch, no dead overload per instantiation.

Matching an implementation is one shared structural check (`semProtocolMatches`): the
protocol's type parameters are the wildcards, so `fun <T> T.equalsWith(other: *TDest)` is
satisfied by `fun Point.equalsWith<TOther>(other: *TOther)`. Two implementations that render
one C++ signature are one overload; the comparison folds the built-in aliases (`Int`/`Int32`,
`Char`/`Int8` are one C++ type each) so the prelude's two `toString(Int)`/`toString(Int32)`
declarations do not write it twice. A **blanket** implementation (`fun <E> E.toString()`)
matches every type and has no overload of its own to write; it is left to ordinary overload
resolution and not registered as a protocol implementation yet.

Only protocols some `when` clause names get a set, and a prelude implementation a set reaches
is marked as reached before the prototype pass, because no call in the program names it
(`collectProtocolReach`).

## What is checked where

- Missing implementation for a concrete type argument: a checker diagnostic at the call.
- A member call on a type parameter no protocol declares: the emitter's `unsupported:`
  report at the enclosing declaration (the call site node is IL-reconstructed and carries no
  position; `Emitter.fail` falls back to the declaration being emitted).
- A type parameter passed on to another constrained call: the caller's own `when` clause has
  to cover the protocol (`Analyzer.currentConstraints`), so nested generic calls check out.
- A binding no call site fixes (an argument type the checker cannot name) is left to C++
  instantiation; the failure mode there is a C++ overload error rather than a diagnostic.

Inside a lambda that captures a constrained parameter, the closure's body is emitted with
the enclosing function's constraints still active plus the closure's own template parameters,
so the call resolves the same way.

## Deferred

- Explicit type arguments on a protocol member call (`value.equalsWith<Str>(other)`), and a
  protocol method whose parameter type a call site cannot deduce.
- A *member* call's instantiation check: a constrained extension
  (`fun T.announce<T>() when T: Show`) dispatches correctly, but a type that does not
  satisfy the protocol is reported by the C++ compiler rather than the checker - the call
  site has no type argument the checker binds.
- Blanket implementations (above).
- Per-package protocol visibility: protocols are program-wide names today.
- More than one method per protocol.
