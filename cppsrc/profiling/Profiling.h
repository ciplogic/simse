#pragma once

#include "../ast/Ast.h"

// The instrumented profiler (`--profile`, impl_specs/profiling.md). The mirror of
// cppsrc/profiling/Profiling.kt.
//
// A flag, not a feature: with `--profile` off every function here returns the empty
// string, so a program built without it is byte-identical to one built before the flag
// existed. With it on, the emitter writes the runtime below into the program's prologue
// and one `profileApp.measure("...")` at the top of every emitted body; the table prints
// when the program leaves.
namespace profiling {
    // `--profile`.
    bool enabled();
    void setEnabled(bool value);

    // The prologue's addition: `<cstdio>`, then the runtime (`ProfileApp`,
    // `ProfileScope`, and the one `profileApp` every body measures into). Empty when the
    // flag is off.
    Str preludeText();

    // The first statement of an emitted body, named by the symbol it is emitted under.
    // Empty when the flag is off.
    Str preamble(const Str& name);
}
