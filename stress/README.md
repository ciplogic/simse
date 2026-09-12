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
bun tools/stress.js --simse cmake-build-debug/simse_transpile.exe   # test the other ring
```

The compiler under test is the point of the harness: by default it runs the
self-hosted `./simse.exe` (build it with `bun build.js --release`), falling back
to the CMake build's `simse_transpile.exe` when there is no such binary. Both
rings must produce the same program outputs, so pointing `--simse` at the other
one is a useful check in itself.

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
  stdin                     optional: fed to the program
```

A case with none of the `expected.*` files is an *unfinished* case, not a
passing one: the harness fails it and tells you to capture the output with
`--update`. `--update` writes the expectations from a run — use it after
reading what the program printed, never as a way to make a red case green.

`expected.cpp` is the emission golden: it pins the generated C++ for a program,
which is how the old fixture goldens pinned it. `stress/hello` has one because
it is tiny; drop one into any case by copying `stress/.work/<name>/out.cpp`
after the case passes.

## Why folders

* **One folder is one project**: multi-file and multi-package programs are just
  more files under `src/` (`stress/modules` has two packages and an `import`).
* **A failure is reproducible by hand**: the work directory holds everything the
  run produced, so `stress/.work/<name>/prog.exe` can be run again, and
  `out.cpp` compiled with any flags.
* **Adding a case is adding a folder** - no build system edit, no CMake target,
  no C++ driver.
* The harness is JavaScript on purpose: the compiler is the artifact under test,
  and everything around it that can be written in a scripted host language keeps
  the C++ surface down to the runtime (`cppsrc/rtl`, `cppsrc/native`) plus the
  bootstrap ring, which is on its way out (see `impl_specs/roadmap.md`).

## The work directory

`stress/.work/<name>/` holds `out.cpp`, `prog.exe`, and the run's `actual.*`
files. It is ignored by git and rebuilt on every run (the shared objects for the
native translation units are cached per flag set, which is what makes repeated
runs fast).
