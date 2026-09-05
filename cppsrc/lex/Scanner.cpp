//
// Created by cipri on 9/4/2026.
//

#include "Scanner.h"

namespace {
    void addRule(List<TokenMatcher> *Rules, TokenKind tokenType, MatchLenFunc match) {
        TokenMatcher token_matcher {tokenType, match};
        Rules->push_back(token_matcher);

    }

    int matchAllOfRule(StrView strView, Func<bool(char)> matchFunc) {
        int len = strView.len;
        for (int i = 0; i<len; i++) {
            if (!matchFunc(strView.at(i))) {
                return i;
            }
        }
        return strView.len;
    }

    bool isSpace(char ch){
        return ch == ' ' || ch == '\t';
    }
}

int matchSpaces(StrView Source) {
    return matchAllOfRule(Source, isSpace);
}

List<TokenMatcher> getTokenRules() {
    List<TokenMatcher> Rules;
    addRule(&Rules, TokenKind::Space, matchSpaces);
    return Rules;
}

StrView viewOf(Str* str) {
    return {str, 0, (int)str->length()};
}