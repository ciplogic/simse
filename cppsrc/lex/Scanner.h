#pragma once
#include "../rtl/simse.hpp"

enum TokenKind : int {
    None,
    Space,
    Identifier,
    ReservedWord,
    Number,
    String,
    Operator,
};

struct StrView {
    Str* str{};
    int start;
    int len;
    char at(int index) {
        return str->at(start + index);
    }
};

StrView viewOf(Str* str);

using MatchLenFunc = Func<int(StrView)>;

struct TokenMatcher {
    TokenKind tokenKind;
    MatchLenFunc match;
};

struct Scanner {
    int Pos;
    Str Source;
};

List<TokenMatcher> getTokenRules();
