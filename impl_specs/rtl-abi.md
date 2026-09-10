# RTL ABI (bootstrap slice)

Status: decision recorded for the first end-to-end slice (T6).

This document fixes the runtime representation that generated C++ targets and
records where that representation diverges from `specs/`. It exists so the
bootstrap shims do not silently become the de-facto specification.

## Decision

For the first end-to-end slice, generated C++ targets the **current
`cppsrc/rtl` shims** as-is:

- `Str = std::string`
- `List<T> = std::vector<T>`
- `PList<T> = std::shared_ptr<List<T>>`
- `Array<T>` = the shim struct holding `int _count` and `std::shared_ptr<T[]>`
- `Dictionary<K, V> = std::unordered_map<K, V>`
- `Opt<T>` and `Res<T>` = the shim structs (over `std::optional` / a value plus
  error `Str`)
- `&T` lowers to `std::shared_ptr<T>`; `*T` lowers to `T*`
- `Cursor<T>` = the immutable list-view shim (`cppsrc/rtl/cursor.hpp`)

The spec layouts — `SmallVector` small-buffer optimization and the ref-counted
`[refcount][typeId][value]` header — are **not** implemented in this slice. This
is deliberate: the goal is one compiling, debugger-friendly translation unit, not
the final memory layout. Every divergence is listed below and is deferred, not
resolved.

`typeId` is currently unused by the runtime and is not stored. Nothing in the
language subset needs it (no virtual dispatch, no dynamic casts).

## Simse type -> C++ representation

| Simse type | C++ representation | Notes |
| --- | --- | --- |
| `Int` | `Int` (`Int32`) | default integer |
| `Int8` / `Int16` / `Int32` / `Int64` | `Int8` / `Int16` / `Int32` / `Int64` | `std::intN_t` aliases |
| `Float32` / `Float64` | `Float32` / `Float64` | `float` / `double` |
| `Char` | `Char` (`std::int8_t`) | byte value; streams print it as a character |
| `Bool` | `Bool` (`bool`) | printed as `true`/`false` (see below) |
| `Str` | `Str` (`std::string`) | mutable byte string |
| `Unit` | `void` | only valid as a function return type |
| `List<T>` | `List<T>` (`std::vector<T>`) | value type, deep copies |
| `Array<T>` | `Array<T>` (shim struct) | shared allocation, fixed length |
| `RawArray<T>` | `RawArray<T>` (`T*`) | unmanaged pointer |
| `&T` | `std::shared_ptr<T>` | counted reference |
| `*T` | `T*` | raw pointer |
| `Opt<T>` | `Opt<T>` (shim over `std::optional<T>`) | |
| `Res<T>` | `Res<T>` (shim: `Value`, `Error`) | failure = non-empty `Error` |
| `Dictionary<K, V>` | `Dictionary<K, V>` (`std::unordered_map`) | |
| `PList<T>` | `PList<T>` (`std::shared_ptr<List<T>>`) | the `&List<T>` spelling |
| `Cursor<T>` | `Cursor<T>` (shim struct) | immutable list view; `next`/`slice` return new cursors |
| `SmallVector<N, T>` | `SmallVector<T, N>` shim | unused by the v1 subset |
| user `data class C` | `struct C` with a field-order constructor | |
| user `enum E` | `enum class E` | explicit values when given |
| callable `(A, B) -> R` | `Func<R(A, B)>` (`std::function`) | `Unit` return -> `void` |

## Divergences from `specs/`

These are the known, accepted differences while the shims are kept. They are
deferred to a later runtime-alignment task; the shim must not be treated as the
normative layout.

1. **`Str` layout.** Spec: inline `SmallVector<24, Char>` with a reserved NUL and
   a 23-byte inline capacity (`specs/containers.md`, `specs/built-in-types.md`).
   Shim: `std::string` (small-string optimization only, unspecified capacity).
2. **`List<T>` layout.** Spec: a value type forwarding to a `SmallVector<4, T>`
   with an inline capacity of 4 (`specs/containers.md`). Shim: `std::vector<T>`,
   with no inline-element guarantee.
3. **Index width / packing.** Spec: 32-bit indices and sizes, 4-byte packing
   (`specs/containers.md`). Shim: `std::vector` uses `size_t`; no packing rule is
   enforced.
4. **`&T` representation.** Spec: a box with the common
   `[reference count][typeId][boxed value]` header
   (`specs/memory-model.md`, `specs/ref-counted-layout.md`). Shim:
   `std::shared_ptr<T>`; there is no `typeId`.
5. **`typeId`.** Spec: part of every ref-counted allocation header, currently
   unused for dispatch. Shim: not stored at all.
6. **`Array<T>` layout.** Spec: one allocation holding the header, element count,
   and elements contiguously (`specs/built-in-types.md`). Shim: `_count` plus a
   separate `std::shared_ptr<T[]>`. Assignment still shares the allocation, which
   matches the observable spec behavior.
7. **`Opt<T>` representation.** Spec: the core-types representation. Shim: a
   struct wrapping `std::optional<T>` (an extra layer). Behavior (`hasValue`,
   `value`) matches the documented API.
8. **`Res<T>` failure sentinel.** Not spelled out in `specs/`; the shim defines
   `isOk()` as "`Error` is empty". Generated code does not depend on this beyond
   calling `isOk()`.
9. **`SmallVector` operations.** The shim is a layout shell with no operations and
   reversed template parameters (`SmallVector<T, N>` vs the Simse spelling
   `SmallVector<N, T>`). The emitter maps `SmallVector<N, T>` to `SmallVector<T, N>`
   (`impl_specs/reification.md`) but the v1 subset does not exercise its operations.

## Operations the emitter needs

No new RTL operations were required for the v1 subset. Specifically:

- Boxing (`&value`) lowers to `std::make_shared<std::remove_cvref_t<decltype(...)>>(value)`,
  which comes from `<memory>` via `cppsrc/rtl/simse.hpp`.
- `*ref` lowers to `.get()` on a `std::shared_ptr` and to `*p` on a raw pointer;
  `copy(x)` lowers to `*(x)` for references/pointers and a plain copy otherwise.
- `&List<T>()` construction would use the existing `makeList<T>()`, but the v1
  subset does not emit it (see gaps below).

### `Cursor<T>`

`Cursor<T>` is an immutable, `Span`-like view over a `List<T>`, the language's
iteration idiom (`for`/range-for is deferred). It has a single field-order
constructor and by-value helpers, and codegen maps member calls to the C++
members:

| Member | C++ | Semantics |
| --- | --- | --- |
| `hasValue(): Bool` | `len > 0` | more elements remain |
| `value(): T` | `(*source)[start]` | first remaining element (unchecked) |
| `next(): Cursor<T>` | `slice(1)` | advanced cursor (a new value) |
| `slice(count): Cursor<T>` | `{source, start+count, len-count}` | advanced by `count` (unchecked) |
| `size(): Int` | `len` | remaining count |

The `cursorOf(items: &List<T>): Cursor<T>` helper (RTL `simse_cursorOf`) covers
all of `items` from index 0. The struct is immutable: nothing mutates the
receiver.

### Lambdas

A lambda lowers to a C++ lambda with by-value captures, assignable to
`Func<Ret(Params)>`:

```text
(v: Int) -> v * 2      =>  [=](Int v) -> Int { return v * 2; }
(v: Int) -> { ... }    =>  [=](Int v) -> Ret { ... }
```

Parameter types come from the explicit annotations or from the expected callable
type (a `typealias` is expanded for this). The return type comes from the
expected callable type, else from a single trailing expression or a `return`.
Reference captures and explicit capture syntax are deferred.

### `print` / `println`

`print(x)` and `println(x)` are predeclared builtins lowered directly to the
standard stream:

```text
println(x)  ->  std::cout << std::boolalpha << (x) << std::endl;
print(x)    ->  std::cout << std::boolalpha << (x);
```

`std::boolalpha` makes `Bool` print as `true`/`false`; it does not affect
`Str`, `Char`, integer, or floating-point output. Each generated file includes
`<iostream>` (and `<type_traits>` for the boxing lowering).

## v1 subset gaps (emit a positioned "unsupported" error)

Now lowered: generic declarations, uses, and calls (via C++ templates; see
`impl_specs/reification.md`), generic `typealias`, `native fun` (see
`impl_specs/native-interop.md`), `switch`, `null`, generic-qualified static calls
(`Res<T>.ok(x)`, `Opt<T>.some(x)`), `Cursor<T>`, and lambdas with by-value
captures.

Still unsupported (each produces `<file>:<line>:<col>: unsupported: ...` rather
than a crash): namespaced native symbols, untyped parameters/fields, compound
assignment operators (`+=` etc.), lambda reference captures, and `for`/range-for
(use `Cursor<T>` and `while`). `List<T>.append`, `removeAt`, and `removeRange`
lower to the native extension symbols `simse_list_append` /
`simse_list_removeAt` / `simse_list_removeRange` declared in the RTL prelude;
`insert`, `clear`, and the remaining container methods are still emitted as
written and are not yet mapped (T12).
