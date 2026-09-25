# stress/ - the end-to-end stress harness

Every folder here is one small Simse **project** plus what it must do. The
harness in `tools/stress.js` walks the folders, transpiles each one with the
Simse compiler, compiles the generated C++ with `cl.exe`, runs it, and compares
the result to the folder's expectations:

```sh
bun tools/stress.js                     # every case, with ./simse.exe
bun tools/stress.js --filter strings    # one case (or a few: repeat --filter)
bun tools/stress.js --list              # what is here, and what each case checks
bun tools/stress.js --release           # compile the programs with /O2 /Ob3
bun tools/stress.js --jobs 4            # cases at a time (compiles are slow)
bun tools/stress.js --simse ./some_other_simse.exe   # test another compiler build
```

The compiler under test is the point: by default the harness runs `./simse.exe`, the
compiler built from the published bootstrap (`bun build.js`).

## A case

```
stress/<name>/
  src/                      the project sources; the transpiler runs with --root src
  expected.stdout           what the program prints (the one required file)
  expected.stderr           optional: what it writes to stderr
  expected.exit             optional: its exit code (default 0)
  expected.cpp              optional: the emitted C++ must match byte for byte
  expected.transpile-error  optional: the transpile must fail and its stderr must
                            contain this text; nothing is compiled or run
  args                      optional: one line of arguments for the program
  compiler-args             optional: one line of extra arguments for the transpiler,
                            after `--root src -o out.cpp` (a `--module <dir>`, say)
  stdin                     optional: fed to the program
```

A case with none of the `expected.*` files is an *unfinished* case, not a
passing one: the harness fails it and tells you to capture the output with
`--update`. `--update` writes the expectations from a run — use it after
reading what the program printed, never as a way to make a red case green.

`expected.cpp` is the emission golden: it pins the generated C++ for a program,
which is how the old fixture goldens pinned it. `stress/objects` and
`stress/machines` carry one each (they replaced the cases that pinned the emitted
shapes); drop one into any case by copying `stress/.work/<name>/out.cpp` after the
case passes.

## Categories

Most cases are one *category* of the language, merged into one project: a single
`src/main.kt` holding every case it replaced as a `part<Name>` function, called in
order by the file's own `main`, with a `// ---- <case> ----` marker per part.
`--filter collections` runs the collections category the way `--filter dictionary`
used to run the part of it that was a case.

```
stress/<category>/
  src/main.kt   `// ---- <case> ----` per merged case, then `main` calling each part
  expected.stdout
  expected.cpp  where the merged cases pinned the emitted C++
```

A case is a project, and what a project costs is `cl.exe`: the transpile is ~15 ms,
the C++ compile ~1 s. Merging 34 single-program cases into 5 took the whole suite
from **52 s to 24 s** (33 cases, serial). A part's body is verbatim from the case it
replaced, and the merged program's stdout is the concatenation in marker order.

What stays one folder per case, and why:

- `diagnostic-*`: the transpile *must* fail, and a real error is the file the error
  is in - two failures in one file can only ever be one case.
- The build-configuration cases - `main-args`, `native-read-file`, `read-lines`,
  `modules`, `qualified-names`, `manifest-modules`, `statics`, `resources*`,
  `smgen-*`: each is a different *project* (its own `simse.md` manifest, `_res.md`,
  `args` or data file), not just a different program, so there is nothing to merge.

## Why folders

* **One folder is one project**: multi-file and multi-package programs are just
  more files under `src/` (`stress/modules` has two packages and an `import`).
* **A failure is reproducible by hand**: the work directory holds everything the
  run produced, so `stress/.work/<name>/prog.exe` can be run again, and
  `out.cpp` compiled with any flags.
* **Adding a case is adding a folder** - no build system edit, no build target,
  no C++ driver.
* The harness is JavaScript on purpose: the compiler is the artifact under test,
  and everything around it that can be written in a scripted host language keeps
  the C++ surface down to the runtime (`cppsrc/rtl`, `cppsrc/native`) plus the
  bootstrap ring, which is on its way out (see `impl_specs/roadmap.md`).

## The work directory

`stress/.work/<name>/` holds `out.cpp`, `prog.exe`, and the run's `actual.*`
files. It is ignored by git and rebuilt on every run (the shared objects for the
native translation units are cached per flag set, which is what makes repeated
runs fast; the cache is invalidated by a newer `cppsrc/**` header too, because
`Str`/`List`/`Array` are header-defined and mixing two versions of them in one
binary is an ODR violation).
