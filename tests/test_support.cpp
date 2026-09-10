#include "test_support.h"

using namespace tests;

namespace tests {
    Str kindName(TokenKind kind) {
        switch (kind) {
            case TokenKind::None: return "None";
            case TokenKind::Space: return "Space";
            case TokenKind::Comment: return "Comment";
            case TokenKind::EndOfLine: return "EndOfLine";
            case TokenKind::Identifier: return "Identifier";
            case TokenKind::ReservedWord: return "ReservedWord";
            case TokenKind::Number: return "Number";
            case TokenKind::String: return "String";
            case TokenKind::Character: return "Character";
            case TokenKind::Operator: return "Operator";
            case TokenKind::Eof: return "Eof";
        }
        return "Unknown";
    }

    Str escapeText(const Str& text) {
        Str escaped;
        for (char ch: text) {
            switch (ch) {
                case '\\': escaped += "\\\\"; break;
                case '\n': escaped += "\\n"; break;
                case '\r': escaped += "\\r"; break;
                case '\t': escaped += "\\t"; break;
                default: escaped += ch; break;
            }
        }
        return escaped;
    }

    ScanResult scanFile(Scanner *scanner, const Str &fileName) {
        Str content = common::readFile(fileName);
        scanner->setSource(content);

        ScanResult scan;
        scan.ok = true;
        while (true) {
            Res<Token> result = scanner->nextToken();
            if (!result.isOk()) {
                scan.ok = false;
                scan.errorPos = SourcePos{scanner->Pos, scanner->Line, scanner->Column};
                scan.errorMessage = result.Error;
                return scan;
            }
            if (result.Value.kind == TokenKind::Eof) {
                return scan;
            }
            scan.tokens.push_back(result.Value);
        }
    }

    Str dump(const ScanResult &scan) {
        Str out;
        for (const Token &token: scan.tokens) {
            out += kindName(token.kind);
            out += '\t';
            out += std::to_string(token.pos.line);
            out += ':';
            out += std::to_string(token.pos.column);
            out += '\t';
            out += escapeText(token.text);
            out += '\n';
        }
        if (!scan.ok) {
            out += "Error";
            out += '\t';
            out += std::to_string(scan.errorPos.line);
            out += ':';
            out += std::to_string(scan.errorPos.column);
            out += '\t';
            out += escapeText(scan.errorMessage);
            out += '\n';
        }
        return out;
    }

    namespace {
        bool sameToken(const Token &a, const Token &b) {
            return a.kind == b.kind
                   && a.text == b.text
                   && a.pos.offset == b.pos.offset
                   && a.pos.line == b.pos.line
                   && a.pos.column == b.pos.column;
        }

        bool isSpaceBased(TokenKind kind) {
            return kind == TokenKind::Space || kind == TokenKind::Comment;
        }
    }

    Str checkWrappers(Scanner *scanner, const Str &fileName, const ScanResult &scan) {
        Res<List<Token>> all = lex::readFileAsTokens(scanner, fileName);
        Res<List<Token>> skipped = lex::readFileAndSkipSpacesTokens(scanner, fileName);

        if (!scan.ok) {
            if (all.isOk() || skipped.isOk()) {
                return "expected readFileAsTokens/readFileAndSkipSpacesTokens to fail";
            }
            return "";
        }

        if (!all.isOk()) {
            return "readFileAsTokens failed: " + all.Error;
        }
        if (!skipped.isOk()) {
            return "readFileAndSkipSpacesTokens failed: " + skipped.Error;
        }
        if (all.Value.size() != scan.tokens.size()) {
            return "readFileAsTokens token count mismatch";
        }
        for (int i = 0; i < (int) scan.tokens.size(); i++) {
            if (!sameToken(all.Value[i], scan.tokens[i])) {
                return "readFileAsTokens token mismatch at index " + std::to_string(i);
            }
        }

        List<Token> expectedSkipped;
        for (const Token &token: scan.tokens) {
            if (!isSpaceBased(token.kind)) {
                expectedSkipped.push_back(token);
            }
        }
        if (skipped.Value.size() != expectedSkipped.size()) {
            return "readFileAndSkipSpacesTokens token count mismatch";
        }
        for (int i = 0; i < (int) expectedSkipped.size(); i++) {
            if (!sameToken(skipped.Value[i], expectedSkipped[i])) {
                return "readFileAndSkipSpacesTokens token mismatch at index " + std::to_string(i);
            }
        }
        return "";
    }
}
