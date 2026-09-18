#pragma once

#include "types.hpp"

// Time natives (the Simse surface is the prelude file cppsrc/rtl/rtl.kt; the
// definitions are in cppsrc/native/Native.cpp). Both are monotonic clocks - never going
// backwards - since an arbitrary fixed point: `simse_nowMillis` for logging, the finer
// `simse_nowMicros` for the instrumented profiler (`cppsrc/profiling`, and the emitted
// `profileApp.measure(...)` of a `--profile` build).
Int64 simse_nowMillis();
Int64 simse_nowMicros();
