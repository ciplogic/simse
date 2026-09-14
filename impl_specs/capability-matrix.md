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

The matrix is derived from the real sources (`cppsrc/lex/Scanner.simse`,
`cppsrc/common/common.simse`, `cppsrc/skelparser/SkeletonParser.simse`) and the
current `cppsrc/{parser,sema,codegen,rtl}` implementations.

## Intended port order

1. **common** - the smallest, no self-referential dependencies; needed by every
   other mirror.
2. **scanner** (`Scanner.simse`) - the first real differential test; needs
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

## Scanner (`Scanner.simse`)

| Feature | Where used | Status | Notes |
| --- | --- | --- | --- |
| `enum` + `Enum.Member` access | `TokenKind.Eof` | supported | emits `TokenKind::Eof`. |
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

## Skeleton parser (`SkeletonParser.simse`)

| Feature | Where used | Status | Notes |
| --- | --- | --- | --- |
| `import cppsrc.lex` (scanner API) | whole file | supported | transitive imports merge. |
| `enum` + member access | `SkeletonType.Terminal` | supported | |
| Generic data class instantiation | `List<SkeletonNode>` | supported | |
| `&List<T>` fields and methods | `SkeletonNode` | supported | |
| `var` parameter mutation | folding loop | supported | parameters are mutable. |
| Indexing assignment | deleting a folded range | supported | |
| Full self-transpile (ported) | whole file | supported | emits, compiles, and diffs byte-identically against the C++ `parseSkeleton` over every fixture (`skel_diff`). |

## AST / parser / sema / codegen mirrors

The parser (T19), sema (T21), and code generator (T22) are ported, and the
driver/CLI plumbing is ported too (T23): the compiler now self-hosts at stage 1.
The XmlNode accessors are shared through `cppsrc/common/xmlutil.simse`, and the
filesystem/IO surface is the prelude natives in `cppsrc/rtl/fs.simse`.

## Ported components

Progress of the incremental port (see `impl_specs/roadmap.md`):

| Component | Mirror | Emits | Compiles | Diff-identical | Harness |
| --- | --- | --- | --- | --- | --- |
| common / xmlutil | `cppsrc/common/*.simse` | yes (merged transitively) | yes | exercised through every port | part of each diff |
| scanner | `cppsrc/lex/Scanner.simse` | yes | yes | yes (5532-line dump) | `scanner_diff` |
| skeleton parser | `cppsrc/skelparser/SkeletonParser.simse` | yes | yes | yes (4992-line tree dump) | `skel_diff` |
| parser | `cppsrc/parser/Parser.simse` | yes | yes | yes (2136-line XmlNode dump) | `parser_diff` |
| sema | `cppsrc/sema/Sema.simse` | yes | yes | yes (40-line diagnostic dump) | `sema_diff` |
| codegen | `cppsrc/codegen/Codegen.simse` | yes | yes | yes (804-line emission dump) | `codegen_diff` |
| driver / CLI | `cppsrc/compiler/Driver.simse` | yes | yes | two-step fixed point (`simse_out1.cpp` == `simse_out.cpp`) | `stage1_check` |

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
| XmlNode accessors | sema consumption | supported | emitted helper functions in `Sema.simse` (attribute lookup, children by role, positions). |
| `Span<T>` | iteration instead of range-for | supported | `while (!span.isEmpty()) { ... span = span.slice(1) }`. |
| lambdas/closures | visitors | supported | by-value captures; reference captures deferred. |
| `for`/range-for | loop rewriting | missing | deferred; `Span<T>` is the replacement idiom. |
| string interpolation | diagnostics | missing | deferred. |
| `when`/pattern matching | dispatch | missing | deferred; use `switch`. |

> **Note.** The C++ compiler's own loops have **not** been refactored to
> `Span<T>`; that happens per component during the parser port. `Span` is the
> language-level replacement for range-for, not a change to the C++ sources.

## Feature gaps seen by the compiler team

These are the language/RTL features that were missing or wrong and had to be
added or fixed across the self-host attempts (each fix is generic, not
component-specific):

1. **Import resolution.** `import a.b.c` now merges every `*.simse` directly
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
- **Scanner self-transpile succeeded.** `simse_transpile cppsrc/lex/Scanner.simse`
  emits `Scanner.simse.cpp` (about 500 lines); it compiles against the RTL and,
  driven by `tests/scanner_simse_main.cpp`, produces a token dump **byte-identical**
  to the hand-written scanner over every `tests/fixtures/*.simse`. The diff is run
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
  cppsrc/skelparser/SkeletonParser.simse` emits `SkeletonParser.simse.cpp`; it
  compiles against the RTL (with `Scanner.simse` and `common` merged through
  `import cppsrc.lex`), and the `skel_diff` step shows the generated
  `parseSkeleton` produces a tree dump byte-identical to the C++ implementation
  over every fixture (1508 lines). `cppsrc/main.simse` was repaired and is now a
  runnable e2e program (`main_program`).
- **T14 (language features) and T15 (XmlNode carrier) done.** Added `switch`,
  `null` lowering, the `Str`/`Char`/numeric library, `min`/`max`, and enum
  conversions; the prelude is now a *set* (`cppsrc/rtl/{rtl,xml}.simse`). The AST
  converts to `XmlNode` (`ast::toXmlNode`, `.astxml` goldens), and the
  `emit_xmlnode` e2e program builds the same tree in Simse and prints it
  byte-identically to the C++ dump for `xml_probe.simse`. The scanner and
  skeleton-parser differentials still pass.
- **T16 (Cursor), T17 (Str library), T18 (lambdas) done.** `Cursor<T>` is the
  iteration idiom (`while (c.hasValue()) { ... c = c.next() }`); the prelude set
  gained `cppsrc/rtl/Cursor.simse` and the `simse_cursorOf` helper, and prelude
  data-class methods now lower to C++ member calls. `Str` gained `charAt`,
  `trim`, `split`, `toUpper`, `toLower`, `isEmpty`, `indexOf`, `lastIndexOf`.
  Lambdas lower to by-value-capturing C++ lambdas assignable to
  `Func<Ret(Params)>`. New e2e programs `emit_cursor`, `emit_str`, and
  `emit_lambda` all transpile, compile, run, and stdout-diff clean; `for`/
  range-for remains deferred (`Cursor<T>` is the replacement); the C++ compiler's
  own loops are not yet refactored to `Cursor`.
- **Parser self-transpile succeeded (T19).** `simse_transpile
  cppsrc/parser/Parser.simse` emits `Parser.simse.cpp` (with `Scanner.simse` and
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
  (`cppsrc/rtl/rtl.simse`) declares them as `native("simse_dict_*")` extensions.
  Codegen learned to lower a generic *native* call to its symbol with type
  arguments (`dictionaryOf<Str, Int>()` -> `simse_dictionaryOf<Str, Int>()`). A
  new e2e program `emit_dict` exercises all of it and stdout-diffs clean.
- **Sema self-transpile succeeded (T21).** `simse_transpile
  cppsrc/sema/Sema.simse` emits `Sema.simse.cpp` (with the parser, scanner, and
  common merged through `import cppsrc.parser`); it compiles against the RTL and
  the `sema_diff` step shows the generated `analyze` produces diagnostics
  **byte-identical** to `sema::analyze` over every fixture (39 lines, 35
  fixtures), with the reference matching the `.sema.expected` goldens. The port
  added only emitted XmlNode accessor helpers; no new compiler/RTL features were
  needed. A negative fixture (`sema_extension_arity.simse`) pins the prelude
  merge on both sides. No mirror had to change.
- **XmlNode accessors shared (T22).** The accessors moved from `Sema.simse` into
  `cppsrc/common/xmlutil.simse` (emitted, non-prelude); the real-source check now
  merges the RTL prelude for non-prelude mirrors so a helper module that uses
  prelude types resolves without an import. `sema_diff` stayed byte-identical.
- **Codegen self-transpile succeeded (T22).** `simse_transpile
  cppsrc/codegen/Codegen.simse` emits `Codegen.simse.cpp` (4789 lines; the parser,
  scanner, sema, and common/xmlutil merged through `import cppsrc.sema`); it
  compiles against the RTL and the `codegen_diff` step shows the generated
  `emitProgram` produces C++ **byte-identical** to `codegen::emitProgram` over
  every fixture, with the reference matching all 21 `.cpp.expected` goldens. No
  new compiler/RTL features were needed (one source-level note: chained string
  literals do not fold to `Str`, so the preamble is built with a `Str` variable).
- **Stage-1 self-host succeeded (T23).** The compiler source set is rooted at
  `cppsrc/compiler/Driver.simse` (a port of `TranspileMain.cpp` +
  `parseFileWithImports`); the C++ `simse_transpile` emits `compiler_stage1.cpp`
  (187048 bytes, covering the scanner, parser, sema, codegen, common/xmlutil,
  skeleton-parser mirror, and driver), it compiles into `simse_stage1`, and running
  `simse_stage1` over the same source set reproduces `compiler_stage1.cpp`
  **byte-for-byte** (the `stage1_check` fixed point). It also reproduces the C++
  output for a real fixture. Added the filesystem/IO prelude natives (`cppsrc/rtl/fs.simse`,
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
  `.simse` found participates, and `simse_transpile` also includes its explicit
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
  component has a `.simse` mirror, all five differentials and every e2e program
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
  `simse_transpile` (C++ and the transpiled `Driver.simse`) default to
  `simse_out.cpp` when `-o` is omitted. `stage1_check` now makes the two-step
  flow explicit: `stage1/gen/simse_out.cpp` (C++ transpiler) is kept as
  `stage1/gen/simse_out1.cpp`, which is compiled into `simse_stage1`; running it
  writes `stage1/run/simse_out.cpp`, compared **byte-for-byte** (no `--ignore-eol`)
  with `simse_out1.cpp`. The `emit_lang` fixture check is byte-exact too. The
  compilation order is now canonical (kept files sorted by normalized path; scan
  results use generic `/` separators), so `--root cppsrc` and
  `simse_transpile cppsrc/compiler/Driver.simse` over the same file set emit
  identical C++. Stray `cppsrc.cpp` verification outputs were removed.
- **The sample `cppsrc/main.simse` and its hand-written CLI `cppsrc/main.cpp`
  were deleted.** `main.simse` was the second `main` in a `simse cppsrc`
  amalgamation, so the whole tree could not be compiled; `main.cpp` was the
  directory-compiler CLI, superseded by `simse_transpile`. `cppsrc` now scans to
  exactly one program (the `Driver.simse` compiler), and
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
  `cppsrc/linear/Linear.simse`, `impl_specs/linear-lowering.md`) rewrites every
  function/method/lambda body into `Stmt.Label`, `Stmt.Goto`, `Stmt.IfTrue`,
  `Stmt.IfFalse` and `Stmt.Block`; the emitters no longer have If/While/Switch/
  `Stmt.Block` only when the region declares a variable at its own level (a C++
  jump may not bypass an initialization still in scope), so most bodies splice
  flat; a second pass (`cppsrc/linear/Simplify.{h,cpp}` / `Simplify.simse`,
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
  accumulator (`StrView.simse`, `Scanner.simse`, `Codegen.simse`,
  `listops.hpp`, `rtl.simse`).
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
  now `AstXmlNode` (`cppsrc/rtl/astxml.simse` + `.hpp`): the node's role is an
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
  `lookupValue`'s scope walk, all in `cppsrc/sema/Sema.simse` (`guide4ai.md`
  section 9).
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
  `FileStream` (`cppsrc/rtl/filestream.hpp`, prelude `cppsrc/rtl/fs.simse`) is
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
  distance to the 727 ms reference - see `guide4ai.md` section 9 item 8.
  `benchmarks/onebrc/benchmark.md` has the method, the notes (tenths as `Int`, the
  half-toward-positive-infinity rounding rule, CRLF vs LF) and the run commands.
- **`Span<T>`, `StrView`, and the in-place line reader (T43).** One borrowed view
era replaced two: `cppsrc/rtl/Span.simse` + `span.hpp` declare `Span<T>` (a `*T`
pointer plus a length, `size`/`isEmpty`/`at`/indexing/`slice` in the C# two forms)
and `cppsrc/rtl/StrView.simse` + `strview.hpp` declare `StrView` - *embeds* a
`Span<Char>` and adds the byte surface (`charAt`, `find`/`indexOf`, `startsWith`,
`startsWithPtr`, `substr`, `toString`), built by `spanOfStr(*text)`; `spanOf(*items)`
borrows a list. The old `Cursor<T>` (a `&List<T>` + start + len), the RTL's
`StrView` (a `*Str` + start + len) and the compiler's own `common.StrView` are gone,
and `Parser.simse` iterates `Span<Token>` while both scanners use `StrView`. Two
design points are load-bearing and measured, not stylistic: an **alias**
(`typealias StrView = Span<Char>`) does not survive the emitter's receiver-type
lookup - a chained call through one is emitted as the wrong conversion
(`simse_int_toString` on a view) - and prelude **methods** are invisible to the
emitter's inference, so the view's operations are declared as natives with explicit
symbols, which is what gives `view.slice(0, n).toString()` and `"x" +
view.toString()` their types. (A third, pre-existing edge bit the test: a chained
call on a handle method - `stream.fileSize().toString()` - has no inferred type and
now picks the wrong `toString`; bind the middle step to a typed `val`, as
`guide4ai.md` section 10 already says.)
  Adding the RTL type name also exposed a name-resolution bug: the emitter consulted
the RTL *name* list before the program's own declarations, so a declared type of
the same name (the compiler had a `common.StrView`) was shadowed in every emitted
signature. `typeName` (both rings) now checks the declared types first and lets any
package other than `rtl` win; T23 and the five differentials stay byte-identical,
and the tracked `cppsrc/simse_out.cpp` was regenerated (its embedded source-map line
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
  keyed by `Str`) and the dictionary's two lookups per line (section 9 item 8).
  The benchmark was then narrowed to this variant alone - C++ STL baseline against
  the in-place Simse program, 4 interleaved pairs: **1093/1106 ms against
  1390/1405 ms, i.e. 1.27x faster than the naive C++** (the ratio has run
  1.26-1.40x across sessions), at a 6.5 MB peak working
  set against the baseline's 5.9 MB, and byte-identical reports.
  `benchmarks/onebrc/benchmark.md` is the write-up.

- **Nested expressions are lowered to temporaries (T44).** The linear pass gave the
  emitter one *statement* vocabulary; `cppsrc/linear/ExpressionLowering.{h,cpp}`
  (`linLowerExprs` in `cppsrc/linear/ExpressionLowering.simse`) gives it one
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
  types come back (the sema-inference item in `guide4ai.md` section 9). Two
  boundaries are deliberate: an **lvalue path stays a path** (binding it would copy
  what is behind it, and a mutating call on the copy would be lost; only its indices
  and arguments are flattened, so `a[i + 2].append(x)` becomes
  `a[_sm_expr1].append(x)`), and **`&&`/`||` are left alone** because their operands
  are evaluated conditionally - those belong to the control-flow lowering, and their
  `ifTrue`/`ifFalse` shapes (plus a `?:` the grammar does not have yet) are written
  down in `impl_specs/linear-lowering.md` as the next step.
  Two goldens moved, both deliberately: `tests/golden/sema_switch_label.simse.cpp.expected`
  (the non-constant case label now hoists `f()` into `_sm_expr1`) and
  `stress/hello/expected.cpp` - the harness compares that file byte for byte, but
  `--update` only rewrites `expected.stdout`/`stderr`/`exit`, so it is copied out of
  `stress/.work/hello/out.cpp` by hand. Verified: both configurations green (the
  five differentials byte-identical, T23's two-step bootstrap byte-identical),
  `simse_tests.exe` **50/50** in both (the new pass and `Span.simse` are checked as
  sources too), `bun tools/stress.js` **24/24** with the self-hosted compiler and
  **24/24** with the C++ ring.

- **The lowered declarations get real types from a semantic step (T45).** The pass
  above bound nested expressions to `_sm_expr<n>` locals and left them *untyped*, so
  the emitter emitted `auto` for them and guessed the type whenever it needed one
  (the receiver of a chained call, `Res<T>.value`, a native extension's return).
  `sema::inferTypes` (`cppsrc/sema/TypeInfer.{h,cpp}`, `semInferTypes` in
  `TypeInfer.simse`) now runs as the last step of the lowering
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
  again, deliberately: `tests/golden/sema_switch_label.simse.cpp.expected` and
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
  output grows, which is the point: `cppsrc/simse_out.cpp` went from 12.4k lines and
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
