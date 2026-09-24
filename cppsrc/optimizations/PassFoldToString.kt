// PassFoldToString.kt: `42.toString()` is `"42"`, so the call never reaches the C++.
//   _sm_expr4 = 42.toString();   ->   _sm_expr4 = "42";
//
// The receiver must already be a literal, so the fold's result keeps `Str`. `Int`/`Bool` only,
// since the RTL converts through `std::to_string`: `Float64` (MSVC `%f` rounding) and `Char`
// (decoding a literal) are other jobs, and a `Str` receiver is left alone.

package optimizations

import common
import linear

// The text a constant's `toString()` is, or empty when it will not fold.
fun foldToStringText(recv: *AstXmlNode): Str {
    val kind: FoldKind = foldKindOf(recv)
    if (kind == FoldKind.Bool) {
        return xmlAttr(recv, AstNodeAttributeKind.Value)
    }
    if (kind == FoldKind.Int) {
        val value: Opt<Int> = foldIntValue(recv)
        if (!value.hasValue()) {
            return ""
        }
        return value.value().toString()
    }
    return ""
}

// `toString` with no arguments on a constant receiver is the constant's text.
fun foldToStringRule(e: *AstXmlNode): AstXmlNode {
    if (xmlKind(e) != AstNodeCategory.ExprCall) {
        return e
    }
    val callee: *AstXmlNode = xmlChildPtr(e, AstNodeKind.Callee)
    if (xmlIsEmpty(callee) || xmlKind(callee) != AstNodeCategory.ExprMember) {
        return e
    }
    if (xmlAttr(callee, AstNodeAttributeKind.Name) != "toString") {
        return e
    }
    if (xmlCount(e, AstNodeKind.Arg) != 0) {
        return e
    }
    val recv: *AstXmlNode = xmlChildPtr(callee, AstNodeKind.Receiver)
    if (xmlIsEmpty(recv)) {
        return e
    }
    val text: Str = foldToStringText(recv)
    if (text.isEmpty()) {
        return e
    }
    return foldStrLit(e, text)
}

fun linFoldToStringBody(stmts: *List<AstXmlNode>): Bool {
    return foldExprsInList(stmts, foldToStringRule)
}

// Self-registration (`Optimize.kt`).
val linFoldToStringPass: Bool = registerLinOptPass("foldToString", linFoldToStringBody)
