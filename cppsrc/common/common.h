#pragma once
#include "../rtl/simse.hpp"

namespace common {
    Str readFile(const Str& filePath);
    List<Str> filesInDir(const Str& dirPath, Str ext = ".simse");

    // A position in a source file. `offset` is the 0-based byte offset of the
    // token's first character; `line` and `column` are 1-based. Tabs count as a
    // single column. A newline is '\n', or '\r' not immediately followed by
    // '\n', so CRLF advances the line exactly once.
    struct SourcePos {
        int offset;
        int line;
        int column;
    };

    // StrView is a view over a range of a Str. It borrows the source without
    // copying: `start` is the offset of the first character and `len` is the
    // number of characters in the view.
    struct StrView {
        Str* str{};
        int start;
        int len;

        char at(int index);

        bool startsWith(const Str& str);

        StrView slice(int matchLength);

        Str toString();
    };

    StrView viewOf(Str* str);

    StrView viewOfAtPos(Str* str, int pos);
}
