//
// Created by cipri on 9/10/2026.
//

#include "SkeletonParser.h"

// Maps a closing token to the opening token it matches.
Str matchingOpenToken(const Str &closingToken) {
    if (closingToken == ">")
        return "<";
    if (closingToken == "}")
        return "{";
    if (closingToken == "]")
        return "[";
    if (closingToken == ")")
        return "(";
    return "";
}

SkeletonType blockTypeForOpenToken(const Str &openingToken) {
    if (openingToken == "(")
        return SkeletonType::Paren;
    if (openingToken == "[")
        return SkeletonType::Square;
    if (openingToken == "{")
        return SkeletonType::Block;
    if (openingToken == "<")
        return SkeletonType::Generics;
    return SkeletonType::None;
}

bool isClosingToken(const Str &token) {
    return token == ">" || token == "}" || token == "]" || token == ")";
}

void SkeletonNode::addTerminalChild(const Token &str) {
    if (_children == nullptr) {
        _children = makeList<SkeletonNode>();
    }
    SkeletonNode skeleton_node;
    skeleton_node._type = SkeletonType::Terminal;
    skeleton_node._token = str;
    _children->push_back(skeleton_node);
}

// Folds the nodes after the nearest unmatched opening token into a single block
// node. The block keeps the opening token; the closing token is dropped. Returns
// false (and folds nothing) when no matching opening token is found.
bool foldBack(PList<SkeletonNode> &nodes, const Str &openingToken) {
    int openIndex = -1;
    for (int i = (int) nodes->size() - 1; i >= 0; i--) {
        SkeletonNode &node = (*nodes)[i];
        if (node._type == SkeletonType::Terminal && node._token.text == openingToken) {
            openIndex = i;
            break;
        }
    }
    if (openIndex < 0) {
        return false;
    }

    SkeletonNode block;
    block.setNodeType(blockTypeForOpenToken(openingToken));
    block._token = (*nodes)[openIndex]._token;

    for (int i = openIndex + 1; i < (int) nodes->size(); i++) {
        block._children->push_back((*nodes)[i]);
    }

    // Delete the folded range from where the block was opened, then insert the
    // folded block in its place.
    nodes->erase(nodes->begin() + openIndex, nodes->end());
    nodes->push_back(block);
    return true;
}

Res<SkeletonNode> parseSkeleton(List<Token> *tokens) {
    SkeletonNode program;
    program.setNodeType(SkeletonType::Program);
    for (Token token: *tokens) {
        Str tokenText = token.text;
        if (isClosingToken(tokenText)) {
            Str openingToken = matchingOpenToken(tokenText);
            // No matching opening token: keep the closing token as a terminal.
            if (foldBack(program._children, openingToken)) {
                continue;
            }
        }
        program.addTerminalChild(token);
    }

    return ok(program);
}
