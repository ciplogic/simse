# Generators (`@SmGen`)

Status: **implemented** for the `cpp`, `res` and `kt` generators, the `Sections` sink, and
the bootstrap path. Per-instantiation generators (`@Json`) are deferred (see the end).

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
| `Generator` | the generator the first argument names (`cpp`, `res`, `kt`) |
| `GeneratorArgs` | the generator's remaining arguments, in order, joined by `,`; a string literal without its quotes |

`IsNative` is `true` for every generated method (nothing is emitted for the declaration
itself, and a call reaches a symbol instead), which is why the existing native paths -
the symbol table, the `this`-receiver extensions, the prelude rules - apply unchanged.

A generator whose text is *not* emitted at the declaration - `cpp` and `res` - names the
symbol a call reaches as the attribute's third argument, and the declaration carries it as
`NativeSymbol`/`HasNativeSymbol` whichever spelling was written. That is not bookkeeping:
a pass that reads the declaration without the emitter's tables reads the symbol there -
`linear`'s `listOf<T>` list literal (whose call is a `Pack`, not a call) is the one that
does, and a `@SmGen("res", section, symbol)` declaration that lost it silently stopped
being the list literal.

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

The predefined sections are the emitter's own assembly *phases*, and each name says what
renders there. The order is what makes the routing **byte-neutral** - the compiler before
and after it transpiles `cppsrc` to identical C++:

    includes -> support -> profile -> strings -> resources -> forward -> types ->
    statics -> prototypes -> init -> bodies

| section | what lands there |
| --- | --- |
| `includes` | the banner and the `#include`s |
| `support` | generated text the *preamble* below needs: a table's decoder, a clock's declaration. The emitter writes nothing into it itself |
| `profile` | the profiler's runtime (a `--profile` build), which needs what `support` declared |
| `strings` | the string-literal table and its initializer |
| `resources` | the resource table and its installer |
| `forward` | generated *declarations*, so they land before every type and body. The emitter writes nothing into it itself |
| `types` | the forward type declarations and the type definitions |
| `statics` | file-level static storage |
| `prototypes` | every function's prototype |
| `init` | the generated static-initialization pass |
| `bodies` | every function body, and a generated *definition* |

- A section holds two kinds of text: the emitter's own lines (`text`, appended in order)
  and the **items** a generator adds (`items`, keyed text). Rendering walks the sections
  in order and, within one, writes `text` first and then the items in the dictionary's
  own order (key order, so it is deterministic) - which is why routing the emitter through
  this cannot change its bytes.
- Every block - a section's own text and each item alike - starts fresh: a blank line
  before it, unless the output already ends with one. A generated text therefore never
  runs into the line before it, which is what keeps a resource's C++ readable in the
  amalgamation.
- A name the emitter does not know is a *new* section, appended at the end of the list,
  so a generator's own machinery renders after the program, out of the way.
- `add` is **last write wins**: an existing key's text is replaced. A generator that
  cares checks `has` first.

## The `res` generator

`@SmGen("res", section[, symbol])` takes its C++ from a **resource** - the generated
functions of the RTL come from `cppsrc/rtl/_res.md`, a program's own from its `_res.md`.
It is the generator for C++ that must be written by hand once and reused: a template whose
text does not depend on the program's types.

- **Where the text comes from**: the tree being compiled *first* (the `_res.md` files
  under its module roots, the list the driver read), the compiler's own table second
  (`Resources.get`) - the same rule the `kt` generator's source lookup uses. The second
  half is what hands every program the RTL's C++ without that program carrying the RTL's
  resource file; the first is what lets the compiler's own RTL be a resource file rather
  than a header, since while the compiler is being built the tree's file is the newer one.
  `Emitter.resText`/`resHas` are the two questions asked of that list (`resHas` is
  separate because a key may hold an *empty* text).
- `<section>:symbol` names the symbol a call goes to, and so does the attribute's second
  argument - the attribute wins where both are written. A declaration that names neither
  goes to its own name.
- `<section>:<name>` for each *section name* holds text added to that section, under the
  symbol as the item's key. So one resource can supply a declaration (`forward`), a
  definition (`bodies`), an include (`includes`), and so on - each in its own section.
  A name that is not a predefined section creates a section, as any generator's would.
- A *shared* section is one with no `symbol:`: its item is keyed by the section's own name
  instead, so it is emitted once however many declarations reach it. That is the form for a
  header's worth of functions - the RTL's `listops` section holds `append`, `listOf`,
  `removeAt`, `toArray`, and the `Str` primitives - and each declaration then *must* name
  its symbol (the attribute's third argument), because the section cannot.
- `<section>:emit` = `always` is the marker for text with no declaration to hang it on:
  the compiler emits the section for every program. `strtable` (the string table's decoder)
  and `timeops` (the clock the profiler reads) are the two, and they are why the RTL's
  headers could go away at all.
- A *prelude* declaration the program never names is skipped, so a prelude generator
  costs a program only what it uses - the rule a prelude function with a body follows.
  A program's own declaration is always emitted, so one generated text may call another;
  a prelude text must not, because a name inside a resource is never parsed.
- **A reach is by name or by symbol.** The set the rule above reads holds the names the
  program calls and the symbols some calls reach: `collectNames` records the symbol of the
  declaration a call *names* (`nativeSymbols`), the symbol of a call on a type name
  (`staticCallSymbol`), and - because the emitter's own spelled calls are in the set too -
  the entry point's `simse_list_append`, which has no call site in the source (`emitFunctions`
  records it, which is why the generator pass runs *after* every body). A program that names
  an RTL symbol directly (`native("simse_str_trim") fun trimmedText(...)`) is the case the
  first half exists for: with the C++ in a header it linked anyway, and with the text in a
  section it is emitted only when the symbol is reached. The named sections make the late
  pass safe: when it runs does not decide where its text renders.
- **Two declarations that name one symbol in one section replace each other** rather than
  emitting two definitions: the item is keyed by the symbol, so the second `add` wins.
  `stress/smgen-res-collision` pins this (the `spanOfEmpty` section of
  `cppsrc/rtl/_res.md` is its decoy).
- Resource text is *raw C++*, so it can only name the RTL's types and the declaration's
  own type parameters - a generated body that had to spell a program-defined type would
  have to be a per-instantiation generator (deferred).

The RTL's hand-written C++ lives here now, one section per header it came from
(`cppsrc/rtl/_res.md`): `strtable` and `timeops` (`emit: always`), `listops`, `dictops`
and `strops` (shared: the List/Array/Str primitives, the Dictionary operations, and the
string/character/numeric conversions the headers held), and `spanOf` (the first user,
whose declaration and definition moved out of `span.hpp`). Five headers are gone -
`strtable.hpp`, `timeops.hpp`, `listops.hpp`, `dictops.hpp`, `strops.hpp` - and `simse.hpp`
no longer includes them; a `@SmGen("res", ...)` declaration emits no prototype of its own,
which is what the `forward` text of its section is for. What stays a header is the type core
and the platform: `simse.hpp`'s own includes, plus the declarations over `native.cpp`
(`fs.hpp`, `filestream.hpp`).

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
   needs: build once with the old spelling, then switch the source and build again. The
   *resource* half of a prelude change does not need one any more: the emitter reads the
   tree's own `_res.md` first, so a section that only just arrived is found there.

## Generated Simse sources (`kt`)

The second kind of implementation a generator can supply is **Simse source**:

    @SmGen("kt", "greet") fun greeting(name: Str): Str

- `<section>:source` holds the source, read from the **program's** own resources first (the
  `_res.md` files under its module roots) and from the compiler's when the program does not
  carry it. A section that is missing is a driver error naming it - it could otherwise only
  fail later, in the C++, where nothing names the declaration.
- The source is *Simse*, so the compiler compiles it: the **driver** scans the parsed
  modules for these declarations, joins the sources into one module under the synthetic
  file name `<generated>/kt.kt`, and parses it (`driverParseSource`) - it is then checked
  by `analyze` with the program and emitted after it, like any other module.
- The generated module's package is **`rtl`**, where a bare name is the symbol a call
  reaches, so the generated function carries the declaration's own name and the call site
  is emitted unchanged. The one consequence to know: the declaring module must not itself
  be `rtl`, or the two declarations are duplicates.
- Nothing is emitted for the declaration itself - not even a prototype, because the
  generated function's own declaration is what the call binds to (a native's parameter
  spelling, `const T&`, and a body's, `T`, are different C++ functions).
- The generated module is a *program* module, so every function in it is emitted, whether
  the program reaches it or not. A generator that emits a transitive closure (see the
  deferred `@Json` below) is what keeps that small.
- Determinism: the sections are collected in module order and joined in that order, so the
  generated module - and the amalgamation - depend only on the sources.

The point of generating *Simse* rather than C++ text: the compiler resolves everything for
the generated code that a C++-text generator cannot - package prefixes (`ns1_Point`), the
RTL's own symbol names, generics, member access, the string library - and the generator
itself is ordinary Simse that builds a `Str`. `stress/smgen-kt` is the end-to-end case;
the declaration there is in package `fixtures` and its source defines `greeting`, which
the call site reaches unchanged.

Not supported yet: a `kt` declaration with a receiver (`this`/`fun T.f`), a generated
module per declaration, and generators whose source is *built* in code rather than read
from a resource (that is the per-instantiation step below, which needs the emitter's
discovery loop: emit, ask the generator for what was reached, re-emit).

## Regression discipline

Every bug found while building this gets a **minimal reproducer** committed with the fix:
a `stress/<name>/` program (source, arguments, expected output and - where the emitted
text is the point - an `expected.cpp` golden), or a focused check.

| case / tool | pins |
| --- | --- |
| `stress/smgen-native` + `stress/smgen-cpp` + `bun tools/smgen.js` | `native("sym")` and `@SmGen("cpp", "defined-in-headers", "sym")` emit byte-identical C++ |
| `stress/smgen-res` | a resource-backed declaration: the `forward` declaration and the `bodies` definition land in their sections |
| `stress/smgen-res-program` | a *program's* own `_res.md` supplies the text: the tree's resources win over the compiler's |
| `stress/smgen-res-collision` | the documented last-write-wins collision |
| `stress/main-args` | a generated symbol the emitter spells itself (`simse_list_append`) is reached, so its section is emitted |
| `stress/smgen-kt` | generated Simse source: the driver compiles it, the generated function is emitted, the call site reaches it |
| `stress/diagnostic-smgen-kt-missing` | a `kt` declaration with no `<section>:source` is rejected by name |
| `stress/diagnostic-attribute-body` | a body on an attributed method is rejected |
| `stress/diagnostic-bodyless-method` | a body-less method with no attribute is rejected |
| `stress/diagnostic-attribute-arg` | an attribute argument must be a literal |
| `stress/diagnostic-attribute-token` | a `@` not followed by an identifier is a scanner error |

## Deferred

- **A reached-instantiation generator** (`@SmGen("json") fun serializeJson<T>(obj: *T): Str`):
  a declaration whose output is *built in code* - the generator is a compiler-side Simse
  function that reads the declaration and the concrete type arguments and returns Simse
  source, which the driver compiles like a `kt` section's. It needs what the `kt` step
  does not: the emitter's discovery loop (emit once, collect the instantiations actually
  reached, ask the generator, re-emit), the emitter choosing a mangled symbol per
  instantiation, and the generator emitting the transitive closure it needs (a nested
  `List<Point>` serializer) through the same naming helper. A later `@Json` spelling is
  sugar for it, and this is the step that makes the serializer itself Simse - the
  generator writes the serialization code in the language, not in C++.
- **The compiler's resources as the generator's input everywhere**: done - `res` and
  `kt` both read the tree's own `_res.md` files first and the compiler's second.
- Attributes on types, fields, parameters, and statements; stacked attributes; multiple
  attributes per declaration; user-supplied generators (a generator the *program* writes,
  which needs either an interpreter or a self-compile); generators influencing call
  expressions; generator-declared ordering (a new section always appends).
- enum-to-string and int-to-enum generators as trailing sections.
