# Resources: `_res.md`, and the `Resources` API

A program's *resources* are text that is not code - a template, a prompt, help text, the
profiler's own generated C++ - kept in files the compiler reads and **embeds in the string
table** the program already carries. No FFI, no data directory, no file at runtime.

## The shape: one flat list of (Key, Value)

```simse
List<(Str, Str)>   // every resource, in the order it was first written
```

A *section* is not a level of nesting: it is a **prefix on the key**.

~~~md
Profiling
====
Profile BootStrap:
```
// here is cpp code of the profile
```

Preamble template: `auto __smProfile = profileApp.measure("|");`
~~~

gives two entries:

| key | value |
| --- | --- |
| `Profiling:Profile BootStrap` | `// here is cpp code of the profile\n` |
| `Profiling:Preamble template` | `auto __smProfile = profileApp.measure("\|");` |

## The file format

Any file whose name ends in **`_res.md`** is a resource file. It is Markdown-shaped,
because it is a file a person edits and its diffs should read like the text they hold.

- A **section title** is a line, underlined by a line of `=` only (`====`). The title is
  *trimmed*, and it prefixes the keys that follow it: `Title` + `Key` is the key
  `Title:Key`. The underline line is spent with its title, so it is never read as an entry
  of its own.
- An **entry** is a line whose *first* `:` has something before it. The key is what stands
  before that colon, *trimmed* - so a stray space or tab in a key is not a bug that only
  shows up at runtime (`Profiling: Name` and `Profiling:Name` are one key).
  - the value is the rest of the line, trimmed, with one pair of wrapping single backticks
    removed if the value is wrapped in them:
    `` Key: `text` `` and `Key: text` both hold `text`;
  - when nothing follows the colon, the value is the **fenced block** that follows
    (` ``` ` ... ` ``` `): its lines as written, each followed by a newline. Blank lines
    between the key and its fence are skipped; a line that is not a fence opening leaves
    the value empty rather than swallowing the text. An opening fence may name a language
    (` ```cpp `); the closing fence is the bare ` ``` `, and a block left open runs to the
    end of the file. A `*`-marked value may be written either way, and a fenced one is
    usually the readable form for a dump several bytes long.
- A line whose first colon has *nothing* before it is not an entry, and neither is a line
  with no colon at all: those are prose, and ignored. A colon anywhere in prose therefore
  makes an entry of it - `:` is the separator, so text that is not an entry must not
  contain one.
- In a fenced block, a `\r` before the newline is not part of the line (the same rule the
  scanner applies), so a value does not depend on the file's line endings. Everything else
  is written as it is.
- A key written twice takes the **last** value, in its first-seen position.
- No escapes are interpreted: `` `\n` `` is a backslash and an `n`. What the file says is
  what the program gets - except for the two places that have to escape something, the C++
  literal the emitter pools the text as (`resQuoteLiteral`) and the same for a `*`-marked
  value's bytes (`resQuoteBinary`), which are the emitter's business.

## Markers

A section title or an entry's key may **open with markers**, which is the whole of how a
resource says more about itself than its text:

| marker | what it means on what it marks |
| --- | --- |
| `!` | **compile-only**: the compiler reads it, the program does not carry it |
| `*` | **binary**: the value as written is hex that stands for the bytes |

- They may be written **together, in either order** (`!*Hidden`, `*!Hidden`), and the run is
  consumed with the whitespace around it: `!greet` is the section `greet`, `*Dark` the key
  `Dark`, so a lookup by spelling - `@SmGen("kt", "greet")`, `Resources.get("Dark")` - cannot
  tell whether anything was marked.
- They may be written on a **section title** or on a **single entry's key**, and there is no
  way to take one back: a marked section marks every entry under it, and a key's own markers
  are on top of that (`Pictures:Icon` is binary because `Pictures` is). A title with nothing
  left after its markers is not a title, and leaves the section and both markers as they were.
- A marker is **not part of the name**, and the section is still a key *prefix*: marking a
  section is not a new level of nesting, just two booleans on every entry under it.

### What `*` means: hex that stands for bytes

A `*`-marked value is a **lower-case hex dump of the bytes the resource holds**, and the
reader turns it into those bytes on the way in. Nothing downstream knows it was ever hex: a
resource is a `Str` - a pointer and a length - like any other, which is why a byte string
with a `\0` in the middle needs no new type anywhere.

- Every pair of hex digits is one byte, in order. Whitespace (spaces, tabs, newlines) is not
  part of the dump, so it may be wrapped however the file likes; decoding **stops** at the
  first character that is neither whitespace nor a lower-case hex digit, and a last digit left
  unpaired is dropped.
- Stopping rather than skipping is deliberate: a value that is not lower-case hex decodes to
  nothing rather than to half its bytes, and this format validates nothing anywhere, so the
  program's own output is where a bad dump shows up.
- The bytes are **not necessarily text** - `5065746572` is `Peter`, but `00ff41` is three
  bytes, one of them a `\0` - so the string table's own encoding is what has to carry them:
  the emitter writes a non-printable byte as an octal escape with all three digits (which
  `cgLiteralByteLength` counts as the one byte it is), so the pool, its length index and the
  program's view of the text agree even when a value starts with `\0`.

## Discovery

The compiler scans the same module roots it reads sources from (`--root`, `--module-root`)
for `_res.md` files, recursively, in the same deterministic order as the `.kt` files (each
canonical path once, sorted), and **joins** them into the one flat list above (a section in
two files merges because the key carries the prefix; the last value of a repeated key wins,
in its first-seen position).

## What the program carries

The keys and values are pooled in **the program's string table** (`__sm_stringTable`), the
one the literals already use - resources *are* string literals, and pooling them there
means a resource value is a `StrView` over the pool, with no second copy anywhere. The
emitter writes the table after the string table is built, so its initialization can read
it:

```cpp
static const char __sm_stringPool[] = "...";           // keys and values are in here
static StrView __sm_stringTable[__sm_stringCount];
// ... the table's own init ...
static const Int __sm_resourceIndex[] = { 12, 3, 14, 9 };   // key, value, key, value, ...
static const Int __sm_resourceCount = 2;
namespace {
    struct __SmResourceInit {
        __SmResourceInit() { Resources::install(__sm_stringTable, __sm_resourceIndex, __sm_resourceCount); }
    } __sm_resourceInit;
}
```

The indices are positions in the string table, and the count is *entries*, not indices. A
program with no `_res.md` file emits no table, no install, and is byte-identical to one
built before the feature existed.

A section marked `!` is **read and not stored**: its entries stay in the list the compiler
works from - a generator looks its keys up, the emitter finds its text and emits it as code -
and they are absent from the pool and from the table above, so `Resources.has` never sees
them and the text is not in the executable a second time. That is the point of the marker:
in a `.md` file that holds *code* - a `kt` section's Simse source, a `res` section's C++ -
the code is compiled into the program, and storing its text as well would be a duplicate of
the same bytes. A resource that is *data* (a template, help text) is left unmarked, because
the program is exactly what should read it.

A `*`-marked value reaches the pool as the **bytes** its hex stood for, spelled so that the
pool can hold any of them: printable bytes as themselves, everything else as an octal escape
(`resQuoteBinary`). Nothing else about it is special - a resource is a key and a `Str`, and a
byte string with a `\0` in it is still a key and a `Str`.

## The API

`Resources` is a prelude type (`cppsrc/rtl/resources.kt`), and its methods are the
language's own code over the table the C++ header hands out - only the storage
(`cppsrc/rtl/resources.hpp`) is C++. `Resources.get(k)` is a static call, the shape
`Res<Str>.ok(x)` has:

```simse
fun entries(): Span<ResourceEntry>   // the table, borrowed
fun get(key: Str): StrView           // the value, empty when the key is absent
fun has(key: Str): Bool
fun count(): Int                     // how many resources the program carries
```

`ResourceEntry` is the C++ `struct ResourceEntry` field for field - a pair of `StrView`s
borrowing the string table - and the type a program walks when it wants more than the
lookup (an index, a section scan).

The declarations are prelude natives with an explicit `this` (`native("resourcesGet")
fun get(this: Resources, key: Str): StrView`), which is what gives the checker a
signature for `Resources.get(k)` and the emitter a symbol to call; the symbol names the
plain prelude function underneath it (`fun resourcesGet(key: Str): StrView`), which is
where the scan is written. The emitter spells such a call as the *symbol*
(`resourcesGet(k)`), not as a C++ static - `Emitter.staticCallSymbol` - and its name walk
records the same symbol, which is what makes the prelude body reachable. Two things stay
C++ because the language cannot say them: a table built before any of the program's code
runs, and the default-constructed `StrView`.

The entries are built **once**, at startup, by `install`, which is called by the table
the emitter writes - a list of `StrView` pairs borrowing the string table:

```cpp
struct ResourceEntry {
    StrView key;
    StrView value;
};
```

A section is the keys' prefix, so "give me a section" is a query over keys - which is the
program's to write, in one line, rather than a second shape in the API:

```simse
val prefix: Str = "Profiling:"
for (*this_resource in Resources.entries()) { ... }   // or: Resources.get("Profiling:...")
```

## Resources as a generator's C++

A resource is also how a `@SmGen` declaration gets its C++
(`impl_specs/generators.md`): `@SmGen("res", section[, symbol])` looks up `<section>:symbol`
(the symbol a call goes to; the attribute's second argument is the same statement, and is
what a *shared* section needs, since one section cannot carry one `symbol:` for many
declarations) and `<section>:<name>` for each section name, and adds the text it finds
there to that section. On the way in, a section may declare `<section>:emit` = `always`:
text the compiler emits for every program, with no declaration to hang it on - what the
string table's own decoder and the clock the profiler reads are.

The lookup reads the **tree being compiled first** - the `_res.md` files under its module
roots, the very list the driver read - and the **compiler's own resources second**: the
`_res.md` files *beside the compiler's prelude*, read from disk by the driver as the
prelude's own `.kt` files are. That second half is what gives a program the RTL's C++
(`cppsrc/rtl/_res.md`) without that program having to carry the RTL's resource file; a
program that carries a section of the same name supplies it to itself.
`@SmGen("kt", section)` reads `<section>:source` by the same rule. So a `_res.md` file under
`cppsrc` is generated C++ in the compiler every build *and* in the compiler's own source
tree, which is what lets the RTL's generated functions be a resource rather than a header.

Because the compiler reads its own file, that file's sections are all marked `!` - the
text is compiled in, and carrying a second copy of it in the compiler's own pool bought
nothing (the pool was 33,446 bytes, 23,034 of them this text; it is 11,051 now, and the
published bootstrap is 23,885 bytes smaller). The one requirement is that the compiler can
find its tree, which it already had to for the prelude.

The value is C++ as written - no escapes are interpreted on the way in - so the resource
is the one place that text lives. `cppsrc/rtl/_res.md` is the RTL's own generated C++
(`strtable`, `timeops`, `listops`, `dictops`, `strops`, `spanOf`), and its `spanOfEmpty`
section is the decoy
`stress/smgen-res-collision` pins the last-write-wins rule with;
`stress/smgen-res-program` is a program whose own file supplies one.

A resource can hold **Simse** too: `@SmGen("kt", section)` reads `<section>:source` and
hands it to the compiler's own front end - the driver parses it, `analyze` checks it with
the program and codegen emits it - so the generated function is compiled, not pasted.

## Where it lives

| piece | file |
| --- | --- |
| the format, the join, the discovery | `cppsrc/resources/Resources.kt` (its `ResourceItem` is the reader's pair - not the RTL's `ResourceEntry`, because the emitter's type table is flat by name) |
| discovery in the driver | one call to the module: `cppsrc/compiler/Driver.kt` |
| the lookup a generator reads | `sourcegen`'s `sourceGenResHas`/`sourceGenResText` (`cppsrc/sourcegen/GenTypes.kt`): the tree's own entries first, the compiler's own (read from disk beside the prelude) second |
| pooling and the table | the emitter: `cppsrc/codegen/Codegen.kt`, after `emitStringTable`, over `resourceStored` - the literals `resources.resStoredLiterals` already spelled |
| the two escape rules, and the flags | `cppsrc/resources/Resources.kt`: `resMarkedName` (the markers), `resStoredLiterals` (`resQuoteLiteral`/`resQuoteBinary`), `resQuoteLiteral` |
| the format's byte helpers | the `resfmt` section of `cppsrc/rtl/_res.md` (`simse_resHexToBytes`, `simse_resQuoteBinary`), reached only by the compiler's own module |
| the storage and `install` | `cppsrc/rtl/resources.hpp` |
| the API and the lookup | `cppsrc/rtl/resources.kt` (Simse) |
| the static form | `Emitter.call` + `Emitter.staticCallSymbol` |
| the RTL's generated C++ | `cppsrc/rtl/_res.md`, read by the `res` generator |
| the end-to-end case | `stress/resources/` (data the program carries) and `stress/resources-compileonly/` (a `!` section: read, not carried) |

## Status

Implemented for the whole path: discovery, parse, join, pooling, the emitted table, the
`Resources` API - the lookup itself now written in Simse over the `Span<ResourceEntry>`
the C++ storage hands out - and the `res` generator, which reads the tree being compiled
first and the compiler's own resources beside the prelude second
(`impl_specs/generators.md`). `stress/resources`
prints every shape the format has: a fenced block, an inline value, an empty value, a
missing key, a comparison against a literal, and the escapes a value needs on the way into
the pool (a double quote, a backslash, a tab, and a value ending in a backslash).

`cppsrc/rtl/_res.md` is entirely marked `!`, and the compiler's `Resources` table is
therefore empty: the RTL type is a *program-facing* API now, and the compiler reads its own
resources from the file (which it has to have anyway, for the prelude's `.kt` files) instead
of carrying them in its pool. The cost of the marker is exactly that requirement, and the
gain is the second copy of the text - 23,034 bytes of pool, and on the last refresh 23,885
bytes of the published bootstrap.

The **markers** are implemented: a section title or an entry's key may open with `!`, with
`*`, or with both in either order, and the marker run is consumed - the entry then keeps its
plain name, is left out of the pool and the table (`!`), and its value is decoded from hex
into the bytes themselves (`*`) before anything else sees it. `stress/resources-compileonly`
pins the `!` half (the `kt` source in a marked section is compiled and its function works, the
program does not carry that key, it does carry the unmarked section beside it, and the count is
one rather than two) and `stress/resources-binary` the `*` half, at both levels and combined:
a section-level `*` (`Pictures`), a key-level one inside an unmarked section, a value spanning
three lines, a value that is text after decoding, a leading `\0` whose *length* is what proves
the pool carried it, a byte above `0x7f` read back as the signed `Char` it is, and a `!*`
section whose entries the program does not carry at all. The corpus's own code resources use
`!` (`stress/smgen-kt`, `stress/smgen-res-program`), where the effect is visible in the
goldens: `smgen-kt`'s string pool drops from 403 bytes to 12, and `smgen-res-program` stops
emitting a resource table (and a string table) at all.

The format's two byte-level helpers are a section of their own, `resfmt`
(`cppsrc/rtl/_res.md`), reached by the declarations in `cppsrc/resources/Resources.kt`. They
are not part of `strops`: a shared section is emitted whole, so putting them there would hand
them to every program that reached a `Str` operation.

`bun tools/stress.js` is **61/61**, and the bootstrap fixed point (`bun tools/bootstrap.js`)
holds byte for byte.

Not done yet: a resource *section* helper (deliberately - a section is a key prefix), a
name-to-index shortcut for lookups (`Resources.get` is a linear scan of a handful of
entries; a `Dictionary` would need a startup build that the `StrView`s make unnecessary
for this size), and a resource *program* (a resource that is Simse and that the compiler
runs: `kt` compiles it into the program, which is all a generator needs today).
