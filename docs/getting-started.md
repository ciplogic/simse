# Getting started

## What you need

| Requirement | Why |
| --- | --- |
| Windows (10/11) | the toolchain and the checked-in build scripts are Windows/MSVC today; Linux and macOS are on the [roadmap](../impl_specs/user-language-roadmap.md) |
| Visual Studio with the **Desktop development with C++** workload | `cl.exe`, the C++ standard library, and the linker the compiler's output is built with |
| CMake and Ninja | they build the hand-written compiler, its tests, and the runtime libraries that generated programs link against |
| [bun](https://bun.sh) | runs the build and test harness (`build.js`, `tools/stress.js`) |

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
| `simse_transpile.exe` | the C++ compiler: `.simse` sources in, one `.cpp` out |
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
the compiler's own Simse sources (`cppsrc/**/*.simse`) into a single
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

## 3. Compile and run a program

Using the self-hosted compiler on one of the bundled examples:

```bat
simse.exe --root docs/examples/hello -o hello.cpp
build.bat --cpp hello.cpp --exe hello.exe
hello.exe
:: hello, simse
```

The first command transpiles every `.simse` file under the given module root (the
`--root` directory) and writes one amalgamated C++ file; the second compiles and
links it against the runtime libraries. `simse.exe` with no arguments scans the
current directory, which is rarely what you want here: it also picks up
`tests/fixtures/`, which intentionally contains broken programs.

The compiler's own CLI is documented by `simse.exe --help`:

```
simse_transpile <input.simse>... [-o <output.cpp>] [--prelude <file>] [--root <dir>] [--module-root <dir>]...
```

- repeating `--module-root <dir>` adds module roots that are scanned for files
  but not built as inputs;
- `--prelude <file>` overrides the implicit `rtl` prelude (normally
  `cppsrc/rtl/*.simse`);
- files can also be listed explicitly instead of scanning a root.

## 4. Run the tests

```bat
cmake-build-debug\simse_tests.exe           :: 50 tests: fixtures, goldens, round-trips
cmake-build-debug\simse_tests.exe --update  :: regenerate the goldens (deliberate changes only)
stress.bat                                  :: transpile, compile and run the 24 stress programs
stress.bat --list                           :: what the corpus contains
stress.bat --filter strings                 :: one case
stress.bat --simse cmake-build-debug\simse_transpile.exe
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
| a dialog box about `stream.valid()` or a debug-STL assert | the Debug build has the MSVC debug assertions live, so bad input (e.g. passing a directory where a `.simse` file is expected) trips them. Use `--release` for a build with the assertions compiled out |
| `transpile failed` while scanning `.` | you are compiling the whole repository, including the intentionally-broken fixtures under `tests/`. Pass `--root <dir>` or explicit file names |
| `build: warning: the CMake build ... is older than the compiler sources` | rebuild the CMake folder; `build.js` is telling you the bootstrap compiler is stale |
| `bun: command not found` | install bun from <https://bun.sh> (the harnesses are JavaScript) |
