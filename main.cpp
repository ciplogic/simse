//
// Created by cipri on 9/4/2026.
//

#include "cppsrc/lex/Scanner.h"
#include "cppsrc/common/common.h"
#include "cppsrc/skelparser/SkeletonParser.h"

using namespace lex;
using namespace common;

int main() {
    List<TokenMatcher> rules = getTokenRules();
    List<Str> files = filesInDir(".", ".simse");
    Scanner scanner(&rules);
    for (auto file: files) {
        Res<List<Token>> tokensResult = readFileAndSkipSpacesTokens(&scanner, file);
        if (!tokensResult.isOk()) {
            printf("Error: %s\n", tokensResult.Error.c_str());
            return 1;
        }
        List<Token>* tokenList = &tokensResult.Value;
        auto skeleton = parseSkeleton(tokenList);
    }


    return 0;
}
