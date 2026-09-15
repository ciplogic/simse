# Getting started

## What you need

| Requirement | Why |
| --- | --- |
| Windows (10/11) | the toolchain and the checked-in build scripts are Windows/MSVC today; Linux and macOS are on the [roadmap](../impl_specs/user-language-roadmap.md) |
| Visual Studio with the **Desktop development with C++** workload | `cl.exe`, the C++ standard library, and the linker the compiler's output is built with |
| CMake and Ninja | they build the hand-written compiler, its tests, and the runtime libraries that generated programs link against |
| [bun](https://bun.sh) | runs the build and test harness (`build.js`, `tools/stress.js`, `tools/bootstrap.js`) |

`build.bat` and `stress.bat` check for `bun` themselves and print a hint if it is
missing. Visual Studio is located through `vswhere`, so any recent installation
works; a `cl.exe` already on your `PATH` is used only as a fallback.

## 1. Build the bootstrap compiler

This builds the hand-written C++ implementation of the compiler plus the runtime
libraries (`simse_lib.lib`, `simse_native.lib`) that every generated program
links against. Do it once after cloning:

```bat
cmake -S . -B cmake-build-debug -G Ninja -DCMAKE_BUILD_TYPE=Debug
cmake --build cmake-build-debug
```

Build it from a Visual Studio developer prompt (`vcvarsall x64` or
`vcvarsall arm64`), or point CMake at the compiler explicitly with
`-DCMAKE_CXX_COMPILER=<path to cl.exe>`; Ninja needs a working `cl.exe` on the
`PATH` at configure time.

What you get in `cmake-build-debug/`:

| Program | What it is |
| --- | --- |
| `simse_transpile.exe` | the C++ compiler: `.kt` sources in, one `.cpp` out |
| `simse_tests.exe` | the in-process test suite (fixtures, goldens, stage checks) |
| `simse_lib.lib`, `simse_native.lib` | the runtime the generated programs link against |

The build also runs, as part of `ALL`, the five differential tests (hand-written
vs transpiled scanner, skeleton parser, parser, sema, codegen) and the two-step
bootstrap check; if any of them fails, the build fails.

> The `cmake-build-*/` folders in the repository are the author's own, with
> absolute paths from his machine. Reconfigure your own as above, or edit the
> `_msvc_configure.bat` / `_msvc_build.bat` inside one of them.

## 2. Build the self-hosted compiler

This is the interesting one: `build.js` asks `simse_transpile.exe` to transpile
the compiler's own Simse sources (`cppsrc/**/*.kt`) into a single
`simse_out.cpp`, then compiles that into `simse.exe`.

```bat
build.bat                                   :: debug: ./simse_out.cpp -> ./simse.exe
build.bat my_compiler.exe                   :: ... with another executable name
build.bat --release                         :: /O2 /Ob3 /DNDEBUG (needs cmake-build-release)
build.bat --help                            :: all options
build.bat --cpp other.cpp --exe other.exe   :: compile an existing amalgamation
```

`build.bat` is a one-line launcher for `bun build.js`, which also finds Visual
Studio, mirrors the CMake configuration (the `List`/`Str`/`Dictionary` backing
choices must match between the libraries and the amalgamation), and reports what
it is doing. The same script can transpile any other module root:

```bat
bun build.js --release --root my_project --out my_project.cpp --exe my_project.exe
```

## 3. Build the compiler *without* a compiler

The amalgamation is checked in as **`cppsrc/simse_bootstrap.cpp`**: it is the same
file `simse.exe` writes by default (as `simse_out.cpp`), kept in the repository so
that Simse can be built by someone who has a C++ compiler and nothing else - no
`simse.exe`, no previous build, no CMake. That is what makes the language
self-hosting rather than a claims file.

```bat
cl /nologo /std:c++20 /EHsc /O2 /Ob3 /DNDEBUG /MD /I. ^
   /DSIMSE_DEFAULT_PRELUDE="%CD%\cppsrc\rtl" /DSIMSE_SOURCE_ROOT="%CD%" ^
   cppsrc/simse_bootstrap.cpp cppsrc/native/Native.cpp cppsrc/common/common.cpp ^
   /Fe:simse.exe
```

Three translation units - the amalgamated compiler, the `native(...)`
implementations, and the shared common library - plus the RTL headers under
`cppsrc/rtl/` that the amalgamation includes. All three are required: the
amalgamation alone leaves the seven filesystem/`eprintln` natives unresolved
(`simse_listFiles`, `simse_writeFile`, `simse_pathCanonical`, `simse_pathIsDirectory`,
`simse_pathExists`, `simse_eprintln`, `simse_native_readFile`), and the natives alone
leave the two helpers `common.cpp` provides. The result is a working compiler:
`simse.exe --root my_project -o my_project.cpp`.

To refresh the published file after changing the compiler (it is generated, never
hand-edited):

```bat
bun build.js --release --out cppsrc/simse_bootstrap.cpp   :: also builds ./simse.exe
```

`bun tools/bootstrap.js` measures the whole story and checks the fixed point - the
compiler built from the published file must reproduce that file byte for byte:

```console
$ bun tools/bootstrap.js
bootstrap: release build, 5 run(s) for the transpiles
  sources    14159 lines of Simse under cppsrc
  bootstrap  cppsrc\simse_bootstrap.cpp: 35715 lines, 1.07 MB (checked in, do not edit)

1. transpile the compiler's own source tree
  self-hosted compiler (simse.exe) --root cppsrc       best 824 ms, median 847 ms
  hand-written C++ ring (simse_transpile.exe) ...      best 177 ms, median 182 ms

2. compile the published bootstrap (cl.exe only, no CMake libraries)
  bootstrap + Native.cpp + common.cpp -> simse_boot.exe best 14529 ms

3. fixed point: the compiled bootstrap transpiles cppsrc again
  simse_boot.exe --root cppsrc                         best 822 ms, median 829 ms
  output == cppsrc/simse_bootstrap.cpp                 yes, byte for byte

  from the published file to a working compiler: 14.53 s
  and that compiler reproduces itself in:        822 ms
  full cycle (compile + self-transpile):         15.35 s
  throughput: 17219 lines/s of Simse (43433 lines/s of C++ out)
```

So: **~15.4 s from the published file to a compiler that reproduces it**, of which
14.5 s is `cl.exe` optimizing 36k lines of generated C++; the compiler's own share
of that - transpiling its whole source tree - is **~0.82 s** (and the machine's load
moves it between 0.8 s and 3.0 s). A debug build of the same file takes ~3.1 s to
compile and then runs the transpile in ~6.6 s.

## 4. Compile and run a program

Using the self-hosted compiler on one of the bundled examples:

```bat
simse.exe --root docs/examples/hello -o hello.cpp
build.bat --cpp hello.cpp --exe hello.exe
hello.exe
:: hello, simse
```

The first command transpiles every `.kt` file under the given module root (the
`--root` directory) and writes one amalgamated C++ file; the second compiles and
links it against the runtime libraries. `simse.exe` with no arguments scans the
current directory, which is rarely what you want here: it also picks up
`tests/fixtures/`, which intentionally contains broken programs.

The compiler's own CLI is documented by `simse.exe --help`:

```
simse_transpile <input.kt>... [-o <output.cpp>] [--prelude <file>] [--root <dir>] [--module-root <dir>]...
```

- repeating `--module-root <dir>` adds module roots that are scanned for files
  but not built as inputs;
- `--prelude <file>` overrides the implicit `rtl` prelude (normally
  `cppsrc/rtl/*.kt`);
- files can also be listed explicitly instead of scanning a root.

## 5. Run the tests

```bat
cmake-build-debug\simse_tests.exe           :: 53 tests: fixtures, goldens, round-trips
cmake-build-debug\simse_tests.exe --update  :: regenerate the goldens (deliberate changes only)
stress.bat                                  :: transpile, compile and run the 29 stress programs
stress.bat --list                           :: what the corpus contains
stress.bat --filter strings                 :: one case
stress.bat --simse cmake-build-debug\simse_transpile.exe
bun tools\bootstrap.js                      :: time the bootstrap and check its fixed point
```

`simse_tests.exe` compares fixtures against `tests/golden/*.expected`; the stress
harness (see [`stress/README.md`](../stress/README.md)) transpiles, compiles and
runs every program under `stress/` with the compiler under test and compares
stdout. The corpus is the end-to-end safety net: it is what catches a codegen
change that "works" but prints the wrong thing.

Both harnesses take `--define` to pass preprocessor defines through, which is how
the alternative runtime backings are exercised (for example
`--define SIMSE_STR_STD_STRING` or `--define SIMSE_DICT_SM`, see
[how-it-works.md](how-it-works.md)).

## Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| `LNK1168: cannot open ... for writing` | a `simse*.exe` you ran earlier is still alive; close it (or kill it) and link again |
| `LNK4272: machine type 'ARM64' conflicts with target machine type 'x86'` followed by many `LNK2019` | your `cl.exe` targets a different architecture than the CMake libraries. Use a developer prompt with the matching architecture (`vcvarsall arm64` for the ARM64 builds) or reconfigure CMake |
| a dialog box about `stream.valid()` or a debug-STL assert | the Debug build has the MSVC debug assertions live, so bad input (e.g. passing a directory where a `.kt` file is expected) trips them. Use `--release` for a build with the assertions compiled out |
| `transpile failed` while scanning `.` | you are compiling the whole repository, including the intentionally-broken fixtures under `tests/`. Pass `--root <dir>` or explicit file names |
| `build: warning: the CMake build ... is older than the compiler sources` | rebuild the CMake folder; `build.js` is telling you the bootstrap compiler is stale |
| `bun: command not found` | install bun from <https://bun.sh> (the harnesses are JavaScript) |
