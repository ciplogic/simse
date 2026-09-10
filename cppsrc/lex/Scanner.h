#pragma once
#include "../common/common.h"

namespace lex {
    using common::SourcePos;
    using common::StrView;

    enum class TokenKind : int {
        None,
        Space,
        Comment,
        EndOfLine,
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

    // A scanned token: its source text, kind, and start position.
    struct Token {
        Str text;
        TokenKind kind;
        SourcePos pos;
    };

    struct Scanner {
        List<TokenMatcher> *_rules;
        int Pos;
        int Line;
        int Column;
        Str Source;

        explicit Scanner(List<TokenMatcher> * rules);

        Res<Token> nextToken();

        void setSource(const Str & str);
    };

    List<TokenMatcher> getTokenRules();

    // Reads `fileName`, scans it to end of input, and collects every token up to
    // (but not including) the Eof token. Returns the scanning error, if any.
    Res<List<Token>> readFileAsTokens(Scanner* scanner, const Str& fileName);

    // Like readFileAsTokens, but drops Space and Comment tokens.
    Res<List<Token>> readFileAndSkipSpacesTokens(Scanner* scanner, const Str& fileName);
}
