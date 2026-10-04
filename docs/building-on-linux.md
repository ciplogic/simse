# Building Simse with GCC/Clang on Linux

The compiler's C++ is standard C++20, so the **bootstrap** — `src/simse_bootstrap.cpp`,
the one translation unit the whole compiler is — builds with a C++ compiler alone. This page
says which compiler, the exact command, and what is still Windows-only. The Windows/MSVC
build is unchanged and remains the reference toolchain (`docs/getting-started.md`).

## Prerequisites

- **GCC 12 or newer** (or Clang 15+). The floor is set by the C++20 features the RTL leans on:
  `constexpr` union members with `std::is_constant_evaluated` (`src/rtl/containers.hpp`,
  `src/rtl/strsmallvector.hpp`), `std::construct_at`/`std::destroy_at`, `std::bit_width`
  (`<bit>`), and integer `std::from_chars` (libstdc++ shipped it in GCC 11). GCC 12 is the
  safe floor for the `constexpr` union work.
- **bun** (https://bun.sh) — the existing harness scripts are JavaScript run by `bun`, but the
  bootstrap itself needs nothing but a C++ compiler.

## Build the compiler from the bootstrap

From the repository root:

```sh
# the bootstrap is one translation unit; the repo root is the include path
g++ -std=c++20 -O2 -DNDEBUG -I. src/simse_bootstrap.cpp -o simse
```

That `simse` is a complete compiler. Check the fixed point (the property that makes the
checked-in file a *bootstrap* and not a snapshot): the compiler it produced must transpile
`src` back into the same bytes.

```sh
./simse --root src -o /tmp/out.cpp
cmp src/simse_bootstrap.cpp /tmp/out.cpp && echo "fixed point holds"
```

## What was made portable

Two places emitted C++ that would not build on a non-MSVC toolchain; both are fixed.

1. **The `native` generator** (`src/compiler/NativeInvokeGen.kt`). `@SmGen("native", ...)`
   emitted a Windows-only loader (`LoadLibraryA`/`GetProcAddress`). It now emits a
   `#ifdef _WIN32` split: `LoadLibraryA`/`GetProcAddress` on Windows, `dlopen`/`dlsym` from
   `<dlfcn.h>` elsewhere. The compiler itself uses no `native` binding, so this only affects
   user programs — but it is what made the emitted `native` text portable.
2. **`Str.toFloat()`** (`src/rtl/_res.md`, the `strops` section). It used
   `std::from_chars` on a `double`, which libstdc++ did not implement until GCC 14. It now
   uses `std::strtod` (with `errno`/`ERANGE` checked), keeping the same "whole string must be a
   number" contract on GCC 12. Integer `toInt` keeps `std::from_chars` (GCC 11+).

## What was already portable (no change needed)

The runtime headers (`src/rtl/*.hpp`, plus the `streams` module's
`src/modules/streams/filestream.hpp`) and the resource text (`src/rtl/_res.md`,
`src/modules/io/_res.md`, `src/modules/streams/_res.md`, `src/modules/json/_res.md`) are plain
standard C++20:
`std::chrono`, `std::filesystem`, `std::bit_width`, `std::type_identity_t`, `std::to_string`.
The one toolchain-dependent construct — 4-byte packing — already branches in
`src/rtl/types.hpp` (`__pragma(pack(...))` on MSVC, `_Pragma("pack(...)")` elsewhere).

## What is still Windows-only (design notes)

These do **not** block building the compiler; they are the remaining portability work, listed
so a design can be made for each.

1. **The tooling scripts.** `build.js`, `tools/stress.js`, `tools/bootstrap.js`,
   `tools/iterate.js`, and
   `tools/msvc.mjs` drive `cl.exe`/`vcvarsall` and the Windows shell (`fc /b`, `dir /b /s`,
   `.exe` names, `.bat`). Porting them is a toolchain-abstraction job (detect `cl` vs
   `g++`/`clang++`, map `/O2 /Ob3 /DNDEBUG` to `-O2 -DNDEBUG`, `/I` to `-I`, `/Fe` to `-o`,
   `fc /b` to `cmp -s` or a byte-compare). The manual `g++` command above is the stand-in.
2. **P/Invoke linking on older glibc.** The `dlopen`/`dlsym` arm needs `-ldl` on glibc before
   2.34 (on 2.34+ they live in `libc`). The compiler's own build is unaffected; a user program
   that uses `@SmGen("native", ...)` would add `-ldl` on those systems.
3. **`examples/http/sockets`** — a `winsock2.h` binding (`ws2_32.dll`). The example is
   inherently Winsock2; a POSIX version is a fresh `_res.md` over `sys/socket.h`.
4. **`examples/sdl2`** — SDL2 headers checked in for Windows ARM64
   (`Lib/3rdparty/SDL2-arm64`). It needs SDL built for the host to run anywhere else.

## Verification (for the next agent, once on a Linux box)

1. Compile the bootstrap with `g++` (above) — this is the step that proves the C++ is
   portable; it needs no `simse.exe` and no build system.
2. Run the fixed-point check (`./simse --root src -o /tmp/out.cpp; cmp ...`).
3. After the tooling scripts are ported: `bun tools/stress.js`, `bun tools/bootstrap.js`, and
   the loop `bun tools/iterate.js`.
