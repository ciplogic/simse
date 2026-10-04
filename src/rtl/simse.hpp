#pragma once

// simse.hpp is the root of the Simse runtime library (RTL). It is the umbrella
// header that pulls in every runtime type. Translation units may include
// simse.hpp directly, or include only the narrower topic header they need.
// Each topic header is self-contained (owns its includes and #pragma once).
//
// What a program's *generated* C++ needs is not necessarily here: the primitives the
// prelude operations reach live in `src/rtl/_res.md` (the `strtable`, `timeops`,
// `listops`, `dictops`, `strops` and `resfmt` sections), which the emitter places
// in the amalgamation's own sections - a program that reaches one of them carries its
// prototype and its definition, and has nothing to link (`impl_specs/generators.md`).
// A module owns the same way; the `streams` module's `FileStream` header is placed by
// that module's `filestreamhpp` section, not by this umbrella (src/modules/streams/_res.md).

// Order follows dependencies: types and containers have no RTL-relative
// dependencies, and the higher-level headers build on them.
#include "types.hpp"        // scalar aliases, Str, the Opt/Res/Span forward declarations
#include "intrinsics.hpp"   // the byte primitives (memcpy/memcmp/...): the machine, in C++
#include "ref.hpp"          // Ref<T> (`&T`): SmRef, or the std::shared_ptr shim
#include "containers.hpp"   // SmallVector, List, PList, Dictionary, Array, RawArray
#include "functional.hpp"   // Func, Action, AutoDefer
// `Span<T>` is *generated* (src/rtl/Span.kt): the emitter writes the struct into the
// program's `types` section, after this header; above, only its declaration. `StrView` is
// the language's alias for `Span<Char>` (types.hpp carries the `using` for hand-written C++,
// and the emitter spells the target in generated code - `type` resolves a non-generic
// typealias). Its operations are prelude functions, the byte-boundary primitives live in
// smstring.hpp and the converting constructor is the `strconv` section (src/rtl/_res.md).
// The compiler's AST (`AstXmlNode`, `AstNodeKind`, ...) is *generated*: it is declared in
// src/modules/compiler/astxml.kt and the emitter writes its structs and enums
// (specs/attributes.md's type materialization), so there is no header to include for it any
// more. The language-level `XmlNode`/`Attribute` live in the `xml` module
// (src/modules/xml/api.kt) and are generated the same way; nothing in the `rtl` prelude
// declares them.