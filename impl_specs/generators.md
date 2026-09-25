# Generators (`@SmGen`)

Status: **implemented** for the `cpp`, `res`, `kt` and `json` generators, the `Sections` sink,
and the bootstrap path. Per-*reach* generation - a declaration whose output is built for the
instantiations a program actually uses - is deferred (see the end); `json` is the first slice of
the "output built in code" kind, and it emits one serializer per type the program declares.

## `@SmGen`

`@SmGen(name, args...)` marks a method whose C++ is produced by the generator `name`.
The attribute's *first argument* names the generator; the remaining arguments are its
own (see `specs/attributes.md` for the token and the literals). The method is body-less:
the generator owns its C++ - a body on an attributed method is a diagnostic.

    @SmGen("cpp", "sym") fun f(items: *List<Int>): Int
    @SmGen("res", "spanOf") fun spanOf<T>(items: *List<T>): Span<T>

The attribute may sit on the declaration's own line or on the line above it.

## What the AST carries

Three attributes on the `Function` node (`AstNodeAttributeKind`), filled by the parser:

| attribute | value |
| --- | --- |
| `Attribute` | the attribute's own name (`SmGen`; a later `@Json` sugar would say `Json`) |
| `Generator` | the generator the first argument names (`cpp`, `res`, `kt`) |
| `GeneratorArgs` | the generator's remaining arguments, in order, joined by `,`; a string literal without its quotes |

`IsNative` is `true` for every generated method, so the native paths (the symbol table, the
`this`-receiver extensions, the prelude rules) apply unchanged.

A generator whose text is *not* emitted at the declaration - `cpp` and `res` - names the
symbol a call reaches as a generator argument, and the declaration carries it as
`NativeSymbol`/`HasNativeSymbol` whichever spelling was written: `cpp` has no parameters of
its own, so its symbol is the argument right after the generator's name, while `res` names
its section first and so its symbol is the third. A pass that reads the declaration
without the emitter's tables reads the symbol there: `linear`'s `listOf<T>` list literal
(whose call is a `Pack`, not a call) is the one that does, and a
`@SmGen("res", section, symbol)` declaration that lost it silently stopped being the list
literal.

## The `cpp` generator

`@SmGen("cpp", "sym")` means the implementation is linked in from a hand-written header,
so the generator has no parameters of its own and its one argument is the shared symbol
argument: the name of the C++ function, which the declaration's own name stands in for when
it is not written (`@SmGen("cpp")`). It produces no text: the *manager* does what such a
declaration needs - the symbol a call reaches, the prototype, the receiver pattern
(`sourceGenDeclare`, `sourceGenDeclaresPrototype`) - and the header has the C++.

`native("sym") fun f(...)`, the keyword that used to be sugar for it, is **gone from the
language (T83)**: `native` is an ordinary identifier again, and every declaration that
reaches generated C++ writes the attribute - `@SmGen("res", section, symbol)` when the text
is a resource section (which is all of the RTL's C++ now, `impl_specs/rtl-abi.md`),
`@SmGen("cpp", symbol)` for the type core, and, for `resources.kt`'s `get`/`has`/`count`, a
symbol alias to a plain Simse function.

## The generator table

Every generator is **one file** under `cppsrc/sourcegen/`, and a `SourceGenerator` that
**registers itself** by name - the shape `cppsrc/lex/Scanner.kt` gives its token matchers:

| file | name | C++ comes from | `declaresPrototype` | `registersReceiver` |
| --- | --- | --- | --- | --- |
| `CppGen.kt` | `cpp` | a header, linked in | yes | yes |
| `ResGen.kt` | `res` | a resource section | no | yes |
| `KtGen.kt` | `kt` | Simse source, compiled with the program | no | no |
| `JsonGen.kt` | `json` | Simse source, *built in code* from the program's types | no | no |

Each module's generators live in a `generators/` subfolder - the `json` module's is
`cppsrc/modules/json/generators/JsonGen.kt` - and the built-in three are one file each under
`cppsrc/sourcegen/`. A module's `generators/` is **compiler-side**: `--root` scans a tree whole
(so the compiler's own build compiles the generators in), while `--module` names a module and
scans it without its `generators/`, so a program that imports the module gets its declarations and
never the generator sources (which are written against the compiler's own packages). The `json`
row is the first generator that reads the program's **type structure** instead of a resource; the
module is where that grows (the per-reach step below).

The last two columns are the two things only the generator can know, so they are part of its
registration line.

A generator is a `typealias OnSourceGen = (*SourceGenContext) -> SourceGenTransform`
(`GenTypes.kt`): it takes *data* and answers a transform. The context carries the generator's
name, the phase, the declaration node (with its name, its arguments, its resolved symbol), the
file it came from, and pointers to the two things a generator may touch besides the AST - the
resources (`FullCompiledState`) and the sink (`Sections`). `SourceGenContext.isReached()` and
`parameter(i)` are the two conveniences over that.

**Registration is the generator's own**, one line at the end of its file:

    val cppGenRegistered: Bool = registerSourceGen("cpp", cppGen, true, true)

`registerSourceGen` appends to the table and answers `true`, because a file-level static's
initializer is an expression and every static has a type (`specs/statics.md`). The table
itself therefore has **no initializer**: its storage starts empty as a guarantee, every
generator appends itself, and whether a registration runs before or after another cannot
matter - the order the initialization pass runs in is unspecified, and a table *assigned*
by one file would have overwritten a registration that ran earlier. The dispatcher looks a
generator up by name, so the order the table ends up in is not observable either; a name
must simply not repeat. A module's generator registers the same way (`specs/simse-md.md`).

**What a generator may touch** is the boundary the package exists for: it reads and writes
the AST nodes, the resources and the sections, and it calls nothing from the compiler's
stages - not the parser, not the emitter, not the semantic pass - so it cannot break when a
compiler API changes (which is also why `Sections` left `codegen` for this package).

The same generator is asked three times, once per phase:

| phase | asked by | for |
| --- | --- | --- |
| `Declare` | codegen's `collect` | resolve the symbol a call reaches, before any call site is emitted |
| `Reparse` | the driver, before the program is checked | hand back Simse source to be compiled with the program |
| `Emit` | codegen, after every body | place text that needs the program's *reach set*, and - asked once more with an empty declaration - text about the program itself |

The `Emit` phase runs once per declaration and then once per generator with `xmlIsEmpty(ctx.declaration)`
- the second is what `emit: always` (a resource's program-wide text), `emit: reached` (a
  module's reach-gated text) and a generator's own sections hang on. `runSourceGen` records a non-empty `SourceGenTransform.key` in
`FullCompiledState.definitions`, which is how a generator that would produce the same thing
twice answers `AlreadyExisting` instead - a generator producing several keys checks and inserts
the rest itself, since a key is one `Str`.

## `Sections`

`Sections` (`cppsrc/sourcegen/Sections.kt`) is the amalgamation's sink and the one place
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
    appendBlock(out: *Str, text: Str)

The predefined sections are the emitter's assembly *phases*. The order keeps the routing
**byte-neutral** (emitting `cppsrc` before and after it is identical C++):

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

- A section holds the emitter's own lines (`text`, in order) and the **items** a generator
  adds (`items`, keyed text). Rendering walks the sections in order and, within one, writes
  `text` then the items in key order (deterministic) - so routing the emitter through this
  cannot change its bytes.
- Every block (a section's text and each item) starts with a blank line unless the output
  already ends with one, keeping the sections separable.
- A name the emitter does not know is a *new* section, appended at the end of the list.
- `add` is **last write wins**: an existing key's text is replaced; a generator that cares
  checks `has` first.
- One `Sections` per compilation, the static `sourceGenOutput`, handed out as a pointer
  (`sourceGenSink`, `sourceGenResetSink`): the emitter and every generator hold it as
  `*Sections`. **The pointer is passed, never read through**: `*this.sections` where
  `sections: *Sections` is a *copy* in Simse, so `sourceGenEmit(*this.sections, ...)` would
  fill a copy and render an empty file.

## The `res` generator

`@SmGen("res", section[, symbol])` takes its C++ from a **resource** - the generated
functions of the RTL come from `cppsrc/rtl/_res.md`, a program's own from its `_res.md`. It is
for C++ written by hand once and reused: a template whose text does not depend on the
program's types.

- **Where the text comes from**: the tree being compiled *first* (the `_res.md` files under
  its module roots, the list the driver read), **the compiler's own resources second** - the
  `_res.md` files beside the compiler's prelude, read from disk as the prelude's own `.kt`
  files are. The driver hands both lists to `sourceGenBegin`, so the lookup
  (`sourceGenResHas`/`sourceGenResText`) needs nothing of the compiler's - the same rule the
  `kt` generator's source lookup uses. This gives every program the RTL's C++ without carrying
  its resource file, and lets the compiler's own RTL be a resource file rather than a header
  (while the compiler is built, the tree's file is the newer one). `resHas` is separate because
  a key may hold an *empty* text.
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
  and `timeops` (the clock the profiler reads) are the two.
- `<section>:emit` = `reached` gates a section for a *module's* declarations too: the text lands
  only when a call reaches the declaration, so a module costs a program only what it uses.
  `filestream` is the first - it is what lets the `io` module hand out `readLine` without every
  program that names `io` carrying the stream code.
- A *prelude* declaration the program never names is skipped, so a prelude generator
  costs a program only what it uses - the rule a prelude function with a body follows. A
  *module* declaration is skipped the same way when its section says `emit: reached`. A
  declaration of the program's own root is always emitted, so one generated text may call
  another; a prelude or gated text must not, because a name inside a resource is never parsed.
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
- **A section marked `!` is read and not carried** (`specs/resources.md`, "What the program
  carries"): the emitter still finds its text and emits it as code, a generator still finds
  its keys, and the *program* does not carry the text as a resource. A section of C++ in a
  program's own `_res.md` should be marked, because the code is compiled in and storing its
  text too is a second copy of the same bytes (`stress/smgen-res-program`, whose golden shows
  the resource table and the string table it needs disappear). The RTL's own sections are
  deliberately *not* marked: `cppsrc/rtl/_res.md` is the compiler's run-time table, the second
  half of the lookup below, and marking them would leave every program without the RTL's C++.

The RTL's hand-written C++ is here (`cppsrc/rtl/_res.md`), one section per header it came
from: `strtable` and `timeops` (`emit: always`), `listops`, `dictops` and `strops`
(shared: the List/Array/Str primitives, the Dictionary operations, and the
string/character/numeric conversions the headers held), and `spanOf` (the first user). A
module owns its own resource, so `fileio` (`emit: always`: the platform's filesystem/IO
operations) and `filestream` (`emit: reached`: the `FileStream` reads, a program paying for them
only when it reads one) are `cppsrc/modules/io/_res.md` now, and the `json` generator's helper is
`cppsrc/modules/json/_res.md`. A `@SmGen("res", ...)`
declaration emits no prototype of its own, which is what the `forward` text of its section
is for. What stays a header is the type core and `filestream.hpp` (the `FileStream` struct
and its methods, minus the `simse_fileStream_open` prototype): `simse.hpp`'s own includes.

## Bootstrap

Adding `@Identifier` to the *scanner* and attributes to the *parser* is a one-time step
the published bootstrap cannot do for itself, so the order matters:

1. The scanner and parser changes land while the compiler's own sources stay `@`-free: the
   bootstrap still parses them, and the resulting compiler understands `@`.
2. `@` may then appear in the compiler's own sources - including the prelude - and
   `bun build.js --out cppsrc/simse_bootstrap.cpp` refreshes the published file, which carries
   the new scanner and parser. No hand-patch of `simse_bootstrap.cpp` is needed;
   `bun tools/bootstrap.js` is the check.
3. A change the *running* compiler cannot emit yet (the prelude's own declarations, the
   parser's own attribute layout) needs the two-phase build: build once with the old spelling,
   then switch the source and build again. The *resource* half of a prelude change does not:
   the emitter reads the tree's own `_res.md` first, so a section that only just arrived is
   found there.

## Generated Simse sources (`kt`)

The second kind of implementation a generator can supply is Simse source:

    @SmGen("kt", "greet") fun greeting(name: Str): Str

- `<section>:source` holds the source, read from the **program's** own resources first (the
  `_res.md` files under its module roots) and from the compiler's when the program does not
  carry it. A missing section is a driver error naming it, since it could otherwise only fail
  later in the C++, where nothing names the declaration.
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
- The source is *code*, so a program's own `kt` section is marked `!` and the program does
  not carry it: the lookup reads it either way (the generated function is compiled in), and
  the text would otherwise be stored in the executable as a resource as well
  (`stress/smgen-kt`, `stress/resources-compileonly`).

The point of generating *Simse* rather than C++ text: the compiler resolves everything for
the generated code that a C++-text generator cannot - package prefixes (`ns1_Point`), the
RTL's own symbol names, generics, member access, the string library - and the generator
itself is ordinary Simse that builds a `Str`. `stress/smgen-kt` is the end-to-end case; the
declaration there is in package `fixtures` and its source defines `greeting`, which the
call site reaches unchanged.

Not supported yet: a `kt` declaration with a receiver (`this`/`fun T.f`), a generated
module per declaration, and generators whose source is *built* in code rather than read
from a resource (that is the per-instantiation step below, which needs the emitter's
discovery loop: emit, ask the generator for what was reached, re-emit).

## The `json` generator

`@SmGen("json") fun T.toJson<T>(): Str` - the declaration the `json` module's `api.kt` carries -
asks for a JSON serializer, and `cppsrc/modules/json/generators/JsonGen.kt` **builds that Simse
source in code**: it is the one generator that reads the program's own *type structure* rather
than a resource. A program names the module (`--module cppsrc/modules/json`) and writes
`import json`, then `value.toJson()`; that becomes `{"x":1,"y":2}` for
`data class Point(var x: Int, var y: Int)`.

- **A program opts in by importing.** The generator acts only on a program a module of which
  spells `import json`, and it skips the module's *own* package (the trigger and the generator's
  own `JsonTypes`). So the compiler building itself, whose tree now carries the module's `api.kt`,
  generates nothing and pays nothing.
- **The API is an extension, and that is forced.** A plain-name call is resolved by name and
  arity alone (`Emitter.findFunction`), so a set of `toJson(*T)` overloads cannot be typed at a
  call site: an argument's handle (a `*T` wants the value's address) is read off the callee, and
  "the callee" is whichever same-name same-arity overload the lookup returned. A *member* call is
  resolved by the receiver's **type** - `toString`'s shape - so `value.toJson()` picks the right
  serializer every time. That is why the declaration is an extension and every generated function
  is `fun T.toJson(): Str`.
- **The declaration emits nothing**, not even a prototype (`declaresPrototype` is false, as for
  `kt`): the generated extension's own declaration is what a call binds to.
- **The output is the transitive closure, emitted once per type.** A data class becomes an object
  of its fields' serializers, recursing into sub-types; a scalar is a leaf. The set is keyed by
  the type's name, so a program with a hundred `Int` fields carries one `Int.toJson`, and a
  `toJson` the *program* writes for a receiver type is found first and left alone - the "don't
  generate twice" rule. `stress/json/expected.cpp` pins it: one prototype and one definition per
  type.
- **The scalars** are `Int`/`Int8..Int64` and `Float32/64` (their own `toString`), `Bool` (the JSON
  literal) and `Str`/`Char` (quoted, with the JSON escapes). The quoting helper is the
  `json:helpers` section of `cppsrc/modules/json/_res.md` - fixed Simse text the generator reads, so it
  stays readable rather than an escaped literal inside the generator.
- **The generated module is `rtl`** (the driver's synthetic reparse module) and begins with one
  `import` per package a serialized class lives in, which is what lets it name a program-defined
  class. Two classes sharing a name across packages are rejected, because one `import` cannot
  disambiguate them.
- **Not yet**: only *named* types are supported - a `List<T>`, an `Opt<T>`, a `*T` field or a
  type alias is a diagnostic naming the field, and a generic data class is rejected. Generation is
  program-wide (every class the program declares), not per reach - the deferred step's pieces.

## Regression discipline

Every bug found while building this gets a **minimal reproducer** committed with the fix:
a `stress/<name>/` program (source, arguments, expected output and - where the emitted
text is the point - an `expected.cpp` golden), or a focused check.

| case / tool | pins |
| --- | --- |
| `stress/smgen-cpp` + `bun tools/stress.js` | a program naming a generated symbol (`@SmGen("cpp", "simse_str_trim")`) reaches the resource section that defines it |
| `stress/smgen-res` | a resource-backed declaration: the `forward` declaration and the `bodies` definition land in their sections |
| `stress/smgen-res-program` | a *program's* own `_res.md` supplies the text: the tree's resources win over the compiler's |
| `stress/smgen-res-collision` | the documented last-write-wins collision |
| `stress/main-args` | a generated symbol the emitter spells itself (`simse_list_append`) is reached, so its section is emitted |
| `stress/smgen-kt` | generated Simse source: the driver compiles it, the generated function is emitted, the call site reaches it |
| `stress/resources-compileonly` | a `!` section: the `kt` source in it is compiled, the program carries neither it nor its key, and the unmarked section beside it is carried |
| `stress/diagnostic-smgen-kt-missing` | a `kt` declaration with no `<section>:source` is rejected by name |
| `stress/diagnostic-attribute-body` | a body on an attributed method is rejected |
| `stress/diagnostic-bodyless-method` | a body-less method with no attribute is rejected |
| `stress/diagnostic-attribute-arg` | an attribute argument must be a literal |
| `stress/diagnostic-attribute-token` | a `@` not followed by an identifier is a scanner error |

## Deferred

- **Per-*reach* generation (the `json` generator's next step).** `cppsrc/json/JsonGen.kt`
  already builds the serializer source in code and emits the transitive closure once per type
  (see "The `json` generator"); what it does not do is scope to what the program *serializes* -
  it walks every declared class. A reached generator needs the emitter's discovery loop (emit
  once, collect the types actually reached, ask the generator, re-emit), and - for a *free*
  `toJson<T>(*T)` API rather than the extension the name+arity resolution forces - a mangled
  symbol chosen per instantiation. The other half is shape coverage: a `List<T>`/`Opt<T>`/`*T`
  field and a generic data class each need a serializer for a shape that has no name of its own
  yet, built through the same naming helper.
- **The compiler's resources as the generator's input everywhere**: done - `res` and
  `kt` both read the tree's own `_res.md` files first and the compiler's second.
- Attributes on types, fields, parameters, and statements; stacked attributes; multiple
  attributes per declaration; user-supplied generators (a generator the *program* writes,
  which needs either an interpreter or a self-compile); generators influencing call
  expressions; generator-declared ordering (a new section always appends).
- enum-to-string and int-to-enum generators as trailing sections.
