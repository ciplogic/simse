// PassFoldToString.kt
//
// `x.toString()` on a constant is the text of that constant: `42.toString()` is `"42"`, so
// the call - and the `std::to_string` behind it - never reaches the C++.
//
//   _sm_expr4 = 42.toString();   ->   _sm_expr4 = "42";
//
// What is folded is the *conversion itself*: the receiver has to already be a literal, and
// a literal is what this pass leaves behind, so the slot below keeps the type the type pass
// gave it (`Str`) and the literal converts exactly as the call's result did.
//
// **`Int` and `Bool` only**, and that is a correctness line rather than a scope one. The
// RTL's conversion is `std::to_string` (`cppsrc/rtl/_res.md`), so what a fold has to
// reproduce is *that function's* text, bit for bit:
//
//   - `Int` is the decimal digits, and `Bool` is `"true"`/`"false"` - exact, and the whole
//     of what this pass does;
//   - `Float64` is MSVC's `%f` with six decimals (`1.5` prints `1.500000`), so folding it
//     means reproducing a libc's float formatting exactly, including its rounding of the
//     seventh digit. A fold that is one digit out is a program that prints a different
//     number, and that is not a thing a peephole may risk. It is a job of its own, with
//     its own tests (the guide's "do the optimizations on small examples first");
//   - `Char` converts through `std::to_string((int) self)` - the numeric value - so
//     folding it means *decoding* a char literal (`'\n'` is 10, not 110), which is the
//     scanner's job and not this walk's.
//
// A `Str` receiver (`"abc".toString()`) is left alone too: it is the owned copy of a view,
// and folding it would turn a `Str` into a `StrView` for no gain at all.

package optimizations

import common
import linear

// The text a constant's `toString()` is, or empty when this pass will not fold it.
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

// One node: `toString` with no arguments on a constant receiver is the constant's text.
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
