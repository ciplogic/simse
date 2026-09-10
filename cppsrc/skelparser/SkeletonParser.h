#pragma once
#include "../rtl/containers.hpp"
#include "../rtl/result.hpp"

#include "../lex/Scanner.h"

using namespace lex;

enum class SkeletonType : int {
    None,
    Program,
    Terminal,
    Paren,
    Square,
    Generics,
    Block,
};

struct SkeletonNode {
    PList<SkeletonNode> _children {};
    SkeletonType _type;
    Token _token;

    void setNodeType(SkeletonType type) {
        _type = type;
        _children = makeList<SkeletonNode>();
    }

    void addTerminalChild(const Token & str);
};

Res<SkeletonNode> parseSkeleton(List<Token>* tokens);
