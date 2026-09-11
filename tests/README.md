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

This builds `simse_lib`, `simse`, `simse_tests`, and `simse_transpile`.

## Run

```
simse_tests.exe
```

The runner:

- scans, parses, and analyzes each `tests/fixtures/*.simse` fixture in sorted
  filename order, comparing the token, AST, XmlNode-AST, and sema goldens (and
  the `cpp` golden when the fixture parses);
- checks the negative fixtures (`parse_error.simse` must fail to parse,
  `sema_unknown_type.simse` must report an unknown-type diagnostic,
  `ctor_arity.simse` a constructor-arity diagnostic,
  `sema_switch_label.simse` a non-constant case-label diagnostic, and
  `sema_extension_arity.simse` a prelude-extension arity diagnostic);
- checks the hoisting fixture (`hoisting.simse` parses and resolves cleanly with
  use-before-declaration);
- parses and analyzes every real `.simse` file under `cppsrc/` plus `../cppsrc/main.simse`
  and asserts the parse succeeds and sema is clean.

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

For a fixture `foo.simse` the harness writes:

- `foo.simse.tokens.expected` - the scanner token dump;
- `foo.simse.ast.expected` - the AST dump (or an error marker, see below);
- `foo.simse.astxml.expected` - the AST rendered as an `XmlNode` tree (only for
  fixtures that parse); see `impl_specs/ast-xmlnode.md`;
- `foo.simse.sema.expected` - the sema diagnostics, one per line (empty if clean);
- `foo.simse.cpp.expected` - the emitted C++ (or a `CodegenError` marker). Only
  written for fixtures that parse, since codegen runs on the AST.

Separately, `tests/golden/<fixture>.stdout.expected` files hold the expected
stdout of the end-to-end programs (below); they are not part of the per-fixture
harness.

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
paths or addresses. Example (`program_basic.simse`):

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
program `emit_xmlnode.simse` prints this format from an `XmlNode` tree it builds
in Simse.

## End-to-end round trip (T8/T9/T10/T12/T14/T15/T16/T17/T18)

The default build (`cmd //c _msvc_build.bat`) also transpiles
`tests/fixtures/{emit_hello, emit_shapes, emit_generics, native_readfile,
emit_containers, emit_lang, emit_xmlnode, emit_cursor, emit_str,
emit_lambda, emit_dict, emit_main_args}.simse` and `cppsrc/main.simse`
(`main_program`), compiles the generated C++ (`e2e_<name>`), runs each with the
repository root as the working directory, and diffs its stdout against
`tests/golden/<name>.stdout.expected` with `cmake -E compare_files --ignore-eol`.
`emit_xmlnode`'s expected stdout is the C++ `XmlNode` dump for
`tests/fixtures/xml_probe.simse`, so it cross-checks the Simse carrier against the
converter. The generated sources and captured stdout live under
`cmake-build-debug/e2e/`. `native_readfile` and `main_program` link the
`simse_native` library; the RTL prelude **set** (`cppsrc/rtl/*.simse`) is loaded
automatically. These steps are part of `ALL`, so an ordinary (and clean) build
exercises the round trip and it cannot rot.

The build also runs the differential ports of the scanner (`scanner_diff`), the
skeleton parser (`skel_diff`), the parser (`parser_diff`), the sema pass
(`sema_diff`), and the C++ emitter (`codegen_diff`); see
`impl_specs/tasks/11-...`, `13-...`, `19-...`, `21-...`, and `22-...`.
Each compares the hand-written C++ component against the transpiled Simse one over
`tests/fixtures/*.simse`; the parser, sema, and codegen drivers additionally check
the reference output against the checked-in goldens.

The `stage1_check` step is the endgame: the C++ transpiler emits
`stage1/compiler_stage1.cpp` from `cppsrc/compiler/Driver.simse` (the whole
compiler source set, through its imports); it compiles into
`stage1/simse_stage1.exe`; and running that stage-1 compiler over the same source
set must reproduce `compiler_stage1.cpp` byte-for-byte (the fixed point). It also
checks `simse_stage1` against the C++ transpiler on the `emit_lang` fixture.

## Transpiler CLI

```
simse_transpile <input.simse>... -o <output.cpp> [--prelude <file>]
```

With no input arguments it discovers every `.simse` under the current directory
(recursively, sorted). `--prelude` overrides the default RTL prelude **set**
(`cppsrc/rtl/`, a directory whose `*.simse` files are all loaded); a missing
default is skipped silently. Errors are
written to stderr as `<file>:<line>:<col>: <message>` and the process exits
nonzero. Run it from the repository root so the generated
`#include "cppsrc/rtl/simse.hpp"` resolves.
