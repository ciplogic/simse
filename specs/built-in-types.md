# Built-in types

Status: design baseline — built-in types for the first implementation.

## Scalar types

The language provides these fixed-width scalar types:

| Type | Representation | Notes |
| --- | --- | --- |
| `Char` | signed 8-bit integer | Exactly equivalent to `Int8` |
| `Int8` | signed 8-bit integer | Range is `-128..127` |
| `Int16` | signed 16-bit integer | |
| `Int32` | signed 32-bit integer | |
| `Int64` | signed 64-bit integer | |
| `Float32` | 32-bit IEEE-754 floating point | |
| `Float64` | 64-bit IEEE-754 floating point | |

`Char` is not a Unicode scalar type. It is an 8-bit signed value and has the
same representation, range, layout, and arithmetic behavior as `Int8`.

`Int` is the default integer type and is currently an alias of `Int32`. Code
that requires a fixed width should use an explicit integer type.

### `Bool`

Status: required for the first implementation.

`Bool` is a built-in type with exactly two values, `true` and `false`.
`true` and `false` are reserved keywords rather than ordinary identifiers.

### Character literals

Status: required for the first implementation.

`'c'` denotes a `Char` (`Int8`) value. The supported escapes are
`\n`, `\r`, `\t`, `\0`, `\\`, `\'`, and `\"`. An unknown escape is an error.
`Char` is not a Unicode scalar.

```text
val newline: Char = '\n'
val quote: Char = '\''
val letter: Char = 'a'
```

## `List<T>`

`List<T>` is a mutable value type with deep-copy semantics. It stores a
growable sequence of `T` values using the inline-buffer representation in
`containers.md`:

```text
List<T> = class {
    values: SmallVector<4, T>
}
```

Copying a list copies its elements and storage. Taking `&List<T>` explicitly
boxes a copy, while `*List<T>` borrows existing storage without copying.
Lists are mutable: elements can be read and assigned, and the sequence can be
grown, cleared, or have elements inserted and removed. Mutating one list value
does not mutate a copied list value.

## `Str`

`Str` is a mutable byte-string value represented as `SmallVector<24, Char>`
with one reserved trailing zero byte. It stores 23 non-zero characters inline
and spills to a heap buffer for longer strings. Both inline and heap storage
are terminated by `0` for C interoperation.

```text
Str = SmallVector<24, Char>
```

Because `Char` is signed 8-bit, `Str` is a byte string rather than a Unicode
string. `Str` is mutable like a C++ `std::string`: characters can be read and
assigned, and the string can be appended to, cleared, resized, or have ranges
modified. Copying a `Str` deep-copies its characters and storage. NUL
termination is maintained after every mutation; the trailing terminator is not
part of the logical string length.

### Minimal `Str` API

Status: required for the first implementation.

`Str()` constructs an empty string. The minimally supported operations are:

- `size(): Int`;
- indexing `s[i]`, for both reading and assignment;
- `append(ch: Char)` and `append(s: Str)`;
- `clear()`;
- `resize(n: Int)`; and
- `data()`, which returns a NUL-terminated buffer for C interop.

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

Status: implemented in the bootstrap RTL (`cppsrc/rtl/Span.kt` and
`cppsrc/rtl/StrView.kt`, `cppsrc/rtl/span.hpp` and `cppsrc/rtl/strview.hpp`).

A `Span<T>` is a borrowed view over a contiguous run of `T`: a `*T` pointer plus
a length, nothing else. It copies nothing and owns nothing, so it is valid only
while its source is alive and unchanged; a view over a buffer a stream owns is
valid until that stream is read again. Views are the idiom for parsing one buffer
(tokenize, split, scan) without allocating per token: `slice` stays a view,
`substr` and `toString` are the owned copies. `spanOf(items: *List<T>)` spans a
list's elements and borrows the list, which must outlive the span.

`Span<T>` is uniform over `T`:

- `size(): Int`;
- `isEmpty(): Bool`;
- `at(index: Int): T` (also `span[index]`);
- `slice(start: Int): Span<T>` - from `start` to the end (unchecked); and
- `slice(start: Int, count: Int): Span<T>` - `count` elements from `start`
  (unchecked).

`StrView` is the view a string's bytes are read through: it embeds a
`Span<Char>` and adds the byte surface. The span is embedded rather than aliased,
because an alias does not survive the emitter's receiver-type lookup. It is what
`FileStream.readLineView()` hands back and what `spanOfStr(text: *Str): StrView`
builds (borrowing the string):

- `size(): Int`; `isEmpty(): Bool`; `at(index: Int): Char` (also `view[index]`);
- `slice(start: Int): StrView` / `slice(start: Int, count: Int): StrView` - the
  same two forms, staying a view;
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

Indexing and member calls are permitted directly on a `*Str`, with automatic
dereference, so a view's body can read through its source without an explicit
dereference.

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

`RawArray<T>` is a spelling alias for `*T`:

```text
RawArray<T> = *T
```

It is an unmanaged pointer to the first element of a contiguous region of `T`.
It carries no element count, does not participate in reference counting, and
does not keep the allocation alive. Pointer indexing requires the caller to
know the valid bounds. A null or dangling `RawArray<T>` may be carried, but
reading or writing through it is unchecked undefined behavior.

`RawArray<T>` is intended for FFI, allocators, and low-level runtime code. It is
not interchangeable with `Array<T>`.

## `Array<T>`

`Array<T>` is a heap-backed, reference-counted contiguous array. The variable
stores a counted reference to one allocation. Assignment copies the reference
and increments its count; the allocation is freed when the last reference is
dropped. Array elements are not copied by assignment.

The allocation contains its metadata and elements in one contiguous block. All
ref-counted allocations begin with the common `[reference count][typeId]`
header defined in `ref-counted-layout.md`:

```text
Array allocation:
+----------------------+  offset 0
| reference count      |  runtime-managed count
+----------------------+  offset sizeof(RefCount)
| typeId               |  compiler-generated type number
+----------------------+  offset sizeof(RefCount) + sizeof(TypeId)
| element count        |  number of T elements
+----------------------+  offset sizeof(RefCount) + sizeof(TypeId) + sizeof(Count)
| T[0]                 |
| T[1]                 |
| ...                  |
| T[count - 1]         |
+----------------------+
```

The elements begin immediately after the common header and element-count field,
subject only to the language's alignment requirements. The header and elements
are allocated together; an implementation must not allocate a separate element
buffer for `Array<T>`. The `typeId` is currently unused; it does not provide
virtual dispatch or dynamic casts.

`Array<T>` is already a reference type. `&Array<T>` is not allowed, and
`*Array<T>` is a raw pointer to the array allocation/header. Use indexing on
`Array<T>` for normal element access. A null `Array<T>` handle is permitted;
use `Opt<Array<T>>` when absence should be explicit.

```text
var first: Array<Int32> = Array<Int32>(3)
first[0] = 10

var second: Array<Int32> = first   // shares the same allocation
second[0] = 20
first[0]                         // 20
```

The array count is immutable after construction for the initial implementation.
The elements themselves are mutable through indexing or other element-update
operations. An array cannot be grown or shrunk; `List<T>` is the resizable
sequence type.

### Minimal `Array<T>` API

The minimally supported `Array<T>` operations are:

- `count(): Int`, the element count stored at the front of the allocation;
- indexing `array[i]`, for reading and for element assignment, with unchecked
  bounds (indexing a `List<T>` or an `Array<T>` is the same operation);
- `arrayEmpty<T>(): Array<T>`, the **shared** empty array of `T`: every call
  returns the same zero-length array, so an empty array never allocates, and a
  defaulted `Array<T>` is that array rather than a null handle;
- `Array<T>.toList(): List<T>`, the growable copy - an array is fixed length, so
  adding an element goes through a list (`array.toList()` + `append` +
  `toArray()`), and
- `List<T>.toArray(): Array<T>`, the fixed-length copy of a list
  (`specs/containers.md`).

Both conversions copy the elements; only the *allocation* is shared (assignment
of an array copies the handle, not the elements).

```text
## Relationship between containers

| Type | Storage | Copy/assignment behavior | Owns lifetime? |
| --- | --- | --- | --- |
| `List<T>` | Inline buffer, spills to heap | Deep copy | Yes, as a value |
| `Str` | Inline byte buffer, spills to heap | Deep copy | Yes, as a value |
| `RawArray<T>` | Raw `T*` | Pointer copy only | No |
| `Array<T>` | Ref-counted header plus contiguous elements | Shared allocation | Yes, through reference count |
