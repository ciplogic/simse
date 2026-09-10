#pragma once
#include "../common/common.h"

namespace lex {
    using common::StrView;

    enum TokenKind : int {
        None,
        Space,
        Comment,
        Identifier,
        ReservedWord,
        Number,
        String,
        Character,
        Operator,
        Eof
    };

    using MatchLenFunc = Func<int(StrView)>;

    struct TokenMatcher {
        TokenKind tokenKind;
        MatchLenFunc match;
    };

    struct Token {
        Str text;
        TokenKind kind;
    };

    struct Scanner {
        List<TokenMatcher> *_rules;
        int Pos;
        Str Source;

        explicit Scanner(List<TokenMatcher> * rules);

        Result<Token> nextToken();

        void setSource(const Str & str);
    };

    List<TokenMatcher> getTokenRules();
}
