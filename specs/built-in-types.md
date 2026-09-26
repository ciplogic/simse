# Built-in types

Status: design baseline — built-in types for the first implementation.

## Scalar types

| Type | Representation | Notes |
| --- | --- | --- |
| `Char` | signed 8-bit integer | Exactly equivalent to `Int8` |
| `Int8` | signed 8-bit integer | Range is `-128..127` |
| `Int16` | signed 16-bit integer | |
| `Int32` | signed 32-bit integer | |
| `Int64` | signed 64-bit integer | |
| `Float32` | 32-bit IEEE-754 floating point | |
| `Float64` | 64-bit IEEE-754 floating point | |

`Char` is not a Unicode scalar type: it has the same representation, range, layout, and
arithmetic behavior as `Int8`.

`Int` is the default integer type and is an alias of `Int32`.

### `Bool`

Status: required for the first implementation.

`Bool` is a built-in type with exactly two values, `true` and `false`.
`true` and `false` are reserved keywords rather than ordinary identifiers.

### Character literals

Status: required for the first implementation.

`'c'` denotes a `Char` (`Int8`) value. The supported escapes are
`\n`, `\r`, `\t`, `\0`, `\\`, `\'`, and `\"`. An unknown escape is an error.

### String literals

Status: implemented (`stress/raw-strings`).

`"..."` is a string literal. Its escapes are the character literals' set, plus `\xNN`
with any run of hex digits and an octal escape of up to three digits; each denotes one
byte (`cppsrc/common/literals.kt` is the one decoder).

A **backtick string** is the same value written raw:

```
val text: Str = `first line
second "line" with a \ backslash`
```

There is no escape and no interpolation: the next backtick ends the string, so a
backtick cannot appear inside, and every byte between the two backticks is the content -
a real newline included, which is what lets a string span lines. A line ending is
normalized to one `\n` (CRLF and a lone CR both), so a source file's line endings do not
change the value. A `"` and a `\` are written as they stand; that - and the multi-line
form - is what makes a backtick string the way to hold another language's source text
verbatim (the profiler's emitted C++, `cppsrc/profiling/Profiling.kt`).

Both forms are one entry in the program's literal pool, and at a site a `StrView` into
it, exactly alike (`impl_specs/rtl-abi.md`): a backtick string is spelled as the
ordinary `"..."` literal denoting the same bytes, so nothing downstream distinguishes
them.

## Operators

Status: required for the first implementation.

The binary operators, loosest first (each row binds tighter than the one above it):

| Operators | Note |
| --- | --- |
| `\|\|` | logical or, short-circuit |
| `&&` | logical and, short-circuit |
| `==` `!=` | equality, on any comparable pair |
| `<` `>` `<=` `>=` | ordering |
| `\|` `^` `&` | bitwise or, xor, and - integers (and `Bool`, where they are the non-short-circuit forms) |
| `<<` `>>` | shifts - integers |
| `+` `-` | additive; `+` also concatenates `Str` |
| `*` `/` `%` | multiplicative |

All of them are left-associative and none is an assignment. `!` negates a `Bool`; `~`
(bitwise not) and the unsigned shifts are not implemented.

The bitwise pair binds tighter than a comparison (Python's and Rust's order, not C's),
and the shifts sit between `+` and `&`.

### Compound assignment and the step operators

`x op= v` for every binary operator whose operation is a value (`+=`, `-=`, `*=`, `/=`,
`%=`, `&=`, `|=`, `^=`, `<<=`, `>>=`), plus the step forms `i++` and `i--`: the target's
*place* is located once, read, folded and written back through it, so nothing is copied
and a receiver or an index with an effect runs once. `specs/memory-model.md` owns the
rule; there is no `&&=`, `||=`, or relational compound form.

## `List<T>`

`List<T>` is a mutable value type over the inline `SmallVector<4, T>` buffer, with
deep-copy semantics (`containers.md`). Copying copies its elements and storage; `&List<T>`
boxes a copy, `*List<T>` borrows existing storage. Elements can be read and assigned, and
the sequence grown, cleared, inserted into, and removed from; mutating one list value does
not mutate a copy.

## `Str`

`Str` is `SmallVector<24, Char>` with a reserved trailing zero byte: 23 characters inline,
spilling to a NUL-terminated heap buffer for C interop. `Char` is signed 8-bit, so `Str` is a
mutable byte string (`std::string`-like): characters can be read and assigned, and the string
appended to, cleared, resized, or have ranges modified. Copying deep-copies its characters
and storage; the terminating NUL is not part of the logical length (`containers.md`,
`core-types.md`).

### Minimal `Str` API

Status: required for the first implementation.

`Str()` constructs an empty string. The minimally supported operations are:

- `size(): Int`;
- indexing `s[i]`, for both reading and assignment;
- `append(ch: Char)` and `append(s: Str)`;
- `clear()`;
- `resize(n: Int)`; and
- `data()`, which returns a NUL-terminated buffer for C interop.

`reserve` and the two in-place appends are also on the emitted surface
(`cppsrc/rtl/rtl.kt`):

- `reserve(count: Int)` grows the buffer once for a run of appends. A hint, not a length:
  the string keeps its size, and appends past the reservation grow it as usual.
- `appendStr(s: Str)` appends in place (`out = out + s` rebuilds the whole buffer);
  `appendStrPtr(s: *Str)` is the same for a text the caller only borrows.

`fmtStr(fmt: StrView, items: *List<Str>): Str` writes one template whole, replacing its
`|` characters in order with one item each. The format is a `StrView` because a format is
almost always a literal, which already is a view. The trailing arguments pack into the
`*List<Str>` (`fmtStr("| |", "a", "b")`). The result length is known up front, so it is
assembled in one buffer with no `reserve`.

A fixed shape means one item per `|`: a call whose points and items do not line up gets
the format back, unfilled, rather than a half-filled result.

Indexing and member calls are permitted directly on a `&Str` and on a `*Str`,
with automatic dereference (see `memory-model.md`).

### Search and substring operations

Status: required for the first implementation.

The bootstrap RTL also provides these `Str` operations (native extensions):

- `find(sub: Str): Int` / `indexOf(sub: Str): Int` - the index of the first
  occurrence of `sub`, or `-1` when absent (the language's spelling of C++
  `npos`);
- `lastIndexOf(sub: Str): Int` - the index of the last occurrence, or `-1`;
- `substr(start: Int, len: Int): Str` - a substring; `start` is clamped to
  `[0, size]` and `len` may run to the end;
- `charAt(index: Int): Char` - the byte at `index` (unchecked);
- `startsWith(prefix: Str): Bool`;
- `endsWith(suffix: Str): Bool`;
- `replace(from: Str, to: Str): Str` - replaces every occurrence of `from`;
- `trim(): Str` - strips leading and trailing whitespace (space, tab, newline,
  carriage return);
- `split(separator: Str): List<Str>` - splits on every occurrence; an empty
  separator yields the whole string as a single element;
- `toUpper(): Str` / `toLower(): Str` - ASCII/byte case folding; and
- `isEmpty(): Bool`.

### Views: `Span<T>` and `StrView`

Status: implemented in the bootstrap RTL (`cppsrc/rtl/Span.kt`, `cppsrc/rtl/StrView.kt`,
`cppsrc/rtl/span.hpp`, `cppsrc/rtl/strview.hpp`).

A `Span<T>` is a borrowed view over a contiguous run of `T`: a `*T` pointer plus a
length, nothing else. It copies and owns nothing, so it is valid only while its source is
alive and unchanged; a view over a buffer a stream owns is valid until that stream is
read again. `slice` stays a view; `substr` and `toString` are the owned copies.
`spanOf(items: *List<T>)` spans a list's elements and borrows the list, which must
outlive the span.

`Span<T>` is uniform over `T`:

- `size(): Int`;
- `isEmpty(): Bool`;
- `at(index: Int): T` (also `span[index]`) - the element as a *value*;
- `atPtr(index: Int): *T` - the element as a *place* (the address of the same element):
  nothing is copied, and a write through it reaches the span's source;
- `slice(start: Int): Span<T>` - from `start` to the end (unchecked); and
- `slice(start: Int, count: Int): Span<T>` - `count` elements from `start`
  (unchecked).

`StrView` is the view a string's bytes are read through, and it is a `Span<Char>`:
`typealias StrView = Span<Char>` (`cppsrc/rtl/StrView.kt`, `cppsrc/rtl/strview.hpp`). It is
what `FileStream.readLineView()` hands back and what `spanOfStr(text: *Str): StrView`
builds (borrowing the string). `size`/`isEmpty`/`at`/`slice`/`atPtr` are the span's own,
reached through the alias; it adds the byte surface below:

- `charAt(index: Int): Char` - the byte at `index` (unchecked);
- `find(sub: Str): Int` / `indexOf(sub: Str): Int` - the index of the first
  occurrence of `sub`, or `-1` (compared in place, nothing copied);
- `startsWith(text: Str): Bool`;
- `startsWithPtr(text: *Str, length: Int): Bool` - the same comparison against a
  string this view does not own, by raw pointer and with its length already
  known;
- `substr(from: Int, count: Int): Str` - the owned copy, with `from` clamped to
  `[0, size]` and `count` allowed to run to the end, like `Str.substr`; and
- `toString(): Str` - the owned copy of the whole view.

### String parsing

Status: required for the first implementation.

Parsing an entire string returns `Opt` and never throws:

- `toInt(): Opt<Int>`; and
- `toFloat(): Opt<Float64>`.

A string that is empty, malformed, or has trailing characters yields
`Opt.none()`.

## Character predicates and numeric conversions

Status: required for the first implementation.

`Char` is a signed 8-bit integer. It provides the predicates `isDigit()`,
`isAlpha()`, `isAlphaOrDigit()`, and `isSpace()` (space, tab, newline, or
carriage return).

Every scalar has a `toString(): Str`:

- `Int`, `Int8`, `Int16`, `Int32`, `Int64`, `Float32`, `Float64`, and `Char` use
  the corresponding `std::to_string` conversion (`Char` stringifies as its integer
  value); and
- `Bool.toString()` yields `"true"` or `"false"`.

The numeric builtins `min(a, b)` and `max(a, b)` return the smaller/larger of two
numeric values of the same type.

## `RawArray<T>`

`RawArray<T>` is a spelling alias for `*T`: an unmanaged pointer to the first element of a
contiguous region of `T`. It carries no element count, does not participate in reference
counting, and does not keep the allocation alive. Pointer indexing requires the caller to
know the valid bounds. A null or dangling `RawArray<T>` may be carried, but reading or
writing through it is unchecked undefined behavior.

`RawArray<T>` is intended for FFI, allocators, and low-level runtime code. It is
not interchangeable with `Array<T>`.

## `Array<T>`

`Array<T>` is a heap-backed, reference-counted contiguous array: the variable stores a
counted reference to one allocation, assignment copies the reference and increments its
count, and the allocation is freed when the last reference is dropped. Elements are not
copied by assignment. The allocation holds the common ref-counted header
(`[reference count][typeId]`) and the element count followed by the elements, in one
contiguous block (`ref-counted-layout.md`); the header and elements are allocated
together, and an implementation must not allocate a separate element buffer. The `typeId`
is unused for dispatch or casting.

`Array<T>` is already a reference type. `&Array<T>` is not allowed, and
`*Array<T>` is a raw pointer to the array allocation/header. Use indexing on
`Array<T>` for normal element access. A null `Array<T>` handle is permitted;
use `Opt<Array<T>>` when absence should be explicit.

The array count is immutable after construction. The elements themselves are mutable
through indexing or other element-update operations. An array cannot be grown or shrunk;
`List<T>` is the resizable sequence type.

### Minimal `Array<T>` API

The minimally supported `Array<T>` operations are:

- `count(): Int`, the element count stored at the front of the allocation;
- indexing `array[i]`, for reading and for element assignment, with unchecked
  bounds (indexing a `List<T>` or an `Array<T>` is the same operation);
- `arrayEmpty<T>(): Array<T>`, the **shared** empty array of `T`: every call
  returns the same zero-length array, so an empty array never allocates, and a
  defaulted `Array<T>` is that array rather than a null handle;
- `Array<T>.toList(): List<T>`, the growable copy - an array is fixed length, so adding an
  element goes through a list, and
- `List<T>.toArray(): Array<T>`, the fixed-length copy of a list
  (`specs/containers.md`).

Both conversions copy the elements; only the allocation is shared (assignment of an
array copies the handle, not the elements).

## Relationship between containers

| Type | Storage | Copy/assignment behavior | Owns lifetime? |
| --- | --- | --- | --- |
| `List<T>` | Inline buffer, spills to heap | Deep copy | Yes, as a value |
| `Str` | Inline byte buffer, spills to heap | Deep copy | Yes, as a value |
| `RawArray<T>` | Raw `T*` | Pointer copy only | No |
| `Array<T>` | Ref-counted header plus contiguous elements | Shared allocation | Yes, through reference count |
