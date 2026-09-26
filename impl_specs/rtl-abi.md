# RTL ABI (bootstrap slice)

Status: decision recorded for the first end-to-end slice (T6).

The runtime representation generated C++ targets, and where it diverges from `specs/`
(so the bootstrap shims do not become the de-facto specification).

> **The RTL's operation layer lives in the language.** Types, layout and anything
> hand-written C++ calls stay C++; an operation whose callers are all Simse and whose body
> the language can express is a prelude `fun` *with a body* (`cppsrc/rtl/rtl.kt`), emitted
> by the compiler. Candidate test: a symbol referenced only by the header that defines it.
> `Span<T>`/`StrView` are the exception - `typealias StrView = Span<Char>`, their operations
> are the `strview` section of `_res.md`, and the *literal interop* of `strview.hpp` stays
> C++ because overload resolution reaches it, not a declaration. What is left of the `cpp`
> generator is the type core (`impl_specs/generators.md`).
>
> **Receiver spelling.** A body-less attributed method writes its receiver as the explicit
> first parameter (`this: Str`); a function *with* a body writes the receiver type before the
> name (`fun Str.isEmpty()`), because only that form is marked a receiver - the explicit
> `this` is a plain parameter named `this`, so a member call does not reach it
> (`specs/functions.md`, "Generic functions"). A receiver is `T* self`, never a copy, and a
> migrated operation spells no `*` on it.
>
> **A prelude body is emitted when a program reaches it** (`reachesPreludeBody`): the name
> has to be called *and* the receiver's type named, because the prelude has one `iter` per
> container and the call must be attributed to one overload. An *unattributable* name emits
> the whole group rather than none of it - so `"".isEmpty()` works without naming `Str`.

## Decision

Generated C++ targets the **`cppsrc/rtl` shims** as-is:

- `Str = SmString` (the spec layout: inline `SmallVector<char, 24>` plus the
  terminating NUL; `cppsrc/rtl/smstring.hpp`)
- `List<T> = SmallVector<T, 4>` (the spec layout, `cppsrc/rtl/containers.hpp`)
- `PList<T> = std::shared_ptr<List<T>>`
- `Array<T>` = the shim struct: one `std::shared_ptr` handle to a count-first block
  (`cppsrc/rtl/containers.hpp`)
- `Dictionary<K, V> = SmDictionary<K, V>` (the RTL's own row/bucket dictionary,
  `cppsrc/rtl/smdictionary.hpp`)
- `Opt<T>` and `Res<T>` = the two arms of one local tagged union (`Variant2<T,
  VoidEnum>` and `Variant2<T, Str>`, `cppsrc/rtl/variant2.hpp`)
- `&T` lowers to `std::shared_ptr<T>`; `*T` lowers to `T*`
- `Span<T>` = the borrowed view shim: a `*T` pointer plus a length
  (`cppsrc/rtl/span.hpp`); `StrView` is its `char` instantiation, the same type
  under a second name

The ref-counted `[refcount][typeId][value]` header is **not** implemented, and nothing in
the runtime allocates one; `typeId` is unused and not stored (no virtual dispatch or
dynamic casts in the language subset). The `SmallVector` small-buffer optimization *is*
implemented and is what both `List<T>` and `Str` are built on. Target: one compiling,
debugger-friendly translation unit, not the final memory layout - every divergence is
listed below and deferred.

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
- The rule applies to declarations *and* to every reference: data classes, enums,
  their `simse_<Name>_fromInt` helpers and their
  members, typealiases, generic templates and their instantiations, plain
  functions, methods lowered to free functions, and a function used as a value
  (a callable argument).
- **`main` keeps its name** - it is the C++ entry point, not a package member -
  and `native` symbols are hand-written C++ (`simse_fileStream_readLine`), so they
  are never prefixed. Fields, locals, parameters, labels and template parameters
  are emitted as written.
- A programmatically built module with no package declaration (the prelude sets
  are merged into one such module by the drivers) is treated like `rtl`.

Known limitation: name *resolution* is still by simple name across the whole compilation,
so if two packages declare the same top-level name the program resolves to one of them
rather than choosing by import. The prefix keeps such a program well-formed - each name is
emitted consistently with the declaration it resolved to - and making resolution
package-aware (imports selecting between same-named declarations) is a separate language
change.

## Simse type -> C++ representation

| Simse type | C++ representation | Notes |
| --- | --- | --- |
| `Int` | `Int` (`Int32`) | default integer |
| `Int8` / `Int16` / `Int32` / `Int64` | `Int8` / `Int16` / `Int32` / `Int64` | `std::intN_t` aliases |
| `Float32` / `Float64` | `Float32` / `Float64` | `float` / `double` |
| `Char` | `Char` (`std::int8_t`) | byte value; streams print it as a character |
| `Bool` | `Bool` (`bool`) | printed as `true`/`false` (see below) |
| `Str` | `Str` (`SmString`) | mutable byte string; inline up to `kStrInlineCapacity - 1` bytes plus NUL (24 by default, `SIMSE_STR_INLINE_CAPACITY` overrides); `size_type` is `int32_t` and `npos` is `-1` |
| `Unit` | `void` | only valid as a function return type |
| `List<T>` | `List<T>` (`SmallVector<T, 4>`) | value type, deep copies; `size_type` is `Int` |
| `Array<T>` | `Array<T>` (shim struct) | shared allocation, fixed length |
| `RawArray<T>` | `RawArray<T>` (`T*`) | unmanaged pointer |
| `&T` | `std::shared_ptr<T>` | counted reference |
| `*T` | `T*` | raw pointer |
| `Opt<T>` | `Opt<T>` (`Variant2<T, VoidEnum>`) | `hasValue()`, `value()`, `some`, `none` |
| `Res<T>` | `Res<T>` (`Variant2<T, Str>`) | `isOk()` reads the tag; `Value`/`Error` |
| `Dictionary<K, V>` | `Dictionary<K, V>` (`SmDictionary`) | the RTL's own rows + power-of-two bucket table; sizes are `Int` |
| `PList<T>` | `PList<T>` (`std::shared_ptr<List<T>>`) | the `&List<T>` spelling |
| `Span<T>` | `Span<T>` (shim struct) | borrowed view: `ptr` + `len`; `slice` returns a new span; `StrView` is `Span<Char>` |
| `SmallVector<N, T>` | `SmallVector<T, N>` (`List<T>` is the `N = 4` instantiation) | inline vector |
| user `data class C` | `struct C` (aggregate) | construction is the aggregate's own brace form at the call site (`ns1_Rec{a, b}`), with the type arguments spelled when the source wrote them (`ns1_Box<Int>{1}`) and C++20 aggregate CTAD when it did not (`ns1_Box{2}`). A temporary argument is elided into the member (no copy); an lvalue costs the one copy value semantics require |
| user `enum class E` | `enum class E` | explicit values when given |
| callable `(A, B) -> R` | `Func<R(A, B)>` (`std::function`) | `Unit` return -> `void` |

## String literals: one pool and two run-length encoded indexes (T52, T75)

Every string literal in the program is emitted **once**, into a pool of bytes at the top
of the file; beside it the emitter writes two parallel indexes - where each entry starts
and how many bytes it is, both stored as *what to subtract from the previous value* and
both run-length encoded - and startup expands the indexes, drops them, and builds one
`StrView` per entry (the `strtable` section of `cppsrc/rtl/_res.md`, which the emitter
emits into every program's `support` and `bodies`). Each site that mentions a literal reads
its entry and asks for the owned `Str`:

```cpp
// The program's string literals: one pool, and two run-length encoded index
// series (offsets as deltas, then lengths), each as what to subtract from the
// previous value; the strtable section has the stream format.
static const Int __sm_stringCount = 541;
static const char __sm_stringPool[] =
    "usage: simse_transpile <input.kt>..." "yield: a `this` parameter cannot be a field; ..." ...;
static const Int16 __sm_stringStarts[] = {541,5,0,-142,40,5,17,1,2,2,4,6,...};
static const Int16 __sm_stringLens[] = {541,4,-142,40,5,17,1,2,2,4,6,...};
static_assert(sizeof(__sm_stringPool) - 1 == 7355, "the string pool and its length index disagree");
static StrView __sm_stringTable[__sm_stringCount];
static struct __SmStringTableInitType {
    __SmStringTableInitType() {
        Int starts[__sm_stringCount];          // expanded, then dropped: two stack
        Int lens[__sm_stringCount];            // arrays, no allocation
        simse_strTableExpand(__sm_stringStarts, starts, __sm_stringCount);
        simse_strTableExpand(__sm_stringLens, lens, __sm_stringCount);
        simse_strTableDecode(__sm_stringPool, starts, lens, __sm_stringTable,
            __sm_stringCount);
    }
} __sm_stringTableInit;

Str ns1_xmlKind(...) {
    ...
    return __sm_stringTable[31];   // the "Expr.IntLit" entry
}
```

**The series encoding.** A stored `x[i]` means `value[i] = value[i-1] - x[i]` (implicit 0 before
the first entry), rebuilt by accumulating in `Int`, so an element is only ever a *difference*.
Literals are ordered longest first, so a length series descends slowly: **85% of the differences
are 0** on the compiler's own table (equal-length literals adjacent), largest magnitude 142
(the longest literal). Each series is run-length encoded - its length, then alternating blocks
of non-repeating values (a count, then the values) and runs (a count, then `times, value` pairs)
to the length. Compiler's own table (`tools/_strtable_runs.mjs`): 541 differences, 119 runs,
longest run 37, stream **256/257 numbers** against 541 - 513 numbers, **1026 B**, against
1082 B without the pass. Much better than `(times, value)` pairs on a literal-heavy series
(all-distinct: `N + 3` numbers against `2N`), slightly worse on a run-heavy one (119 runs: 257
against 239).

**`Int16`, chosen by the emitter.** Every stream number is small (largest 142, longest run 37);
the emitter encodes, takes the largest magnitude, and widens the element to `Int` if any number
does not fit. The whole index is one source line, 2 bytes/number instead of 4.

**Why a pool and views.** An entry was a 32-byte owning `Str` (4 length + 4 capacity + 24
inline); it is a 12-byte `StrView` (`Span<Char>` + the operations, packed by T74), text stored
once, start-up allocating nothing - 72 literals used to heap-allocate. Compiler's own set (541
entries, 7355-byte pool): **~19.7 KB of table before** (526 `Str` objects, 16.4 KB static +
2.9 KB startup) against **~14.5 KB now** (pool 7355 + 1026 B index + 6492 bytes of views, no
allocation); the initializer's two stack arrays are 4.3 KB, gone on return. The count grew
526 -> 541 because the emission text is itself literals; the honest per-revision comparison is
per-entry cost (32 bytes against 12 + the text) and startup allocations (72 against 0).

**The binary shrinks too.** The old table was 526 *dynamic initializers* (one
`SmString(const char*)` each, inlined), so a pool and two loops removed ~19.9 KB of `.text` on
the compiler's release binary against ~12.8 KB of `.rdata`/`.data` (the `Str[526]` array vs
`StrView[541]`, plus literals that existed twice). `tools/_pe_sections.mjs`, published
bootstrap at HEAD vs current, same flags: `.text` 1 969 196 -> 1 949 308, `.rdata` 238 072 ->
235 560, `.data` 20 408 -> 10 072, `.pdata` 49 232 -> 46 800, file
**2 279 424 -> 2 237 952 (-41 KB)**.

**`starts` is `lens` shifted while no two literals share text.** The offset increment of entry
`i` is entry `i-1`'s length, so the two streams expand to the same numbers with a leading 0
(`tools/_strtable_runs.mjs` prints the check). Dropping `starts` and letting the decoder advance
by the length it just rebuilt halves the index; the series returns with substring sharing, which
makes an offset increment independent of the previous length.

**Where the lengths come from.** The emitter computes them (`cgLiteralByteLength`,
`CgStringTable.kt`), one byte per escape and a *run* for `\xHH...`/octal as C++ counts them. The
pool is the literal texts adjacent, so the C++ compiler decodes the bytes; the emitter only has
to agree on escape cost. The pool's own `sizeof` `static_assert` is the cross-check - one escape
disagreement shifts the total and stops the build instead of silently shifting every later
literal - and the two-step bootstrap (T23) pins it from the other side (stage 1 reads its own
541 literals and must regenerate its source byte for byte).

The entries are sorted **longest first, then text, alphabetically** (`val` < `var`, but
`vars` < `val`), so the order is total (T22/T23). A literal the *lowering* invents keeps its own
spelling at the site, and the prelude's literals are not emitted (the prelude is included, not
transpiled).

**The sites use the view; the interop is `strview.hpp`.** A literal site reads its entry as it
stands, so **a comparison or `+` builds no `Str`** - `str == literal`, `literal == literal`,
`literal + text`, `println(literal)` have direct `StrView` overloads (`operator==`/`!=`/`<`/`<=`/
`>`/`>=`, `+` in all three pairings, plus the `std::ostream` writer). Everything else (a slot, a
`return`, a by-value parameter, a `const Str&` argument, a list pack) reaches the converting
constructor (`SmString(const StrView&)`, declared in `smstring.hpp`, defined in `strview.hpp`)
and materializes the same copy as before. The *mixed* overloads matter: without
`operator==(const Str&, StrView)` and its mirror, `str == literal` is ambiguous, because both
`const char* -> Str` and `StrView -> Str` exist.

Measured with `tools/_bench_ab.mjs` (15 interleaved self-transpile runs, previous published
bootstrap vs new, same flags): the `toString()` cut was **~7.5-9% slower**
(**838.3/907.0 -> 918.1/962.4 ms** in one window), the view sites **~0-2%**
(**804.4/827.7 -> 816.6/837.0 ms** and **830.7/865.3 -> 831.9/883.2 ms**), i.e. parity -
expected, since the old table's read sites were construction-free too (`const Str` binds a
comparison directly). The view form buys the memory (14.5 KB against 19.7 KB), the startup
allocations (0 against 72), and the `+` sites that used to *copy* a long entry. Still converted
rather than borrowed: an argument to a native taking `const Str&` (`simse_str_find`,
`startsWith`, `split`, `appendStr`, `eprintln` - about 20 sites), the remaining allowlist entry
from T73.

**A backtick string is pooled as its `"..."` spelling.** The pool holds C++ string
literals verbatim, because the C++ compiler is what decodes them, so a raw, multi-line
string cannot go in as written. The parser spells it as the ordinary double-quoted literal
for the same bytes (`litRawString`, `common/literals.kt`) - a backslash and a quote escaped,
each line ending one `\n` - and everything downstream, this length index included, sees a
plain literal (`specs/built-in-types.md`, `stress/raw-strings`).

**Why the lengths are not `sizeof` expressions.** `(Int) sizeof("<literal>") - 1` would let the
C++ compiler compute the length with no emitter-side decoding, but the length would then be a
*symbol in the generated file* the emitter cannot index on, and the second index (the deltas)
could not be written. Substring sharing (pointing `"Hell"` into `"Hello "`) needs the emitter to
decode escape *values*, not counts; it saves 875 bytes of pool on the compiler's own set against
the 2.1 KB the extra index costs while the indexes are `Int`, so it belongs with the packed
encoding.

**Why not wrap each literal in a `constexpr` helper.** Measured, *slower* (~3%): the RTL's
`const char*` overloads (`operator==(const SmString&, const char*)` and friends) compare a
literal **in place** (`compareBytes(text, length)`, `strlen` folded to a constant) and never
built a temporary; wrapping forces one. The conversion path (`SmString(const char*)` over the
constexpr `assign`) had nothing to fold either.

## Divergences from `specs/`

Known, accepted differences while the shims are kept, deferred to a later
runtime-alignment task; the shim is not the normative layout.

1. **`Str` layout.** Spec: inline `SmallVector<24, Char>`, reserved NUL, 23-byte inline
   capacity (`specs/containers.md`, `specs/built-in-types.md`). Shim: same shape, capacity
   a build knob - `SmString` (`cppsrc/rtl/smstring.hpp`) over `StrSmallVector`
   (`cppsrc/rtl/strsmallvector.hpp`), the char-specialized vector: `Int _len`, `Int _cap`,
   an inline byte buffer unioned with the heap pointer, 4-byte packed, no per-element
   lifetime machinery. Capacity defined once (`kStrInlineCapacity` in `strsmallvector.hpp`);
   default the spec's **24 bytes** (23 chars inline); overridable with
   `-DSIMSE_STR_INLINE_CAPACITY=<n>` (T33: 16 bytes saves ~18% of the peak working set but
   sends 16-character strings - `"Name: John Smith"`, `"Expr.GenericName"` - to the heap,
   which the default avoids). The capacity is **part of the ABI**: every translation unit
   in a binary has to agree, or the two sides disagree about where a `Str`'s bytes live -
   memory corruption, not a link error. `build.js` mirrors the cache value into the
   amalgamation compile (with a warning when a `--define` disagrees), as for
   `SIMSE_NO_PACK4` (item 10). `_len` counts **characters** (zero-based; empty is
   `_len == 0`, the generic `SmallVector`'s convention); the NUL sits one byte past the
   text, in the allocation `_cap` measures in bytes (`data()[size()]` is always `'\0'`), so
   reads (`size()`, `empty()`, `end()`) need no adjustment and the `+1` lives only in the
   write paths. The NUL is written by every growing operation (`push_back`, `resize`,
   `assign`, `append`) - no separate terminate pass. `Str.size()` is the character count.
   The inline path is `constexpr`-constructible. Every size/length/index is `Int` (32-bit
   signed): `SmString::size_type` is `int32_t`, `npos` is `-1`. `std::size_t` appears only
   where a stdlib signature requires it (allocation, `memcpy`/`memmove`/`memchr`,
   `char_traits<char>::length`), always as an explicit widening cast, so nothing narrows a
   `size_t` into an `Int` (this retired the C4267 warnings). `std::string` survives **only
   at the native boundary**: `simse_toStdString`/`simse_fromStdString`,
   `std::getline(std::istream&, Str&)`, `FileStream`'s recycled line buffer
   (`cppsrc/rtl/filestream.hpp`), and the `std::filesystem`/`<fstream>` use in the `fileio`
   section of `cppsrc/rtl/_res.md`.
2. **`List<T>` implementation.** Spec: `List<T>` *is* `SmallVector<4, T>`
   (`specs/containers.md`). Shim: matches - `SmallVector<T, kListInlineCapacity>` (4), the
   documented layout, and its only implementation (no `std::vector` mode).
3. **Index width / packing.** Spec: 32-bit indices and sizes, 4-byte packing
   (`specs/containers.md`). Shim: matches on the width - `SmallVector::size_type` is `Int`,
   `SmString::size_type` is `int32_t`, `npos` is `-1` - and `std::size_t` appears only
   where a stdlib signature requires one, always as an explicit widening cast. Packing is
   item 10.
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
7. **`Opt<T>` / `Res<T>` representation.** Spec: the core-types representation,
   two states and a tag. Shim: one hand-written tagged union serves both
   (`Variant2<A, B>`, `cppsrc/rtl/variant2.hpp`) - `Opt<T>` is `Variant2<T,
   VoidEnum>` and `Res<T>` is `Variant2<T, Str>` - so an empty optional and a
   failed result hold no payload, and the untaken arm is not constructed. The
   alternatives are managed by hand (`setFirst`/`setSecond`/`clear`); `std::variant`
   was rejected because its accessors throw and its valueless state is a third
   state this type cannot enter. Behavior (`hasValue`, `value`) matches the
   documented API.
8. **`Res<T>` failure sentinel.** `isOk()` reads the union's tag, not the message, so
   `err("")` is a failure. Generated code only calls `isOk()`.
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
    `SmallVector`, `Array`, `Span` and `StrView` follow the same rule. The difference
    is a size, not a no-op: a struct holding a pointer is 16 bytes under the host's
    alignment (8-byte pointer, `Int`, padding) and **12** under the rule, which is
    what `sizeof` reports now (T74). The hand-written structs that hold *host* types
    keep the host alignment, because those types are 8-aligned and cannot be packed
    without lying about them: `xml.hpp`'s `XmlNode`/`Attribute` (312/64),
    `FileStream`'s `std::ifstream`/`std::string`, and the shims built on
    `std::shared_ptr`/`std::function`. `SIMSE_NO_PACK4` turns the packing off and
    reverts to host layout.
11. **`Dictionary<K, V>` implementation.** Spec: a value dictionary whose hashing,
    buckets and iteration order are deliberately unspecified
    (`specs/dictionary.md`). Shim: `Dictionary<K, V>` is
    `cppsrc/rtl/smdictionary.hpp`'s `SmDictionary<TKey, TValue>`, and that is its
    only implementation - `std::unordered_map` is not used anywhere in the tree.
    `SmDictionary` is the .NET shape: one `Entry` per row (`hash`, `next`, key,
    value), chains by row index, a bucket table whose length is a power of two with
    the mask kept in a field (`hash & _mask`), a first table of 4 buckets growing
    4x (4 fits the `_buckets` list's inline buffer, so a small dictionary allocates
    nothing for its table), and removal by tombstone (`hash = -1`). Rows are
    append-only - a removed row stays a hole - and both iteration and a growth pack
    the live rows together:
    `compact()` runs from the iterator-producing calls when `_count !=
    _rows.size()` (so iteration is a pointer walk over `_rows`), and `growBuckets()`
    packs in the same pass because it already walks every row to rebuild the chains.
    An insert into an empty bucket skips the chain walk and the key compare
    altogether (no row hashes there, so the key cannot be present). Measured against
    the `std::unordered_map` it replaced: iteration ~8x, deep copies ~5x and miss
    lookups ~1.6x, and ~6% faster end to end on the 6,357-line self-transpile (37
    interleaved pairs over two windows: 62.6/68.2 and 61.5/69.5 ms against 67.2/72.7
    and 65.2/73.8 ms), with `fill`/`erase` and the compiler's small-dictionary churn
    at parity. The one deficit is hit lookups on cache-resident tables, ~1.8x slower,
    because the bucket is a row index (a second dependent load); the
    `impl_specs/capability-matrix.md` (T41) entry has the full table and the suspects.
    Two semantic properties worth recording: it keeps no reference/iterator stability
    across an insert (rows live in a `SmallVector`), and `keys()`/`values()` order is
    row order (insertion order, holes packed away on demand) rather than bucket order -
    both are unspecified in the spec, and nothing in the tree depends on either.

## Operations the emitter needs

### Reading files line by line, and the clock

`FileStream` (`cppsrc/rtl/filestream.hpp`, prelude `cppsrc/rtl/fs.kt` for the *type*; the
operations are the `io` module's) is the
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
  prelude `cppsrc/rtl/Span.kt`) into the readahead buffer, valid until the
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

The emitter resolves a type name by consulting the program's own declarations *before*
the RTL list, so a declared type from any package other than `rtl` wins over a prelude
name (`typeName`, `cgIsRtlTypeName` in `Codegen.kt`); T23 and the five differentials stay
byte-identical.

`simse_nowMillis` (the `timeops` section of `cppsrc/rtl/_res.md`) is a monotonic
millisecond clock for logging and for measuring a run.

The Simse surface, with the C++ symbol each one reaches (`cppsrc/modules/io/api.kt`,
`cppsrc/rtl/rtl.kt`):

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

No new RTL operations were required:

- Boxing (`&value`) lowers to `std::make_shared<std::remove_cvref_t<decltype(...)>>(value)`,
  which comes from `<memory>` via `cppsrc/rtl/simse.hpp`.
- `*value` (address of a value) lowers to `&name` for a plain name and to
  `simse_addressOf(expr)` otherwise. `simse_addressOf` (`rtl/types.hpp`) binds
  lvalues and temporaries, so a call result can be passed as a pointer for the
  duration of the call without copying; read-only `*XmlNode` / `*List<T>`
  parameters use this form at their call sites. A bare `this` is the one operand
  that is already the address: a value receiver *is* the `T* self` the call site
  passed, so `*this` is `self` (and a call on `this` passes it - see 13). `*ref`
  lowers to `.get()` on a `std::shared_ptr`; `*ptr` lowers to `*ptr`; `copy(x)`
  lowers to `*(x)` for references/pointers and a plain copy otherwise.
- `&List<T>()` construction would use the existing `makeList<T>()`, but the v1
  subset does not emit it (see gaps below).

### `Dictionary<K, V>` and the `List` extras (T20)

The front end needs maps, so `Dictionary<K, V>` (`SmDictionary`) gained a native
surface in the `dictops` section of `cppsrc/rtl/_res.md`, and `List<T>` gained two
helpers. All are prelude natives with explicit symbols (`cppsrc/rtl/rtl.kt`):

| Simse | C++ symbol | Notes |
| --- | --- | --- |
| `dictionaryOf<K, V>()` | `simse_dictionaryOf` | empty `Dictionary<K, V>` |
| `d.getPtr(key)` | `simse_dict_getPtr` | `*V`: the value's place, or `null` when absent |
| `d.get(key)` | `simse_dict_get` | `Opt<V>`; empty when absent - built on `getPtr` |
| `d.has(key)` | `simse_dict_has` | `Bool` - `getPtr` with the pointer tested |
| `d.insert(key, value)` | `simse_dict_insert` | insert or replace |
| `d.remove(key)` | `simse_dict_remove` | erase; a no-op when absent |
| `d.size()` | `simse_dict_size` | `Int` |
| `d.keys()` | `simse_dict_keys` | `List<K>`, unspecified order |
| `d.values()` | `simse_dict_values` | `List<V>`, unspecified order |
| `d.clear()` | `simse_dict_clear` | remove every entry |
| `items.contains(value)` | `simse_list_contains` | linear `operator==` scan |
| `items.sort(less)` | `simse_list_sort` | in-place `std::sort` with the `(T, T) -> Bool` lambda |

`getPtr`/`get`/`has`/`insert`/`remove` take their key (and value) as a non-deduced
`std::type_identity_t` so a literal argument converts to the element type. The pointer
`getPtr` answers is the dictionary's own row - one `findRow` walk, no copy - and it is
valid until the next `insert`/`remove`/`clear` on that dictionary (`valuePtr` in
`smdictionary.hpp`, which is `const` because the row list is already `mutable` for the
packing a query memoizes). Neither `get` nor `has` packs holes any more: they answer from
the row lookup, and only the iterator-producing calls (`find`/`begin`/`end`) pack. A
generic *native* call lowers to its symbol with the type arguments, e.g.
`dictionaryOf<Str, Int>()` -> `simse_dictionaryOf<Str, Int>()`; a `(T, T) -> Bool`
comparator lowers to a C++ lambda, so `sort` is a template over the comparator
type. `keys()`/`values()` follow the dictionary's own iteration order, which the
spec leaves unspecified; sort for determinism.

Identity comparison on handles: `==`/`!=` on `&T` (`std::shared_ptr`) and `*T`
compare the handle/pointer itself (C++ `operator==`), used to compare declaration
handles for identity.

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

Lowered: generic declarations, uses, and calls (via C++ templates; see
`impl_specs/reification.md`), generic `typealias`, native declarations (see
`impl_specs/native-interop.md`), `when`, `null`, generic-qualified static calls
(`Res<T>.ok(x)`, `Opt<T>.some(x)`), `Span<T>`, and lambdas with by-value captures.

Still unsupported (each produces `<file>:<line>:<col>: unsupported: ...` rather
than a crash): namespaced native symbols, untyped parameters/fields, compound
assignment operators (`+=` etc.), lambda reference captures, and `for`/range-for
(use `Span<T>` and `while`). `List<T>.append`, `removeAt`, `removeRange`,
`contains`, and `sort` lower to the native extension symbols of the
`listops`/`dictops` sections of `cppsrc/rtl/_res.md`, declared in the RTL prelude;
the remaining `List` methods (`insert`, `clear`) are still emitted as written and
are not yet mapped.

12. **`Str::size()` is `Int`.** Every language `size` accessor is `Int` (the
    `Dictionary`/`Span`/`StrView` natives all return `Int`): `SmString::size_type` is
    `int32_t`, so `size()`/`length()` are `Int` and `npos` is `-1`. `std::size_t` appears
    only where a stdlib signature requires one, always as an explicit widening cast, so
    nothing narrows a `size_t` into an `Int` (retiring the C4267 warnings on the
    compiler's own build).

13. **A value receiver is a raw pointer (`T* self`).** A method whose receiver is a
    *value* (`fun advance(...)` inside a data class, `fun f(this: Point, ...)`,
    `fun Str.firstByte()`) is emitted as `T* self`, not `T& self`: the receiver is
    then the language's own borrow form (`specs/memory-model.md`'s `*T`), member
    access is `self->field`, a bare `this` reads as the object (`(*self)`), and a call
    site passes the receiver's **address** (`ns_f(simse_addressOf(x))`, which covers a
    place and a temporary alike - the latter is valid for the call, per
    `simse_addressOf`'s contract). A receiver that is the bare `this` is the one case
    where no address has to be taken - the emitted receiver *is* that address - so the
    call passes the pointer itself (`ns_f(self)`, C++'s `this` inside a closure class)
    and a borrow of the receiver (`*this`) is the same pointer. A receiver declared as
    a handle keeps it, which keeps refcounting available to the body: `this: &T` stays
    `std::shared_ptr<T> self` (so `self` can be stored in a list and keeps its
    refcount), and `this: *T` stays `T* self` (where `this` *is* the pointer, so
    `*this` is the pointee). *Native* extensions are the one exception: their host
    signature decides, so the emitter passes the receiver expression itself
    (`nativeReceiverArg`) and the RTL's `T&`-taking helpers are unchanged.
