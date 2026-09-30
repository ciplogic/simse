// CgExpr.kt
//
// Spelling expressions: the read-through conversion, the type table's lookups,
// extension/overload resolution, member access, and `inferType`. Extension methods on
// `Emitter` (Codegen.kt).

package codegen

import sema
import common
import linear
import optimizations
import profiling
import resources
import sourcegen


fun Emitter.expr(e: *AstXmlNode, minPrec: Int, expected: *AstXmlNode): Str {
    // The dst-driven half of the conversion table (`impl_specs/linear-il.md`): a `*T`/`&T`
    // spelled where a `T` is expected is read through, so a use need not spell the `*`.
    if (this.cgNeedsReadThrough(e, expected)) {
        return fmtStr("*(|)", this.expr(e, 0, xmlEmptyNode()))
    }
    val p: Int = cgPrecedence(e)
    var s: Str = ""
    if (p < minPrec) {
        s = s + "("
    }
    s = s + this.exprInner(e, expected)
    if (p < minPrec) {
        s = s + ")"
    }
    return s
}

// Whether `e` has to be read through to be spelled as `expected` - the same type modulo
// the handle. It must not convert with no type expected, nor a value *into* a handle
// (that `*T` binding is the writer's to spell, `specs/memory-model.md`).
fun Emitter.cgNeedsReadThrough(e: *AstXmlNode, expected: *AstXmlNode): Bool {
    if (xmlIsEmpty(expected) || ilIsHandleType(expected)) {
        return false
    }
    // `copy(v)` is the conversion already spelled, by the extractor or by the writer.
    if (xmlKind(e) == AstNodeCategory.ExprCopy) {
        return false
    }
    val have: AstXmlNode = this.inferType(e)
    if (xmlIsEmpty(have) || !ilIsHandleType(have)) {
        return false
    }
    val pointee: AstXmlNode = semPointeeOf(have)
    if (xmlIsEmpty(pointee)) {
        return false
    }
    return ilTypeText(pointee) == ilTypeText(expected)
}

fun Emitter.operandKind(e: *AstXmlNode): NameKind {
    if (xmlKind(e) == AstNodeCategory.ExprName) {
        val name: Str = xmlAttr(e, AstNodeAttributeKind.Name)
        if (name == "this") {
            return this.selfKind
        }
        val kind: *NameKind = this.nameKinds.getPtr(name)
        if (kind != null) {
            return *kind
        }
    }
    return NameKind.Value
}

fun Emitter.namedType(name: *Str): AstXmlNode {
    return this.namedTypeExpr(name)
}

fun Emitter.pointee(typeNode: *AstXmlNode): AstXmlNode {
    var current: AstXmlNode = typeNode
    while (!xmlIsEmpty(current)) {
        val kind: AstNodeCategory = xmlKind(current)
        if ((kind == AstNodeCategory.TypeReference || kind == AstNodeCategory.TypePointer)) {
            val inner: AstXmlNode = xmlChild(current, AstNodeKind.Inner)
            if (xmlIsEmpty(inner)) {
                return current
            }
            current = inner
        } else {
            return current
        }
    }
    return current
}

fun Emitter.isHandleType(typeNode: *AstXmlNode): Bool {
    if (xmlIsEmpty(typeNode)) {
        return false
    }
    val kind: AstNodeCategory = xmlKind(typeNode)
    if (kind == AstNodeCategory.TypeReference || kind == AstNodeCategory.TypePointer) {
        return true
    }
    if (kind == AstNodeCategory.TypeGeneric && xmlAttr(typeNode, AstNodeAttributeKind.Name) == "PList") {
        return true
    }
    return false
}

fun Emitter.isIndexableContainer(typeNode: *AstXmlNode): Bool {
    if (xmlIsEmpty(typeNode)) {
        return false
    }
    val kind: AstNodeCategory = xmlKind(typeNode)
    when (kind) {
        AstNodeCategory.TypeNamed -> {
            return xmlAttr(typeNode, AstNodeAttributeKind.Name) == "Str"
        }

        AstNodeCategory.TypeGeneric -> {
            val name: Str = xmlAttr(typeNode, AstNodeAttributeKind.Name)
            return name == "List" || name == "Array" || name == "Dictionary" || name == "SmallVector" || name == "Span"
        }
    }
    return false
}

fun Emitter.unifyType(pattern: *AstXmlNode, actual: *AstXmlNode, typeParams: *List<Str>): Bool {
    var actualPtr: AstXmlNode = actual
    val pk: AstNodeCategory = xmlKind(pattern)
    if (pk != AstNodeCategory.TypeReference && pk != AstNodeCategory.TypePointer) {
        while (true) {
            val ak0: AstNodeCategory = xmlKind(actualPtr)
            if ((ak0 == AstNodeCategory.TypeReference || ak0 == AstNodeCategory.TypePointer)) {
                val inner: AstXmlNode = xmlChild(actualPtr, AstNodeKind.Inner)
                if (xmlIsEmpty(inner)) {
                    break
                }
                actualPtr = inner
            } else {
                break
            }
        }
    }
    val ak: AstNodeCategory = xmlKind(actualPtr)
    when (pk) {
        AstNodeCategory.TypeIntLit -> {
            return ak == AstNodeCategory.TypeIntLit && xmlAttr(
                actualPtr,
                AstNodeAttributeKind.Text
            ) == xmlAttr(pattern, AstNodeAttributeKind.Text)
        }

        AstNodeCategory.TypeNamed -> {
            if (xmlIsTypeParam(xmlAttr(pattern, AstNodeAttributeKind.Name), typeParams)) {
                return true
            }
            return ak == AstNodeCategory.TypeNamed && xmlAttr(actualPtr, AstNodeAttributeKind.Name) == xmlAttr(
                pattern,
                AstNodeAttributeKind.Name
            )
        }

        AstNodeCategory.TypeGeneric -> {
            if (xmlIsTypeParam(xmlAttr(pattern, AstNodeAttributeKind.Name), typeParams)) {
                return true
            }
            if (ak != AstNodeCategory.TypeGeneric) {
                return false
            }
            val patternName: Str = xmlAttr(pattern, AstNodeAttributeKind.Name)
            val actualName: Str = xmlAttr(actualPtr, AstNodeAttributeKind.Name)
            if (actualName != patternName
                && !(patternName == "List" && actualName == "PList")
                && !(patternName == "PList" && actualName == "List")
            ) {
                return false
            }
            val patternArgs: List<AstXmlNode> = xmlChildren(pattern, AstNodeKind.TypeArg)
            val actualArgs: List<AstXmlNode> = xmlChildren(actualPtr, AstNodeKind.TypeArg)
            if (patternArgs.size() != actualArgs.size()) {
                return false
            }
            var i: Int = 0
            while (i < patternArgs.size()) {
                if (!this.unifyType(patternArgs[i], actualArgs[i], typeParams)) {
                    return false
                }
                i = i + 1
            }
            return true
        }

        AstNodeCategory.TypeReference -> {
            if (ak == AstNodeCategory.TypeReference && !xmlIsEmpty(xmlChildPtr(actualPtr, AstNodeKind.Inner))
                && !xmlIsEmpty(xmlChildPtr(pattern, AstNodeKind.Inner))
            ) {
                return this.unifyType(
                    xmlChildPtr(pattern, AstNodeKind.Inner),
                    xmlChildPtr(actualPtr, AstNodeKind.Inner),
                    typeParams
                )
            }
            return false
        }

        AstNodeCategory.TypePointer -> {
            if (ak == AstNodeCategory.TypePointer && !xmlIsEmpty(xmlChildPtr(actualPtr, AstNodeKind.Inner))
                && !xmlIsEmpty(xmlChildPtr(pattern, AstNodeKind.Inner))
            ) {
                return this.unifyType(
                    xmlChildPtr(pattern, AstNodeKind.Inner),
                    xmlChildPtr(actualPtr, AstNodeKind.Inner),
                    typeParams
                )
            }
            return false
        }
        // `..T` is a state machine and carries its element type like a pointer its
        // pointee, so a pattern `..T` matches `..Int` element-wise (impl_specs/yield.md).
        AstNodeCategory.TypeYield -> {
            if (ak == AstNodeCategory.TypeYield && !xmlIsEmpty(xmlChildPtr(actualPtr, AstNodeKind.Inner))
                && !xmlIsEmpty(xmlChildPtr(pattern, AstNodeKind.Inner))
            ) {
                return this.unifyType(
                    xmlChildPtr(pattern, AstNodeKind.Inner),
                    xmlChildPtr(actualPtr, AstNodeKind.Inner),
                    typeParams
                )
            }
            return false
        }
    }
    return false
}

fun Emitter.functionReturn(name: *Str): AstXmlNode {
    for (*fn in this.functions) {
        if (fn.name == name) {
            val ret: *AstXmlNode = xmlChildPtr(fn.decl, AstNodeKind.ReturnType)
            if (!xmlIsEmpty(ret)) {
                return ret
            }
        }
    }
    return xmlEmptyNode()
}

fun Emitter.memberCallReturn(callee: *AstXmlNode): AstXmlNode {
    val receiverType: AstXmlNode = this.inferType(xmlChildPtr(callee, AstNodeKind.Receiver))
    val recv: AstXmlNode = this.resolveAlias(this.pointee(receiverType))
    val calleeText: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
    for (*fn in this.functions) {
        if (fn.isNative || xmlIsEmpty(fn.receiver)) {
            continue
        }
        if (fn.name != calleeText) {
            continue
        }
        val ret: *AstXmlNode = xmlChildPtr(fn.decl, AstNodeKind.ReturnType)
        if (!xmlIsEmpty(recv) && this.unifyType(this.resolveAlias(fn.receiver), recv, fn.templateParams)
            && !xmlIsEmpty(ret)
        ) {
            return ret
        }
    }
    val extensions: *List<CgNativeExt> = this.nativeExtensions.getPtr(calleeText)
    if (extensions != null) {
        for (*ext in extensions) {
            if (!xmlIsEmpty(recv) && !xmlIsEmpty(ext.receiver)
                && this.unifyType(this.resolveAlias(ext.receiver), recv, ext.typeParams)
                && !xmlIsEmpty(ext.returnType)
            ) {
                return ext.returnType
            }
        }
    }
    if (!xmlIsEmpty(recv) && xmlKind(recv) == AstNodeCategory.TypeGeneric) {
        val typeArgs: List<AstXmlNode> = xmlChildren(recv, AstNodeKind.TypeArg)
        if (calleeText == "value" && xmlAttr(recv, AstNodeAttributeKind.Name) == "Opt" && typeArgs.size() > 0) {
            return typeArgs[0]
        }
    }
    if (!xmlIsEmpty(recv) && (calleeText == "size" || calleeText == "count")) {
        val recvName: Str = xmlAttr(recv, AstNodeAttributeKind.Name)
        if (recvName == "List" || recvName == "Str" || recvName == "Array"
            || recvName == "Dictionary" || recvName == "SmallVector"
        ) {
            return this.namedType("Int")
        }
    }
    if (calleeText == "isOk" || calleeText == "hasValue") {
        return this.namedType("Bool")
    }
    if (!xmlIsEmpty(recv) && xmlKind(recv) == AstNodeCategory.TypeYield) {
        // A machine's own surface (`impl_specs/for.md`): `advance()` answers whether there
        // was a value, `current` is the field holding it; the type pass answers both the
        // same way.
        if (calleeText == "advance") {
            return this.namedType("Bool")
        }
        if (calleeText == "current") {
            return xmlChild(recv, AstNodeKind.Inner)
        }
    }
    // An enum's conversions (`specs/declarations.md`): `toInt()` is the member's value,
    // `fromInt(n)` the unchecked cast back.
    if (!xmlIsEmpty(recv) && xmlKind(recv) == AstNodeCategory.TypeNamed
        && this.enumNames.has(xmlAttr(recv, AstNodeAttributeKind.Name))
    ) {
        if (calleeText == "toInt") {
            return this.namedType("Int")
        }
        if (calleeText == "fromInt") {
            return this.renameRole(recv, AstNodeKind.Type)
        }
    }
    return xmlEmptyNode()
}

fun Emitter.inferType(e: *AstXmlNode): AstXmlNode {
    val kind: AstNodeCategory = xmlKind(e)
    when (kind) {
        AstNodeCategory.ExprIntLit -> {
            return this.namedType("Int")
        }

        AstNodeCategory.ExprFloatLit -> {
            return this.namedType("Float64")
        }

        AstNodeCategory.ExprStrLit -> {
            return this.namedType("Str")
        }

        AstNodeCategory.ExprCharLit -> {
            return this.namedType("Char")
        }

        AstNodeCategory.ExprBoolLit -> {
            return this.namedType("Bool")
        }

        AstNodeCategory.ExprNullLit -> {
            return xmlEmptyNode()
        }

        AstNodeCategory.ExprName -> {
            val name: Str = xmlAttr(e, AstNodeAttributeKind.Name)
            if (name == "this") {
                return this.selfType
            }
            val localType: *AstXmlNode = this.localTypes.getPtr(name)
            if (localType != null) {
                return *localType
            }
            val staticNode: AstXmlNode = this.staticType(name)
            if (!xmlIsEmpty(staticNode)) {
                return staticNode
            }
            if (this.enumNames.has(name)) {
                return this.namedType(name)
            }
            return xmlEmptyNode()
        }

        AstNodeCategory.ExprGenericName -> {
            return this.genericTypeExpr(xmlAttr(e, AstNodeAttributeKind.Name), xmlChildren(e, AstNodeKind.TypeArg))
        }

        AstNodeCategory.ExprMember -> {
            val lhs: *AstXmlNode = xmlChildPtr(e, AstNodeKind.Receiver)
            if (xmlKind(lhs) == AstNodeCategory.ExprName && this.enumNames.has(
                    copy(
                        xmlAttr(
                            lhs,
                            AstNodeAttributeKind.Name
                        )
                    )
                )
            ) {
                return this.namedType(xmlAttr(lhs, AstNodeAttributeKind.Name))
            }
            val baseType: AstXmlNode = this.inferType(lhs)
            val base: AstXmlNode = this.pointee(baseType)
            if (xmlIsEmpty(base)) {
                return xmlEmptyNode()
            }
            val memberText: Str = xmlAttr(e, AstNodeAttributeKind.Name)
            if (xmlKind(base) == AstNodeCategory.TypeYield) {
                // A machine's own field (`impl_specs/for.md`): what it last yielded.
                if (memberText == "current") {
                    return xmlChild(base, AstNodeKind.Inner)
                }
            }
            if (xmlKind(base) == AstNodeCategory.TypeGeneric && xmlAttr(
                    base,
                    AstNodeAttributeKind.Name
                ) == "Res"
            ) {
                val typeArgs: List<AstXmlNode> = xmlChildren(base, AstNodeKind.TypeArg)
                if (memberText == "value" && typeArgs.size() > 0) {
                    return typeArgs[0]
                }
                if (memberText == "error") {
                    return this.namedType("Str")
                }
            }
            val baseKind: AstNodeCategory = xmlKind(base)
            if (baseKind == AstNodeCategory.TypeNamed || baseKind == AstNodeCategory.TypeGeneric) {
                val baseName: Str = xmlAttr(base, AstNodeAttributeKind.Name)
                val decl: *AstXmlNode = this.types.getPtr(baseName)
                if (decl != null) {
                    if (decl.name == AstNodeKind.DataClass) {
                        val fields: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Field)
                        for (*field in fields) {
                            if (xmlAttr(field, AstNodeAttributeKind.Name) == memberText) {
                                return xmlChild(field, AstNodeKind.Type)
                            }
                        }
                    }
                }
            }
            return xmlEmptyNode()
        }

        AstNodeCategory.ExprCall -> {
            val callee: *AstXmlNode = xmlChildPtr(e, AstNodeKind.Callee)
            val calleeKind: AstNodeCategory = xmlKind(callee)
            if (calleeKind == AstNodeCategory.ExprGenericName) {
                val name: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
                if (this.types.has(name) || semIsRtlTypeName(name)) {
                    return this.genericTypeExpr(name, xmlChildren(callee, AstNodeKind.TypeArg))
                }
                return this.functionReturn(name)
            }
            if (calleeKind == AstNodeCategory.ExprName) {
                val name: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
                if (this.types.has(name) || semIsRtlTypeName(name)) {
                    return this.namedType(name)
                }
                return this.functionReturn(name)
            }
            if (calleeKind == AstNodeCategory.ExprMember) {
                return this.memberCallReturn(callee)
            }
            return xmlEmptyNode()
        }

        AstNodeCategory.ExprIndex -> {
            val baseType: AstXmlNode = this.inferType(xmlChildPtr(e, AstNodeKind.Receiver))
            val base: AstXmlNode = this.pointee(baseType)
            if (xmlIsEmpty(base)) {
                return xmlEmptyNode()
            }
            if (xmlKind(base) == AstNodeCategory.TypeNamed && xmlAttr(base, AstNodeAttributeKind.Name) == "Str") {
                return this.namedType("Char")
            }
            if (xmlKind(base) != AstNodeCategory.TypeGeneric) {
                return xmlEmptyNode()
            }
            val typeArgs: List<AstXmlNode> = xmlChildren(base, AstNodeKind.TypeArg)
            if (typeArgs.size() == 0) {
                return xmlEmptyNode()
            }
            val baseName: Str = xmlAttr(base, AstNodeAttributeKind.Name)
            if (baseName == "SmallVector" && typeArgs.size() == 2) {
                return typeArgs[1]
            }
            if (baseName == "Dictionary" && typeArgs.size() == 2) {
                return typeArgs[1]
            }
            return typeArgs[0]
        }

        AstNodeCategory.ExprRef -> {
            var node: AstXmlNode = AstXmlNode(
                AstNodeKind.Type,
                AstNodeCategory.TypeReference,
                List<AstNodeAttribute>(),
                Array<AstXmlNode>()
            )
            xmlAddChild(
                node,
                this.renameRole(this.inferType(xmlChildPtr(e, AstNodeKind.Operand)), AstNodeKind.Inner)
            )
            return node
        }

        AstNodeCategory.ExprDeref -> {
            // `*x` is the address of what `x` denotes: a value's storage (`&x`), a counted
            // reference's pointee (`x.get()`), or the pointer itself - in which case the
            // type is the pointee. `SemInfer.infer` spells the same three cases.
            val operand: AstXmlNode = this.inferType(xmlChildPtr(e, AstNodeKind.Operand))
            if (xmlIsEmpty(operand)) {
                return xmlEmptyNode()
            }
            val operandKind: AstNodeCategory = xmlKind(operand)
            if (operandKind == AstNodeCategory.TypePointer) {
                val pointee: *AstXmlNode = xmlChildPtr(operand, AstNodeKind.Inner)
                if (xmlIsEmpty(pointee)) {
                    return xmlEmptyNode()
                }
                return this.renameRole(pointee, AstNodeKind.Type)
            }
            var node: AstXmlNode =
                AstXmlNode(
                    AstNodeKind.Type,
                    AstNodeCategory.TypePointer,
                    List<AstNodeAttribute>(),
                    Array<AstXmlNode>()
                )
            var inner: AstXmlNode = operand
            if (operandKind == AstNodeCategory.TypeReference) {
                inner = xmlChild(operand, AstNodeKind.Inner)
            }
            if (xmlIsEmpty(inner)) {
                return xmlEmptyNode()
            }
            xmlAddChild(node, this.renameRole(inner, AstNodeKind.Inner))
            return node
        }

        AstNodeCategory.ExprCopy, AstNodeCategory.ExprUnary -> {
            return this.inferType(xmlChildPtr(e, AstNodeKind.Operand))
        }

        AstNodeCategory.ExprBinary -> {
            val op: Str = xmlAttr(e, AstNodeAttributeKind.Op)
            if (op == "==" || op == "!=" || op == "<" || op == ">" || op == "<=" || op == ">="
                || op == "&&" || op == "||"
            ) {
                return this.namedType("Bool")
            }
            // The operation is on values: a handle operand is read through to its pointee -
            // the `*T -> T` row, spelled at the operand (`binaryOperand`).
            return this.pointee(this.inferType(xmlChildPtr(e, AstNodeKind.Lhs)))
        }

        AstNodeCategory.ExprLambda -> {
            var fnType: AstXmlNode = AstXmlNode(
                AstNodeKind.Type,
                AstNodeCategory.TypeFunction,
                List<AstNodeAttribute>(),
                Array<AstXmlNode>()
            )
            val paramTypes: List<AstXmlNode> = xmlChildren(e, AstNodeKind.ParamType)
            xmlAddChildren(fnType, paramTypes)
            return fnType
        }
    }
    return xmlEmptyNode()
}

