# Resources: `_res.md`, and the `Resources` API

A program's resources are text that is not code (a template, a prompt, help text, generated
C++), kept in files the compiler reads and embedded in the program's string table. No FFI,
no data directory, no file at runtime.

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

Any file whose name ends in **`_res.md`** is a resource file. It is Markdown-shaped.

- A **section title** is a line, underlined by a line of `=` only (`====`). The title is
  *trimmed*, and it prefixes the keys that follow it: `Title` + `Key` is the key
  `Title:Key`. The underline line is spent with its title, so it is never read as an entry
  of its own.
- An **entry** is a line whose *first* `:` has something before it. The key is what stands
  before that colon, *trimmed* (`Profiling: Name` and `Profiling:Name` are one key).
  - the value is the rest of the line, trimmed, with one pair of wrapping single backticks
    removed if the value is wrapped in them:
    `` Key: `text` `` and `Key: text` both hold `text`;
  - when nothing follows the colon, the value is the **fenced block** that follows
    (` \`\`\` ` ... ` \`\`\` `): its lines as written, each followed by a newline. Blank lines
    between the key and its fence are skipped; a line that is not a fence opening leaves
    the value empty rather than swallowing the text. An opening fence may name a language
    (` \`\`\`cpp `); the closing fence is the bare ` \`\`\` `, and a block left open runs to the
    end of the file.
- A line whose first colon has *nothing* before it, or that has no colon at all, is prose
  and ignored; since `:` is the separator, prose that contains one becomes an entry.
- In a fenced block, a `\r` before the newline is not part of the line, so a value does not
  depend on the file's line endings. Everything else is written as it is.
- A key written twice takes the **last** value, in its first-seen position.
- No escapes are interpreted: `` `\n` `` is a backslash and an `n`. What the file says is
  what the program gets, except for the two places that escape something, the C++ literal
  the emitter pools the text as (`resQuoteLiteral`) and a `*`-marked value's bytes
  (`resQuoteBinary`).

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
- A value that is not lower-case hex therefore decodes to nothing rather than to half its
  bytes; this format validates nothing, so a bad dump shows up in the program's own output.
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
one the literals already use: a resource value is a `StrView` over the pool, with no second
copy. The emitter writes the table after the string table is built, so its initialization
can read it:

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
program with no `_res.md` file emits no table and no install.

A section marked `!` is read and not stored: its entries stay in the list the compiler
works from (a generator looks its keys up; the emitter finds its text and emits it as code)
and are absent from the pool and the table, so `Resources.has` never sees them. In a `.md`
file that holds code (a `kt` section's Simse source, a `res` section's C++), the code is
compiled into the program, so storing its text as well would duplicate the same bytes. A
resource that is data (a template, help text) is left unmarked.

A `*`-marked value reaches the pool as the bytes its hex stood for, spelled so the pool can
hold any of them: printable bytes as themselves, everything else as an octal escape
(`resQuoteBinary`).

## The API

`Resources` is a prelude type (`cppsrc/rtl/resources.kt`), Simse code over the table the
C++ header (`cppsrc/rtl/resources.hpp`) hands out. `Resources.get(k)` is a static call:

```simse
fun entries(): Span<ResourceEntry>   // the table, borrowed
fun get(key: Str): StrView           // the value, empty when the key is absent
fun has(key: Str): Bool
fun count(): Int                     // how many resources the program carries
```

`ResourceEntry` is the C++ `struct ResourceEntry` field for field - a pair of `StrView`s
borrowing the string table - and the type a program walks when it wants more than the
lookup (an index, a section scan).

The declarations carry an explicit `this` and name their implementation with `@SmGen`
(`@SmGen("cpp", "resourcesGet") fun get(this: Resources, key: Str): StrView`), giving the
checker a signature and the emitter a symbol to call; the symbol names the plain prelude
function underneath it (`fun resourcesGet(key: Str): StrView`), where the scan is written.
The emitter spells such a call as the symbol (`resourcesGet(k)`, `Emitter.staticCallSymbol`)
rather than as a C++ static. What stays C++ because the language cannot say it: the storage
and `install` in `cppsrc/rtl/resources.hpp`, and the accessor over it (`entries`) in the
`resources` section of `cppsrc/rtl/_res.md`.

The entries are built **once**, at startup, by `install`, which is called by the table
the emitter writes - a list of `StrView` pairs borrowing the string table.

A section is the keys' prefix, so a section query is a query over keys, written by the
program (`Resources.entries()`, or `Resources.get("Profiling:...")`).

## Resources as a generator's C++

A resource is also how a `@SmGen` declaration gets its C++ (`impl_specs/generators.md`):
`@SmGen("res", section[, symbol])` looks up `<section>:symbol` (the symbol a call goes to;
the second argument is what a *shared* section needs, since one section cannot carry one
`symbol:` for many declarations) and `<section>:<name>` for each section name, adding the
text it finds to that section. A section may declare `<section>:emit` = `always`: text the
compiler emits for every program, with no declaration to hang it on (the string table's own
decoder and the clock the profiler reads). `<section>:emit` = `reached` is the other side: the
section's text is **reach-gated even when the declaration that names it is a module's** - a
prelude declaration is gated already, a program's is not. A module's C++ then costs a program
only what it uses; `filestream` (the `FileStream` reads, `cppsrc/rtl/_res.md`) is the first, and
it is what keeps the `io` module from carrying the stream code into a program that never reads a
stream. `@SmGen("kt", section)` reads `<section>:source`
and hands it to the compiler's own front end - the driver parses it, `analyze` checks it,
codegen emits it - so the generated function is compiled, not pasted.

The lookup reads the tree being compiled first (the `_res.md` files under its module roots,
the very list the driver read) and the compiler's own resources second (the `_res.md` files
beside the compiler's prelude, read from disk by the driver as the prelude's own `.kt` files
are). The second half gives a program the RTL's C++ (`cppsrc/rtl/_res.md`) without carrying
the RTL's resource file; a program that carries a section of the same name supplies it to
itself. Because the compiler reads that file, its sections are all marked `!`, so the text
is compiled in and not carried a second time in the compiler's own pool (the pool was 33,446
bytes, 23,034 of them this text; it is 11,051 now, and the published bootstrap is 23,885
bytes smaller). `cppsrc/rtl/_res.md` is the RTL's own generated C++ (`strtable`, `timeops`,
`listops`, `dictops`, `strops`, `strcat`, `spanOf`); its `spanOfEmpty` section is the decoy
`stress/smgen-res-collision` pins the last-write-wins rule with, and
`stress/smgen-res-program` is a program whose own file supplies one.

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
`Resources` API (the lookup written in Simse over the `Span<ResourceEntry>` the C++ storage
hands out), and the `res` generator (`impl_specs/generators.md`). `stress/resources` prints
every shape the format has: a fenced block, an inline value, an empty value, a missing key, a
comparison against a literal, and the escapes a value needs on the way into the pool.

`cppsrc/rtl/_res.md` is entirely marked `!`, so the compiler's `Resources` table is empty:
the RTL type is a program-facing API, and the compiler reads its own resources from the file
it needs for the prelude's `.kt` files anyway. The cost of the marker is that requirement;
the gain is the second copy of the text (the numbers above).

The markers are implemented. `stress/resources-compileonly` pins the `!` half (the `kt`
source in a marked section is compiled and its function works, the program does not carry
that key, it does carry the unmarked section beside it, and the count is one rather than two)
and `stress/resources-binary` the `*` half, at both levels and combined (a section-level `*`,
a key-level one inside an unmarked section, a multi-line value, a leading `\0` whose length
proves the pool carried it, and a byte above `0x7f` read back as the signed `Char`).
`stress/smgen-kt`'s string pool drops from 403 bytes to 12, and `stress/smgen-res-program`
stops emitting a resource table (and a string table) at all.

The format's two byte-level helpers are a section of their own, `resfmt`
(`cppsrc/rtl/_res.md`), reached by the declarations in `cppsrc/resources/Resources.kt`; they
are not part of `strops`, because a shared section is emitted whole.

`bun tools/stress.js` is **61/61**, and the bootstrap fixed point (`bun tools/bootstrap.js`)
holds byte for byte.

Not done yet: a resource *section* helper (deliberately - a section is a key prefix), a
name-to-index shortcut for lookups (`Resources.get` is a linear scan of a handful of entries;
a `Dictionary` would need a startup build the `StrView`s make unnecessary for this size), and
a resource *program* (a resource that is Simse and that the compiler runs).
