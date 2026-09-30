// CgCall.kt
//
// The call forms and the inner expression spelling: `exprInner` and `call`. Extension
// methods on `Emitter` (Codegen.kt).

package codegen

import sema
import common
import linear
import optimizations
import profiling
import resources
import sourcegen


fun Emitter.exprInner(e: *AstXmlNode, expected: *AstXmlNode): Str {
    val kind: AstNodeCategory = xmlKind(e)
    when (kind) {
        AstNodeCategory.ExprIntLit, AstNodeCategory.ExprFloatLit, AstNodeCategory.ExprCharLit -> {
            return xmlAttr(e, AstNodeAttributeKind.Text)
        }

        AstNodeCategory.ExprStrLit -> {
            // A table entry is a `const Str` glvalue: a comparison or a `const Str&`
            // parameter binds it without building anything; an owned position copies it.
            return this.literals.spelling(xmlAttr(e, AstNodeAttributeKind.Text))
        }

        AstNodeCategory.ExprBoolLit -> {
            return xmlAttr(e, AstNodeAttributeKind.Value)
        }

        AstNodeCategory.ExprNullLit -> {
            return this.nullTo(expected)
        }

        AstNodeCategory.ExprName -> {
            val name: Str = xmlAttr(e, AstNodeAttributeKind.Name)
            if (name == "this") {
                // A value receiver is a raw pointer (`T* self`) in the emitted code, so
                // reading it reads through the pointer; a handle receiver is its handle.
                // Inside a closure class the receiver is C++'s `this`.
                if (this.inClosureMethod) {
                    if (this.selfKind == NameKind.Value) {
                        return "(*this)"
                    }
                    return "this"
                }
                if (this.selfKind == NameKind.Value) {
                    return "(*self)"
                }
                return "self"
            }
            if (this.localTypes.has(name)) {
                return name
            }
            // Not a local: a static carries its package's prefix, a top-level function used
            // as a value carries its function's, and a known prelude native resolves to its
            // symbol.
            val entry: *CgStatic = this.staticsByName.getPtr(name)
            if (entry != null) {
                return this.qualify(entry.packageName, name)
            }
            val pkg: Str = this.functionPackage(name)
            if (pkg != "") {
                return this.qualify(pkg, name)
            }
            val nativeOpt: Opt<Str> = this.nativeSymbols.get(name)
            if (nativeOpt.hasValue()) {
                return nativeOpt.value()
            }
            return name
        }

        AstNodeCategory.ExprGenericName -> {
            this.fail(
                e,
                fmtStr("unsupported: generic-qualified expression '|<...>'", xmlAttr(e, AstNodeAttributeKind.Name))
            )
            return "/*unsupported*/"
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
                val enumName: Str = xmlAttr(lhs, AstNodeAttributeKind.Name)
                return fmtStr(
                    "|::|",
                    this.qualify(this.typePackage(enumName), enumName),
                    xmlAttr(e, AstNodeAttributeKind.Name)
                )
            }
            return this.memberAccess(lhs, xmlAttr(e, AstNodeAttributeKind.Name))
        }

        AstNodeCategory.ExprCall -> {
            return this.call(e)
        }

        AstNodeCategory.ExprIndex -> {
            val lhs: *AstXmlNode = xmlChildPtr(e, AstNodeKind.Receiver)
            val baseExpr: Str = this.expr(lhs, 12, xmlEmptyNode())
            val baseType: AstXmlNode = this.inferType(lhs)
            var deref: Bool = false
            if (!xmlIsEmpty(baseType)) {
                if (this.isHandleType(baseType) && xmlKind(baseType) != AstNodeCategory.TypePointer) {
                    deref = true
                } else if (xmlKind(baseType) == AstNodeCategory.TypePointer) {
                    deref = this.isIndexableContainer(xmlChildPtr(baseType, AstNodeKind.Inner))
                }
            }
            if (deref) {
                return fmtStr(
                    "(*|)[|]",
                    baseExpr,
                    this.expr(xmlChildPtr(e, AstNodeKind.Index), 0, xmlEmptyNode())
                )
            }
            return fmtStr("|[|]", baseExpr, this.expr(xmlChildPtr(e, AstNodeKind.Index), 0, xmlEmptyNode()))
        }

        AstNodeCategory.ExprUnary -> {
            return xmlAttr(e, AstNodeAttributeKind.Op) + this.expr(
                xmlChildPtr(e, AstNodeKind.Operand),
                7,
                xmlEmptyNode()
            )
        }

        AstNodeCategory.ExprBinary -> {
            val lhs: *AstXmlNode = xmlChildPtr(e, AstNodeKind.Lhs)
            val rhs: *AstXmlNode = xmlChildPtr(e, AstNodeKind.Rhs)
            val op: Str = xmlAttr(e, AstNodeAttributeKind.Op)
            if (xmlKind(lhs) == AstNodeCategory.ExprNullLit || xmlKind(rhs) == AstNodeCategory.ExprNullLit) {
                var other: AstXmlNode = lhs
                if (xmlKind(lhs) == AstNodeCategory.ExprNullLit) {
                    other = rhs
                }
                val otherType: AstXmlNode = this.pointee(this.inferType(other))
                if (!xmlIsEmpty(otherType) && xmlKind(otherType) == AstNodeCategory.TypeGeneric
                    && xmlAttr(otherType, AstNodeAttributeKind.Name) == "Opt"
                ) {
                    val hasValue: Str = this.expr(other, 12, xmlEmptyNode()) + ".hasValue()"
                    if (op == "==") {
                        return fmtStr("(!|)", hasValue)
                    }
                    if (op == "!=") {
                        return fmtStr("(|)", hasValue)
                    }
                    this.fail(e, fmtStr("unsupported: Opt-vs-null comparison '|'", op))
                    return "/*unsupported*/"
                }
            }
            val p: Int = cgPrecedence(e)
            var lhsExpected: AstXmlNode = xmlEmptyNode()
            if (xmlKind(lhs) == AstNodeCategory.ExprNullLit) {
                lhsExpected = this.inferType(rhs)
            }
            var rhsExpected: AstXmlNode = xmlEmptyNode()
            if (xmlKind(rhs) == AstNodeCategory.ExprNullLit) {
                rhsExpected = this.inferType(lhs)
            }
            return fmtStr("| | |", this.expr(lhs, p, lhsExpected), op, this.expr(rhs, p + 1, rhsExpected))
        }

        AstNodeCategory.ExprLambda -> {
            // A lambda is a closure class, built by the instruction list; reaching here
            // means the lowering did not turn it into one (impl_specs/linear-il.md).
            this.fail(e, "unsupported: a lambda outside a closure construction")
            return "/*unsupported*/"
        }

        AstNodeCategory.ExprRef -> {
            val operandNode: *AstXmlNode = xmlChildPtr(e, AstNodeKind.Operand)
            if (xmlKind(operandNode) == AstNodeCategory.ExprCall) {
                val callee: *AstXmlNode = xmlChildPtr(operandNode, AstNodeKind.Callee)
                if (xmlKind(callee) == AstNodeCategory.ExprGenericName && xmlAttr(
                        callee,
                        AstNodeAttributeKind.Name
                    ) == "List"
                    && xmlCount(operandNode, AstNodeKind.Arg) == 0
                ) {
                    return fmtStr(
                        "makeList<|>()",
                        this.typeArgsString("List", xmlChildren(callee, AstNodeKind.TypeArg))
                    )
                }
            }
            val operand: Str = this.expr(operandNode, 0, xmlEmptyNode())
            return fmtStr(
                "makeRef<std::remove_cvref_t<decltype((|))>>(|)",
                operand, operand
            )
        }

        AstNodeCategory.ExprDeref -> {
            val operandNode: *AstXmlNode = xmlChildPtr(e, AstNodeKind.Operand)
            val operand: Str = this.expr(operandNode, 7, xmlEmptyNode())
            val operandType: AstXmlNode = this.inferType(operandNode)
            var nameKind: NameKind = NameKind.Value
            if (!xmlIsEmpty(operandType)) {
                nameKind = this.kindOf(operandType)
            } else {
                nameKind = this.operandKind(operandNode)
            }
            if (nameKind == NameKind.Shared) {
                return fmtStr("(|).get()", operand)
            }
            if (nameKind == NameKind.Value) {
                // `*value` is the address of the value, no copy (specs/memory-model.md): a
                // plain name is an lvalue (`&name`), anything else may be a temporary
                // (`simse_addressOf`).
                if (this.selfKind == NameKind.Value && xmlKind(operandNode) == AstNodeCategory.ExprName
                    && xmlAttr(operandNode, AstNodeAttributeKind.Name) == "this"
                ) {
                    // The receiver's address is the receiver: `*this` is `self`.
                    return this.selfPointer()
                }
                if (xmlKind(operandNode) == AstNodeCategory.ExprName) {
                    return "&" + operand
                }
                return fmtStr("simse_addressOf(|)", operand)
            }
            return "*" + operand
        }

        AstNodeCategory.ExprCopy -> {
            val operandNode: *AstXmlNode = xmlChildPtr(e, AstNodeKind.Operand)
            val operand: Str = this.expr(operandNode, 0, xmlEmptyNode())
            val operandType: AstXmlNode = this.inferType(operandNode)
            var nameKind: NameKind = NameKind.Value
            if (!xmlIsEmpty(operandType)) {
                nameKind = this.kindOf(operandType)
            } else {
                nameKind = this.operandKind(operandNode)
            }
            if (nameKind == NameKind.Shared || nameKind == NameKind.Pointer) {
                return fmtStr("*(|)", operand)
            }
            return fmtStr("(|)", operand)
        }
    }
    return "/*unsupported*/"
}

fun Emitter.call(e: *AstXmlNode): Str {
    val callee: *AstXmlNode = xmlChildPtr(e, AstNodeKind.Callee)
    val calleeKind: AstNodeCategory = xmlKind(callee)
    val argNodes: List<AstXmlNode> = xmlChildren(e, AstNodeKind.Arg)

    when (calleeKind) {
        AstNodeCategory.ExprGenericName -> {
            var args: List<Str> = List<Str>()
            for (*argNode in argNodes) {
                args.append(this.expr(argNode, 0, xmlEmptyNode()))
            }
            val name: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
            var calleeName: Str = this.qualify(this.functionPackage(name), name)
            val nativeOpt: Opt<Str> = this.nativeSymbols.get(name)
            var hasPlainFunction: Bool = false
            for (*candidate in this.functions) {
                if (xmlAttr(candidate.decl, AstNodeAttributeKind.IsNative) != "true"
                    && xmlAttr(candidate.decl, AstNodeAttributeKind.Name) == name
                ) {
                    hasPlainFunction = true
                }
            }
            if (!hasPlainFunction && nativeOpt.hasValue()) {
                calleeName = nativeOpt.value()
            }
            if (this.dataClassNames.has(name)) {
                // A construction is the aggregate's brace form, with the type arguments
                // spelled (`ns1_Box<Int>{1}`); CTAD covers the bare-name arm below.
                return fmtStr(
                    "|<|>{|}",
                    this.qualify(this.typePackage(name), name),
                    this.typeArgsString(name, xmlChildren(callee, AstNodeKind.TypeArg)),
                    cgJoin(args, ", ")
                )
            }
            return fmtStr(
                "|<|>(|)",
                calleeName,
                this.typeArgsString(name, xmlChildren(callee, AstNodeKind.TypeArg)),
                cgJoin(args, ", ")
            )
        }

        AstNodeCategory.ExprName -> {
            val name: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
            if (name == "println" || name == "print") {
                var arg: Str = ""
                if (argNodes.size() > 0) {
                    arg = this.expr(argNodes[0], 0, xmlEmptyNode())
                }
                // C stdio, not `std::cout` (the `print` resource): one `fwrite` per value.
                if (name == "println") {
                    return fmtStr("simse_println((|), stdout)", arg)
                }
                return fmtStr("simse_print((|), stdout)", arg)
            }
            val target: AstXmlNode = this.findFunction(name, argNodes.size())
            var args: List<Str> = List<Str>()
            val targetParams: List<AstXmlNode> = xmlChildren(target, AstNodeKind.Param)
            val targetReceiver: *AstXmlNode = xmlChildPtr(target, AstNodeKind.Receiver)
            for ((*arg, i) in argNodes) {
                var expectedArg: AstXmlNode = xmlEmptyNode()
                if (!xmlIsEmpty(target) && i < targetParams.size()) {
                    expectedArg = xmlChild(targetParams[i], AstNodeKind.Type)
                }
                // A receiver function called by name takes the receiver first, a value
                // receiver as its address (`T* self`).
                if (i == 0 && !xmlIsEmpty(targetReceiver)
                    && xmlAttr(target, AstNodeAttributeKind.HasReceiver) == "true"
                    && !this.isHandleType(targetReceiver)
                ) {
                    args.append(this.receiverArg(targetReceiver, argNodes[0]))
                } else {
                    args.append(this.expr(arg, 0, expectedArg))
                }
            }
            val nativeOpt: Opt<Str> = this.nativeSymbols.get(name)
            var hasPlainFunction: Bool = false
            for (*candidate in this.functions) {
                if (xmlAttr(candidate.decl, AstNodeAttributeKind.IsNative) != "true"
                    && xmlAttr(candidate.decl, AstNodeAttributeKind.Name) == name
                ) {
                    hasPlainFunction = true
                }
            }
            var calleeName: Str = this.qualify(this.functionPackage(name), name)
            if (!hasPlainFunction && nativeOpt.hasValue()) {
                calleeName = nativeOpt.value()
            }
            if (this.dataClassNames.has(name)) {
                // A construction without type arguments: the brace form, with C++20
                // aggregate CTAD deducing them.
                return fmtStr(
                    "|{|}", this.qualify(this.typePackage(name), name), cgJoin(args, ", ")
                )
            }
            return fmtStr("|(|)", calleeName, cgJoin(args, ", "))
        }

        AstNodeCategory.ExprMember -> {
            var args: List<Str> = List<Str>()
            for (*argNode in argNodes) {
                args.append(this.expr(argNode, 0, xmlEmptyNode()))
            }
            val calleeText: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
            val receiverExpr: *AstXmlNode = xmlChildPtr(callee, AstNodeKind.Receiver)

            // `getAs` is not a call: the extractor turns `h.getAs<T>()` into the IL's `Cast`
            // (`IlExtractor.call`), whose target is the destination's type - the IL names a
            // callee and never spells a signature, so the type argument cannot reach here.

            // `x.iter()` on a machine *is* `x` - the wrap a `for` puts around what it
            // iterates; `..T` is not spellable, so the identity is the backend's
            // (impl_specs/for.md).
            if (calleeText == "iter") {
                val identityRecv: AstXmlNode = this.pointee(this.inferType(receiverExpr))
                if (!xmlIsEmpty(identityRecv) && xmlKind(identityRecv) == AstNodeCategory.TypeYield) {
                    return this.expr(receiverExpr, 0, xmlEmptyNode())
                }
            }

            // Enum conversions: `x.toInt()` and `Enum.fromInt(v)`.
            if (calleeText == "toInt") {
                val enumReceiver: AstXmlNode = this.pointee(this.inferType(receiverExpr))
                if (!xmlIsEmpty(enumReceiver) && xmlKind(enumReceiver) == AstNodeCategory.TypeNamed
                    && this.enumNames.has(xmlAttr(enumReceiver, AstNodeAttributeKind.Name))
                ) {
                    return fmtStr("static_cast<Int>(|)", this.expr(receiverExpr, 12, xmlEmptyNode()))
                }
            }
            if (calleeText == "fromInt" && xmlKind(receiverExpr) == AstNodeCategory.ExprName
                && this.enumNames.has(xmlAttr(receiverExpr, AstNodeAttributeKind.Name))
            ) {
                val enumName: Str = xmlAttr(receiverExpr, AstNodeAttributeKind.Name)
                return fmtStr(
                    "|(|)",
                    this.qualify(this.typePackage(enumName), fmtStr("simse_|_fromInt", enumName)),
                    cgJoin(args, ", ")
                )
            }

            // The `Resources` API (`cppsrc/rtl/resources.kt`, specs/resources.md): a call
            // on a *type name*, like `Enum.fromInt` above; the declaration's symbol is what
            // the call reaches.
            if (xmlKind(receiverExpr) == AstNodeCategory.ExprName) {
                val staticSymbol: Str = this.staticCallSymbol(
                    xmlAttr(receiverExpr, AstNodeAttributeKind.Name), calleeText
                )
                if (staticSymbol != "") {
                    return fmtStr("|(|)", staticSymbol, cgJoin(args, ", "))
                }
            }
            if (xmlKind(receiverExpr) == AstNodeCategory.ExprGenericName) {
                val genericName: Str = xmlAttr(receiverExpr, AstNodeAttributeKind.Name)
                return fmtStr(
                    "|<|>::|(|)",
                    this.qualify(this.typePackage(genericName), genericName),
                    this.typeArgsString(genericName, xmlChildren(receiverExpr, AstNodeKind.TypeArg)),
                    calleeText,
                    cgJoin(args, ", ")
                )
            }

            val receiverType: AstXmlNode = this.inferType(receiverExpr)
            val receiver: AstXmlNode = this.pointee(receiverType)
            if (!xmlIsEmpty(receiver)) {
                val fnIndex: Int = this.findExtensionFn(calleeText, receiverExpr, args.size())
                if (fnIndex >= 0) {
                    val fn: *CgFn = *this.functions[fnIndex]
                    val all: Str = cgReceiverArgs(this.receiverArg(fn.receiver, receiverExpr), args)
                    return fmtStr("|(|)", this.qualify(fn.packageName, fn.name), all)
                }
                val extIndex: Int = this.findNativeExt(calleeText, receiverExpr, args.size())
                if (extIndex >= 0) {
                    val extensions: *List<CgNativeExt> = this.nativeExtensions.getPtr(calleeText)
                    val ext: *CgNativeExt = *extensions[extIndex]
                    // Record the reach: a native extension call is a symbol use, so the
                    // section that defines it must be emitted - a setter the lowering
                    // synthesizes has no call in the AST for `collectNames` to see.
                    this.referencedNames.insert(ext.symbol, true)
                    val all: Str = cgReceiverArgs(this.nativeReceiverArg(ext.receiver, receiverExpr), args)
                    return fmtStr("|(|)", ext.symbol, all)
                }
                return fmtStr("|(|)", this.memberAccess(receiverExpr, calleeText), cgJoin(args, ", "))
            }

            if (this.receiverFnNames.has(calleeText)) {
                val byName: Int = this.findReceiverFnByName(calleeText)
                var receiver: Str = ""
                if (byName >= 0) {
                    val caller: *CgFn = *this.functions[byName]
                    receiver = this.receiverArg(caller.receiver, receiverExpr)
                } else {
                    receiver = this.expr(receiverExpr, 12, xmlEmptyNode())
                }
                val all: Str = cgReceiverArgs(receiver, args)
                return fmtStr("|(|)", this.qualify(this.functionPackage(calleeText), calleeText), all)
            }
            val extensions: *List<CgNativeExt> = this.nativeExtensions.getPtr(calleeText)
            if (extensions != null) {
                if (extensions.size() > 0) {
                    this.referencedNames.insert(extensions[0].symbol, true)
                    val all: Str = cgReceiverArgs(this.expr(receiverExpr, 12, xmlEmptyNode()), args)
                    return fmtStr("|(|)", extensions[0].symbol, all)
                }
            }
            return fmtStr("|(|)", this.memberAccess(receiverExpr, calleeText), cgJoin(args, ", "))
        }
    }
    this.fail(e, "unsupported: call target")
    return "/*unsupported*/"
}

