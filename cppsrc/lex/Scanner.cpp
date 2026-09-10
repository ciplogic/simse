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
        return strView.len;
    }

    int matchAllOfRules(StrView strView, CharMatcher matchFirst, CharMatcher matchFunc) {
        int len = strView.len;
        if (!matchFirst(strView.at(0))) {
            return 0;
        }
        for (int i = 0; i < len; i++) {
            if (!matchFunc(strView.at(i))) {
                return i;
            }
        }

        return strView.len;
    }

    List<Str> ReservedWords = {
        {
            "class", "data", "val", "var", "fun", "return",
            "while", "for",
            "if", "else", "true", "false", "null"
        }
    };

    bool isReservedWord(StrView strView) {
        for (Str reservedWord : ReservedWords) {
            if (reservedWord.at(0) != strView.at(0)) {
                continue;
            }\

            if (strView.startsWith(reservedWord)) {
                return true;
            }
        }
        return false;
    }

    bool isSpace(char ch) {
        return ch == ' ' || ch == '\t';
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


    int matchSpaces(StrView Source) {
        return matchAllOfRule(Source, isSpace);
    }

    int matchIdentifier(StrView Source) {
        return matchAllOfRules(Source, isAlpha, isAlphaOrDigit);
    }

    char StrView::at(int index) {
        return str->at(start + index);
    }

    bool StrView::startsWith(const Str &str) {
        if (str.length() > len) {
            return false;
        }
        for (int i = 0; i < str.length(); i++) {
            if (at(i) != str.at(i)) {
                return false;
            }
        }
        return true;
    }

    StrView StrView::slice(int matchLength) {
        return {str, start, start + matchLength};
    }

    Str StrView::toString() {
        Str result;
        result.resize(len);
        for (int i = 0; i < len; i++) {
            result.at(i) = at(i);
        }
        return result;
    }

    StrView viewOf(Str *str) {
        return {str, 0, (int) str->length()};
    }


    StrView viewOfAtPos(Str *str, int pos) {
        return {str, pos, (int) str->length() - pos};
    }

    Scanner::Scanner(List<TokenMatcher> *rules) {
        _rules = rules;
        Pos = 0;
    }

    Result<Token> Scanner::nextToken() {
        StrView sourceView = viewOfAtPos(&this->Source, this->Pos);
        if (sourceView.len == 0) {
            Token eofToken ("", TokenKind::Eof);
            return ok(eofToken);
        }
        for (TokenMatcher &rule: *_rules) {
            int matchLength = rule.match(sourceView);
            if (matchLength == 0) {
                continue;
            }
            auto tokenSlice = sourceView.slice(matchLength);

            Token token;
            token.text = tokenSlice.toString();
            token.kind = rule.tokenKind;

            return ok(token);
        }
        return resError<Token>("Unexpected character");
    }

    void Scanner::setSource(const Str &str) {
        this->Source = str;
        this->Pos = 0;
    }

    List<TokenMatcher> getTokenRules() {
        List<TokenMatcher> Rules;
        addRule(&Rules, Space, matchSpaces);
        addRule(&Rules, Identifier, matchIdentifier);
        return Rules;
    }
}
