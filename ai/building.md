# Building, running, testing

Toolchain: MSVC (arm64) + `cl.exe`, C++20, and the `bun` runtime for the harnesses. There is
no build system to configure: the compiler is Simse, the only hand-written C++ is the runtime
headers (`src/rtl/*.hpp`) and the published bootstrap (`src/simse_bootstrap.cpp`), and
everything else a program needs is generated into its own translation unit from `_res.md`
files — so a build is one `cl.exe` invocation over one file, with nothing to link.

## The commands

```sh
# build (from the repo root): src -> ./simse_out.cpp -> ./simse.exe
./build.bat                              # debug (/MDd)
./build.bat --release                    # /O2 /Ob3 /DNDEBUG + /GL (LTCG at link; the default)
./build.bat --release --no-lto           # skip whole-program optimization: quicker to build
./build.bat --release --pdb               # + /Zi /DEBUG (a .pdb in build/), for a profiler
./build.bat my_simse.exe                 # same, different executable name
./build.bat --cpp other.cpp --exe x.exe  # compile an existing amalgamation
./build.bat --help                       # all options (see build.js)

# the published bootstrap: the same amalgamation, checked in so the compiler can be built
# with a C++ compiler alone (docs/getting-started.md). Refresh it whenever emitted C++ moves
# (the file is generated, never hand-edited):
bun build.js --release --out src/simse_bootstrap.cpp   # also builds ./simse.exe

# does the fixed point hold? compile the bootstrap, transpile, compare the bytes
bun tools/bootstrap.js                   # add --debug for the debug flags

# the end-to-end corpus: one folder per program under stress/, each with its expected output;
# the harness transpiles, compiles and runs every one with the compiler under test
bun tools/stress.js                      # or ./stress.bat; the format is stress/README.md
bun tools/stress.js --list               # what the corpus contains
bun tools/stress.js --filter modules --jobs 4
bun tools/stress.js --simse ./other.exe  # test another compiler build

# the compiler by hand (the compiler *is* the CLI; --prelude defaults to src/rtl, so run it
# from the repo root)
./simse.exe --root src -o simse_out.cpp             # the whole compiler
./simse.exe --root stress/strings/src -o strings.cpp
./simse.exe --root my/src --module src/modules/json -o out.cpp   # --module repeats
```

The verification loop after a compiler change: `./build.bat --release`, `bun tools/stress.js`,
`bun tools/bootstrap.js`. The two-step property is that the compiler built from the published
bootstrap must reproduce that file byte for byte — the check that catches emitted C++ which
depends on which compiler emitted it. When the change is visible in the emitted C++, refresh
the published file and commit it with the source.

## Debug views (all print to stderr, C++ output unchanged)

```sh
./simse.exe --root src -o a.cpp --showLinearRepresentation 2> il.txt   # the IL of every body
./simse.exe --root examples/async/src --module src/modules/io --showAsync   # suspension coloring
./simse.exe --root src -o a.cpp --showBorrow 2> borrow.txt             # per-candidate borrow decisions (prelude first)
./simse.exe --root stress/concat/src -o fused.cpp                      # the concat fusion (on)
./simse.exe --root stress/concat/src -o unfused.cpp --no-concat        # ... and its A/B off
./simse.exe --root src -o unborrowed.cpp --no-borrow                    # the auto-borrow rewrite off (params and for)
./simse.exe --root stress/when-strings/src -o when.cpp                  # the when-over-strings lowering
./simse.exe --root src -o prof.cpp --profile                            # RAII timers (simse_profile.txt)
```

## Troubleshooting

- **If `simse.exe` is running, linking it again fails with `LNK1168`** — kill it first.
- An **interrupted** build is worse than a running one: `cl` writes the executable where it is
  asked to, so a build killed mid-link leaves a truncated `simse.exe`, and the next build fails
  with `bun: unknown error:` (or `Exec format error`) because the transpiler is not a program.
  The way back is the published bootstrap:
  `./build.bat --release --cpp src/simse_bootstrap.cpp --exe simse.exe`.
- A compiler change the *running* compiler cannot apply to its own sources is the **two-build
  rule** (`ai/contributing.md`).
- The same bootstrap builds with GCC 12+ / Clang 15+ on Linux (`docs/building-on-linux.md`);
  the harness scripts drive `cl.exe`.

## Repository layout

- `src/` — the compiler, all Simse: `lex/`, `parser/`, `sema/`, `linear/`, `codegen/`,
  `compiler/` (the source generators), `optimizations/`, `profiling/`, `resources/`,
  `common/`, plus `src/modules/` (reusable modules: `json`, `compiler`, `xml`) and `src/rtl/`
  (the prelude `.kt` files, the hand-written headers, and `_res.md`, which holds the runtime's
  generated C++).
- `specs/` — the normative language specification. `impl_specs/` — per-subsystem design and
  the change log (`capability-matrix.md`).
- `stress/` — the end-to-end corpus (`stress/README.md`). `examples/` — the runnable examples.
- `tools/` — the JavaScript harness (`stress.js`, `bootstrap.js`, `vscheck.mjs`, `msvc.mjs`);
  the remaining `_*.mjs`/probe files are scratch and not part of any workflow.
- `docs/` — the published documentation (tour, how-it-works, state-of-the-field).

## Profiling

`bun build.js --release --profile` builds an instrumented compiler (or pass `--profile` on the
CLI to any program): every emitted body carries an RAII timer and the program writes a CSV of
inclusive totals and call counts on exit (`--profile-file`, default `simse_profile.txt`;
`--profile-nanos`). With the flag off the emitted file is byte-identical. `impl_specs/profiling.md`
has the format; `build.bat --release --pdb` feeds the VS sampling profiler instead.
