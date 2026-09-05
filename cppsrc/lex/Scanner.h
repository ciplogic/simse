#pragma once
#include "../rtl/simse.hpp"

enum TokenType : int {
    None,
    Space,
    Identifier,
    ReservedWord,
    Number,
    String,
    Operator,
};

struct TokenMatcher {
    TokenType TokenKind;
    Func<int()> Match;
};

struct Scanner {

};