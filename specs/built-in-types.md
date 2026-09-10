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

## Relationship between containers

| Type | Storage | Copy/assignment behavior | Owns lifetime? |
| --- | --- | --- | --- |
| `List<T>` | Inline buffer, spills to heap | Deep copy | Yes, as a value |
| `Str` | Inline byte buffer, spills to heap | Deep copy | Yes, as a value |
| `RawArray<T>` | Raw `T*` | Pointer copy only | No |
| `Array<T>` | Ref-counted header plus contiguous elements | Shared allocation | Yes, through reference count |
