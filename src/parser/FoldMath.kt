// FoldMath.kt
//
// The math folds (impl_specs/fold-math.md), the `ConstParams` shape: a whole-program
// AST-to-AST rewrite beside the `!!` expansion and before sema, so the checker, the
// lowering and the emitter all see the folded program and nothing downstream knows the
// optimization exists. Two rules:
//
//   sin(0.0)              ->   0.0        (the exact identities of the math module)
//   x / 2.0               ->   x * 0.5    (a float division by a power of two)
//
// The identity fold replaces a call to the `math` module's wrapper with the literal the
// correctly rounded function answers - never an evaluated approximation, so a folded
// program is bit-identical to the unfolded one (`sin` of a zero is that zero, sign
// included; `cos` of a zero is one; `pow`'s C99 identities; `abs` drops a literal's
// sign, an exact negation). Every replacement is a *float* literal, or the argument
// expression itself when that argument is one, so the call's float type is always what
// stands in its place. The gate is name-level and declines on any doubt: a name folds
// only when every declaration the compiled set carries for it is the module's own
// body-less `@SmGen` declaration with the wrapper symbol this pass knows - a program
// that declares its own `sin`, or generates one under another symbol, is left alone.
//
// The division peephole is the one place a float operation is rewritten without knowing
// the operands' values: `x / D` becomes `x * R` only when `D`'s literal text is an exact
// power of two, where the reciprocal is also exactly representable and the two
// operations are bit-identical for every input - signs, zeros, infinities, NaNs and
// subnormals included, because scaling a binary float by a power of two is exact both
// ways, and the reciprocal's decimal spelling round-trips because powers of two are
// finite decimals (`FoldExprs`' "a Float64 fold needs a spelling that parses back to
// the same bits", satisfied by construction). An integer division never qualifies: it
// truncates, and `x / 2` is not `x * 0.5`.

package parser

import compiler

import common

// Whether the walk rewrote anything; a field so the helpers can report through a pointer.
data class FmState(
    var changed: Bool
)

// ---- the identity table ------------------------------------------------------

// Whether `symbol` is one of the math module's wrappers for `name`
// (`modules/math/api.kt`): most names own one symbol, the float-widened ones own two
// (`simse_math_sin` and `simse_math_sinf`). The fold's gate matches a declaration's
// `@SmGen` symbol against these, so a same-named declaration of the program's own can
// never be folded as the library's.
fun fmIsMathSymbol(name: Str, symbol: Str): Bool {
    when (name) {
        "sin" -> {
            return symbol == "simse_math_sin" || symbol == "simse_math_sinf"
        }

        "cos" -> {
            return symbol == "simse_math_cos" || symbol == "simse_math_cosf"
        }

        "tan" -> {
            return symbol == "simse_math_tan" || symbol == "simse_math_tanf"
        }

        "sqrt" -> {
            return symbol == "simse_math_sqrt" || symbol == "simse_math_sqrtf"
        }

        "abs" -> {
            return symbol == "simse_math_fabs" || symbol == "simse_math_fabsf"
        }

        "pow" -> {
            return symbol == "simse_math_pow" || symbol == "simse_math_powf"
        }

        "asin" -> {
            return symbol == "simse_math_asin"
        }

        "acos" -> {
            return symbol == "simse_math_acos"
        }

        "atan" -> {
            return symbol == "simse_math_atan"
        }

        "atan2" -> {
            return symbol == "simse_math_atan2"
        }

        "sinh" -> {
            return symbol == "simse_math_sinh"
        }

        "cosh" -> {
            return symbol == "simse_math_cosh"
        }

        "tanh" -> {
            return symbol == "simse_math_tanh"
        }

        "exp" -> {
            return symbol == "simse_math_exp"
        }

        "log" -> {
            return symbol == "simse_math_log"
        }

        "log2" -> {
            return symbol == "simse_math_log2"
        }

        "log10" -> {
            return symbol == "simse_math_log10"
        }

        "cbrt" -> {
            return symbol == "simse_math_cbrt"
        }

        "floor" -> {
            return symbol == "simse_math_floor"
        }

        "ceil" -> {
            return symbol == "simse_math_ceil"
        }

        "round" -> {
            return symbol == "simse_math_round"
        }
    }
    return false
}

// The gate's name test: whether the name is one the module declares at all.
fun fmIsMathName(name: Str): Bool {
    when (name) {
        "sin", "cos", "tan", "asin", "acos", "atan", "atan2", "sinh", "cosh", "tanh",
        "exp", "log", "log2", "log10", "pow", "sqrt", "cbrt", "abs", "floor", "ceil",
        "round" -> {
            return true
        }
    }
    return false
}


// The zero-preserving functions: `f(+-0)` is that same zero, sign included.
fun fmKeepsZero(name: Str): Bool {
    when (name) {
        "sin", "tan", "asin", "atan", "sinh", "tanh", "cbrt" -> {
            return true
        }
    }
    return false
}

// The functions that answer one at zero.
fun fmOneAtZero(name: Str): Bool {
    when (name) {
        "cos", "cosh", "exp" -> {
            return true
        }
    }
    return false
}

// The functions that answer zero at one (the logarithms' principal values).
fun fmZeroAtOne(name: Str): Bool {
    when (name) {
        "log", "log2", "log10" -> {
            return true
        }
    }
    return false
}

// ---- the literal shapes, decided on the text and only exactly -----------------

// Every character is a `0` digit; "" qualifies (an absent fraction).
fun fmAllZeros(part: Str): Bool {
    var i: Int = 0
    while (i < part.size()) {
        if (part[i] != '0') {
            return false
        }
        i++
    }
    return true
}

// Every character is a digit 0-9 ("" does not qualify).
fun fmAllDigits(part: Str): Bool {
    if (part.size() == 0) {
        return false
    }
    var i: Int = 0
    while (i < part.size()) {
        val code: Int = Int(part[i])
        if (code < 48 || code > 57) {
            return false
        }
        i++
    }
    return true
}

// A literal's text is a zero: an optional sign, then digits that are all `0`, an
// optional all-zero fraction, and never an exponent (`0`, `0.0`, `-0.0`, `0.000`).
fun fmIsZeroText(text: Str): Bool {
    var body: Str = text
    if (body.size() > 0 && (body[0] == '-' || body[0] == '+')) {
        body = body.substr(1, body.size() - 1)
    }
    if (body.size() == 0 || body.find("e") != -1 || body.find("E") != -1) {
        return false
    }
    var dot: Int = body.find(".")
    var intPart: Str = body
    var fracPart: Str = ""
    if (dot != -1) {
        intPart = body.substr(0, dot)
        fracPart = body.substr(dot + 1, body.size() - dot - 1)
    }
    return fmAllZeros(intPart) && fmAllZeros(fracPart)
}

// A literal's text is a positive one: `1` with an optional all-zero fraction.
fun fmIsOneText(text: Str): Bool {
    if (text.size() == 0 || text.find("e") != -1 || text.find("E") != -1) {
        return false
    }
    var dot: Int = text.find(".")
    var intPart: Str = text
    var fracPart: Str = ""
    if (dot != -1) {
        intPart = text.substr(0, dot)
        fracPart = text.substr(dot + 1, text.size() - dot - 1)
    }
    return intPart == "1" && fmAllZeros(fracPart)
}

// A zero text spelled as a float: "0" becomes "0.0" and "-0" becomes "-0.0", so the
// replacement of a float-returning call is always a float literal; a text that already
// carries a dot stands as written, its sign travelling with it.
fun fmZeroAsFloat(text: Str): Str {
    if (text.find(".") != -1) {
        return text
    }
    if (text.size() > 0 && text[0] == '-') {
        return "-0.0"
    }
    return "0.0"
}

// The text of an integer or float literal, or "" for anything else.
fun fmLiteralText(e: *AstXmlNode): Str {
    val kind: AstNodeCategory = xmlKind(e)
    if (kind == AstNodeCategory.ExprIntLit || kind == AstNodeCategory.ExprFloatLit) {
        return xmlAttr(e, AstNodeAttributeKind.Text)
    }
    return ""
}

// A float literal node built on another node's role and position.
fun fmFloatLit(like: AstXmlNode, text: Str): AstXmlNode {
    var attrs: List<AstNodeAttribute> = List<AstNodeAttribute>()
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.Line, xmlLine(*like).toString()))
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.Column, xmlColumn(*like).toString()))
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.Text, text))
    return AstXmlNode(like.name, AstNodeCategory.ExprFloatLit, attrs, Array<AstXmlNode>())
}

// ---- the power-of-two reciprocal ----------------------------------------------

// The reciprocal of a power-of-two float literal's text, as another exact float
// spelling; "" when the text is not an exact power of two (or not a plain decimal).
// The integer-significand family (`2.0`, `4.0`, `1024.0`) answers `"0." + 5^k padded
// to k digits` (2^-4 is 0.0625); the fraction family (`0.5`, `0.25`, `0.125` - the
// texts whose fraction is 5^k in exactly k digits) answers `2^k + ".0"`. A sign
// travels; an exponent form or a non-power-of-two declines.
fun fmPow2Reciprocal(text: Str): Str {
    if (text.size() == 0 || text.find("e") != -1 || text.find("E") != -1) {
        return ""
    }
    var sign: Str = ""
    var body: Str = text
    if (body[0] == '-') {
        sign = "-"
        body = body.substr(1, body.size() - 1)
    } else if (body[0] == '+') {
        body = body.substr(1, body.size() - 1)
    }
    var dot: Int = body.find(".")
    var intPart: Str = body
    var fracPart: Str = ""
    if (dot != -1) {
        intPart = body.substr(0, dot)
        fracPart = body.substr(dot + 1, body.size() - dot - 1)
    }
    if (!fmAllDigits(intPart) || (fracPart.size() > 0 && !fmAllDigits(fracPart))) {
        return ""
    }
    if (fmAllZeros(fracPart)) {
        // 2^k for some k: peel twos, declining an odd value above one.
        val parsed: Opt<Int64> = litParseInt64(intPart)
        if (!parsed.hasValue()) {
            return ""
        }
        var v: Int64 = parsed.value()
        if (v < 1) {
            return ""
        }
        var k: Int = 0
        var ok: Bool = true
        while (v > 1) {
            if (v % 2 != 0) {
                ok = false
                break
            }
            v = v / 2
            k++
        }
        if (!ok || k > 27) {
            return ""
        }
        if (k == 0) {
            return sign + "1.0"
        }
        var p: Int64 = 1
        var i: Int = 0
        while (i < k) {
            p = p * 5
            i++
        }
        var digits: Str = p.toString()
        var frac: Str = ""
        var z: Int = k - digits.size()
        while (z > 0) {
            frac = frac + "0"
            z--
        }
        return sign + "0." + frac + digits
    }
    // The fraction family: `0.<5^k>` where the fraction has exactly k digits and no
    // leading zero (`0.5`, `0.25`, `0.125` - deeper powers carry a leading zero, like
    // 0.0625, and decline here).
    if (!fmAllZeros(intPart) || fracPart.size() == 0 || fracPart[0] == '0') {
        return ""
    }
    var f: Int64 = 5
    var k: Int = 1
    while (k <= 3) {
        if (fracPart == f.toString()) {
            var r: Int64 = 1
            var j: Int = 0
            while (j < k) {
                r = r * 2
                j++
            }
            return sign + r.toString() + ".0"
        }
        f = f * 5
        k++
    }
    return ""
}

// ---- the declaration gate -----------------------------------------------------

// The symbol a declaration's `@SmGen` names. `GeneratorArgs` carries the arguments
// after the generator name (`generatorArgsText`), so the symbol is the *second* of them
// for `res` (section, symbol) and the first for `cpp`; "" when it is neither or spells
// none.
fun fmGeneratorSymbol(decl: *AstXmlNode): Str {
    val generator: Str = xmlAttr(decl, AstNodeAttributeKind.Generator)
    if (generator != "res" && generator != "cpp") {
        return ""
    }
    val args: Str = xmlAttr(decl, AstNodeAttributeKind.GeneratorArgs)
    if (args.size() == 0) {
        return ""
    }
    var want: Int = 1
    if (generator == "cpp") {
        want = 0
    }
    var seen: Int = -1
    var start: Int = 0
    var i: Int = 0
    while (i <= args.size()) {
        if (i == args.size() || args[i] == ',') {
            seen++
            if (seen == want) {
                return args.substr(start, i - start).trim()
            }
            start = i + 1
        }
        i++
    }
    return ""
}

// One declaration weighed: a table name stays foldable only while every declaration of
// it is the module's own body-less wrapper with the symbol this pass knows.
fun fmWeighDecl(decl: *AstXmlNode, seen: *Dictionary<Str, Bool>, refused: *Dictionary<Str, Bool>): Unit {
    if (decl.name != AstNodeKind.Function) {
        return
    }
    val name: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
    if (!fmIsMathName(name)) {
        return
    }
    seen.insert(name, true)
    // The wrappers are the body-less `@SmGen` declarations - which carry
    // `IsNative` themselves, so that mark is not part of the test: only a *body*
    // (a declaration the program wrote its own meaning into) refuses the name.
    val bodyless: Bool = xmlAttr(decl, AstNodeAttributeKind.HasBody) != "true"
    if (!bodyless || !fmIsMathSymbol(name, fmGeneratorSymbol(decl))) {
        refused.insert(name, true)
    }
}

fun fmCollectNames(module: *AstXmlNode, seen: *Dictionary<Str, Bool>, refused: *Dictionary<Str, Bool>): Unit {
    for (*decl in xmlDecls(module)) {
        fmWeighDecl(decl, *seen, *refused)
        if (decl.name == AstNodeKind.DataClass) {
            for (*method in xmlChildren(decl, AstNodeKind.Function)) {
                fmWeighDecl(method, *seen, *refused)
            }
        }
    }
}

// ---- the folds ----------------------------------------------------------------

// The identity fold on one call node: the replacement literal, or the node itself.
fun fmFoldCallAt(
    e: *AstXmlNode, seen: *Dictionary<Str, Bool>, refused: *Dictionary<Str, Bool>,
    state: *FmState
): AstXmlNode {
    val callee: *AstXmlNode = xmlChildPtr(e, AstNodeKind.Callee)
    if (xmlKind(callee) != AstNodeCategory.ExprName) {
        return *e
    }
    val name: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
    if (!seen.has(name) || refused.has(name)) {
        return *e
    }
    val args: List<AstXmlNode> = xmlChildren(e, AstNodeKind.Arg)
    if (fmKeepsZero(name)) {
        if (args.size() == 1) {
            val text: Str = fmLiteralText(args[0])
            if (text != "" && fmIsZeroText(text)) {
                state.changed = true
                return fmFloatLit(*e, fmZeroAsFloat(text))
            }
        }
        return *e
    }
    if (fmOneAtZero(name) || fmZeroAtOne(name)) {
        if (args.size() == 1) {
            val text: Str = fmLiteralText(args[0])
            var hit: Bool = fmIsZeroText(text)
            var answer: Str = "1.0"
            if (fmZeroAtOne(name)) {
                hit = fmIsOneText(text)
                answer = "0.0"
            }
            if (text != "" && hit) {
                state.changed = true
                return fmFloatLit(*e, answer)
            }
        }
        return *e
    }
    if (name == "sqrt") {
        if (args.size() == 1) {
            val text: Str = fmLiteralText(args[0])
            if (text != "") {
                if (fmIsZeroText(text)) {
                    state.changed = true
                    return fmFloatLit(*e, fmZeroAsFloat(text))
                }
                if (fmIsOneText(text)) {
                    state.changed = true
                    return fmFloatLit(*e, "1.0")
                }
            }
        }
        return *e
    }
    if (name == "abs") {
        if (args.size() == 1 && xmlKind(args[0]) == AstNodeCategory.ExprFloatLit) {
            val text: Str = xmlAttr(args[0], AstNodeAttributeKind.Text)
            if (text.size() > 0 && text[0] == '-') {
                state.changed = true
                return fmFloatLit(*e, text.substr(1, text.size() - 1))
            }
        }
        return *e
    }
    if (name == "pow") {
        if (args.size() == 2) {
            val exponent: Str = fmLiteralText(args[1])
            if (exponent != "" && fmIsZeroText(exponent)) {
                // C99: pow(x, +-0) is 1 for every x, NaN included.
                state.changed = true
                return fmFloatLit(*e, "1.0")
            }
            if (exponent != "" && fmIsOneText(exponent)
                && xmlKind(args[0]) == AstNodeCategory.ExprFloatLit
            ) {
                // pow(x, 1) is x; the argument stands in the call's place only when it
                // is already a float literal, so the call's type never moves.
                state.changed = true
                return args[0]
            }
            val base: Str = fmLiteralText(args[0])
            if (base != "" && fmIsOneText(base)) {
                // C99: pow(+1, y) is 1 for every y, NaN included.
                state.changed = true
                return fmFloatLit(*e, "1.0")
            }
        }
        return *e
    }
    if (name == "atan2") {
        if (args.size() == 2) {
            val yText: Str = fmLiteralText(args[0])
            val xText: Str = fmLiteralText(args[1])
            if (yText != "" && xText != "" && fmIsZeroText(yText) && fmIsOneText(xText)) {
                state.changed = true
                return fmFloatLit(*e, fmZeroAsFloat(yText))
            }
        }
        return *e
    }
    return *e
}

// The division peephole on one binary node: `x / <power of two>` becomes
// `x * <reciprocal>`, the operands' float-ness carried by the divisor's literal kind.
fun fmFoldDivAt(e: *AstXmlNode, state: *FmState): AstXmlNode {
    if (xmlKind(e) != AstNodeCategory.ExprBinary) {
        return *e
    }
    if (xmlAttr(e, AstNodeAttributeKind.Op) != "/") {
        return *e
    }
    val rhs: *AstXmlNode = xmlChildPtr(e, AstNodeKind.Rhs)
    if (xmlKind(rhs) != AstNodeCategory.ExprFloatLit) {
        return *e
    }
    val reciprocal: Str = fmPow2Reciprocal(xmlAttr(rhs, AstNodeAttributeKind.Text))
    if (reciprocal == "") {
        return *e
    }
    var attrs: List<AstNodeAttribute> = List<AstNodeAttribute>()
    for (attr in e.attributes) {
        if (attr.name == AstNodeAttributeKind.Op) {
            attrs.append(AstNodeAttribute(AstNodeAttributeKind.Op, "*"))
        } else {
            attrs.append(attr)
        }
    }
    var out: AstXmlNode = AstXmlNode(e.name, AstNodeCategory.ExprBinary, attrs, Array<AstXmlNode>())
    xmlAddChild(out, *xmlChildPtr(e, AstNodeKind.Lhs))
    xmlAddChild(out, fmFloatLit(*rhs, reciprocal))
    state.changed = true
    return out
}

// ---- the walk -----------------------------------------------------------------

// One node through both folds; a child that folded rebuilds its parent, the
// `foldExprsUnder` discipline. A lambda's body folds too (both rules are pure
// expression rewrites); type nodes are not expressions and are not entered.
fun fmRewrite(
    node: *AstXmlNode, seen: *Dictionary<Str, Bool>, refused: *Dictionary<Str, Bool>,
    state: *FmState
): AstXmlNode {
    val kind: AstNodeCategory = xmlKind(node)
    if (kind == AstNodeCategory.ExprLambda || kind == AstNodeCategory.TypeNamed
        || kind == AstNodeCategory.TypeGeneric || kind == AstNodeCategory.TypePointer
        || kind == AstNodeCategory.TypeReference || kind == AstNodeCategory.TypeFunction
    ) {
        return *node
    }
    var kids: List<AstXmlNode> = List<AstXmlNode>()
    var rebuilt: Bool = false
    var i: Int = 0
    while (i < node.Children.count()) {
        // A fresh state per child: "this subtree folded" is the child's own fact, and a
        // shared one-way latch cannot say it (an earlier sibling's fold would leave it
        // set, and every later child's return would look unchanged - or the reverse, a
        // parent entered after a fold discarding its own folded children).
        var childState: FmState = FmState(false)
        val rewritten: AstXmlNode = fmRewrite(*node.Children[i], *seen, *refused, *childState)
        if (childState.changed || rebuilt) {
            if (!rebuilt) {
                var j: Int = 0
                while (j < i) {
                    kids.append(node.Children[j])
                    j++
                }
                rebuilt = true
            }
            kids.append(rewritten)
        }
        if (childState.changed) {
            state.changed = true
        }
        i++
    }
    var here: AstXmlNode = *node
    if (rebuilt) {
        here = exprLike(*node, kids)
    }
    here = fmFoldCallAt(*here, *seen, *refused, *state)
    here = fmFoldDivAt(*here, *state)
    return here
}

// ---- the entry ----------------------------------------------------------------

// The pass: weigh the table's names against the compiled declarations, then rewrite
// the program modules through both folds. The prelude is not rewritten (the
// `ConstParams` rule: its bodies are the runtime's own, and the division peephole has
// nothing to find in them that the release build of a program cares about).
fun fmFoldMath(preludeModules: List<AstXmlNode>, modules: List<AstXmlNode>): List<AstXmlNode> {
    var seen: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    var refused: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    for (*pre in preludeModules) {
        fmCollectNames(pre, *seen, *refused)
    }
    for (*mod in modules) {
        fmCollectNames(mod, *seen, *refused)
    }
    var out: List<AstXmlNode> = List<AstXmlNode>()
    for (*mod in modules) {
        var state: FmState = FmState(false)
        out.append(fmRewrite(mod, *seen, *refused, *state))
    }
    return out
}
