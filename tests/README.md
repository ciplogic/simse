# Golden tests

Scanner golden tests. Each fixture in `tests/fixtures/` is scanned by the real
scanner and its token stream is compared against a checked-in golden in
`tests/golden/`.

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

The runner scans each `tests/fixtures/*.simse` fixture in sorted filename order
and prints `PASS`/`FAIL` per fixture, a line diff for each failure, and a final
`N passed, M failed` summary. It exits nonzero if any fixture fails.

The fixtures directory defaults to the compile-time `SIMSE_FIXTURES_DIR`. Pass a
directory as the first argument to override it:

```
simse_tests.exe path\to\fixtures
```

## Update goldens

Regenerate the goldens after an intentional scanner change:

```
simse_tests.exe --update
```

or, equivalently:

```
set SIMSE_UPDATE_GOLDENS=1
simse_tests.exe
```

Update mode rewrites every `tests/golden/<fixture>.tokens.expected` and reports
`UPDATED` per fixture. Review the diff of the regenerated goldens before
committing. After updating, run once more in check mode to confirm 0 failures.

## Dump format

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

### Errors

The `unmatched_char.simse` fixture triggers a scanner error. Since the token list
is incomplete in that case, the harness (not the scanner) appends a final line to
the dump:

```
Error\t<line>:<column>\t<escaped error message>
```

where the position is where the scanner stopped, i.e. the offending character.
