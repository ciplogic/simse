//
// Created by cipri on 9/4/2026.
//

#include "Scanner.h"

using namespace lex;

namespace lex {
    void addRule(List<TokenMatcher> *Rules, TokenKind tokenType, MatchLenFunc match) {
        TokenMatcher token_matcher{tokenType, match};
        Rules->push_back(token_matcher);
    }

    using CharMatcher = Func<bool(char)>;

    int matchAllOfRule(StrView strView, CharMatcher matchFunc) {
        int len = strView.len;
        for (int i = 0; i < len; i++) {
            if (!matchFunc(strView.at(i))) {
                return i;
            }
        }
        return len;
    }

    int matchAllOfRules(StrView strView, CharMatcher matchFirst, CharMatcher matchFunc) {
        if (strView.len == 0) {
            return 0;
        }
        if (!matchFirst(strView.at(0))) {
            return 0;
        }
        for (int i = 1; i < strView.len; i++) {
            if (!matchFunc(strView.at(i))) {
                return i;
            }
        }

        return strView.len;
    }

    List<Str> ReservedWords = {
        "class", "data", "val", "var", "fun", "return",
        "while", "for",
        "if", "else", "true", "false", "null",
        "enum", "typealias", "native", "import", "this"
    };

    bool isReservedWord(StrView strView) {
        for (Str &reservedWord: ReservedWords) {
            if (strView.len != (int) reservedWord.length()) {
                continue;
            }

            if (strView.startsWith(reservedWord)) {
                return true;
            }
        }
        return false;
    }

    bool isSpace(char ch) {
        return ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n';
    }

    bool isDigit(char ch) {
        return ch >= '0' && ch <= '9';
    }

    bool isAlpha(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch == '_');
    }

    bool isAlphaOrDigit(char ch) {
        return isAlpha(ch) || isDigit(ch);
    }

    bool isOperatorChar(char ch) {
        switch (ch) {
            case '+': case '-': case '*': case '/': case '%':
            case '=': case '<': case '>': case '!':
            case '&': case '|': case '^': case '~':
            case '?': case ':': case ';': case ',': case '.':
            case '(': case ')': case '[': case ']':
            case '{': case '}':
                return true;
            default:
                return false;
        }
    }

    // Longest-match-first is not needed: no entry is a prefix of another.
    List<Str> MultiCharOperators = {
        "->", "==", "!=", "<=", ">=", "&&", "||",
        "+=", "-=", "*=", "/=", "%="
    };

    int matchSpaces(StrView Source) {
        return matchAllOfRule(Source, isSpace);
    }

    int matchIdentifier(StrView Source) {
        return matchAllOfRules(Source, isAlpha, isAlphaOrDigit);
    }

    int matchReservedWord(StrView Source) {
        int length = matchIdentifier(Source);
        if (length == 0) {
            return 0;
        }
        if (isReservedWord(Source.slice(length))) {
            return length;
        }
        return 0;
    }

    int matchNumber(StrView Source) {
        int len = Source.len;
        int i = 0;
        while (i < len && isDigit(Source.at(i))) {
            i++;
        }
        if (i == 0) {
            return 0;
        }

        // Optional fractional part: a '.' must be followed by a digit to belong
        // to the number, otherwise it is member/range punctuation.
        if (i + 1 < len && Source.at(i) == '.' && isDigit(Source.at(i + 1))) {
            i++;
            while (i < len && isDigit(Source.at(i))) {
                i++;
            }
        }
        return i;
    }

    int matchComment(StrView Source) {
        if (Source.len < 2 || Source.at(0) != '/') {
            return 0;
        }
        if (Source.at(1) == '/') {
            int i = 2;
            while (i < Source.len && Source.at(i) != '\n' && Source.at(i) != '\r') {
                i++;
            }
            return i;
        }
        if (Source.at(1) == '*') {
            int i = 2;
            while (i + 1 < Source.len) {
                if (Source.at(i) == '*' && Source.at(i + 1) == '/') {
                    return i + 2;
                }
                i++;
            }
        }
        return 0;
    }

    int matchStringLiteral(StrView Source) {
        if (Source.len == 0 || Source.at(0) != '"') {
            return 0;
        }
        int i = 1;
        while (i < Source.len) {
            char ch = Source.at(i);
            if (ch == '\\') {
                i += 2;
                continue;
            }
            if (ch == '"') {
                return i + 1;
            }
            i++;
        }
        return 0;
    }

    int matchCharLiteral(StrView Source) {
        if (Source.len == 0 || Source.at(0) != '\'') {
            return 0;
        }
        int i = 1;
        while (i < Source.len) {
            char ch = Source.at(i);
            if (ch == '\\') {
                i += 2;
                continue;
            }
            if (ch == '\'') {
                return i + 1;
            }
            if (ch == '\n') {
                return 0;
            }
            i++;
        }
        return 0;
    }

    int matchOperator(StrView Source) {
        for (Str &op: MultiCharOperators) {
            if (Source.len >= (int) op.length() && Source.startsWith(op)) {
                return (int) op.length();
            }
        }
        if (Source.len > 0 && isOperatorChar(Source.at(0))) {
            return 1;
        }
        return 0;
    }

    Scanner::Scanner(List<TokenMatcher> *rules) {
        _rules = rules;
        Pos = 0;
    }

    Result<Token> Scanner::nextToken() {
        while (this->Pos < (int) this->Source.length()) {
            StrView sourceView = common::viewOfAtPos(&this->Source, this->Pos);
            for (TokenMatcher &rule: *_rules) {
                int matchLength = rule.match(sourceView);
                if (matchLength <= 0) {
                    continue;
                }
                if (matchLength > sourceView.len) {
                    matchLength = sourceView.len;
                }
                auto tokenSlice = sourceView.slice(matchLength);

                Token token;
                token.text = tokenSlice.toString();
                token.kind = rule.tokenKind;

                this->Pos += matchLength;
                return ok(token);
            }
            return resError<Token>("Unexpected character");
        }

        Token eofToken{"", TokenKind::Eof};
        return ok(eofToken);
    }

    void Scanner::setSource(const Str &str) {
        this->Source = str;
        this->Pos = 0;
    }

    List<TokenMatcher> getTokenRules() {
        List<TokenMatcher> Rules;
        // Order matters: comments before operators (so `//` is not two `/`),
        // reserved words before identifiers, and operators last.
        addRule(&Rules, Comment, matchComment);
        addRule(&Rules, Space, matchSpaces);
        addRule(&Rules, String, matchStringLiteral);
        addRule(&Rules, Character, matchCharLiteral);
        addRule(&Rules, Number, matchNumber);
        addRule(&Rules, ReservedWord, matchReservedWord);
        addRule(&Rules, Identifier, matchIdentifier);
        addRule(&Rules, Operator, matchOperator);
        return Rules;
    }
}
