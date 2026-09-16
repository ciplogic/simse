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

### Constructing a list

`listOf<T>(a, b, c)` builds a list **from its values**, in that order, and is one
instruction (`Pack`, `impl_specs/linear-il.md`). Up to four elements live in the
list's inline buffer, so a short literal allocates nothing:

```simse
val keywords: List<Str> = listOf<Str>("static", "var", "val")
val primes: List<Int> = listOf(2, 3, 5, 7)   // the element type is inferred
val empty: List<Str> = listOf<Str>()
```

`List<T>(...)` is the RTL's own construction, and a **count**:

```simse
val zeros: List<Int> = List<Int>(3)              // three default elements
val flags: List<Bool> = List<Bool>(4, false)    // four copies of false
```

so a literal can never be mistaken for a size (`List<Str>("a", "b")` is a count
construction that takes no `Str`, and is refused rather than silently reinterpreted).
`Array<T>(n)` is a count construction for the same reason - an array is fixed-length,
so a count is what it is built from (`specs/built-in-types.md`) - and only a list is
built from values.

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
- `contains(value: T): Bool`, a linear membership test;
- `sort(less: (T, T) -> Bool)`, an in-place sort using the comparator lambda; and
- `toArray(): Array<T>`, the fixed-length copy (specs/built-in-types.md), the
  counterpart of `Array<T>.toList()`.

Indexing and member calls are permitted directly on a `&List<T>` and on a
`*List<T>`, with automatic dereference (see `memory-model.md`). The `append`,
`removeAt`, `removeRange`, `contains`, and `sort` operations are exposed to the
compiler as native extensions (`impl_specs/tasks/12-container-methods-as-native.md`,
`impl_specs/tasks/20-dictionary-and-sort.md`). `insert` and `clear` are specified
but not yet mapped by the bootstrap emitter.

### Iteration

A container is iterable by `for`, in order and without an index of its own:

```simse
for (value in items) { ... }
for ((value, index) in items) { ... }
```

The prelude writes `List<T>.smToYield(): ..T` in Simse, and one per container besides it
(`Array<T>`, `Span<T>`): a state machine that walks the container in order
(`specs/functions.md`, `impl_specs/for.md`). The loop is therefore the `while` the language
writes around a machine, `continue` still moves it on, and the iteration is over the
container's own order, reading each element once. `Dictionary<K, V>` has no `smToYield`
yet, so it is walked with an index loop over `keys()`.

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

### `Span<T>`

Status: required for the first implementation.

`Span<T>` is a borrowed view over a contiguous run of `T`: a pointer and a
length, nothing else. The language's spelling of C#'s `Span<T>`, it copies
nothing and owns nothing, so it is valid only while the memory it points at is
alive and unmodified. A span is iterable by `for` like any other container (the prelude
has `Span<T>.smToYield`), and the state machine walks it with an index; a `while` that
slices is the way to walk *storage* without building a machine (`functions.md`):

```text
var span: Span<Int> = spanOf(*items)
while (!span.isEmpty()) {
    x = span[0]
    span = span.slice(1)
}
```

A span is a value, so `slice` returns a NEW span and the receiver is never
mutated:

- `size(): Int`; `isEmpty(): Bool`; `at(index: Int): T` (also `span[index]`);
- `slice(start: Int): Span<T>` (from `start` to the end); and
- `slice(start: Int, count: Int): Span<T>` (`count` elements from `start`).

Both slice forms are unchecked, like other container indexing. `spanOf(items:
*List<T>): Span<T>` covers all of a list from index 0, **borrowing** the list; the
source must outlive the span (`&items` would box a *copy*).

Text has its own view on top of a span: `StrView` embeds a `Span<Char>` and adds
the byte operations `charAt`, `find`/`indexOf`, `startsWith`, `startsWithPtr`,
`substr`, and `toString` (`substr`/`toString` are the owned copies), and
`spanOfStr(text: *Str): StrView` builds one from a string. `specs/built-in-types.md`
("Views") has the full surface; `impl_specs/rtl-abi.md` has how each one lowers.

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
