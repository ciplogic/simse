//
// simse_transpile: the transpiler CLI. Parses the given .simse inputs (or every
// .simse under the current directory when none are given), runs name/type
// resolution, and writes one amalgamated C++ translation unit.
//
// Usage: simse_transpile <input.simse>... -o <output.cpp>
//

#include "Codegen.h"
#include "../common/common.h"
#include "../lex/Scanner.h"
#include "../parser/Parser.h"
#include "../sema/Sema.h"

#include <cstdio>

using namespace common;
using namespace lex;

int main(int argc, char **argv) {
    List<Str> inputs;
    Str output;
    for (int i = 1; i < argc; i++) {
        Str arg = argv[i];
        if (arg == "-o") {
            if (i + 1 >= argc) {
                fprintf(stderr, "simse_transpile: -o requires a path\n");
                return 2;
            }
            output = argv[++i];
        } else if (arg == "-h" || arg == "--help") {
            printf("usage: simse_transpile <input.simse>... -o <output.cpp>\n");
            return 0;
        } else {
            inputs.push_back(arg);
        }
    }

    if (inputs.empty()) {
        inputs = filesInDir(".", ".simse");
    }
    if (inputs.empty()) {
        fprintf(stderr, "simse_transpile: no .simse inputs found\n");
        return 2;
    }
    if (output.empty()) {
        fprintf(stderr, "simse_transpile: missing -o <output.cpp>\n");
        return 2;
    }

    List<TokenMatcher> rules = getTokenRules();
    Scanner scanner(&rules);

    List<codegen::Input> modules;
    for (const Str &file: inputs) {
        Res<List<Token>> tokens = readFileAndSkipSpacesTokens(&scanner, file);
        if (!tokens.isOk()) {
            fprintf(stderr, "%s\n", tokens.Error.c_str());
            return 1;
        }
        List<Token> tokenList = tokens.Value;
        Res<ast::Module> parsed = parser::parseModule(tokenList, file);
        if (!parsed.isOk()) {
            fprintf(stderr, "%s\n", parsed.Error.c_str());
            return 1;
        }
        List<Str> diagnostics = sema::analyze(parsed.Value, file);
        if (!diagnostics.empty()) {
            for (const Str &diagnostic: diagnostics) {
                fprintf(stderr, "%s\n", diagnostic.c_str());
            }
            return 1;
        }
        codegen::Input input;
        input.fileName = file;
        input.module = parsed.Value;
        modules.push_back(input);
    }

    Res<Str> emitted = codegen::emitProgram(modules);
    if (!emitted.isOk()) {
        fprintf(stderr, "%s\n", emitted.Error.c_str());
        return 1;
    }

    FILE *out = fopen(output.c_str(), "wb");
    if (out == nullptr) {
        fprintf(stderr, "simse_transpile: cannot write %s\n", output.c_str());
        return 1;
    }
    fwrite(emitted.Value.data(), 1, emitted.Value.length(), out);
    fclose(out);
    return 0;
}
