// Parser.kt
//
// The grammar parser: it consumes tokens from the scanner and builds an AstXmlNode tree
// (impl_specs/ast-xmlnode.md). Errors are recorded in `failed`/`error`; the entry points
// return Res.err with a positioned message.
//
// `for` and `when` are desugared here, so no stage downstream sees either.

package parser

import lex
import common

// The position travels with the expression: a parent node takes its own from the operand
// it extends (callee, receiver or left).
data class ExprNode(
    var node: AstXmlNode,

    var line: Int,
    var column: Int
)

data class Parser(
    var cursor: Span<Token>,

    var failed: Bool,
    var error: Str,
    var file: Str,
    var nextTemplateId: Int,
    // The `>`s a `>>` closer left over: see `matchGenericCloser`.
    var pendingClosers: Int,
    // The enclosing function's declared return type (empty outside one): what a
    // parenthesized `return (x)` constructs, via the `initByValue` convention.
    var returnTypeCtx: AstXmlNode,
    // Unique suffix for the temporary a `return (...)` construction declares.
    var ctorCounter: Int
) {

    fun peek(offset: Int): Token {
        if (offset <= 0) {
            return this.cursor[0]
        }
        val rest: Span<Token> = this.cursor.slice(offset)
        if (!rest.isEmpty()) {
            return rest[0]
        }
        return this.cursor[this.cursor.size() - 1]
    }

    fun advance(): Token {
        val token: Token = this.cursor[0]
        if (this.cursor.size() > 1) {
            this.cursor = this.cursor.slice(1)
        }
        return token
    }

    fun atEnd(): Bool {
        return this.peek(0).kind == TokenKind.Eof
    }

    fun checkText(text: *Str): Bool {
        return this.peek(0).text == text
    }

    fun checkKind(kind: TokenKind): Bool {
        return this.peek(0).kind == kind
    }

    fun matchText(text: *Str): Bool {
        if (this.checkText(text)) {
            this.advance()
            return true
        }
        return false
    }

    fun setError(pos: SourcePos, message: *Str): Unit {
        if (this.failed) {
            return
        }
        this.failed = true
        this.error = fmtStr(
            "|:|:|: |",
            this.file, pos.line.toString(), pos.column.toString(), message
        )
    }

    fun fail(message: *Str): Bool {
        this.setError(this.peek(0).pos, message)
        return false
    }

    fun expectText(text: *Str): Bool {
        if (this.matchText(text)) {
            return true
        }
        return this.fail(fmtStr("expected '|'", text))
    }

    fun expectName(): Str {
        if (this.checkKind(TokenKind.Identifier)) {
            return this.advance().text
        }
        this.fail("expected name")
        return ""
    }

    // `>>` scans as one shift token, so a closer takes one `>` out of it and leaves the rest
    // pending for the closer of the list it is nested in (`List<List<Int>>`). `>>=` is an error.
    fun checkGenericCloser(): Bool {
        if (this.pendingClosers > 0) {
            return true
        }
        val text: Str = this.peek(0).text
        return text == ">" || text == ">>"
    }

    fun matchGenericCloser(): Bool {
        if (this.pendingClosers > 0) {
            this.pendingClosers = this.pendingClosers - 1
            return true
        }
        if (this.matchText(">")) {
            return true
        }
        if (this.matchText(">>")) {
            this.pendingClosers = 1
            return true
        }
        if (this.checkText(">>=")) {
            return this.fail("a `>` closer ran into `=`: write a space before it")
        }
        return this.fail("expected '>'")
    }

    fun skipSeparators(): Unit {
        while (this.checkKind(TokenKind.EndOfLine) || this.checkText(";")) {
            this.advance()
        }
    }

    // A data class's fields separate on `,` (or `;`); only the field list takes a comma,
    // since between statements and methods one would read as an expression part.
    fun skipFieldSeparators(): Unit {
        while (this.checkKind(TokenKind.EndOfLine) || this.checkText(";") || this.checkText(",")) {
            this.advance()
        }
    }

    fun skipNewlines(): Unit {
        while (this.checkKind(TokenKind.EndOfLine)) {
            this.advance()
        }
    }

    fun atStmtEnd(): Bool {
        return this.checkKind(TokenKind.EndOfLine) || this.atEnd()
                || this.checkText(";") || this.checkText("}")
    }

    fun emptyNode(): AstXmlNode {
        return AstXmlNode(AstNodeKind.None, AstNodeCategory.None, List<AstNodeAttribute>(), Array<AstXmlNode>())
    }

    fun emptyExpr(): ExprNode {
        return ExprNode(this.emptyNode(), 0, 0)
    }

    // A comparison a string literal is tested against: `Str == "lit"` compares the *view* of
    // the string against the literal, never a copy of it (`spanOfStr`, cppsrc/rtl/StrView.kt) -
    // the view operators read the bytes in place, and a `*Str` operand is not read through at
    // all. The `when`-over-strings desugar does the same for its tests (`parseWhen`).
    fun isStringCompareOp(op: *Str): Bool {
        return op == "==" || op == "!=" || op == "<" || op == "<=" || op == ">" || op == ">="
    }

    fun posAttrs(line: Int, column: Int): List<AstNodeAttribute> {
        var attrs: List<AstNodeAttribute> = listOf<AstNodeAttribute>(
            AstNodeAttribute(AstNodeAttributeKind.Line, line.toString()),
            AstNodeAttribute(AstNodeAttributeKind.Column, column.toString())
        )
        return attrs
    }

    // `parent` is a non-owning pointer: the append replaces the caller's `Children` handle.
    fun attach(parent: *AstXmlNode, role: AstNodeKind, child: *AstXmlNode): Unit {
        xmlAddChild(parent, this.roleOf(child, role))
    }

    // Sets `child`'s role without attaching it, for a caller collecting children itself:
    // appending one at a time through `xmlAddChild` is O(k*k) for k children.
    fun roleOf(child: *AstXmlNode, role: AstNodeKind): AstXmlNode {
        var renamed: AstXmlNode = child
        renamed.name = role
        return renamed
    }

    // Children are built once from the list rather than appended to per child.
    fun container(name: AstNodeKind, children: *List<AstXmlNode>): AstXmlNode {
        return AstXmlNode(name, AstNodeCategory.None, List<AstNodeAttribute>(), children.toArray())
    }

    // The names are read only; a `*List<Str>` avoids copying the caller's list.
    fun appendTypeParams(node: *AstXmlNode, names: *List<Str>): Unit {
        var params: List<AstXmlNode> = List<AstXmlNode>()
        var i: Int = 0
        while (i < names.size()) {
            var attrs: List<AstNodeAttribute> = List<AstNodeAttribute>()
            attrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, names[i]))
            params.append(AstXmlNode(AstNodeKind.TypeParam, AstNodeCategory.None, attrs, Array<AstXmlNode>()))
            i = i + 1
        }
        xmlAddChildren(node, params)
    }

    fun parseRoot(): AstXmlNode {
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

    fun parseImport(): AstXmlNode {
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

    fun parseDecl(): AstXmlNode {
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
    fun parseAttributedDecl(pure: Bool, suspendModifier: Bool, borrowModifier: Bool): AstXmlNode {
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
    fun attachTypeAttribute(node: *AstXmlNode, attrName: Str, args: *List<Str>): Unit {
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
    fun parseStaticVar(): AstXmlNode {
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

    fun parseDataClass(): AstXmlNode {
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

    fun parseEnum(): AstXmlNode {
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

    fun parseTypeAlias(): AstXmlNode {
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

    fun parseTypeParams(): List<Str> {
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

    fun looksLikeTypeStart(): Bool {
        // `..` starts a `..T` receiver: `fun ..T.iter<T>()` is the wrap that makes a
        // machine iterable like any other source (impl_specs/for.md).
        return this.checkKind(TokenKind.Identifier)
                || this.checkText("(") || this.checkText("&") || this.checkText("*")
                || this.checkText("..")
    }

    fun parseParamName(): Str {
        if (this.checkKind(TokenKind.Identifier) || this.checkText("this")) {
            return this.advance().text
        }
        this.fail("expected parameter name")
        return ""
    }

    // `attrName`/`attrArgs` are the parsed attribute (specs/attributes.md). An attributed
    // method may be body-less; a body-less method with no attribute has no implementation.
    // `pure`/`suspendModifier`/`borrowModifier` are the `data`/`suspend`/`borrow` modifiers,
    // carried as `IsPure`/`IsSuspend`/`IsBorrow`; `parseDecl` says what each promises.
    fun parseFunction(
        attrName: *Str, attrArgs: *List<Str>, pure: Bool, suspendModifier: Bool,
        borrowModifier: Bool
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

    fun namedTypeNode(role: AstNodeKind, name: Str, pos: SourcePos): AstXmlNode {
        var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        attrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
        return AstXmlNode(role, AstNodeCategory.TypeNamed, attrs, Array<AstXmlNode>())
    }

    // A synthesized `*T`: the inner takes the `Inner` role a parsed one gets, so the emitter
    // reads it the same way.
    fun pointerTypeNode(role: AstNodeKind, inner: AstXmlNode, pos: SourcePos): AstXmlNode {
        var renamed: AstXmlNode = inner
        renamed.name = AstNodeKind.Inner
        var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        var node: AstXmlNode = AstXmlNode(role, AstNodeCategory.TypePointer, attrs, Array<AstXmlNode>())
        xmlAddChild(node, renamed)
        return node
    }

    fun parseType(role: AstNodeKind): AstXmlNode {
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

    fun parseGenericArgs(): List<AstXmlNode> {
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

    fun parseBlock(): List<AstXmlNode> {
        var body: List<AstXmlNode> = List<AstXmlNode>()
        // A newline before a `{` where a block is the only thing that can follow is not a
        // statement boundary (Kotlin's rule, and why a Kotlin formatter can be pointed here).
        this.skipNewlines()
        if (!this.expectText("{")) {
            return body
        }
        this.skipSeparators()
        while (!this.checkText("}") && !this.atEnd() && !this.failed) {
            if (!this.parseStmtInto(body)) {
                return body
            }
            this.skipSeparators()
        }
        this.expectText("}")
        return body
    }

    // One source statement appended to `out`, but a statement may expand to several: `for`
    // is a declaration plus its loop, and the declaration must sit outside the loop.
    fun parseStmtInto(out: *List<AstXmlNode>): Bool {
        val text: Str = this.peek(0).text
        when (text) {
            "for" -> {
                return this.parseFor(out)
            }

            "when" -> {
                return this.parseWhen(out)
            }
        }
        val stmt: AstXmlNode = this.parseStmt()
        if (this.failed) {
            return false
        }
        out.append(stmt)
        return true
    }

    fun parseStmt(): AstXmlNode {
        val text: Str = this.peek(0).text
        when (text) {
            "val", "var" -> {
                return this.parseVarDecl()
            }

            "if" -> {
                return this.parseIf()
            }

            "while" -> {
                return this.parseWhile()
            }

            "return" -> {
                return this.parseReturn()
            }

            "yield" -> {
                return this.parseYield()
            }

            "break" -> {
                val pos: SourcePos = this.peek(0).pos
                this.advance()
                return AstXmlNode(
                    AstNodeKind.Stmt,
                    AstNodeCategory.StmtBreak,
                    this.posAttrs(pos.line, pos.column),
                    Array<AstXmlNode>()
                )
            }

            "continue" -> {
                val pos: SourcePos = this.peek(0).pos
                this.advance()
                return AstXmlNode(
                    AstNodeKind.Stmt,
                    AstNodeCategory.StmtContinue,
                    this.posAttrs(pos.line, pos.column),
                    Array<AstXmlNode>()
                )
            }

            "++", "--" -> {
                // A step's value is the assignment's, so a prefix one has nothing to hand back.
                this.fail(fmtStr("`|` stands on its own as a statement (`i|`)", text, text))
                return this.emptyNode()
            }
        }

        val pos: SourcePos = this.peek(0).pos
        val expr: ExprNode = this.parseExpr(0)
        if (this.failed) {
            return this.emptyNode()
        }
        if (isStepOp(this.peek(0).text)) {
            // `i++`/`i--` are the compound assignment with a `1` (specs/memory-model.md);
            // anywhere else a step is the diagnostic above.
            val step: Str = this.advance().text
            var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
            attrs.append(AstNodeAttribute(AstNodeAttributeKind.Op, stepAssignOp(step)))
            var node: AstXmlNode = AstXmlNode(AstNodeKind.Stmt, AstNodeCategory.StmtAssign, attrs, Array<AstXmlNode>())
            this.attach(node, AstNodeKind.Target, expr.node)
            this.attach(node, AstNodeKind.Value, this.intLiteralAt(1, pos).node)
            return node
        }
        if (isAssignOp(this.peek(0).text)) {
            val op: Str = this.advance().text
            this.skipNewlines()
            val value: ExprNode = this.parseExpr(0)
            if (this.failed) {
                return this.emptyNode()
            }
            var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
            attrs.append(AstNodeAttribute(AstNodeAttributeKind.Op, op))
            var node: AstXmlNode = AstXmlNode(AstNodeKind.Stmt, AstNodeCategory.StmtAssign, attrs, Array<AstXmlNode>())
            this.attach(node, AstNodeKind.Target, expr.node)
            this.attach(node, AstNodeKind.Value, value.node)
            return node
        }
        var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        var node: AstXmlNode = AstXmlNode(AstNodeKind.Stmt, AstNodeCategory.StmtExprStmt, attrs, Array<AstXmlNode>())
        this.attach(node, AstNodeKind.Expr, expr.node)
        return node
    }

    fun parseVarDecl(): AstXmlNode {
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
        var typeNode: AstXmlNode = this.emptyNode()
        if (this.matchText(":")) {
            typeNode = this.parseType(AstNodeKind.Type)
            if (this.failed) {
                return this.emptyNode()
            }
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
        var node: AstXmlNode = AstXmlNode(AstNodeKind.Stmt, AstNodeCategory.StmtVarDecl, attrs, Array<AstXmlNode>())
        if (typeNode.name != AstNodeKind.None) {
            xmlAddChild(node, typeNode)
        }
        if (init.node.name != AstNodeKind.None) {
            this.attach(node, AstNodeKind.Init, init.node)
        }
        return node
    }

    fun parseIf(): AstXmlNode {
        val pos: SourcePos = this.peek(0).pos
        this.advance()
        if (!this.expectText("(")) {
            return this.emptyNode()
        }
        val cond: ExprNode = this.parseExpr(0)
        if (this.failed) {
            return this.emptyNode()
        }
        if (!this.expectText(")")) {
            return this.emptyNode()
        }
        val thenBody: List<AstXmlNode> = this.parseBlock()
        if (this.failed) {
            return this.emptyNode()
        }
        var hasElse: Bool = false
        var elseBody: List<AstXmlNode> = List<AstXmlNode>()
        if (this.matchText("else")) {
            hasElse = true
            if (this.checkText("if")) {
                val nested: AstXmlNode = this.parseIf()
                if (this.failed) {
                    return this.emptyNode()
                }
                elseBody.append(nested)
            } else {
                elseBody = this.parseBlock()
                if (this.failed) {
                    return this.emptyNode()
                }
            }
        }
        var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        var node: AstXmlNode = AstXmlNode(AstNodeKind.Stmt, AstNodeCategory.StmtIf, attrs, Array<AstXmlNode>())
        this.attach(node, AstNodeKind.Cond, cond.node)
        xmlAddChild(node, this.container(AstNodeKind.Then, thenBody))
        if (hasElse) {
            xmlAddChild(node, this.container(AstNodeKind.Else, elseBody))
        }
        return node
    }

    fun parseWhile(): AstXmlNode {
        val pos: SourcePos = this.peek(0).pos
        this.advance()
        if (!this.expectText("(")) {
            return this.emptyNode()
        }
        val cond: ExprNode = this.parseExpr(0)
        if (this.failed) {
            return this.emptyNode()
        }
        if (!this.expectText(")")) {
            return this.emptyNode()
        }
        val body: List<AstXmlNode> = this.parseBlock()
        if (this.failed) {
            return this.emptyNode()
        }
        var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        var node: AstXmlNode = AstXmlNode(AstNodeKind.Stmt, AstNodeCategory.StmtWhile, attrs, Array<AstXmlNode>())
        this.attach(node, AstNodeKind.Cond, cond.node)
        xmlAddChild(node, this.container(AstNodeKind.Body, body))
        return node
    }

    // Whether a `when` subject may be read *again* per test instead of being copied into a
    // template: a place - a name, or a member/index/deref chain of places - has no call and no
    // side effect, and nothing in the test chain writes it (every test runs before any arm body),
    // so the chain can be evaluated against the subject itself. That is what saves the copy the
    // template would make of a `Str` subject - a heap copy, for a text longer than the inline
    // buffer - which is a cost on *every* `when` execution and dwarfs the tests themselves.
    fun whenSubjectIsPlace(node: *AstXmlNode): Bool {
        val kind: AstNodeCategory = xmlKind(node)
        if (kind == AstNodeCategory.ExprName) {
            return true
        }
        if (kind == AstNodeCategory.ExprDeref) {
            val operand: *AstXmlNode = xmlChildPtr(node, AstNodeKind.Operand)
            return !xmlIsEmpty(operand) && this.whenSubjectIsPlace(operand)
        }
        if (kind == AstNodeCategory.ExprMember) {
            val receiver: *AstXmlNode = xmlChildPtr(node, AstNodeKind.Receiver)
            return !xmlIsEmpty(receiver) && this.whenSubjectIsPlace(receiver)
        }
        if (kind == AstNodeCategory.ExprIndex) {
            val receiver: *AstXmlNode = xmlChildPtr(node, AstNodeKind.Receiver)
            val index: *AstXmlNode = xmlChildPtr(node, AstNodeKind.Index)
            return !xmlIsEmpty(receiver) && this.whenSubjectIsPlace(receiver)
                    && !xmlIsEmpty(index) && this.whenSubjectIsPlace(index)
        }
        return false
    }

    // `<receiver>.<method>()`, for a receiver that is an expression rather than a name.
    fun receiverCallAt(receiver: *ExprNode, method: *Str, pos: SourcePos): ExprNode {
        var memberAttrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        memberAttrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, method))
        var member: AstXmlNode =
            AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprMember, memberAttrs, Array<AstXmlNode>())
        this.attach(member, AstNodeKind.Receiver, receiver.node)
        var callAttrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        var call: AstXmlNode = AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprCall, callAttrs, Array<AstXmlNode>())
        this.attach(call, AstNodeKind.Callee, member)
        return ExprNode(call, pos.line, pos.column)
    }

    // `<callee>(<arg>)`, for a free call the desugaring builds rather than parses.
    fun freeCallAt(callee: *Str, arg: *ExprNode, pos: SourcePos): ExprNode {
        val calleeExpr: ExprNode = this.nameExprAt(callee, pos)
        var callAttrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        var call: AstXmlNode = AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprCall, callAttrs, Array<AstXmlNode>())
        this.attach(call, AstNodeKind.Callee, calleeExpr.node)
        this.attach(call, AstNodeKind.Arg, arg.node)
        return ExprNode(call, pos.line, pos.column)
    }

    // `<receiver>[<index>]`, for a receiver that is an expression rather than a name.
    fun receiverIndexAt(receiver: *ExprNode, index: *ExprNode, pos: SourcePos): ExprNode {
        var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        var node: AstXmlNode = AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprIndex, attrs, Array<AstXmlNode>())
        this.attach(node, AstNodeKind.Receiver, receiver.node)
        this.attach(node, AstNodeKind.Index, index.node)
        return ExprNode(node, pos.line, pos.column)
    }

    // One condition for an arm's labels, so their body is emitted once. With `dispatch` (the
    // `when`-lowering optimization) each label's `==` is *guarded*: by the subject's length
    // first, and - for a one-byte literal, or for a longer one when `--when-first-char` asks -
    // by its first byte. Both guards are necessary conditions, so the predicate is unchanged
    // and the whole string compare runs only for a label that can still match, which is what
    // turns a `when` over N string labels from N `memcmp` calls into a few integer compares.
    fun whenCondition(
        subject: *
        ExprNode,
        lengthName: *
        Str,
        labels: *
        List<AstXmlNode>,
        pos: SourcePos,
        dispatch: Bool
    ): ExprNode {
        var cond: ExprNode = this.whenLabelCondition(subject, lengthName, labels[0], pos, dispatch)
        var i: Int = 1
        while (i < labels.size()) {
            val equals: ExprNode = this.whenLabelCondition(subject, lengthName, labels[i], pos, dispatch)
            cond = this.binaryExprAt("||", cond, equals, pos)
            i = i + 1
        }
        return cond
    }

    // One label's test: `<subject> == <label>`, guarded when the lowering is on. A label whose
    // first byte has no printable spelling keeps the plain comparison, and a missing guard only
    // costs the `memcmp` it would have saved - so this is always safe, label by label.
    fun whenLabelCondition(
        subject: *
        ExprNode,
        lengthName: *
        Str,
        label: *
        AstXmlNode,
        pos: SourcePos,
        dispatch: Bool
    ): ExprNode {
        val equals: ExprNode = this.binaryExprAt(
            "==", subject, ExprNode(*label, pos.line, pos.column), pos
        )
        if (!dispatch) {
            return equals
        }
        val text: Str = xmlAttr(label, AstNodeAttributeKind.Text)
        val length: Int = litByteLength(text)
        var test: ExprNode = this.binaryExprAt(
            "==", this.nameExprAt(lengthName, pos), this.intLiteralAt(length, pos), pos
        )
        if (length == 0) {
            // The empty text is the only one of length zero, so the length test is the whole test.
            return test
        }
        val ch: Str = litCharSpelling(text)
        if (ch == "") {
            return this.binaryExprAt("&&", test, equals, pos)
        }
        if (length == 1 || whenFirstChar()) {
            test = this.binaryExprAt(
                "&&", test,
                this.binaryExprAt(
                    "==",
                    this.receiverIndexAt(subject, this.intLiteralAt(0, pos), pos),
                    this.charLiteralAt(ch, pos),
                    pos
                ),
                pos
            )
            if (length == 1) {
                // The one byte *is* the text, so the string compare has nothing left to decide.
                return test
            }
        }
        return this.binaryExprAt("&&", test, equals, pos)
    }

    // Whether every one of an arm's labels is a string literal: what the guarded tests need,
    // since a length is a compile-time property only for a literal.
    fun whenLabelsAreLiterals(labels: *List<AstXmlNode>): Bool {
        var i: Int = 0
        while (i < labels.size()) {
            if (xmlKind(*labels[i]) != AstNodeCategory.ExprStrLit) {
                return false
            }
            i = i + 1
        }
        return true
    }

    // A character literal whose source spelling is `spelling` (like `'x'`), which
    // `litCharSpelling` built from a byte of a string literal.
    fun charLiteralAt(spelling: *Str, pos: SourcePos): ExprNode {
        var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        attrs.append(AstNodeAttribute(AstNodeAttributeKind.Text, spelling))
        return ExprNode(
            AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprCharLit, attrs, Array<AstXmlNode>()),
            pos.line,
            pos.column
        )
    }

    // `when` (specs/functions.md): desugared here to the `if`/`else` chain it means, so
    // nothing downstream knows what a `when` is. The subject is bound once in a template, so it
    // is evaluated once; arms do not fall through, so a `break`/`continue` in one is the
    // enclosing loop's. A **place** subject skips the template entirely (`whenSubjectIsPlace`):
    // reading it again per test is free and cannot change.
    //
    // When *every* arm's labels are string literals - and `--when-dispatch` is on, by default -
    // the chain also binds a **view** of the subject (`_sm_when1_v`, `spanOfStr`,
    // cppsrc/rtl/StrView.kt) and its length (`_sm_when1_n`), and each label's test compares the
    // view, guarded by the length (`whenLabelCondition`). The view keeps the subject from being
    // copied per label, while a subject that already is a `StrView` views itself (the same
    // `spanOfStr`, whose `StrView` overload is the identity, cppsrc/rtl/_res.md), so the desugar
    // never has to know which of the two it got. The arms, their order and the `else` are
    // untouched, so the rewrite cannot change which arm matches.
    fun parseWhen(out: *List<AstXmlNode>): Bool {
        val pos: SourcePos = this.peek(0).pos
        this.advance()
        if (!this.expectText("(")) {
            return false
        }
        val subject: ExprNode = this.parseExpr(0)
        if (this.failed) {
            return false
        }
        if (!this.expectText(")")) {
            return false
        }
        this.skipNewlines()
        if (!this.expectText("{")) {
            return false
        }
        this.skipSeparators()

        // Bound before the arms are parsed, so a nested `for`/`when` in an arm takes the next id.
        val whenId: Int = this.nextTemplateId
        val subjectName: Str = "_sm_when" + whenId.toString()
        val viewName: Str = subjectName + "_v"
        val lengthName: Str = subjectName + "_n"
        this.nextTemplateId = whenId + 1

        // The subject's own value: itself when it is a place, the template otherwise.
        val place: Bool = this.whenSubjectIsPlace(subject.node) && !whenCopySubject()
        var base: ExprNode = subject
        if (!place) {
            base = this.nameExprAt(subjectName, pos)
        }
        var subjectExpr: ExprNode = base

        // One `if` per arm in source order, the `else` arm's statements as the tail. Labels and
        // bodies are collected first, because whether the tests can be guarded - every label a
        // string literal - is only known once every arm has been read.
        var armLabels: List<List<AstXmlNode>> = List<List<AstXmlNode>>()
        var armBodies: List<List<AstXmlNode>> = List<List<AstXmlNode>>()
        var armPositions: List<SourcePos> = List<SourcePos>()
        var tail: List<AstXmlNode> = List<AstXmlNode>()
        var literals: Bool = true
        var seenElse: Bool = false
        while (!this.checkText("}") && !this.atEnd() && !this.failed) {
            val armPos: SourcePos = this.peek(0).pos
            if (this.matchText("else")) {
                if (seenElse) {
                    this.fail("'when' can have only one 'else' arm")
                    return false
                }
                seenElse = true
                this.skipNewlines()
                if (!this.expectText("->")) {
                    return false
                }
                tail = this.parseBlock()
                if (this.failed) {
                    return false
                }
                this.skipSeparators()
                continue
            }
            if (seenElse) {
                this.fail("'else' must be the last arm of a 'when'")
                return false
            }
            // `is`/`in` are Kotlin's pattern labels; `when` matches a value with `==` only.
            if (this.checkText("is") || this.checkText("in")) {
                this.fail("'when' matches a value or 'else', not a pattern")
                return false
            }
            var labels: List<AstXmlNode> = List<AstXmlNode>()
            val first: ExprNode = this.parseExpr(0)
            if (this.failed) {
                return false
            }
            labels.append(first.node)
            while (this.matchText(",")) {
                this.skipNewlines()
                val next: ExprNode = this.parseExpr(0)
                if (this.failed) {
                    return false
                }
                labels.append(next.node)
            }
            this.skipNewlines()
            if (!this.expectText("->")) {
                return false
            }
            val body: List<AstXmlNode> = this.parseBlock()
            if (this.failed) {
                return false
            }
            if (!this.whenLabelsAreLiterals(*labels)) {
                literals = false
            }
            armLabels.append(labels)
            armBodies.append(body)
            armPositions.append(armPos)
            this.skipSeparators()
        }
        if (!this.expectText("}")) {
            return false
        }
        val dispatch: Bool = whenDispatch() && literals && armLabels.size() > 0
        if (dispatch) {
            // The tests compare a *view* over the subject, not the subject (see the header).
            subjectExpr = this.nameExprAt(viewName, pos)
        }

        var arms: List<AstXmlNode> = List<AstXmlNode>()
        var a: Int = 0
        while (a < armLabels.size()) {
            val cond: ExprNode = this.whenCondition(
                subjectExpr, lengthName, *armLabels[a], armPositions[a], dispatch
            )
            arms.append(this.ifNode(cond, *armBodies[a], armPositions[a]))
            a = a + 1
        }

        // The chain is right-nested, and the tail goes on first: linking copies an arm into
        // its predecessor's else body (nodes are values), so an arm must be complete before
        // it is.
        if (seenElse && arms.size() > 0 && tail.size() > 0) {
            xmlAddChild(arms[arms.size() - 1], this.container(AstNodeKind.Else, tail))
        }
        var i: Int = arms.size() - 1
        while (i > 0) {
            var next: List<AstXmlNode> = List<AstXmlNode>()
            next.append(arms[i])
            xmlAddChild(arms[i - 1], this.container(AstNodeKind.Else, next))
            i = i - 1
        }
        if (!place) {
            out.append(this.varDeclNode(subjectName, true, this.emptyNode(), subject, pos))
        }
        if (dispatch) {
            // The view, taken once from the subject (`spanOfStr`, cppsrc/rtl/StrView.kt).
            out.append(
                this.varDeclNode(viewName, true, this.emptyNode(), this.freeCallAt("spanOfStr", base, pos), pos)
            )
            // Once, so a guarded test does not call `size()` per label.
            out.append(
                this.varDeclNode(
                    lengthName, true, this.emptyNode(), this.receiverCallAt(subjectExpr, "size", pos), pos
                )
            )
        }
        if (arms.size() > 0) {
            out.append(arms[0])
        } else {
            // `else` was the only arm, so its statements are the whole construct.
            var e: Int = 0
            while (e < tail.size()) {
                out.append(tail[e])
                e = e + 1
            }
        }
        return true
    }

    fun parseReturn(): AstXmlNode {
        val pos: SourcePos = this.peek(0).pos
        this.advance()
        var value: ExprNode = this.emptyExpr()
        if (!this.atStmtEnd()) {
            if (this.checkText("(") && this.ctorReturnable()) {
                val block: AstXmlNode = this.tryParseCtorReturn(pos)
                if (!xmlIsEmpty(block)) {
                    return block
                }
            }
            value = this.parseExpr(0)
            if (this.failed) {
                return this.emptyNode()
            }
        }
        var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        var node: AstXmlNode = AstXmlNode(AstNodeKind.Stmt, AstNodeCategory.StmtReturn, attrs, Array<AstXmlNode>())
        if (value.node.name != AstNodeKind.None) {
            this.attach(node, AstNodeKind.Value, value.node)
        }
        return node
    }

    // Whether the enclosing return type can be constructed by `initByValue`: a plain named
    // or generic type (`Opt<Int>`, `Point`), never a pointer or reference.
    fun ctorReturnable(): Bool {
        if (xmlIsEmpty(this.returnTypeCtx)) {
            return false
        }
        val kind: AstNodeCategory = xmlKind(this.returnTypeCtx)
        return kind == AstNodeCategory.TypeNamed || kind == AstNodeCategory.TypeGeneric
    }

    // `return (a, b)`: `initByValue` constructs the return type. It is an *extension* on the
    // instance (it sets the receiver and returns nothing), so this lowers to a default-built
    // `T`, the extension called on it, and the `T` returned. Tries the group and restores the
    // cursor when it is not the whole statement, so a plain `return (a) || (b)` still parses.
    fun tryParseCtorReturn(pos: SourcePos): AstXmlNode {
        val savedCursor: Span<Token> = this.cursor
        val savedFailed: Bool = this.failed
        val savedError: Str = this.error
        val savedClosers: Int = this.pendingClosers
        this.advance()
        this.skipNewlines()
        var args: List<ExprNode> = List<ExprNode>()
        if (!this.checkText(")")) {
            while (true) {
                val arg: ExprNode = this.parseExpr(0)
                if (this.failed) {
                    break
                }
                args.append(arg)
                this.skipNewlines()
                if (!this.matchText(",")) {
                    break
                }
                this.skipNewlines()
            }
        }
        var ok: Bool = !this.failed && this.checkText(")")
        if (ok) {
            this.advance()
            ok = this.atStmtEnd()
        }
        if (!ok) {
            this.cursor = savedCursor
            this.failed = savedFailed
            this.error = savedError
            this.pendingClosers = savedClosers
            return this.emptyNode()
        }
        this.ctorCounter = this.ctorCounter + 1
        val temp: Str = fmtStr("_sm_ctor|", this.ctorCounter.toString())
        val tempExpr: ExprNode = this.nameExprAt(temp, pos)
        val retType: AstXmlNode = this.roleOf(this.returnTypeCtx, AstNodeKind.Type)
        val decl: AstXmlNode = this.varDeclNode(temp, true, retType, this.emptyExpr(), pos)
        var memberAttrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        memberAttrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, "initByValue"))
        var member: AstXmlNode =
            AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprMember, memberAttrs, Array<AstXmlNode>())
        this.attach(member, AstNodeKind.Receiver, tempExpr.node)
        var kids: List<AstXmlNode> = List<AstXmlNode>()
        kids.append(this.roleOf(member, AstNodeKind.Callee))
        var i: Int = 0
        while (i < args.size()) {
            kids.append(this.roleOf(args[i].node, AstNodeKind.Arg))
            i = i + 1
        }
        var callAttrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        var call: AstXmlNode = AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprCall, callAttrs, kids.toArray())
        var stmtAttrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        var stmt: AstXmlNode =
            AstXmlNode(AstNodeKind.Stmt, AstNodeCategory.StmtExprStmt, stmtAttrs, Array<AstXmlNode>())
        this.attach(stmt, AstNodeKind.Expr, call)
        var retAttrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        var ret: AstXmlNode = AstXmlNode(AstNodeKind.Stmt, AstNodeCategory.StmtReturn, retAttrs, Array<AstXmlNode>())
        this.attach(ret, AstNodeKind.Value, tempExpr.node)
        var body: List<AstXmlNode> = List<AstXmlNode>()
        body.append(decl)
        body.append(stmt)
        body.append(ret)
        var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        var block: AstXmlNode = AstXmlNode(AstNodeKind.Stmt, AstNodeCategory.StmtBlock, attrs, Array<AstXmlNode>())
        xmlAddChild(block, this.container(AstNodeKind.Body, body))
        return block
    }

    // The value the state machine hands out (impl_specs/yield.md); a `return`-like statement
    // the state-machine pass replaces.
    fun parseYield(): AstXmlNode {
        val pos: SourcePos = this.peek(0).pos
        this.advance()
        val value: ExprNode = this.parseExpr(0)
        if (this.failed) {
            return this.emptyNode()
        }
        var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        var node: AstXmlNode = AstXmlNode(AstNodeKind.Stmt, AstNodeCategory.StmtYield, attrs, Array<AstXmlNode>())
        this.attach(node, AstNodeKind.Value, value.node)
        return node
    }

    // Builders for the `for` desugaring below. Every node they make carries the `for`
    // token's position, so a diagnostic points at the line the user wrote.

    fun varDeclNode(name: *Str, isVar: Bool, typeNode: *AstXmlNode, init: *ExprNode, pos: SourcePos): AstXmlNode {
        var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        attrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
        attrs.append(AstNodeAttribute(AstNodeAttributeKind.IsVar, boolText(isVar)))
        var node: AstXmlNode = AstXmlNode(AstNodeKind.Stmt, AstNodeCategory.StmtVarDecl, attrs, Array<AstXmlNode>())
        if (typeNode.name != AstNodeKind.None) {
            xmlAddChild(node, typeNode)
        }
        if (init.node.name != AstNodeKind.None) {
            this.attach(node, AstNodeKind.Init, init.node)
        }
        return node
    }

    fun namedTypeNode(name: *Str, pos: SourcePos): AstXmlNode {
        var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        attrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
        return AstXmlNode(AstNodeKind.Type, AstNodeCategory.TypeNamed, attrs, Array<AstXmlNode>())
    }

    fun assignNode(target: *ExprNode, value: *ExprNode, pos: SourcePos): AstXmlNode {
        var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        attrs.append(AstNodeAttribute(AstNodeAttributeKind.Op, "="))
        var node: AstXmlNode = AstXmlNode(AstNodeKind.Stmt, AstNodeCategory.StmtAssign, attrs, Array<AstXmlNode>())
        this.attach(node, AstNodeKind.Target, target.node)
        this.attach(node, AstNodeKind.Value, value.node)
        return node
    }

    fun breakNode(pos: SourcePos): AstXmlNode {
        return AstXmlNode(
            AstNodeKind.Stmt,
            AstNodeCategory.StmtBreak,
            this.posAttrs(pos.line, pos.column),
            Array<AstXmlNode>()
        )
    }

    fun ifNode(cond: *ExprNode, thenBody: *List<AstXmlNode>, pos: SourcePos): AstXmlNode {
        var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        var node: AstXmlNode = AstXmlNode(AstNodeKind.Stmt, AstNodeCategory.StmtIf, attrs, Array<AstXmlNode>())
        this.attach(node, AstNodeKind.Cond, cond.node)
        xmlAddChild(node, this.container(AstNodeKind.Then, thenBody))
        return node
    }

    fun whileNode(cond: *ExprNode, body: *List<AstXmlNode>, pos: SourcePos): AstXmlNode {
        var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        var node: AstXmlNode = AstXmlNode(AstNodeKind.Stmt, AstNodeCategory.StmtWhile, attrs, Array<AstXmlNode>())
        this.attach(node, AstNodeKind.Cond, cond.node)
        xmlAddChild(node, this.container(AstNodeKind.Body, body))
        return node
    }

    fun nameExprAt(text: *Str, pos: SourcePos): ExprNode {
        var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        attrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, text))
        return ExprNode(
            AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprName, attrs, Array<AstXmlNode>()),
            pos.line,
            pos.column
        )
    }

    fun intLiteralAt(value: Int, pos: SourcePos): ExprNode {
        var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        attrs.append(AstNodeAttribute(AstNodeAttributeKind.Text, value.toString()))
        return ExprNode(
            AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprIntLit, attrs, Array<AstXmlNode>()),
            pos.line,
            pos.column
        )
    }

    fun boolLiteralAt(value: Bool, pos: SourcePos): ExprNode {
        var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        attrs.append(AstNodeAttribute(AstNodeAttributeKind.Value, boolText(value)))
        return ExprNode(
            AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprBoolLit, attrs, Array<AstXmlNode>()),
            pos.line,
            pos.column
        )
    }

    fun unaryExprAt(op: *Str, operand: *ExprNode, pos: SourcePos): ExprNode {
        var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        attrs.append(AstNodeAttribute(AstNodeAttributeKind.Op, op))
        var node: AstXmlNode = AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprUnary, attrs, Array<AstXmlNode>())
        this.attach(node, AstNodeKind.Operand, operand.node)
        return ExprNode(node, pos.line, pos.column)
    }

    fun binaryExprAt(op: *Str, lhs: *ExprNode, rhs: *ExprNode, pos: SourcePos): ExprNode {
        var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        attrs.append(AstNodeAttribute(AstNodeAttributeKind.Op, op))
        var node: AstXmlNode = AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprBinary, attrs, Array<AstXmlNode>())
        this.attach(node, AstNodeKind.Lhs, lhs.node)
        this.attach(node, AstNodeKind.Rhs, rhs.node)
        return ExprNode(node, pos.line, pos.column)
    }

    // The `<target>.<wrap>()` wrap a `for` puts around what it iterates (`iter`/`iterPtr`).
    fun iterCall(target: *ExprNode, pos: SourcePos, wrap: *Str): ExprNode {
        var memberAttrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        memberAttrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, wrap))
        var member: AstXmlNode =
            AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprMember, memberAttrs, Array<AstXmlNode>())
        this.attach(member, AstNodeKind.Receiver, target.node)
        var callAttrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        var call: AstXmlNode = AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprCall, callAttrs, Array<AstXmlNode>())
        this.attach(call, AstNodeKind.Callee, member)
        return ExprNode(call, pos.line, pos.column)
    }

    // What the machine last yielded (`current`): the `for` template's loop variable.
    fun memberExprAt(target: *Str, field: *Str, pos: SourcePos): ExprNode {
        var memberAttrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        memberAttrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, field))
        var member: AstXmlNode =
            AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprMember, memberAttrs, Array<AstXmlNode>())
        val receiver: ExprNode = this.nameExprAt(target, pos)
        this.attach(member, AstNodeKind.Receiver, receiver.node)
        return ExprNode(member, pos.line, pos.column)
    }

    // `<target>.<method>()`: the machine's `advance`.
    fun methodCallAt(target: *Str, method: *Str, pos: SourcePos): ExprNode {
        var memberAttrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        memberAttrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, method))
        var member: AstXmlNode =
            AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprMember, memberAttrs, Array<AstXmlNode>())
        val receiver: ExprNode = this.nameExprAt(target, pos)
        this.attach(member, AstNodeKind.Receiver, receiver.node)
        var callAttrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        var call: AstXmlNode = AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprCall, callAttrs, Array<AstXmlNode>())
        this.attach(call, AstNodeKind.Callee, member)
        return ExprNode(call, pos.line, pos.column)
    }

    // `for` (specs/functions.md): two forms, each binding an element or a *pointer* to it,
    // all iterating a state machine (`..T`). Lowered here to the `while` it means, so no
    // stage downstream sees a `for` and `break`/`continue` inside are the `while`'s own.
    //
    //   for (v in m) { body }       var _sm_for1 = m
    //                               while (_sm_for1.advance()) {
    //                                   val v = _sm_for1.current
    //                                   body
    //                               }
    //
    //   for ((v, i) in m) { ... }   the same, plus `var _sm_index1: Int = -1` before the
    //                               loop, the pre-increment as the body's first statement,
    //                               and `val i = _sm_index1` after `v`.
    //
    // The index pre-increments as the body's *first* statement, not in the condition:
    // `continue` jumps to the condition, so a bump at the end of the body would miss that
    // iteration, and `-1` makes the first one 0. The names come from the per-file
    // `nextTemplateId` counter, so nested loops never collide and two runs agree.
    fun parseFor(out: *List<AstXmlNode>): Bool {
        val pos: SourcePos = this.peek(0).pos
        this.advance()
        if (!this.expectText("(")) {
            return false
        }
        var valueName: Str = ""
        var indexName: Str = ""
        var withIndex: Bool = false
        var valueIsPointer: Bool = false
        if (this.matchText("(")) {
            withIndex = true
            valueIsPointer = this.matchText("*")
            valueName = this.expectName()
            if (this.failed) {
                return false
            }
            if (!this.expectText(",")) {
                return false
            }
            indexName = this.expectName()
            if (this.failed) {
                return false
            }
            if (!this.expectText(")")) {
                return false
            }
        } else {
            valueIsPointer = this.matchText("*")
            valueName = this.expectName()
            if (this.failed) {
                return false
            }
        }
        if (!this.matchText("in")) {
            return this.fail("expected 'in'")
        }
        this.skipNewlines()
        val machine: ExprNode = this.parseExpr(0)
        if (this.failed) {
            return false
        }
        if (!this.expectText(")")) {
            return false
        }
        val body: List<AstXmlNode> = this.parseBlock()
        if (this.failed) {
            return false
        }

        val id: Int = this.nextTemplateId
        this.nextTemplateId = this.nextTemplateId + 1
        val machineName: Str = "_sm_for" + id.toString()
        val counterName: Str = "_sm_index" + id.toString()

        // A *member* `iter()` call, not `iter(x)`: that is what binds the function's type
        // parameter from the receiver, leaving the loop variable typed.
        var wrap: Str = "iter"
        if (valueIsPointer) {
            wrap = "iterPtr"
        }
        val iterated: ExprNode = this.iterCall(machine, pos, wrap)
        out.append(this.varDeclNode(machineName, true, this.emptyNode(), iterated, pos))
        if (withIndex) {
            val counterInit: ExprNode = this.intLiteralAt(-1, pos)
            out.append(this.varDeclNode(counterName, true, this.namedTypeNode("Int", pos), counterInit, pos))
        }

        var loop: List<AstXmlNode> = List<AstXmlNode>()
        if (withIndex) {
            val target: ExprNode = this.nameExprAt(counterName, pos)
            val one: ExprNode = this.intLiteralAt(1, pos)
            val value: ExprNode = this.binaryExprAt("+", this.nameExprAt(counterName, pos), one, pos)
            loop.append(this.assignNode(target, value, pos))
        }
        val stepValue: ExprNode = this.memberExprAt(machineName, "current", pos)
        loop.append(this.varDeclNode(valueName, false, this.emptyNode(), stepValue, pos))
        if (withIndex) {
            val counterRef: ExprNode = this.nameExprAt(counterName, pos)
            loop.append(this.varDeclNode(indexName, false, this.emptyNode(), counterRef, pos))
        }
        var bi: Int = 0
        while (bi < body.size()) {
            loop.append(body[bi])
            bi = bi + 1
        }
        out.append(this.whileNode(this.methodCallAt(machineName, "advance", pos), loop, pos))
        return true
    }

    fun parseExpr(minBindingPower: Int): ExprNode {
        var left: ExprNode = this.parseUnary()
        if (this.failed) {
            return this.emptyExpr()
        }
        while (true) {
            var newlines: Int = 0
            while (this.peek(newlines).kind == TokenKind.EndOfLine) {
                newlines = newlines + 1
            }
            if (newlines > 0) {
                val lookaheadBP: Int = binaryBindingPower(this.peek(newlines).text)
                if (lookaheadBP < 0 || lookaheadBP < minBindingPower) {
                    break
                }
                var k: Int = 0
                while (k < newlines) {
                    this.advance()
                    k = k + 1
                }
            }
            val bp: Int = binaryBindingPower(this.peek(0).text)
            if (bp < 0 || bp < minBindingPower) {
                break
            }
            val op: Str = this.advance().text
            this.skipNewlines()
            val right: ExprNode = this.parseExpr(bp + 1)
            if (this.failed) {
                return this.emptyExpr()
            }
            // One side of a comparison against a string literal is wrapped in `spanOfStr`: the
            // test reads a view of the place instead of copying it into a `Str` temporary, and
            // a `*Str` operand is not read at all. Only a *place* is wrapped - a call's or an
            // operation's result is materialised anyway, so a view of it would only add work.
            var lhsNode: AstXmlNode = left.node
            var rhsNode: AstXmlNode = right.node
            if (this.isStringCompareOp(op)) {
                if (xmlKind(right.node) == AstNodeCategory.ExprStrLit
                    && this.whenSubjectIsPlace(left.node)
                ) {
                    val viewed: ExprNode = this.freeCallAt("spanOfStr", left, SourcePos(0, left.line, left.column))
                    lhsNode = viewed.node
                }
                if (xmlKind(left.node) == AstNodeCategory.ExprStrLit
                    && this.whenSubjectIsPlace(right.node)
                ) {
                    val viewed: ExprNode = this.freeCallAt("spanOfStr", right, SourcePos(0, right.line, right.column))
                    rhsNode = viewed.node
                }
            }
            var attrs: List<AstNodeAttribute> = this.posAttrs(left.line, left.column)
            attrs.append(AstNodeAttribute(AstNodeAttributeKind.Op, op))
            var node: AstXmlNode = AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprBinary, attrs, Array<AstXmlNode>())
            this.attach(node, AstNodeKind.Lhs, lhsNode)
            this.attach(node, AstNodeKind.Rhs, rhsNode)
            left = ExprNode(node, left.line, left.column)
        }
        return left
    }

    fun parseUnary(): ExprNode {
        val pos: SourcePos = this.peek(0).pos
        val text: Str = this.peek(0).text
        when (text) {
            "!", "-" -> {
                val op: Str = this.advance().text
                val operand: ExprNode = this.parseUnary()
                if (this.failed) {
                    return this.emptyExpr()
                }
                var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
                attrs.append(AstNodeAttribute(AstNodeAttributeKind.Op, op))
                var node: AstXmlNode =
                    AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprUnary, attrs, Array<AstXmlNode>())
                this.attach(node, AstNodeKind.Operand, operand.node)
                return ExprNode(node, pos.line, pos.column)
            }

            "&" -> {
                this.advance()
                val operand: ExprNode = this.parseUnary()
                if (this.failed) {
                    return this.emptyExpr()
                }
                var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
                var node: AstXmlNode = AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprRef, attrs, Array<AstXmlNode>())
                this.attach(node, AstNodeKind.Operand, operand.node)
                return ExprNode(node, pos.line, pos.column)
            }

            "*" -> {
                this.advance()
                val operand: ExprNode = this.parseUnary()
                if (this.failed) {
                    return this.emptyExpr()
                }
                var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
                var node: AstXmlNode =
                    AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprDeref, attrs, Array<AstXmlNode>())
                this.attach(node, AstNodeKind.Operand, operand.node)
                return ExprNode(node, pos.line, pos.column)
            }
        }
        return this.parsePostfix()
    }

    fun parsePostfix(): ExprNode {
        var expr: ExprNode = this.parsePrimary()
        if (this.failed) {
            return this.emptyExpr()
        }
        // One list for the whole walk, cleared per call: the arguments of the call being parsed.
        var args: List<AstXmlNode> = List<AstXmlNode>()
        while (true) {
            if (this.matchText("(")) {
                args.clear()
                this.skipNewlines()
                if (!this.checkText(")")) {
                    val first: ExprNode = this.parseExpr(0)
                    if (this.failed) {
                        return this.emptyExpr()
                    }
                    args.append(first.node)
                    this.skipNewlines()
                    while (this.matchText(",")) {
                        this.skipNewlines()
                        val next: ExprNode = this.parseExpr(0)
                        if (this.failed) {
                            return this.emptyExpr()
                        }
                        args.append(next.node)
                        this.skipNewlines()
                    }
                }
                if (!this.expectText(")")) {
                    return this.emptyExpr()
                }
                var kids: List<AstXmlNode> = List<AstXmlNode>()
                kids.append(this.roleOf(expr.node, AstNodeKind.Callee))
                var a: Int = 0
                while (a < args.size()) {
                    kids.append(this.roleOf(*args[a], AstNodeKind.Arg))
                    a = a + 1
                }
                var attrs: List<AstNodeAttribute> = this.posAttrs(expr.line, expr.column)
                var node: AstXmlNode =
                    AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprCall, attrs, kids.toArray())
                expr = ExprNode(node, expr.line, expr.column)
            } else if (this.matchText("[")) {
                this.skipNewlines()
                val index: ExprNode = this.parseExpr(0)
                if (this.failed) {
                    return this.emptyExpr()
                }
                if (!this.expectText("]")) {
                    return this.emptyExpr()
                }
                var attrs: List<AstNodeAttribute> = this.posAttrs(expr.line, expr.column)
                var node: AstXmlNode =
                    AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprIndex, attrs, Array<AstXmlNode>())
                this.attach(node, AstNodeKind.Receiver, expr.node)
                this.attach(node, AstNodeKind.Index, index.node)
                expr = ExprNode(node, expr.line, expr.column)
            } else if (this.matchText(".")) {
                val name: Str = this.expectName()
                if (this.failed) {
                    return this.emptyExpr()
                }
                // A generic member call (`h.getAs<Int8>()`): tentative, the way a leading
                // `name<...>` is in `parsePrimary` - the closer must be followed by `(`, or the
                // `<` was a comparison (`m.branch < 3`) and this is a plain member.
                var typeArgs: List<AstXmlNode> = List<AstXmlNode>()
                if (this.checkText("<")) {
                    val savedCursor: Span<Token> = this.cursor
                    val savedFailed: Bool = this.failed
                    val savedError: Str = this.error
                    val savedClosers: Int = this.pendingClosers
                    typeArgs = this.parseGenericArgs()
                    if (this.failed || !this.checkText("(")) {
                        this.cursor = savedCursor
                        this.failed = savedFailed
                        this.error = savedError
                        this.pendingClosers = savedClosers
                        typeArgs = List<AstXmlNode>()
                    }
                }
                var attrs: List<AstNodeAttribute> = this.posAttrs(expr.line, expr.column)
                attrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
                var node: AstXmlNode =
                    AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprMember, attrs, Array<AstXmlNode>())
                this.attach(node, AstNodeKind.Receiver, expr.node)
                if (typeArgs.size() > 0) {
                    xmlAddChildren(node, typeArgs)
                }
                expr = ExprNode(node, expr.line, expr.column)
            } else if (this.checkText("!") && this.peek(1).text == "!") {
                // `x!!` (cppsrc/parser/Propagate.kt): the payload of a `Res`, or its failure
                // returned out of the enclosing function. Two bangs, so no scanner change.
                this.advance()
                this.advance()
                var attrs: List<AstNodeAttribute> = this.posAttrs(expr.line, expr.column)
                var node: AstXmlNode =
                    AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprPropagate, attrs, Array<AstXmlNode>())
                this.attach(node, AstNodeKind.Operand, expr.node)
                expr = ExprNode(node, expr.line, expr.column)
            } else {
                break
            }
        }
        return expr
    }

    // A string token's text: the ordinary quoted literal as written, or the quoted literal a
    // backtick string's raw content spells (litRawString) - so everything downstream, the
    // when guards and StrView resolution included, sees one kind of text.
    fun stringTokenText(text: *Str): Str {
        if (text.size() > 0 && text[0] == '`') {
            return litRawString(text)
        }
        return * text
    }

    fun parsePrimary(): ExprNode {
        val pos: SourcePos = this.peek(0).pos
        if (this.checkKind(TokenKind.Number)) {
            val text: Str = this.advance().text
            var kind: AstNodeCategory = AstNodeCategory.ExprIntLit
            if (text.find(".") != -1) {
                kind = AstNodeCategory.ExprFloatLit
            }
            var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
            attrs.append(AstNodeAttribute(AstNodeAttributeKind.Text, text))
            return ExprNode(AstXmlNode(AstNodeKind.Expr, kind, attrs, Array<AstXmlNode>()), pos.line, pos.column)
        }
        if (this.checkKind(TokenKind.String)) {
            val raw: Str = this.advance().text
            val text: Str = this.stringTokenText(raw)
            var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
            attrs.append(AstNodeAttribute(AstNodeAttributeKind.Text, text))
            return ExprNode(
                AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprStrLit, attrs, Array<AstXmlNode>()),
                pos.line,
                pos.column
            )
        }
        if (this.checkKind(TokenKind.Character)) {
            val text: Str = this.advance().text
            var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
            attrs.append(AstNodeAttribute(AstNodeAttributeKind.Text, text))
            return ExprNode(
                AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprCharLit, attrs, Array<AstXmlNode>()),
                pos.line,
                pos.column
            )
        }
        if (this.checkText("true") || this.checkText("false")) {
            val text: Str = this.advance().text
            var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
            if (text == "true") {
                attrs.append(AstNodeAttribute(AstNodeAttributeKind.Value, "true"))
            } else {
                attrs.append(AstNodeAttribute(AstNodeAttributeKind.Value, "false"))
            }
            return ExprNode(
                AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprBoolLit, attrs, Array<AstXmlNode>()),
                pos.line,
                pos.column
            )
        }
        if (this.checkText("null")) {
            this.advance()
            val attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
            return ExprNode(
                AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprNullLit, attrs, Array<AstXmlNode>()),
                pos.line,
                pos.column
            )
        }
        if (this.checkText("this")) {
            this.advance()
            var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
            attrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, "this"))
            return ExprNode(
                AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprName, attrs, Array<AstXmlNode>()),
                pos.line,
                pos.column
            )
        }
        if (this.checkKind(TokenKind.Identifier)) {
            val name: Str = this.peek(0).text
            if (name == "copy" && this.peek(1).text == "(") {
                this.advance()
                this.advance()
                val inner: ExprNode = this.parseExpr(0)
                if (this.failed) {
                    return this.emptyExpr()
                }
                if (!this.expectText(")")) {
                    return this.emptyExpr()
                }
                var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
                var node: AstXmlNode =
                    AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprCopy, attrs, Array<AstXmlNode>())
                this.attach(node, AstNodeKind.Operand, inner.node)
                return ExprNode(node, pos.line, pos.column)
            }
            if (this.peek(1).text == "<") {
                val savedCursor: Span<Token> = this.cursor
                val savedFailed: Bool = this.failed
                val savedError: Str = this.error
                this.advance()
                val typeArgs: List<AstXmlNode> = this.parseGenericArgs()
                if (!this.failed && (this.checkText(".") || this.checkText("("))) {
                    var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
                    attrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
                    var gnode: AstXmlNode =
                        AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprGenericName, attrs, Array<AstXmlNode>())
                    xmlAddChildren(gnode, typeArgs)
                    return ExprNode(gnode, pos.line, pos.column)
                }
                this.cursor = savedCursor
                this.failed = savedFailed
                this.error = savedError
            }
            this.advance()
            var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
            attrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
            return ExprNode(
                AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprName, attrs, Array<AstXmlNode>()),
                pos.line,
                pos.column
            )
        }
        if (this.checkText("(")) {
            val savedCursor: Span<Token> = this.cursor
            val savedFailed: Bool = this.failed
            val savedError: Str = this.error
            val lambda: ExprNode = this.tryParseLambda()
            if (!this.failed) {
                return lambda
            }
            this.cursor = savedCursor
            this.failed = savedFailed
            this.error = savedError
            this.advance()
            this.skipNewlines()
            val inner: ExprNode = this.parseExpr(0)
            if (this.failed) {
                return this.emptyExpr()
            }
            if (!this.expectText(")")) {
                return this.emptyExpr()
            }
            return inner
        }
        this.fail("expected expression")
        return this.emptyExpr()
    }

    fun tryParseLambda(): ExprNode {
        if (!this.matchText("(")) {
            this.fail("expected '('")
            return this.emptyExpr()
        }
        val pos: SourcePos = this.peek(0).pos
        var names: List<Str> = List<Str>()
        var paramTypes: List<AstXmlNode> = List<AstXmlNode>()
        this.skipNewlines()
        if (!this.checkText(")")) {
            while (true) {
                if (!this.checkKind(TokenKind.Identifier)) {
                    this.fail("expected lambda parameter")
                    return this.emptyExpr()
                }
                names.append(this.advance().text)
                if (this.matchText(":")) {
                    val paramType: AstXmlNode = this.parseType(AstNodeKind.ParamType)
                    if (this.failed) {
                        return this.emptyExpr()
                    }
                    paramTypes.append(paramType)
                }
                this.skipNewlines()
                if (!this.matchText(",")) {
                    break
                }
                this.skipNewlines()
            }
        }
        if (!this.checkText(")")) {
            this.fail("expected ')'")
            return this.emptyExpr()
        }
        if (this.peek(1).text != "->") {
            this.fail("expected '->'")
            return this.emptyExpr()
        }
        this.advance()
        this.advance()

        var body: List<AstXmlNode> = List<AstXmlNode>()
        if (this.checkText("{")) {
            // A lambda has no declared return type, so its returns construct nothing.
            val savedReturnType: AstXmlNode = this.returnTypeCtx
            this.returnTypeCtx = this.emptyNode()
            body = this.parseBlock()
            this.returnTypeCtx = savedReturnType
            if (this.failed) {
                return this.emptyExpr()
            }
        } else {
            val value: ExprNode = this.parseExpr(0)
            if (this.failed) {
                return this.emptyExpr()
            }
            var sattrs: List<AstNodeAttribute> = this.posAttrs(value.line, value.column)
            var snode: AstXmlNode =
                AstXmlNode(AstNodeKind.Stmt, AstNodeCategory.StmtExprStmt, sattrs, Array<AstXmlNode>())
            this.attach(snode, AstNodeKind.Expr, value.node)
            body.append(snode)
        }

        var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        attrs.append(AstNodeAttribute(AstNodeAttributeKind.Params, joinStrs(names, ",")))
        var node: AstXmlNode = AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprLambda, attrs, Array<AstXmlNode>())
        xmlAddChildren(node, paramTypes)
        xmlAddChild(node, this.container(AstNodeKind.Body, body))
        return ExprNode(node, pos.line, pos.column)
    }
}

// Parses a pre-filtered token cursor (with a synthetic Eof already appended).
fun parseModule(cursor: Span<Token>, fileName: *Str): Res<AstXmlNode> {
    var parser: Parser = Parser(
        cursor, false, "", fileName, 1, 0,
        AstXmlNode(AstNodeKind.None, AstNodeCategory.None, List<AstNodeAttribute>(), Array<AstXmlNode>()), 0
    )
    val root: AstXmlNode = parser.parseRoot()
    if (parser.failed) {
        return Res<AstXmlNode>.err(parser.error)
    }
    return Res<AstXmlNode>.ok(root)
}

// Drops Space/Comment tokens and appends a synthetic Eof. A newline inside `(...)` or
// `[...]` separates nothing: the list may be wrapped (specs/declarations.md).
fun parseModule(tokens: *List<Token>, fileName: *Str): Res<AstXmlNode> {
    var toks: List<Token> = List<Token>()
    var bracketed: Int = 0
    var i: Int = 0
    while (i < tokens.size()) {
        val token: Token = tokens[i]
        if (token.kind == TokenKind.Operator) {
            if (token.text == "(" || token.text == "[") {
                bracketed = bracketed + 1
            } else if (token.text == ")" || token.text == "]") {
                if (bracketed > 0) {
                    bracketed = bracketed - 1
                }
            }
        }
        if (token.kind != TokenKind.Space && token.kind != TokenKind.Comment) {
            if (!(token.kind == TokenKind.EndOfLine && bracketed > 0)) {
                toks.append(token)
            }
        }
        i = i + 1
    }
    var eofPos: SourcePos = SourcePos(0, 1, 1)
    if (toks.size() > 0) {
        eofPos = toks[toks.size() - 1].pos
    }
    toks.append(Token("", TokenKind.Eof, eofPos))
    return parseModule(spanOf(*toks), fileName)
}
