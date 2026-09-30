// ParserDecl.kt
//
// Declarations and types: the module, imports, declarations, functions and type
// expressions. Extension methods on `Parser` (Parser.kt), which is what lets the parser
// be read in pieces; they use only its public state.

package parser

import lex
import common


fun Parser.parseRoot(): AstXmlNode {
    this.skipSeparators()
    // The mandatory file-level `package a.b.c`; a second one is rejected by parseDecl.
    var packageName: Str = ""
    if (!this.checkText("package")) {
        this.fail("expected 'package' declaration")
        return this.emptyNode()
    }
    this.advance()
    packageName = this.expectName()
    if (!this.failed) {
        while (this.matchText(".")) {
            val next: Str = this.expectName()
            if (this.failed) {
                break
            }
            packageName = packageName + "." + next
        }
    }
    this.skipSeparators()

    var attrs: List<AstNodeAttribute> = listOf<AstNodeAttribute>(
        AstNodeAttribute(AstNodeAttributeKind.Line, "1"),
        AstNodeAttribute(AstNodeAttributeKind.Column, "1"),
        AstNodeAttribute(AstNodeAttributeKind.Package, packageName)
    )
    var root: AstXmlNode = AstXmlNode(AstNodeKind.Module, AstNodeCategory.Module, attrs, Array<AstXmlNode>())

    var imports: List<AstXmlNode> = List<AstXmlNode>()
    var decls: List<AstXmlNode> = List<AstXmlNode>()

    while (!this.atEnd() && !this.failed) {
        if (this.checkText("import")) {
            imports.append(this.parseImport())
        } else {
            decls.append(this.parseDecl())
        }
        this.skipSeparators()
    }

    xmlAddChildren(root, imports)
    xmlAddChildren(root, decls)
    return root
}

fun Parser.parseImport(): AstXmlNode {
    val pos: SourcePos = this.peek(0).pos
    this.advance()
    var path: Str = this.expectName()
    if (this.failed) {
        return this.emptyNode()
    }
    while (this.matchText(".")) {
        val next: Str = this.expectName()
        if (this.failed) {
            return this.emptyNode()
        }
        path = path + "." + next
    }
    var attrs: List<AstNodeAttribute> = listOf<AstNodeAttribute>(
        AstNodeAttribute(AstNodeAttributeKind.Path, path),
        AstNodeAttribute(AstNodeAttributeKind.Line, pos.line.toString()),
        AstNodeAttribute(AstNodeAttributeKind.Column, pos.column.toString())
    )
    return AstXmlNode(AstNodeKind.Import, AstNodeCategory.None, attrs, Array<AstXmlNode>())
}

fun Parser.parseDecl(): AstXmlNode {
    if (this.checkKind(TokenKind.Attribute)) {
        return this.parseAttributedDecl(false, false, false)
    }
    val text: Str = this.peek(0).text
    when (text) {
        "var", "val" -> {
            return this.parseStaticVar()
        }

        "data" -> {
            // `data class` is a data class; `data fun` marks a *pure* function - no side
            // effects, the result a function of its receiver and arguments - which lets
            // the reuse pass merge a repeated call of an unchanged argument
            // (`linear/ReusePure.kt`).
            if (this.peek(1).text == "class") {
                return this.parseDataClass()
            }
            if (this.peek(1).text == "fun") {
                this.advance()
                return this.parseFunction("", List<Str>(), true, false, false)
            }
            if (this.peek(1).kind == TokenKind.Attribute) {
                // `data @SmGen(...) fun ...`: the mark may precede the attribute.
                this.advance()
                return this.parseAttributedDecl(true, false, false)
            }
            this.fail("expected 'class' or 'fun' after 'data'")
            return this.emptyNode()
        }

        "suspend" -> {
            // `suspend fun`: the declaration's body may wait, so the lowering turns it into
            // a ref-counted task and a call to it is a suspension (impl_specs/async.md).
            // The modifier is the whole marker - the signature keeps the plain return type
            // and there is no `Async<T>`.
            if (this.peek(1).text == "fun") {
                this.advance()
                return this.parseFunction("", List<Str>(), false, true, false)
            }
            if (this.peek(1).kind == TokenKind.Attribute) {
                this.advance()
                return this.parseAttributedDecl(false, true, false)
            }
            this.fail("expected 'fun' or an attribute after 'suspend'")
            return this.emptyNode()
        }

        "borrow" -> {
            // `borrow fun`: the body only *reads* its receiver and parameters and never
            // writes through them, so a caller may hand it a pointer
            // (impl_specs/escape-analysis.md). The flag is the *borrowness* proof - weaker
            // than `data`, which also promises the result is a function of the arguments.
            if (this.peek(1).text == "fun") {
                this.advance()
                return this.parseFunction("", List<Str>(), false, false, true)
            }
            if (this.peek(1).kind == TokenKind.Attribute) {
                this.advance()
                return this.parseAttributedDecl(false, false, true)
            }
            this.fail("expected 'fun' or an attribute after 'borrow'")
            return this.emptyNode()
        }

        "enum" -> {
            return this.parseEnum()
        }

        "typealias" -> {
            return this.parseTypeAlias()
        }

        "fun" -> {
            return this.parseFunction("", List<Str>(), false, false, false)
        }
    }
    this.fail("expected declaration")
    return this.emptyNode()
}

// `@SmGen("cpp", "sym") fun f(...)` (specs/attributes.md). Only method declarations take
// attributes, so `fun` must follow.
fun Parser.parseAttributedDecl(pure: Bool, suspendModifier: Bool, borrowModifier: Bool): AstXmlNode {
    val attrToken: Token = this.advance()
    val attrText: Str = attrToken.text
    var attrName: Str = attrText
    if (attrText.size() > 0 && attrText[0] == '@') {
        attrName = attrText.substr(1, attrText.size() - 1)
    }
    var args: List<Str> = List<Str>()
    if (this.matchText("(")) {
        this.skipNewlines()
        while (!this.checkText(")") && !this.atEnd() && !this.failed) {
            if (!this.checkKind(TokenKind.String) && !this.checkKind(TokenKind.Number)) {
                this.fail("attribute arguments are string or integer literals")
                return this.emptyNode()
            }
            val arg: Token = this.advance()
            val argText: Str = arg.text
            args.append(this.stringTokenText(argText))
            this.skipNewlines()
            if (!this.matchText(",")) {
                break
            }
            this.skipNewlines()
        }
        if (!this.expectText(")")) {
            return this.emptyNode()
        }
    }
    // An attribute rides on its own line, so the separator before the declaration is skipped.
    this.skipSeparators()
    // `@SmGen(...) data fun f(...)` marks a pure function and `@SmGen(...) suspend fun f(...)`
    // a suspending one (`data`/`suspend` are modifiers, the attribute selects the C++).
    // A *type* may carry the attribute too (specs/attributes.md): the materialization
    // marker. An attribute on a data class or enum class names the type C++ that is
    // hand-written (a header, or a resource section) so the emitter must not generate
    // the struct; an unmarked prelude type is generated from its declaration.
    if (this.checkText("data") && this.peek(1).text == "class") {
        val node: AstXmlNode = this.parseDataClass()
        if (this.failed) {
            return this.emptyNode()
        }
        this.attachTypeAttribute(node, attrName, args)
        return node
    }
    if (this.checkText("enum") && this.peek(1).text == "class") {
        val node: AstXmlNode = this.parseEnum()
        if (this.failed) {
            return this.emptyNode()
        }
        this.attachTypeAttribute(node, attrName, args)
        return node
    }
    var isPure: Bool = pure
    var isSuspend: Bool = suspendModifier
    var isBorrow: Bool = borrowModifier
    while (this.checkText("data") || this.checkText("suspend") || this.checkText("borrow")) {
        if (this.matchText("data")) {
            isPure = true
        } else if (this.matchText("suspend")) {
            isSuspend = true
        } else {
            this.matchText("borrow")
            isBorrow = true
        }
        this.skipSeparators()
    }
    if (!this.checkText("fun")) {
        this.fail("expected 'fun' after an attribute")
        return this.emptyNode()
    }
    return this.parseFunction(attrName, args, isPure, isSuspend, isBorrow)
}

// Records a type attribute the way parseFunction records a method one: Attribute (its
// name), Generator (the first argument) and GeneratorArgs (the rest, joined by a comma).
// The emitter reads Generator to know the type C++ is elsewhere (Codegen.typeIsRaw).
fun Parser.attachTypeAttribute(node: *AstXmlNode, attrName: Str, args: *List<Str>): Unit {
    var generatorName: Str = ""
    if (args.size() > 0) {
        generatorName = attrLiteralText(args[0])
    }
    val generatorArgs: Str = generatorArgsText(args)
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Attribute, attrName))
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Generator, generatorName))
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.GeneratorArgs, generatorArgs))
}

// A file-level `var`/`val` (specs/statics.md): type required, initializer optional, and
// the shape matches a `Stmt.VarDecl` so the emitters have one variable form.
fun Parser.parseStaticVar(): AstXmlNode {
    val pos: SourcePos = this.peek(0).pos
    val isVar: Bool = this.matchText("var")
    if (!isVar) {
        if (!this.expectText("val")) {
            return this.emptyNode()
        }
    }
    val name: Str = this.expectName()
    if (this.failed) {
        return this.emptyNode()
    }
    if (!this.expectText(":")) {
        return this.emptyNode()
    }
    val typeNode: AstXmlNode = this.parseType(AstNodeKind.Type)
    if (this.failed) {
        return this.emptyNode()
    }
    var init: ExprNode = this.emptyExpr()
    if (this.matchText("=")) {
        this.skipNewlines()
        init = this.parseExpr(0)
        if (this.failed) {
            return this.emptyNode()
        }
    }
    var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.IsVar, boolText(isVar)))
    var node: AstXmlNode = AstXmlNode(AstNodeKind.Var, AstNodeCategory.Var, attrs, Array<AstXmlNode>())
    xmlAddChild(node, typeNode)
    if (init.node.name != AstNodeKind.None) {
        this.attach(node, AstNodeKind.Init, init.node)
    }
    return node
}

fun Parser.parseDataClass(): AstXmlNode {
    val pos: SourcePos = this.peek(0).pos
    this.advance()
    if (!this.expectText("class")) {
        return this.emptyNode()
    }
    val name: Str = this.expectName()
    if (this.failed) {
        return this.emptyNode()
    }
    var typeParams: List<Str> = List<Str>()
    if (this.checkText("<")) {
        typeParams = this.parseTypeParams()
        if (this.failed) {
            return this.emptyNode()
        }
    }

    var fields: List<AstXmlNode> = List<AstXmlNode>()
    if (this.matchText("(")) {
        this.skipFieldSeparators()
        while (!this.checkText(")") && !this.atEnd() && !this.failed) {
            val fieldPos: SourcePos = this.peek(0).pos
            var isVar: Bool = false
            if (this.matchText("var")) {
                isVar = true
            } else if (this.matchText("val")) {
                isVar = false
            } else {
                this.fail("expected 'val' or 'var'")
                return this.emptyNode()
            }
            val fieldName: Str = this.expectName()
            if (this.failed) {
                return this.emptyNode()
            }
            var fieldType: AstXmlNode = this.emptyNode()
            if (this.matchText(":")) {
                fieldType = this.parseType(AstNodeKind.Type)
                if (this.failed) {
                    return this.emptyNode()
                }
            }
            var fattrs: List<AstNodeAttribute> = listOf<AstNodeAttribute>(
                AstNodeAttribute(AstNodeAttributeKind.Name, fieldName),
                AstNodeAttribute(AstNodeAttributeKind.IsVar, boolText(isVar)),
                AstNodeAttribute(AstNodeAttributeKind.Line, fieldPos.line.toString()),
                AstNodeAttribute(AstNodeAttributeKind.Column, fieldPos.column.toString())
            )
            var fnode: AstXmlNode = AstXmlNode(AstNodeKind.Field, AstNodeCategory.None, fattrs, Array<AstXmlNode>())
            if (fieldType.name != AstNodeKind.None) {
                xmlAddChild(fnode, fieldType)
            }
            fields.append(fnode)
            this.skipFieldSeparators()
        }
        if (!this.expectText(")")) {
            return this.emptyNode()
        }
    }

    var methods: List<AstXmlNode> = List<AstXmlNode>()
    // The body brace may stand on its own line: a newline there is not a declaration boundary.
    this.skipNewlines()
    if (this.checkText("{")) {
        this.advance()
        this.skipSeparators()
        while (!this.checkText("}") && !this.atEnd() && !this.failed) {
            if (!this.checkText("fun")) {
                this.fail("expected method declaration")
                return this.emptyNode()
            }
            methods.append(this.parseFunction("", List<Str>(), false, false, false))
            this.skipSeparators()
        }
        if (!this.expectText("}")) {
            return this.emptyNode()
        }
    }

    var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
    var node: AstXmlNode = AstXmlNode(AstNodeKind.DataClass, AstNodeCategory.DataClass, attrs, Array<AstXmlNode>())
    this.appendTypeParams(node, typeParams)
    xmlAddChildren(node, fields)
    xmlAddChildren(node, methods)
    return node
}

fun Parser.parseEnum(): AstXmlNode {
    val pos: SourcePos = this.peek(0).pos
    this.advance()
    // `enum class`: there is no bare `enum`.
    if (!this.expectText("class")) {
        return this.emptyNode()
    }
    val name: Str = this.expectName()
    if (this.failed) {
        return this.emptyNode()
    }
    var typeParams: List<Str> = List<Str>()
    if (this.checkText("<")) {
        typeParams = this.parseTypeParams()
        if (this.failed) {
            return this.emptyNode()
        }
    }
    if (!this.expectText("{")) {
        return this.emptyNode()
    }
    this.skipSeparators()

    var members: List<AstXmlNode> = List<AstXmlNode>()
    while (!this.checkText("}") && !this.atEnd() && !this.failed) {
        val memberPos: SourcePos = this.peek(0).pos
        val memberName: Str = this.expectName()
        if (this.failed) {
            return this.emptyNode()
        }
        var hasValue: Bool = false
        var memberValue: Int = 0
        if (this.matchText("=")) {
            if (!this.checkKind(TokenKind.Number)) {
                this.fail("expected enum value")
                return this.emptyNode()
            }
            val valueText: Str = this.advance().text
            if (valueText.find(".") != -1) {
                this.fail("enum value must be an integer")
                return this.emptyNode()
            }
            val parsed: Opt<Int> = valueText.toInt()
            if (parsed.hasValue()) {
                memberValue = parsed.value()
            }
            hasValue = true
        }
        var mattrs: List<AstNodeAttribute> = listOf<AstNodeAttribute>(
            AstNodeAttribute(AstNodeAttributeKind.Name, memberName),
            AstNodeAttribute(AstNodeAttributeKind.HasValue, boolText(hasValue)),
            AstNodeAttribute(AstNodeAttributeKind.Value, memberValue.toString()),
            AstNodeAttribute(AstNodeAttributeKind.Line, memberPos.line.toString()),
            AstNodeAttribute(AstNodeAttributeKind.Column, memberPos.column.toString())
        )
        members.append(AstXmlNode(AstNodeKind.EnumMember, AstNodeCategory.None, mattrs, Array<AstXmlNode>()))
        this.skipSeparators()
        this.matchText(",")
        this.skipSeparators()
    }
    if (!this.expectText("}")) {
        return this.emptyNode()
    }

    var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
    var node: AstXmlNode = AstXmlNode(AstNodeKind.Enum, AstNodeCategory.Enum, attrs, Array<AstXmlNode>())
    this.appendTypeParams(node, typeParams)
    xmlAddChildren(node, members)
    return node
}

fun Parser.parseTypeAlias(): AstXmlNode {
    val pos: SourcePos = this.peek(0).pos
    this.advance()
    val name: Str = this.expectName()
    if (this.failed) {
        return this.emptyNode()
    }
    var typeParams: List<Str> = List<Str>()
    if (this.checkText("<")) {
        typeParams = this.parseTypeParams()
        if (this.failed) {
            return this.emptyNode()
        }
    }
    if (!this.expectText("=")) {
        return this.emptyNode()
    }
    val target: AstXmlNode = this.parseType(AstNodeKind.TargetType)
    if (this.failed) {
        return this.emptyNode()
    }

    var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
    var node: AstXmlNode = AstXmlNode(AstNodeKind.TypeAlias, AstNodeCategory.TypeAlias, attrs, Array<AstXmlNode>())
    this.appendTypeParams(node, typeParams)
    xmlAddChild(node, target)
    return node
}

fun Parser.parseTypeParams(): List<Str> {
    var out: List<Str> = List<Str>()
    if (!this.expectText("<")) {
        return out
    }
    this.skipNewlines()
    while (!this.checkGenericCloser() && !this.atEnd()) {
        val name: Str = this.expectName()
        if (this.failed) {
            return out
        }
        out.append(name)
        this.skipNewlines()
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

