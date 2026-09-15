#pragma once

#include "../rtl/simse.hpp"

// Hand-written native functions callable from Simse via `native fun`
// (impl_specs/native-interop.md). The symbol is a plain global identifier and
// parameters are borrowed as `const T&`, matching the declarations the emitter
// generates at the top of an amalgamated file.
//
// `simse_native_readFile` is declared in cppsrc/common/common.kt (non-prelude,
// so the emitter emits its prototype); the filesystem/IO set below is declared in
// the prelude (cppsrc/rtl/fs.kt) with prototypes in cppsrc/rtl/fs.hpp.

Str simse_native_readFile(const Str& path);
