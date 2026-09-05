# Value types: inline containers

Status: design baseline — suitable for the first self-hosted implementation.

Value types store their storage inline where the variable lives and deep-copy
on copy. This document collects the container families built on that idea.

## Layout: packing and index width

- The language and its types are **4-byte packed**: values are laid out on
  4-byte boundaries.
- `SmallVector` and `List` use **32-bit indices/sizes**, so a single list can
  hold at most `2^32`-ish (~4.3 billion) elements.

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

`List<T>` is a separate type, **not** a type alias of `SmallVector<T, 4>`. It
owns a field that stores the buffer and *forwards* its operations to that
field:

```text
class List<T> {
    values: SmallVector<T, 4>   // inline up to 4 elements, heap beyond
    // pushes, indexing, size, ... are forwarded to `values`
}
```

Consequence: lists up to 4 elements are stored inline with no heap allocation;
only longer lists allocate on the heap.

## `Str`

`Str` is the inline string type. It is the reified `SV24<u8>` described above,
built for C interop: the inline buffer is **always NUL-terminated** (a trailing
`\0` is reserved), so `Str::data()` is always a valid C string with no copy.
This leaves **23 usable inline bytes** (buffer is still 24 → `Str` is 32 bytes
total); strings up to 23 chars stay inline and beyond that spill to the heap
(also NUL-terminated). Copying a `Str` deep-copies its text.

```text
Str = SV24<u8>   // 4 length + 4 capacity + 24 buffer = 32 bytes, NUL-terminated
```

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
