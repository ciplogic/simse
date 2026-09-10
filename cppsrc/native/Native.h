#pragma once

#include "../rtl/simse.hpp"

// Hand-written native functions callable from Simse via `native fun`
// (impl_specs/native-interop.md). The symbol is a plain global identifier and
// parameters are borrowed as `const T&`, matching the declarations the emitter
// generates at the top of an amalgamated file.

Str simse_native_readFile(const Str& path);
