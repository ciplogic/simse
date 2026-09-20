// Parser.kt
//
// The grammar parser, ported from cppsrc/parser/Parser.cpp. It consumes tokens
// from the scanner and builds an AstXmlNode tree using the schema in
// impl_specs/ast-xmlnode.md, byte-for-byte compatible with ast::toXmlNode in
// cppsrc/ast/Ast.cpp.
//
// Iteration uses `Span<Token>` (there is no range-for, and the parser's token
// window is a span over the scanner's list: `slice(1)` advances it). Errors are
// recorded in `failed`/`error`; the entry points return Res.err with a positioned
// message.

package parser

import lex
import common

// An expression node plus the source position where the expression started. The
// position is needed by parents (a Call/Index/Member/Binary node takes its
// position from its callee/receiver/left operand).
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
    var pendingClosers: Int
) {

    // ---- token cursor helpers ---------------------------------------------

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

    // One `>` of a type-argument list's closer. `>>` and `>>=` scan as single tokens (they
    // are the shift operators), so a closer that finds one takes a single `>` out of it and
    // remembers the rest: the closer of the list it is nested in takes that one without
    // reading a token, which is what `List<List<Int>>` needs. What a `>>=` mostly is - the
    // `>=` operator - is not something a closer can be asked for, so that one spelling is a
    // diagnostic: write a space before the `=`.
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

    // The separator between a data class's fields: Kotlin's `,`
    // (specs/declarations.md, "data class"). A `;` is accepted there too - it is what the
    // sources used before the spelling was aligned with Kotlin, and it costs nothing to
    // keep. Only the *field* list takes a comma: between statements and methods it is
    // not a separator, because there it would read as an expression part.
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

    // ---- AstXmlNode builders -------------------------------------------------

    fun emptyNode(): AstXmlNode {
        return AstXmlNode(AstNodeKind.None, AstNodeCategory.None, List<AstNodeAttribute>(), Array<AstXmlNode>())
    }

    fun emptyExpr(): ExprNode {
        return ExprNode(this.emptyNode(), 0, 0)
    }

    // kind, line, column - the common prefix of most node attribute lists. The
    // node's kind is a field (`AstNodeCategory`), so only the position is here.
    fun posAttrs(line: Int, column: Int): List<AstNodeAttribute> {
        var attrs: List<AstNodeAttribute> = listOf<AstNodeAttribute>(
            AstNodeAttribute(AstNodeAttributeKind.Line, line.toString()),
            AstNodeAttribute(AstNodeAttributeKind.Column, column.toString())
        )
        return attrs
    }

    // Appends `child` under `parent` with the child's element role set to `role`.
    // `parent` is a non-owning pointer, so the append replaces the caller's
    // `Children` handle even though the node itself is not passed by value.
    fun attach(parent: *AstXmlNode, role: AstNodeKind, child: *AstXmlNode): Unit {
        xmlAddChild(parent, this.roleOf(child, role))
    }

    // `child` under `role`, for a caller that is collecting a node's children itself:
    // appending them one at a time through `xmlAddChild` rebuilds the whole children
    // array per child (an append is an array-to-list-to-array round trip), so a node
    // with k children costs O(k*k) - a call collects its callee and arguments this way
    // and builds the array once.
    fun roleOf(child: *AstXmlNode, role: AstNodeKind): AstXmlNode {
        var renamed: AstXmlNode = child
        renamed.name = role
        return renamed
    }

    // A container node is one whose children are just `children`: the array is
    // built once from the list rather than appended to per child.
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

    // ---- module -----------------------------------------------------------

    fun parseRoot(): AstXmlNode {
        this.skipSeparators()
        // A mandatory, single, file-level `package a.b.c` as the first
        // declaration. A second `package` is rejected by parseDecl.
        var packageName: Str = ""
        if (!this.checkText("package")) {
            this.fail("expected 'package' declaration")
            return this.emptyNode()
        }
        this.advance() // package
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

        // Every file declares exactly one package; the attribute is always
        // present. Matches ast::toXmlNode.
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

    // ---- declarations -----------------------------------------------------

    fun parseImport(): AstXmlNode {
        val pos: SourcePos = this.peek(0).pos
        this.advance() // import
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
            return this.parseAttributedDecl()
        }
        val text: Str = this.peek(0).text
        when (text) {
            "var", "val" -> {
                return this.parseStaticVar()
            }

            "data" -> {
                return this.parseDataClass()
            }

            "enum" -> {
                return this.parseEnum()
            }

            "typealias" -> {
                return this.parseTypeAlias()
            }

            "native" -> {
                return this.parseFunction(true, "", List<Str>())
            }

            "fun" -> {
                return this.parseFunction(false, "", List<Str>())
            }
        }
        this.fail("expected declaration")
        return this.emptyNode()
    }

    // An attributed declaration: `@SmGen("cpp", "sym") fun f(...)`
    // (specs/attributes.md). The `@Name` is one token (`TokenKind.Attribute`) and the
    // arguments are literals; only method declarations take attributes in this baseline,
    // so the declaration that must follow is `fun`.
    fun parseAttributedDecl(): AstXmlNode {
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
                args.append(arg.text)
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
        // An attribute rides on its own line - the declaration it belongs to starts on
        // the next one - so the separator between the two is skipped.
        this.skipSeparators()
        if (!this.checkText("fun")) {
            this.fail("expected 'fun' after an attribute")
            return this.emptyNode()
        }
        return this.parseFunction(false, attrName, args)
    }

    // A file-level `var`/`val`: static storage (specs/statics.md). The type is
    // required (no inference) and the initializer is optional. The children are
    // the same shape as a `Stmt.VarDecl`, so the emitters have one variable form;
    // the role is `Var` because this is a declaration, not a statement.
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
        this.advance() // data
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
        // The body brace may stand on its own line (a formatter wraps the parameter list
        // and leaves `{` behind it): a newline there is not a declaration boundary.
        this.skipNewlines()
        if (this.checkText("{")) {
            this.advance()
            this.skipSeparators()
            while (!this.checkText("}") && !this.atEnd() && !this.failed) {
                if (!this.checkText("fun")) {
                    this.fail("expected method declaration")
                    return this.emptyNode()
                }
                methods.append(this.parseFunction(false, "", List<Str>()))
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
        this.advance() // enum
        // `enum class`, spelled the way `data class` is: there is no bare `enum`.
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
        this.advance() // typealias
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
        // `..` starts a `..T` receiver: `fun ..T.smToYield<T>()` is the wrap that makes a
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

    // `attrName`/`attrArgs` are the parsed `@SmGen` attribute, empty for a declaration
    // without one (specs/attributes.md). A method that carries an attribute may be
    // body-less, and its implementation belongs to the attribute's generator; a
    // body-less method with no attribute has no implementation at all, which is what
    // the `hasBody` check below rejects. The arguments are kept as they were written
    // (a string literal still has its quotes), so the two spellings of one declaration
    // - `native(sym)` and `@SmGen("cpp", sym)` - fill exactly the same attributes.
    fun parseFunction(isNative: Bool, attrName: *Str, attrArgs: *List<Str>): AstXmlNode {
        val pos: SourcePos = this.peek(0).pos
        var nativeSymbol: Str = ""
        var hasNativeSymbol: Bool = false

        if (isNative) {
            this.advance() // native
            if (this.matchText("(")) {
                if (this.checkKind(TokenKind.String)) {
                    nativeSymbol = this.advance().text
                    hasNativeSymbol = true
                }
                if (!this.expectText(")")) {
                    return this.emptyNode()
                }
            }
            if (!this.expectText("fun")) {
                return this.emptyNode()
            }
        } else {
            if (!this.expectText("fun")) {
                return this.emptyNode()
            }
        }

        var hasReceiver: Bool = false
        var receiverNode: AstXmlNode = this.emptyNode()
        var declName: Str = ""

        if (!isNative && this.looksLikeTypeStart()) {
            val savedCursor: Span<Token> = this.cursor
            val savedFailed: Bool = this.failed
            val savedError: Str = this.error
            val receiver: AstXmlNode = this.parseType(AstNodeKind.Receiver)
            if (!this.failed && this.checkText(".")) {
                this.advance() // .
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
            body = this.parseBlock()
            if (this.failed) {
                return this.emptyNode()
            }
            hasBody = true
        }

        // What an attribute means: the declaration's C++ is the generator's, so there
        // is no body to emit, and the generator names the symbol. `@SmGen("cpp", sym)`
        // is the form `native(sym)` spells, so it fills the same attributes - which is
        // what makes the two spellings one declaration. `native(sym)` is sugar for it
        // (impl_specs/generators.md), so the attributes are filled the same way
        // whichever was written.
        var attributeName: Str = attrName
        var generatorName: Str = ""
        var generatorArgs: Str = ""
        if (attrName.size() > 0) {
            // The attribute's first argument names the generator; the rest are its own.
            if (attrArgs.size() > 0) {
                generatorName = attrLiteralText(attrArgs[0])
            }
            var a: Int = 1
            while (a < attrArgs.size()) {
                if (a > 1) {
                    generatorArgs = generatorArgs + ","
                }
                generatorArgs = generatorArgs + attrLiteralText(attrArgs[a])
                a = a + 1
            }
        } else if (isNative) {
            attributeName = "SmGen"
            generatorName = "cpp"
            if (hasNativeSymbol) {
                generatorArgs = attrLiteralText(nativeSymbol)
            }
        }
        if (attributeName.size() > 0) {
            // The generator owns the C++: nothing is emitted for the declaration itself,
            // and a call reaches the symbol instead.
            isNative = true
            if (hasBody) {
                this.setError(pos, "a method whose C++ is generated must not have a body")
                return this.emptyNode()
            }
            // The argument that names the C++ symbol a call reaches: `cpp` has no
            // parameters of its own, so its symbol is the argument right after the
            // generator's name; `res` names its section first, so its symbol is the
            // third. `kt` names no symbol at all (its text is compiled from source).
            // Whichever was written, the declaration carries the name as `NativeSymbol`,
            // because a pass that reads the declaration without the emitter's tables
            // reads it there - `linear`'s `listOf<T>` list literal is the one that does
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
        } else if (!hasBody && !isNative) {
            this.setError(pos, "a body-less method needs 'native' or an attribute")
            return this.emptyNode()
        }

        var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        attrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, declName))
        attrs.append(AstNodeAttribute(AstNodeAttributeKind.IsNative, boolText(isNative)))
        attrs.append(AstNodeAttribute(AstNodeAttributeKind.HasBody, boolText(hasBody)))
        attrs.append(AstNodeAttribute(AstNodeAttributeKind.HasReceiver, boolText(hasReceiver)))
        attrs.append(AstNodeAttribute(AstNodeAttributeKind.HasNativeSymbol, boolText(hasNativeSymbol)))
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

    // ---- types ------------------------------------------------------------

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
        // `..T`: the function's body yields `T`, so it is lowered to a state machine
        // whose element type is `T` (impl_specs/yield.md).
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
            this.advance() // (
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
            if (this.checkText("<")) {
                val typeArgs: List<AstXmlNode> = this.parseGenericArgs()
                if (this.failed) {
                    return this.emptyNode()
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
            // A `>>` the argument's own list left over ends *this* list too: the closer is
            // pending, not a token, and what follows the type is the caller's again.
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

    // ---- statements -------------------------------------------------------

    fun parseBlock(): List<AstXmlNode> {
        var body: List<AstXmlNode> = List<AstXmlNode>()
        // A declaration's body brace may stand on its own line (`data class X(...)` then
        // `{`): a newline before a `{` where a block is the only thing that can follow is
        // not a statement boundary. Kotlin's rule, and why a Kotlin-kind formatter can be
        // pointed at these files.
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

    // One source statement, appended to `out`. A statement *may* expand to more than
    // one: `for` is a declaration plus the loop it runs (`parseFor`), and the
    // declaration has to sit outside the loop. Matches `Parser::parseStmtInto`.
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
                // A step's value is the assignment's, so there is nothing for a *prefix* one
                // to hand back: it has to stand on its own as a statement.
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
            // `i++` / `i--`: the step forms, which are the compound assignment above with a
            // `1` (specs/memory-model.md). A step reached anywhere else - inside an
            // expression, or in the prefix position a statement starts with - is the
            // diagnostic above, because the statement's value is what it would hand back.
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
        this.advance() // if
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
        this.advance() // while
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

    // `subj == a || subj == b`: one condition for an arm, so an arm with several
    // labels emits its body once and any expression is a legal label.
    fun whenCondition(subject: *Str, labels: *List<AstXmlNode>, pos: SourcePos): ExprNode {
        var cond: ExprNode = this.binaryExprAt(
            "==", this.nameExprAt(subject, pos),
            ExprNode(labels[0], pos.line, pos.column), pos
        )
        var i: Int = 1
        while (i < labels.size()) {
            val equals: ExprNode = this.binaryExprAt(
                "==", this.nameExprAt(subject, pos),
                ExprNode(labels[i], pos.line, pos.column), pos
            )
            cond = this.binaryExprAt("||", cond, equals, pos)
            i = i + 1
        }
        return cond
    }

    // `when` (specs/functions.md): the language's selection statement, in Kotlin's
    // spelling and with Kotlin's semantics, desugared right here to the `if`/`else`
    // chain it means - so nothing downstream knows what a `when` is:
    //
    //   when (kind) {
    //       Kind.A, Kind.B -> { body1 }
    //       Kind.C -> { body2 }
    //       else -> { body3 }
    //   }
    //     ->
    //   var _sm_when1 = kind
    //   if (_sm_when1 == Kind.A || _sm_when1 == Kind.B) { body1 }
    //   else if (_sm_when1 == Kind.C) { body2 }
    //   else { body3 }
    //
    // The subject is bound to a name of the template's own (as `for` binds the
    // machine): the source evaluates it once, so the chain must too. The arm bodies
    // are blocks, `else` is the last arm, and arms do not fall through - which is what
    // makes a `break`/`continue` inside an arm the enclosing loop's, as in Kotlin.
    // Matches `Parser::parseWhen`.
    fun parseWhen(out: *List<AstXmlNode>): Bool {
        val pos: SourcePos = this.peek(0).pos
        this.advance() // when
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

        // The template's own name for the subject, before the arms are parsed: a nested
        // `for`/`when` in an arm body takes the next id (both rings number the same way).
        val subjectName: Str = "_sm_when" + this.nextTemplateId.toString()
        this.nextTemplateId = this.nextTemplateId + 1

        // One `if` per arm, in source order, plus the `else` arm's statements as the
        // chain's tail. The nodes are linked afterwards, because `else if` is an `if`
        // in the previous arm's else body.
        var arms: List<AstXmlNode> = List<AstXmlNode>()
        var tail: List<AstXmlNode> = List<AstXmlNode>()
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
            // `is`/`in`/a range are Kotlin's pattern labels; the language has `==`
            // against a value and that is all `when` matches on today.
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
            arms.append(this.ifNode(this.whenCondition(subjectName, labels, armPos), body, armPos))
            this.skipSeparators()
        }
        if (!this.expectText("}")) {
            return false
        }

        // The chain is right-nested: each arm's else body holds the next `if`, and the
        // `else` arm's statements are the last arm's else body. The tail goes on first:
        // linking copies an arm into its predecessor's else body (nodes are values), so
        // an arm has to be complete before it is copied.
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
        out.append(this.varDeclNode(subjectName, true, this.emptyNode(), subject, pos))
        if (arms.size() > 0) {
            out.append(arms[0])
        } else {
            // `else` was the only arm: its statements are the whole construct.
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
        this.advance() // return
        var value: ExprNode = this.emptyExpr()
        if (!this.atStmtEnd()) {
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

    // `yield e`: the value the state machine hands out (impl_specs/yield.md). A
    // statement like `return`, and the state-machine pass replaces it - nothing
    // downstream has to know what a yield is. Matches `Parser::parseYield`.
    fun parseYield(): AstXmlNode {
        val pos: SourcePos = this.peek(0).pos
        this.advance() // yield
        val value: ExprNode = this.parseExpr(0)
        if (this.failed) {
            return this.emptyNode()
        }
        var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
        var node: AstXmlNode = AstXmlNode(AstNodeKind.Stmt, AstNodeCategory.StmtYield, attrs, Array<AstXmlNode>())
        this.attach(node, AstNodeKind.Value, value.node)
        return node
    }

    // ---- the `for` desugaring ---------------------------------------------
    //
    // The builders below assemble the pieces the template needs. Every node they make
    // carries the `for` token's position, so a diagnostic points at the line the user
    // wrote; `Parser::parseFor` in the C++ ring builds the same shape the same way.

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

    // `<target>.<wrap>()`: the wrap the `for` forms put around what they iterate
    // (`smToYield`, or `smToYieldPtr` for the `*v` form).
    fun smToYieldCall(target: *ExprNode, pos: SourcePos, wrap: *Str): ExprNode {
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

    // `<target>.<method>()`: the machine's `next`, `value`, `hasValue`.
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

    // `for` (specs/functions.md). Two forms, and each of them in two flavours - binding
    // each element, or binding a *pointer* to it (`for (*v in c)`, `for ((*v, i) in c)`) -
    // all iterating a *state machine* (`..T`, what a `yield`ing function produces).
    // Every form is lowered right here to the `while` it means - so no stage downstream
    // sees a `for`, and `break`/`continue` inside one are the `while`'s own:
    //
    //   for (v in m) { body }          var _sm_for1 = m
    //                                  while (_sm_for1.advance()) {
    //                                      val v = _sm_for1.value()
    //                                      body
    //                                  }
    //
    //   for ((v, i) in m) { body }     the same, plus `var _sm_index1: Int = -1` before
    //                                  the loop, the pre-increment as the body's first
    //                                  statement, and `val i = _sm_index1` after `v`.
    //
    // The machine holds what it yielded (`current`), so the loop variable is one read from
    // it: the protocol builds nothing per element - no `Opt` to construct, ask
    // `hasValue()` of and unwrap (`impl_specs/for.md`).
    //
    // The index is pre-incremented as the body's *first* statement rather than in the
    // loop's condition: `continue` jumps to the condition, which is the machine's own
    // `advance()` and so is evaluated again (the machine does move along), but an index
    // incremented at the end of the body would miss that iteration. `-1` is what makes the
    // pre-increment hand out 0 first. The names come from a per-file counter, so nested
    // loops never collide and two runs produce the same output.
    //
    // `*v` differs only in the wrap: the machine's element is then `*T`, so `v` is the
    // element's *place* rather than a copy of it (the prelude's `smToYieldPtr`).
    fun parseFor(out: *List<AstXmlNode>): Bool {
        val pos: SourcePos = this.peek(0).pos
        this.advance() // for
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

        // The iterated expression is wrapped in the invisible `smToYield()` call: a
        // `for` iterates whatever has one, so a container walks itself in order and a
        // machine passes through (impl_specs/for.md). It is a *member* call, because
        // that is what binds the function's type parameter from the receiver - a plain
        // `smToYield(x)` would leave the loop variable untyped.
        var wrap: Str = "smToYield"
        if (valueIsPointer) {
            wrap = "smToYieldPtr"
        }
        val iterated: ExprNode = this.smToYieldCall(machine, pos, wrap)
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
        val stepValue: ExprNode = this.methodCallAt(machineName, "value", pos)
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

    // ---- expressions (Pratt) ----------------------------------------------

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
            var attrs: List<AstNodeAttribute> = this.posAttrs(left.line, left.column)
            attrs.append(AstNodeAttribute(AstNodeAttributeKind.Op, op))
            var node: AstXmlNode = AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprBinary, attrs, Array<AstXmlNode>())
            this.attach(node, AstNodeKind.Lhs, left.node)
            this.attach(node, AstNodeKind.Rhs, right.node)
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
        // One list for the whole walk, cleared per call: the arguments of the call being
        // parsed (they are copied into the node below), not one list per iteration.
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
                var attrs: List<AstNodeAttribute> = this.posAttrs(expr.line, expr.column)
                attrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
                var node: AstXmlNode =
                    AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprMember, attrs, Array<AstXmlNode>())
                this.attach(node, AstNodeKind.Receiver, expr.node)
                expr = ExprNode(node, expr.line, expr.column)
            } else {
                break
            }
        }
        return expr
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
            val text: Str = this.advance().text
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
                this.advance() // copy
                this.advance() // (
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
                this.advance() // name
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
            this.advance() // (
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
        this.advance() // )
        this.advance() // ->

        var body: List<AstXmlNode> = List<AstXmlNode>()
        if (this.checkText("{")) {
            body = this.parseBlock()
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
        attrs.append(AstNodeAttribute(AstNodeAttributeKind.Params, joinNames(names)))
        var node: AstXmlNode = AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprLambda, attrs, Array<AstXmlNode>())
        xmlAddChildren(node, paramTypes)
        xmlAddChild(node, this.container(AstNodeKind.Body, body))
        return ExprNode(node, pos.line, pos.column)
    }
}

// ---- helpers --------------------------------------------------------------

// The value of one attribute argument (specs/attributes.md): a string literal without
// its quotes, or an integer literal as written.
fun attrLiteralText(text: Str): Str {
    if (text.size() >= 2 && text.substr(0, 1) == "\"" && text.substr(text.size() - 1, 1) == "\"") {
        return text.substr(1, text.size() - 2)
    }
    return text
}

fun boolText(value: Bool): Str {
    if (value) {
        return "true"
    }
    return "false"
}

// The names are read only; a `*List<Str>` avoids copying the caller's list.
fun joinNames(names: *List<Str>): Str {
    var out: Str = Str()
    var i: Int = 0
    while (i < names.size()) {
        if (i > 0) {
            out = out + ","
        }
        out = out + names[i]
        i = i + 1
    }
    return out
}

// Binding powers for the Pratt expression parser. Left-associative (the
// recursive call uses bp + 1).
fun binaryBindingPower(op: *Str): Int {
    when (op) {
        "||" -> {
            return 10
        }

        "&&" -> {
            return 20
        }

        "==", "!=" -> {
            return 30
        }

        "<", ">", "<=", ">=" -> {
            return 40
        }

        // The bitwise operators, in Python's and Rust's order: tighter than a comparison and
        // looser than the shifts, so `a & b == c` is `(a & b) == c` and a bit test needs no
        // parentheses. (C puts them the other way round, where it silently means
        // `a & (b == c)` - the trap this order exists to avoid; Python's `&` beats `==` too.)
        "|" -> {
            return 43
        }

        "^" -> {
            return 44
        }

        "&" -> {
            return 45
        }

        "<<", ">>" -> {
            return 47
        }

        "+", "-" -> {
            return 50
        }

        "*", "/", "%" -> {
            return 60
        }
    }
    return -1
}

fun isAssignOp(op: *Str): Bool {
    return op == "=" || op == "+=" || op == "-="
            || op == "*=" || op == "/=" || op == "%="
            || op == "&=" || op == "|=" || op == "^="
            || op == "<<=" || op == ">>="
}

// The step operators: `i++` and `i--`, which are statements rather than values.
fun isStepOp(op: *Str): Bool {
    return op == "++" || op == "--"
}

// The compound assignment a step is: `i++` is `i += 1`, `i--` is `i -= 1`.
fun stepAssignOp(op: *Str): Str {
    if (op == "++") {
        return "+="
    }
    return "-="
}

// ---- entry points ---------------------------------------------------------

// Parses a pre-filtered token cursor (with a synthetic Eof already appended).
fun parseModule(cursor: Span<Token>, fileName: *Str): Res<AstXmlNode> {
    var parser: Parser = Parser(cursor, false, "", fileName, 1, 0)
    val root: AstXmlNode = parser.parseRoot()
    if (parser.failed) {
        return Res<AstXmlNode>.err(parser.error)
    }
    return Res<AstXmlNode>.ok(root)
}

// Parses a raw token list: drops Space/Comment tokens and appends a synthetic
// Eof, matching the C++ parser's constructor. A newline inside `(...)` or `[...]`
// separates nothing either: a condition or an argument list may be wrapped, and a
// formatter is free to do it (Kotlin's rule, and the reason a Kotlin-kind formatter can
// be pointed at these files, specs/declarations.md).
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
