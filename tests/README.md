# Golden tests

Golden tests for the front end. Each fixture in `tests/fixtures/` is run through
the scanner, the parser, and the name/type resolver, and three deterministic dumps
are compared against checked-in goldens in `tests/golden/`.

## Build

The tests build with the project. From `simse/cmake-build-debug`:

```
cmd //c _msvc_build.bat
```

This builds `simse_lib`, `simse`, and `simse_tests`.

## Run

```
simse_tests.exe
```

The runner:

- scans, parses, and analyzes each `tests/fixtures/*.simse` fixture in sorted
  filename order, comparing the token, AST, and sema goldens;
- checks the two negative fixtures (`parse_error.simse` must fail to parse,
  `sema_unknown_type.simse` must report an unknown-type diagnostic);
- parses and analyzes every real `.simse` file under `cppsrc/` plus `main.simse`
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

Update mode rewrites every `tests/golden/<fixture>.{tokens,ast,sema}.expected`
and reports `UPDATED` per fixture. Review the diff of the regenerated goldens
before committing. After updating, run once more in check mode to confirm 0
failures.

## Golden categories

For a fixture `foo.simse` the harness writes:

- `foo.simse.tokens.expected` - the scanner token dump;
- `foo.simse.ast.expected` - the AST dump (or an error marker, see below);
- `foo.simse.sema.expected` - the sema diagnostics, one per line (empty if clean).

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

A scan error appends a final line:

```
Error\t<line>:<column>\t<escaped error message>
```

where the position is where the scanner stopped, i.e. the offending character.

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

Because the scanner fixtures and the negative fixtures are not complete programs,
their `.ast.expected` holds a one-line marker instead of a tree:

```
ScanError @<line>:<column> <message>   // the fixture did not scan
ParseError <file>:<line>:<column>: <message>   // the fixture did not parse
```

Their `.sema.expected` is empty (sema only runs on a successfully parsed module).

### Sema dump format

One diagnostic per line, in walk order:

```
<file>:<line>:<column>: <message>
```

An empty file means no diagnostics.
