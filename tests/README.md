# Golden tests

Golden tests for the front end and the C++ emitter. Each fixture in
`tests/fixtures/` is run through the scanner, the parser, name/type resolution,
and (when it parses) code generation, and deterministic dumps are compared
against checked-in goldens in `tests/golden/`.

## Build

The tests build with the project. From `simse/cmake-build-debug`:

```
cmd //c _msvc_build.bat
```

This builds `simse_lib`, `simse_tests`, and `simse_transpile`.

## Run

```
simse_tests.exe
```

The runner:

- scans, parses, and analyzes each `tests/fixtures/*.kt` fixture in sorted
  filename order, comparing the token, AST, XmlNode-AST, and sema goldens (and
  the `cpp` golden when the fixture parses);
- checks the negative fixtures (`parse_error.kt` must fail to parse,
  `sema_unknown_type.kt` must report an unknown-type diagnostic,
  `ctor_arity.kt` a constructor-arity diagnostic,
  `when_else_last.kt` an arm-after-`else` parse error, and
  `sema_extension_arity.kt` a prelude-extension arity diagnostic);
- checks the hoisting fixture (`hoisting.kt` parses and resolves cleanly with
  use-before-declaration);
- parses and analyzes every real `.kt` file under `cppsrc/` and asserts the
  parse succeeds and sema is clean.

It prints `PASS`/`FAIL` per case, a line diff for each failure, and a final
`N passed, M failed` summary, exiting nonzero if any case fails.

The fixtures directory defaults to the compile-time `SIMSE_FIXTURES_DIR`. Pass a
directory as the first argument to override it:

```
simse_tests.exe path\to\fixtures
```

## Update goldens

Regenerate the goldens after an intentional front-end change:

```
simse_tests.exe --update
```

or, equivalently:

```
set SIMSE_UPDATE_GOLDENS=1
simse_tests.exe
```

Update mode rewrites every `tests/golden/<fixture>.{tokens,ast,astxml,sema,cpp}.expected`
and reports `UPDATED` per fixture. Review the diff of the regenerated goldens
before committing. After updating, run once more in check mode to confirm 0
failures.

## Golden categories

For a fixture `foo.kt` the harness writes:

- `foo.kt.tokens.expected` - the scanner token dump;
- `foo.kt.ast.expected` - the AST dump (or an error marker, see below);
- `foo.kt.astxml.expected` - the AST rendered as an `XmlNode` tree (only for
  fixtures that parse); see `impl_specs/ast-xmlnode.md`;
- `foo.kt.sema.expected` - the sema diagnostics, one per line (empty if clean);
- `foo.kt.cpp.expected` - the emitted C++ (or a `CodegenError` marker). Only
  written for fixtures that parse, since codegen runs on the AST.

Separately, the end-to-end programs and their expected stdout moved to the
`stress/` corpus and its `bun tools/stress.js` harness (`stress/README.md`): a
folder per program, the harness transpiles it with the compiler under test,
compiles the generated C++, runs it and diffs the output. This runner no longer
knows about stdout goldens.

### Token dump format

One line per token, TAB-separated, deterministic:

```
<KindName>\t<line>:<column>\t<escapedText>
```

- `KindName` is the `lex::TokenKind` enumerator name (e.g. `ReservedWord`).
- `line`/`column` are the token's 1-based start position from `Token::pos`
  (tabs count as one column; CRLF advances the line once).
- `escapedText` is the token text with `\`, `\n`, `\r`, and `\t` escaped as
  backslash sequences. Empty text is an empty final field.
- The Eof token is not printed.

A scan error replaces the position column with the full scanner message:

```
Error\t<escaped full message>
```

e.g. `Error\t1:3: Unexpected character: '@ b\\n'` (the scanner message already
carries `<line>:<column>` and the escaped snippet).

### AST dump format

An indented tree, two spaces per level, one node per line, ASCII only and free of
paths or addresses. Example (`program_basic.kt`):

```
Module @1:1
  DataClass Point @8:1
    Field var x: Int @8:18
    Function sum @9:5
      Return Int
      Body
        Return @10:9
          Binary + @10:16
            Lhs
              Member .x @10:16
                Receiver
                  Name this @10:16
            Rhs
              Member .y @10:25
                Receiver
                  Name this @10:25
```

Types are rendered inline in source-like form (`List<TokenMatcher>`,
`(*Float64) -> Unit`, `&List<SkeletonNode>`). Literals keep their scanned text
(with `\`, `\n`, `\r`, `\t` escaped).

Because the scanner fixtures are not complete programs, their `.ast.expected`
holds a one-line marker instead of a tree:

```
ScanError <message>                          // the fixture did not scan
ParseError <file>:<line>:<column>: <message> // the fixture did not parse
```

Their `.sema.expected` and `.cpp.expected` are empty/absent (sema and codegen
only run on a successfully parsed module).

### Sema dump format

One diagnostic per line, in walk order:

```
<file>:<line>:<column>: <message>
```

An empty file means no diagnostics.

### C++ emission dump format

The amalgamated translation unit from `codegen::emitProgram`. For a fixture that
parses, the golden is either the emitted C++ or a single marker line:

```
CodegenError <file>:<line>:<column>: <message>
```

The emitted file starts with a fixed prelude (`#include "cppsrc/rtl/simse.hpp"`,
`<iostream>`, `<type_traits>`), then type declarations, forward declarations, and
definitions, each preceded by a `// <file>:<line>` source comment. See
`impl_specs/rtl-abi.md` for the type mapping and the supported subset.

### XmlNode AST dump format

The AST is also rendered through `XmlNode` (`ast::toXmlNode` + `ast::dumpXmlNode`),
one line per node, two spaces of indent per depth:

```
<name> <key>='<value>' <key>='<value>' ...
```

`name` is the structural role and a `kind` attribute distinguishes categories;
see `impl_specs/ast-xmlnode.md` for the full schema. The Simse proof-of-carrier
program `emit_xmlnode.kt` prints this format from an `XmlNode` tree it builds
in Simse.

## End-to-end stress corpus

The transpile -> compile -> run -> compare round trip is no longer a set of CMake
targets here. Every end-to-end program lives in its own folder under `stress/`
(`stress/<name>/src/` plus its expectations), and `bun tools/stress.js` runs the
whole corpus against the compiler under test - by default the self-hosted
`./simse.exe`, so the artifact that ships is the one being exercised. See
`stress/README.md` for the folder format and the options; `--filter`, `--list`
and `--jobs` are the ones used most. The programs that exercise the language
(`stress/{hello,shapes,generics,containers,language-tour,strings,dictionary,
lambdas,xml-tree,cursor,main-args,native-read-file}`) plus the newer stress cases
(modules, control-flow, text-processing, recursion, bulk-list, and the two
diagnostic cases) all live there.

## Differential ports and the bootstrap

The build also runs the differential ports of the scanner (`scanner_diff`), the
skeleton parser (`skel_diff`), the parser (`parser_diff`), the sema pass
(`sema_diff`), the resources parser (`resources_diff`, over the `tests/resources/*.md`
fixtures), and the C++ emitter (`codegen_diff`); see
`impl_specs/tasks/11-...`, `13-...`, `19-...`, `21-...`, and `22-...`.
Each compares the hand-written C++ component against the transpiled Simse one over
`tests/fixtures/*.kt` (the resources one over its own `.md` fixtures); the parser,
sema, and codegen drivers additionally check
the reference output against the checked-in goldens.

The `stage1_check` step is the two-step bootstrap: the C++ transpiler emits
`stage1/gen/simse_out.cpp` from `cppsrc/compiler/Driver.kt` (the whole
compiler source set, through its imports); that file is kept as
`stage1/gen/simse_out1.cpp` and compiled into `stage1/simse_stage1.exe`; and
running that stage-1 compiler over the same source set regenerates
`stage1/run/simse_out.cpp`, which must be byte-identical to `simse_out1.cpp`
(the fixed point). It also checks `simse_stage1` against the C++ transpiler on
the `emit_lang` fixture.

## Transpiler CLI

```
simse_transpile <input.kt>... [-o <output.cpp>] [--prelude <file>]
                [--root <dir>] [--module-root <dir>]...
                [--showLinearRepresentation] [--profile]
```

With no `-o` the output is written to `simse_out.cpp` in the current folder.
With no input arguments it discovers every `.kt` under the current directory
(recursively, sorted). `--prelude` overrides the default RTL prelude **set**
(`cppsrc/rtl/`, a directory whose `*.kt` files are all loaded); a missing
default is skipped silently. Errors are
written to stderr as `<file>:<line>:<col>: <message>` and the process exits
nonzero. Run it from the repository root so the generated
`#include "cppsrc/rtl/simse.hpp"` resolves.
