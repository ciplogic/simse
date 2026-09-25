# Getting started

## What you need

| Requirement | Why |
| --- | --- |
| Windows (10/11) | the toolchain and the checked-in build scripts are Windows/MSVC today; Linux and macOS are on the [roadmap](../impl_specs/user-language-roadmap.md) |
| Visual Studio with the **Desktop development with C++** workload | `cl.exe`, the C++ standard library, and the linker the compiler's output is built with |
| [bun](https://bun.sh) | runs the build and harness scripts (`build.js`, `tools/stress.js`, `tools/bootstrap.js`) |

`build.bat` and `stress.bat` check for `bun` themselves and print a hint if it is
missing. Visual Studio is located through `vswhere`, so any recent installation
works; a `cl.exe` already on your `PATH` is used only as a fallback.

## 1. Build the compiler

`build.js` transpiles the compiler's own Simse sources (`cppsrc/**/*.kt`) into
one `simse_out.cpp` and compiles that one file into `./simse.exe`. It uses
`./simse.exe` as the transpiler when one exists; on a fresh checkout there is
none yet, so it first compiles the published `cppsrc/simse_bootstrap.cpp` and
uses that (section 2).

```bat
build.bat                                   :: debug: ./simse_out.cpp -> ./simse.exe
build.bat my_compiler.exe                   :: ... with another executable name
build.bat --release                         :: /O2 /Ob3 /DNDEBUG + /GL
build.bat --release --no-lto                :: ... without whole-program optimization
build.bat --release --pdb                   :: optimized code + a .pdb for the profiler
build.bat --help                            :: all options
build.bat --cpp other.cpp --exe other.exe   :: compile an existing amalgamation
```

`build.bat` is a one-line launcher for `bun build.js`, which also finds Visual
Studio through `tools/msvc.mjs` and reports what it is doing. `--define` reaches
the whole translation unit - the amalgamation and the RTL C++ it carries are
compiled together - so the `SIMSE_STR_INLINE_CAPACITY` / `SIMSE_NO_PACK4` knobs
apply consistently. `--profile` transpiles with the instrumented profiler
(`impl_specs/profiling.md`). The same script can transpile any other module
root:

```bat
bun build.js --release --root my_project --out my_project.cpp --exe my_project.exe
```

Objects and intermediates go to `build/<debug|release>/`; the generated file
includes `"cppsrc/rtl/simse.hpp"`, so the repository root is the include path.

## 2. Build the compiler *without* a compiler

The amalgamation is checked in as **`cppsrc/simse_bootstrap.cpp`**: the compiler
source tree as one C++ file, kept in the repository so that Simse can be built by
someone who has a C++ compiler and nothing else - no `simse.exe`, no previous
build, no build script. That is what makes the language self-hosting rather than
a claims file.

```bat
cl /nologo /std:c++20 /EHsc /O2 /Ob3 /DNDEBUG /MD /W3 /I. ^
   cppsrc/simse_bootstrap.cpp ^
   /Fe:simse.exe
```

One translation unit - the amalgamated compiler - plus the RTL headers under
`cppsrc/rtl/` that the amalgamation includes. The generated symbols it uses
(`simse_native_readFile`, `simse_listFiles`, `simse_writeFile`,
`simse_pathCanonical`, `simse_eprintln`, `simse_nowMillis`, ...) are not linked
in from a second file: their C++ lives in the RTL's resource sections
(`cppsrc/rtl/_res.md`, the `fileio` and `timeops` sections), which are emitted
into every program's translation unit, so the published bootstrap already carries
them. The result is a working compiler. Run it from the repository root:
`simse.exe --root my_project -o my_project.cpp` - the RTL sits at the default
prelude path `cppsrc/rtl`.

To refresh the published file after changing the compiler (it is generated, never
hand-edited):

```bat
bun build.js --release --out cppsrc/simse_bootstrap.cpp   :: also builds ./simse.exe
```

`bun tools/bootstrap.js` measures the whole story and checks the fixed point - the
compiler built from the published file must reproduce that file byte for byte:

```console
$ bun tools/bootstrap.js
bootstrap: release build, 3 run(s) for the transpiles
  sources    17905 lines of Simse under cppsrc
  bootstrap  cppsrc\simse_bootstrap.cpp: 45757 lines, 1.38 MB (checked in, do not edit)

1. compile the published bootstrap (cl.exe only, no build system)
  cppsrc\simse_bootstrap.cpp -> simse_boot.exe         best 20713 ms

2. transpile the compiler's own source tree
  simse_boot.exe (just built) --root cppsrc            best 1093 ms, median 1122 ms
  working compiler (simse.exe) --root cppsrc           best 1042 ms, median 1089 ms

3. fixed point: both outputs must equal the published bootstrap
  simse_boot.exe's output == the bootstrap             yes, byte for byte
  the working compiler's output == the bootstrap       yes, byte for byte

  from the published file to a working compiler: 20.71 s
  and that compiler reproduces itself in:        1093 ms
  full cycle (compile + self-transpile):         21.81 s
  throughput: 16385 lines/s of Simse (41872 lines/s of C++ out)
  the working compiler transpiles the same tree in: 1042 ms
```

So: **~21 s from the published file to a compiler that reproduces it**, almost
all of it `cl.exe` optimizing 45.7k lines of generated C++; the compiler's own
share - transpiling its whole source tree - is **~1.1 s** (the machine's load
moves it between 1.0 s and 3.0 s).

## 3. Compile and run a program

Using the self-hosted compiler on one of the bundled examples:

```bat
simse.exe --root docs/examples/hello -o hello.cpp
build.bat --cpp hello.cpp --exe hello.exe
hello.exe
:: hello, simse
```

The first command transpiles every `.kt` file under the given module root (the
`--root` directory) and writes one amalgamated C++ file; the second compiles that
one file into the executable - the runtime is already in it, emitted from the
RTL's resource sections. `simse.exe` with no arguments scans the current
directory, which is rarely what you want here: the repository holds several
independent programs - the prelude, the examples, the stress cases - that are not
one module.

The compiler's own CLI is:

```
simse.exe --root <dir> -o <out.cpp> [--prelude <dir>] [--profile] [--no-concat] [--when-first-char] [--showLinearRepresentation]
```

- `--root <dir>` scans a directory tree for `.kt` files (the compiler's own
  source tree is `cppsrc`);
- `--prelude <dir>` overrides the implicit prelude, which defaults to the
  relative path `cppsrc/rtl`, so run the compiler from the repository root;
- `--profile` emits the instrumented profiler;
  `--showLinearRepresentation` dumps the linear IL (see
  `impl_specs/profiling.md` and `impl_specs/linear-il.md`).

## 4. Run the stress corpus

```bat
stress.bat                                  :: transpile, compile and run the 45 stress programs
stress.bat --list                           :: what the corpus contains
stress.bat --filter strings                 :: one case
stress.bat --release                        :: compile the case programs with /O2 /Ob3
bun tools\bootstrap.js                      :: time the bootstrap and check its fixed point
```

The stress harness (see [`stress/README.md`](../stress/README.md)) transpiles,
compiles and runs every program under `stress/` with the compiler under test and
compares stdout. The corpus is the end-to-end safety net: it is what catches a
codegen change that "works" but prints the wrong thing.

`stress.js` takes `--define` to pass preprocessor defines through, which is how
the runtime knobs are exercised (for example
`--define SIMSE_STR_INLINE_CAPACITY=16` or `--define SIMSE_NO_PACK4`, see
[how-it-works.md](how-it-works.md)).

## Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| `LNK1168: cannot open ... for writing` | a `simse*.exe` you ran earlier is still alive; close it (or kill it) and link again |
| `LNK4272: machine type 'ARM64' conflicts with target machine type 'x86'` followed by many `LNK2019` | your `cl.exe` targets a different architecture than the build. Use a developer prompt with the matching architecture (`vcvarsall arm64` for ARM64) or pass `--arch` to `build.js` |
| a dialog box about `stream.valid()` or a debug-STL assert | the Debug build has the MSVC debug assertions live, so bad input (e.g. passing a directory where a `.kt` file is expected) trips them. Use `--release` for a build with the assertions compiled out |
| `transpile failed` while scanning `.` | you are compiling the whole repository: the prelude, the examples and the stress cases are independent programs, not one module. Pass `--root <dir>` |
| `build: warning: simse.exe is older than cppsrc/rtl` | the compiler predates a prelude change; `build.bat` rebuilds it and replaces the stale compiler |
| `bun: command not found` | install bun from <https://bun.sh> (the harnesses are JavaScript) |
