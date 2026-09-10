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
        "enum", "typealias", "native", "import", "this",
        "break", "continue",
        "switch", "case", "default"
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

    // Horizontal whitespace only. Line endings are their own token kind.
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

    // A line ending is CRLF, LF, or CR, matched as a whole.
    int matchEndOfLine(StrView Source) {
        if (Source.len == 0) {
            return 0;
        }
        char ch = Source.at(0);
        if (ch == '\n') {
            return 1;
        }
        if (ch == '\r') {
            if (Source.len >= 2 && Source.at(1) == '\n') {
                return 2;
            }
            return 1;
        }
        return 0;
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
        Line = 1;
        Column = 1;
    }

    // Advances `pos` by `matchLength` characters, updating `line` and `column`
    // one character at a time. A newline is '\n', or '\r' that is not
    // immediately followed by '\n' (so CRLF counts once). Tabs count as a single
    // column.
    void advancePosition(const Str &source, int &pos, int &line, int &column, int matchLength) {
        for (int i = 0; i < matchLength; i++) {
            char ch = source.at(pos);
            bool isNewline = ch == '\n'
                             || (ch == '\r'
                                 && (pos + 1 >= (int) source.length() || source.at(pos + 1) != '\n'));
            if (isNewline) {
                line += 1;
                column = 1;
            } else {
                column += 1;
            }
            pos += 1;
        }
    }

    // Escapes the first `maxLen` bytes of `view` so a diagnostic stays on one
    // line: backslash, newline, carriage return, and tab get backslash escapes;
    // printable ASCII (32..126) is kept; every other byte (including >= 127)
    // becomes \xNN with two uppercase hex digits. Bytes are treated as unsigned.
    Str escapedSnippet(StrView view, int maxLen) {
        static const char *hexDigits = "0123456789ABCDEF";
        Str snippet;
        int count = view.len < maxLen ? view.len : maxLen;
        for (int i = 0; i < count; i++) {
            unsigned char byte = (unsigned char) view.at(i);
            switch (byte) {
                case '\\': snippet += "\\\\"; break;
                case '\n': snippet += "\\n"; break;
                case '\r': snippet += "\\r"; break;
                case '\t': snippet += "\\t"; break;
                default:
                    if (byte >= 32 && byte <= 126) {
                        snippet += (char) byte;
                    } else {
                        snippet += "\\x";
                        snippet += hexDigits[(byte >> 4) & 0xF];
                        snippet += hexDigits[byte & 0xF];
                    }
                    break;
            }
        }
        return snippet;
    }

    Res<Token> Scanner::nextToken() {
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
                token.pos = SourcePos{this->Pos, this->Line, this->Column};

                advancePosition(this->Source, this->Pos, this->Line, this->Column, matchLength);
                return ok(token);
            }
            SourcePos startPos{this->Pos, this->Line, this->Column};
            Str message = std::to_string(startPos.line) + ":" + std::to_string(startPos.column)
                          + ": Unexpected character: '" + escapedSnippet(sourceView, 10) + "'";
            return resError<Token>(message);
        }

        Token eofToken{"", TokenKind::Eof, SourcePos{this->Pos, this->Line, this->Column}};
        return ok(eofToken);
    }

    void Scanner::setSource(const Str &str) {
        this->Source = str;
        this->Pos = 0;
        this->Line = 1;
        this->Column = 1;
    }

    List<TokenMatcher> getTokenRules() {
        List<TokenMatcher> Rules;
        // Order matters: comments before operators (so `//` is not two `/`),
        // reserved words before identifiers, and operators last.
        addRule(&Rules, TokenKind::Comment, matchComment);
        addRule(&Rules, TokenKind::Space, matchSpaces);
        addRule(&Rules, TokenKind::EndOfLine, matchEndOfLine);
        addRule(&Rules, TokenKind::String, matchStringLiteral);
        addRule(&Rules, TokenKind::Character, matchCharLiteral);
        addRule(&Rules, TokenKind::Number, matchNumber);
        addRule(&Rules, TokenKind::ReservedWord, matchReservedWord);
        addRule(&Rules, TokenKind::Identifier, matchIdentifier);
        addRule(&Rules, TokenKind::Operator, matchOperator);
        return Rules;
    }

    Res<List<Token>> readFileAsTokens(Scanner *scanner, const Str &fileName) {
        Str content = common::readFile(fileName);
        scanner->setSource(content);

        List<Token> tokens;
        while (true) {
            Res<Token> result = scanner->nextToken();
            if (!result.isOk()) {
                return resError<List<Token>>(fileName + ": " + result.Error);
            }
            if (result.Value.kind == TokenKind::Eof) {
                return ok(tokens);
            }
            tokens.push_back(result.Value);
        }
    }

    bool isSpaceBasedToken(TokenKind kind) {
        return kind == TokenKind::Space || kind == TokenKind::Comment;
    }

    Res<List<Token>> readFileAndSkipSpacesTokens(Scanner *scanner, const Str &fileName) {
        Res<List<Token>> allTokens = readFileAsTokens(scanner, fileName);
        if (!allTokens.isOk()) {
            return resError<List<Token>>(allTokens.Error);
        }

        List<Token> tokens;
        for (Token token: allTokens.Value) {
            if (isSpaceBasedToken(token.kind)) {
                continue;
            }
            tokens.push_back(token);
        }
        return ok(tokens);
    }
}
