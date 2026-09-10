//
// Created by cipri on 9/4/2026.
//

#include "lex/Scanner.h"
#include "common/common.h"
#include "skelparser/SkeletonParser.h"

using namespace lex;
using namespace common;

int main() {
    List<TokenMatcher> rules = getTokenRules();
    List<Str> files = filesInDir("cppsrc", ".simse");
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
