#pragma once

#include "../cppsrc/common/common.h"
#include "../cppsrc/lex/Scanner.h"

// Support code for the golden test harness. Lives in simse_lib so both the test
// executable and any future tooling can use it. It deliberately depends only on
// the real scanner (getTokenRules / readFileAsTokens / readFileAndSkipSpacesTokens
// / Scanner::nextToken) and the standard library.

namespace tests {
    using common::SourcePos;
    using lex::Scanner;
    using lex::Token;
    using lex::TokenKind;

    // The outcome of scanning one fixture. When `ok` is false the scanner
    // rejected a character; `tokens` then holds everything scanned before it and
    // `errorPos` is the position of the offending character.
    struct ScanResult {
        List<Token> tokens;
        bool ok;
        SourcePos errorPos;
        Str errorMessage;
    };

    // The TokenKind enumerator name, e.g. "ReservedWord".
    Str kindName(TokenKind kind);

    // Escapes `\`, `\n`, `\r`, and `\t` as backslash sequences so a token's text
    // fits on a single dump line. Every other byte is passed through unchanged.
    Str escapeText(const Str& text);

    // Scans `fileName` token by token. Unlike readFileAsTokens this keeps the
    // partial token stream when the scanner fails, which the unmatched-input
    // fixture needs.
    ScanResult scanFile(Scanner* scanner, const Str& fileName);

    // Renders a ScanResult in the golden dump format: one TAB-separated line per
    // token, `<KindName>\t<line>:<column>\t<escapedText>`. The Eof token is not
    // printed. A scan error appends a final `Error\t<line>:<column>\t<message>`
    // line.
    Str dump(const ScanResult& scan);

    // Cross-checks readFileAsTokens and readFileAndSkipSpacesTokens against the
    // manual scan. Returns an empty string when they agree, otherwise a
    // description of the mismatch.
    Str checkWrappers(Scanner* scanner, const Str& fileName, const ScanResult& scan);
}
