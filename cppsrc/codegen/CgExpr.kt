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

// Re-roots `child` under `role` (a shallow copy whose element name changes).
fun Emitter.renameRole(child: *AstXmlNode, role: AstNodeKind): AstXmlNode {
    var renamed: AstXmlNode = child
    renamed.name = role
    return renamed
}

// The receiver argument for a lowered Simse call: a value receiver is a raw pointer, so
// the argument is the receiver's address (`simse_addressOf`, cppsrc/rtl/types.hpp); a
// counted reference is unwrapped with `.get()`, and a bare `this` is already that pointer.
fun Emitter.receiverArg(pattern: *AstXmlNode, recv: *AstXmlNode): Str {
    if (this.isHandleType(pattern)) {
        return this.expr(recv, 12, xmlEmptyNode())
    }
    if (this.selfKind == NameKind.Value && xmlKind(recv) == AstNodeCategory.ExprName
        && xmlAttr(recv, AstNodeAttributeKind.Name) == "this"
    ) {
        return this.selfPointer()
    }
    val recvType: AstXmlNode = this.inferType(recv)
    if (!xmlIsEmpty(recvType)) {
        val kind: AstNodeCategory = xmlKind(recvType)
        if (kind == AstNodeCategory.TypeReference
            || (kind == AstNodeCategory.TypeGeneric && xmlAttr(recvType, AstNodeAttributeKind.Name) == "PList")
        ) {
            return fmtStr("(|).get()", this.expr(recv, 12, xmlEmptyNode()))
        }
        if (kind == AstNodeCategory.TypePointer) {
            return this.expr(recv, 12, xmlEmptyNode())
        }
    }
    return fmtStr("simse_addressOf(|)", this.expr(recv, 12, xmlEmptyNode()))
}

// The emitted receiver as the raw pointer it already is: `self`, or C++'s `this` inside a
// closure class. That pointer is the receiver's address, so a borrow of `this` spells no
// dereference.
fun Emitter.selfPointer(): Str {
    if (this.inClosureMethod) {
        return "this"
    }
    return "self"
}

// The receiver argument for a lowered native call: the host signature decides the form, so
// the expression passes as it is, dereferenced through a handle (the RTL's value receivers
// are `T&`).
fun Emitter.nativeReceiverArg(pattern: *AstXmlNode, recv: *AstXmlNode): Str {
    if (this.isHandleType(pattern)) {
        return this.expr(recv, 12, xmlEmptyNode())
    }
    val recvType: AstXmlNode = this.inferType(recv)
    if (this.isHandleType(recvType)) {
        return fmtStr("(*|)", this.expr(recv, 12, xmlEmptyNode()))
    }
    return this.expr(recv, 12, xmlEmptyNode())
}

// Index into `functions` of the first Simse-declared receiver function with this name, or -1.
fun Emitter.findReceiverFnByName(name: *Str): Int {
    for ((*fn, i) in this.functions) {
        if (fn.isNative || xmlIsEmpty(fn.receiver)) {
            continue
        }
        if (fn.name == name) {
            return i
        }
    }
    return -1
}

// Index into `functions` of a Simse extension matching the receiver and arity, or -1.
fun Emitter.findExtensionFn(name: *Str, recvExpr: *AstXmlNode, argCount: Int): Int {
    val recvType: AstXmlNode = this.inferType(recvExpr)
    val recv: AstXmlNode = this.resolveAlias(this.pointee(recvType))
    if (xmlIsEmpty(recv)) {
        return -1
    }
    for ((*fn, i) in this.functions) {
        if (fn.isNative || xmlIsEmpty(fn.receiver)) {
            continue
        }
        if (fn.name == name && fn.paramCount == argCount && this.unifyType(
                this.resolveAlias(fn.receiver),
                recv,
                fn.templateParams
            )
        ) {
            return i
        }
    }
    return -1
}

// Index into `nativeExtensions[name]` of a matching receiver, or -1.
fun Emitter.findNativeExt(name: *Str, recvExpr: *AstXmlNode, argCount: Int): Int {
    val extensions: *List<CgNativeExt> = this.nativeExtensions.getPtr(name)
    if (extensions == null) {
        return -1
    }
    val recvType: AstXmlNode = this.inferType(recvExpr)
    val recv: AstXmlNode = this.resolveAlias(this.pointee(recvType))
    if (xmlIsEmpty(recv)) {
        return -1
    }
    for ((*ext, i) in extensions) {
        if (!xmlIsEmpty(ext.receiver) && ext.argCount == argCount
            && this.unifyType(this.resolveAlias(ext.receiver), recv, ext.typeParams)
        ) {
            return i
        }
    }
    return -1
}

fun Emitter.memberAccess(base: *AstXmlNode, name: *Str): Str {
    var arrow: Bool = false
    val baseType: AstXmlNode = this.inferType(base)
    if (xmlKind(base) == AstNodeCategory.ExprName
        && xmlAttr(base, AstNodeAttributeKind.Name) == "this" && this.selfKind == NameKind.Value
    ) {
        // A value receiver is a raw pointer (`T* self`), so its members are reached with `->`.
        arrow = true
    } else if (!xmlIsEmpty(baseType)) {
        arrow = this.isHandleType(baseType)
    } else if (xmlKind(base) == AstNodeCategory.ExprName) {
        val baseName: Str = xmlAttr(base, AstNodeAttributeKind.Name)
        if (baseName == "this") {
            arrow = this.selfKind != NameKind.Value
        } else {
            val kind: *NameKind = this.nameKinds.getPtr(baseName)
            if (kind != null && *kind == NameKind.Shared) {
                arrow = true
            }
        }
    }
    var field: Str = name
    val recv: AstXmlNode = this.pointee(baseType)
    if (!xmlIsEmpty(recv) && xmlKind(recv) == AstNodeCategory.TypeGeneric && xmlAttr(
            recv,
            AstNodeAttributeKind.Name
        ) == "Res"
    ) {
        if (name == "value") {
            field = "Value"
        } else if (name == "error") {
            field = "Error"
        }
    }
    var op: Str = "."
    if (arrow) {
        op = "->"
    }
    // A value receiver's own member access reads through its pointer (`self->field`); the
    // bare name `this` elsewhere reads as the object (`(*self)`).
    if (xmlKind(base) == AstNodeCategory.ExprName && xmlAttr(base, AstNodeAttributeKind.Name) == "this"
        && this.selfKind == NameKind.Value
    ) {
        if (this.inClosureMethod) {
            return "this->" + field
        }
        return "self->" + field
    }
    return this.expr(base, 12, xmlEmptyNode()) + op + field
}

fun Emitter.nullTo(expected: *AstXmlNode): Str {
    if (!xmlIsEmpty(expected) && xmlKind(expected) == AstNodeCategory.TypeGeneric && xmlAttr(
            expected,
            AstNodeAttributeKind.Name
        ) == "Opt"
    ) {
        return fmtStr(
            "Opt<|>()",
            this.typeArgsString("Opt", xmlChildren(expected, AstNodeKind.TypeArg))
        )
    }
    return "nullptr"
}

// A receiver's type is resolved through a `typealias` before matching (`StrView` is
// `Span<Char>`, cppsrc/rtl/StrView.kt), and so is the declared receiver, so the match works
// from either side. A non-alias type is returned as it came in.
fun Emitter.resolveAlias(typeNode: *AstXmlNode): AstXmlNode {
    var current: AstXmlNode = typeNode
    var guard: Int = 0
    while (!xmlIsEmpty(current) && xmlKind(current) == AstNodeCategory.TypeNamed) {
        guard = guard + 1
        if (guard >= 100) {
            break
        }
        val name: Str = xmlAttr(current, AstNodeAttributeKind.Name)
        val decl: *AstXmlNode = this.types.getPtr(name)
        if (decl == null) {
            break
        }
        if (decl.name != AstNodeKind.TypeAlias) {
            break
        }
        val target: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.TargetType)
        if (xmlIsEmpty(target)) {
            break
        }
        current = target
    }
    return current
}

fun Emitter.expectedCallable(expected: *AstXmlNode): AstXmlNode {
    val resolved: AstXmlNode = this.resolveAlias(expected)
    if (!xmlIsEmpty(resolved) && xmlKind(resolved) == AstNodeCategory.TypeFunction) {
        return resolved
    }
    return xmlEmptyNode()
}

// A non-native function with the given name and parameter count; a method is not one.
fun Emitter.findFunction(name: *Str, argCount: Int): AstXmlNode {
    for (*fn in this.functions) {
        if (fn.isNative || fn.isMethod
            || fn.name != name
        ) {
            continue
        }
        if (xmlCount(fn.decl, AstNodeKind.Param) == argCount) {
            return fn.decl
        }
    }
    return xmlEmptyNode()
}

fun Emitter.isUnitType(typeNode: *AstXmlNode): Bool {
    if (xmlIsEmpty(typeNode)) {
        return true
    }
    return xmlKind(typeNode) == AstNodeCategory.TypeNamed && xmlAttr(typeNode, AstNodeAttributeKind.Name) == "Unit"
}
