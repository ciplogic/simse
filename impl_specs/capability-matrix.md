# Capability matrix: porting components to Simse

Status: living document. Records, per compiler component, which language and RTL
features that component needs in order to be written in Simse and transpiled,
plus whether the bootstrap compiler currently supports each feature. It is the
working checklist for the incremental port described in
`impl_specs/plan-to-selfhost.md`.

Status values:

- **supported** - the compiler/RTL handles it today; a self-host attempt may rely on it.
- **partial** - handled for some shapes, or only after a codegen fix; see notes.
- **missing** - not handled; a self-host attempt will fail until it is added.

The matrix is derived from the real sources (`cppsrc/lex/Scanner.kt`,
`cppsrc/common/common.kt`, `cppsrc/skelparser/SkeletonParser.kt`) and the
current `cppsrc/{parser,sema,codegen,rtl}` implementations.

## Intended port order

1. **common** - the smallest, no self-referential dependencies; needed by every
   other mirror.
2. **scanner** (`Scanner.kt`) - the first real differential test; needs
   `common` via `import cppsrc.common`.
3. **skeleton parser** - uses the scanner API and `List<SkeletonNode>`.
4. **AST/parser/sema/codegen** - the largest, still C++-only; port last.

## `common`

| Feature | Needed by | Status | Notes |
| --- | --- | --- | --- |
| `data class` with fields and methods | `common` | supported | lowered to a C++ struct plus free functions. |
| `var`/`val` locals, `while`, `if`, `return` | `common` | supported | |
| `Str` value type, indexing, `size()`, literals | `common` | supported | `Str` is `std::string`. |
| `&T` fields and `&value` construction | `common` | supported | `&T` -> `std::shared_ptr<T>`. |
| `&T` member access / indexing auto-deref | `common` | supported | emits `(*handle)[i]`, `handle->m()`. |
| `native("Symbol") fun` without body | `common.readFile` | supported | `simse_native_readFile`. |
| `import a.b.c` merging a directory | any importer | supported | resolved relative to the repo root. |
| Generic data class | not used here | supported | C++ templates. |

## Scanner (`Scanner.kt`)

| Feature | Where used | Status | Notes |
| --- | --- | --- | --- |
| `enum class` + `Enum.Member` access | `TokenKind.Eof` | supported | emits `TokenKind::Eof`. |
| `typealias` to function type | `MatchLenFunc`, `CharPredicate` | supported | `Func<R(A...)>`. |
| Function values / calling a parameter | `rule.match(view)` | supported | `Func` is `std::function`. |
| Generic data class instantiation | `List<TokenMatcher>` | supported | |
| `&T` return + `&T` parameter | `getTokenRules`, `addRule` | supported | `&List<T>()` -> `makeList<T>()`; calls deref as needed. |
| `List<T>` methods (`append`, `size`, indexing) | `tokens`, `rules`, `words` | supported | `append`/`removeAt`/`removeRange` lower to `simse_list_*`. |
| `Str.append(Char)` | `escapedSnippet` | supported | lowers to `simse_str_append`. |
| `Int.toString()` | `unexpectedCharacterMessage` | supported | lowers to `std::to_string`. |
| Static generic calls `Res<T>.ok/.err` | `readFileAsTokens` | supported | emits `Res<T>::ok(...)`. |
| `Res<T>` accessors as properties (`value`, `error`) | `readFileAsTokens` | supported | emitted as `Value`/`Error`. |
| `while`, `break`, `continue` | matchers | supported | |
| `Bool` logic and comparisons | matchers | supported | |
| Method calls through `*T` | `readFileAsTokens(scanner: *Scanner)` | supported | `*scanner` auto-deref. |
| Import cycles | `import cppsrc.common` | supported | reported, not followed. |

## Skeleton parser (`SkeletonParser.kt`)

| Feature | Where used | Status | Notes |
| --- | --- | --- | --- |
| `import cppsrc.lex` (scanner API) | whole file | supported | transitive imports merge. |
| `enum class` + member access | `SkeletonType.Terminal` | supported | |
| Generic data class instantiation | `List<SkeletonNode>` | supported | |
| `&List<T>` fields and methods | `SkeletonNode` | supported | |
| `var` parameter mutation | folding loop | supported | parameters are mutable. |
| Indexing assignment | deleting a folded range | supported | |
| Full self-transpile (ported) | whole file | supported | emits, compiles, and diffs byte-identically against the C++ `parseSkeleton` over every fixture (`skel_diff`). |

## AST / parser / sema / codegen mirrors

The parser (T19), sema (T21), and code generator (T22) are ported, and the
driver/CLI plumbing is ported too (T23): the compiler now self-hosts at stage 1.
The XmlNode accessors are shared through `cppsrc/common/xmlutil.kt`, and the
filesystem/IO surface is the prelude natives in `cppsrc/rtl/fs.kt`.

## Ported components

Progress of the incremental port (see `impl_specs/roadmap.md`):

| Component | Mirror | Emits | Compiles | Diff-identical | Harness |
| --- | --- | --- | --- | --- | --- |
| common / xmlutil | `cppsrc/common/*.kt` | yes (merged transitively) | yes | exercised through every port | part of each diff |
| scanner | `cppsrc/lex/Scanner.kt` | yes | yes | yes (5532-line dump) | `scanner_diff` |
| skeleton parser | `cppsrc/skelparser/SkeletonParser.kt` | yes | yes | yes (4992-line tree dump) | `skel_diff` |
| parser | `cppsrc/parser/Parser.kt` | yes | yes | yes (2136-line XmlNode dump) | `parser_diff` |
| sema | `cppsrc/sema/Sema.kt` | yes | yes | yes (40-line diagnostic dump) | `sema_diff` |
| codegen | `cppsrc/codegen/Codegen.kt` | yes | yes | yes (804-line emission dump) | `codegen_diff` |
| driver / CLI | `cppsrc/compiler/Driver.kt` | yes | yes | two-step fixed point (`simse_out1.cpp` == `simse_out.cpp`, the published copy of which is `cppsrc/simse_bootstrap.cpp`) | `stage1_check` |

## Feature status changes (last port)

1. **Pointer-index auto-dereference.** `tokens[i]` where `tokens: *List<Token>` now
   emits `(*tokens)[i]`; indexing a raw array of scalars stays `p[i]`.
2. **Constructor arity checking** in sema: a call to a known `data class` whose
   argument count differs from the field count is now diagnosed
   (e.g. `data class 'Widget' expects 2 field(s) but got 1`).
3. **`SkeletonNode` default member initializers** in C++ (`_type = None`,
   `_token = {}`), matching the Simse mirror's explicit zero token so the tree
   dump is identical.

## Feature status changes (T14/T15)

The AST/parser port is unblocked by these generic additions:

1. **`switch`/`case`/`default`** - reserved words, parsed into a Switch AST node,
   type-checked (constant labels; `break` allowed in a switch), lowered to C++
   `switch`.
2. **`null` literal** - lowered by expected type: `nullptr` for `*T`/`&T` and
   `Opt<T>()` for `Opt<T>`; `x == null`/`!= null` test `hasValue()` for `Opt`.
3. **`Str` library** - `find`, `substr`, `startsWith`, `endsWith`, `replace`,
   `toInt`, `toFloat` as prelude natives (`cppsrc/rtl/strops.hpp`).
4. **`Char` predicates** - `isDigit`, `isAlpha`, `isAlphaOrDigit`, `isSpace`.
5. **Numeric `toString`** for every scalar plus `Bool`; `min`/`max`.
6. **Enum conversions** - `Enum.toInt()` and checked `Enum.fromInt(Int)`.
7. **PList as a list handle** - `PList<T>` (`&List<T>`) is treated as a handle
   for member access/index/call lowering, so `XmlNode.Children.append(...)` works.
8. **XmlNode carrier** - `Attribute`/`XmlNode` mapped onto the RTL types; the AST
   converts to `XmlNode` (`ast::toXmlNode`) with a deterministic dump and
   `.astxml` goldens.

A latent use-after-free in codegen was also fixed: `pointee(inferType(x))`
returned a pointer into a temporary `TypePtr`; call sites now keep the
`TypePtr` alive.

## Feature status changes (T16/T17/T18)

1. **`Cursor<T>`** - an immutable, `Span`-like list view; the iteration idiom now
   that `for`/range-for stays deferred. `next`/`slice` return new cursors.
2. **`Str` library completed** - `charAt`, `trim`, `split`, `toUpper`, `toLower`,
   `isEmpty`, and `indexOf`/`lastIndexOf` on top of the earlier surface.
3. **Lambdas** - lowered to C++ lambdas with by-value captures (`[=]`),
   assignable to `Func<Ret(Params)>`; parameter types from annotations or the
   expected callable type; return type from the expected type or the body.
4. **Prelude data-class methods** now lower to C++ member calls (not free
   functions), which is what makes `Cursor.hasValue()` / `next()` work: the
   bodies live in `cppsrc/rtl/cursor.hpp`.

## AST / parser / sema / codegen (target features)

| Feature | Needed by | Status | Notes |
| --- | --- | --- | --- |
| `switch` statements | parser dispatch by kind | supported | Switch AST + sema + C++ lowering. |
| `null` for `&T`/`*T`/`Opt` | nullable AST children | supported | context-directed lowering. |
| `Str` library | token text handling | supported | `find`/`indexOf`/`lastIndexOf`/`substr`/`charAt`/`startsWith`/`endsWith`/`replace`/`trim`/`split`/`toUpper`/`toLower`/`isEmpty`/`toInt`/`toFloat`. |
| `Char` predicates | lexer helpers | supported | prelude natives. |
| numeric `toString` | diagnostics | supported | all scalars + Bool. |
| `min`/`max` | range handling | supported | prelude natives. |
| enum `toInt`/`fromInt` | kind dispatch | supported | per-enum helper emitted. |
| XmlNode AST carrier | AST in Simse | supported (C++ converter + Simse carrier proof) | schema in `impl_specs/ast-xmlnode.md`. |
| Dictionary operations | symbol tables, scopes | supported | `dictionaryOf`/`get`/`has`/`insert`/`remove`/`size`/`keys`/`values`/`clear` (`cppsrc/rtl/dictops.hpp`, T20). |
| List `contains`/`sort` | dedup, deterministic order | supported | `simse_list_contains`; `sort` takes a `(T, T) -> Bool` lambda. |
| XmlNode accessors | sema consumption | supported | emitted helper functions in `Sema.kt` (attribute lookup, children by role, positions). |
| `Span<T>` | iteration instead of range-for | supported | `while (!span.isEmpty()) { ... span = span.slice(1) }`. |
| lambdas/closures | visitors | supported | by-value captures; reference captures deferred. |
| `for` | loop rewriting | `for` over a machine only | two forms, desugared to `while` in the parser; a container is walked with an index or a `Span<T>` (`specs/functions.md`, `impl_specs/for.md`). |
| range-for over a container | loop rewriting | missing | deferred; `Span<T>` is the replacement idiom. |
| string interpolation | diagnostics | missing | deferred. |
| `when`/pattern matching | dispatch | missing | deferred; use `switch`. |

> **Note.** The C++ compiler's own loops have **not** been refactored to
> `Span<T>`; that happens per component during the parser port. `Span` is the
> language-level replacement for range-for, not a change to the C++ sources.

## Feature gaps seen by the compiler team

These are the language/RTL features that were missing or wrong and had to be
added or fixed across the self-host attempts (each fix is generic, not
component-specific):

1. **Import resolution.** `import a.b.c` now merges every `*.kt` directly
   under `a/b/c`, with cycle detection.
2. **Enum-qualified access in expressions** now lowers generally to `Enum::Member`.
3. **Generic-qualified static calls** (`Res<T>.ok(x)`) lower to `Type<T>::method(x)`;
   the RTL `Res` gained static `ok`/`err`.
4. **`&T`/`*T` auto-dereference** for member access, indexing, and calls.
5. **Receiver-typed method resolution** in codegen so a name like `append` or
   `toString` picks the correct lowering for the receiver's type.
6. **`Str.append(Char)`** and **`Int.toString()`** RTL/builtin lowerings.
7. **`&List<T>()`** construction lowers to `makeList<T>()`.
8. **Pointer-index auto-dereference**: indexing through a `*T` whose pointee is a
   container (`*List<T>`, `*Str`, ...) emits `(*p)[i]` (found porting the
   skeleton parser's `parseSkeleton(tokens: *List<Token>)`).
9. **Constructor arity checking** in sema (found a real mirror bug:
   `Token("", TokenKind.None)` had 2 arguments for a 3-field `Token`).

## Update log

- Initial matrix created while starting the scanner self-transpile (T11). The
  scanner row is the live status; update it as differential results come in.
- **Scanner self-transpile succeeded.** `simse_transpile cppsrc/lex/Scanner.kt`
  emits `Scanner.kt.cpp` (about 500 lines); it compiles against the RTL and,
  driven by `tests/scanner_simse_main.cpp`, produces a token dump **byte-identical**
  to the hand-written scanner over every `tests/fixtures/*.kt`. The diff is run
  by the default build (`scanner_diff`). The fixes that made this possible are the
  seven generic items above (import resolution, enum access, static generic calls,
  `&T`/`*T` auto-deref, receiver-typed method resolution, `Str.append`/
  `Int.toString`, and `&List<T>()`), plus a default constructor on emitted data
  classes so `Res<T>::err` can hold a payload, and a non-deduced value parameter
  on `simse_list_append` so literal arguments convert.
- **`common`/`StrView` are ported in effect**: their declarations are merged into
  the scanner through `import cppsrc.common` and emitted as part of the same
  translation unit, and the differential run exercises their methods (`at`,
  `startsWith`, `slice`, `toString`).
- **Skeleton-parser self-transpile succeeded.** `simse_transpile
  cppsrc/skelparser/SkeletonParser.kt` emits `SkeletonParser.kt.cpp`; it
  compiles against the RTL (with `Scanner.kt` and `common` merged through
  `import cppsrc.lex`), and the `skel_diff` step shows the generated
  `parseSkeleton` produces a tree dump byte-identical to the C++ implementation
  over every fixture (1508 lines). `cppsrc/main.kt` was repaired and is now a
  runnable e2e program (`main_program`).
- **T14 (language features) and T15 (XmlNode carrier) done.** Added `switch`,
  `null` lowering, the `Str`/`Char`/numeric library, `min`/`max`, and enum
  conversions; the prelude is now a *set* (`cppsrc/rtl/{rtl,xml}.kt`). The AST
  converts to `XmlNode` (`ast::toXmlNode`, `.astxml` goldens), and the
  `emit_xmlnode` e2e program builds the same tree in Simse and prints it
  byte-identically to the C++ dump for `xml_probe.kt`. The scanner and
  skeleton-parser differentials still pass.
- **T16 (Cursor), T17 (Str library), T18 (lambdas) done.** `Cursor<T>` is the
  iteration idiom (`while (c.hasValue()) { ... c = c.next() }`); the prelude set
  gained `cppsrc/rtl/Cursor.kt` and the `simse_cursorOf` helper, and prelude
  data-class methods now lower to C++ member calls. `Str` gained `charAt`,
  `trim`, `split`, `toUpper`, `toLower`, `isEmpty`, `indexOf`, `lastIndexOf`.
  Lambdas lower to by-value-capturing C++ lambdas assignable to
  `Func<Ret(Params)>`. New e2e programs `emit_cursor`, `emit_str`, and
  `emit_lambda` all transpile, compile, run, and stdout-diff clean; `for`/
  range-for remains deferred (`Cursor<T>` is the replacement); the C++ compiler's
  own loops are not yet refactored to `Cursor`.
- **Parser self-transpile succeeded (T19).** `simse_transpile
  cppsrc/parser/Parser.kt` emits `Parser.kt.cpp` (with `Scanner.kt` and
  `common` merged through `import cppsrc.lex`); it compiles against the RTL and
  the `parser_diff` step shows the generated `parseModule` produces an XmlNode
  tree **byte-identical** to `ast::toXmlNode` over all 33 fixtures (1908 lines),
  and the reference matches the 19 `tests/golden/*.astxml.expected` goldens. The
  port needed no new compiler/RTL features: the T14-T18 surface (switch, Cursor,
  Str library, lambdas, XmlNode/PList handles) was sufficient. No mirror had to
  change.
- **Dictionary/List surface added (T20).** `cppsrc/rtl/dictops.hpp` implements
  `simse_dictionaryOf` plus `simse_dict_{get,has,insert,remove,size,keys,values,
  clear}`, `simse_list_contains`, and `simse_list_sort`; the prelude
  (`cppsrc/rtl/rtl.kt`) declares them as `native("simse_dict_*")` extensions.
  Codegen learned to lower a generic *native* call to its symbol with type
  arguments (`dictionaryOf<Str, Int>()` -> `simse_dictionaryOf<Str, Int>()`). A
  new e2e program `emit_dict` exercises all of it and stdout-diffs clean.
- **Sema self-transpile succeeded (T21).** `simse_transpile
  cppsrc/sema/Sema.kt` emits `Sema.kt.cpp` (with the parser, scanner, and
  common merged through `import cppsrc.parser`); it compiles against the RTL and
  the `sema_diff` step shows the generated `analyze` produces diagnostics
  **byte-identical** to `sema::analyze` over every fixture (39 lines, 35
  fixtures), with the reference matching the `.sema.expected` goldens. The port
  added only emitted XmlNode accessor helpers; no new compiler/RTL features were
  needed. A negative fixture (`sema_extension_arity.kt`) pins the prelude
  merge on both sides. No mirror had to change.
- **XmlNode accessors shared (T22).** The accessors moved from `Sema.kt` into
  `cppsrc/common/xmlutil.kt` (emitted, non-prelude); the real-source check now
  merges the RTL prelude for non-prelude mirrors so a helper module that uses
  prelude types resolves without an import. `sema_diff` stayed byte-identical.
- **Codegen self-transpile succeeded (T22).** `simse_transpile
  cppsrc/codegen/Codegen.kt` emits `Codegen.kt.cpp` (4789 lines; the parser,
  scanner, sema, and common/xmlutil merged through `import cppsrc.sema`); it
  compiles against the RTL and the `codegen_diff` step shows the generated
  `emitProgram` produces C++ **byte-identical** to `codegen::emitProgram` over
  every fixture, with the reference matching all 21 `.cpp.expected` goldens. No
  new compiler/RTL features were needed (one source-level note: chained string
  literals do not fold to `Str`, so the preamble is built with a `Str` variable).
- **Stage-1 self-host succeeded (T23).** The compiler source set is rooted at
  `cppsrc/compiler/Driver.kt` (a port of `TranspileMain.cpp` +
  `parseFileWithImports`); the C++ `simse_transpile` emits `compiler_stage1.cpp`
  (187048 bytes, covering the scanner, parser, sema, codegen, common/xmlutil,
  skeleton-parser mirror, and driver), it compiles into `simse_stage1`, and running
  `simse_stage1` over the same source set reproduces `compiler_stage1.cpp`
  **byte-for-byte** (the `stage1_check` fixed point). It also reproduces the C++
  output for a real fixture. Added the filesystem/IO prelude natives (`cppsrc/rtl/fs.kt`,
  `cppsrc/rtl/fs.hpp`, `cppsrc/native/Native.cpp`) and one generic transpiler fix:
  the argv form of `main` (`fun main(args: List<Str>): Int` ->
  `int main(int argc, char** argv)` with the list built from argv), implemented
  identically in both emitters. All five differentials and every e2e program stay
  green.
- **`simse` is now the directory compiler (T24).** The `simse` executable
  (`cppsrc/main.cpp`) compiles a folder into one amalgamated `.cpp` in the current
  folder: `simse [<dir>] [-o <out.cpp>] [--prelude <p>] [--root <d>]`. The
  parse -> sema -> codegen -> write pipeline moved into a shared
  `compiler::transpile` (`cppsrc/Compiler.{h,cpp}`), so `simse_transpile` and
  `simse` agree; `simse_transpile`'s CLI is unchanged (verified by the stage-1
  fixed point). A new additive `parser::collectImportSet` returns the ordered,
  de-duplicated set of participating files, so a scanned directory is compiled
  with each file emitted exactly once. Prelude files found by the scan are
  dropped by canonical path.
- **Modules and packages (T25).** A module is a directory and a package is a
  namespace. Every file declares exactly one `package a.b.c` as its first
  declaration (`package` is reserved); a missing package is a positioned parse
  error, and the `Module` XmlNode always carries the `package` attribute. A
  compilation is the project module root (the `simse` positional directory, or
  `--root`) plus each repeatable `--module-root`, scanned recursively; every
  `.kt` found participates, and `simse_transpile` also includes its explicit
  inputs. `import a.b.c` only makes package `a.b.c` visible unqualified and never
  adds files, so the directory-based `parser::ImportLoader` /
  `parseFileWithImports` / `collectImportSet` are gone. Sema is compilation-wide
  (`analyze(List<Input>)` in C++, `analyze(List<SemaInput>)` in Simse): it groups
  declarations by package, reports duplicate top-level names within a package
  (across files), reports an import of a package no scanned file declares, and
  builds each file's unqualified scope from its own package, its imports, and the
  implicit `rtl` prelude. `simse cppsrc` (and `simse .` from inside `cppsrc/`)
  now resolves imports identically regardless of the working directory. All five
  differentials stay byte-identical (goldens regenerated for the added package
  line and attribute) and the stage-1 fixed point still holds. Fully-qualified
  names are deferred (see `specs/modules.md` and the T25 task notes).
- **Port complete; the compiler self-hosts (fixed point).** Every compiler
  component has a `.kt` mirror, all five differentials and every e2e program
  stay byte-identical, and the stage-1 compiler reproduces its own C++ output
  byte-for-byte. The T1-T24 task files under `impl_specs/tasks/` were pruned once
  their work was verified; this matrix and git history retain the detail, and
  `impl_specs/tasks/25-modules-and-packages.md` carries the outstanding work.
- **Data classes emit as aggregates with `_make_<Name>` factories.** The emitter
  no longer writes per-class constructors (`C() = default;` and
  `C(fields...) : ...`); `struct C` is a plain aggregate and construction is
  routed through a generated `_make_C(fields...)` factory that brace-initializes
  it. Call sites (`C(args...)`, `C<T>(args...)`) lower to the factory in both
  rings; prelude data classes (RTL types with hand-written constructors) are
  unaffected. The aggregate is still default-constructible where C++ needs it
  (e.g. the payload of a failed `Res<T>`), so the earlier default constructor is
  no longer required. Goldens regenerated; all five differentials and the
  stage-1 fixed point hold.
- **Two-step bootstrap naming fixed.** The `simse` directory compiler and
  `simse_transpile` (C++ and the transpiled `Driver.kt`) default to
  `simse_out.cpp` when `-o` is omitted. `stage1_check` now makes the two-step
  flow explicit: `stage1/gen/simse_out.cpp` (C++ transpiler) is kept as
  `stage1/gen/simse_out1.cpp`, which is compiled into `simse_stage1`; running it
  writes `stage1/run/simse_out.cpp`, compared **byte-for-byte** (no `--ignore-eol`)
  with `simse_out1.cpp`. The `emit_lang` fixture check is byte-exact too. The
  compilation order is now canonical (kept files sorted by normalized path; scan
  results use generic `/` separators), so `--root cppsrc` and
  `simse_transpile cppsrc/compiler/Driver.kt` over the same file set emit
  identical C++. Stray `cppsrc.cpp` verification outputs were removed.
- **The sample `cppsrc/main.kt` and its hand-written CLI `cppsrc/main.cpp`
  were deleted.** `main.kt` was the second `main` in a `simse cppsrc`
  amalgamation, so the whole tree could not be compiled; `main.cpp` was the
  directory-compiler CLI, superseded by `simse_transpile`. `cppsrc` now scans to
  exactly one program (the `Driver.kt` compiler), and
  `simse_transpile --root cppsrc` (output `simse_out.cpp`, compiled by
  `build.bat`) is the whole-compiler command. The `simse` target and the
  `main_program` e2e case were removed with them.
- **Raw-pointer (`*T`) read-only parameters replace by-value lists.** `*value`
  now lowers to `&value` (address of an lvalue, no copy) in both rings, so the
  language's raw-pointer form is usable from Simse code. The mirrors' obvious
  read-only list parameters were migrated: `cgJoin`, `joinNames`, `unifyType`,
  `semaUnifyReceiver`, `xmlIsTypeParam`, `appendTypeParams`, `genericTypeExpr`;
  their call sites pass `*local` / `*field`. Parameters whose call sites all pass
  temporaries (`emitStmts`, `typeArgsString`, `templateClause`'s fresh lists) were
  deliberately left alone: those arguments are move-initialized, so there is no
  copy to remove — same for `binaryBindingPower(op: Str)`
  (`this.peek(...).text` is an xvalue, i.e. a move). All five differentials, the
  goldens, and the two-step bootstrap stay green; transpiling the compiler showed
  no measurable wall-clock change (~340 ms either way), since the removed copies
  were small lists.
- **XmlNode is passed by raw pointer through the read-only mirrors.** `*value`
  now lowers to `&name` / `simse_addressOf(expr)` (`rtl/types.hpp`), so a
  temporary can be addressed for the duration of the call (the earlier form
  emitted `&prvalue` and did not compile). Migrated to `*XmlNode`: the `common`
  accessors (`xmlAttr`, `xmlKind`, `xmlIsEmpty`, `xmlChild`/`xmlChildren`,
  `xmlCount`, `xmlHasChild`, `xmlLine`/`xmlColumn`, `xmlTypeParamNames`,
  `xmlDecls`, `xmlLambdaParams`, ...; ~590 call sites) and the codegen emitter's
  node parameters (`expr`, `exprInner`, `lambda`, `inferType`, `type`, `kindOf`,
  `pointee`, `unifyType`, `emitStmt`, `emitDataClass`, `emitEnum`, ...; 37
  functions, 205 call-site arguments). Six value contexts keep value semantics
  with `copy(...)` (`CgFn` construction, the emitter's `selfType`, and the
  `current`/`actualPtr`/`renamed` locals). Measured on `--root cppsrc` (release):
  **~340 ms before, ~234 ms with the accessors migrated, ~229 ms with the
  emitter too** — the emitter parameters add no measurable win at this size, so
  the remaining cost is elsewhere (scanner/parser and the linear attribute
  scans). All five differentials, the goldens, and the two-step bootstrap hold.
- **The remaining by-value node parameters are gone.** Same treatment for the
  mirror's other stages: codegen (`typeArgsString`, `emitStmts`), parser
  (`attach`, `container`, `appendTypeParams`), driver (`driverAppendNamed`,
  `driverAppendDecls`), and sema (18 functions: `resolveType`, `analyzeDecl`,
  `analyzeFunction`, `analyzeStmt`, `analyzeExpr`, `exprType`, `declareValue`,
  `checkCallArity`, ...). The dead `genericType(name, List<XmlNode>)` wrapper was
  deleted in favour of `genericTypeExpr(name, *List<XmlNode>)`. Value contexts
  keep a copy with `copy(...)`: `attach`'s renamed child, `declareValue`'s
  `ValueBinding`, and the three `append*` symbol tables. `tools/xmlnode-migrate.mjs`
  grew `List<XmlNode>` support, CRLF-tolerant brace matching, and no longer strips
  the address-of star in `*param[i]` / `*param.field` (only a bare `*param`).
  The regenerated amalgamation has **zero** by-value `XmlNode` / `List<XmlNode>`
  parameters. Measured on `--root cppsrc` (release, hand-written transpiler):
  ~36 ms warm (~88 ms cold); the earlier ~229 ms figure could not be reproduced
  on this machine — the number to profile is the generated compiler
  (`simse.exe` from `simse_out.cpp`), which is what `simse.sln` builds.
- **Control flow is lowered to labels and gotos before emission (T26).** A new
  post-sema pass (`cppsrc/linear/Linear.{h,cpp}` and the `linear` package in
  `cppsrc/linear/Linear.kt`, `impl_specs/linear-lowering.md`) rewrites every
  function/method/lambda body into `Stmt.Label`, `Stmt.Goto`, `Stmt.IfTrue`,
  `Stmt.IfFalse` and `Stmt.Block`; the emitters no longer have If/While/Switch/
  `Stmt.Block` only when the region declares a variable at its own level (a C++
  jump may not bypass an initialization still in scope), so most bodies splice
  flat; a second pass (`cppsrc/linear/Simplify.{h,cpp}` / `Simplify.kt`,
  `linear::simplifyBody`) then prunes the linear form to a fixed point: jumps to
  the next statement, the `ifTrue (c) goto A; goto B; A:` -> `ifFalse (c) goto B:`
  fold, dead statements after a `goto`/`return`, and labels nothing targets.
  `switch` keeps source-order fallthrough and hoists its subject into an untyped
  `VarDecl` (evaluated once, as a C++ `switch` subject would be); `for` is not a
  language feature yet, and the pass is where a future `for` -> `while`
  desugaring belongs. Labels are `L1..Ln` per body. The two rings agree by
  construction and the differentials prove it; the compiler's own amalgamation
  now contains no structured control flow from Simse sources (the only `while`
  left is the emitter's hand-written argv loop for `main`). Cost, release on
  `--root cppsrc`, interleaved runs: self-hosted compiler ~+5% (the machine
  throttled mid-session, so the ratio is the comparable number), amalgamation
  5368 -> 9445 lines before the simplification pass and 7265 after it, `cl.exe`
  compile time ~+2.5%, and the hand-written transpiler stays at ~36 ms. Nine
  `.cpp` goldens were regenerated; all e2e programs, the five differentials and
  the two-step bootstrap stay green.
- **`List<T>` is backed by the inline `SmallVector` by default (T27).**
  `SmallVector<T, N>` is no longer a layout shell: it implements the
  std::vector-compatible surface the compiler uses, with the documented
  `_len`/`_cap`/union layout, explicit element lifetimes and 4 inline slots
  (`cppsrc/rtl/containers.hpp`). `List<T>` is `SmallVector<T, 4>` unless
  `SIMSE_LIST_STD_VECTOR` is defined (CMake option of the same name;
  `build.bat --define SIMSE_LIST_STD_VECTOR`); `build.js` mirrors the CMake cache
  so an amalgamation always matches the RTL libraries it links against. Both
  configurations build and pass the full suite (60 tests, e2e, five
  differentials, two-step bootstrap); `tools/smallvector_stress.cpp` covers the
  inline/heap transitions under AddressSanitizer. Cost: `sizeof(XmlNode)` grows
  72 -> 312 bytes (four inline `Attribute`s), while the self-hosted compiler is
  ~2% faster on `--root cppsrc` (278 ms vs 283 ms median, interleaved) because
  most nodes avoid a heap allocation for their attributes. `tests/
  skel_simse_main.cpp` had to use `List<Token>` instead of `std::vector<Token>`
  for the parseSkeleton boundary. `specs/containers.md` now states the same
  thing: `List<T>` *is* `SmallVector<4, T>` (the two were kept apart during the
  bootstrap only because `SmallVector` was still an unimplemented shell), so the
  only remaining divergence is the optional `SIMSE_LIST_STD_VECTOR` mode and the
  reversed template parameters.
- **4-byte packing is the language's layout rule (T28).** `specs/memory-model.md`
  gained an "Alignment and packing" section: no type is aligned to more than 4
  bytes, aggregate fields sit on 4-byte boundaries, and the ABI promises no
  8-byte alignment for `Int64`/`Float64`/pointer fields. `cppsrc/rtl/types.hpp`
  defines `SIMSE_PACK_PUSH`/`SIMSE_PACK_POP` (`__pragma(pack(push,4))` under
  MSVC, `_Pragma("pack(push,4)")` elsewhere; `SIMSE_NO_PACK4` reverts to host
  layout). The C++ emitter wraps every generated aggregate in them and
  `SmallVector`/`Array` are defined under the same rule. Measured: an
  `{Int, Int64, Int}` data class drops 24 -> 16 bytes, `Array<Int>` 24 -> 20,
  `alignof(List<T>)` 8 -> 4; `Token` (48), `Attribute` (64) and `XmlNode` (312)
  are unchanged because their fields already fall on 8-byte offsets. The
  compiler's own runtime is neutral (289 ms packed vs 280 ms host alignment,
  interleaved medians) and the transpiled output is byte-identical either way.
  Caveat recorded in `impl_specs/rtl-abi.md`: the shim types are host library
  types declared 8-aligned (`std::string`, `std::shared_ptr`, `std::function`),
  so packed aggregates under-align them — `tools/packed_alignment_probe.cpp`
  exercises that case at 4-mod-8 addresses on the ARM64 target and is clean.
- **`Str` is the inline `SmString` by default (T29).** `cppsrc/rtl/smstring.hpp`
  defines `SmString`: a `SmallVector<char, 24>` of the bytes plus the terminating
  NUL kept at `data()[size()]`, i.e. the `specs/containers.md` layout (inline up
  to 23 bytes, `size()` excluding the NUL), with the std::string-like surface the
  compiler uses (ctors, assignment, `size`/`capacity`/`reserve`/`clear`,
  indexing/`at`/`data`, pointer iterators, `push_back`/`append`/`+=`/`resize`/
  `insert`/`erase`/`replace`/`swap`/`substr`/`find`/`rfind`/`compare`,
  `toStdString`, `==`/`<`/`+`/`<<`/`getline`, `std::hash`). `Str` is `SmString`
  unless `SIMSE_STR_STD_STRING` is defined (CMake option of the same name;
  `build.bat --define SIMSE_STR_STD_STRING`), in which case it is `std::string`
  exactly as before; `build.js` mirrors the CMake cache so an amalgamation
  always matches the RTL libraries it links against. Native code that must talk
  to the standard library goes through `simse_toStdString` /
  `simse_fromStdString`, which are trivial copies in the `std::string`
  configuration, so `cppsrc/common`, `cppsrc/native`, the driver and the test
  harness compile against either backing. Both configurations build and pass
  the full suite (60 tests, e2e, five differentials, two-step bootstrap), and a
  `SmString`-backed `simse_transpile` and a `std::string`-backed one produce
  **byte-identical** output over the compiler source set (checked with `cmp`).
  Cost in the Debug bootstrap (ARM64, `/Od`), measured before the `CgFn`
  borrowing in the next entry: the two-step `stage1_check` ran the same source set
  in ~7-9 s with `SmString` against ~32 s with `std::string`, because the Debug
  STL's checked iterators tax every `std::string` element access while
  `SmallVector` has no such checks; the
  Release `/O2` micro-benchmarks in `tools/str_bench.cpp` show the expected
  reverse on the bulk operations (e.g. long-string construction 90 -> 249 ms,
  dictionary insert/lookup 162 -> 299 ms, `find` 95 -> 146 ms) with `SmString`
  *faster* on copy construction (76 -> 54 ms). Two implementation details
  landed with it: `common::StrView` and its Simse mirror now hold a raw `*Str`
  (the Simse declaration was `&Str`, which made every view copy an atomic
  refcount) and a `simse_str_appendStr` prelude native gives in-place `Str`
  append, since the language has no `+=` and `out = out + text` rebuilds the
  accumulator (`StrView.kt`, `Scanner.kt`, `Codegen.kt`,
  `listops.hpp`, `rtl.kt`).
- **`CgFn`/`CgNativeExt` are borrowed, not copied (T30).** `CgFn` carries two
  `XmlNode`s (`decl`, `receiver`), so `val fn: CgFn = this.functions[i]`
  deep-copied the whole function declaration — and the several read-only scans
  (`functionReturn`, `memberCallReturn`, `findExtensionFn`, `findFunction`, the
  `CgNativeExt` probe in `memberCallReturn`, and the two `hasPlainFunction`
  probes in `call()`) run per expression, so emission paid a copy of every
  function per lookup. Those sites now hold `*CgFn`/`*CgNativeExt` pointers into
  the emitter's own lists (`emitFunctions`, `beginScope` and `emitFunction` take
  `*CgFn`) and `emitFunction` borrows the declaration as `*XmlNode` instead of
  copying it. The C++ ring already iterates `const Fn &` over `ast::Decl*`, so no
  mirror change was needed. Effect on the Debug two-step `stage1_check` (ARM64,
  `/Od`) for the compiler source set: **~6.6-8.7 s -> 2.1 s** with `SmString` and
  **~32 s -> 3.6 s** with `std::string`; goldens, the five differentials and the
  bootstrap fixed point are unchanged, and the two `Str` backings still produce
  byte-identical output.
- **`Str` comparisons against raw C strings stopped building a temporary (T31).**
  `SmString::compare(const char*)` constructed an `SmString` from the C string
  before comparing it, so every `kind == "Expr.Binary"` paid a construction, a
  `strlen` and a `terminate` before the actual byte compare; `<=`/`>=` against a
  `const char*` had no overload at all and silently escalated to the
  `SmString`-vs-`SmString` operator the same way. `compare(const char*)` now runs
  the C string in place (`std::char_traits<char>::length` +
  `char_traits::compare`, i.e. `strlen` + `memcmp`, both `constexpr` in C++17)
  and the missing `<=`/`>=` overloads were added for both operand orders. The
  comparison *semantics* are unchanged — a raw C string is still a valid operand
  of all six operators — only the temporary is gone. Measured (/O2):
  `str == "literal"` 16.0 -> 7.9 ns per comparison; the Debug two-step bootstrap
  drops 2.1 s -> 1.9 s and the hand-written `simse_transpile` 313 -> 299 ms.
- **`Str` got a char-specialized buffer, and it is `constexpr` (T32).**
  `SmString` no longer wraps `SmallVector<char, 24>`:
  `cppsrc/rtl/strsmallvector.hpp` implements the same 32-byte layout (`Int _len`,
  `Int _cap`, a 24-byte inline buffer unioned with the heap pointer, 4-byte
  packed) without the per-element lifetime machinery, because `Char` is trivial —
  growing is a length bump, clearing/shrinking/destruction are length updates,
  and assigning or appending a whole string is one move, with the buffer dropping
  back inline when the text fits. The buffer's **stored length counts the
  terminating NUL** (`size()` is `_len - 1`, the empty string is `_len == 1`), so
  `data()[size()]` is `'\0'` by construction: growing writes the new NUL as part
  of the same operation and `SmString::terminate()` disappeared, along with the
  `clear` + `reserve` + `resize` + copy + terminate sequences. Two methods per
  whole-text write: `assign`/`append` take text laid out as a string — a literal,
  `std::string::data()`, another `Str` — where `[count]` is the terminating NUL,
  and copy the whole `count + 1` bytes in one move; `assignSubstring` /
  `appendSubstring` take a window inside a longer string (`substr`, a
  `(ptr, count)` source that is not terminated at `count`) and copy `count` bytes
  plus a written NUL. The two cases share the capacity/heap logic through a
  compile-time parameter, so each method is straight-line code with no flag to
  fold. **`constexpr
  Str` works** on the default backing too: the inline buffer is activated and
  zeroed only while constant evaluating (`std::is_constant_evaluated()`), so the
  same constructors write through live elements in a constant expression and stay
  raw storage at runtime, and `SmString`'s
  size/data/indexing/compare/`find`/`rfind` surface is `constexpr` (the
  `memcmp`/`strlen`/`memcpy`/`memchr` primitives were swapped for their
  `std::char_traits` spellings, which are constexpr and compile to the same
  intrinsics). `tools/constexpr_probe.cpp` compiles and `static_assert`s on both
  backings (MSVC's C++20 `std::string` is constexpr as well),
  `tools/compare_probe.cpp` checks all six operators with a raw C string on
  either side for inline and heap strings, and `tools/str_diag.cpp` pins the
  empty string, the `(ptr, count)` constructor on a non-terminated buffer, and
  the `data()[size()] == '\0'` invariant. The generic `SmallVector` also stopped
  type-punning: its inline buffer is a real `T _inlineStore[N]` union member
  instead of a `reinterpret_cast`-ed byte array, and its inline path is
  constexpr-annotated. Measured as an interleaved A/B against the committed
  (generic-`SmallVector`) headers in the same time window (`/O2`, 4M iterations,
  `tools/str_bench.cpp`): construct from `const char*` 140 -> 58 ms, construct a
  long (76-byte) string 803 -> 346 ms, copy 132 -> 51 ms, copy assign
  114 -> 51 ms, `Str` from a literal 109 -> 34 ms, `substr` 101 -> 56 ms, concat
  195 -> 88 ms, `str == "literal"` 212 -> 85 ms, dictionary insert/lookup
  669 -> 474 ms, `find` in a long string unchanged (412 -> 376 ms), and
  `Str == Str` on two inline strings ~1 ns slower (25 -> 29 ms) because the
  comparison now goes through `char_traits::compare`; against the intermediate
  "explicit NUL after every write" buffer the NUL-in-`_len` convention is neutral
  on construction/copy/compare and **2.5x faster on `append`** (39 -> 15 ms) and
  `push_back` runs — and `substr` (the `assignSubstring` path) is unchanged.
  `sizeof(Str)` stays 32 and `alignof(Str)` 4
  (`tools/size_probe.cpp`), and `tools/str_stress.cpp` soaks 20k inline/heap
  transitions and the "NUL at `size()`" invariant. End to end in the Debug
  bootstrap (ARM64, `/Od`, interleaved on a quiet machine): `stage1_check`
  1.9 s -> **1.27 s** and the hand-written `simse_transpile` 299 -> **210 ms**;
  the whole two-step `stage1_check` target (recompile the amalgamation, fixture
  differential, fixed-point compare) is **3.9 s**, and the self-hoist run alone —
  the stage-1 compiler transpiling the full compiler source set — is **127 ms**
  in Release (`/O2 /MD`) and 0.73 s in Debug without `/RTC1`, against 41 ms for
  the hand-written ring on the same input; goldens, the five differentials, the
  bootstrap fixed point (both `Str` backings) and the cross-backing
  byte-identity all still hold.
- **`Str`'s inline capacity is one constant, at the spec's 24 (T33).** The 24 was
  written in two places (`StrSmallVector::inlineCapacity` and the unused
  `SmString::inlineCapacity`/`maxInlineLen` pair); it is now defined once as
  `kStrInlineCapacity` in `cppsrc/rtl/strsmallvector.hpp`, read by both the buffer
  and `SmString`, and overridable per build with `-DSIMSE_STR_INLINE_CAPACITY=<n>`
  (CMake cache variable; `build.js` mirrors it into the amalgamation compile and
  warns on a mismatch, because the capacity is part of the ABI — see
  `impl_specs/rtl-abi.md`). The default stays at the spec's 24: the alternative
  was measured and rejected. Release `/O2 /MD`, ARM64, two legs interleaved
  run-for-run in one time window (`tools/_bench_ab.mjs`) and rebuilt per capacity
  (`tools/_hoist_ab.bat` for the self-hosted binary, the `simse_transpile` target
  for the hand-written ring).
  Layout (`tools/_cap_ab.bat <n> size_probe`): `Str` 32 B at 24, 28 at 20, 24 at
  16; `Attribute` 64 / — / 48; `List<Str>` 136 / — / 104; `List<Attribute>` 264 /
  — / 200; **`XmlNode` 312 / — / 240**. Peak working set, measured exactly
  (`tools/memrun.cpp`): the self-hosted compiler 45.6 MB at 24 against 42.4 MB at
  20 and **37.4 MB at 16** (private bytes 40.1 / 36.8 / 31.9 MB); the hand-written
  ring 24.9 MB at 24 against 23.3 MB at 16. Time: no difference anywhere inside
  the run-to-run spread — the self-hosted compiler over the full source set is
  119.8/128.2 ms min/median at 24 against 117.4/126.6 at 16 and 114.2/124.0 at 20,
  the hand-written ring 30.4/35.8 against 31.0/35.5.
  The micro-benchmark shows why 16 was rejected anyway: its inline limit is 15
  characters, and the benchmark's "short" string is `"Expr.GenericName"` — 16
  characters, one too many — so the short-string rows allocate on every
  operation: construct 28.5 -> 124.1 ms, copy 24.1 -> 77.7 ms, literal
  construction 18.0 -> 77.1 ms (4M iterations), `concat short+short` 48.6 ->
  74.1 ms, while rows that fit at both capacities are unchanged (`construct
  long`, `find` in a long string, every `List` row). Capacity 20 (19 characters
  inline, `Str` 28 B) keeps all of those fast at 25.7/17.0 ms and costs 4.2 MB
  over 16, but the compiler's own vocabulary is full of identifiers and messages
  past 19 characters (`simse_list_removeRange`, `binaryBindingPower`,
  `driverGatherFiles`), so the heap would come back for longer text; the tail of
  the distribution is not measurable in a synthetic row, and the default stays
  at the spec's 23-character capacity. The full workload never showed a timing
  difference either way, because the bulk of its strings are either short enough
  for any of these capacities or long enough to spill at all of them. Emitted
  output is byte-identical at every capacity tested (checked with `cmp`).
- **Release tuning: `/Ob3` pays, LTO does not (T34).** `build.bat --release`
  compiles the amalgamation with `/O2 /Ob3 /MD /DNDEBUG`. `/O2` is MSVC's highest
  optimization level (`/O3` is a GCC/Clang spelling; `/Ox` is a subset of `/O2`),
  and `/Ob3` is the only dial beyond it — it lets the inliner go further than
  `/O2`'s `/Ob2`. Measured on the self-hosted compiler transpiling the full
  source set, interleaved run-for-run (`tools/_bench_ab.mjs`) over six windows of
  20-30 pairs: medians 127.2 ms with plain `/O2` against 122.6 ms with `/Ob3`,
  i.e. **~3.6% faster in every window** (mins 117.2 against 113.7; the minima are
  noisy on this machine, the medians are not), for **586 KB -> 653 KB** of code
  (+11%). Peak working set is unchanged (45.6 MB), and the emitted output is
  byte-identical. MSVC's whole-program optimization (`/GL` + `/LTCG`, `build.bat
  --lto`) measured **neutral**: medians 126.7 ms against 127.2 ms, identical
  memory — expected, since the amalgamation already is one translation unit and
  the only calls crossing a module boundary are the handful of `native`
  functions, so there is nothing left for LTO to inline across. `--lto` stays
  available as a flag and is off by default because it costs link time (a full
  `/GL` link, seconds rather than milliseconds) for nothing measurable here;
  `/Ob3` is in `--release` because it is the cheapest few percent the compiler
  has left. The hand-written ring's CMake release build is unchanged (`/O2`), so
  its numbers above stay comparable to earlier records.
- **`Str`'s buffer counts characters, zero-based (T35).** `StrSmallVector::_len`
  used to be the *stored byte count* — the terminating NUL was part of it — so
  every read paid for the terminator: `size()` was `_len - 1`, `empty()` was
  `_len <= 1`, `end()` was `raw() + (_len - 1)`. It is now the **character
  count**, the same zero-based convention the generic `SmallVector` uses for its
  elements, with the NUL one byte past the text in the allocation `_cap`
  measures in bytes. Reads became arithmetic-free and the `+1` moved into the
  write paths (`push_back`, `resize`, `assign`, `append`), which run once per
  mutation rather than once per read. Layout, capacity knob, `constexpr`
  construction, `Str` semantics and emitted output are unchanged (checked with
  `tools/constexpr_probe.cpp` under both backings, the five differentials, the
  bootstrap fixed point and a `cmp` of the two compilers' output). Measured on
  the self-hosted compiler transpiling the full source set, release `/O2 /Ob3`,
  interleaved run-for-run (`tools/_bench_ab.mjs`), two windows of 15 and 20
  pairs: before 122.7/131.8 and 126.5/136.2 ms (min/median), after **118.0/126.4
  and 120.7/131.3 ms** — **~4% faster in both windows**. Peak working set is
  unchanged (the layout is identical: `_len`/`_cap` are still two `Int`s). The
  change is header-only, which is why `tools/stress.js` now invalidates its
  cached `Native.obj`/`common.obj` on a newer `cppsrc/**` header: a cached object
  built under the old convention linked into a program compiled under the new one
  is an ODR violation, and it showed up as a 6-byte file reported as 7 bytes.
- **The AST's roles and attribute keys are enums (`AstXmlNode`, T36).** Profiling
  the two rings showed the self-hosted compiler's cost centre: 2,522,264
  `xmlAttr` calls, 68,148 child scans and 16,823 temporary child lists for a
  6,357-line self-transpile — every one of them a `Str` key comparison or a list
  allocation, where the hand-written ring reads typed fields. The AST carrier is
  now `AstXmlNode` (`cppsrc/rtl/astxml.kt` + `.hpp`): the node's role is an
  `AstNodeKind` and an attribute's key an `AstNodeAttributeKind`, so lookups are
  integer compares; attribute *values* stay `Str`, and `ast::astNodeKindText` /
  `ast::astNodeAttributeText` turn an enum back into the schema's spelling for the
  dump (the `.astxml` goldens are unchanged, byte for byte). The enums are
  prelude/RTL types so both rings share one definition; the language-level
  `XmlNode` stays the general tree a program builds. Two effects, same release
  setup and interleaved A/B over the full source set: the node shrank from 312 to
  **172 bytes** (`List<AstNodeAttribute>` 264 -> 152), and min/median runtime fell
  from **120.6/128.1 ms to 81.4/84.3 ms (~33%)** with peak working set 21.3 ->
  **16.1 MB** — the self-hosted ring is now *smaller* than the hand-written one
  (22.8 MB) and its slowdown against it narrowed from 3.5-3.7x to **2.4x**
  (34.6/39.8 ms). Emitted output is byte-identical; the five differentials and the
  bootstrap fixed point stay green.
- **Table lookups do not rebuild their tables (T37).** The scanner's accessors -
  `reservedWords()`, `multiCharOperators()`, `getTokenRules()` - built their table
  and returned it **by value**, so every call copied it: `matchOperator` runs for
  every token, which made a 24-entry keyword list and a 12-entry operator list per
  token. The tables are now file-level `var` statics (`specs/statics.md`, the
  feature's first use inside the compiler): the generated pass builds each once
  before `main`, the accessors hand out a raw `*List<T>` into them, and both
  lookups share one comparison function (`tableMatch(view, table, exact)` - exact
  for a reserved word, first-prefix for an operator) instead of each editing its
  own loop. The C++ ring mirrors it (`lex::tableMatch`, `getTokenRules()` returns a
  pointer to a function-local static), and the differential drivers call
  `simse_initStatics()` first because they host a generated component that has no
  `main`. Measured on the self-hosted compiler over the full source set, release,
  interleaved pairs in one window: **88.4/93.1 -> 72.3/77.3 ms** (15 pairs),
  **85.5/90.6 -> 71.0/76.6 ms** and **86.8/93.1 -> 69.8/75.0 ms** (20 pairs each) -
  **15-20% less time**, with one short window measuring 6% (this machine
  throttles; the direction is the same in every window). Emitted output is
  byte-identical, peak working set unchanged at 16.4 MB (the tables are small; the
  win is the allocation churn). Against the hand-written ring the gap is now
  **~2.0x** (36.4/42.7 ms), from 3.5-3.7x before T35-T37.
- **The node's category is an enum too (T38).** The last text comparisons in the
  pipeline are gone: the category (`kind` - "Stmt.If", "Type.Generic", ...) is now
  a field of type `AstNodeCategory`, so `xmlKind(node)` returns an enum and sites
  like `if (xmlKind(expr) == "Expr.IntLit" || ...)` are integer compares all the
  way down. It stays a *separate* enum from the role because the two are
  independent in this schema - `attach`/`renameRole` re-root a node (new role)
  while its category travels with it - and `AstNodeAttributeKind.Kind` is gone
  with it (the category is not an attribute any more). The dump still prints
  `kind='...'` first, from the enum (`ast::astNodeCategoryText`, with the Simse twin
  `common.xmlKindText` for diagnostics), so the `.astxml` goldens and the five
  differentials are byte-identical. Effects: `AstXmlNode` is 176 bytes and carries
  one fewer attribute per node, and the runtime win is small - **2-16%** across
  three interleaved windows of 20/25 pairs (min 73.7->71.9, 93.6->86.7, 82.6->69.6
  ms; this machine throttles hard, so the honest summary is "a few percent, always
  in the same direction"). The point is uniformity: every test on a node is an
  integer compare.
- **The scanner's table match got cheap pre-tests (T39).** `tableMatch` used to
  build each entry's `Str` and compare character by character; it now (a) tests the
  view's **first character** against the entry's, (b) tests the **length**, and only
  then compares the rest, (c) reaches the entry through a **raw pointer** into the
  table instead of copying the `Str` out of it, and (d) compares through the new
  `StrView.startsWithPtr(text: *Str, length: Int)`, which takes a pointer and a
  known length, so nothing in the lookup copies text. The C++ ring mirrors it
  (entries by reference, the same three tests in the same order). Effects, all with
  byte-identical output (compiler emission, tokens, AST dumps) and the full gate
  green: the self-transpile is **73.1/78.5 -> 64.9/69.8 ms** (-11%, 20 pairs), the
  scanner stage alone over the same 6,357 lines is **317.9/333.0 -> 261.1/271.3 ms**
  (-18/-19%, 20 pairs), the parser stage **415.2/427.8 -> 357.1/368.2 ms** (-14%),
  and against the hand-written ring the scanner is now **1.49x** (180.6/191.5 vs
  268.6/278.9 ms on that stage) where the same stage measured ~1.8x before. The
  first cut of this change did not compile and had an infinite loop - see the note
  below.
- **Throughput baseline, and the one phase that is superlinear (T40).** At this
  commit the self-hosted compiler over its own source set (`cppsrc`, 6,357 lines,
  release) runs in **65.1/68.2 ms** (min/median, 7 interleaved pairs,
  `tools/_bench_ab.mjs`) against the hand-written ring's **35.6/42.6 ms** -
  **1.6-1.8x**, where this run started at 3.5-3.7x (T35-T39), or roughly **95k
  lines/s**. That is a small fraction of what the emitted C++ costs to compile
  (`cl.exe` over the ~190 KB amalgamation), so the transpiler is not what makes a
  build slow. One known edge: cost is quadratic in a *single* file's declaration
  count. Measured with one file of N trivial 8-line functions (regenerate: a
  `package big` plus N copies of `fun fN(a: Int): Int { var b = a + N; if (b > 10)
  { b = b - 1 }; return b }`), `./simse.exe --root ...` takes **396.7 ms for
  16,005 lines (~25 us/line)** and **6,339.8 ms for 64,005 lines (~99 us/line)** -
  4x the input for 16x the time. Per stage on the same inputs the parser is linear
  (54.8 -> 200.5 ms, 3.7x) and codegen linear (62.8 -> 223.3 ms, 3.6x) while
  **sema is 408.3 -> 6,155.4 ms (15x)** - the quadratic phase. Decision (agreed
  with the user): accept the current speed and stop optimizing; the compiler's own
  workload is many small files, where the cost is linear. Fixing sema's scaling is
  deferred, not forgotten - the suspects are the get-append-insert copies into
  `globalFunctions`/`packageDecls` in `collectGlobal`, `buildVisible` re-running
  per file, the per-call overload scans in `analyzeCall`/`markExtensionUsed`, and
  `lookupValue`'s scope walk, all in `cppsrc/sema/Sema.kt` (`guide4ai.md`
  section 8).
- **A custom `Dictionary` exists, and is measured faster (T41).**
  `cppsrc/rtl/smdictionary.hpp` implements `SmDictionary<TKey, TValue>` - the .NET
  shape: one `Entry` per row (`hash`, `next`, key, value), chains by row index, a
  power-of-two bucket table whose **mask** is a field (`hash & _mask`; 16 buckets
  growing 4x), the `Str` hash reading **8 bytes at a time** with a shift-xor fold
  and the scalar keys left as-is (a `>> 16` xor at most), and removal by tombstone
  (`hash = -1`). Rows are **append-only** (no free list, no `_freeCount`): a removed
  row stays a hole, and both the iterator-producing calls and a **growth** pack the
  live rows together - `growBuckets()` compacts in the same pass because it already
  walks every row to rebuild the chains, which is what took the compiler-shaped
  workloads from behind to parity and the end-to-end from parity to a win. An insert
  into an **empty bucket** skips the chain walk and the key compare altogether. It
  is selected by `SIMSE_DICT_SM` (CMake option + `build.js` mirroring, like the
  `List`/`Str` backings) and verified like them: both backings emit
  **byte-identical** C++, and both pass the differentials, the bootstrap fixed
  point, `simse_tests.exe` and stress 23/23 on both rings.
  Measured with `tools/smdict_stress.cpp` (`/O2 /DNDEBUG`, keys prebuilt so the
  benchmark does not time `to_string`, min of three rounds, ms sm/std): at 200k
  entries 2M **hit** lookups 94-101/50-57 (the one remaining deficit), 2M **miss**
  lookups 23.5-24.8/38.6-44.6 (**~1.6x faster**), fill 16.6-18.2/15.6-16.4 (parity),
  erase half the table 7.4-7.7/9.2-9.9 (~1.2x faster); at 2,200 entries 400k hit
  lookups 20-24/6-7 (the same deficit); iteration over 200k rows 1.5-1.6/12-13.5
  (**~8x faster**), 20k deep copies of a 150-entry dictionary 12-15/65-81
  (**~5x faster**); the compiler's own shapes - 30k rounds of 24 inserts/72
  lookups/8 erases 56.7-58.3/55.6-58.1 and the emitter's clear-and-refill
  50.2-54.6/50.9-52.4 - now at **parity** (they were ~1.3-1.4x behind before
  `growBuckets` started packing). End to end on the 6,357-line self-transpile (37
  interleaved pairs over two windows) it is **~6% faster**: 62.6/68.2 and 61.5/69.5
  ms against the std backing's 67.2/72.7 and 65.2/73.8 ms, both statistics; peak
  working set is equal (16.2-16.5 vs 16.4-16.5 MB). The earlier sketch's two
  separate lists (entries in one, cached hash + link in another) were replaced by
  the single row because splitting measured ~1.7x slower on hit-heavy lookups. What
  remains before it could be the default is the ~1.8x hit-lookup deficit on
  cache-resident tables - the open suspects are the bucket holding a *row index*
  (a second dependent load per hit) where MSVC's bucket holds a *node pointer*,
  `SmallVector::operator[]`'s inline/heap branch on every access, and the cached-hash
  pre-test paying an extra compare on hits where MSVC compares the key directly.
  Nothing else in the tree depends on either backing.
- **The RTL reads files line by line, and a naive 1BRC measures it (T42).**
  `FileStream` (`cppsrc/rtl/filestream.hpp`, prelude `cppsrc/rtl/fs.kt`) is
  `openFileStream(path): *FileStream` plus the **struct methods**
  `readLine(): Opt<Str>`, `readLineInto(buffer: *Str): Bool`, `fileSize(): Int64`,
  `close()`; `nowMillis()` (`cppsrc/rtl/timeops.hpp`) is the monotonic ms clock
  the benchmark reports its own time with. The methods have to be members, not
  free natives, because the emitter calls a handle's operations as members
  (`stream.readLine()` on a `*FileStream` emits `(*stream).readLine()`), exactly
  like `Cursor`/`XmlNode`. `readLine` `std::getline`s into a `std::string` the
  stream recycles and returns a fresh `Str`; `readLineInto` reads ahead in a
  256 KiB chunk, finds the newline with `memchr` and copies into the caller's
  `Str`, whose heap block is reused, so there is no allocation after the longest
  line seen. Both strip a trailing `\r` and accept a missing final newline.
  Measured on `benchmarks/onebrc` (the challenge, naive: no mmap, no chunked
  parsing, no per-station arrays, no threads; 10,000,000 rows, 133,931,538 B =
  127.7 MiB, 100 stations, one ARM64 laptop, release, min/median, interleaved):
  Simse `readLine` **2040/2048 ms** (66 MB/s, 6.6 MB peak WS), Simse
  `readLineInto(*line)` **1156/1161 ms** (**116 MB/s**, 6.5 MB), the naive C++
  baseline (`getline` + `find(';')` + `stod` + `unordered_map<std::string, Stats>`)
  **1480/1490 ms** (~90 MB/s, 5.9 MB), and the Bun generator/reference `check`
  **727 ms** (184 MB/s). All the reports are byte-identical (the Simse and C++
  writers are compared after stripping the `\r` Windows text-mode stdout adds;
  the baseline switches stdout to binary mode). So the reader is a **1.8x** swing
  on this workload, and it dominates the parsing. The benchmark itself was later
  narrowed to the in-place reader (T43, `benchmarks/onebrc/benchmark.md`); these
  three-mode numbers stand as the reader comparison, and all three reads remain
  in the RTL (`stress/read-lines` covers them). Two findings are recorded here
  because they cost cycles: `*x` **borrows** (the aggregation takes
  `*Dictionary<Str, Stats>`) while `&x` **boxes a copy** of the local, so
  mutations through the box are silently lost (the earlier `&List<Int>`/
  `*List<Int>` probe: `val h: &List<Int> = &items; h.append(1)` leaves
  `items.size() == 0`, `val p: *List<Int> = *items; items.append(3)` leaves
  `p.size() == 1`); and the two lookups per line (`get` then `insert`, since no
  API exposes a stored value in place) are a library gap, which is the remaining
  distance to the 727 ms reference - see `guide4ai.md` section 8 item 8.
  `benchmarks/onebrc/benchmark.md` has the method, the notes (tenths as `Int`, the
  half-toward-positive-infinity rounding rule, CRLF vs LF) and the run commands.
- **`Span<T>`, `StrView`, and the in-place line reader (T43).** One borrowed view
era replaced two: `cppsrc/rtl/Span.kt` + `span.hpp` declare `Span<T>` (a `*T`
pointer plus a length, `size`/`isEmpty`/`at`/indexing/`slice` in the C# two forms)
and `cppsrc/rtl/StrView.kt` + `strview.hpp` declare `StrView` - *embeds* a
`Span<Char>` and adds the byte surface (`charAt`, `find`/`indexOf`, `startsWith`,
`startsWithPtr`, `substr`, `toString`), built by `spanOfStr(*text)`; `spanOf(*items)`
borrows a list. The old `Cursor<T>` (a `&List<T>` + start + len), the RTL's
`StrView` (a `*Str` + start + len) and the compiler's own `common.StrView` are gone,
and `Parser.kt` iterates `Span<Token>` while both scanners use `StrView`. Two
design points are load-bearing and measured, not stylistic: an **alias**
(`typealias StrView = Span<Char>`) does not survive the emitter's receiver-type
lookup - a chained call through one is emitted as the wrong conversion
(`simse_int_toString` on a view) - and prelude **methods** are invisible to the
emitter's inference, so the view's operations are declared as natives with explicit
symbols, which is what gives `view.slice(0, n).toString()` and `"x" +
view.toString()` their types. (A third, pre-existing edge bit the test: a chained
call on a handle method - `stream.fileSize().toString()` - has no inferred type and
now picks the wrong `toString`; bind the middle step to a typed `val`, as
`guide4ai.md` section 9 already says.)
  Adding the RTL type name also exposed a name-resolution bug: the emitter consulted
the RTL *name* list before the program's own declarations, so a declared type of
the same name (the compiler had a `common.StrView`) was shadowed in every emitted
signature. `typeName` (both rings) now checks the declared types first and lets any
package other than `rtl` win; T23 and the five differentials stay byte-identical,
and the tracked `cppsrc/simse_bootstrap.cpp` was regenerated (its embedded source-map line
numbers shifted with the parser/scanner edits).
  `FileStream` gained `readLineView(): Opt<StrView>` (T42's reader) on the same
  readahead buffer and the same `nextLineSpan` code path as `readLineInto`, so the
  two are the same lines by construction; the view is valid until the next read.
  `stress/read-lines` covers all three readers over CRLF, empty lines, a missing
  final newline, the 23-byte inline boundary, and - generated into the git-ignored
  harness work directory - a 300 KB line behind a short one, so the buffer's tail
  shift, growth and refill are exercised; `stress/span` (renamed from `cursor`) and
  `stress/lambdas`/`recursion` cover span iteration (24/24 with the self-hosted
  compiler and with the C++ ring, `simse_tests.exe` 48/48 at the time).
  Measured on the 1BRC (10M rows, 127.7 MiB, release, 3 interleaved pairs,
  min/median, all reports byte-identical, 6.5 MB peak working set):
  **view 1087/1088 ms** against **into 1156/1161 ms** - the in-place reader is
  **6%** faster than the recycled buffer and **1.43x** ahead of the naive C++
  baseline (1550/1570 ms), against 1.88x across the three Simse readers. What
  remains is two `Str`s per line (`tenths` takes a `Str` and the dictionary is
  keyed by `Str`) and the dictionary's two lookups per line (section 8 item 8).
  The benchmark was then narrowed to this variant alone - C++ STL baseline against
  the in-place Simse program, 4 interleaved pairs: **1093/1106 ms against
  1390/1405 ms, i.e. 1.27x faster than the naive C++** (the ratio has run
  1.26-1.40x across sessions), at a 6.5 MB peak working
  set against the baseline's 5.9 MB, and byte-identical reports.
  `benchmarks/onebrc/benchmark.md` is the write-up.

- **Nested expressions are lowered to temporaries (T44).** The linear pass gave the
  emitter one *statement* vocabulary; `cppsrc/linear/ExpressionLowering.{h,cpp}`
  (`linLowerExprs` in `cppsrc/linear/ExpressionLowering.kt`) gives it one
  *expression* vocabulary. Together with T26's control-flow lowering, the emitter
  is left with a strictly structural job: no `if`/`while`/`switch`, and no
  expression deeper than one operation, to understand. It runs as
  `lowerExprs(simplifyBody(lowerBody(...)))` -
  after the peephole trim, so nothing folds a temporary back - and leaves every
  expression either a simple operand (a literal, a name, a qualified name, a lambda,
  an lvalue path) or a single operation over simple operands; anything deeper is
  bound to an untyped `_sm_expr<n>` `VarDecl` numbered by a per-body counter (like
  the labels), inserted in front of the statement that needed it - inline for a
  `var`, whose name must stay visible for the rest of its region (wrapping one in a
  block narrowed the scope and produced "undeclared identifier"), and inside
  `Stmt.Block` for everything else, so no jump can cross an initialization.
  Temporaries stay *untyped*, so the emitter emits `auto` - the same path the
  hoisted `switch` subject already used, and the reason this pass is not where the
  types come back (the sema-inference item in `guide4ai.md` section 8). Two
  boundaries are deliberate: an **lvalue path stays a path** (binding it would copy
  what is behind it, and a mutating call on the copy would be lost; only its indices
  and arguments are flattened, so `a[i + 2].append(x)` becomes
  `a[_sm_expr1].append(x)`), and **`&&`/`||` are left alone** because their operands
  are evaluated conditionally - those belong to the control-flow lowering, and their
  `ifTrue`/`ifFalse` shapes (plus a `?:` the grammar does not have yet) are written
  down in `impl_specs/linear-lowering.md` as the next step.
  Two goldens moved, both deliberately: `tests/golden/sema_switch_label.kt.cpp.expected`
  (the non-constant case label now hoists `f()` into `_sm_expr1`) and
  `stress/hello/expected.cpp` - the harness compares that file byte for byte, but
  `--update` only rewrites `expected.stdout`/`stderr`/`exit`, so it is copied out of
  `stress/.work/hello/out.cpp` by hand. Verified: both configurations green (the
  five differentials byte-identical, T23's two-step bootstrap byte-identical),
  `simse_tests.exe` **50/50** in both (the new pass and `Span.kt` are checked as
  sources too), `bun tools/stress.js` **24/24** with the self-hosted compiler and
  **24/24** with the C++ ring.

- **The lowered declarations get real types from a semantic step (T45).** The pass
  above bound nested expressions to `_sm_expr<n>` locals and left them *untyped*, so
  the emitter emitted `auto` for them and guessed the type whenever it needed one
  (the receiver of a chained call, `Res<T>.value`, a native extension's return).
  `sema::inferTypes` (`cppsrc/sema/TypeInfer.{h,cpp}`, `semInferTypes` in
  `TypeInfer.kt`) now runs as the last step of the lowering
  (`inferTypes(lowerExprs(simplifyBody(lowerBody(body))), facts, body)`) and fills
  in the type of every untyped `VarDecl` it can prove, walking the statements in
  scope order (shadowing included) instead of relying on emission order.
  The program-level facts it reads - declared types, enum names, functions and
  methods with their receiver patterns, native extensions, file-level statics - are
  the emitter's own collected tables, threaded to the emitter as a parameter rather
  than stored in a field: in the Simse ring a data-class *field* would have to name
  another package's type, and the amalgamated file emits the packages in its own
  order, so the first version failed to compile with `'facts': unknown override
  specifier`. **Generics stay symbolic**: a type parameter in scope is a fine type
  to spell (the emitted C++ is a template, so reification is still the C++
  compiler's job), an explicit instantiation substitutes its type arguments into the
  call's result, and a member call binds the extension's parameters from its
  receiver (`Box<Int>.get()` with `get(): T` is `Int`). A type the emitter cannot
  spell in that body - a parameter out of scope, an unknown name - is *not* written:
  the declaration keeps its `auto`. Four initializer shapes are deliberately left
  alone (a lambda, `null`, `&x`/`*x`/`copy(x)` - they change representation in ways
  the pass does not model), and so is anything whose operand was one of them; in the
  compiler's own 12.4k-line output that leaves **42** `auto`s out of ~1,400 (1,402
  before the pass), all of them those shapes plus three small gaps recorded in
  `impl_specs/linear-lowering.md` (a prelude struct method like `Span.size()`, a
  native extension called as a plain function, a call through a function-typed
  local). Two costs are recorded too: the type helpers the emitter shared with the
  inference moved to `sema/TypeInfer.h` (one list of RTL type names, now the union
  of what both rings needed - they had drifted apart), and the Simse ring needs its
  nodes **re-rooted** (`semReRole`) because a type read out of a declaration carries
  the role it was read from (`ReturnType`) and the emitter looks children up by
  role; without it the pass annotated and the emitter saw nothing. Verified: both
  configurations green (five differentials byte-identical, T23's two-step bootstrap
  byte-identical in both), `simse_tests.exe` **51/51** in both (the new file is one
  more source test), `bun tools/stress.js` **24/24** with the self-hosted compiler
  and **24/24** with the C++ ring, and the 1BRC report unchanged. Two goldens moved
  again, deliberately: `tests/golden/sema_switch_label.kt.cpp.expected` and
  `stress/hello/expected.cpp` (hand-copied from `stress/.work/hello/out.cpp`, as
  `--update` never rewrites an `expected.cpp`).

- **A value position is one operation deep (T46).** After T45 the pass still let a
  *path* sit in a value position (`Int _sm_expr1 = self.x + self.y;`), which left two
  operations inside one temporary. The rule now is that *every* value position - an
  operand, a call argument, a conditional jump's condition, a `return`'s value -
  binds anything that is not a literal/name/qualified name/lambda:
  `Int _sm_expr1 = self.x; Int _sm_expr2 = self.y; Int _sm_expr3 = _sm_expr1 +
  _sm_expr2;`, `Bool _sm_expr1 = i < 5; ifTrue (_sm_expr1)`, and
  `Int _sm_expr1 = p.x; Int _sm_expr2 = _sm_expr1 + 1; return _sm_expr2;`. Aliases
  survive only in the three positions where they are load-bearing - a call
  **receiver** (`a[i].append(x)` stays a call on `a[i]` with the index flattened),
  an **assignment target**, and the operand of `&`/`*` - which the pass calls
  `Path`. One case is excluded from binding because it is not a copy: a **borrow of
  a temporary**. `*f()` is `simse_addressOf(f())`, and the RTL's contract is that
  such a pointer lasts for the call it is passed to (`cppsrc/rtl/types.hpp`), so
  hoisting it into a variable would outlive the pointee; a borrow whose operand is
  an lvalue (`*p`, `*self.field`, `*(list[i])`) is a place and *is* bound. Two more
  things the change had to get right: the Simse ring's `exprIsSimple` now treats the
  *absent* sentinel as simple (a bare `return;` has an empty value node, and the new
  rule promptly bound it, which the emitter printed as `/*unsupported*/`), and the
  ring asymmetry is explicit in the spec (`impl_specs/linear-lowering.md`). The
  output grows, which is the point: `cppsrc/simse_bootstrap.cpp` went from 12.4k lines and
  1,402 `auto`s at T44 to **18.2k lines, 5,157 temporaries and 931 `auto`s**, of
  which 711 are borrows whose type the inference still declines to spell (that is
  the next increment, and it is a `Pointer`/pointee rule away). Verified: both
  configurations green (the five differentials and T23's two-step bootstrap
  byte-identical), `simse_tests.exe` **51/51** in both, `bun tools/stress.js`
  **24/24** with the self-hosted compiler and **24/24** with the C++ ring, and the
  1BRC report byte-identical after rebuilding it with the new compiler.

- **A value receiver is a raw pointer in the emitted code (T47).** A receiver that is
  a *value* - a data-class method (`fun advance(...)`), `fun f(this: Point, ...)`,
  `fun Str.firstByte()` - is now emitted `T* self` instead of `T& self`, which makes
  `self` the language's own borrow form (`*T`) and one thing everywhere:
  `self->field`, a bare `this` reading as the object (`(*self)`), and a call site
  passing the receiver's **address** (`ns_f(simse_addressOf(x))` - the one form that
  covers a place and a temporary alike, the latter valid for the call per
  `simse_addressOf`'s contract). Since a value position is one operation deep (T46),
  that address is also the rule the *expression* lowering already speaks for a
  borrow, so the receiver stops being a special case there. What the user asked to
  keep, and it is kept: a receiver declared as a handle stays a handle - `this: &T`
  is still `std::shared_ptr<T> self`, so a body can store `self` in a list and the
  refcount survives; `this: *T` is still `T* self` (where `this` *is* the pointer, so
  `*this` is the pointee and such bodies do not change). *Native* extensions remain
  the host's business (their `T&`-taking helpers in the RTL did not change): the
  emitter passes the receiver expression for them exactly as before
  (`nativeReceiverArg`), which is why this landed without touching the runtime
  surface. Two extra pieces: the hand-written differential drivers
  (`tests/*_simse_main.cpp` call emitted receiver functions directly) now pass
  `&scanner`, and a receiver whose *type* could not be inferred is resolved by name
  (`findReceiverFnByName`) so it still gets the address form. Verified: both
  configurations green (five differentials byte-identical, T23's two-step bootstrap
  byte-identical), `simse_tests.exe` **51/51** in both, `bun tools/stress.js`
  **24/24** with the self-hosted compiler and **24/24** with the C++ ring, and the
  1BRC rebuilt with the new compiler reports byte-identically (1144 ms).

- **Conditions are decomposed into short-circuit jumps, lambda bodies run the whole
  pipeline, and the borrow shapes are typed (T48).** `&&`/`||`/`!` in a *condition*
  no longer reach the emitter as expressions: `lowerCondition` in
  `Linear.{cpp,simse}` tests one leaf at a time, `&&` jumping to the false target and
  `||` to the true one, a leaf spelled with whichever of `ifTrue`/`ifFalse` matches
  its value and emitting *both* jumps so the peephole folds the pair into one - no
  source negation ever becomes a negated expression, and a nested call argument
  whose `&&` cannot be decomposed keeps the condition a single jump
  (`containsShortCircuit`/`isDecomposable` draw that boundary). A lambda body is no
  longer a special single-expression case: `lambda()` builds the `return` the single
  expression stands for, runs the whole lowering on it (so a lambda's temporaries,
  labels and blocks are the same shapes as any body's) and sets the return type the
  bare `return null` needed. The type inference learned the three shapes it had been
  leaving to `auto`: `&x` is a counted reference to `x`'s value, `*x` is the address
  of what `x` denotes (`Pointer(T)` for a value, `Pointer` of the pointee for a
  handle, the pointee when `x` already is a pointer) and `copy(x)` is the value
  behind the handle - which is what took `tests/golden/program_expr.kt.cpp.expected`
  from `auto reference`/`auto dereferenced` to `std::shared_ptr<Int>`/`Int*` and
  `program_extension`'s `*self` to `Int`. Verified: both configurations green (five
  differentials byte-identical, T23 byte-identical), `simse_tests.exe` **51/51** in
  both, `bun tools/stress.js` **24/24**.

- **The linear form is a loop of stages, and blocks are folded at the end of every
  round (T49).** The emitter no longer spells the pipeline at each call site
  (`flattenBlocks(lowerExprs(simplifyBody(lowerBody(body))))`); it calls
  `linear::lowerForEmission` (`linLowerForEmission`), the loop the user specified:
  `lowerBody` / `simplifyBody` / `lowerExprs` run until none of them changes
  anything, then `flattenBlocks` runs, and while *that* changed something the round
  starts over - folding a block exposes a jump the peephole can fold, and a folded
  jump can free a label. Every stage now returns the body **and** whether it changed
  (`linear::Lowered` / `LinLowered`), which is what the loop tests; the stages only
  ever remove statements, so it terminates on its own (the guard bounds a bug).
  `flattenBlocks` folds a nested block into its parent unless splicing it would move
  one of its declarations across a jump, which C++ rejects (C2362): the rule is the
  exact condition `pos (J) < pos (D) <= pos (L)` for a declaration `D` the block
  brings up, a jump `J` (at any depth - it runs after everything before the block it
  is written in) and a label `L` at that level. In the compiler's own output: 2,895
  blocks (1,260 nested) and 21,965 lines become **1,339 blocks (555 nested) and
  18,925 lines** - and ~8.4k lines of Simse now transpile into that 19k-line output
  in ~0.25 s. One pre-existing bug had to be fixed on the way: `labelPass` scanned
  only a sequence's *own* statements for jumps, so the moment the expression
  lowering wrapped a jump in the block that carries its temporaries, a label whose
  only jump was that one looked unused and was deleted - a `goto` to a missing
  label, MSVC C2094. The scan (and the folding's own, which has the mirror-image
  question) now looks through blocks. Six `tests/golden/*.cpp.expected` goldens and
  `stress/hello/expected.cpp` moved (the latter hand-copied from
  `stress/.work/hello/out.cpp`, which the harness never rewrites), all of them the
  brace collapse and nothing else. Verified: both configurations green (five
  differentials byte-identical, T23 byte-identical), `simse_tests.exe` **51/51** in
  both, `bun tools/stress.js` **24/24** with the C++ ring and **24/24** with the
  self-hosted `simse.exe` rebuilt from the new output, the user's own two-step flow
  (`bun build.js --release`, rename to `simse_out1.cpp` + `simse_stage1.exe`,
  transpile again) byte-identical, and the 1BRC rebuilt with the new compiler
  reports byte-identically to the JavaScript reference (1187 ms, inside the
  1056-1177 ms session range `benchmarks/onebrc/benchmark.md` records).

- **The linear form's own slots move to the top of the body, and the folding can
  finish (T50).** The user's reading of the block that stayed: *hoist the variables,
  as a step after the types are resolved, to the start of the function.*
  `linear::hoistSlots` (`linHoistSlots`) does exactly that for the lowering's own
  storage - the `_sm_expr<n>` temporaries and the `simse_sw_<n>` switch subjects -
  and turns each initializer into an assignment where the declaration stood:
  `{ Bool _sm_expr2 = i == 3; if (_sm_expr2) goto L4; }` becomes `Bool _sm_expr2;`
  among the body's first declarations plus `_sm_expr2 = i == 3;` in place. A slot at
  the top of the body is a slot no jump can bypass, which was the *only* reason the
  folding kept those blocks (C2362), so this is what turns a body into one flat
  sequence of labels, jumps and assignments. The initializer keeps its position, so
  evaluation order and side effects do not move; what moves is where the storage is
  declared, which makes every slot live for the whole body (a bytecode frame's
  slots, no liveness reuse). It runs **after `sema::inferTypes`** - a declaration has
  to keep the type that pass proved, `auto x;` is not a declaration - so the
  pipeline is two phases, `lowerForEmission` then `inferTypes` then
  `linear::finishForEmission` (the hoisting, the peephole and the folding in the
  same loop shape). A slot with no type keeps its declaration and its block, and a
  source-level `val`/`var` never moves: its scope is the program's and two scopes may
  reuse a name. Numbers on the compiler's own output: **1,339 blocks (555 nested)
  and 18,925 lines become 758 blocks (94 nested) and 23,604 lines** - the extra
  lines are one declaration plus one assignment where there was one
  declaration-with-initializer, and the blocks that remain are the *program's* own
  scopes (a source `var` whose declaration a jump bypasses), not the lowering's. The
  flatness is not free: the 1BRC goes 1187 -> 1350 ms on the same machine (its hot
  loop's slots are now function-wide), which is the trade the user chose over
  carrying per-type handling in the placement rule; the transpile itself is
  unaffected (~0.09 s for the C++ ring, ~0.40 s for the self-hosted one, both over
  the same 8.4k-line source set). Verified: both configurations green (five
  differentials byte-identical, T23 byte-identical), `simse_tests.exe` **51/51** in
  both, `bun tools/stress.js` **24/24** with the C++ ring and **24/24** with the
  self-hosted `simse.exe` rebuilt from the new output (the `hello` case's
  `expected.cpp` moved again, hand-copied), the user's two-step flow byte-identical,
  and the 1BRC report byte-identical to the JavaScript reference. The one bug on the
  way was mine and the compiler caught it loudly: `isSlotName` compared
  `_sm_expr` with `compare(0, 7, ...)` where the prefix is 8 characters, so the C++
  ring hoisted nothing while the Simse ring (whose `startsWith` compiles to the RTL
  native) hoisted everything - T23's two-step bootstrap is what surfaced it.

- **What still keeps a block.** 94 blocks in the compiler's own output are nested in
  another block, and they are the *source's* nesting: a `var` the program declared
  inside a branch, whose initialization a jump to the sibling branch bypasses. The
  same hoisting applied to source variables would need renaming (two scopes may reuse
  a name) and would erase the program's own scopes; the lowering's slots are the part
  that has to go, and it is gone.

- **A lambda body is typed like any other body, and the IL carries what the pass
  proved (T51).** Two things were wrong behind one symptom. `for` inside a lambda did
  not compile: over a *machine* it emitted `smToYield(simse_addressOf(_sm_expr1))` for
  a receiver that already was one (a C++ type error, and the wrap is the identity
  there - `impl_specs/for.md`), and over a *container* the loop variable had no type,
  so `v.toString()` picked the `StrView` overload.

  Root cause: `LinearForm`'s `lambda()` ran `lowerForEmission` and `finishForEmission`
  on a lambda's statements but never `sema::inferTypes`, so a closure frame knew no
  inferred types at all - and even where the pass *had* run, a machine's `..T` never
  reached the frame, because a declaration is deliberately never written with it
  (`linear/Yield.cpp` relies on that to reject a `for` over a machine crossing a
  `yield`, and the emitted C++ types such a slot `auto`).

  So: `sema::Body` gained the lambda frame (`paramNames`/`paramTypes`/`captures`),
  `inferTypes` gained an out-parameter with **every** binding it proved (spellable or
  not), and that record travels into the IL body (`IlBody.inferred`). A backend seeds
  its spelling frame from it (`Emitter::ilSeedFrameTypes`: the pass's record first,
  then the declared slot types, which win) instead of walking statements, so the
  frame is typed in a function body, a lambda body and a machine's method alike. The
  `ilSeedBodyTypes` statement walk that used to do this for function bodies is gone.

  The change also needed **forward declarations** in the emitted C++: `IlFunction`
  now holds a pointer to `sema::Facts`, and packages are emitted in source order, so
  an aggregate can name a type from a later package. Both rings emit `struct X;` for
  every aggregate before any definition (four goldens gained exactly one line each;
  `stress/hello/expected.cpp` too).

  Verified: five differentials byte-identical, T23 byte-identical in both
  configurations, `simse_tests.exe` **55/55**, `bun tools/stress.js` **30/30** with
  the C++ ring, **30/30** with the self-hosted `simse.exe` and **30/30** with the
  stage-1 binary, and `bun tools/bootstrap.js` fixed point byte for byte
  (14,159 lines of Simse in 824 ms self-hosted, 177 ms for the C++ ring).

  Two regression cases came out of it, one per half of the bug. `stress/lambda-for`
  (both `for` forms and the indexed form, each inside a lambda) pins the *behavior*;
  `tests/fixtures/lambda_scopes.kt` pins the *rings* - and writing it immediately
  found a second, older divergence: the Simse ring let the enclosing body's rename
  reach a lambda's own declarations, so with two lambdas in one body that each declare
  a local `value`, the second came out `_sm_value_2` in one ring and `value` in the
  other. The corpus never showed it: the program compiled and ran the same either way,
  so only a fixture compared ring-to-ring (T22) catches it. The fix is the Simse
  renamer's masked rewrite handing `false` to a lambda body's own lists, which is what
  the C++ ring's `rewriteUses(..., false)` already did.

- **String literals are emitted into one table, read by index (T52).** The user's
  reading of the emitted output: *for large programs these optimizations add up*, so
  readability is not a constraint on what the emitter may spell. Every literal in the
  program now goes into a `static const Str __sm_stringTable[N] = { ... };` at the top
  of the file, sorted (so the two rings agree on every index - T22/T23) and built once
  before `main`, and each site reads its entry: `return __sm_stringTable[31];` where
  the source wrote `return "Expr.IntLit";`.

  What it buys, in the order it measured: a use that only **reads** the text -
  a comparison, or a `const Str&` argument, which is how every native in `fs.hpp` and
  the diagnostic printers take a string - binds the entry with no conversion at all,
  where a literal over the inline capacity used to build a heap-backed temporary at
  each such site (42% of the compiler's own literals are); and one copy of each text is
  shared by the whole program. An *owned* position still copies - the language's value
  semantics - which is why the win is ~2% and not more.

  The table cannot be `constexpr`: 42% of the literals exceed the inline capacity, and
  a heap allocation cannot escape a constant evaluation, so the entries are built by
  dynamic initialization. It is also **not** a `List<Str>` built by a `setupTexts()`
  with a dictionary: the emitter assigns the indices while compiling, so there is
  nothing to look up, and a growing `List` would reallocate and *copy* every entry -
  each copy of a heap-backed entry allocating again.

  Measured (interleaved A/B, same machine state, two runs of 13 and 19 pairs): the
  self-transpile of `cppsrc` goes 827/855 ms and 822/841 ms (best/median) **before** to
  816/835 ms and 808/824 ms **after** - about 2% faster, and ~1470 sites now read the
  table. A separate experiment that wrapped every literal in a `constexpr`
  `_to_smString(...)` helper instead was **3% slower** and was reverted: the RTL's
  `const char*` overloads already compare a literal in place, so wrapping only forced
  a temporary into existence where there had been none.

  Verified: five differentials byte-identical, T23 byte-identical in both
  configurations, `simse_tests.exe` **55/55**, `bun tools/stress.js` **30/30** with the
  C++ ring, the self-hosted `simse.exe` and the stage-1 binary, and
  `bun tools/bootstrap.js` fixed point byte for byte.

- **`when` replaces `switch`; data-class fields take Kotlin's `,`; `for` gained its
  pointer forms (T53).** Three source-level changes in one pass, plus one bug they
  exposed.

  `switch`/`case`/`default` are **gone** - Kotlin has no `switch`, so the language has
  exactly one selection statement, `when`, and the *parser* desugars it to the
  `if`/`else` chain it means (`Parser::parseWhen`), binding the subject to one generated
  `_sm_when<n>` declaration. Nothing downstream has a `when`: `StmtKind::Switch`,
  `SwitchCase`, `AstNodeCategory::StmtSwitch`, `AstNodeKind::Case`,
  `AstNodeAttributeKind::IsDefault`, `linear::lowerSwitch`, sema's constant-case-label
  rule and the `simse_sw_<n>` slot prefix are all deleted, in both rings and in the
  schema. A label is an arbitrary expression (`subject == label`), several labels on one
  arm share one body (`a, b ->` is one `||` condition), arms do not fall through, the
  arm body is a block, `else` must be last, and `break`/`continue` in an arm belong to
  the enclosing loop. `specs/functions.md` is the norm; `stress/when` pins the behavior
  (subject evaluated once, label group shares a body, loop `break`/`continue`, a
  no-`else` `when`, a nested `when`, an enum and a `Str` subject);
  `tests/fixtures/when.kt` pins the rings, `when_else_last.kt` the one diagnostic.

  A data class's field list is separated by `,` (`specs/declarations.md`), which is what
  the `.kt` sources now use - 190 separators across the compiler sources, fixtures,
  stress cases and doc snippets. The parser takes `;` there as well (it is what the
  sources used before, and the statement separator means removing it would buy nothing),
  so the change moved no golden but the three token dumps.

  `for (*x in xs)` / `for ((*x, i) in xs)`: the second wrap, `smToYieldPtr`, hands out
  each element's **place** (`..*T`), so a container of aggregates is walked without a
  copy per iteration and a write through the loop variable reaches the element. It is
  one more prelude function per container, not a second `for`: the machine is generic
  over its element type, so `..*T` gives `Opt<T*>` and `T**` with no special case, and
  sema's `for` gate now takes the wrap *name* from the call the parser wrote. Measured
  on the compiler's own hot walks (`declares`/`lowerStmts`/`containsShortCircuit`,
  release `./simse.exe` over `--root cppsrc`, interleaved A/B, 11 pairs): `while` +
  `*xs[i]` 824.3/839.5 ms (min/median) against `for (*x in xs)` 826.2/835.8 ms - the
  hand-written loop's cost without its index - and the *value* form 810.8/826.8 ms (a
  copy per element is not visible either, which is why the pointer form is a shape
  choice, not a rescue). The compiler's own statement/child walks now use it; the survey
  tool (`tools/_for_candidates.mjs`) counts 272 `while` list-walks left, 151 of them with
  no use of the index at all.

  The bug they exposed is **not** one of the three changes: an experiment that made
  `SmString::size_type` signed (`int32_t`, to silence the C4267 warnings) broke every
  `rfind`, because its `pos = npos` default then compares as `-1 >= last` and the
  backward scan never starts - `stress/strings`' `lastIndexOf` printed `-1`.
  `SmString::rfind` now tests `npos` explicitly (both the text and the char overload)
  instead of the type being reverted.

  Verified: five differentials byte-identical in both configurations, T23 byte-identical,
  `simse_tests.exe` **56/56**, `bun tools/stress.js` **32/32** on the C++ ring, the
  self-hosted `simse.exe` and the stage-1 binary (`stress/when`, `stress/for-pointer`
  new).

- **One implementation per built-in, and 32-bit sizes everywhere (T54).** The RTL's
  three `std::` backings and the defines that selected them are gone: `List<T>` is
  `SmallVector<T, 4>`, `Str` is `SmString`, `Dictionary<K, V>` is the RTL's
  `SmDictionary` (the .NET row/bucket shape), and `std::vector`, `std::string` and
  `std::unordered_map` appear nowhere in the repo's own code. `std::string` survives
  only at the *native boundary* - `simse_toStdString` / `simse_fromStdString`, the
  `std::getline(std::istream&, Str&)` helper, `FileStream`'s recycled line buffer,
  and the `std::filesystem`/`<fstream>` calls in `Native.cpp`/`common.cpp` - which is
  what the helpers are named for.

  Every size, length and index the RTL exposes is now the language's `Int`
  (`int32_t`), `Str::npos` is `-1`, and `std::size_t` appears only where the standard
  library's own signature demands one (allocation, `memcpy`/`memmove`/`memchr`,
  `char_traits::length`), as an explicit widening cast. That closes the last C4267
  (`size_t` → `Int`) warning group recorded in `impl_specs/rtl-abi.md`: a clean
  rebuild of the whole debug configuration (101 targets, including the amalgamation's
  own compile) reports **no warnings at all**.

  A signed size type has one trap, and it bit immediately: with `npos = -1`,
  `SmString::rfind`'s `pos = npos` default compares as `-1 >= last`, which is false,
  so the backward scan never started and every `lastIndexOf` returned `-1`
  (`stress/strings` caught it). Every `pos = npos` default has to *test* npos, not
  compare it; `rfind`'s text and char overloads do now.

  Removed with the backings: the `SIMSE_LIST_STD_VECTOR` / `SIMSE_STR_STD_STRING` /
  `SIMSE_DICT_SM` CMake options, their `add_compile_definitions`, `build.js`'s cache
  mirroring of them (its ABI agreement check is down to
  `SIMSE_STR_INLINE_CAPACITY`, with `SIMSE_NO_PACK4` still a build-time choice),
  `std::hash<SmString>` (only `std::unordered_map<Str, …>` ever needed it; `Str` keys
  hash through the RTL's own 8-bytes-a-time `simse_dict_hashKey`), and the three
  backing-comparison tools (`tools/str_bench.cpp`, `tools/_bench4.bat`,
  `tools/smdict_stress.cpp`). `SmString` keeps its `std::string` interop members -
  they *are* the native boundary - and gains a note saying so.

  `SmDictionary`'s first bucket table is now **4** entries instead of 16
  (`kDictInitialBuckets`): 4 `Int`s fit the `_buckets` list's own inline buffer, so a
  dictionary that stays small never allocates for its table at all - which is most
  of them (sema opens one per scope per file). It still grows 4x, so 4 -> 16 -> 64;
  the only cost is one extra rehash for a dictionary that outgrows four entries.

  Measured: `./simse.exe --root cppsrc` goes from 824.3/839.5 ms (min/median,
  interleaved A/B, 11 pairs, the mixed set of `while`/`for` loop shapes) to
  792.0/798.3 ms now (7 runs) - ~4-5%, which is the dictionary's own win (T41
  measured ~6% end to end) plus the inline first table. `bun tools/bootstrap.js`
  reports the fixed point byte for byte, 14.97 s to compile the published bootstrap
  and 773-793 ms for it to reproduce itself.

  Verified: clean rebuild of both configurations, five differentials byte-identical,
  T23 byte-identical, `simse_tests.exe` **56/56**, `bun tools/stress.js` **32/32** on
  the C++ ring, the self-hosted `simse.exe` and the stage-1 binary, and
  `bun tools/bootstrap.js` fixed point byte for byte.

- **The IL's opcode is an enum, not a string (T55).** `IlOp` carried `name: Str`,
  so every instruction ever built allocated (or copy-on-wrote) a string for a value
  that has exactly 32 possible spellings, every `op.name == "Call"`-style test in the
  extractor and the backend compared text, and `ilSignature` found a row by *searching*
  the table for that text - on the hottest path of codegen, once per operand read.
  `IlOp` is now `{ IlOpKind kind; List<int> operands; }`.

  `IlOpKind` has one member per signature-table row (same order, so `ilSignature(kind)`
  is a direct index and no longer a search), plus `Unsupported` for the instruction the
  extractor cannot build - a value in the enum rather than a missing string. The
  spelling survives only where a human needs it: `ilOpKindText(kind)` feeds the dump's
  opcode column (`--showLinearRepresentation`) and the two diagnostics that named an
  opcode, and `ilWritesDestination(kind)` is a `switch` instead of a linear scan of an
  eighteen-entry `List<Str>` per operand read.
  The backend switches on the enum throughout. Both rings: the same enum, table,
  `ilOpKindText`, extractor and backend live in `LinearForm.{h,cpp}`/`LinearForm.kt` and
  `Codegen.{cpp,kt}`; `impl_specs/linear-il.md` states the rule.

  Verified: both configurations rebuild with no warnings, the five differentials are
  byte-identical, T23 is byte-identical, `simse_tests.exe` **56/56**,
  `bun tools/stress.js` **32/32** on all three compilers; and, the decisive check, the
  two rings' `--showLinearRepresentation` dumps of the whole compiler **diff identically**
  (38,911 lines), which is what shows the change is the model's shape and not its
  behavior.

  Measured: interleaved A/B of two amalgamations built by `build.js --release` from the
  same flags, pre-change (HEAD's published bootstrap) against post-change, 11 pairs then
  15 pairs, each leg alternating run for run. Pre-change min 800.1 / 809.7 ms, median
  857.0 / 833.6 ms; post-change min 775.9 / 783.2 ms, median 823.0 / 807.7 ms. So
  `./simse.exe --root cppsrc` is **~3% faster at the minimum** (~4% at the median) - the
  IL is on codegen's hot path, and removing a string from every instruction shows up.

- **Block folding no longer copies a node per block per round (T56).** The fold
  (`linear::flattenBlocks`, `linFlattenBlocks`) used to build a fresh node for *every*
  block at every level of every round, before it knew whether the block would survive
  its own splice: `make_shared<Stmt>(*stmt)` in the C++ ring, and in the Simse ring
  `flattenInner(stmt: AstXmlNode)` - a node **by value** - plus `flattenPass(stmts:
  List<AstXmlNode>)` - a whole **list by value** - at every recursion level, on top of
  `xmlChildren` copying the block's statement list into a fresh `List` on every call.
  A `Stmt` carries four lists and two `Str`s; an `AstXmlNode` carries an attribute list
  that allocates. Most blocks are spliced away, so most of that was built to be thrown
  out.

  Now the flattened body of a block is computed up front and kept out of the item list,
  and the node is built in the one branch where the block **survives** the splice. The
  safety scan reads ``bodies[i]`` for a block item rather than the item's own body (a new
  `itemCrosses`/`linItemCrosses`), which is what lets the wrapper node not exist yet.
  The Simse mirror takes the level lists and items by pointer (`*List<AstXmlNode>`,
  `*AstXmlNode`) instead of by value.

  One rule the two rings have to state out loud, because it is not local: the splice
  decisions are a **batch**, before any body is used. Each decision reads the block bodies
  of the *other* items in the sequence, so emitting as you decide silently changes which
  blocks survive (see `impl_specs/linear-lowering.md`, "Block folding").

  Measured: `./simse.exe --root cppsrc` 792.0/794 ms -> **750/769 ms**
  (`bun tools/bootstrap.js`), and interleaved A/B of two amalgamations built by
  `build.js --release` from the same flags (13 pairs) 777.0/807.3 ms -> **729.3/742.8 ms**
  - **~6% at the minimum, ~8% at the median** from the folding rewrite alone.

  Verified: both configurations rebuild clean, the five differentials are byte-identical,
  T23 is byte-identical, `simse_tests.exe` **56/56**, `bun tools/stress.js` **32/32** on all
  three compilers, the two rings emit the compiler byte for byte, and
  `bun tools/bootstrap.js` reports the fixed point byte for byte (36573 lines, 1.11 MB).

  Also settled with a measurement: the fold is **not** a candidate for deletion. Stubbing
  it to a no-op (return the body, report no change) makes the emitted compiler 4.6%
  bigger - 38224 lines against 36527, and **3799 `goto`s against 2910** - because ~890
  jumps can only be folded once the blocks are gone. It is worth keeping, and now cheap.

  What it does *not* yet do: each level still builds its own `List<AstXmlNode>`, so a
  leaf statement is copied once per level it passes through. The single-output-list form
  the user proposed cannot be taken literally - the safety predicate is *level-relative*
  (it needs the whole flattened sibling sequence and the positions in it), so a walk that
  appends leaves downward loses exactly what the test reads. The form that works computes
  the level's items as pointer lists and materialises only surviving blocks; the fully
  flat form needs the leaf order plus block spans (splicing never moves a leaf, so the
  `mergedIndex` arithmetic collapses to leaf indices) and is left for a later pass.

- **No braces in the emitted C++: the inference now types the shapes it used to leave
  open (T57).** The emitter's only source of a bare `{` in a body was the C2362
  fallback - a declaration whose scope a jump would skip. It could not *hoist* such a
  declaration because the type was not known (`auto x;` is not a declaration in C++),
  so it declared in place (`auto x = value;`) and opened a block around it. **30**
  declarations in the compiler's own output took that path, from exactly three shapes,
  all of them gaps in the lowering-time inference rather than anything the emitter got
  wrong:

  - **a static constructor** - `Opt<T>.none()`, `Opt<T>.some(x)`, `Res<T>.ok(x)`,
    `Res<T>.err(m)` (18 of the 30). Nothing *declares* these forms: `Type.name(...)`
    lowers to `Type::name(...)` syntactically, so the result type has to be stated in
    the inference, and it is now stated per name - the four the spec spells in
    `specs/core-types.md`, each answering the type it is qualified by. Deliberately
    *not* a blanket "a static form answers its own type": `EnumType.fromInt(n)` answers
    `Opt<EnumType>` (`specs/declarations.md`).
  - **a `Res<T>` field read** - `result.Value`, `parsed.Error` (11 of the 30). The
    inference checked the spelling `specs/core-types.md` documents (`value`/`error`),
    but the RTL's own fields are `Value`/`Error` and the emitter only *remaps* the
    lowercase pair (`Codegen.kt:3442`) - a name it does not remap is emitted as written
    - so the sources use the capitalized one and it never matched. Both spellings type
    now. (The dual spelling is a wart; the spec and the sources disagree, and this
    records the reality rather than changing the language.)
  - **calling a value** - `predicate(c)`, `rest(c)` where the parameter is
    `CharPredicate`, a `typealias` for `(Char) -> Bool` (2 of the 30). An indirect call
    answers the callable type's return type, resolved through the alias exactly as the
    emitter resolves it (`resolveAlias`).

  Result: **30 blocks -> 0** in the emitted compiler, and the IL is cleaner for it -
  `DeclareInit` ops **64 -> 7** (only the `for` lowering's own typed locals, none
  crossed), every remaining declaration hoisted to the top of its body. Measured:
  `./simse.exe --root cppsrc` **750/769 -> 731/738 ms** (`bun tools/bootstrap.js`), so
  the hoisting costs nothing here.

  Sample-first, as asked: `stress/flat-blocks` is the case that pins all three shapes
  (two early returns of the same `Opt<Int>.none()` that the fold merges, a `Res<Str>`
  field read, and a `size()` result), 0 blocks, run end to end by `bun tools/stress.js`.
  One golden changes on purpose: `tests/golden/program_expr.kt.cpp.expected` (a
  `Res<Int> _sm_expr1;` hoisted, its `auto` declaration becoming an assignment).

  Verified: both configurations rebuild clean, the five differentials are byte-identical,
  T23 is byte-identical, `simse_tests.exe` **56/56**, `bun tools/stress.js` **33/33** on
  all three compilers, the two rings emit the compiler byte for byte, and
  `bun tools/bootstrap.js` reports the fixed point byte for byte (36688 lines, 1.12 MB).

- **The pointer form of `for` used where a loop was copying its element (T58).** Four
  index walks in the compiler's own sources handed each element to the body *by value*
  - `val attr: AstNodeAttribute = like.attributes[i]` - so every iteration paid for a
  copy of an aggregate:

  - `simNameAttrs` (`cppsrc/linear/Simplify.kt`) - and this one copied **twice** per
    element, because the `else` branch then put the copy into the result list. It is now
    `for (*attr in like.attributes)` with an explicit `attrs.append(copy(attr))`, so the
    copy that remains is the one the result list actually needs (and the `Name` match
    copies nothing);
  - `exprReplaceRole` (`cppsrc/linear/ExpressionLowering.kt`) and its sema twin
    `semReplaceRole` (`cppsrc/sema/TypeInfer.kt`) - `for (*child in existing)` with
    `kids.append(copy(child))`;
  - `parseSkeleton`'s token walk (`cppsrc/skelparser/SkeletonParser.kt`) -
    `for (*token in tokens)` with `addTerminalChild(copy(token))`.

  What the pass has to do, since it is not a find-and-replace: the pointer form makes the
  binding a `*T`, so in the body `*x` (which was the address of the *copy*) becomes `x`
  (the pointer now in hand), and a use that needs a `T` becomes `copy(x)`. The compiler
  reports every one of those as a type error (`cannot convert from 'AstXmlNode' to
  'AstXmlNode *'`), so the work is convert -> build -> fix, one loop at a time - which is
  also why each `copy(x)` is worth reading as a confirmation that the copy is intended.
  A loop is only a candidate when the index is used nowhere else in the body, the
  container is not touched in the body (the pointer form holds the container, the index
  form re-reads it), and the binding is never assigned (with the pointer form that would
  write the element instead of a copy). Roughly a quarter of the loops shaped like this
  in the tree pass those three tests; the rest read the container again or use the index
  for something else, and need a per-loop decision.

  Measured: interleaved A/B, 13 pairs, 724.1/764.5 ms -> **712.3/742.3 ms** (~1.6% at the
  minimum, ~2.9% at the median); `bun tools/bootstrap.js` 731/738 ms -> **717/725 ms**.
  Four loops is a small slice of the compiler, so the per-loop share is small too - the
  point of the record is the shape and the rules, not the total.

  Verified: both configurations rebuild clean, the five differentials are byte-identical,
  T23 is byte-identical, `simse_tests.exe` **56/56**, `bun tools/stress.js` **33/33** on
  the self-hosted and hand-written rings, no golden changed (the change is in the
  compiler's own sources, so the fixtures' emitted C++ is untouched), and
  `bun tools/bootstrap.js` reports the fixed point byte for byte.

- **The IL is a strict bytecode now: one instruction is one operation over declared,
  typed slots (T59).** The instruction list was *already* one operation per instruction,
  but a slot the extractor had synthesized for a position the statements did not hold in
  a slot of its own - a read's base, a call's receiver - carried **no type**, so the
  backend folded it: the whole expression went back inline at its single use, and the
  emitted C++ computed several operations in one statement (`_sm_expr1 = items[i].n;` is
  an index and a field read). The fold had also become load-bearing for *correctness*
  rather than text: a `GetIndex` temporary stood in for a place, and only the re-inlining
  made the address the right one.

  What changed, in both rings:

  - **The type rules answer about one expression** (`sema::typeOfExpr` /
    `semTypeOfExpr`, new): the same rules the pass applies to a whole body, asked with an
    explicit name environment - the extractor's flat frame - so a slot the extractor
    synthesizes gets the type a declaration could have spelled.
  - **A typed synthesized slot is declared with the frame**: its `Declare` goes to the
    top of the instruction list, exactly where `hoistSlots` puts the lowering's own slots,
    so a body is one block of declared registers, no jump can cross a declaration, and
    the emitted C++ keeps T57's *no braces* (0 blocks in the compiler's own output).
  - **A read's base is a place instruction**: `IndexAddr`/`FieldAddr` write the address
    of the place (`&items[i]`, `&self->source`) into a `*T` slot that is declared and read
    by name - so a read copies no aggregate and a call that mutates its receiver reaches
    the original. A base whose *value* is already a handle needs no address (the handle is
    the base); an *unnameable* base keeps the value form, because the address of something
    whose type is unknown is not a thing the emitted C++ can spell.
  - **New opcode `GetStaticAddr`** (`dst = &<name>`): `*static` is the address of the
    static, not of a copy of it. (`*reservedWordTable` had been returning the address of a
    local copy - the fold hid it, because the copy was never materialized.)
  - **`Deref`'s operand follows the place/value rule**: a `Member`/`Index` chain that is an
    inline value is a place (`*items[i]` is `&items[i]`); a call's result is a value, whose
    address is taken at the call.
  - **The backend does not inline anything**: a typed slot's text is its name, a typed
    declaration is `T name;` where the frame put it, and the *only* fold left is a slot
    with no type at all - 25 over the compiler - declared where its single definition is
    (`auto x = <value>;`), with the C++ scope rule ([stmt.dcl]/3) applied at that position.
  - **A machine's class reaches the type rules** (`IlFunction::selfDecl`,
    `sema::Body::selfDecl`): the lowering built it, so `this._sm_self` types as `*List<T>`
    instead of nothing - which is what had made a prelude machine's `size()` call take the
    member-call path with a dereferenced receiver (`(*this->_sm_self)->size()`).

  Measured over the compiler's own tree: 537 bodies, 37,132 instructions, 1,636
  synthesized slots of which **25 untyped** and **14 folded** (was 845 / 637); 949
  `FieldAddr`, 216 `IndexAddr`, 9 `GetStaticAddr`. The published bootstrap goes 36,696 ->
  **40,943** lines (1.12 -> 1.26 MB) and the two transpiles cost more - the
  address of every place is now a declared slot rather than an expression the emitter
  rebuilt: `--root cppsrc` **805/808 -> 859/863 ms** self-hosted, **178/187 -> 198/200 ms**
  hand-written; the `cl.exe`-only compile of the published file 15.26 -> **15.51 s** and the
  compiled bootstrap reproduces itself in 862 ms (full cycle 16.37 s). Programs behave
  identically (the whole corpus and the suite agree on the output); what changed is the
  *shape*: the emitted C++ is one operation per statement.

  Verified: both configurations rebuild clean, the five differentials are byte-identical,
  T23 (`simse_out1.cpp` == the stage-1 regeneration) is byte-identical, `simse_tests.exe`
  **56/56**, `bun tools/stress.js` **33/33** on the self-hosted ring *and* on the
  hand-written one, the two rings' IL dumps are byte-identical over `cppsrc` (40,136
  lines), and `cppsrc/simse_bootstrap.cpp` was regenerated with
  `bun build.js --release --out ...` so `bun tools/bootstrap.js` reports the fixed point
  byte for byte.

  Two goldens change on purpose (`tests/golden/for_iteration.kt.cpp.expected`,
  `tests/golden/lambda_scopes.kt.cpp.expected`), and one behavioral bug was caught while
  landing it: a static *read* into a typed slot is a copy, so `names.append(x)` appended to
  the copy and `names` stayed empty (`stress/statics`) - the reason a static used as a base
  is `GetStaticAddr`.

  Docs updated with it: `impl_specs/linear-il.md` (the instruction table, the corpus
  numbers, the codegen rules, the capabilities table), and the published numbers in
  `docs/getting-started.md`, `docs/how-it-works.md`, `docs/state-of-the-field.md`
  (15,040 source lines, 56 tests, 33 stress cases, the bootstrap block above).

- **The generated constructors stop copying their arguments (T60).** The `_make_<Name>`
  factory is the one call boundary the emitter owns (a user function's parameters are the
  language's value semantics, and the RTL natives already take `const T&`), and it was the
  worst of them: every non-scalar argument was copied **twice** - once into the parameter,
  once into the field - so constructing a `data class` with four `Str` fields allocated
  eight times. The factory now moves what it built the aggregate out of:

  ```cpp
  // before                                   // after
  ns1_Rec ns1__make_Rec(Str a, Str b, ...) {  ns1_Rec ns1__make_Rec(Str a, Str b, ...) {
      return ns1_Rec{a, b, c, d};                 return ns1_Rec{std::move(a), ...};
  }                                           }
  ```

  That is the RTL's own idiom - `AstXmlNode(AstNodeKind, AstNodeCategory,
  List<AstNodeAttribute>, Array<AstXmlNode>)` takes by value and moves into its fields - and
  it is strictly better than the two shapes it replaces: a temporary argument costs no copy
  at all (it is elided into the parameter) and an lvalue costs exactly the one copy value
  semantics require. `T&` parameters were the first idea and are wrong (they cannot bind a
  literal or a temporary); `const T&` also removes the second copy but *forces* one for a
  temporary, so by value plus a move dominates both. Scalars, enums and raw pointers ride in
  a register as before.

  Measured, and the honest answer is two different numbers. On the **compiler's own
  workload it is not measurable**: interleaved A/B of two release boot binaries over
  `--root cppsrc` (12-14 pairs each) reported 857.6/867.0 -> 847.0/858.8 and
  864.7/880.0 -> 860.9/876.3 ms - a consistent sign, but inside the machine's wobble - and
  the reason is the RTL: the compiler's own values are small enough to live in the inline
  buffers (`List<T>` is `SmallVector<T, 4>`, `Str` holds 23 characters inline), so their
  copies never allocate. Where it *does* show is a construction-heavy program whose values
  are past the inline buffers: 2,000,000 constructions of a `data class` with four
  32-character `Str` fields, the two emissions compiled with `/O2` and interleaved
  (10 pairs) - **306.2/311.4 -> 168.1/170.0 ms** (~1.8x, half the allocations), identical
  output. So this is a win for a *user's* value-heavy program, not for the compiler.

  Verified: both configurations rebuild clean, the five differentials are byte-identical,
  T23 is byte-identical, `simse_tests.exe` **56/56**, `bun tools/stress.js` **33/33** on the
  self-hosted ring and the hand-written one, and `cppsrc/simse_bootstrap.cpp` regenerated so
  `bun tools/bootstrap.js` reports the fixed point byte for byte (41,066 lines, 1.26 MB).
  No golden changed: the fixtures' data classes are scalar-only, so their emission is
  identical (and so is every `expected.cpp` in the corpus).

### Note: the shape of a lookup like this

Worth recording because the first attempt at T39 got it wrong in four ways the
compiler *did* catch and one it could not:

- `view[0]` - a data class has no indexing; `StrView` reads through `at(0)`.
- `entry[0]` is fine when `entry: *Str` (`Str` has indexing; `*Str` derefs), but
  `entry.len` is not: `len` is `StrView`'s field. Use `size()`.
- passing a `*Str` to `startsWith(text: Str)` is a type error *and* the copy the
  change was meant to remove; hence `startsWithPtr`.
- `continue` before the loop's `i = i + 1`: the compiler cannot see it, and the
  lookup spins forever. Increment first, then `continue`.

- **A call packs its trailing arguments, and a list has a literal (T61).** The one
  thing a Simse program could not do in one instruction was build a small list: every
  element was an `append`, so `val keywords = ["static", "var", "val"]` was three calls
  and three growth checks - and a function wanting "all the rest" of its arguments
  (`format(shape, items...)`) could not be written at all. Both are now the same
  instruction, `Pack` (`impl_specs/linear-il.md`): the IL's `newarr` + fill.

  - **A callee whose last parameter is a list packs.** `fun addAll(values: *List<Int>)`
    called as `addAll(1, 2, 3)` builds one list of three elements; `sum(6, 7, 8)` with a
    by-value `List<Int>` packs too. In the extractor, not the parser: the arguments are
    already extracted into slots there, the frame is flat, and the pack is one more
    instruction over slots that exist.
  - **The counted forms are deliberately excluded** (`&List<T>`, `PList<T>`, which are
    the same `std::shared_ptr<List<T>>`): a packed list is a throwaway temporary, so a
    control block plus a reference count that drops at the end of the same statement
    would be cost with no use. `sum(1, 2, 3)` against a `&List<Int>` parameter is the
    arity error it always was (`stress/diagnostic-pack-counted`), and the zero-copy
    spelling is the borrow.
  - **One argument for one parameter is the list itself** - `addAll(*xs)` passes it and
    builds no one-element list of a list - which is what tells `addAll(*xs)` from
    `addAll(1)` when both have one argument. An argument whose type the rules cannot name
    leaves the call exactly as it was.
  - **`listOf<T>(a, b, c)` is the literal**, the same instruction spelled directly. Since
    `List` is `SmallVector<T, 4>`, a literal of up to four elements stores them in the
    inline buffer and allocates nothing: one construction, no growth, no heap. It is
    *not* the type's own name doing double duty: `List<T>(...)` stays the RTL's
    construction, which is a **count** (`List<Int>(3)`, `List<Bool>(4, false)`), so a
    literal can never be mistaken for a size. Written without type arguments,
    `listOf(2, 3, 5)` takes its element type from the first value. The prelude declares
    it (`native("simse_listOf") fun listOf<T>(values: *List<T>): List<T>`) so the
    checker and the type rules have a signature to read, and the extractor emits the
    `Pack` into the call's own destination instead of a call.
  - **A call argument's handle is inferred** (`specs/functions.md`, "Handles at a
    call"): a `*T` parameter accepts a `T` argument (its address - so an API can change
    `fun f(xs: List<Int>)` to `fun f(xs: *List<Int>)` and every existing call keeps
    compiling, and stops copying), a by-value parameter reads through a `*T`/`&T`
    argument, and `&T` boxes a copy of what it is given. Types that are not the same are
    not converted (`List<Int>` against `*List<Str>` is the type error it always was),
    and a `*T` *binding* still writes its `*`.
  - **A member call resolves by its receiver, and an ambiguous name resolves to
    nothing.** `callTarget` (the extractor's window on the program's declarations, the
    same rule the checker applies) now unifies the receiver's type, because two types
    may each have a method of the same name - `Analyzer.exprType(expr: *AstXmlNode)` and
    `IlExtractor.exprType(e: AstXmlNode)` both exist, and a name is not a declaration.
    That was harmless while the lookup only decided packing; with the argument
    conversion it decided *types*, so it had to become exact.
  - **The RTL's count constructions stay as they were** - `List<T>(n)` and
    `List<T>(n, value)` - and `Array<T>(n)` keeps its count construction too (an array
    is fixed-length, so a count is what it is built from - `specs/built-in-types.md`).

  Measured over the compiler's own tree (`bun tools/_il_report.mjs`): 545 bodies,
  38,151 instructions, **18 `Pack`** (the sources' own list literals - `listOf<IlSignature>`
  in `LinearForm.kt`, `listOf<Str>` in `Scanner.kt`, `listOf<AstNodeAttribute>` in
  `Parser.kt`, ...), 1,595 synthesized slots of which 93 untyped (a `for`'s machine slot
  is one, so the count moves with the number of `for` loops) and 15 folded. The published
  bootstrap goes 41,066 -> **42,633** lines (1.26 -> 1.31 MB) and self-transpiles in
  **1078/1113 ms** (was 847/858 on the T60 tree with the smaller source), the hand-written
  ring in 240/246 ms (`cl.exe`-only compile 17.0 s, the compiled bootstrap reproduces
  itself in 1101 ms, full cycle 18.13 s) - the rings' *ratio* is unchanged at **4.5x**,
  which is the number that does not move with the machine's load. This is a *user*
  feature: the compiler itself packs nothing, so its own throughput is not the
  measurement that matters.

  Verified: both configurations rebuild clean, the five differentials are byte-identical,
  T23 is byte-identical, `simse_tests.exe` **56/56**, `bun tools/stress.js` **35/35** on
  the self-hosted ring and on the hand-written one (with the new `pack-args` case, and
  `diagnostic-pack-counted` asserting the counted form is still refused), and
  `cppsrc/simse_bootstrap.cpp` regenerated so `bun tools/bootstrap.js` reports the fixed
  point byte for byte. No golden changed.

  Docs updated with it: `specs/functions.md` ("Packing the trailing arguments"),
  `specs/containers.md` ("Constructing a list"), `impl_specs/linear-il.md` (the `Pack`
  row, the corpus numbers).

- **The compiler's own Simse sources were migrated to the new vocabulary (T61, same
  change).** With the literal and the packing rule in hand, the `.kt` ring was swept
  file by file for three shapes: a local list built by consecutive `append`s (a literal
  now), an `if`-chain testing one local against values (a `when` now), and an index walk
  `while (i < C.size())` whose element is used as a place (`for (*item in C)` now). 29
  conversions in the small files, 14 in `LinearForm.kt`, 11 in `Parser.kt`, 6 in
  `Codegen.kt`, 16 in the sema pair, plus `Simplify`/`Yield`/`Linear`/`ExpressionLowering`
  - and the refactor is *why* the emitted compiler now carries 18 `Pack`s. The rules
  that kept it safe are worth keeping: the `when` subject must be a plain local that no
  arm assigns to (the lowering evaluates it once, an `if`-chain re-reads it); the pointer
  `for` is for a container the body does not mutate, with an index used *only* to index
  it, and only where the element is used as a *place* (`*C[i]` becomes the loop variable
  itself - a leftover `*` on a pointer is the one bug this pass produced, and the
  generated C++ caught it: `xmlAttr(*field, ...)` on a `*AstXmlNode`). Nothing outside
  `cppsrc/**/*.kt` changed in the sweep.

- **The value/handle conversion got its second half, and `xmlAttr` became a borrow (T62).**
  The conversion table was already what a call argument and a binary operand asked for; the
  destination-driven half was missing, so a `*T` could not be *returned* or *assigned*
  where a `T` was wanted. Now `Codegen`'s `needsReadThrough` (and its `Codegen.cpp` mirror)
  reads the spelling off the two types - a `*T`/`&T` where the destination slot, the
  returned type, an assignment or a file-level `var`'s declared type is a `T` is
  `*(x)` - which is what lets an accessor hand back a borrow. `ns2_xmlAttr` returns `*Str`
  (the attribute's own storage, or the shared `xmlMissingAttr` for a missing one) instead of
  constructing a `Str` per call, and its ~250 callers did not change: the positions they
  read it in ask for a `Str`. Three correctness fixes came with it: the emitter's
  `inferType` for `*x` is the *type-directed* rule the type pass already used (a pointer
  operand's `*x` is a load, not another address - the old guess spelled `*(*stmt)`), and a
  binary operation whose *both* operands are handles of the same pointee reads both through
  (`xmlAttr(a, Name) == xmlAttr(b, Name)` is a string comparison - pointer equality there
  silently broke `semaUnifyReceiver`, which the sema differential caught), and the type
  pass's rule for `a op b` is the left operand *as a value*, so the slot the extractor
  declares for it is a `Str`, not a `*Str`.

  `copy(...)` is still spelled by hand where the destination's type cannot be read: a
  container of `Str` (`names.append(copy(xmlAttr(param, Name)))`) and a native extension's
  type parameter (`Dictionary<K, V>.has(key: K)` - the extension's parameters are not in
  the facts, so the extractor cannot resolve `K`). Closing that gap is the follow-up - and
  T65 is that follow-up: both shapes convert now, and no `copy` is left in the ring.

  Measured (`tools/_bench_ab.mjs`, 7 interleaved runs, same input): self-transpile
  **1000.8/1026.9 ms** against the pre-change compiler's **1068.5/1090.3 ms** (min/median)
  - about 6%, not the 33% the profile's `ns2_xmlAttr` row suggested (that row's *total* CPU
  includes its callers). `bun tools/bootstrap.js` now reports the fixed point byte for byte
  at **1021 ms** self-transpile, 254 ms for the C++ ring.

  Verified: both configurations rebuild clean, the five differentials are byte-identical,
  T22/T23 pass in both, `simse_tests.exe` **56/56**, `bun tools/stress.js` **40/40** on the
  self-hosted ring and on the hand-written one, and `cppsrc/simse_bootstrap.cpp` regenerated
  so `bun tools/bootstrap.js` reports the fixed point byte for byte. No golden changed.

  Docs updated with it: `specs/memory-model.md` ("Automatic dereference": the operand rule
  with two handles, and the value positions a destination's type spells) and
  `impl_specs/linear-il.md` ("The conversion": the destination-driven spelling, the
  borrowing accessor, and why the two operand sides had to agree).

- **The parser's token-text helpers borrow the text they compare (T63).** `checkText`,
  `matchText` and `expectText` take `*Str` instead of `Str`, the closest the `.kt` ring can
  get to the hand-written ring's `const char *` (`Parser.cpp`): the token text is read, never
  kept. Measured **neutral** (`tools/_bench_ab.mjs`, 7 interleaved runs: 1011.5/1033.4 ms
  against 1023.1/1032.8 ms), because the copy is *conserved*, not removed: a name argument
  no longer copies at the call (`&x`) but the comparison in the callee materializes the
  pointee into a slot (`_sm_base1 = *(text)` - the `*T -> T` row), and a literal argument
  now needs a `Str` slot built for its address (`_sm_base3 = __sm_stringTable[40];
  _sm_base2 = &_sm_base3;`) where it used to ride the instruction as a pool operand.

  What would make the pattern pay is **folding the read-through into its single use**: the
  slot a conversion writes is written once and read once, next to each other
  (`readThrough` then the operation that asked for it), so a backend could print `*(text)`
  at that one use - `peek().text == *(text)`, no copy at all - the way it already folds an
  untyped slot. That is the extension to the folding rule, not a new rule.

- **`Str` grew the two operations a joiner wants (T64).** `reserve(count)` (the emitted
  `simse_str_reserve`, a hint that grows the buffer once) and `appendStrPtr(value: *Str)`
  (an append of a *borrow*, the `Ptr` convention `StrView.startsWithPtr` already keeps), and
  the compiler's three joins now use them: `cgJoin`/`cgJoinChar` (Codegen), `ilJoinList`
  (LinearForm, whose `out = out + part` rebuilt the whole buffer per part) and
  `semaTypeTextList`, plus the two rings' `ilTypeText` builders, which now write into one
  reserved buffer instead of a `name + "<" + joined + ">"` chain of three.

  The shapes measured, over 256 parts x 40 chars (a 10 KB result) and 1024 x 200 (200 KB),
  as a standalone program (`stress/.work/repro2`, `nowMillis`, checksummed so nothing is
  optimised away):

  | accumulation | 10 KB, 500 joins | 200 KB, 100 joins |
  | --- | --- | --- |
  | `out = out + part` | 98 ms | 2819 ms |
  | `appendStrPtr` in place | 9 ms | 32 ms |
  | `appendStrPtr` + `reserve` | 13 ms | 15 ms |

  So the *in-place* append is the win (10x on small parts, 88x on large ones) and
  `reserve` pays exactly on the large result (2x there) and costs a little on the small one
  (the exact-size allocation and the extra pass that measures it beat what the doubling
  saved). The compiler's own joins are all in the small column, and the self-transpile A/B
  is neutral (1131.3 -> 1132.8 ms median, 7 interleaved runs) - the joins are a small share
  of the emitter's work; what they are for is the *shape* the language should offer.

  Two latent bugs surfaced on the way, both reported rather than fixed here (neither is on
  a path the compiler's own sources take): a `main` whose parameter is named `argv` emits
  `int main(int argc, char** argv)` with a local `List<Str> argv` of the same name
  (uncompilable C++), and a *plain* function whose name collides with a `native fun`
  extension picks up the extension's return type - `fun find(parts: *List<Str>, index: Int):
  *Str` typed as `Int`, from `Str.find`'s signature - because the emitter records an
  explicit-`this` native's receiver as *empty*, so `functionReturn` treats it as a plain
  function. (The second one is fixed by T65, below; the `argv` one still stands.)

- **`fmtStr`, and the conversion covers the parameters a call cannot type - so the
  compiler's own ring spells no `copy` at all (T65).** Two changes that share one root: what
  a *destination's* type decides, the extractor should emit, and nothing a program writes
  should have to say it twice.

  `fmtStr(fmt: *Str, items: *List<Str>): Str` is the fixed-shape joiner
  (`simse_fmtStr` in `cppsrc/rtl/listops.hpp`): the format's `|` characters are replaced, in
  order, by one item each, the length is known before anything is written (the format minus
  the points it fills, plus every item), and the result is assembled in one `reserve`d
  buffer. `Codegen.kt` writes **38** of its string chains as `fmtStr` now, the shape an
  emitter has thousands of: `fail`'s `file:line:col: msg` is `fmtStr("|:|:|: |", ...)`, and
  `exprInner`'s wrappers are `*(|)`, `(|).get()`, `simse_addressOf(|)`, `<|>`, `(*|)[|]`.
  Because it takes its items the way any pack-taking call does, the trailing arguments pack
  straight into the `*List<Str>` - `fmtStr("| |", "a", "b")`, no `listOf` to write, no `*`
  on a literal - which is the *second* thing T61 bought and the first place it reads well.

  The conversion half. T62 read the row off the callee's parameter, which left three shapes
  whose type the parameter list cannot fix (`impl_specs/linear-il.md`, "A parameter the call
  cannot type is read from the argument"): a `native fun` extension's receiver, which the
  declaration spells as an explicit `this` first parameter, so the fact's own `receiver` was
  **empty** and `callTarget` did not even see the call as a member; a bare type parameter
  (`Dictionary<K, V>.has(key: K)`), which the *receiver* binds and no argument can be
  compared against; and a construction (`AstNodeAttribute(kind, value)`), which the
  extractor sees as a plain call with a `Name` callee and no facts entry, so its arguments
  were never converted at all. The first is now `extensionReceiver`/`receiverParams`/
  `isExtensionDecl` (and the parameters a call's arguments convert against start *after* the
  receiver - what `packStart` and the pack-arity test count), the second is `isBareTypeParam`
  (a bare parameter's *form* is a value, so a handle argument is read through to the pointee
  it names itself), the third is `dataClassDecl` (the class's `Field` nodes are the "parameters"),
  and all three are mirrored in both rings. That `isExtensionDecl` also fixes the `Str.find`
  collision T64 reported: `functionReturn` skips an extension, so `fun find(parts:
  *List<Str>, index: Int): *Str` types as `*Str` again.

  With that, `copy(...)` left the ring's source entirely - **79 sites**, every one of them a
  conversion (or, on a value, the identity row) that the destination already decides. What
  the emitter produces is a strictly smaller compiler: the amalgamation goes from 45,293 to
  45,265 lines and from 945 to **931** `AstXmlNode` scratch temporaries, because a conversion
  now folds into the destination that asked for it (`self->selfType = *(selfTypePtr)` where
  it used to be a slot assigned and then read) and a no-op `copy(v)` of a value drops out
  (`receiver = (base)` becomes `receiver = base`).

  Measured with `tools/_bench_ab.mjs` (9 interleaved runs, the pre-change amalgamation
  compiled from the same sources so only the *compiler binary* differs):
  **1087.5/1103.4 -> 1075.9/1085.1 ms** min/median, and `bun tools/bootstrap.js` reports
  1080/1083 ms for the self-transpile against T64's 1131.3/1132.8. For once the win is where
  the emitters are: the loops the compiler runs most, over AST nodes that no longer get
  copied into a slot per argument.

  Verified: both rings rebuild clean, T23's two-step bootstrap is byte-identical, T22's
  emission differential is unchanged, `simse_tests.exe` **56/56**, `bun tools/stress.js`
  **40/40** on the self-hosted ring and **40/40** on the hand-written one,
  `cppsrc/simse_bootstrap.cpp` regenerated so `bun tools/bootstrap.js` reports the fixed
  point byte for byte in *both* configurations (release 45,266 lines/1.38 MB, debug with
  assertions on - which is what would have caught an aliasing mistake), and no golden
  changed. A structural diff of the two amalgamations (slot names and string-table indices
  erased, 632 functions on each side) shows 27 functions differing, every difference one of
  three shapes: a dropped no-op `copy`, a folded temporary, and a read-through inlined at
  its single use - no type, callee or operator moved.

  Docs updated with it: `specs/built-in-types.md` (`fmtStr` beside `reserve`/`appendStrPtr`),
  `specs/memory-model.md` ("Extraction": the operation is what the extractor emits, not what
  a program writes) and `impl_specs/linear-il.md` ("The conversion": the three parameter
  shapes, and the type itself is never inferred).

- **The type questions borrow the frame, and the pipeline's scans read nodes in place
  (T66).** The `.kt` ring was 3.8-4x the hand-written one; it is **2.8x** now
  (`tools/_bench_ab.mjs`, 9 interleaved runs on the same tree: **1247.5/1289.2 ->
  885.1/919.1 ms** min/median against the C++ ring's 314.3/323.6 ms). What the copies cost
  was measured first, not guessed: the RTL's `SmallVector` copy constructor was instrumented
  (a size bucket, plus a phase tag the ring sets around each stage) and one self-transpile
  turned out to copy **6.78M lists / 18.8M elements**, of which **6.20M lists / 17.0M
  elements are `List<AstNodeAttribute>`** - i.e. `AstXmlNode` copies, each one deep-copying
  up to four attributes and their `Str`s. So every change below is "copy less", and the
  phase tag said where: 6.5M of the element copies sat in `linLowerForEmission`, 6.0M in
  `linFinishForEmission`, 3.1M in the IL extractor.

  The largest single site was the extractor's type question. `SemInfer.typeOf` re-seeded the
  caller's frame into a new scope *per question* - one dictionary insert per name, per
  question - which the phase tag measured at **258 of the 361 ms** the extractor spent in
  `semTypeOfExpr` (11,247 questions, ~23us each, on frames of a few dozen names). `SemInfer`
  now has a `baseScope`, the *outermost* scope, **borrowed** from the caller (`typeOf`
  assigns the pointer); everything the inference marks still lands in a pushed scope above
  it, so shadowing is unchanged. The same pass rebuilt the extractor's frame dictionary from
  scratch whenever a slot was added (`frameChanged` -> a full walk of `out.vars` at the next
  question - 66 ms); `addVar`/`setSlotType` now insert the one entry (`frameAdd`), which is
  sound because the frame is append-only. Together: **361 -> 24 ms** in the type questions
  and **483 -> 140 ms** in `ilExtractUnit`.

  Then the scans that copy a block's statement list just to read it: `linJumpsTo` (run once
  per label of a body, so its `linBlockStmts` copies were quadratic-adjacent) and
  `linStmtCrosses` now walk a block's `Body` children in place (`linStmtJumpsTo`), and the
  simplifier's prune/label passes and the slot hoisting bind the statement they are looking
  at as `*AstXmlNode` instead of a value (a value binding *and* the append copied every
  statement twice per pass). In `ExprFlattener` a statement nothing was rebuilt under is
  passed on as it is, instead of being rebuilt through `exprReplaceRole` (which copies every
  child of it) - and the parser hoists `parsePostfix`'s argument list out of the `while`,
  clearing it per call, and builds a call's callee+arguments as **one** children array
  (`roleOf`) where `attach`-per-argument rebuilt the array per child.

  Unchanged on purpose: everything about *what* is emitted. The strongest check is
  structural - the pre-change compiler binary and the post-change one transpile the same
  `cppsrc` tree to **byte-identical** C++ (`cmp`), and the differentials, the two-step
  bootstrap and the goldens agree. Two later attempts were reverted for failing exactly that
  check, and they are the two things worth knowing before trying this again: a `flat` that
  returns the original node when nothing under it was bound (it changes which expressions
  get bound, because the rebuild is also what decides *what* a bind sees), and an early-out
  in `linHoistSlots` (neutral in time, so it was dropped rather than kept).

  Verified: both CMake rings rebuild clean (five differentials byte-identical, T23's
  two-step bootstrap byte-identical), `simse_tests.exe` **56/56**, `bun tools/stress.js`
  **40/40** on the self-hosted ring, and the amalgamation the release build emits is
  unchanged.

- **A call on `this` passes the receiver, not the address of a dereference of it (T67).**
  The T47 rule ("a value receiver is `T* self`") made `receiverArg` take the receiver
  expression's *address*, which for the bare `this` came out as
  `ns1_ilEmitOpsChecked(simse_addressOf((*self)), il, level)` - a dereference followed by
  the address of the dereference, i.e. `self`, spelled the long way, at **1,741** call
  sites in the compiler's own emission. It copies nothing (`*self` is an lvalue, so
  `simse_addressOf` binds the referent and returns it), but it *reads* like a copy of the
  whole receiver in the artifact that is supposed to be read by hand, every time a method
  calls another on itself.

  The emitter now special-cases the one receiver that already *is* the address: a bare
  `this` with a value receiver is passed as `self` (C++'s `this` inside a closure class)
  by the new `selfPointer()`, in both rings and in both places the language spells the
  receiver's address - the call (`receiverArg`) and the borrow `*this` (`ExprDeref`). A
  receiver that is a *place* is untouched: `counter.twice()` is still
  `ns1_twice(simse_addressOf(counter))` and `holder.counter.bump()` still takes
  `&holder.counter`, which is `stress/pointer-place`'s rule from the other side.

  Neutral in time (`tools/_bench_ab.mjs`, 9 interleaved pairs: 791.1/801.6 -> 799.6/808.2
  ms - the fix was never about a copy, since `&(*p)` folds to `p`); the change is in what
  the output *says*. Covered by the new `stress/receiver-shapes` (`expected.cpp` pins the
  four spellings: a self-call, `*this`, a local, and a field), which is also the corpus'
  first case with a method call on `this` - that gap is why no golden moved when the
  emitter changed.

  Verified: both CMake rings rebuild clean (T22 byte-identical, T23's two-step bootstrap
  byte-identical), `simse_tests.exe` **56/56**, `bun tools/stress.js` **41/41** on the
  self-hosted ring and **41/41** on the hand-written one, and `cppsrc/simse_bootstrap.cpp`
  regenerated so `bun tools/bootstrap.js` reports the fixed point byte for byte.

- **The ring's remaining index walks iterate by pointer (T68).** `for (*x in xs)` binds the
  *element* - one pointer, no copy - where `while (i < xs.size())` with `val x: T = xs[i]`
  copies a value per iteration, and `for (x in xs)` copies one too. The ring already used
  the pointer form in most places (all 27 `for` loops in `Codegen.kt`, the statement walks in
  `Simplify.kt`); this is the rest of it, chosen by the same rule the guide states - the
  container is read-only in the body, the element is only read (or used as a place), and no
  pointer escapes the loop - which is what makes the copy-free form safe: a pointer into a
  list whose storage moves under an append, or one that outlives the walk, is the bug this
  rewrite can produce.

  The ones that were *hot*, not just tidy: the scanner's rule walk (`nextToken` bound one
  `TokenMatcher` per rule per token position - the ring's innermost loop); the renamer
  (`SimRenamer.rewrite` took its node **by value**, so the shadowing pass rebuilt every node
  of every body after copying it, and `renamedTo` bound a `SimRenameScope` by value, which
  copies the `Dictionary` a scope holds, once per name node of the body); the two overload
  scans in `Sema`, which bound a candidate declaration per call; and the extractor's
  `typeIndex`, which bound the type node it was about to read. The rest (`collect`,
  `emitTypes`, `emitForwardTypes`, `emitStatics`, `emitStaticInit`, `emitDataClass`,
  `emitEnum`, `emitEnumConversion`, `emitNativeDeclarations`, `collectProgramNames`,
  `beginScope`, `emitFunction`, `ilFunctionFor`, `registerMachineType`, `emitMachine`,
  `signatureText`, `lambdaOf`, `readFileAsTokens`) are once-per-program or once-per-body
  walks: nothing measurable on their own, kept because a value copy of an `AstXmlNode` (or
  of a struct holding one) in the ring's own source is exactly what T66 spent its effort
  removing.

  Measured: a compiler built from the same tree with only the style difference
  (`tools/_bench_ab.mjs`, 25 interleaved pairs) **777.5/811.1 -> 758.8/781.1 ms** min/median
  (~2-4%), and the emitted C++ is **byte-identical** - the strongest check for a change like
  this, because the two compilers read the *same* sources and only their own code differs.
  Verified as usual: five differentials byte-identical, T23's two-step bootstrap
  byte-identical, `simse_tests.exe` **56/56**, `bun tools/stress.js` **41/41** on both rings,
  and `cppsrc/simse_bootstrap.cpp` regenerated with the fixed point byte for byte.

- **`fmtStr` fills a matched shape only (T69).** The operation's contract had two edge cases
  ("with no `|` left the remaining items are appended, and with no item left the rest of the
  format is appended verbatim"), and they cost a branch and a `min` in a function the emitter
  calls for every shaped line it writes. The shape a *fixed format* writes is **one item per
  `|`**, and that is now the only one the operation handles: the point count is compared to
  the item count up front, and anything else - a mismatch, or no item list - comes back as
  the format itself, unfilled, rather than half-filled. `simse_countChars` is the helper that
  makes the check (one scan of the format, which the removed `points` loop did anyway), and
  the write pass behind it is one loop with no per-point condition.

  The assumption was checked, not assumed: all **38** literal `fmtStr(...)` call sites in the
  ring pass exactly one item per `|` (a scratch scan over `cppsrc`), and the compiler built
  with the change emits **byte-identical** C++ to the one built without it - which is what
  makes "the shape always matches" a fact about the ring rather than a hope. Measured
  **neutral** (`tools/_bench_ab.mjs`, 25 interleaved pairs, the CMake libraries rebuilt on
  each side so both link a matching header: 834.7/890.8 -> 842.4/881.6 ms) - the branches it
  removes were never the cost; what the change buys is a function with no edge cases in it.

  Docs updated with it: `specs/built-in-types.md` (the shape, and what a mismatch gets),
  `cppsrc/rtl/rtl.kt`'s prelude comment, and the header's own comment. Verified: both CMake
  rings rebuild clean (T22 and T23 byte-identical), `simse_tests.exe` **56/56**,
  `bun tools/stress.js` **41/41** on both rings, `cppsrc/simse_bootstrap.cpp` regenerated with
  the fixed point byte for byte.

- **`min`, `max` and `fmtStr` are written in the language now (T70).** The RTL's
  *operation* layer is moving off C++ where it can: a prelude `fun` **with a body** is
  emitted by the compiler for both rings and only when a program reaches it
  (`reachesPreludeBody`), which is how `List<T>.smToYield` already worked. What decides a
  candidate is not taste but a grep - a native whose only reference is the header that
  defines it has no C++ caller left:

  - `simse_fmtStr` went first (the user's example): the body is `charAt` + `append` +
    `appendStr` + `reserve`, all of them primitives that stay native, and the two edge
    cases of T69 stay gone. The header's `simse_countChars`/`simse_fmtStr` are deleted;
    the emitted C++ now carries `Str fmtStr(Str* fmt, List<Str>* items)` built by the
    compiler, and **the two-step bootstrap still holds byte for byte** - which is the real
    proof, because the stage-1 compiler *runs* the migrated function to produce its own
    output. Measured **neutral** (`tools/_bench_ab.mjs`, 25 interleaved pairs, CMake
    libraries rebuilt on each side: 768.7/785.8 -> 766.5/792.4 ms) - `simse_str_charAt` is
    an inline header function, so the loop the language writes folds into what the C++
    version did.
  - `simse_min`/`simse_max` became one generic each (`fun min<T>(a: T, b: T): T`), so the
    `Int`-only natives and the C++ templates behind them are gone. A generic Simse function
    is reified as a C++ template, and the ring's own `smaller<T>` experiment confirmed the
    checker accepts `<` on a bare type parameter.

  What the generic form exposed - and it is a *pre-existing* gap, not a regression in
  behaviour the old natives had: the type pass substitutes the arguments of an **explicit**
  instantiation into a call's result and nothing else (`sema/TypeInfer.kt`'s
  `functionReturn`; `sema/TypeInfer.h` says so in as many words). So `min(3, 2)` has type
  `T`, and a *member call* on it cannot resolve: `min(3, 2).toString()` emits the wrong
  native (the fallback picks the first extension named `toString` - `StrView`'s - and the
  generated C++ fails to compile, instead of a Simse diagnostic). `min<Int>(3, 2)` and
  `val n: Int = min(3, 2)` both work. Inferring the bindings from the *argument* types
  (the `semBindTypes`/`semSubstitute` pair already exists, used for extension receivers)
  is the fix, and it is the gate for migrating more generic RTL operations.

  The new `stress/rtl-simse` covers all three (nothing in the compiler calls `min`/`max`,
  so without it their bodies would never be compiled at all): the generic form for `Int`,
  `Str` and `Float64`, both instantiation spellings, `fmtStr`'s matched shape (`||`
  included) and its refusal. The migration rule and the `Span`/`StrView` verdict are
  recorded in `impl_specs/rtl-abi.md`. Verified: five differentials byte-identical, T23's
  two-step bootstrap byte-identical, `simse_tests.exe` **56/56**, `bun tools/stress.js`
  **42/42** on both rings, `cppsrc/simse_bootstrap.cpp` regenerated with the fixed point
  byte for byte.

- **`Str.isEmpty` is the language's own body, and a prelude body is emitted when a call
  reaches it (T71).** The second RTL operation off C++, and the first one with a
  **receiver**: `simse_str_isEmpty` is deleted from `strops.hpp`, and `rtl.kt` carries

  ```text
  fun Str.isEmpty(): Bool {
      return this.size() == 0
  }
  ```

  Three things the migration settled, in the order they came up:

  - **The receiver's spelling is not the pointer question it looks like.** The receiver of
    a `native` is the explicit first parameter (`this: Str`); a function *with* a body has
    to write the receiver type before the name, because only that form is marked a
    receiver - the explicit `this` is a plain parameter named `this`, so member syntax
    never reaches it (both rings; `xs.firstOrNone()` on the documented
    `fun firstOrNone<T>(this: List<T>)` of `specs/functions.md` emits the C++ member call
    `xs.firstOrNone()`), and the emitted signature is `firstOrNone(List<T>* self)` all the
    same. This is a *reported* spec/impl gap, not a decision: the parser sets
    `HasReceiver` only for the receiver-type form, while `native` extensions implement the
    explicit one in `addNativeExt`.
  - **`*Str` buys nothing here either.** `receiverParam` emits `T* self` for a *value*
    receiver and `Str* self` for a `*Str` one - the same parameter - so no receiver is ever
    copied and the pointer spelling only changes the language-visible type. (On a *plain*
    parameter it is the reverse of free: a `*Str` parameter takes an argument's *address*,
    so a literal or a temporary is first copied into a slot to have one - probed, see
    T70's `fmtStr`/the `*` argument rule.)
  - **The reachability rule had a hole, and the migration is what exposed it.** A prelude
    body was emitted only when the program also *named* the receiver's type
    (`reachesPreludeBody`), which is what keeps one container's `smToYield` machine out of a
    program that iterates another's. A program that never names `Str` - `"".isEmpty()` on a
    literal, or a receiver of an inferred local - therefore emitted *no* body and called
    one anyway: `error C3861: 'isEmpty': identifier not found` in the generated C++, in both
    rings. The rule now falls back to the call when *no* overload of the name is
    attributable (the whole group is emitted; an unused overload is dead but valid C++),
    and keeps the type test when one of them is - so `smToYield` behaves exactly as before.
    `stress/str-isempty` is the smallest program with the shape (a literal receiver, no
    `Str` type named anywhere); it does not compile before the fix.

  What it costs, measured rather than argued: the native was an `inline` header function,
  so the *call* is what changed, and the new body is a function the caller's own TU
  carries - **neutral** (`tools/_bench_ab.mjs`, 25 interleaved pairs, two passes:
  774.0/806.6 -> 769.6/805.2 and 784.3/805.4 -> 768.5/818.3 ms min/median; min is the
  readable number on this machine). The emitted code is not literally the same: a *field*
  receiver loses the read-back a native's value receiver needed
  (`simse_str_isEmpty((*_sm_base))` -> `isEmpty(_sm_base)`), and a *literal* receiver gains a
  materialized `Str` slot, which no call site in the ring has. `stress/rtl-simse` grew the
  receiver shapes (literal, local, `this.text` in a method of the program's own), and the
  ring's output is byte-identical - the two legs of the bench above emit the same file.
  Verified: five differentials byte-identical, T23's two-step bootstrap byte-identical,
  `simse_tests.exe` **56/56**, `bun tools/stress.js` **43/43** on both rings,
  `cppsrc/simse_bootstrap.cpp` regenerated with the fixed point byte for byte.

- **`Codegen.kt` split into three files, and the two resolution bugs the split exposed
  (T72).** The emitter was one 4,423-line file; it is 2,944 lines now, with the rest in
  two new files. Nothing about the emitted C++ was meant to change, and apart from the
  moves it does not - the fixed point still holds byte for byte - which is what makes the
  two findings below worth reading: both are *pre-existing*, and the second one is a
  user-visible diagnostic gap.

  - **`IlCodeGen.kt` (1,507 lines) is the instruction-list backend**: the IL's own types
    (`IlFrame`, `IlCrossing`, `IlScope`, `IlText`) and the 41 walks that spell a body
    (`dumpIl`, `ilFunctionFor`, `ilIntAt` ... `emitBodyAt`). They moved as **extension
    functions on `Emitter`** (`fun Emitter.ilEmitOps(...)`), because a class cannot be
    reopened in a second file and the emitted C++ is the same either way: an extension's
    receiver is the `Emitter* self` a class method's is, so the call sites and the symbols
    keep their spelling. The move itself was a script (find the region, dedent it, rewrite
    each signature); the whole-file diff is the proof of how mechanical it is.
  - **`CgStringTable.kt` is the literal pool as a class** (`StringTable`: `add`, `sort`,
    `count`, `entry`, `spelling`), leaving `Codegen.kt` only the *emission*
    (`emitStringTable`, which needs the emitter's output) - a literal site went from
    "has/get/format" to `this.literals.spelling(text)`, and `sortLiterals` became the
    `sort` step in `run`'s prelude. The `Cg` prefix is not decoration: the driver scans a
    module root in path order and the amalgamation defines types in that order, and
    `Emitter` embeds the table **by value**, so the file has to sort before `Codegen.kt`
    (the same rule as "a data-class field cannot name another package's type", now
    recorded for sibling files in `guide4ai.md`).
  - **`'"'` is spelled `'\"'`** in the three char literals whose value is `"`
    (`Scanner.kt` twice, `IlCodeGen.kt` once). Same `Char`, and Kotlin's highlighter stops
    reading `'"'` as an unterminated string. The emitted C++ keeps the *source spelling*
    of a char literal, so the bootstrap carries the change (three sites).

  **Finding 1: a plain call can bind to an extension by scan order.** `linear`'s
  `ilOpComment` calls **its own** `ilOperandAt(operands, 0)` (a plain function in
  `LinearForm.kt`). While the codegen helper was a *method* of `Emitter`, nothing else
  answered to that name - `functionPackage`/`hasPlainFunction` skip methods and natives -
  but as an extension in `codegen` (scanned *before* `linear`) it became a candidate for a
  bare call by name, shadowed the linear one, and the emitted call lost its receiver:
  `ns1_ilOperandAt(operands, 0)` where the host takes `(self, operands, index)` - correct
  C++ errors, and *no* Simse diagnostic. The fix here is the narrow one: the codegen
  helper is renamed `ilOpOperand` (with a comment saying why), so the two names cannot be
  confused. The rule itself - "a bare call prefers a plain function over an extension, and
  among two plain ones the scan order decides" - is still open, and it needs both rings'
  `functionPackage`/`hasPlainFunction` (or the sema) to agree on an order that is not the
  file path.

  **Finding 2: an undefined value name is not diagnosed.** `text.find(D)` with no `D` in
  scope transpiles **clean** in both rings (exit 0) and emits `simse_str_find(text, D)`, so
  the error the user sees is C++'s `'D': undeclared identifier` - a repro is three lines
  and needs no RTL. `sema` reports an unknown *type*, not an unknown *value*, and the
  emitter's `ExprName` arm falls through to the raw name (which the RTL's open surface
  needs for a *declared* name it has not seen); a name that resolves to nothing should be a
  positioned diagnostic instead. Still open.

  Verified: five differentials byte-identical (T22 now transpiles `Codegen.kt` with
  `--module-root cppsrc/codegen` and depends on the two new files; `SIMSE_ALL_MIRRORS`
  lists them), T23's two-step bootstrap byte-identical in release *and* debug,
  `simse_tests.exe` **58/58** (56 + one `PASS source` per new file), `bun tools/stress.js`
  **43/43** on both rings, `cppsrc/simse_bootstrap.cpp` regenerated and the fixed point
  byte for byte. `tools/_symdiff.mjs` is the check that a move lost nothing: it compares
  the emitted *symbol sets* of two amalgamations, which is how the symbol changes there
  (one rename, one deleted helper, seven new definitions) were confirmed to be the whole
  of it.
  No bench claim: the window was busy - the **unchanged** C++ ring measured
  292 -> 454 ms in the same session - so an A/B there would have measured the machine; the
  refactor's runtime effect is structural (one extra call per pooled literal). The rings
  also agree byte for byte on the compiler's own sources
  (`simse_transpile --root cppsrc` vs `simse.exe --root cppsrc`), which the fixture
  differentials do not cover.

- **The string-table join, measured before it was built (T73 - the design; the substrate
  landed as T75).** The proposal: sort the pool by **decoded length first, text second**
  (longest first, so shorter literals can be found inside longer ones), join the literals
  into one text (appending only the ones that are *not* already a substring of it), store
  one `(delta-from-previous-start, length)` pair per literal as `int16` when the joined text
  is under 32K (`int32` otherwise), and rebuild the N `Str`s at startup by slicing the
  joined text. It is a sound idea, and it is the right *representation* for the next step
  - but as specified it is a measured **regression**, because the entries stay owning
  `Str`s:

  - On the compiler's own table (526 literals, 6638 decoded bytes;
    `tools/_strtable_gain.mjs` over `cppsrc/simse_bootstrap.cpp`): **344 of 526** literals
    are appended as pieces (65%), the joined text is **5762** bytes (13% of the text is
    shared), the slices cost 2104 bytes.
  - `Str` is 32 bytes (4 len + 4 cap + 24 inline), so **today's table is ~19.7 KB**
    (526 objects, including 72 literals longer than the inline capacity that allocate
    2907 bytes at static-init time).
  - Join **+ materialized `Str` entries**: the same 526 objects *plus* the joined text and
    the slices = **~23.9 KB** static (4.2 KB over today's 19.7) and the same static-init
    allocations. The joined text is *added* to the per-literal copies instead of replacing
    them, which is the whole of it.
  - Join **+ `StrView` entries** (the `Str` objects go away, the entries point into the
    joined text): **~10 KB**, no static-init allocations, no copies. *That* is the version
    that pays - and it is the "a constant is a `StrView`" rule.

  The rule is bigger than it looks, and the probe says so. Today a literal *use* is free
  when it is read (`__sm_stringTable[k]` is a `const Str` glvalue that binds a `const Str&`
  parameter); the ring's own emitted C++ mentions a literal at **1396** sites against 526
  pooled entries. With view entries each `Str`-typed site needs a materialization unless
  the RTL grows view operations, so the change is: the literal's *type*, the conversion at
  every `Str` position (sema, extractor and emitter, both rings), view-taking operations
  for the hot ones (`==`, `+`, `find`, a `Str` parameter) and the spec text for all of it.
  And the interop the rule leans on is not there yet, silently: `view == text`,
  `text == view`, `takesStr(view)` and `val owned: Str = view` all **transpile clean**
  (exit 0) and then fail in C++ - `C2678` (binary `==`: no operator for `Str`/`StrView`),
  `C2664` (`StrView` to `Str`), `C2679` (`=` from `StrView`) - which is T72's Finding 2
  (an unresolved or mismatched name is emitted, not diagnosed) seen from the type side.

  So the join is held as the substrate for the view work rather than landed on its own (the
  substrate landed in **T75**, and this section is why: the entries had to stop being
  owning `Str`s first). If the view rule is wanted, the order is: the
  conversion/diagnostic work first (it is what makes a *wrong* site an error instead of a
  C++ error), then the join and the view entries together, then the spec.

  **The slice encoding: 7 bits a byte, high bit terminal (T73, refined).** The pairs are
  not fixed-width: each value is a run of bytes carrying 7 payload bits each, with the
  high bit *set on the last one* ("terminal"), so a length under 128 and a delta within
  ±63 are one byte each. A delta spends its first byte's bit 6 on the sign and only 6 bits
  on the value; continuation bytes carry 7 more bits, unsigned, low group first (`value |=
  (byte & 0x7F) << shift`, `shift` = 0 then 6, 13, 20...). "start 0, len 27" is `0x80`,
  `0x9B`; a next start of 27 is `0x9B` again; a reuse delta of -2 is `0x80 | 0x40 | 2` =
  `0xC2`. On the compiler's own 526 slices that is **~1.2 KB** instead of the 2104 the
  `int16` pairs need - real, and still not what decides the question:

  - join + materialized `Str` entries: the same 526 objects **plus** text and slices =
    **23.9 KB** (the encoding shaves 0.8 KB off the 24.7 above). A loss either way.
  - join + `StrView` entries: **13.4 KB**, no static-init allocations. The object is what
    matters, and the sizes are *measured* (`tools/probe_sizes.cpp`, compiled through
    `build.js --cpp`): `Str` is 32 bytes and `StrView` is **12** since T74 packed it (it was
    16), so at 20 000 literals it is 640 KB against 240 KB of views plus their text.
    "Compact for large applications" is the view, not the join.

  **Why it is landable without a language change (measured, `tools/_strtable_shapes.mjs`).**
  The 1393 literal uses in the ring's own emitted C++ fall into three groups:

  | shape | uses | under view entries |
  | --- | --- | --- |
  | `slot/field = literal` | 618 | unchanged: an owning position, a copy either way |
  | `... + literal` / `literal + ...` | 386 | one `operator+` overload (or a copy) |
  | `return literal;` | 119 | unchanged |
  | a generated function's argument | ~250 | unchanged where the parameter is `Str` by value |
  | a string native's argument | ~20 | an overload where the native *borrows* it |

  So the rule can be **materialize by default, keep the view only where the RTL has a
  borrowed overload** - no sema and no spec *type* change (a constant stays a `Str` to the
  language; the view is the representation, which is the "sorted out as spec" part). The
  allowlist starts at comparisons (`==`/`!=` both ways), `+` both ways, `println`'s `<<`,
  and the natives that borrow a `Str` argument (`simse_str_find`, `simse_str_startsWith`,
  `simse_str_split`, `simse_str_appendStr`, `simse_eprintln`); everything else materializes
  exactly as today, so a missed allowlist entry costs a copy, never correctness. The
  emitter's half is the join, the encoder and the table build; the RTL's half is the
  overloads and the walk (a `(delta, len)` reader that fills a `StrView[N]`); both rings
  must agree byte for byte, and the 1393 sites above are the shape list a differential
  would exercise.

- **`Span` and `StrView` follow the 4-byte packing rule (T74).** `specs/memory-model.md`
  says every type is aligned to at most 4 bytes, and `types.hpp` says the RTL wraps its own
  value containers in `SIMSE_PACK_PUSH` - but `Span` was the one value struct that was
  missed, so on this 64-bit host it took the pointer's 8-byte alignment: `sizeof(Span<Char>)`
  and `sizeof(StrView)` were **16**, with a 4-byte hole in every view the scanner, the
  parser and the RTL pass around. Measured (`tools/probe_sizes.cpp`, a C++ probe compiled
  through `build.js --cpp`): **16 -> 12** for both, `List<StrView>` 72 -> 56, while `Str`
  stays 32 and the already-packed `AstXmlNode`/`AstNodeAttribute` are untouched at 176/36.
  `xml.hpp`'s `XmlNode`/`Attribute` (312/64) keep host alignment - they are user-program
  types and a separate call.

  The emitted C++ is **byte-identical** (a layout is not printed), so no golden moved and
  `cppsrc/simse_bootstrap.cpp` did not change; the win is in the compiler's own data and in
  any program that walks spans. Verified: five differentials and T23 in release **and**
  debug, `simse_tests.exe` **58/58**, `bun tools/stress.js` **43/43** on both rings, and
  `bun tools/bootstrap.js`'s fixed point byte for byte. The two self-transpile timings moved
  in the same direction (ring best 837 -> 761 ms, and the hand-written ring, which shares the
  header, 292 -> 279 ms) - indicative, not a controlled A/B, since the box also went quiet
  in between.

  It also makes the string-table proposal cheaper: a `StrView[526]` table is 6312 bytes
  rather than 8416, so T73's design totals **13.4 KB** (6312 of views + 5763 of joined text
  + 1301 of varint slices) against today's ~24.3 KB - **~45%** off the table - and the 72
  static-init allocations become none. T75 landed the views; the packed indexes and the
  substring sharing are what is still open.

- **The compiler's string table is a pool with two run-length encoded indexes, and its
  entries are views (T75).** T73 was held because the entries stayed owning `Str`s; the
  representation was landed as soon as they stopped being. What the emitter writes now:

  ```cpp
  // The program's string literals: one pool, and two run-length encoded index
  // series (offsets as deltas, then lengths), each as what to subtract from the
  // previous value; strtable.hpp has the stream format.
  static const Int __sm_stringCount = 541;
  static const char __sm_stringPool[] =
      "usage: simse_transpile <input.kt>..." "yield: a `this` parameter cannot be a field; ..." ...;
  static const Int16 __sm_stringStarts[] = {541,5,0,-142,40,5,17,1,2,2,4,6,...};
  static const Int16 __sm_stringLens[] = {541,4,-142,40,5,17,1,2,2,4,6,...};
  static_assert(sizeof(__sm_stringPool) - 1 == 7355,
                "the string pool and its length index disagree");
  static StrView __sm_stringTable[__sm_stringCount];
  static struct __SmStringTableInitType {  // expands the index, fills the views,
      __SmStringTableInitType() {           // drops the expansion - no heap
          Int starts[__sm_stringCount];
          Int lens[__sm_stringCount];
          simse_strTableExpand(__sm_stringStarts, starts, __sm_stringCount);
          simse_strTableExpand(__sm_stringLens, lens, __sm_stringCount);
          simse_strTableDecode(__sm_stringPool, starts, lens, __sm_stringTable,
              __sm_stringCount);
      }
  } __sm_stringTableInit;
  ```

  `simse_strTableExpand`/`simse_strTableDecode` (`cppsrc/rtl/strtable.hpp`) expand the two
  streams into the stack arrays and walk them into the views; `StrView` grew a `toString()`
  member (`strview.hpp`) because the *site* asks for the owned value. Six deliberate
  choices:

  - **Both series are "what to subtract from the previous value"**, with an implicit 0
    before the first entry: `value[i] = value[i-1] - x[i]`, rebuilt in an `Int` accumulator,
    so an element is only ever a *difference* and the pool's size is the one large number.
    Longest-first order makes the length series descend slowly, and on this table **85% of
    the differences are 0** (equal-length literals are adjacent), the largest magnitude is
    the longest literal's 142.
  - **Each series is run-length encoded**: its length, then alternating blocks of
    non-repeating values (a count, then the values) and of runs (a count, then `times,
    value` pairs), until the length is filled. That is what the zeros collapse to: on this
    table 541 differences, 119 runs, longest run 37, and a stream of **256/257 numbers**
    (513 numbers, **1026 B**) against 1082 B per series without the pass - 47%. A single
    value is written once whichever block it lands in, so a block costs one count and no
    more: on an all-distinct series that is `N + 3` numbers against `2N` for `(times, value)`
    pairs, and on this run-heavy one 257 against 239 - the trade the format makes.
  - **The element is `Int16` when every number in the stream fits**, which the emitter
    checks (it encodes the stream, takes the largest magnitude and widens to `Int`
    otherwise) - it can, because it computes every number. 2 bytes a number instead of 4 is
    what lets each index be a single source line.
  - **The lengths are numbers the emitter computed**, not `(Int) sizeof(<literal>) - 1`
    expressions: with `sizeof` the length is a symbol in the generated file that the emitter
    cannot index on, so the second series could not be written at all, and neither the
    encoding nor the width could be chosen.
  - **The pool is the literal texts again, adjacent**, so the C++ compiler decodes the
    escapes of the emitted program and the emitter's `literalByteLength`
    (`cgLiteralByteLength` in `CgStringTable.kt`) only has to agree about how many bytes an
    escape *costs*. The `static_assert` on the pool's `sizeof` is that agreement, checked by
    the program's own build: an escape counted differently shifts the total and stops the
    build instead of silently shifting every literal after it. T23 pins it from the other
    side - the stage-1 compiler reads its own 541 literals through this table and must
    regenerate its source byte for byte. The pool is one source line while it fits, wrapped
    at 60 000 characters - short of the standard's 65 536-character logical-line limit.
  - **The sort is longest first, then text** (`val` < `var`, `vars` < `val`), as T73
    specified - the order the difference series and, later, text sharing both want.

  **`starts` is `lens` shifted while no two literals share text.** The offset increment of
  entry `i` is entry `i-1`'s length, so the two streams expand to the same numbers with a
  leading 0 (`tools/_strtable_runs.mjs` prints the check): two streams for one series of
  information. Dropping `starts` and advancing the offset by the length the decoder just
  rebuilt is a one-line change that halves the index; the stream returns when substring
  sharing lands, which is what makes the increment independent of the previous length.

  Measured on the compiler's own source set (541 entries, a 7355-byte pool - the count grew
  526 -> 537 -> 541 as the emission text itself grew): **~19.7 KB of table before**
  (526 `Str` objects, 16.4 KB static plus 2.9 KB allocated at static-init time for the 72
  literals over the inline capacity) against **~14.5 KB now** (pool 7355 + 1026 B of
  run-length encoded index + 6492 bytes of views), with **no startup allocation** and the
  index's 4.3 KB expansion living on the stack only while the initializer runs. A table is
  not compared across source revisions, so the number that means something is the per-entry
  cost (32 bytes against 12 + the text) and the startup allocations (72 against 0); dropping
  the redundant `starts` takes the index to ~513 B.

  **The sites use the view, and the interop lives in `strview.hpp`.** A literal site reads
  its entry as it stands, so **a comparison or a `+` builds no `Str`**: `str == literal`,
  `literal == literal`, `literal + text` and `println(literal)` have direct overloads over
  `StrView` (`==`/`!=`/`<`/`<=`/`>`/`>=` and `+` in all three pairings, plus an
  `std::ostream` writer), and everything else - a slot, a `return`, a by-value parameter, a
  `const Str&` argument, a list pack - reaches the converting constructor
  (`SmString(const StrView&)`: declared in `smstring.hpp`, which cannot see `StrView`,
  defined in `strview.hpp`, which includes it) and materializes exactly the copy it
  materialized before. The *mixed* overload pair is load-bearing: without
  `operator==(const Str&, StrView)` and its mirror, `str == literal` is **ambiguous** (one
  candidate would convert the left operand, another the right), because `const char* -> Str`
  and `StrView -> Str` both exist.

  This is what recovered the cost of the first cut, which asked for the owned `Str` at
  every mention: measured with `tools/_bench_ab.mjs`, 15 interleaved runs of the
  self-transpile against the previous published bootstrap, same flags, the `toString()` cut
  was **~7.5-9% slower** (**838.3/907.0 -> 918.1/962.4 ms**) and the view sites are **~0-2%**
  - parity with the `Str` table (**804.4/827.7 -> 816.6/837.0** and **830.7/865.3 ->
  831.9/883.2 ms** in two windows; the box's spread is wide, so read the ratio). Parity is
  the honest expectation: the old table's read sites were construction-free too, so what the
  view form buys is the memory, the startup allocations, and the `+` sites that used to copy
  a long entry before appending. What still converts rather than borrows is a `const Str&`
  argument to a native (`simse_str_find`, `startsWith`, `split`, `appendStr`, `eprintln` -
  ~20 sites in the ring's own emission), the last entry of T73's allowlist.

  **The binary is smaller too.** `static const Str __sm_stringTable[526] = {"a", "b", ...}` was
  **526 dynamic initializers** - one `SmString(const char*)` construction per entry, inlined
  one after another before `main`. The pool plus the two loops that expand and decode the
  index replace all of that with one small function, and the entries themselves shrink.
  Measured on the compiler's own release binary (`tools/_pe_sections.mjs` reads the PE
  section table; the two binaries are the published bootstrap at HEAD against the current
  one, same flags):

  | section | before | after | delta |
  | --- | --- | --- | --- |
  | `.text` | 1 969 196 | 1 949 308 | **-19 888** |
  | `.rdata` | 238 072 | 235 560 | -2 512 |
  | `.data` | 20 408 | 10 072 | **-10 336** |
  | `.pdata` | 49 232 | 46 800 | -2 432 |
  | file | 2 279 424 | 2 237 952 | **-41 472** |

  The `.text` win is the surprise and it is the bigger half: 526 initializer calls became
  two loops, and that is *net* of the encoder the emitter gained. The `.data` win is exactly
  the 16 832-byte `Str[526]` against the 6492-byte `StrView[541]` (16 832 - 6492 = 10 340,
  against 10 336 aligned); `.rdata` shrinks because the literals used to exist twice, once as
  the initializer's text and once in the built entry. The `.pdata` shrink follows the
  initializer code.

  What T73's spec still owes: dropping the redundant `starts` (the difference encoding makes
  it a copy of `lens` until sharing lands), substring sharing (it needs the emitter to decode
  escape *values*, not just counts, and it is what makes `starts` independent of `lens`), and
  the borrowed-argument overloads above.

  Verified: five differentials and T23 in release **and** debug, `simse_tests.exe`
  **58/58**, `bun tools/stress.js` **44/44** on both rings (the new
  `stress/string-escapes` pins the escape counting against the pool), and
  `bun tools/bootstrap.js`'s fixed point byte for byte. 15 byte-compared goldens moved on
  purpose (12 `tests/golden/*.cpp.expected`, `stress/flat-blocks/expected.cpp`); `hello`
  and `receiver-shapes` carry no literals, so their `expected.cpp` did not.

- **A profile of the self-transpile says the ring's gap is *when* a node's attributes are
  read, and `CgFn` carries them now (T76).** The user profiled the Release binary:
  `emitFunctions` **91.98%**, `emitBodyAt` 58.81% (the whole of its
  `emitIlBodyText` -> `ilEmitOpsChecked` -> `ilEmitOps` chain, 41%/41%/39%), and
  **`ns2_xmlAttr` 37.14%** with the attribute-iteration machine under it
  (`List_smToYieldPtr_yieldable<AstNodeAttribute>::next`, 26.93%) - i.e. essentially *all*
  of the IL emit loop's time was the compiler's most-called helper. Two readings of that
  are possible and only one is right:

  - *The loop is slow.* It is not: rewriting `xmlAttr` as an index walk over a borrowed
    attribute list (no `Opt<*T>` per element, no machine) measured **neutral** - 15
    interleaved runs of the self-transpile, `762.9/777.3` against `762.3/772.4` ms - so the
    rewrite was reverted (and the pointer-`for` stays the ring's style, `guide4ai.md`).
  - *The call count is absurd*, which is the answer. Where the calls came from was not
    `expr` (a handful per emitted node) but the **lookup walks**: `findFunction`,
    `findReceiverFnByName`, `findExtensionFn`, `memberCallReturn`, `functionPackage`,
    `reachesPreludeBody` and `preludeReceiverNamed` scan *every* collected function and read
    `xmlAttr(fn.decl, IsNative)` / `xmlAttr(fn.decl, Name)` / `xmlAttr(fn.decl, HasBody)`
    per candidate - for every call site. The hand-written ring compares `decl->name` /
    `decl->isNative` / `decl->hasBody` **fields** there, because its `ast::Decl` has them;
    the Simse ring's node is a uniform attribute list, so its port turned each field read
    into a fresh scan. That is the ring gap in one line.

  `CgFn` now carries `name`, `isNative` and `hasBody`, filled once in `addFunction` (the one
  place a `CgFn` is built), and every one of those walks compares fields. Measured with
  `tools/_bench_ab.mjs`, 15 interleaved runs of the self-transpile, the published bootstrap
  at HEAD against the new one, same flags: **800.4/814.3 -> 762.9/777.3 ms** min/median -
  which is **~4.5% faster than the old `Str`-table compiler** and **~7% faster than the
  state before this change** (`816.6/837.0`), so the whole string-table work plus this is a
  net speedup and not a wash. `bun tools/bootstrap.js` agrees: the self-transpile is
  790/794 ms against the hand-written ring's 290/303, a ratio of **2.7x** (it was 2.8-3.3x
  in this session's earlier windows), with the fixed point still byte for byte.

  **The `for` protocol is `current` + `advance()` + `value()` now, and it was landed as an
  artifact change (T77).** Every machine used to emit *both* halves of the protocol -
  `next() -> Opt<T>` and `advance(value: *T) -> Bool` - while the `for` lowering used
  `next()` for all four forms, so in the compiler's own emitted C++ **`.advance(` appeared 0
  times while `.next()` appeared 124**, and each `advance` body (~50 emitted lines per
  instantiation) was compiled and never called. That is gone: the machine holds what it
  yielded in a `current` field, `advance()` steps it and answers whether there was a value,
  and `value()` reads `current` out as the *element* type - so the loop variable is still a
  typed binding and the emitter needed **no new IL op** (`machine.value()` is a plain
  `Call`). One protocol for all four `for` forms, no `Opt` in the artifact, no dead method
  per instantiation.

  - **It is not a speedup, and that is measured rather than argued.** This was the earlier
    T76 reading: replacing the shape is not where the time goes.
    `tools/_loop_protocol.cpp` prices the shapes directly (a search body with a runtime
    key, the shape `xmlAttr` has, on a 64-element `List<Attr>` - `AstNodeAttribute`-sized -
    over 200 000 rounds, `/O2 /Ob3` through `build.js --cpp`):

    | shape | ns/element |
    | --- | --- |
    | `next()` + `Opt<*T>` (what the emitter used to write) | 0.51 |
    | `advance(*T)` (emitted, never called) | 0.46-0.51 |
    | `current` + `advance()` (what the emitter writes now) | 0.46-0.51 |
    | index walk, no machine (baseline) | 0.32-0.41 |

    The three protocol shapes are **identical** - MSVC inlines the machine and removes the
    `Opt` - and dropping the machine entirely buys ~0.15 ns an element, which over the
    compiler's few million attribute visits is under a millisecond. Two controlled A/Bs of
    the real compiler with only `xmlAttr`'s body differing agreed (**770.6/781.7** against
    **771.6/786.8** ms, and **764.4/772.6** against **762.3/772.4**, 15 interleaved runs
    each), so the profiler's `next` figure was *memory-attributed*: the innermost frame of a
    cache-walking loop gets the samples. The change was worth making for the artifact and
    for the one protocol; it is not sold as a win.
  - **A body may name what the machine names.** Collecting the fields turned up a latent
    collision: `branch` and `current` were **silently skipped** (a local of either name
    aliased the protocol field - its own initializer wrote the branch - which the
    `machineReserved` list in `emitYieldable` was papering over), and `value`/`advance`
    would have been a C++ redefinition (`Int value;` next to `Int value()`), which is
    exactly what `stress/yield`'s `var value: Int` hit. A body name that collides is now
    emitted under a mangled one (`linear::yieldFieldName`, `_sm_f_value`), and the rewrite
    and the factory's `machine.x = x` go through the same rule; `stress/yield` names all
    four (`branch`, `current`, `advance`, `value`).
  - Verified: five differentials and T23 in release (fixed point byte for byte),
    `simse_tests.exe` **58/58** in both configurations, `bun tools/stress.js` **44/44** on
    both rings, and the two rings byte-identical on `stress/yield`. The goldens moved on
    purpose (`tests/golden/{for_iteration,lambda_scopes,program_expr,when}.cpp.expected` and
    the two `.astxml.expected` of the loops), and `cppsrc/simse_bootstrap.cpp` was refreshed
    (`bun tools/bootstrap.js`: fixed point byte for byte). The IL hoisting below is verified
    the strict way: the compiler before and after it transpile `cppsrc` to **byte-identical
    C++** (`cmp` on the two outputs), which is what makes it a backend-internal change.

  **The IL backend's per-declaration rescans, hoisted - and that is neutral too (T77b).**
  The one place the backend did work proportional to the *body* rather than to the
  instruction was the block a declaration needs when a jump crosses it: `ilJumpCrossing`
  rebuilt a label->position **`Dictionary`** and rescanned the body for *every*
  declaration - and the emit loop asked again (a second full call) when the block actually
  opened. Three changes, none of which can change the output (it is byte-identical):

  - the label positions are one `List<Int>` per body (`ilLabelPositions`) instead of a hash
    map per declaration, so a jump's target is an array read;
  - the crossing computed for a block is *kept* with the block (`blockEnd` holds the
    `IlCrossing`, not just its `end`), so the walk runs once per block instead of twice;
  - a `declareOp` dictionary the Simse ring allocated per body and never read is gone (the
    C++ ring never had it).

  Measured with `tools/_bench_ab.mjs` on the self-transpile, 15 interleaved runs:
  **739.3/756.0** before against **751.2/759.2** after in one window, and **1225.9/1247.1**
  against **1229.7/1240.9** in another (the machine was in two very different states; both
  legs sat in the same one) - i.e. **neutral**, and the honest reading is that these
  declarations are rare enough that the walk was never the cost. The other half of the
  same attempt was **reverted**: memoizing `ilFolded` per slot (`three hash probes and a
  closure-table lookup per operand read`) measured neutral in the self-hosted compiler
  *and*, isolated, in the C++ ring (**250.5/260.2** with the memo against **251.1/261.2**
  without, 15 runs), so it was dropped rather than kept as unearned state. The `labelPos`
  array and the single crossing walk stayed: they are strictly less work with no new state,
  not a speedup.

  **The measurement itself needed a control.** A null A/B - the *same* binary on both
  legs - reads **768.9/797.2** against **781.6/804.1** ms, so leg B carries a ~1% bias of
  its own, and a window with background load showed medians 60% above the clean ones
  (1245 ms against 780). Every number above is from an interleaved run in a quiet window,
  and the `min` is the statistic to trust.

  **Where the time actually is.** After this the profile reads `emitBodyAt` 47.29%
  (`emitIlBodyText` 26.55% + `ilExtractUnit` 20.60%) with `xmlAttr` at 25.53% - and, since
  the walk's shape is priced at nothing, that 25% is the **data** it reads: the emitter's
  operand -> `AstXmlNode` -> text round trip (`ilValueText`/`expr`, 19.8% each) and the
  extractor's per-node reads (`ilExtractUnit` -> `run` -> `stmts` -> `statement`, 20.6%,
  20.1%, 19.9%, 19.8%). Cutting it means **reading fewer attributes per node**, not a
  cheaper loop: a borrowed `xmlChild` (it returns a 176-byte `AstXmlNode` *copy* today,
  attributes included), and a fast path that spells the common operand shapes without
  building a node and reading it back. The two ring implementations are the evidence that
  this is the whole difference: the hand-written ring reads *typed struct fields*
  (`decl->name`, `expr->kind`) while this one scans attribute lists, and the ring ratio is
  **2.85x** (`bun tools/bootstrap.js`: 780/813 ms against 274/277 ms).

- **The instrument: `--profile`, one RAII timer per emitted body (T78).** The two
  attempts above were both aimed by *reading* the profile and guessing, and both measured
  neutral - so the next step was the tool, not a hunch. `--profile` makes the emitted
  program measure itself: the runtime goes into the prologue, every emitted body gets
  `auto __smProfile = profileApp.measure("ns1_emitFunction");` as its first statement
  (destructor banks the elapsed microseconds, so the total is the body's whole run,
  nested calls included), and a static's destructor prints the table on stderr when the
  program leaves `main` - whichever `return` it took. Each row also carries the **call
  count**, which is the number a sampling profile cannot give you and the one that
  separates "called once, expensive" from "called a million times, cheap".

  It lives in `cppsrc/profiling/` (`Profiling.kt` for the Simse ring, `Profiling.{h,cpp}`
  for the C++ ring, `impl_specs/profiling.md`): the flag, the runtime text, and the three
  one-line hooks (`preludeText`, `emitBodyAt`, `emitClosureClass`). Two details are
  load-bearing and are commented where they sit: the timer is the body's *first*
  statement (nothing precedes it, so no `goto` can cross into its scope - the `C2362` rule
  `ilJumpCrossing` exists for), and the timer name is the body's emitted *symbol*, so a
  row names the function and not its source line. `simse_nowMicros`
  (`cppsrc/rtl/timeops.hpp` / `Native.cpp` / the `nowMicros()` surface) is the clock.

  Enable it with `bun build.js --release --profile` (the flag is passed through to the
  transpile step) or `simse_transpile --profile` for any program. Off, every hook returns
  the empty string and the emitted file is **byte-identical** to before the flag existed -
  which is why the whole suite stayed green: `simse_tests.exe` **59/59** in release and
  debug, `bun tools/stress.js` **44/44** on both rings, T22/T23 green,
  `bun tools/bootstrap.js`'s fixed point byte for byte, and the two rings byte-identical
  with the flag *on* as well (`cmp` on `stress/yield` transpiled both ways). The published
  bootstrap stays clean: a profiled compiler is a local artifact, never published.

  Not evaluated yet - the flag was added as the *instrument*, and the first reading of its
  numbers is the next step. What it already says about itself: a profiled compiler reports
  **401 emitted bodies** on `--root cppsrc`, `main` first.

- **The first reading of the instrument, and the fix it chose (T78 continued).** The
  profiled compiler reports **526 emitted bodies** on `--root cppsrc`, and the shape
  agrees with the sampling profile: `main` -> `emitProgram` (88%) -> `emitFunction` (1214
  calls = 607 functions x 2 passes), and inside one function the lowering + hoist/simplify
  + type pass is **2.22 s (53%)** against `emitBodyAt` **1.90 s (46%)**
  (`ilExtractUnit` 0.99 / `emitIlBodyText` 0.91). That names the 44% of `emitFunction` the
  sampling profile could not: the linear lowering, the hoist/simplify passes and the type
  pass - not the IL backend.

  Two corrections had to be made to the tool itself first, both from its own table:
  measuring the yield machinery's `advance`/`value` (44.7M / 44.4M entries, ~22% of the
  run) made the instrument its own subject, so `emitMachine` now passes `measure = false`;
  and the entry was two `Dictionary` lookups (a `times` and a `calls`), now one row
  struct. After that the instrumented run is 4.73 s against a clean 0.75 s.

  The widest row was the *label scan*: `linStmtJumpsTo` 1.85M calls, reached once per
  label through `linJumpsTo`, so `labelPass` scanned the whole sequence (with a `Str`
  compare per statement) once for **every label**. Collecting the jump targets in one walk
  (`linJumpTargets` / `linCollectJumpTargets`, both rings) cut the instrumented run
  **4.73 s -> 3.64 s** and the clean release run **734.7/754.5 -> 720.6/743.7 ms**
  (`tools/_bench_ab.mjs`, 15 interleaved runs, quiet window) - an order of magnitude less
  than the instrumented number, and that gap is the lesson: the profiled build has no
  inlining and pays ~40 ns per entry, so it prices *calls*, not the optimizer's code.
  Verified byte-identical on eight fixtures, every `.cpp` golden, T22/T23, `stress.js`
  44/44 on both rings and `bootstrap.js`'s fixed point.

- **Resources: `_res.md` files, embedded in the program's string table (T79).** Text that
  is not code - a template, a prompt, help text, the profiler's own generated C++ - now
  lives in files the compiler reads and embeds in the pool the literals already use, so a
  resource is a `StrView` over that pool at runtime and there is no FFI, no data directory
  and no second copy of the text anywhere (`specs/resources.md`). The parser is a new
  module, both rings (`cppsrc/resources/Resources.kt`, `Resources.{h,cpp}`), and its
  differential is `resources_diff` (a `_res.md` fixture set dumped as one C++-quoted
  `key<TAB>value` line per entry, plus the joined list): hand-written and transpiled
  byte-identical. The end-to-end case is `stress/resources`, which prints every shape the
  format has.

  Four decisions the build forced, worth writing down because the draft did not have
  them. **An entry is a line whose first `:` has something before it** - "a line whose
  trimmed form ends with `:`" cannot express `Key: value`, and with `:` as the separator
  that unavoidably makes an entry of prose containing one; the spec says so now. **A `\r`
  before a newline is not part of a fence line**, so a value does not depend on the
  file's line endings (the scanner's own rule). **The driver hands codegen the *quoted*
  literals, not the entries**: `List<ResourceEntry>` as a field of the emitted `Emitter`
  struct would be declared *after* the resources package's own type (the file-order rule
  `CgStringTable.kt` documents), and quoting once keeps it off both walks - the pool is a
  pool of literal texts, so that is also the honest shape. **`memberReturn` needed no
  change**: declaring the three operations as ordinary prelude natives on the type
  (`fun get(this: Resources, key: Str): StrView`) is what gives the checker their
  signatures, and only the *emitter* needed the static form, spelled beside
  `Enum.fromInt` (`Resources::get(...)`, the C++ `struct Resources` carrying the statics).

  Verified: `simse_tests.exe` **61/61** (the whole `cppsrc` set still analyzes, and one
  more source file), `bun tools/stress.js` **45/45** on the self-hosted ring and 45/45 on
  `simse_transpile.exe`, the new case's emitted C++ byte-identical between the two rings,
  T22/T23 green, and `bun tools/bootstrap.js`'s fixed point byte for byte. A program with
  no `_res.md` is unchanged: every other case and every `.cpp` golden passed untouched.
  A stale compiler is worth knowing about here - the *prelude* is read from disk at run
  time, so an old `simse.exe` picks up `rtl/resources.kt` and spells the calls through the
  natives it names (`simse_resources_get(Resources, ...)`); rebuild with
  `bun build.js --release` before judging a new RTL declaration.

- **The hand-written compiler ring is gone: Simse is the only implementation (T80).** The
  port grew a second, hand-written compiler to develop against, and by the end it was a
  liability rather than a reference: every semantic change had to be made twice, the
  differentials needed a C++-only `ast::Decl` layer to exist at all, and the ring's own
  fixture/golden harness was the only thing pinning behavior the stress corpus already
  pins end to end. What was *not* removable is the ground the compiler stands on, and that
  is now the whole of the hand-written C++: the runtime headers (`cppsrc/rtl/*.hpp`), one
  translation unit of `native(...)` symbols (`cppsrc/rtl/native.cpp`, the old
  `native/Native.cpp` with `common/common.cpp`'s two filesystem helpers folded in), and
  the published bootstrap.

  Deleted: `Compiler.{h,cpp}`, `ast/`, `lex/Scanner.{h,cpp}`, `skelparser/
  SkeletonParser.{h,cpp}`, `parser/Parser.{h,cpp}`, `sema/{Sema,TypeInfer}.{h,cpp}`,
  `linear/*.{h,cpp}`, `codegen/{Codegen.{h,cpp},TranspileMain.cpp}`,
  `profiling/Profiling.{h,cpp}`, `resources/Resources.{h,cpp}`, `native/`,
  `common/common.{h,cpp}`, the whole `tests/` tree (fixtures, goldens,
  `simse_tests.exe`, the ten differential drivers), `stage1/`, `CMakeLists.txt`,
  `simse.sln`/`simse.vcxproj`, and the `cmake-build-*` folders. `build.js` no longer links
  `simse_lib.lib`/`simse_native.lib` - it compiles the amalgamation and `native.cpp` in
  one `cl` invocation, which also means `SIMSE_STR_INLINE_CAPACITY`/`SIMSE_NO_PACK4`
  cannot disagree between them any more (`--define` reaches both). The MSBuild profiling
  project became `build.bat --release --pdb`; the CMake cache's architecture became
  `tools/msvc.mjs`'s `hostArch()`.

  What *replaces* the five differentials and the golden harness is one property, already
  the strongest thing the repo had: **the bootstrap fixed point**. The published
  `cppsrc/simse_bootstrap.cpp` compiles with `cl.exe` alone (plus `native.cpp`), and the
  compiler that comes out of it must transpile `cppsrc` back into that same file, byte for
  byte - `bun tools/bootstrap.js` checks that *and* compares the repo's own `./simse.exe`
  against it, which is what makes a stale compiler say so by name. On this machine
  (release, arm64): bootstrap compile **21.1 s**, self-transpile **1.18 s** for 17,905
  lines of Simse (1.38 MB / 45,757 lines of C++ out), both comparisons byte-identical.
  Verified after the deletion: `bun build.js --release` (including the no-`simse.exe`
  path, which compiles the bootstrap first), `bun tools/bootstrap.js` (both fixed-point
  checks), `bun tools/stress.js` **45/45**, and the refreshed bootstrap's own fixed point.

  Two smaller consequences worth recording. The compiler's CLI prefix and the generated
  file's banner said `simse_transpile` (the deleted binary's name); they say `simse` and
  "Generated by the Simse compiler" now, so the three `stress/*/expected.cpp` goldens
  moved with them. And the comments inside the remaining `.kt` sources still refer to the
  port's history ("the hand-written ring", "both rings") in places: they are the record of
  how each piece was ported, not a description of the tree.

- **Generators: attributes, `@SmGen`, `Sections`, and generated Simse (T26/T27).** A
  declaration can now name the thing that implements it: `@SmGen` is an attribute on a
  body-less method (`specs/attributes.md`), `@Identifier` is one scanner token, and the
  parser fills three AST attributes (`Attribute`/`Generator`/`GeneratorArgs`) - which is
  also what `native("sym")` lowers to (`@SmGen("cpp", "sym")`), so
  the two spellings are one declaration (`bun tools/smgen.js` asserts their amalgamations
  are byte-identical). Three generators exist: `cpp` (the C++ is in the headers, i.e.
  `native`), `res` (the C++ is a resource the *compiler* carries - `cppsrc/rtl/_res.md`,
  whose `spanOf` is where the RTL's `spanOf` moved out of `span.hpp`, and whose
  `spanOfEmpty` section pins the documented last-write-wins collision), and `kt` (the
  implementation is *Simse source* from `<section>:source`, which the driver parses and
  compiles with the program).

  The amalgamation's assembly is now a first-class object: `Sections`
  (`cppsrc/sourcegen/Sections.kt`, `cppsrc/codegen/CgSections.kt` when it landed) holds the
  named sections in render order
  (`includes -> forward -> types -> statics -> prototypes -> init -> bodies`), a
  generator's addition is a named item inside one of them (last write wins), and a new
  name appends at the end. Routing the emitter through it was verified the strict way -
  the compiler before and after it transpiles `cppsrc` to identical C++ - and that is why
  the emitter's own text stays a sequential buffer inside its section: its bytes cannot
  depend on the item dictionary.

  Generating *Simse* rather than C++ text is the interesting half: a C++-text generator
  cannot name a program type (`Point` is `ns1_Point`, an index over the sorted package
  set), while generated Simse goes through the same sema and codegen as the program -
  `stress/smgen-kt` declares `greeting` and the source in its `_res.md` implements it,
  the call site unchanged. What is *not* built yet is the step `@Json` needs: per-
  instantiation generation (the emitter discovering what was reached, choosing a mangled
  symbol, and the generator emitting the closure), which is T28.
- **The resources API is Simse now.** The lookup was the last part of the feature that
  had to be C++; `resources.hpp` is down to the storage and `install`, plus one accessor,
  `simse_resources_entries()`, that hands the table out as a `Span<ResourceEntry>`. Over
  that, `cppsrc/rtl/resources.kt` writes `Resources.entries/get/has/count` in the
  language - a size test and `StrView.startsWith` per entry, allocation-free, what the
  C++ did - and the static call `Resources.get(k)` now resolves through
  `Emitter.staticCallSymbol` to the prelude function's *symbol* (the name walk records
  the same symbol, which is what makes the prelude body reachable). Two findings from
  that move, both in `guide4ai.md`'s gotchas now: the emitter's type table is flat by
  name (the compiler's reader type had to become `ResourceItem` so the RTL's
  `ResourceEntry` could exist), and a *static* call's result has no inferred type, so a
  chained member needs a typed local.
- **Release builds default to whole-program optimization** (`/GL`, whose link-time codegen
  is LTCG; `--no-lto` opts out). Measured here: transpile 0.87 s vs 0.86 s with and
  without, compile+link 16.4 s vs 19.2 s - neutral on this machine, and now the default
  because run time is what it is for. `cl` ignores a bare `/LTCG` (D9002); linking `/GL`
  objects is what requests the LTCG pass.

  Verified after all of the above: `bun tools/stress.js` **55/55** (five `smgen-*` cases,
  five attribute diagnostics, the rest unchanged), `bun tools/smgen.js` 1/1,
  `bun tools/bootstrap.js` - the published `cppsrc/simse_bootstrap.cpp` refreshed and both
  fixed-point comparisons byte-identical - and `./simse.exe` reproducing `simse_out.cpp`
  (release+`/GL`: 0.86 s for the self-transpile).

- **The RTL's generated C++ is a resource now (T29).** `strtable.hpp`, `timeops.hpp`,
  `listops.hpp`, `dictops.hpp` and `strops.hpp` are gone: their text is the
  `strtable`/`timeops`/`listops`/`dictops`/`strops` sections of `cppsrc/rtl/_res.md`, and
  `rtl.kt`'s declarations reach it with `@SmGen("res", section, symbol)` instead of
  `native(...)` (no prelude string/list/dictionary operation is a `native` any more). The
  emitted assembly grew the
  sections that made that possible (`support`, `profile`, `strings`, `resources`,
  `forward`, then the emitter's own phases), and every block - a section's text, each
  generated item - renders with a blank line before it, so a resource's C++ reads as its
  own block in the amalgamation rather than running into the line above it.

  Three findings are worth keeping. **The lookup had to become "the tree's own `_res.md`
  first, the compiler's table second"** (`Emitter.resText`/`resHas`, the rule the `kt`
  generator already used): with only the compiler's baked table, a tree whose RTL text
  *is* the file on disk could not be built at all - the section the compiler needed was
  not in the compiler that was compiling it. **A `res` declaration has to carry its
  symbol in the attributes** (`NativeSymbol`, filled for `cpp` and `res` alike): `linear`'s
  `listOf<T>` list literal reads the symbol off the declaration without the emitter's
  tables, so a declaration that named it only as the attribute's argument stopped being a
  `Pack` and the emitter had no type for the call (the symptom was `unsupported type 'T'`
  while emitting a body). And **the generator pass runs after every body**, because the
  reachability rule that keeps a prelude generator out of programs that do not use it
  must also see what the emitter spelled itself: the entry point's argument list is built
  with `simse_list_append`, which has no call site in the source (`stress/main-args` pins
  it). A fourth came with `strops`: **a call records the symbol of the declaration it
  names** (`collectNames`), not just of a call on a type name - a program may name an RTL
  symbol directly (`native("simse_str_trim") fun trimmedText(...)`, `stress/smgen-native`),
  which linked while that C++ was a header and needs the symbol reach now that it is a
  section.

  The five headers were not one change but two: `strtable`/`timeops`/`listops`/`spanOf`
  first, then `dictops` and `strops` - and the second is what surfaced the fourth finding
  above. `List.contains`/`sort` were `native` "on purpose" in `rtl.kt` before (their C++ was
  in `dictops.hpp`, which the comment had got wrong); they are `res` declarations now, like
  everything else the prelude offers.

  A program's own `_res.md` can supply generated C++ for itself now, which
  `stress/smgen-res-program` pins. Verified: `bun tools/stress.js` **56/56**, the emitted
  C++ goldens re-captured (the decoder's text moved into every program's amalgamation -
  that is the cost of the header going away), `bun tools/smgen.js` 1/1, and the bootstrap
  fixed point holds byte for byte.

- **The generators are a package of their own now (T30).** `cppsrc/sourcegen/` holds
  `Sections.kt` (the sink, moved out of `codegen` verbatim), `GenTypes.kt` (the data a
  generator sees: `SourceGenContext`, `SourceGenTransform`, `FullCompiledState`, the
  `OnSourceGen` typealias), one file per generator (`CppGen.kt`, `ResGen.kt`, `KtGen.kt`),
  and `SourceGen.kt` - the manager, with `addSourceGen`/`makeSourceGens` built the way
  `lex/Scanner.kt` builds its token rules and the three entry points
  `sourceGenDeclare`/`sourceGenReparseSource`/`sourceGenEmit`. A generator is a lambda over
  `*SourceGenContext`; `codegen` keeps only the calling side.

  The point is the boundary: a generator is the one part of the compiler a *program's*
  author writes, so it may touch data (AST nodes, resources, the sink) and nothing else -
  not the parser, not the emitter, not the semantic pass - and cannot break when a compiler
  API changes. `Sections` left `codegen` for that reason: it is a data structure in the
  generators' package now, held as one static per compilation and handed out as
  `*Sections` (`sourceGenSink`), the same shape and for the same reason as the scanner's
  rule table.

  One trap found and paid for: **`*p` where `p: *Sections` reads through**, so calling
  `sourceGenEmit(*this.sections, ...)` materialized a *copy* of the assembly - the
  generators filled the copy while `render()` read the real sink, and every program came
  out with no generated text at all (`simse_dict_keys`: identifier not found). The pointer
  is passed, never dereferenced; `Sections.render`'s "text then items, each as its own
  block" is unchanged, which is why the refactor is byte-neutral.

  Verified the strict way, since the refactor should change no bytes: the refactored
  compiler and the previous one transpile `cppsrc` to *identical* C++ (compared directly,
  then `bun tools/bootstrap.js` after one `cppsrc/simse_bootstrap.cpp` refresh reports both
  fixed-point comparisons byte for byte), `bun tools/stress.js` **56/56** with no golden
  re-captured, `bun tools/smgen.js` 1/1.


- **The project file (`simse.md`), and generators that register themselves (T31's first
  half).** A root's `simse.md` names the modules a project is built from (`module: <dir>`,
  repeated) and a module's own `simse.md` may say `sourcegen: true`; the driver reads both
  before it scans anything (`manifestValues`/`driverExpandRoot`, `Driver.kt`), in the
  resource idiom minus its sections: prose lines carry no entry, an entry is `key: value`, a
  repeated key means "one more of these". **A root with a manifest is scanned as exactly the
  modules it names**, so a `stray.kt` beside the manifest is not scanned; a root without one
  is scanned whole, which is what `--root cppsrc` keeps doing - and what lets the compiler's
  own tree gain a manifest later without a second code path. A module that declares
  generators is a hard error naming it: the extension is not implemented, and a program that
  asked for a generator must not compile silently without it
  (`stress/manifest-modules`, `stress/diagnostic-manifest-sourcegen`).

  The generators' own half landed with it: they now **register themselves**. `sourceGenTable`
  has no initializer, and each generator's file ends in one line -
  `val cppGenRegistered: Bool = registerSourceGen("cpp", cppGen, true, true)` - whose
  file-level static initializer appends it to the table (`specs/statics.md`; the mechanism was
  probed before it was relied on: a static whose initializer calls a function that appends to
  another file's static-typed storage works). That is what a module's generator will do too,
  and it is order-independent in a way an assignment would not be: the initialization pass's
  order is unspecified, but "storage starts empty" is a guarantee and an append cannot lose a
  registration. The dispatcher looks a generator up by name, so the order the table ends up
  in is not observable either.

  Verified: the compiler builds itself, `bun tools/stress.js` **58/58** (the two new cases),
  `bun tools/smgen.js` 1/1, and the bootstrap fixed point holds after one refresh.

- **A resource section can be marked compile-only (`!`).** A `_res.md` section title that
  opens with `!` is *read and not carried*: the compiler reads its entries exactly as before -
  a generator looks its keys up, the emitter finds its text and emits it as code - and the
  program it builds does not store them. The `!` is trimmed off the name with the whitespace
  around it, so a lookup by spelling cannot tell whether a section was marked, and the
  presence of the entry differs only in `ResourceItem.compileOnly`. Two views of the one list
  the driver read do the rest: `resEntriesFlat(resources)` is what the emitter *looks up*
  (every entry) and `resEntriesFlat(resStoredEntries(resources))` is what it *pools* - the
  program's string table and its `Resources` table are built from the second, so a marked
  section is absent from both.

  The reason is code that lives in a `.md` file: a `kt` section's Simse source, a `res`
  section's C++, are compiled into the program, and storing the text as well duplicated the
  same bytes (in the string pool *and* in the resource table). A resource that is *data* is
  left unmarked, because the program is exactly what should read it. The effect is visible in
  the corpus's goldens: `stress/smgen-kt`'s string pool drops from 403 bytes to 12 and its
  resource table disappears, `stress/smgen-res-program` stops emitting a string table at all,
  and `stress/resources-compileonly` pins all four facts at once (the generated function works
  from a marked section, the program does not carry that key, it does carry the unmarked
  section beside it, and the count is one).

  One file was left unmarked at that point, `cppsrc/rtl/_res.md`, and the reason was the one
  trap this feature had: a `_res.md` under `cppsrc` *was* the compiler's own run-time resource
  table, which is how a program that carries no section of its own received the RTL's C++
  (`Resources.get` was the second half of the generator lookup). Marking those sections would
  have taken the text out of the compiler, and no program would have got its RTL code. That
  was fixed one step later (the entry below) by moving the second half of the lookup to the
  file itself.

  Verified: `bun tools/stress.js` **59/59** (one new case, two goldens re-captured - and the
  diffs are the point: the text and the table are gone), `bun tools/smgen.js` 1/1, and the
  bootstrap fixed point holds byte for byte after one refresh.

- **The compiler stops carrying its own resources (`cppsrc/rtl/_res.md` is `!` throughout).**
  The pool the compiler built into itself was 33,446 bytes, and 23,034 of them were the text of
  `cppsrc/rtl/_res.md` - text the compiler *also* had as code, since the `res` generator emits
  it. The marker from the entry above could not simply be applied to that file: the second half
  of the generator lookup *was* the compiler's own pooled table (`Resources.get`), read at run
  time to serve a program that carries no `_res.md` of its own - so marking the file would have
  taken the RTL's C++ out of the compiler, and no program would have received it.

  The fix is to move that second half to the file. The driver now loads the resources **beside
  the compiler's prelude** (`resLoad(driverResourceRoots(resolvedPrelude))`), exactly as it loads
  the prelude's `.kt` files, and `sourcegen`'s state carries two lists - the tree's resources and
  the compiler's own (`FullCompiledState.compilerResources`) - with `sourceGenResHas`/
  `sourceGenResText` and `resGenAlways` reading the second instead of the RTL's `Resources` API.
  The RTL's `Resources` type is a *program-facing* API now; the compiler reads files, which it
  already had to do for the prelude. Every section of `cppsrc/rtl/_res.md` is marked `!`, so
  nothing of it is pooled and the compiler's own `Resources` table is empty.

  Verified: pool 33,446 → 11,051 bytes, and the published bootstrap 1,572,537 → 1,548,652 bytes
  (one refresh). `bun tools/stress.js` **60/60** - every program still receives the RTL's code,
  and the disk read is exercised by nearly every case, since almost none carries a `_res.md` of
  its own - `bun tools/smgen.js` 1/1, and the bootstrap fixed point holds byte for byte.

- **The skeleton parser is gone.** `cppsrc/skelparser/SkeletonParser.kt` (140 lines) was the
  first component ported from the hand-written ring - the pilot that proved a Simse source file
  could be transpiled, compiled and differentially checked against its C++ original
  (`skel_diff`, byte-identical tree dumps) - and once `parser/Parser.kt` parsed tokens straight
  into the AST it had no caller at all: nothing in `cppsrc` named `parseSkeleton`, `foldBack`,
  `matchingOpenToken`, `blockTypeForOpenToken`, `isClosingToken`, `SkeletonNode` or
  `SkeletonType`, the only link left was an unused `import skelparser` in the driver, and the C++
  original it mirrored (`SkeletonParser.h`/`.cpp`) had already been deleted. It was compiled
  into every build regardless (the tree is scanned whole), together with the string literals of
  its token tests. Deleted, with the import; the pilot's history is this file's own log and the
  porting story in guide4ai.md.

  Worth knowing as a consequence: the only `&T`/`PList` sites in the compiler's own source were
  in that file (`&List<SkeletonNode>` x11), so `cppsrc` now contains **no counted reference at
  all** - the emitted `Ref<T>` spelling and the `SmRef`/`std::shared_ptr` switch are exercised by
  the RTL's `Array`/`PList` and by the corpus (`stress/counted-reference`,
  `stress/pack-args`, `stress/language-tour`) rather than by the compiler's own ring.

- **A `*` marker for binary resources, markers on individual keys, and the output framed
  honestly.** The resource format grew a second marker: `*` means the value as written is
  **lower-case hex that stands for the bytes**, decoded once on the way in
  (`resValueText` -> `simse_resHexToBytes`), so nothing downstream knows it was hex - a
  resource is a `Str` (pointer + length) whether or not it holds a `\0` in the middle. Both
  markers are now read by one function (`resMarkedName`) from a **section title or an entry's
  key**, may be written together in either order, and are consumed with the surrounding
  whitespace; there is no way to unset one, so a marked section marks every entry under it and
  a key's markers only add. `ResourceItem` carries the two flags (`compileOnly`, `binary`).

  Three consequences worth remembering. **The pool has to hold arbitrary bytes**, so a
  `*`-marked value is spelled by `resQuoteBinary` - printable bytes as themselves, everything
  else as an *octal* escape with all three digits written (`\x` would take every hex digit that
  follows it) - and `cgLiteralByteLength` already counts an octal escape as the one byte it is,
  which is what lets a value begin with `\0` and keep its length (`stress/resources-binary`).
  **The spelling moved out of the emitter**: the driver hands the emitter literals that are
  already spelled (`resStoredLiterals`), so the pool and its index cannot disagree about how a
  byte is written, and `Emitter.resourceEntries` - the full list, unused since the lookup moved
  to `sourcegen` - is gone. And **the format's byte helpers needed a section of their own**
  (`resfmt`): putting them in `strops` made every program that reached a `Str` operation carry
  them, which three goldens caught immediately.

  The same pass rewrote the framing the project outgrew: the README no longer promises
  "readable C++" (the output is a *lowering* - hoisted locals, numbered temporaries,
  labels/gotos - that a profiler and a debugger can use, and it will keep getting less
  readable), it says plainly that there is one language and one implementation (the compiler's
  own sources; the bootstrap is *its output*, checked in as the fixed-point proof and as the
  way a C++-only machine builds a Simse compiler), and it spends its room on what writing the
  language feels like instead of on the emitter's shape. The same claim was removed from
  `impl_specs/user-language-roadmap.md`, `docs/state-of-the-field.md` and
  `impl_specs/generators.md`.

  Verified: `bun tools/stress.js` **61/61** (one new case, and no golden needed re-capturing
  once the helpers stopped riding in `strops`), `bun tools/smgen.js` 1/1, and the bootstrap
  fixed point holds byte for byte after one refresh.

- **One `fmtStr` template per line, a `fromInt` that short-circuits, and a pack whose
  elements convert.** Three changes to what a body looks like, all of them shapes the
  emitted C++ was spelling the long way.

  **The `+` chains are gone.** Wherever the compiler built a line from several small
  pieces it now writes one `fmtStr` template - ~145 call sites across `codegen`, `linear`,
  `sema`, `parser`, `lex`, `sourcegen`, `compiler`, `profiling` and the RTL's
  `resources`/`profiling` files: `"if (" + test + ") goto " + target + ";"` is
  `fmtStr("if (|) goto |;", test, target)`. The rule stayed deliberately narrow - three
  pieces or more, at least one of them a literal, and built *fresh*, so a
  `text = text + ...` run inside a loop is still a run of appends. A literal `|` cannot be
  spelled in a template, so a key like `name + "|" + kind` stayed a chain, and a two-piece
  expression (`"goto " + target`) was left alone.

  **A dense enum's `fromInt` is one range test.** `Enum.fromInt(Int): Opt<Enum>` was an
  if-chain, one `if (value == N)` per member - 34 lines for `IlOpKind`. An enum whose
  members run 0, 1, 2, ... *is* its own index, so it is now
  `if (value >= 0 && value <= 33) return Opt<E>::some((E) value);`. The lower bound is not
  decoration: without it `fromInt(-1)` answers `some((E) -1)` where the chain answered
  `none()`. Only the dense shape short-circuits - a gap (`B = 5`) or a non-zero start keeps
  the chain.

  **A pack's elements convert** (`linear/LinearForm.kt`, `packArguments` and the `listOf`
  literal path). Elements were taken with `operandOf`, so a handle element in a list of
  values - `listOf<Str>(xmlAttr(n, Name))`, a `*Str` into a `List<Str>` - emitted
  `List<Str>{p}` and was a C++ type error. They now convert the way a by-value parameter's
  argument does (`specs/functions.md`, "Handles at a call", which records it), matching the
  spec's own table for arguments. The migration is what found it: `fmtStr`'s items are `Str`
  and `xmlAttr` answers a `*Str`, so the emitter's own calls are exactly this shape. It is
  also the change that needed **two builds** - the tree's sources may not use a behavior the
  running compiler does not have, so the first build spelled those sites through a `Str`
  local and the second took the borrow directly.

  The compiler's *own* amalgamation moved (the pool, whose literals the templates changed,
  and the `:;` a label ends with), which is why the published bootstrap was refreshed. What
  did **not** move is any program's output: `stress/hello` and `stress/flat-blocks`, the two
  emission goldens, pass untouched and every other byte is unchanged.

  Verified: `bun tools/stress.js` **61/61** - `stress/rtl-simse` grew the handle-item and
  packed-handle sections and its `expected.stdout` is the one golden that changed - both
  emission goldens byte-identical, `bun tools/smgen.js` 1/1, and the bootstrap fixed point
  holds byte for byte after one refresh.

- **The runtime's last hand-written translation unit is gone: a program is one file (T29
  finished).** `cppsrc/rtl/native.cpp` - the platform's natives (file I/O, directory
  listing, the path tests, `eprintln`, `FileStream::open`) and the two clocks - was the
  repository's second hand-written translation unit, so every build was "compile the
  amalgamation *and* native.cpp, then link", and a build that left the second file out
  failed in the linker (`LNK2019`) rather than in the compiler. Anyone bootstrapping Simse
  had to know that; now there is nothing to know.

  The bodies are resources. `fileio` is a new section of `cppsrc/rtl/_res.md`: the
  prototypes (which were `cppsrc/rtl/fs.hpp`, plus `simse_fileStream_open` in
  `filestream.hpp`) are its `forward:` text and the definitions are its `bodies:` text, with
  `native.cpp`'s two file-local helpers inlined into their one caller each. `timeops` gained
  the clock bodies beside the declarations it already had, and `fs.hpp` is deleted. The
  prelude declarations name their symbol in the section
  (`@SmGen("res", "fileio", "simse_listFiles")` in `cppsrc/rtl/fs.kt`, and `readFile` in
  `cppsrc/common/common.kt`), so nothing is linked in beside the program's own translation
  unit: `build.js`, `tools/bootstrap.js`, `tools/stress.js`, `simse.vcxproj` and
  `tools/vscheck.mjs` no longer name a second file, and `bun tools/bootstrap.js` compiles
  `cppsrc/simse_bootstrap.cpp` with `cl.exe` alone.

  **`fileio` is `emit: always`**, which is the one decision worth recording. A *program* may
  name any of those symbols with a declaration of its own -
  `native("simse_native_readFile") fun readFile(path: Str): Str`, which is
  `impl_specs/native-interop.md`'s example and `stress/native-read-file`'s - and then no
  `res` declaration exists to reach the section (`sourcegen/ResGen.kt` emits a section for
  the declarations that name it, plus the `emit: always` ones, while a `native(...)`
  declaration names the `cpp` generator). The narrow alternative - one section per platform
  symbol with `symbol:`, emitted when that symbol is reached - would keep a program to what
  it actually calls, at the cost of changing that mechanism; it is recorded in
  `guide4ai.md` §8 rather than done. What every program pays for `emit: always` is about
  4 KB of C++ text, the same functions the linker used to place whether or not they were
  called.

  Verified: `./build.bat --release` green, `bun tools/stress.js` **61/61**
  (`native-read-file` is the case that pins the FFI path; the ten emission goldens were
  re-captured and their only delta is the reworded `timeops` comment plus the `fileio`
  section), and `bun tools/bootstrap.js` reports **one input file** with the fixed point
  holding byte for byte (`cppsrc\simse_bootstrap.cpp -> simse_boot.exe`, 15.9 s,
  self-transpile 866 ms, 1.49 MB / 49,585 lines checked in).

- **The intrinsics are one union and one span: `Variant2`, and `StrView` is
  `Span<Char>` (T81).** Two things the runtime had grown separately became one thing
each. `Opt<T>` was a struct wrapping `std::optional<T>` and `Res<T>` was a struct with a
  `T` field beside a `Str` error, so a result was two objects that could disagree - and
  did: `isOk()` was "`Error` is empty", which read `Res<T>.err("")` as a success. Both are
  now arms of one local tagged union, `Variant2<A, B>` (`cppsrc/rtl/variant2.hpp`):
  `Opt<T>` is `Variant2<T, VoidEnum>` and `Res<T>` is `Variant2<T, Str>`, `VoidEnum` being
  the empty alternative that tells an optional from a result. `std::variant` was rejected
  for two reasons that both matter to this runtime: `std::get` throws (there are no
  exceptions here, and every accessor is unchecked like the rest of the RTL) and its
  valueless-by-exception state is a third state `Res` cannot enter. What the two types
  expose did not change - `.Value`/`.Error`, `value()`/`hasValue()`, `some`/`none`,
  `ok`/`err` are the language's own spellings and the emitter writes them straight
  through, so they are members of the one storage type.

  `StrView` was a struct holding a `Span<Char>` plus the text operations, which made a
  `StrView` and a `Span<Char>` different types for no observable reason. It is now
  `typealias StrView = Span<Char>` on both sides (`cppsrc/rtl/StrView.kt`,
  `cppsrc/rtl/strview.hpp`), so one type carries both names and the text operations are
  natives *on the span*. Two things had to move with it: the `bytes` member (there is
  none; a view is the span) and `StrView::toString()` (gone - it existed for a `.toString()`
  on a string-pool entry that no emitted code ever wrote; the free `simse_strView_toString`
  is what sites call). No emitter change was needed, which is the pleasant part: the
  prelude declares its receivers as `StrView` and the call sites' types are spelled
  `StrView` too, so the receiver-type lookup still matches, and `typeName` prints `StrView`
  unchanged. `resources.kt`'s `StrView(Span<Char>(null, 0))` became `Span<Char>(null, 0)`.

  **The interesting part is that triviality is a performance property.** The first cut
  was one class body that always managed the union's lifetime, and it cost the
  self-transpile **~3%** (interleaved A/B, min: 731.7 -> 754.9 ms): an owning-union type is
  never trivially copyable or trivially destructible, so every `std::optional<Int>`-shaped
  slot in the compiler - and there are many (`Opt<Int>`, `Opt<StrView>`, `Opt<NameKind>`,
  plus every temporary) - turned into a value with a destructor to run and a copy the ABI
  hands back through memory instead of in registers. `Variant2Storage<A, B, Managed>` now
  has two forms, chosen by `!(is_trivially_copyable_v<A> && is_trivially_copyable_v<B>)`:
  the trivial one declares no copy, move or destructor at all (a setter starts the other
  arm's lifetime by assigning to it, `clear` is nothing), the managed one does the
  placement-new/destroy dance. That recovered essentially all of it - **min +0.7% / median
  +1.4%** (25 pairs) and **min +1.0% / median +1.1%** (30 pairs), against a null control
  that reads ±1% - so the refactor is a wash by measurement. Two things that look like
  they should share code cannot: the arms are spelled out in *each* form, because the
  union's destructor is deleted as soon as one arm has one and a common base holding it
  would carry that deletion into both forms, and the `First`/`Second` constructor tags are
  what let a `Variant2<Str, Str>` - `Res<Str>`, the emitter's hot return type - say which
  arm it is building.

  Emitted program text is byte-identical (`cmp` on the compiler's own transpile, and the
  nine `stress/*/expected.cpp` goldens pass untouched), so the change is
  representation-only and **the published bootstrap does not move** - it was refreshed
  anyway and came back byte for byte.

  Verified: `./build.bat --release` green, `bun tools/stress.js` **62/62** - the new
  `stress/optional-result` is the case that pins the union: `null` in an `Opt<T>`, an
  `Opt<Str>` payload copied *out* of the union, re-assignment between arms, `err("")` as a
  failure, `List<Opt<Int>>`, and a failed `Res<Int>` whose message is longer than the
  inline buffer copied through the union and handed back - `bun tools/smgen.js` 1/1, both
  `bun tools/bootstrap.js` fixed-point checks byte for byte, a `SIMSE_NO_PACK4` build of
  the invariant probe, and static assertions that `Opt<Int>`/`Opt<StrView>` are trivially
  copyable and trivially destructible while `Opt<Str>`/`Res<Str>` manage their storage.

- **The RTL writes `@SmGen`, and the view/stream/resource operations are resources
  (T82).** `native("sym")` was still the spelling of every prelude declaration that
  reached hand-written C++ - the `StrView` operations, `FileStream`'s methods and the
  `Resources` accessor - although the two spellings have been one declaration since T26
  (`native("sym")` is sugar for `@SmGen("cpp", "sym")`, `bun tools/smgen.js`). They say
  `@SmGen` now, so **no file under `cppsrc/` spells `native`**: the keyword stays the
  *program's* FFI spelling, `stress/native-read-file` and the equivalence fixture
  (`stress/smgen-native`, `stress/smgen-cpp`) keep it honest, and dropping it from the
  language is `guide4ai.md` §8's call rather than this change's.

  The same pass moved the C++ those declarations named out of the headers and into three
  new `cppsrc/rtl/_res.md` sections, because a header is compiled *in* while a section is
  text a declaration reaches:

  - `strview` - the twelve view operations (`size`, `isEmpty`, `at`, `charAt`, `slice`
    x2, `startsWith`, `startsWithPtr`, `find`, `indexOf`, `substr`, `toString`,
    `spanOfStr`). `strview.hpp` keeps the *type* (`typealias StrView = Span<Char>`) and
    the **literal interop** - the comparison operators, `+`, `<<`, `simse_strView_of`,
    `simse_strView_compare`, the `Str` conversion - and that split is forced, not chosen:
    the interop is reached by the C++ compiler's overload resolution at a literal site,
    so no prelude declaration could name it and no section could be reached for it.
  - `filestream` - `FileStream`'s method bodies, as out-of-line member definitions (the
    emitter calls a handle's methods as members, so the *struct* has to keep declaring
    them; `filestream.hpp` is fields, declarations and the three reads' contract now).
    `readLineView` builds its view directly instead of calling `simse_spanOfStr`, so a
    program that reads views does not drag the `strview` section in with it - the one
    coupling the move would otherwise have created.
  - `resources` - the one accessor over the table, `simse_resources_entries`; `install`
    and the storage stay in `resources.hpp`, because the table the emitter writes calls
    them before any declaration is reached.

  **What the move costs, and why it is still the right shape.** The reach rule is
  *name*-based (`SourceGenContext.isReached`), and a name like `size`, `toString`,
  `isEmpty`, `at`, `slice` or `find` is a method on several types, so a program that calls
  `names.size()` on a `List` carries the whole `strview` section: `stress/flat-blocks`
  627 -> 749 lines of emitted C++, `stress/hello` +1 (the reworded `fileio` comment) and
  the compiler's own amalgamation +122 of 50.6 k. Nothing runs differently (unused inline
  functions are not codegen'd) - it is emitted *text*. One section per operation would
  make the reach exact at the price of twelve sections; the convention is one section per
  header's worth of operations (`listops`, `dictops`, `strops`), so it stays one.

  Verified: the `@SmGen` migration alone is **byte-identical** in the emitted C++ (`cmp`
  on the compiler's own transpile, before and after), which is the sugar relation failing
  to matter, as documented; `bun tools/smgen.js` 1/1 (the prelude now on the attribute
  side of the pair, the fixture still `native`); `bun tools/stress.js` **62/62** with the
  ten `expected.cpp` goldens re-captured by hand - their only delta is the new section
  text and that comment; `bun tools/bootstrap.js` both fixed-point checks byte for byte
  after the refresh, self-transpile 741 ms.
