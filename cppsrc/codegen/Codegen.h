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
    //
    // `resourceLiterals` are the `_res.md` entries the driver read
    // (`resources::loadLiterals`, specs/resources.md): the C++ literal of every key and
    // value, key then value. They are pooled into the program's string table like any
    // other literal and installed into the `Resources` API at start-up. An empty list
    // emits neither.
    Res<Str> emitProgram(const List<Input>& inputs, const List<Str>& resourceLiterals);

    // `--showLinearRepresentation`: the IL of every body, on stderr (a debugging view;
    // the emitted file is the same with and without it).
    bool showIl();
    void setShowIl(bool value);
}
