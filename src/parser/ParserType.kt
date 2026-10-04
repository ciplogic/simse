// ParserType.kt
//
// Type expressions and signatures: `parseType` and its generic/pointer/function forms, the
// type parameters a declaration introduces, and `parseFunction`. Extension methods on
// `Parser` (Parser.kt).

package parser
import compiler

import lex
import common

fun Parser.looksLikeTypeStart(): Bool {
    // `..` starts a `..T` receiver: `fun ..T.iter<T>()` is the wrap that makes a
    // machine iterable like any other source (impl_specs/for.md).
    return this.checkKind(TokenKind.Identifier)
            || this.checkText("(") || this.checkText("&") || this.checkText("*")
            || this.checkText("..")
}

fun Parser.parseParamName(): Str {
    if (this.checkKind(TokenKind.Identifier) || this.checkText("this")) {
        return this.advance().text
    }
    this.fail("expected parameter name")
    return ""
}

// `attrName`/`attrArgs` are the parsed attribute (specs/attributes.md). An attributed
// method may be body-less; a body-less method with no attribute has no implementation.
// `pure`/`suspendModifier`/`borrowModifier`/`operatorModifier` are the
// `data`/`suspend`/`borrow`/`operator` modifiers, carried as
// `IsPure`/`IsSuspend`/`IsBorrow`/`IsOperator`; `parseDecl` says what each promises.
fun Parser.parseFunction(
    attrName: *Str, attrArgs: *List<Str>, pure: Bool, suspendModifier: Bool,
    borrowModifier: Bool, operatorModifier: Bool
): AstXmlNode {
    val pos: SourcePos = this.peek(0).pos
    var nativeSymbol: Str = ""
    var hasNativeSymbol: Bool = false

    if (!this.expectText("fun")) {
        return this.emptyNode()
    }

    var hasReceiver: Bool = false
    var receiverNode: AstXmlNode = this.emptyNode()
    var declName: Str = ""

    if (this.looksLikeTypeStart()) {
        val savedCursor: Span<Token> = this.cursor
        val savedFailed: Bool = this.failed
        val savedError: Str = this.error
        val receiver: AstXmlNode = this.parseType(AstNodeKind.Receiver)
        if (!this.failed && this.checkText(".")) {
            this.advance()
            val name: Str = this.expectName()
            if (this.failed) {
                return this.emptyNode()
            }
            hasReceiver = true
            receiverNode = receiver
            declName = name
        } else {
            this.cursor = savedCursor
            this.failed = savedFailed
            this.error = savedError
        }
    }
    if (!hasReceiver) {
        declName = this.expectName()
        if (this.failed) {
            return this.emptyNode()
        }
    }

    var functionTypeParams: List<Str> = List<Str>()
    if (this.checkText("<")) {
        functionTypeParams = this.parseTypeParams()
        if (this.failed) {
            return this.emptyNode()
        }
    }

    if (!this.expectText("(")) {
        return this.emptyNode()
    }
    this.skipNewlines()
    var params: List<AstXmlNode> = List<AstXmlNode>()
    while (!this.checkText(")") && !this.atEnd() && !this.failed) {
        val paramPos: SourcePos = this.peek(0).pos
        val paramName: Str = this.parseParamName()
        if (this.failed) {
            return this.emptyNode()
        }
        var paramType: AstXmlNode = this.emptyNode()
        if (this.matchText(":")) {
            paramType = this.parseType(AstNodeKind.Type)
            if (this.failed) {
                return this.emptyNode()
            }
        }
        var pattrs: List<AstNodeAttribute> = listOf<AstNodeAttribute>(
            AstNodeAttribute(AstNodeAttributeKind.Name, paramName),
            AstNodeAttribute(AstNodeAttributeKind.Line, paramPos.line.toString()),
            AstNodeAttribute(AstNodeAttributeKind.Column, paramPos.column.toString())
        )
        var pnode: AstXmlNode = AstXmlNode(AstNodeKind.Param, AstNodeCategory.None, pattrs, Array<AstXmlNode>())
        if (paramType.name != AstNodeKind.None) {
            xmlAddChild(pnode, paramType)
        }
        params.append(pnode)
        this.skipNewlines()
        if (!this.matchText(",")) {
            break
        }
        this.skipNewlines()
    }
    if (!this.expectText(")")) {
        return this.emptyNode()
    }

    var returnType: AstXmlNode = this.emptyNode()
    if (this.matchText(":")) {
        returnType = this.parseType(AstNodeKind.ReturnType)
        if (this.failed) {
            return this.emptyNode()
        }
    }

    // `when T: Printable, Countable` (specs/declarations.md, "Protocols"): the constraints a
    // generic function's type parameters must satisfy at every instantiation. Parsed here,
    // where the signature ends and the body has not begun.
    val constraintsText: Str = this.parseProtocolConstraints()
    if (this.failed) {
        return this.emptyNode()
    }

    var body: List<AstXmlNode> = List<AstXmlNode>()
    var hasBody: Bool = false
    if (this.checkText("{")) {
        // The body's returns construct this function's return type (the `return (x)`
        // convention), so the type travels with the parse.
        val savedReturnType: AstXmlNode = this.returnTypeCtx
        this.returnTypeCtx = returnType
        body = this.parseBlock()
        this.returnTypeCtx = savedReturnType
        if (this.failed) {
            return this.emptyNode()
        }
        hasBody = true
    }

    // The attribute's C++ is the generator's, so there is no body to emit and the
    // generator names the symbol; `@SmGen("cpp", sym)` and `native(sym)` fill the same
    // attributes. The arguments are kept as written (a string keeps its quotes).
    var attributeName: Str = attrName
    var generatorName: Str = ""
    if (attrName.size() > 0) {
        if (attrArgs.size() > 0) {
            generatorName = attrLiteralText(attrArgs[0])
        }
    }
    val generatorArgs: Str = generatorArgsText(attrArgs)
    var isNative: Bool = false
    if (attributeName.size() > 0) {
        isNative = true
        if (hasBody) {
            this.setError(pos, "a method whose C++ is generated must not have a body")
            return this.emptyNode()
        }
        // The symbol a call reaches: `cpp`'s is the argument after the generator name,
        // `res`'s is the third (it names a section first), `kt`'s is none. Carried as
        // `NativeSymbol` because a pass without the emitter's tables reads it there
        // (`ilIsListOf`).
        var symbolArg: Int = -1
        if (generatorName == "cpp") {
            symbolArg = 1
        }
        if (generatorName == "res") {
            symbolArg = 2
        }
        if (symbolArg >= 0 && attrArgs.size() > symbolArg) {
            nativeSymbol = attrArgs[symbolArg]
            hasNativeSymbol = true
        }
    } else if (!hasBody) {
        this.setError(pos, "a body-less method needs an attribute")
        return this.emptyNode()
    }

    var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, declName))
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.IsNative, boolText(isNative)))
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.HasBody, boolText(hasBody)))
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.HasReceiver, boolText(hasReceiver)))
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.HasNativeSymbol, boolText(hasNativeSymbol)))
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.IsPure, boolText(pure)))
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.IsSuspend, boolText(suspendModifier)))
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.IsBorrow, boolText(borrowModifier)))
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.IsOperator, boolText(operatorModifier)))
    if (constraintsText != "") {
        attrs.append(AstNodeAttribute(AstNodeAttributeKind.Protocols, constraintsText))
    }
    if (hasNativeSymbol) {
        attrs.append(AstNodeAttribute(AstNodeAttributeKind.NativeSymbol, nativeSymbol))
    }
    if (attributeName.size() > 0) {
        attrs.append(AstNodeAttribute(AstNodeAttributeKind.Attribute, attributeName))
        attrs.append(AstNodeAttribute(AstNodeAttributeKind.Generator, generatorName))
        attrs.append(AstNodeAttribute(AstNodeAttributeKind.GeneratorArgs, generatorArgs))
    }
    var node: AstXmlNode = AstXmlNode(AstNodeKind.Function, AstNodeCategory.Function, attrs, Array<AstXmlNode>())
    if (hasReceiver) {
        xmlAddChild(node, receiverNode)
    }
    this.appendTypeParams(node, functionTypeParams)
    xmlAddChildren(node, params)
    if (returnType.name != AstNodeKind.None) {
        xmlAddChild(node, returnType)
    }
    if (hasBody) {
        xmlAddChild(node, this.container(AstNodeKind.Body, body))
    }
    return node
}

// The optional `when <typeParam>: <Protocol>[, <Protocol>][, <typeParam>: ...]` clause a
// signature may carry (specs/declarations.md, "Protocols"). A bare protocol name continues
// the subject last written, so `when T: Printable, Countable` is two requirements of `T`.
// Answers the attribute text (`"T:Printable,T:Countable"`, one `param:protocol` item per
// entry), or "" when there is no clause.
fun Parser.parseProtocolConstraints(): Str {
    if (!this.checkText("when")) {
        return ""
    }
    this.advance()
    var items: List<Str> = List<Str>()
    var subject: Str = ""
    while (!this.atStmtEnd() && !this.checkText("{") && !this.failed) {
        if (this.checkKind(TokenKind.Identifier) && this.peek(1).text == ":") {
            subject = this.advance().text
            this.advance()
        } else if (subject == "") {
            this.fail("expected 'TypeParameter: Protocol' after 'when'")
            return ""
        }
        if (!this.checkKind(TokenKind.Identifier)) {
            this.fail("expected a protocol name")
            return ""
        }
        val protocolName: Str = this.advance().text
        items.append(subject + ":" + protocolName)
        if (!this.matchText(",")) {
            break
        }
        this.skipNewlines()
    }
    if (items.size() == 0) {
        this.fail("expected 'TypeParameter: Protocol' after 'when'")
        return ""
    }
    return joinStrs(items, ",")
}

fun Parser.namedTypeNode(role: AstNodeKind, name: Str, pos: SourcePos): AstXmlNode {
    var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
    return AstXmlNode(role, AstNodeCategory.TypeNamed, attrs, Array<AstXmlNode>())
}

// A synthesized `*T`: the inner takes the `Inner` role a parsed one gets, so the emitter
// reads it the same way.
fun Parser.pointerTypeNode(role: AstNodeKind, inner: AstXmlNode, pos: SourcePos): AstXmlNode {
    var renamed: AstXmlNode = inner
    renamed.name = AstNodeKind.Inner
    var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    var node: AstXmlNode = AstXmlNode(role, AstNodeCategory.TypePointer, attrs, Array<AstXmlNode>())
    xmlAddChild(node, renamed)
    return node
}

fun Parser.parseType(role: AstNodeKind): AstXmlNode {
    val pos: SourcePos = this.peek(0).pos

    if (this.matchText("&")) {
        val inner: AstXmlNode = this.parseType(AstNodeKind.Inner)
        if (this.failed) {
            return this.emptyNode()
        }
        var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        var node: AstXmlNode = AstXmlNode(role, AstNodeCategory.TypeReference, attrs, Array<AstXmlNode>())
        xmlAddChild(node, inner)
        return node
    }
    if (this.matchText("*")) {
        val inner: AstXmlNode = this.parseType(AstNodeKind.Inner)
        if (this.failed) {
            return this.emptyNode()
        }
        var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        var node: AstXmlNode = AstXmlNode(role, AstNodeCategory.TypePointer, attrs, Array<AstXmlNode>())
        xmlAddChild(node, inner)
        return node
    }
    // `..T`: lowered to a state machine whose element type is `T` (impl_specs/yield.md).
    if (this.matchText("..")) {
        val inner: AstXmlNode = this.parseType(AstNodeKind.Inner)
        if (this.failed) {
            return this.emptyNode()
        }
        var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        var node: AstXmlNode = AstXmlNode(role, AstNodeCategory.TypeYield, attrs, Array<AstXmlNode>())
        xmlAddChild(node, inner)
        return node
    }
    if (this.checkText("(")) {
        this.advance()
        var params: List<AstXmlNode> = List<AstXmlNode>()
        this.skipNewlines()
        if (!this.checkText(")")) {
            val first: AstXmlNode = this.parseType(AstNodeKind.Type)
            if (this.failed) {
                return this.emptyNode()
            }
            params.append(first)
            this.skipNewlines()
            while (this.matchText(",")) {
                this.skipNewlines()
                val next: AstXmlNode = this.parseType(AstNodeKind.Type)
                if (this.failed) {
                    return this.emptyNode()
                }
                params.append(next)
                this.skipNewlines()
            }
        }
        if (!this.expectText(")")) {
            return this.emptyNode()
        }
        if (this.matchText("->")) {
            var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
            var node: AstXmlNode = AstXmlNode(role, AstNodeCategory.TypeFunction, attrs, Array<AstXmlNode>())
            var renamed: List<AstXmlNode> = List<AstXmlNode>()
            var i: Int = 0
            while (i < params.size()) {
                var param: AstXmlNode = params[i]
                param.name = AstNodeKind.ParamType
                renamed.append(param)
                i = i + 1
            }
            xmlAddChildren(node, renamed)
            val ret: AstXmlNode = this.parseType(AstNodeKind.ReturnType)
            if (this.failed) {
                return this.emptyNode()
            }
            xmlAddChild(node, ret)
            return node
        }
        if (params.size() == 1) {
            var only: AstXmlNode = params[0]
            only.name = role
            return only
        }
        this.fail("expected '->' in function type")
        return this.emptyNode()
    }
    if (this.checkKind(TokenKind.Identifier)) {
        val name: Str = this.advance().text
        // `RawPtr` is `void*` and `PtrOf<T>` is `*T` (specs/memory-model.md, "Memory
        // operators on types"). Both desugar to the pointer node *here*, so every stage
        // downstream - the handle kinds, the native generator's `void*`, the emitter -
        // already reads them as pointers; the language keeps no second notion of one.
        if (name == "RawPtr") {
            return this.pointerTypeNode(role, this.namedTypeNode(AstNodeKind.Inner, "Unit", pos), pos)
        }
        if (this.checkText("<")) {
            val typeArgs: List<AstXmlNode> = this.parseGenericArgs()
            if (this.failed) {
                return this.emptyNode()
            }
            if (name == "PtrOf" && typeArgs.size() == 1) {
                return this.pointerTypeNode(role, typeArgs[0], pos)
            }
            var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
            attrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
            var node: AstXmlNode = AstXmlNode(role, AstNodeCategory.TypeGeneric, attrs, Array<AstXmlNode>())
            xmlAddChildren(node, typeArgs)
            return node
        }
        var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        attrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
        return AstXmlNode(role, AstNodeCategory.TypeNamed, attrs, Array<AstXmlNode>())
    }
    this.fail("expected type")
    return this.emptyNode()
}

fun Parser.parseGenericArgs(): List<AstXmlNode> {
    var out: List<AstXmlNode> = List<AstXmlNode>()
    if (!this.expectText("<")) {
        return out
    }
    this.skipNewlines()
    while (!this.checkGenericCloser() && !this.atEnd() && !this.failed) {
        var arg: AstXmlNode = this.emptyNode()
        if (this.checkKind(TokenKind.Number)) {
            val argPos: SourcePos = this.peek(0).pos
            var attrs: List<AstNodeAttribute> = this.posAttrs(argPos.line, argPos.column)
            attrs.append(AstNodeAttribute(AstNodeAttributeKind.Text, this.advance().text))
            arg = AstXmlNode(AstNodeKind.TypeArg, AstNodeCategory.TypeIntLit, attrs, Array<AstXmlNode>())
        } else {
            arg = this.parseType(AstNodeKind.TypeArg)
            if (this.failed) {
                return out
            }
        }
        out.append(arg)
        this.skipNewlines()
        // A pending closer ends this list too: what follows the type is the caller's again.
        if (this.checkGenericCloser()) {
            break
        }
        if (!this.matchText(",")) {
            break
        }
        this.skipNewlines()
    }
    this.matchGenericCloser()
    return out
}
