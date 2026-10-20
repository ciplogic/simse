# The dx toolset (smt)

`dx` is the developer-experience face of the **smt** toolset — one Simse program,
`bin/smt-tool/src/main.kt`, built into `bin/smt.exe` by `smt.bat`. `dx.bat` is a two-line
forwarder, not a second tool: a second CLI would mean a second help text, a second
transpiled program and a second amalgamation to keep checked in, for no new capability.

    dx init [<dir>]           scaffold a project: simse.md + src/main.kt
    dx build                  transpile the project, then compile it
    dx run [-- <args>...]     build the project, then run its program
    dx check                  transpile the project only: the fast front-end check
    dx clean                  remove the build artifacts (out.cpp, out.obj, out.exe)
    dx install [<module>...]  still a stub (see below)

Run from the repository root, where `simse.exe` is and `smt.bat`/`dx.bat` have put `cl` on
the path; a project that lives elsewhere is named with `--root <dir>`. Every command but
`install` is implemented and exercised: `init` refuses to overwrite an existing `simse.md`
or `src/main.kt`, `check` fails with the transpiler's diagnostics, `clean` reports what it
removed and refuses to report success when a file would not go (`out.exe` still running).

## What was complex, and why

**`install` is still a stub, and that is not laziness.** Resolving a project's modules
needs a *universe* to resolve against: a distribution format, a place modules come from, and
a place they are staged. `specs/modules.md` defers exactly that ("external modules are
deferred"), and the manifest (`specs/simse-md.md`) can name only directories that already
exist on disk. A stub `install` that copied a directory beside the project would be a
decision made twice — once in the tool and once, differently, in the spec — so the tool
prints its plan (`dx help install`) and returns a failure instead of half-doing it.

**`clean` needed one new FFI native, and that rippled further than it looks.** The `io`
module had write/create/exists but no delete, so `dx clean` could not stay a Simse program
without one. `deleteFile` went into `modules/io` the same way as its siblings
(`@SmGen("res", "fileio", "simse_deleteFile")`), but the `fileio` section is
`emit: always` — every program carries the section's C++ so a hand-named native resolves —
which means one six-line body moved the compiler's own emitted amalgamation, which moved the
published bootstrap (in the sibling `simse_bootstrap` checkout), which meant the full
fixed-point loop, not just a rebuild. That is the price structure of a module surface change: the *language* cost
is a declaration, the *repo* cost is a bootstrap refresh.

**`check` still writes `out.cpp`.** The compiler's CLI is file-shaped (`-o <file>`); there
is no in-memory transpile a tool can call. So `check` is exactly `build` without the `cl`
step, and it leaves the same artifact behind — which the next `build` overwrites. Making
`check` write nothing would mean a compiler-side flag or a temp file in a fixed place; both
are more surface than the win justifies today.

**The bootstrap check itself broke while verifying this, and the cause was latent.**
`tools/bootstrap.js` compiles the published bootstrap with its own `cl` line, and that line
was missing `/bigobj` — the one flag `build.js` already carries with a comment saying it is
not optional. The amalgamation had been sitting just under the COFF section limit; the seven
lines `deleteFile` added pushed it over and the check failed with `C1128`. A tool-only
change found a harness bug because the harness is the thing every change is verified through.

## A hole this work found (open)

Unresolved method calls on a `Str` are **not diagnosed**: `s.nosuch(1)` transpiles to
`s.nosuch(1)` in C++ and fails in `cl`, not in the compiler. `smt` carried a call to
`s.charAt(i)` — a method that no longer exists (the indexer `s[i]` replaced it; the
*symbol* `simse_str_charAt` kept the old name, `src/rtl/rtl.kt`) — and the checked-in
amalgamation still compiled because it had been emitted before the method was removed. The
only reason the tool caught it is that nothing in the harness rebuilds `bin/smt.exe`, so the
regression sat undetected in the checked-in file. Two things follow, neither done here:

- the compiler should report an unresolved method call instead of emitting it (a sema fix,
  not a tool fix);
- some loop step — the corpus, `iterate.js`, or a `--smt` flag on an existing harness —
  should rebuild and smoke-test `bin/smt.exe` so a regression in the toolset cannot hide in
  its checked-in amalgamation again.
