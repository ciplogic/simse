# RTL ABI (bootstrap slice)

Status: decision recorded for the first end-to-end slice (T6).

This document fixes the runtime representation that generated C++ targets and
records where that representation diverges from `specs/`. It exists so the
bootstrap shims do not silently become the de-facto specification.

## Decision

For the first end-to-end slice, generated C++ targets the **current
`cppsrc/rtl` shims** as-is:

- `Str = SmString` (the spec layout: inline `SmallVector<char, 24>` plus the
  terminating NUL; `std::string` behind `SIMSE_STR_STD_STRING`)
- `List<T> = SmallVector<T, 4>` (the spec layout; `std::vector<T>` behind
  `SIMSE_LIST_STD_VECTOR`)
- `PList<T> = std::shared_ptr<List<T>>`
- `Array<T>` = the shim struct: one `std::shared_ptr` handle to a count-first block
  (`cppsrc/rtl/containers.hpp`)
- `Dictionary<K, V> = std::unordered_map<K, V>`
- `Opt<T>` and `Res<T>` = the shim structs (over `std::optional` / a value plus
  error `Str`)
- `&T` lowers to `std::shared_ptr<T>`; `*T` lowers to `T*`
- `Span<T>` = the borrowed view shim: a `*T` pointer plus a length
  (`cppsrc/rtl/span.hpp`)

The ref-counted `[refcount][typeId][value]` header is **not** implemented in this
slice, and nothing in the runtime allocates one. The `SmallVector`
small-buffer optimization *is* implemented and is the default backing for both
`List<T>` and `Str`. The goal remains one compiling, debugger-friendly
translation unit, not the final memory layout; every remaining divergence is
listed below and is deferred, not resolved.

`typeId` is currently unused by the runtime and is not stored. Nothing in the
language subset needs it (no virtual dispatch, no dynamic casts).

## Emitted symbol names (package qualification)

Every emitted top-level declaration carries its package's prefix, so that two
packages can both declare `Point` or `bump` without colliding in the amalgamated
translation unit - the generated code never spells a package name out:

- **`rtl` is emitted bare.** The built-in types and runtime operations live in
  the built-in namespace (`specs/modules.md`), which is also why they map onto
  the hand-written RTL C++ types and `simse_*` natives with no prefix of their
  own.
- **Every other package gets `ns<index>_`**, where the index comes from a global
  dictionary the emitter fills once per compilation: the input packages are
  sorted by name and numbered from 1, so the numbering never depends on
  discovery order and the output stays reproducible. For the compiler's own
  source set the assignment is `codegen` `ns1_`, `common` `ns2_`, `compiler`
  `ns3_`, `lex` `ns4_`, `linear` `ns5_`, `parser` `ns6_`, `sema` `ns7_`,
  `skelparser` `ns8_`.
- The rule applies to declarations *and* to every reference: data classes and
  their `_make_` factories, enums, their `simse_<Name>_fromInt` helpers and their
  members, typealiases, generic templates and their instantiations, plain
  functions, methods lowered to free functions, and a function used as a value
  (a callable argument).
- **`main` keeps its name** - it is the C++ entry point, not a package member -
  and `native` symbols are hand-written C++ (`simse_native_readFile`), so they
  are never prefixed. Fields, locals, parameters, labels and template parameters
  are emitted as written.
- A programmatically built module with no package declaration (the prelude sets
  are merged into one such module by the drivers) is treated like `rtl`.

Known limitation, unchanged by this: name *resolution* is still by simple name
across the whole compilation, so if two packages declare the same top-level name
the program resolves to one of them rather than choosing by import. The prefix
keeps such a program well-formed - each name is emitted consistently with the
declaration it resolved to - and making resolution package-aware (imports
selecting between same-named declarations) is a separate language change.

## Simse type -> C++ representation

| Simse type | C++ representation | Notes |
| --- | --- | --- |
| `Int` | `Int` (`Int32`) | default integer |
| `Int8` / `Int16` / `Int32` / `Int64` | `Int8` / `Int16` / `Int32` / `Int64` | `std::intN_t` aliases |
| `Float32` / `Float64` | `Float32` / `Float64` | `float` / `double` |
| `Char` | `Char` (`std::int8_t`) | byte value; streams print it as a character |
| `Bool` | `Bool` (`bool`) | printed as `true`/`false` (see below) |
| `Str` | `Str` (`SmString` by default, `std::string` with `SIMSE_STR_STD_STRING`) | mutable byte string; inline up to `SIMSE_STR_INLINE_CAPACITY - 1` bytes plus NUL (16 by default; 23 with the spec's 24) |
| `Unit` | `void` | only valid as a function return type |
| `List<T>` | `List<T>` (`SmallVector<T, 4>` by default, `std::vector<T>` with `SIMSE_LIST_STD_VECTOR`) | value type, deep copies |
| `Array<T>` | `Array<T>` (shim struct) | shared allocation, fixed length |
| `RawArray<T>` | `RawArray<T>` (`T*`) | unmanaged pointer |
| `&T` | `std::shared_ptr<T>` | counted reference |
| `*T` | `T*` | raw pointer |
| `Opt<T>` | `Opt<T>` (shim over `std::optional<T>`) | |
| `Res<T>` | `Res<T>` (shim: `Value`, `Error`) | failure = non-empty `Error` |
| `Dictionary<K, V>` | `Dictionary<K, V>` (`std::unordered_map`) | |
| `PList<T>` | `PList<T>` (`std::shared_ptr<List<T>>`) | the `&List<T>` spelling |
| `Span<T>` | `Span<T>` (shim struct) | borrowed view: `ptr` + `len`; `slice` returns a new span |
| `SmallVector<N, T>` | `SmallVector<T, N>` (`List<T>` is the `N = 4` instantiation) | inline vector |
| user `data class C` | `struct C` (aggregate) + `_make_C` factory | construction lowers to the factory; no emitted constructors |
| user `enum E` | `enum class E` | explicit values when given |
| callable `(A, B) -> R` | `Func<R(A, B)>` (`std::function`) | `Unit` return -> `void` |

## Divergences from `specs/`

These are the known, accepted differences while the shims are kept. They are
deferred to a later runtime-alignment task; the shim must not be treated as the
normative layout.

1. **`Str` layout.** Spec: inline `SmallVector<24, Char>` with a reserved NUL and
   a 23-byte inline capacity (`specs/containers.md`, `specs/built-in-types.md`).
   Shim: the same shape, but the capacity is a build knob — `Str` is `SmString`
   (`cppsrc/rtl/smstring.hpp`) over `StrSmallVector`
   (`cppsrc/rtl/strsmallvector.hpp`), the char-specialized form of that vector:
   `Int _len`, `Int _cap`, an inline byte buffer unioned with the heap pointer,
   4-byte packed, without the per-element lifetime machinery the generic
   `SmallVector` needs. The inline capacity is defined once, in
   `strsmallvector.hpp` (`kStrInlineCapacity`), and read from there by both the
   buffer and `SmString`; its default is the spec's **24 bytes** (23 characters
   inline). It is overridable with `-DSIMSE_STR_INLINE_CAPACITY=<n>` so the
   size/speed trade-off can be measured without editing sources; T33 in
   `impl_specs/capability-matrix.md` records those measurements (16 bytes saves
   ~18% of the peak working set but sends 16-character strings — `"Name: John
   Smith"`, `"Expr.GenericName"` — to the heap, which the default avoids). The
   capacity
   is **part of the ABI**: every translation unit in a binary has to agree on it,
   or the two sides disagree about where a `Str`'s bytes live — which corrupts
   memory rather than failing to link. `build.js` therefore mirrors the cache
   value into the amalgamation compile (with a warning when a `--define`
   disagrees), exactly as it does for the `List`/`Str` backings above;
   `impl_specs/capability-matrix.md` (T33) records the measurements behind the
   default. The buffer
   counts **characters** in `_len` (zero-based: the empty string is `_len == 0`,
   the same convention the generic `SmallVector` uses for its elements) and keeps
   the terminating NUL one byte past the text, in the allocation that `_cap`
   measures in bytes (`data()[size()]` is always `'\0'`), so reads — `size()`,
   `empty()`, `end()` — need no adjustment for the terminator and the `+1` lives
   only in the write paths, which run once per mutation. The NUL is written as
   part of every growing operation (`push_back`, `resize`, `assign`, `append`),
   so there is no separate terminate pass. `Str.size()` is the character count,
   as the spec requires. The inline
   path is `constexpr`-constructible, so
   `constexpr Str` works while the text fits inline. Native code that has to talk
   to the standard library goes through `simse_toStdString` /
   `simse_fromStdString` so the same code compiles with either backing.
   `SIMSE_STR_STD_STRING` (CMake option of the same name;
   `build.bat --define SIMSE_STR_STD_STRING`) is the bootstrap escape hatch that
   backs `Str` with `std::string` instead; as with `List`, `build.js` mirrors the
   CMake cache so an amalgamation always matches the libraries it links, and the
   two backings produce byte-identical compiler output.
2. **`List<T>` backing.** Spec: `List<T>` *is* `SmallVector<4, T>`
   (`specs/containers.md`). Shim: matches by default — `List<T>` is
   `SmallVector<T, kListInlineCapacity>` (4) — with the documented layout. The
   `SIMSE_LIST_STD_VECTOR` define (CMake option of the same name;
   `build.bat --define SIMSE_LIST_STD_VECTOR`) is a bootstrap escape hatch that
   backs `List<T>` with `std::vector<T>` instead, and is not the target layout;
   `build.js` mirrors the CMake cache so an amalgamation always matches the
   libraries it links.
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
   and elements contiguously (`specs/built-in-types.md`). Shim: one allocation
   holding the **element count first and the elements immediately after it**
   (`Int _len` at offset 0, elements at offset 4 under the packing rule, single
   `::operator new` for count + elements - `ArrayBlock<T>`); the handle is a
   `std::shared_ptr<ArrayBlock<T>>` (16 bytes, using the shared pointer's count
   instead of a materialized `[refcount][typeId]` header), and every empty array
   refers to the one shared zero-length block per element type, so an empty array
   allocates nothing. Assignment shares the block, which matches the observable
   spec behavior; `count()`, indexing, `arrayEmpty<T>()`, `List.toArray()` and
   `Array.toList()` are the surface (`tools/array_layout_probe.cpp` pins the
   offsets).
7. **`Opt<T>` representation.** Spec: the core-types representation. Shim: a
   struct wrapping `std::optional<T>` (an extra layer). Behavior (`hasValue`,
   `value`) matches the documented API.
8. **`Res<T>` failure sentinel.** Not spelled out in `specs/`; the shim defines
   `isOk()` as "`Error` is empty". Generated code does not depend on this beyond
   calling `isOk()`.
9. **`SmallVector` operations.** The shim implements the std::vector-compatible
   surface the compiler uses: construction (default/copy/move/init-list/range/,
   `(count, value)`), assignment, `size`/`capacity`/`empty`/`reserve`,
   `resize`/`assign`, `operator[]`/`at`/`front`/`back`/`data`, raw-pointer
   iterators (so range-for and `std::sort` work), `push_back`/`emplace_back`/
   `pop_back`, `insert`/`erase`/`clear`/`swap`, and `==`/`!=`, with the
   documented `_len`/`_cap`/union layout and explicit element lifetimes
   (`tools/smallvector_stress.cpp` exercises the inline/heap transitions).
   Template parameters stay reversed (`SmallVector<T, N>` vs the Simse spelling
   `SmallVector<N, T>`): the emitter maps `SmallVector<N, T>` to `SmallVector<T, N>`
   (`impl_specs/reification.md`).
10. **Alignment.** Spec: every type is 4-byte packed (`specs/memory-model.md`,
    "Alignment and packing"). Shim: generated aggregates are emitted between
    `SIMSE_PACK_PUSH` / `SIMSE_PACK_POP` (`cppsrc/rtl/types.hpp`), and
    `SmallVector` and `Array` follow the same rule. The hand-written RTL structs
    (`XmlNode`, `Attribute`, `Span`, ...) keep the host alignment because their
    fields already sit on 4-byte boundaries, so packing them would not change a
    single size. The host types the shims are built on (`std::shared_ptr`,
    `std::function`, `std::unordered_map`, and `std::string` under the
    `SIMSE_STR_STD_STRING` escape hatch) are declared 8-aligned and are therefore
    under-aligned by the packed definitions; that is accepted while the shims
    exist. `SIMSE_NO_PACK4` turns the packing off and reverts to host layout.
11. **`Dictionary<K, V>` backing.** Spec: a value dictionary whose hashing,
    buckets and iteration order are deliberately unspecified
    (`specs/dictionary.md`). Shim: `std::unordered_map` by default, and
    `cppsrc/rtl/smdictionary.hpp`'s `SmDictionary<TKey, TValue>` behind
    `SIMSE_DICT_SM` (CMake option of the same name; `build.js` mirrors the cache).
    `SmDictionary` is the .NET shape: one `Entry` per row (`hash`, `next`, key,
    value), chains by row index, a bucket table whose length is a power of two with
    the mask kept in a field (`hash & _mask`), a first table of 16 buckets growing
    4x, and removal by tombstone (`hash = -1`). Rows are append-only - a removed row
    stays a hole - and both iteration and a growth pack the live rows together:
    `compact()` runs from the iterator-producing calls when `_count !=
    _rows.size()` (so iteration is a pointer walk over `_rows`), and `growBuckets()`
    packs in the same pass because it already walks every row to rebuild the chains.
    An insert into an empty bucket skips the chain walk and the key compare
    altogether (no row hashes there, so the key cannot be present). The two backings
    emit byte-identical compiler output, and both pass the differentials, the
    bootstrap fixed point and the stress corpus. It is **opt-in, and measured ~6%
    faster** end to end on the 6,357-line self-transpile (37 interleaved pairs over
    two windows: 62.6/68.2 and 61.5/69.5 ms against the std backing's 67.2/72.7 and
    65.2/73.8 ms), with iteration ~8x, deep copies ~5x and miss lookups ~1.6x
    faster, `fill`/`erase` and the compiler's small-dictionary churn at parity, and
    one remaining deficit: hit lookups on cache-resident tables are ~1.8x slower in
    the micro-benchmark (`tools/smdict_stress.cpp`).
    `impl_specs/capability-matrix.md` (T41) has the full table and the suspects.
    Two semantic differences from `std::unordered_map` are worth recording: the
    backing keeps no reference/iterator stability across an insert (rows live in a
    `SmallVector`), and `keys()`/`values()` order is row order (insertion order,
    holes packed away on demand) rather than bucket order - both are unspecified in
    the spec, and nothing in the tree depends on either.

## Operations the emitter needs

### Reading files line by line, and the clock

`FileStream` (`cppsrc/rtl/filestream.hpp`, prelude `cppsrc/rtl/fs.simse`) is the
RTL's line reader. `openFileStream(path): *FileStream` is a free native (null when
the file cannot be opened); the operations are **methods of the struct** -
`readLine(): Opt<Str>`, `readLineInto(buffer: *Str): Bool`,
`readLineView(): Opt<StrView>`, `fileSize(): Int64`, `close()` - because the
emitter calls a handle's methods as members (`stream.readLine()` on a
`*FileStream` emits `(*stream).readLine()`).

All three readers share one 256 KiB readahead buffer and one code path
(`nextLineSpan`, which `memchr`s for the newline, shifts a partial line to the
front of the buffer to keep it contiguous, and grows the buffer when a single
line does not fit), and all three strip a trailing `\r` and treat a final line
without a newline as a line. They differ in what the caller gets:

- `readLine` goes through `std::getline` into a `std::string` the stream recycles
  and hands back a fresh `Str` (one allocation per line longer than `Str`'s inline
  capacity). It reads the file directly, so a stream must be read with *one* of
  the three - mixing `readLine` with the other two skips bytes.
- `readLineInto` copies the line into the caller's `Str`, whose heap block is
  reused - no allocation after the longest line seen.
- `readLineView` copies nothing: it returns a `StrView` (`cppsrc/rtl/span.hpp`,
  prelude `cppsrc/rtl/Span.simse`) into the readahead buffer, valid until the
  next read on that stream, which is the shape a parse loop wants
  (`find`/`slice`/`at` stay in the buffer; `toString` is the owned copy).

Measured on `benchmarks/onebrc` (10M rows, 127.7 MiB, release, interleaved
min/median, all reports byte-identical, all at a 6.5 MB peak working set):
`readLine` **2040/2048 ms**, `readLineInto` **1156/1161 ms**, `readLineView`
**1087/1088 ms** - the reader choice is worth 1.88x, and the in-place path is 1.43x
*ahead* of the naive C++ `getline`+`stod` baseline's 1550/1570 ms measured in the
same session. (The benchmark's headline - the in-place variant alone against the
same baseline - has run **1.26x-1.40x** across sessions, because the C++ leg varies
more than the Simse one; `benchmarks/onebrc/benchmark.md` has the current set.) The
in-place path still builds two `Str`s per line (the station name, which is the
dictionary's key type, and the temperature, which `tenths` takes as a `Str`);
removing those is the next step, together with in-place dictionary access (item
11's remaining gap).

A type-name subtlety this cost a cycle to learn: the emitter resolves a type name
by consulting the RTL list *before* the program's own declarations, so a declared
type that shares a prelude name was shadowed in every emitted signature (it happened
when the RTL gained `StrView` while the compiler had a `common.StrView` of its own -
a name that no longer exists, since both are `StrView` now). `typeName` now checks
`types` first and lets a declared type from any package other than `rtl` win
(`cppsrc/codegen/Codegen.cpp` and the `cgIsRtlTypeName`/`typeName` mirror in
`Codegen.simse`); T23 and the five differentials stay byte-identical.

`simse_nowMillis` (`cppsrc/rtl/timeops.hpp`) is a monotonic millisecond clock for
logging and for measuring a run; it exists because the benchmark needed to report
its own time the way the C++ baseline does.

The Simse surface, with the C++ symbol each one reaches (`cppsrc/rtl/fs.simse`,
`cppsrc/rtl/rtl.simse`):

| Simse | C++ symbol | Notes |
| --- | --- | --- |
| `openFileStream(path)` | `simse_fileStream_open` | `*FileStream`; null when the file cannot be opened |
| `stream.readLine()` | `FileStream::readLine` (member) | the next line, an empty `Opt` at end of file |
| `stream.readLineInto(*buffer)` | `FileStream::readLineInto` (member) | the next line into a recycled `Str`, `false` at end of file |
| `stream.readLineView()` | `FileStream::readLineView` (member) | the next line as a `StrView` into the readahead buffer; empty `Opt` at end of file, valid until the next read |
| `stream.fileSize()` | `FileStream::fileSize` (member) | the file's size in bytes, for throughput reporting |
| `stream.close()` | `FileStream::close` (member) | releases the handle |
| `nowMillis()` | `simse_nowMillis` | monotonic milliseconds since an arbitrary fixed point |

### Boxing, addresses, and `copy`

No new RTL operations were required for the v1 subset. Specifically:

- Boxing (`&value`) lowers to `std::make_shared<std::remove_cvref_t<decltype(...)>>(value)`,
  which comes from `<memory>` via `cppsrc/rtl/simse.hpp`.
- `*value` (address of a value) lowers to `&name` for a plain name and to
  `simse_addressOf(expr)` otherwise. `simse_addressOf` (`rtl/types.hpp`) binds
  lvalues and temporaries, so a call result can be passed as a pointer for the
  duration of the call without copying; read-only `*XmlNode` / `*List<T>`
  parameters use this form at their call sites. `*ref` lowers to `.get()` on a
  `std::shared_ptr`; `*ptr` lowers to `*ptr`; `copy(x)` lowers to `*(x)` for
  references/pointers and a plain copy otherwise.
- `&List<T>()` construction would use the existing `makeList<T>()`, but the v1
  subset does not emit it (see gaps below).

### `Dictionary<K, V>` and the `List` extras (T20)

The front end (the Simse sema port) needs maps, so `Dictionary<K, V>`
(`std::unordered_map`) gained a native surface in `cppsrc/rtl/dictops.hpp`, and
`List<T>` gained two helpers. All are prelude natives with explicit symbols
(`cppsrc/rtl/rtl.simse`):

| Simse | C++ symbol | Notes |
| --- | --- | --- |
| `dictionaryOf<K, V>()` | `simse_dictionaryOf` | empty `Dictionary<K, V>` |
| `d.get(key)` | `simse_dict_get` | `Opt<V>`; empty when absent |
| `d.has(key)` | `simse_dict_has` | `Bool` |
| `d.insert(key, value)` | `simse_dict_insert` | insert or replace |
| `d.remove(key)` | `simse_dict_remove` | erase; a no-op when absent |
| `d.size()` | `simse_dict_size` | `Int` |
| `d.keys()` | `simse_dict_keys` | `List<K>`, unspecified order |
| `d.values()` | `simse_dict_values` | `List<V>`, unspecified order |
| `d.clear()` | `simse_dict_clear` | remove every entry |
| `items.contains(value)` | `simse_list_contains` | linear `operator==` scan |
| `items.sort(less)` | `simse_list_sort` | in-place `std::sort` with the `(T, T) -> Bool` lambda |

`get`/`has`/`insert`/`remove` take their key (and value) as a non-deduced
`std::type_identity_t` so a literal argument converts to the element type. A
generic *native* call lowers to its symbol with the type arguments, e.g.
`dictionaryOf<Str, Int>()` -> `simse_dictionaryOf<Str, Int>()`; a `(T, T) -> Bool`
comparator lowers to a C++ lambda, so `sort` is a template over the comparator
type. `keys()`/`values()` are ordered by the hash table and are therefore not
deterministic across implementations; sort for determinism.

Identity comparison on handles: `==`/`!=` on `&T` (`std::shared_ptr`) and `*T`
compare the handle/pointer itself (C++ `operator==`), which is what the sema port
uses to compare declaration handles for identity.

### `Span<T>`

`Span<T>` is a borrowed view over a contiguous run of `T`: a `*T` pointer and a
length, the language's iteration and in-place parsing idiom (`for`/range-for is
deferred). It has a single field-order constructor and by-value helpers, and
codegen maps member calls to the C++ members:

| Member | C++ | Semantics |
| --- | --- | --- |
| `size(): Int` | `len` | element count |
| `isEmpty(): Bool` | `len <= 0` | true when nothing remains |
| `at(index): T` | `ptr[index]` | the element at `index` (unchecked; `span[index]` is the same) |
| `slice(start): Span<T>` | `{ptr + start, len - start}` | from `start` to the end (unchecked) |
| `slice(start, count): Span<T>` | `{ptr + start, count}` | `count` elements from `start` (unchecked) |

The `spanOf(items: *List<T>): Span<T>` helper (RTL `simse_spanOf`) covers all of
`items` from index 0, and `spanOfStr(text: *Str): StrView` (RTL
`simse_spanOfStr`) covers a string's bytes. Both **borrow** their source: the
source must outlive the span, and `&items` would box a *copy*. On `StrView`,
codegen also maps `charAt`, `find`/`indexOf`, `startsWith`, `startsWithPtr`,
`substr`, and `toString`. The struct is immutable: nothing mutates the receiver.

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
(`Res<T>.ok(x)`, `Opt<T>.some(x)`), `Span<T>`, and lambdas with by-value
captures.

Still unsupported (each produces `<file>:<line>:<col>: unsupported: ...` rather
than a crash): namespaced native symbols, untyped parameters/fields, compound
assignment operators (`+=` etc.), lambda reference captures, and `for`/range-for
(use `Span<T>` and `while`). `List<T>.append`, `removeAt`, `removeRange`,
`contains`, and `sort` lower to the native extension symbols in
`cppsrc/rtl/{listops,dictops}.hpp` declared in the RTL prelude; the remaining
`List` methods (`insert`, `clear`) are still emitted as
written and are not yet mapped.
