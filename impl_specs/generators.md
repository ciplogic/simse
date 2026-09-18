# Generators (`@SmGen`)

Status: **implemented** for the `cpp` and `res` generators, the `Sections` sink, and the
bootstrap path. Per-instantiation generators (`@Json`) are deferred (see the end).

## `@SmGen`

`@SmGen(name, args...)` marks a method whose C++ is produced by the generator `name`.
The attribute's *first argument* names the generator; the remaining arguments are its
own (see `specs/attributes.md` for the token and the literals). The method is body-less:
the generator owns its C++ - a body on an attributed method is a diagnostic.

    @SmGen("cpp", "defined-in-headers", "sym") fun f(items: *List<Int>): Int
    @SmGen("res", "spanOf") fun spanOf<T>(items: *List<T>): Span<T>

The attribute may sit on the declaration's own line or on the line above it.

## What the AST carries

Three attributes on the `Function` node (`AstNodeAttributeKind`), filled by the parser:

| attribute | value |
| --- | --- |
| `Attribute` | the attribute's own name (`SmGen`; a later `@Json` sugar would say `Json`) |
| `Generator` | the generator the first argument names (`cpp`, `res`) |
| `GeneratorArgs` | the generator's remaining arguments, in order, joined by `,`; a string literal without its quotes |

`IsNative` is `true` for every generated method (nothing is emitted for the declaration
itself, and a call reaches a symbol instead), which is why the existing native paths -
the symbol table, the `this`-receiver extensions, the prelude rules - apply unchanged.

## `native` is a generator

`native("sym") fun f(...)` is **sugar** for `@SmGen("cpp", "defined-in-headers", "sym")`:
the parser fills the very same attributes, so the two spellings are one declaration and
emit the same C++ (`bun tools/smgen.js` asserts exactly that). `defined-in-headers`
means the implementation is linked from the hand-written headers; the optional symbol
argument names it when it differs from the declaration's own name.

## `Sections`

`Sections` (`cppsrc/codegen/CgSections.kt`) is the amalgamation's sink and the one place
that knows the order:

    data class NamedSection(name: Str, text: Str, items: Dictionary<Str, Str>)
    data class Sections(sections: List<NamedSection>, current: Int)

    section(name: Str): Int        // get-or-create; a new section is appended at the end
    begin(name: Str): Unit         // makes it the section the emitter writes into
    appendText(text) / appendLine(indent, text)
    add(name: Str, key: Str, text: Str)
    has(name: Str, key: Str): Bool
    get(name: Str, key: Str): Opt<Str>
    names(): List<Str>             // the names, in render order
    render(): Str

The predefined sections are the emitter's own assembly stages, and that order is what
makes the routing **byte-neutral** - the compiler before and after it transpiles
`cppsrc` to identical C++:

    includes -> forward -> types -> statics -> prototypes -> init -> bodies

- `includes` is the preamble (the `#include`s, the profiler's runtime), the native
  prototypes, and the string/resource tables. `forward` is the one section the emitter
  leaves **empty**: it is where a generated *declaration* goes, so it lands before every
  type and body. The rest are as their names say; the program's `main` is emitted with
  the other bodies.
- A section holds two kinds of text: the emitter's own lines (`text`, appended in order)
  and the **items** a generator adds (`items`, keyed text). Rendering walks the sections
  in order and, within one, writes `text` first and then the items in the dictionary's
  own order - which is why routing the emitter through this cannot change its bytes.
- A name the emitter does not know is a *new* section, appended at the end of the list,
  so a generator's own machinery renders after the program, out of the way.
- `add` is **last write wins**: an existing key's text is replaced. A generator that
  cares checks `has` first.

## The `res` generator

`@SmGen("res", section)` takes its C++ from a **resource the compiler carries**
(`Resources.get`, `cppsrc/rtl/_res.md` for the RTL's own generated functions). It is the
generator for C++ that must be written by hand once and reused: a template whose text
does not depend on the program's types.

- `<section>:symbol` names the symbol a call goes to. Without it the declaration's own
  name is the symbol.
- `<section>:<name>` for each *section name* holds text added to that section, under the
  symbol as the item's key. So one resource can supply a declaration (`forward`), a
  definition (`bodies`), an include (`includes`), and so on - each in its own section.
  A name that is not a predefined section creates a section, as any generator's would.
- A *prelude* declaration the program never names is skipped, so a prelude generator
  costs a program only what it uses - the rule a prelude function with a body follows.
  A program's own declaration is always emitted, so one generated text may call another;
  a prelude text must not, because a name inside a resource is never parsed.
- **Two declarations that name one symbol in one section replace each other** rather
  than emitting two definitions: the item is keyed by the symbol, so the second `add`
  wins. `stress/smgen-res-collision` pins this (`cppsrc/rtl/smgen_collision_res.md` is
  its decoy).
- Resource text is *raw C++*, so it can only name the RTL's types and the declaration's
  own type parameters - a generated body that had to spell a program-defined type would
  have to be a per-instantiation generator (deferred).

`cppsrc/rtl/Span.kt`'s `spanOf` is the first user: the declaration and the definition
moved out of `cppsrc/rtl/span.hpp` into the `spanOf` section of `cppsrc/rtl/_res.md`,
so the same text is one resource instead of a hand-written header.

## Bootstrap

Adding `@Identifier` to the *scanner* and attributes to the *parser* is a one-time step
the published bootstrap cannot do for itself, so the order matters:

1. The scanner and parser changes land while the compiler's own sources stay `@`-free:
   the bootstrap still parses them, and the resulting compiler understands `@`.
2. Once that compiler exists, `@` may appear in the compiler's own sources - including
   the prelude - and `bun build.js --out cppsrc/simse_bootstrap.cpp` refreshes the
   published file, which then carries the new scanner and parser. No hand-patch of
   `simse_bootstrap.cpp` is needed; `bun tools/bootstrap.js` is the check.
3. A change the *running* compiler cannot emit yet (the prelude's own declarations, the
   parser's own attribute layout) needs the two-phase build the bootstrap dance always
   needs: build once with the old spelling, then switch the source and build again.

## Regression discipline

Every bug found while building this gets a **minimal reproducer** committed with the fix:
a `stress/<name>/` program (source, arguments, expected output and - where the emitted
text is the point - an `expected.cpp` golden), or a focused check.

| case / tool | pins |
| --- | --- |
| `stress/smgen-native` + `stress/smgen-cpp` + `bun tools/smgen.js` | `native("sym")` and `@SmGen("cpp", "defined-in-headers", "sym")` emit byte-identical C++ |
| `stress/smgen-res` | a resource-backed declaration: the `forward` declaration and the `bodies` definition land in their sections |
| `stress/smgen-res-collision` | the documented last-write-wins collision |
| `stress/diagnostic-attribute-body` | a body on an attributed method is rejected |
| `stress/diagnostic-bodyless-method` | a body-less method with no attribute is rejected |
| `stress/diagnostic-attribute-arg` | an attribute argument must be a literal |
| `stress/diagnostic-attribute-token` | a `@` not followed by an identifier is a scanner error |

## Deferred

- **Per-instantiation generators** (`@SmGen("json") fun serializeJson<T>(obj: *T): Str`):
  the emitter collects instantiations the way generics are reified, computes a mangled
  symbol per instantiation, invokes the generator with the concrete type arguments, and
  the call site calls that symbol. The generator may report a positioned diagnostic for
  an instantiation it cannot handle. A later `@Json` spelling is sugar for it.
- **The program's own resources** as a generator's input (today the generator reads the
  *compiler's* pool, which is what the prelude needs; a program-supplied `_res.md`
  would need the entries passed to codegen beside the literal pool).
- Attributes on types, fields, parameters, and statements; stacked attributes; multiple
  attributes per declaration; user-supplied generators; generators influencing call
  expressions; generator-declared ordering (a new section always appends).
- enum-to-string and int-to-enum generators as trailing sections.
