#pragma once

#include "../ast/Ast.h"
#include "../lex/Scanner.h"

// The recursive-descent + Pratt parser for the minimal Simse subset. It works on
// the token stream produced by the scanner (Space and Comment tokens are ignored;
// the stream must not contain the Eof token, though a synthetic one is appended
// internally). EndOfLine and ';' both terminate statements.

namespace parser {
    // Parses a whole module. On failure the returned error is formatted as
    // "<fileName>:<line>:<col>: <message>".
    Res<ast::Module> parseModule(List<lex::Token>& tokens, const Str& fileName);
}
