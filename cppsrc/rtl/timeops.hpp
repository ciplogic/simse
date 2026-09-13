#pragma once

#include "types.hpp"

// Time natives (the Simse surface is the prelude file cppsrc/rtl/rtl.simse; the
// definitions are in cppsrc/native/Native.cpp). `simse_nowMillis` is a monotonic
// clock for logging and for measuring a run: milliseconds since an arbitrary fixed
// point, never going backwards.
Int64 simse_nowMillis();
