# The prelude surface: what the compiler knows, and what may stay special

Status: living inventory — written after the 2026-10 migration rounds moved `split`,
`replace`, the array conversions, the `toString` family's logic, and the concat
primitives out of the RTL's C++ and into Simse bodies over the machine intrinsics.

The rule the rounds established, and this file exists to keep:

> **A fact belongs in the compiler only if it is a fact about the machine or the
> program's shape. A fact about *meaning* belongs in the prelude, where it can be
> read, changed and optimized like any other Simse code.**

"Compiler" here means the emitter, sema and the whole-program passes; "prelude" means
the Simse of `src/rtl/*.kt` plus the thin `_res.md` sections; "machine intrinsics"
means `src/rtl/intrinsics.{kt,hpp}` - block copy, fill, compare, byte find, a byte
pointer, `setBytes`, `bitWidth64` - each one wrapping one library instruction the
language cannot spell, the pattern for any future blocker ("if you miss a cast,
define it as an intrinsic and move the logic out").

## The inventory, classified

### Machine-shaped — stays in the compiler

- **Scalar operator typing and emission** (`a + b` on built-ins): the operators *are*
  the target machine's instructions. Declarations already shadow the built-in rule
  (`operatorBinaryReturn` runs first), so a type wanting its own `plus` is prelude
  business; the built-in fallback - now the *wider-operand* rule - is a language rule,
  documented in `specs/built-in-types.md`, not an emitter exception.
- **The `for` rewrite** (containers to spans, machines to `advance`): the loop's
  lowering; the machines themselves (`iterValues`, `iter`, `toValues`, `toPtrs`) are
  prelude Simse.
- **The `when`-over-strings desugar**: one call to `spanOfStr`, chosen by C++
  overload resolution; the comparison is the prelude's `compareTo`.
- **`main(args)`** building the argument `List<Str>`: program shape.
- **The string table**: pooled literals, the RLE streams, and the static-init decoder.
  Runs before `main`; nothing else may.

### Runtime-shaped — already in the prelude

The string library's whole surface (`find`/`findFrom`, `lastIndexOf`, `split`,
`replace`, `trim`, `substr`, `startsWith`/`endsWith`, case folding, the `Char`
predicates, `toInt` with `from_chars` semantics, `intToStr`), the array conversions,
`fmtStr`/`fmtStrWith`, the `Opt`/`Res` bodies, the concat trio (`strCountDigits` over
`bitWidth64`, `strAddInt`, `strBoolView`), and the resource format's hex/quote
helpers. The borrow prover's `mutates` mark (`specs/attributes.md`) is what makes
result-building bodies provable; `bpCppCalled` scans the *resource texts* for
C++-faced names - and only those (headers precede every declaration, so a name in a
header is a C++ member, never a prelude function).

### Emitter-written text — the known warts, and their fixes

- **`print`/`println`** (`CgCall`'s special case, the `print` section's conditional
  placement in `resGenAlways`): no declaration exists because the checker is
  permissive about plain calls, so an anchor would be a spelling the language does
  not have. *Fix when*: the checker grows strict call checking, or a `println<T>`
  declaration lands with a read-through rule for handles.
- **The concat expansion** (`IlConcat`'s cursor/memcpy loop): instruction selection
  for the `Concat` IL - legitimately the backend's. Its *reach* is decided at
  body-extraction time, after the reached-name rule runs; `ilConcatReachScan` closes
  that gap with a conservative AST pre-scan (any `+`, any `fmtStr`/`fmtStrWith`) that
  marks the trio reached - and pools their literals - before `emitFunctions` decides,
  so a program whose bodies hold neither carries none of the three. Full pre-extraction
  (units built before the prototype pass) was assessed and rejected: it doubles the
  lowering pipeline for the many bodies that concatenate, or trades that for a unit
  cache threaded through every emission path. The scan is the same guarantee at a walk.
- **`print`/`println`**'s conditional placement in `resGenAlways` stands as written
  below - its inputs (the AST's own `println` calls) are early-visible, so it needs
  nothing from the fusion timeline.

### Platform-shaped — stays in `_res.md` C++

`io` (stdio/filesystem), `http` (WinINet), `process` (popen/wait), `streams`
(`std::ifstream` layout), `minui` (SDL/Skia), the task pool (threads), the
`SmDictionary` bridge, `charAt`/`toFloat` and the two alias-spelling `toString`s
(emitted-C++ name collisions: `Int8`≡`Char`, `Int32`≡`Int`). gzip has no C++ at
all. Each has its reason written where it lives.

## What this buys

The point is not line count. It is that **optimization work happens in Simse**:
the digit writer's pair-table-vs-per-digit trade, the `memFindByte` search shape,
`toInt`'s overflow rules - all were changed this cycle by editing ordinary prelude
functions, corpus-green in minutes, no emitter knowledge involved. Every fact moved
out of the compiler is a fact a future pass (inlining, specialization, a better
concat) can see as a call graph rather than as text it must not break.
