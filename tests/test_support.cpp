#include "test_support.h"

#include "../cppsrc/codegen/Codegen.h"
#include "../cppsrc/parser/Parser.h"
#include "../cppsrc/sema/Sema.h"

#include <filesystem>
#include <string>

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
        ScanResult scan;
        scan.ok = true;
        scan.errorPos = SourcePos{0, 1, 1};
        if (!std::filesystem::exists(fileName)) {
            // Report a clean failure instead of letting readFile fault on a
            // missing path.
            scan.ok = false;
            scan.errorMessage = "cannot read file: " + fileName;
            return scan;
        }

        Str content = common::readFile(fileName);
        scanner->setSource(content);

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
            Str prefix = fileName + ": ";
            if (all.Error.rfind(prefix, 0) != 0) {
                return "readFileAsTokens error is missing the file prefix: " + all.Error;
            }
            if (skipped.Error.rfind(prefix, 0) != 0) {
                return "readFileAndSkipSpacesTokens error is missing the file prefix: " + skipped.Error;
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

    namespace {
        // The RTL prelude is parsed once and reused. It participates in
        // resolution but is never emitted (impl_specs/native-interop.md).
        const ast::Module *defaultPrelude(bool &loaded) {
            static bool tried = false;
            static bool ok = false;
            static ast::Module module;
            if (!tried) {
                tried = true;
#ifdef SIMSE_DEFAULT_PRELUDE
                Str path = SIMSE_DEFAULT_PRELUDE;
                if (std::filesystem::exists(path)) {
                    Res<ast::Module> parsed = parser::parseFile(path);
                    if (parsed.isOk()) {
                        module = parsed.Value;
                        ok = true;
                    }
                }
#endif
            }
            loaded = ok;
            return ok ? &module : nullptr;
        }

        ast::Module combineWithPrelude(const ast::Module &prelude, const ast::Module &input) {
            ast::Module combined;
            combined.pos = input.pos;
            for (const ast::Import &import: prelude.imports) {
                combined.imports.push_back(import);
            }
            for (const ast::Import &import: input.imports) {
                combined.imports.push_back(import);
            }
            for (const ast::DeclPtr &decl: prelude.declarations) {
                combined.declarations.push_back(decl);
            }
            for (const ast::DeclPtr &decl: input.declarations) {
                combined.declarations.push_back(decl);
            }
            return combined;
        }
    }

    AstSemaResult runAstSema(const ScanResult &scan, const Str &displayName) {
        AstSemaResult result;
        if (!scan.ok) {
            result.parsed = false;
            result.ast = "ScanError " + escapeText(scan.errorMessage) + "\n";
            return result;
        }

        List<Token> tokens = scan.tokens;
        Res<ast::Module> parsed = parser::parseModule(tokens, displayName);
        if (!parsed.isOk()) {
            result.parsed = false;
            result.ast = "ParseError " + parsed.Error + "\n";
            return result;
        }

        result.parsed = true;
        result.ast = ast::dumpModule(parsed.Value);

        bool hasPrelude = false;
        const ast::Module *prelude = defaultPrelude(hasPrelude);
        ast::Module toAnalyze =
            hasPrelude ? combineWithPrelude(*prelude, parsed.Value) : parsed.Value;
        List<Str> diagnostics = sema::analyze(toAnalyze, displayName);
        for (const Str &diagnostic: diagnostics) {
            result.sema += diagnostic + "\n";
        }

        List<codegen::Input> inputs;
        if (hasPrelude) {
            codegen::Input preludeInput;
#ifdef SIMSE_DEFAULT_PRELUDE
            preludeInput.fileName = SIMSE_DEFAULT_PRELUDE;
#endif
            preludeInput.module = *prelude;
            preludeInput.prelude = true;
            inputs.push_back(preludeInput);
        }
        codegen::Input input;
        input.fileName = displayName;
        input.module = parsed.Value;
        inputs.push_back(input);
        Res<Str> emitted = codegen::emitProgram(inputs);
        result.hasCpp = true;
        result.cpp = emitted.isOk() ? emitted.Value : ("CodegenError " + emitted.Error + "\n");
        return result;
    }

    Str emitFixtureCpp(Scanner *scanner, const Str &fixturesDir, const Str &name) {
        Str path = (std::filesystem::path(fixturesDir) / name).string();
        ScanResult scan = scanFile(scanner, path);
        if (!scan.ok) return "";
        List<Token> tokens = scan.tokens;
        Res<ast::Module> parsed = parser::parseModule(tokens, name);
        if (!parsed.isOk()) return "";

        bool hasPrelude = false;
        const ast::Module *prelude = defaultPrelude(hasPrelude);
        List<codegen::Input> inputs;
        if (hasPrelude) {
            codegen::Input preludeInput;
#ifdef SIMSE_DEFAULT_PRELUDE
            preludeInput.fileName = SIMSE_DEFAULT_PRELUDE;
#endif
            preludeInput.module = *prelude;
            preludeInput.prelude = true;
            inputs.push_back(preludeInput);
        }
        codegen::Input input;
        input.fileName = name;
        input.module = parsed.Value;
        inputs.push_back(input);
        Res<Str> emitted = codegen::emitProgram(inputs);
        return emitted.isOk() ? emitted.Value : Str("");
    }
}
