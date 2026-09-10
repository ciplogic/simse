# Generic reification (bootstrap slice)

Status: decision recorded for T9.

This document fixes how generics are reified on the C++ backend. The decision is
deliberately not redesigned here; it is recorded and then implemented.

## Decision

Reification is realized on the C++ backend by **emitting C++ templates**.
Distinct Simse instantiations become distinct C++ types through ordinary C++
template instantiation. There is **no erasure** and no universal runtime object.
Hand-emitting monomorphized concrete types is explicitly deferred.

Mapping from Simse declarations to C++:

| Simse declaration | Emitted C++ |
| --- | --- |
| `data class C<T...>(...)` | `template <class T...> struct C { ... };` |
| `fun f<T...>(...) { ... }` | `template <class T...> ... f(...) { ... }` |
| `typealias A<T...> = Target` | `template <class T...> using A = Target;` |
| generic use, e.g. `Pair<Int, Str>` | template arguments: `Pair<Int, Str>` |
| generic call, e.g. `identity<Int>(x)` | `identity<Int>(x)` |

Type parameters are in scope inside the declaration that introduces them, so a
field or parameter written `T` lowers to `T`.

## Built-in generic mapping

| Simse | C++ |
| --- | --- |
| `List<T>` | `List<T>` |
| `PList<T>` / `&List<T>` | `PList<T>` |
| `Array<T>` | `Array<T>` |
| `RawArray<T>` | `RawArray<T>` |
| `Opt<T>` | `Opt<T>` |
| `Res<T>` | `Res<T>` |
| `Dictionary<K, V>` | `Dictionary<K, V>` |
| `SmallVector<N, T>` | **`SmallVector<T, N>`** (value parameter moves last) |

The `SmallVector` reorder exists because the Simse spelling puts the inline
capacity `N` first while the RTL template is declared `SmallVector<T, int N>`.

## Instantiation tracking

The compiler still tracks the set of concrete instantiations a program
references, and reports instantiation arity/argument errors (`List<Int, Str>` and
`Pair<Int>` for a two-parameter `Pair` are rejected with a positioned
diagnostic). It does **not** emit unused instantiations: because the backend
emits templates, a concrete type is only produced at a use site, so
declarations that are never instantiated generate no code. Value parameters such
as `SmallVector<4, T>` participate in the C++ type itself
(`SmallVector<T, 4>`), so distinct capacities are distinct types.

## Divergences / notes

- The RTL `SmallVector` is a layout shell with no operations; the mapping is
  recorded and used for type names but the v1 subset does not exercise its
  operations.
- Generic static-member dispatch (`Res<T>.ok(...)`) is not lowered yet; it
  reports a positioned `unsupported` diagnostic.
