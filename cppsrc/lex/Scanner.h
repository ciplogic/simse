#pragma once
#include "../rtl/simse.hpp"

namespace lex {
    enum TokenKind : int {
        None,
        Space,
        Identifier,
        ReservedWord,
        Number,
        String,
        Operator,
        Eof
    };

    struct StrView {
        Str* str{};
        int start;
        int len;
        char at(int index);

        bool startsWith(const Str & str);

        StrView slice(int matchLength);

        Str toString();
    };

    StrView viewOf(Str* str);

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
