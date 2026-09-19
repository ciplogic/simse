#pragma once

// simse.hpp is the root of the Simse runtime library (RTL). It is the umbrella
// header that pulls in every runtime type. Translation units may include
// simse.hpp directly, or include only the narrower topic header they need.
// Each topic header is self-contained (owns its includes and #pragma once).
//
// What a program's *generated* C++ needs is not necessarily here: the primitives the
// prelude operations reach live in `cppsrc/rtl/_res.md` (the `strtable`, `timeops`,
// `listops`, `dictops` and `strops` sections), which the emitter places in the
// amalgamation's own sections, and `native.cpp` does not use them (`impl_specs/generators.md`).

// Order follows dependencies: types and containers have no RTL-relative
// dependencies, and the higher-level headers build on them.
#include "types.hpp"        // scalar aliases and Str
#include "ref.hpp"          // Ref<T> (`&T`): SmRef, or the std::shared_ptr shim
#include "containers.hpp"   // SmallVector, List, PList, Dictionary, Array, RawArray
#include "span.hpp"         // Span<T> (borrowed view: pointer + length)
#include "strview.hpp"      // StrView (Span<Char> + the text operations)
#include "resources.hpp"    // the `_res.md` resources a program carries
#include "optional.hpp"     // Opt<T>
#include "fs.hpp"           // simse_listFiles/simse_writeFile/... native ops (prelude)
#include "filestream.hpp"   // FileStream: simse_fileStream_* native ops (prelude)
#include "functional.hpp"   // Func, Action, AutoDefer
#include "result.hpp"       // Result/Res, ok, resError, err
#include "xml.hpp"          // Attribute, XmlNode (the general tree a program builds)
#include "astxml.hpp"       // AstNodeKind, AstNodeAttributeKind, AstXmlNode (the compiler's AST)