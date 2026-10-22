# Tests

Status: implemented - `@Test`, the `--tests` build, the generated suite.

## Marking

A test is a function marked with the `@Test` **marker attribute**
(`specs/attributes.md`, "Marker attributes"):

```kt
package tests
import app

@Test fun twice_doubles(): Unit {
    assert(twice(2) == 4, "twice(2) should be 4")
}
```

A marked function is an ordinary function; a build without `--tests` ignores the mark
(the function is compiled and never called). The suite can call a plain function, so the
shape is required and one diagnostic names whichever rule broke ("an `@Test` function is
a plain Unit function with no parameters"): no receiver, no type parameters, no
parameters, `Unit` back, not `suspend`.

## The `--tests` build

`--tests` compiles the program into a test binary. The driver walks the parsed program
modules (the prelude carries no tests) *before* sema, collects the marked functions, and
generates one suite module (`<generated>/tests.kt`, package `rtl`) that is checked and
emitted like any module the program declares:

- **The suite's `main` replaces the program's own.** Every program-module `main` - the
  emitter picks the entry point by that name alone (`specs/modules.md`) - is dropped from
  the tree; a second `main` beside the suite's would be a C++ redefinition. Nothing else
  should call `main` by name.
- The suite imports each test's package once and calls the bare name. Two packages may
  not share a test's name - one `import` cannot disambiguate two declarations of one
  name - so the second is a diagnostic ("duplicate test name ...").
- `--tests` with no marked function among the compiled modules is a diagnostic, not an
  empty pass: a suite with no tests would answer "passed" to a project whose test roots
  were never compiled in.
- **Order is deterministic**: modules in the driver's canonical-path order (the same
  order every compiled file takes), declarations in source order.

## The suite

The suite prints a `running <package>.<name>` line ahead of each call, then the call;
after the last test it prints `<N> tests passed` and exits 0. A failure inside a test is
the fatal path (`specs/functions.md`, "Fatal errors"): `assert` writes
`panic: assert failed: <message>` to stderr, flushes stdout - which is what makes the
`running` line of the failing test visible - and ends the process. Nothing unwinds, no
destructors run, later tests are not run, and the exit code is 3 (a `std::_Exit`, not
the CRT's `abort` - whose fault report is a dialog box on Windows). The line ahead of
each call is what says which test died; there is no per-test isolation in this baseline.

## Where tests live

The compiler takes test files from whatever roots it is given, so a project keeps its
tests out of normal builds by structure: a `test/` directory named as a module only in
the test invocation (`--root src --module test --tests`), or a `simse.md` manifest whose
`module:` entries exclude it (`specs/simse-md.md`). The `dx` tool's `test` command makes
the convention: `dx test` adds `<root>/test` as a module and passes `--tests`.
