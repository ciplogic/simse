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
`cppsrc/common/StrView.simse`, `cppsrc/common/common.simse`,
`cppsrc/skelparser/SkeletonParser.simse`) and the current
`cppsrc/{parser,sema,codegen,rtl}` implementations.

## Intended port order

1. **common / StrView** - the smallest, no self-referential dependencies; needed
   by every other mirror.
2. **scanner** (`Scanner.simse`) - the first real differential test; needs
   `common` via `import cppsrc.common`.
3. **skeleton parser** - uses the scanner API and `List<SkeletonNode>`.
4. **AST/parser/sema/codegen** - the largest, still C++-only; port last.

## `common` / `StrView`

| Feature | Needed by | Status | Notes |
| --- | --- | --- | --- |
| `data class` with fields and methods | `StrView` | supported | lowered to a C++ struct plus free functions. |
| `var`/`val` locals, `while`, `if`, `return` | `StrView`, `common` | supported | |
| `Str` value type, indexing, `size()`, literals | `StrView` | supported | `Str` is `std::string`. |
| `&T` fields and `&value` construction | `StrView.source` | supported | `&T` -> `std::shared_ptr<T>`. |
| `&T` member access / indexing auto-deref | `StrView.at` | supported | emits `(*handle)[i]`, `handle->m()`. |
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
| common / StrView / xmlutil | `cppsrc/common/*.simse` | yes (merged transitively) | yes | exercised through every port | part of each diff |
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
| `Cursor<T>` | iteration instead of range-for | supported | `while (c.hasValue()) { ... c = c.next() }`. |
| lambdas/closures | visitors | supported | by-value captures; reference captures deferred. |
| `for`/range-for | loop rewriting | missing | deferred; `Cursor<T>` is the replacement idiom. |
| string interpolation | diagnostics | missing | deferred. |
| `when`/pattern matching | dispatch | missing | deferred; use `switch`. |

> **Note.** The C++ compiler's own loops have **not** been refactored to
> `Cursor<T>`; that happens per component during the parser port. `Cursor` is the
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
