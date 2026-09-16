// SkeletonParser.kt
//
// The Simse-language skeleton parser: it folds balanced token blocks into a
// SkeletonNode tree. It mirrors cppsrc/skelparser/SkeletonParser.h and
// cppsrc/skelparser/SkeletonParser.cpp.
//
// There is no stack: nodes are kept in one flat child list, and on a closing
// token the parser folds back to the nearest unmatched opening token of the
// same kind, then deletes the folded range from where the block was opened.

package skelparser

import lex
import common

enum class SkeletonType {
    None,
    Program,
    Terminal,
    Paren,
    Square,
    Generics,
    Block
}

// The children list is a counted reference, so the node type can be recursive
// without embedding SkeletonNode values inline.
data class SkeletonNode(var children: &List<SkeletonNode>, var type: SkeletonType, var token: Token) {
    fun setNodeType(skeletonType: SkeletonType): Unit {
        this.type = skeletonType
        this.children = & List < SkeletonNode >()
    }

    fun addTerminalChild(token: Token): Unit {
        this.children.append(SkeletonNode(& List < SkeletonNode >(), SkeletonType.Terminal, token))
    }
}

// Maps a closing token to the opening token it matches.
fun matchingOpenToken(closingToken: Str): Str {
    when (closingToken) {
        ">" -> {
            return "<"
        }

        "}" -> {
            return "{"
        }

        "]" -> {
            return "["
        }

        ")" -> {
            return "("
        }
    }
    return ""
}

fun blockTypeForOpenToken(openingToken: Str): SkeletonType {
    when (openingToken) {
        "(" -> {
            return SkeletonType.Paren
        }

        "[" -> {
            return SkeletonType.Square
        }

        "{" -> {
            return SkeletonType.Block
        }

        "<" -> {
            return SkeletonType.Generics
        }
    }
    return SkeletonType.None
}

fun isClosingToken(token: Str): Bool {
    return token == ">" || token == "}" || token == "]" || token == ")"
}

// Folds the nodes after the nearest unmatched opening token into a single block
// node. The block keeps the opening token; the closing token is dropped. Returns
// false (and folds nothing) when no matching opening token is found.
fun foldBack(nodes: &List<SkeletonNode>, openingToken: Str): Bool {
    var openIndex: Int = -1
    var i: Int = nodes.size() - 1
    while (i >= 0 && openIndex < 0) {
        val node: SkeletonNode = nodes[i]
        if (node.type == SkeletonType.Terminal && node.token.text == openingToken) {
            openIndex = i
        }
        i = i - 1
    }
    if (openIndex < 0) {
        return false
    }

    var block: SkeletonNode =
        SkeletonNode(& List < SkeletonNode >(), blockTypeForOpenToken(openingToken), nodes[openIndex].token)

    var j: Int = openIndex + 1
    while (j < nodes.size()) {
        block.children.append(nodes[j])
        j = j + 1
    }

    // Delete the folded range from where the block was opened, then insert the
    // folded block in its place.
    nodes.removeRange(openIndex, nodes.size())
    nodes.append(block)
    return true
}

fun parseSkeleton(tokens: *List<Token>): Res<SkeletonNode> {
    // The program node carries a zero token (None kind, position 0:0), matching
    // the hand-written C++ parser's default-constructed SkeletonNode.
    var program: SkeletonNode =
        SkeletonNode(& List < SkeletonNode >(), SkeletonType.None, Token("", TokenKind.None, SourcePos(0, 0, 0)))
    program.setNodeType(SkeletonType.Program)

    for (*token in tokens) {
        val tokenText: Str = token.text

        var folded: Bool = false
        if (isClosingToken(tokenText)) {
            folded = foldBack(program.children, matchingOpenToken(tokenText))
        }
        // No matching opening token: keep the closing token as a terminal.
        if (!folded) {
            program.addTerminalChild(copy(token))
        }
    }

    return Res<SkeletonNode>.ok(program)
}
