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

    // `--linearCodegen`: the *report* - run every body through both codegen paths and
    // list the differences on stderr, keeping the statement text where they disagree.
    // The emitted file is unchanged, so this is the work list for the rest of the port
    // (impl_specs/linear-il.md).
    bool linearCodegen();
    void setLinearCodegen(bool value);

    // Emit from the IL for every body it can express and from the statement tree for
    // the rest - the default. `setLinearCodegenEmit(false)` (`--statementsCodegen`) puts
    // the statement path back in charge.
    void setLinearCodegenEmit(bool value);
}
