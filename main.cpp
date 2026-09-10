//
// Created by cipri on 9/4/2026.
//

#include "cppsrc/lex/Scanner.h"
#include "cppsrc/common/common.h"

using namespace lex;
using namespace common;

int main() {
    List<TokenMatcher> rules = getTokenRules();
    List<Str> files = filesInDir(".", ".simse");
    Scanner scanner(&rules);
    for (auto file: files) {
        Str content = readFile(file);
        scanner.setSource(content);
        auto currentTokenResult = scanner.nextToken();
        while (currentTokenResult.Value.kind != TokenKind::Eof) {
            if (!currentTokenResult.isOk()) {
                printf("Error: %s\n", currentTokenResult.Error.c_str());
                return 1;
            }
            Token currentToken = currentTokenResult.Value;
            printf("%s\n", currentToken.text.c_str());
            currentTokenResult = scanner.nextToken();
        }
    }


    return 0;
}
