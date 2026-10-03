// ParserDecl.kt
//
// Declarations and types: the module, imports, declarations, functions and type
// expressions. Extension methods on `Parser` (Parser.kt), which is what lets the parser
// be read in pieces; they use only its public state.

package parser
import compiler

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
            val decl: AstXmlNode = this.parseDecl()
            // A `union class` carries its implicit tag enum as a `UnionTag` child; hoist it
            // to the module as an ordinary `Enum` declaration, before the class, so sema
            // and the emitter see one top-level enum and the class beside it.
            val tag: AstXmlNode = xmlChild(decl, AstNodeKind.UnionTag)
            if (!xmlIsEmpty(tag)) {
                var top: AstXmlNode = tag
                top.name = AstNodeKind.Enum
                decls.append(top)
            }
            decls.append(decl)
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

        "union" -> {
            // `union class` (specs/declarations.md): a data class one field of which is live
            // at a time, selected by an implicit `Sm<Name>Types` tag enum the parser
            // synthesizes beside it.
            if (this.peek(1).text != "class") {
                this.fail("expected 'class' after 'union'")
                return this.emptyNode()
            }
            return this.parseUnionClass()
        }

        "native", "ref" -> {
            // `native class` / `ref class` (specs/memory-model.md): the layout word replaces
            // `data` - a native class is a data class whose generated struct keeps the host's
            // alignment instead of the language's 4-byte packing, and a ref class is recorded
            // and changes nothing yet. `parseDataClass` consumes the word itself, expecting
            // `class` after it.
            if (this.peek(1).text != "class") {
                this.fail("expected 'class' after '" + text + "'")
                return this.emptyNode()
            }
            val isNativeClass: Bool = text == "native"
            val node: AstXmlNode = this.parseDataClass()
            if (this.failed) {
                return this.emptyNode()
            }
            node.attributes.append(
                AstNodeAttribute(AstNodeAttributeKind.IsNativeClass, boolText(isNativeClass))
            )
            node.attributes.append(
                AstNodeAttribute(AstNodeAttributeKind.IsRefClass, boolText(!isNativeClass))
            )
            return node
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

// `union class U(var A: T, ...)` (specs/declarations.md): a discriminated union - one field
// is live at a time, named by the tag enum. Beyond parsing the class itself (the shape is a
// data class's), this synthesizes two things:
//
//   - the implicit `Sm<Name>Types` enum (`None` first, then one member per field), carried
//     as a `UnionTag` child for `parseRoot` to hoist beside the class; and
//   - the tag surface as ordinary method declarations, marked `IsUnionGenerated`:
//     `getTypeOf()`, `isOfType(typeToCheck)`, `setNone()`, and per field a `get<Field>()`
//     (an `Opt<T>`: empty when the tag says another arm), a `set<Field>(value)` that also
//     moves the tag, and an `initByValue(value)` arm constructor.
//
// The emitter writes their C++ inline with the struct (`emitUnionClass`) because the tag is
// not a Simse field its IL could name; the checker resolves them like any method.
fun Parser.parseUnionClass(): AstXmlNode {
    val pos: SourcePos = this.peek(0).pos
    var node: AstXmlNode = this.parseDataClass()
    if (this.failed) {
        return this.emptyNode()
    }
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.IsUnionClass, "true"))
    val name: Str = xmlAttr(node, AstNodeAttributeKind.Name)
    val tagName: Str = unionTagName(name)
    xmlAddChild(node, this.unionTagEnum(name, node, pos))

    val unitType: AstXmlNode = this.namedTypeNode(AstNodeKind.ReturnType, "Unit", pos)
    val boolType: AstXmlNode = this.namedTypeNode(AstNodeKind.ReturnType, "Bool", pos)
    val tagType: AstXmlNode = this.namedTypeNode(AstNodeKind.ReturnType, tagName, pos)
    var generated: List<AstXmlNode> = List<AstXmlNode>()
    generated.append(this.unionMethod("getTypeOf", List<AstXmlNode>(), tagType, pos, ""))
    var checkParams: List<AstXmlNode> = List<AstXmlNode>()
    checkParams.append(
        this.unionParam("typeToCheck", this.namedTypeNode(AstNodeKind.Type, tagName, pos), pos)
    )
    generated.append(this.unionMethod("isOfType", checkParams, boolType, pos, ""))
    generated.append(this.unionMethod("setNone", List<AstXmlNode>(), unitType, pos, ""))
    generated.append(this.unionMethod("initByValue", List<AstXmlNode>(), unitType, pos, ""))
    for (*field in xmlChildren(node, AstNodeKind.Field)) {
        val fieldName: Str = xmlAttr(field, AstNodeAttributeKind.Name)
        val fieldPos: SourcePos = SourcePos(0, xmlLine(field), xmlColumn(field))
        val suffix: Str = upperFirst(fieldName)
        val fieldType: AstXmlNode = xmlChild(field, AstNodeKind.Type)
        var params: List<AstXmlNode> = List<AstXmlNode>()
        params.append(this.unionParam("value", fieldType, fieldPos))
        generated.append(this.unionMethod("set" + suffix, params, unitType, fieldPos, fieldName))
        generated.append(
            this.unionMethod("get" + suffix, List<AstXmlNode>(), this.unionOptType(fieldType, fieldPos), fieldPos, fieldName)
        )
        var initParams: List<AstXmlNode> = List<AstXmlNode>()
        initParams.append(this.unionParam("value", fieldType, fieldPos))
        generated.append(this.unionMethod("initByValue", initParams, unitType, fieldPos, fieldName))
    }
    xmlAddChildren(node, generated)
    return node
}

// The implicit tag enum, as a `UnionTag` child of the class: `Sm<Name>Types`, members `None`
// (0) and the field names in declaration order.
fun Parser.unionTagEnum(className: *Str, decl: *AstXmlNode, pos: SourcePos): AstXmlNode {
    var members: List<AstXmlNode> = List<AstXmlNode>()
    members.append(this.unionEnumMember("None", pos))
    for (*field in xmlChildren(decl, AstNodeKind.Field)) {
        members.append(this.unionEnumMember(xmlAttr(field, AstNodeAttributeKind.Name), pos))
    }
    var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, unionTagName(className)))
    var node: AstXmlNode = AstXmlNode(AstNodeKind.UnionTag, AstNodeCategory.Enum, attrs, Array<AstXmlNode>())
    xmlAddChildren(node, members)
    return node
}

fun Parser.unionEnumMember(memberName: *Str, pos: SourcePos): AstXmlNode {
    var attrs: List<AstNodeAttribute> = listOf<AstNodeAttribute>(
        AstNodeAttribute(AstNodeAttributeKind.Name, memberName),
        AstNodeAttribute(AstNodeAttributeKind.HasValue, "false"),
        AstNodeAttribute(AstNodeAttributeKind.Value, "0"),
        AstNodeAttribute(AstNodeAttributeKind.Line, pos.line.toString()),
        AstNodeAttribute(AstNodeAttributeKind.Column, pos.column.toString())
    )
    return AstXmlNode(AstNodeKind.EnumMember, AstNodeCategory.None, attrs, Array<AstXmlNode>())
}

// One generated method declaration: no body (the emitter writes it), no receiver child (the
// checker reads `this` as the enclosing class), marked `IsUnionGenerated`. `fieldName` is the
// arm the method belongs to, carried as `Text` so the emitter writes `_type = Tag::<field>`
// without re-deriving it (empty for the tag-wide methods).
fun Parser.unionMethod(
    methodName: *Str, params: *List<AstXmlNode>, ret: AstXmlNode, pos: SourcePos, fieldName: *Str
): AstXmlNode {
    var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, methodName))
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.Text, fieldName))
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.IsNative, "false"))
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.HasBody, "false"))
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.HasReceiver, "false"))
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.IsPure, "false"))
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.IsSuspend, "false"))
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.IsBorrow, "false"))
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.IsUnionGenerated, "true"))
    var node: AstXmlNode = AstXmlNode(AstNodeKind.Function, AstNodeCategory.Function, attrs, Array<AstXmlNode>())
    xmlAddChildren(node, params)
    xmlAddChild(node, ret)
    return node
}

fun Parser.unionParam(paramName: *Str, typeNode: AstXmlNode, pos: SourcePos): AstXmlNode {
    var attrs: List<AstNodeAttribute> = listOf<AstNodeAttribute>(
        AstNodeAttribute(AstNodeAttributeKind.Name, paramName),
        AstNodeAttribute(AstNodeAttributeKind.Line, pos.line.toString()),
        AstNodeAttribute(AstNodeAttributeKind.Column, pos.column.toString())
    )
    var node: AstXmlNode = AstXmlNode(AstNodeKind.Param, AstNodeCategory.None, attrs, Array<AstXmlNode>())
    xmlAddChild(node, typeNode)
    return node
}

// `Opt<T>` as a return type: the field's type node re-roled to a `TypeArg`.
fun Parser.unionOptType(inner: AstXmlNode, pos: SourcePos): AstXmlNode {
    var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, "Opt"))
    var node: AstXmlNode = AstXmlNode(
        AstNodeKind.ReturnType, AstNodeCategory.TypeGeneric, attrs, Array<AstXmlNode>()
    )
    var arg: AstXmlNode = inner
    arg.name = AstNodeKind.TypeArg
    xmlAddChild(node, arg)
    return node
}

// `IntValue` -> `IntValue` (unchanged), `x` -> `X`: the suffix a field contributes to
// `get<Field>`/`set<Field>` (the shared `common.upperFirst`).

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
