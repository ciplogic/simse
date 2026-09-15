#pragma once
#include "../rtl/simse.hpp"

#include <filesystem>

namespace common {
    Str readFile(const Str& filePath);
    List<Str> filesInDir(const Str& dirPath, Str ext = ".kt");

    // The Str <-> std::filesystem boundary (impl_specs/rtl-abi.md): the language
    // works in `Str`, the standard library in `std::string`/`path`. With
    // `Str = std::string` these are cheap copies, so the same code compiles in
    // either configuration.
    inline std::filesystem::path toPath(const Str& value) {
        return std::filesystem::path(simse_toStdString(value));
    }

    inline Str fromPath(const std::filesystem::path& value) {
        return simse_fromStdString(value.string());
    }

    // A position in a source file. `offset` is the 0-based byte offset of the
    // token's first character; `line` and `column` are 1-based. Tabs count as a
    // single column. A newline is '\n', or '\r' not immediately followed by
    // '\n', so CRLF advances the line exactly once.
    struct SourcePos {
        int offset;
        int line;
        int column;
    };

    // Views over the text a scanner is walking are the RTL's `Span<Char>`
    // (cppsrc/rtl/span.hpp): a pointer and a length, borrowed, in place of the
    // `StrView` this file used to define.
}
