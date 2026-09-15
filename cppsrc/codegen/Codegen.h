#pragma once

#include "../ast/Ast.h"

// C++ emission for the v1 subset (impl_specs/rtl-abi.md, impl_specs/
// transpilation.md). One or more parsed modules are amalgamated into a single
// readable translation unit targeting the bootstrap RTL shims in cppsrc/rtl.
//
// Everything outside the supported subset yields a positioned "unsupported"
// error rather than a crash: the error is formatted as
// "<file>:<line>:<col>: <message>".

namespace codegen {
    // One parsed input file. The file name is used in source-map comments and
    // diagnostics. A `prelude` input participates in symbol collection (so its
    // declarations resolve) but is never emitted; this is how the RTL surface is
    // made available without an import (impl_specs/native-interop.md).
    struct Input {
        Str fileName;
        ast::Module module;
        bool prelude = false;
    };

    // Amalgamates every input into one C++ translation unit. Deterministic: the
    // same inputs always produce byte-identical output.
    Res<Str> emitProgram(const List<Input>& inputs);

    // `--linearCodegen`: emit every body from its instruction list, comparing it
    // against the statement path (impl_specs/linear-il.md). The IL's text is used
    // only where the two agree, so the flag cannot change the output; where they
    // disagree the statement text is kept and the difference - the first line that
    // differs, with both spellings - is reported on stderr. The report is the work
    // list for the rest of the port.
    bool linearCodegen();
    void setLinearCodegen(bool value);

    // `--linearCodegenEmit`: emit the IL's text for every body it could express, and
    // the statement text for the rest. With `linearCodegen` off, nothing changes.
    void setLinearCodegenEmit(bool value);
}
