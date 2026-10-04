# agents.md — start here

Simse is a small, statically typed language (Kotlin/.NET influenced) that transpiles to a
single amalgamated C++20 file. The compiler is written **in Simse** (`src/**/*.kt`); the only
hand-written C++ is the runtime (`src/rtl/*.hpp`) plus the published bootstrap
(`src/simse_bootstrap.cpp`). Everything else a program needs is generated from `_res.md`
resource sections.

Read the one file that matches the task, then the design doc it points at:

| Task | Read |
| --- | --- |
| write or review Simse code | [`ai/language.md`](ai/language.md) |
| build, run, test, debug | [`ai/building.md`](ai/building.md) |
| what works, what is deferred | [`ai/status.md`](ai/status.md) |
| change the compiler | [`ai/contributing.md`](ai/contributing.md) |
| per-subsystem design | [`impl_specs/`](impl_specs/) — start at `transpilation.md`; [`specs/`](specs/) is the normative language |
| history: what moved and what it cost | [`impl_specs/capability-matrix.md`](impl_specs/capability-matrix.md) |

The loop that verifies any compiler change, and the only one that counts:

```sh
./build.bat --release && bun tools/stress.js && bun tools/bootstrap.js
```

A passing loop is the point to commit it and push: one commit per validated change, in the
style `ai/contributing.md` describes. A change that is believed better does not wait for a
review request.

References to the old numbered sections of this file map as: §1/§3/§4 → this file and
`impl_specs/`, §2 → `ai/building.md`, §5/§6 → `ai/contributing.md`, §7 → `ai/language.md`,
§8 → `ai/status.md`, §9 → `ai/contributing.md` (gotchas).
