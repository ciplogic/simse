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
    end of the file.
- A line whose first colon has *nothing* before it is not an entry, and neither is a line
  with no colon at all: those are prose, and ignored. A colon anywhere in prose therefore
  makes an entry of it - `:` is the separator, so text that is not an entry must not
  contain one.
- In a fenced block, a `\r` before the newline is not part of the line (the same rule the
  scanner applies), so a value does not depend on the file's line endings. Everything else
  is written as it is.
- A key written twice takes the **last** value, in its first-seen position.
- No escapes are interpreted: `` `\n` `` is a backslash and an `n`. What the file says is
  what the program gets - except for the one place that has to be escaped, the C++ literal
  the emitter pools the text as, which is its own business (`resQuoteLiteral`).

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

## The API

`Resources` is a prelude type (declarations in `cppsrc/rtl/resources.kt`, implementation in
`cppsrc/rtl/resources.hpp`) with **static** methods - `Resources.get(k)` is a static call,
the shape `Res<Str>.ok(x)` has:

```simse
fun get(key: Str): StrView          // the value, empty when the key is absent
fun has(key: Str): Bool
fun count(): Int                    // how many resources the program carries
```

The declarations are ordinary prelude natives on the type (`native("simse_resources_get")
fun get(this: Resources, key: Str): StrView`), which is what gives the checker their
signatures; the *emitter* spells every call as the static form `Resources::get(...)`, so
the C++ `struct Resources` carries the statics and nothing calls the natives.

The entries are built **once**, at startup, as a list of `StrView` pairs borrowing the
string table - `install` does that and nothing else does:

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
for (this_resource in keys) { ... }   // or: Resources.get("Profiling:Profile BootStrap")
```

## Where it lives

| piece | file |
| --- | --- |
| the format, the join, the discovery | `cppsrc/resources/Resources.kt` |
| discovery in the driver | one call to the module: `cppsrc/compiler/Driver.kt` |
| pooling and the table | the emitter: `cppsrc/codegen/Codegen.kt`, after `emitStringTable` |
| the API | `cppsrc/rtl/resources.kt` (surface), `cppsrc/rtl/resources.hpp` (implementation) |
| the static form | `Emitter.call` (the shape `Enum.fromInt` has) |
| the end-to-end case | `stress/resources/` |

## Status

Implemented for the whole path: discovery, parse, join, pooling, the emitted table, and the
`Resources` API. `stress/resources` prints every shape the format has: a fenced block, an
inline value, an empty value, a missing key, a comparison against a literal, and the escapes
a value needs on the way into the pool (a double quote, a backslash, a tab, and a value
ending in a backslash). `bun tools/stress.js` is **45/45**, and the bootstrap fixed point
(`bun tools/bootstrap.js`) holds byte for byte.

Not done yet: a resource *section* helper (deliberately - a section is a key prefix), and
any name-to-index shortcut for lookups (`Resources.get` is a linear scan of a handful of
entries; a `Dictionary` would need a startup build that the `StrView`s make unnecessary for
this size).
