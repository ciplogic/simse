# Value types: inline containers

Status: design baseline — suitable for the first self-hosted implementation.

Generic types are reified; see `generics.md`. In particular, every distinct
`SmallVector<N, T>` instantiation is a distinct concrete type in generated C++,
and `List<T>` is the `N = 4` instantiation.

Value types store their storage inline where the variable lives and deep-copy
on copy. This document collects the container families built on that idea.

## Layout: packing and index width

- The language and its types are **4-byte packed**: values are laid out on
  4-byte boundaries and no type is aligned to more than 4 bytes
  (`specs/memory-model.md`, "Alignment and packing").
- `SmallVector` (and therefore `List`, which is `SmallVector<4, T>`) uses
  **32-bit indices/sizes**, so a single list can hold at most `2^32`-ish
  (~4.3 billion) elements.

## `SmallVector<N, T>`

`SmallVector<N, T>` is a **distinct** inline vector type with a small-buffer
optimization, following LLVM's `SmallVector<T, N>`: it stores up to `N`
elements **inline** (no heap allocation) and only spills to a heap buffer once
it grows past `N`. It is a value type and targets hot paths where most
collections stay tiny.

### Reified inline capacities

Each distinct inline capacity `N` is a **reified type** created by the compiler
for that constant. `N` is not carried as an abstract runtime parameter; the
layout is baked in per concrete type. `SmallVector<N, T>` is the canonical
source spelling; `SV<N, T>` and names such as `SV24<u8>` are compiler-internal
spellings and may be used in diagnostics, but are not required user syntax.

`SmallVector` layout (4-byte packing, 32-bit sizes):

- `4` bytes — `length`
- `4` bytes — `capacity` (doubles as inline-vs-heap discriminant:
  `capacity <= N` means inline, beyond that it refers to a heap allocation)
- `N * sizeof(T)` bytes — inline buffer

Worked example — `Str = SV24<u8>`:

- `4` length + `4` capacity + `24` buffer = **32 bytes**, a multiple of 4, so no
  padding. An empty-to-23-char `Str` is a single inline 32-byte object.

## `List<T>`

`List<T>` **is** `SmallVector<4, T>`: it is not a distinct type. The two were kept
separate during the bootstrap (`List` stayed on `std::vector` while
`SmallVector` was still a layout shell with no operations, where unifying them
would have been a distraction); now that `SmallVector` is a full inline vector
they are one type, so a list gets the inline buffer for free.

```text
List<T> = SmallVector<4, T>   // inline up to 4 elements, heap beyond
```

Consequence: lists up to 4 elements are stored inline with no heap allocation;
only longer lists allocate on the heap.
`List<T>` is mutable: it supports element assignment and sequence operations
such as append, insert, remove, and clear. These operations may change its
length and may move its heap buffer, so raw pointers into a list are not stable
across mutations that relocate storage.

### Minimal `List<T>` API

Status: required for the first implementation.

The minimally supported `List<T>` operations are:

- `size(): Int`;
- indexing `list[i]`, for both reading and assignment, where assignment requires
  a mutable variable;
- `append(value: T)`;
- `insert(index: Int, value: T)`;
- `removeAt(index: Int)`;
- `removeRange(start: Int, end: Int)`, which removes the half-open range
  `[start, end)`;
- `clear()`;
- `contains(value: T): Bool`, a linear membership test; and
- `sort(less: (T, T) -> Bool)`, an in-place sort using the comparator lambda.

Indexing and member calls are permitted directly on a `&List<T>` and on a
`*List<T>`, with automatic dereference (see `memory-model.md`). The `append`,
`removeAt`, `removeRange`, `contains`, and `sort` operations are exposed to the
compiler as native extensions (`impl_specs/tasks/12-container-methods-as-native.md`,
`impl_specs/tasks/20-dictionary-and-sort.md`). `insert` and `clear` are specified
but not yet mapped by the bootstrap emitter.

## `Dictionary<K, V>`

Status: required for the first implementation (the front end is written against
it).

`Dictionary<K, V>` is the built-in value dictionary (`dictionary.md`): a mutable
mapping from keys to values with value semantics. The bootstrap compiler exposes
it to Simse programs as native extensions (`impl_specs/rtl-abi.md`,
`impl_specs/tasks/20-dictionary-and-sort.md`):

- `dictionaryOf<K, V>(): Dictionary<K, V>` - the empty construction;
- `get(key: K): Opt<V>` - the value for `key`, or an empty `Opt` when absent;
- `has(key: K): Bool`; `size(): Int`;
- `insert(key: K, value: V)` - insert or replace;
- `remove(key: K)` - erase when present (a no-op otherwise);
- `keys(): List<K>` and `values(): List<V>`; and
- `clear()`.

`keys()`/`values()` return entries in the dictionary's iteration order, which is
**unspecified** (it follows the runtime hash table); sort the result for a
deterministic order. `get` is the safe accessor: there is no indexing operation
that manufactures a default value, matching the language's no-null policy.

### Iteration: `Cursor<T>`

Status: required for the first implementation.

There is no `for`/range-for; iteration uses an immutable, `Span`-like cursor and
`while`:

```text
var c: Cursor<Int> = cursorOf(items)
while (c.hasValue()) {
    process(c.value())
    c = c.next()
}
```

`Cursor<T>` covers a contiguous range of a `&List<T>`. It is a value, so `next`
and `slice` return NEW cursors and the receiver is never mutated:

- `hasValue(): Bool`; `value(): T`; `size(): Int`;
- `next(): Cursor<T>` (advance one); and
- `slice(count: Int): Cursor<T>` (advance `count`).

`cursorOf(items: &List<T>): Cursor<T>` covers all of a list from index 0. Reading
`value` past the end and slicing beyond the remaining length are unchecked, like
other container indexing. The cursor holds a counted reference to the list, so it
keeps the list alive without copying its elements. See `impl_specs/tasks/16-cursor.md`
and `impl_specs/rtl-abi.md`.

## `Str`

`Str` is the inline string type. It is the reified `SmallVector<24, Char>`
described above,
built for C interop: the inline buffer is **always NUL-terminated** (a trailing
`\0` is reserved), so `Str::data()` is always a valid C string with no copy.
This leaves **23 usable inline bytes** (buffer is still 24 → `Str` is 32 bytes
total); strings up to 23 chars stay inline and beyond that spill to the heap
(also NUL-terminated). `Str` is mutable like `std::string`: its characters,
length, and contents may be changed, and it may be appended to, cleared,
resized, or have ranges modified. Copying a `Str` deep-copies its text. The
terminating NUL is maintained automatically and is not included in `length`.

```text
Str = SmallVector<24, Char>   // 4 length + 4 capacity + 24-byte buffer
```

`Char` is signed 8-bit, so `Str` is a byte string. See `built-in-types.md` for
the complete built-in type list and for `Array<T>` and `RawArray<T>`.

## Aliasing and relocation

Value containers may contain counted references (`&T`), but may not contain raw
pointers (`*T`) as ordinary fields. A raw pointer is an explicitly unsafe,
non-owning borrow and cannot be stored in a value that can be copied or moved.
This prevents a copied or relocated `SmallVector` from leaving an internal raw
pointer pointing at the old storage. Programs that need an address-bearing
object must use a stable counted box (`&T`) and take a raw pointer only for a
lexically bounded unsafe operation.

`SmallVector` copy and move operations preserve value semantics: elements are
copied or moved, and heap storage is never shared implicitly. Implementations
must update the inline/heap representation before exposing any raw pointer.

`Dictionary<K, V>` is the built-in value dictionary. Its value semantics and
currently unspecified hashing rules are defined in `dictionary.md`.
