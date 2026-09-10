#pragma once

// simse.hpp is the root of the Simse runtime library (RTL). It is the umbrella
// header that pulls in every runtime type. Translation units may include
// simse.hpp directly, or include only the narrower topic header they need.
// Each topic header is self-contained (owns its includes and #pragma once).

// Order follows dependencies: types and containers have no RTL-relative
// dependencies, and the higher-level headers build on them.
#include "types.hpp"        // scalar aliases and Str
#include "containers.hpp"   // SmallVector, List, PList, Dictionary, Array, RawArray
#include "listops.hpp"      // simse_list_* native List operations (prelude)
#include "optional.hpp"     // Opt<T>
#include "functional.hpp"   // Func, Action, AutoDefer
#include "result.hpp"       // Result/Res, ok, resError, err
#include "xml.hpp"          // Attribute, XmlNode