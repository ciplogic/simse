// SemaAnalyze.kt
//
// `Analyzer`'s walk: declarations, statements, expressions and the call checks. Extension
// methods on `Analyzer` (Sema.kt); SemaCollect.kt has the collection and scopes.

package sema

import compiler

import parser
import common

fun Analyzer.analyzeDecl(decl: *AstXmlNode): Unit {
    val kind: AstNodeCategory = xmlKind(decl)
    when (kind) {
        AstNodeCategory.Var -> {
            // Static storage: the initializer may name any hoisted declaration,
            // including another static (specs/statics.md; init order unspecified).
            val staticType: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.Type)
            if (!xmlIsEmpty(staticType)) {
                this.resolveType(staticType)
                this.checkUninitHolder(staticType, xmlLine(decl), xmlColumn(decl))
            }
            val init: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.Init)
            if (!xmlIsEmpty(init)) {
                this.analyzeExpr(init)
            }
            return
        }

        AstNodeCategory.DataClass -> {
            this.pushTypeScope()
            val typeParams: List<Str> = xmlTypeParamNames(decl)
            var i: Int = 0
            while (i < typeParams.size()) {
                this.declareType(typeParams[i])
                i = i + 1
            }
            this.pushScope()
            this.declareValue("this", true, false, xmlEmptyNode())
            val fields: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Field)
            for (*field in fields) {
                val fieldType: *AstXmlNode = xmlChildPtr(field, AstNodeKind.Type)
                if (!xmlIsEmpty(fieldType)) {
                    this.resolveType(fieldType)
                    this.checkUninitHolder(fieldType, xmlLine(field), xmlColumn(field))
                }
            }
            val methods: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Function)
            for (*method in methods) {
                this.analyzeFunction(method)
            }
            this.popScope()
            this.popTypeScope()
            return
        }

        AstNodeCategory.Enum -> {
            return
        }

        AstNodeCategory.TypeAlias -> {
            this.pushTypeScope()
            val typeParams: List<Str> = xmlTypeParamNames(decl)
            var i: Int = 0
            while (i < typeParams.size()) {
                this.declareType(typeParams[i])
                i = i + 1
            }
            val target: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.TargetType)
            if (!xmlIsEmpty(target)) {
                this.resolveType(target)
            }
            this.popTypeScope()
            return
        }

        AstNodeCategory.Function -> {
            this.analyzeFunction(decl)
            return
        }
    }
}

fun Analyzer.analyzeFunction(decl: *AstXmlNode): Unit {
    this.pushTypeScope()
    val typeParams: List<Str> = xmlTypeParamNames(decl)
    var i: Int = 0
    while (i < typeParams.size()) {
        this.declareType(typeParams[i])
        i = i + 1
    }
    this.pushScope()
    this.declareValue("this", true, false, xmlEmptyNode())
    if (xmlAttr(decl, AstNodeAttributeKind.HasReceiver) == "true") {
        val receiver: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.Receiver)
        if (!xmlIsEmpty(receiver)) {
            this.resolveType(receiver)
        }
    }
    val params: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Param)
    for (*param in params) {
        val paramType: *AstXmlNode = xmlChildPtr(param, AstNodeKind.Type)
        // The receiver is emitted as a pointer (`T* self`), never a copy, so a type with
        // an `unInit` may be one (=`fun T.f` / `this: T`); only a held *value* needs the
        // `*T`/`&T` handle.
        val isReceiver: Bool = xmlAttr(param, AstNodeAttributeKind.Name) == "this"
        if (!xmlIsEmpty(paramType)) {
            this.resolveType(paramType)
            if (!isReceiver) {
                this.checkUninitHolder(paramType, xmlLine(param), xmlColumn(param))
            }
        }
        // Parameters are not `val` declarations, so reassigning one is never reported.
        this.declareValue(xmlAttr(param, AstNodeAttributeKind.Name), true, false, paramType)
    }
    val returnType: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.ReturnType)
    if (!xmlIsEmpty(returnType)) {
        this.resolveType(returnType)
        this.checkUninitHolder(returnType, xmlLine(decl), xmlColumn(decl))
    }

    val savedLoopDepth: Int = this.loopDepth
    this.loopDepth = 0
    val body: List<AstXmlNode> = xmlChildren(xmlChildPtr(decl, AstNodeKind.Body), AstNodeKind.Stmt)
    for (*stmtNode in body) {
        this.analyzeStmt(stmtNode)
    }
    this.promoteForLoops(*body)
    this.loopDepth = savedLoopDepth

    this.popScope()
    this.popTypeScope()
}

fun Analyzer.analyzeStmt(stmt: *AstXmlNode): Unit {
    val kind: AstNodeCategory = xmlKind(stmt)
    when (kind) {
        AstNodeCategory.StmtVarDecl -> {
            val init: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Init)
            if (!xmlIsEmpty(init)) {
                this.analyzeExpr(init)
            }
            val declaredType: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Type)
            if (!xmlIsEmpty(declaredType)) {
                this.resolveType(declaredType)
                this.checkUninitHolder(declaredType, xmlLine(stmt), xmlColumn(stmt))
            }
            var type: AstXmlNode = declaredType
            if (xmlIsEmpty(type) && !xmlIsEmpty(init)) {
                type = this.exprType(init)
            }
            this.checkForIterable(stmt)
            this.declareValue(
                xmlAttr(stmt, AstNodeAttributeKind.Name),
                xmlAttr(stmt, AstNodeAttributeKind.IsVar) == "true",
                true,
                type
            )
            return
        }

        AstNodeCategory.StmtAssign -> {
            val target: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Target)
            if (!xmlIsEmpty(target)) {
                this.analyzeExpr(target)
            }
            val value: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Value)
            if (!xmlIsEmpty(value)) {
                this.analyzeExpr(value)
            }
            if (!xmlIsEmpty(target) && xmlKind(target) == AstNodeCategory.ExprName) {
                val targetName: Str = xmlAttr(target, AstNodeAttributeKind.Name)
                val binding: Opt<ValueBinding> = this.lookupValue(targetName)
                if (binding.hasValue() && binding.value().checkAssign && !binding.value().isMutable) {
                    this.diag(
                        xmlLine(target), xmlColumn(target),
                        `cannot assign to val '@targetName'`
                    )
                }
            }
            return
        }

        AstNodeCategory.StmtIf -> {
            val cond: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Cond)
            if (!xmlIsEmpty(cond)) {
                this.analyzeExpr(cond)
            }
            this.pushScope()
            val thenBody: List<AstXmlNode> = xmlChildren(xmlChildPtr(stmt, AstNodeKind.Then), AstNodeKind.Stmt)
            for (*thenStmt in thenBody) {
                this.analyzeStmt(thenStmt)
            }
            this.popScope()
            val elseBlock: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Else)
            if (!xmlIsEmpty(elseBlock)) {
                this.pushScope()
                val elseBody: List<AstXmlNode> = xmlChildren(elseBlock, AstNodeKind.Stmt)
                for (*elseStmt in elseBody) {
                    this.analyzeStmt(elseStmt)
                }
                this.popScope()
            }
            return
        }

        AstNodeCategory.StmtWhile -> {
            val cond: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Cond)
            if (!xmlIsEmpty(cond)) {
                this.analyzeExpr(cond)
            }
            this.pushScope()
            this.loopDepth = this.loopDepth + 1
            val body: List<AstXmlNode> = xmlChildren(xmlChildPtr(stmt, AstNodeKind.Body), AstNodeKind.Stmt)
            for (*bodyStmt in body) {
                this.analyzeStmt(bodyStmt)
            }
            this.loopDepth = this.loopDepth - 1
            this.popScope()
            return
        }

        AstNodeCategory.StmtReturn -> {
            val value: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Value)
            if (!xmlIsEmpty(value)) {
                this.analyzeExpr(value)
            }
            return
        }

        AstNodeCategory.StmtBreak -> {
            if (this.loopDepth == 0) {
                this.diag(xmlLine(stmt), xmlColumn(stmt), "'break' outside a loop")
            }
            return
        }

        AstNodeCategory.StmtContinue -> {
            if (this.loopDepth == 0) {
                this.diag(xmlLine(stmt), xmlColumn(stmt), "'continue' outside a loop")
            }
            return
        }

        AstNodeCategory.StmtExprStmt -> {
            val expr: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Expr)
            if (!xmlIsEmpty(expr)) {
                this.analyzeExpr(expr)
            }
            return
        }
    }
}

fun Analyzer.analyzeExpr(expr: *AstXmlNode): Unit {
    val kind: AstNodeCategory = xmlKind(expr)
    when (kind) {
        AstNodeCategory.ExprIntLit, AstNodeCategory.ExprFloatLit, AstNodeCategory.ExprStrLit, AstNodeCategory.ExprCharLit,
        AstNodeCategory.ExprBoolLit, AstNodeCategory.ExprNullLit, AstNodeCategory.ExprName -> {
            return
        }

        AstNodeCategory.ExprGenericName -> {
            this.checkGenericNameArity(expr)
            val args: List<AstXmlNode> = xmlChildren(expr, AstNodeKind.TypeArg)
            for (*arg in args) {
                this.resolveType(arg)
            }
            return
        }

        AstNodeCategory.ExprMember -> {
            val receiver: *AstXmlNode = xmlChildPtr(expr, AstNodeKind.Receiver)
            if (!xmlIsEmpty(receiver)) {
                this.analyzeExpr(receiver)
            }
            return
        }

        AstNodeCategory.ExprCall -> {
            val callee: *AstXmlNode = xmlChildPtr(expr, AstNodeKind.Callee)
            if (!xmlIsEmpty(callee)) {
                this.analyzeExpr(callee)
            }
            val args: List<AstXmlNode> = xmlChildren(expr, AstNodeKind.Arg)
            for (*arg in args) {
                this.analyzeExpr(arg)
            }
            this.checkCallArity(expr)
            this.checkExtensionCallArity(expr)
            this.checkUninitCall(expr, callee)
            return
        }

        AstNodeCategory.ExprIndex -> {
            val receiver: *AstXmlNode = xmlChildPtr(expr, AstNodeKind.Receiver)
            if (!xmlIsEmpty(receiver)) {
                this.analyzeExpr(receiver)
            }
            val index: *AstXmlNode = xmlChildPtr(expr, AstNodeKind.Index)
            if (!xmlIsEmpty(index)) {
                this.analyzeExpr(index)
            }
            return
        }

        AstNodeCategory.ExprUnary, AstNodeCategory.ExprRef, AstNodeCategory.ExprDeref, AstNodeCategory.ExprCopy -> {
            val operand: *AstXmlNode = xmlChildPtr(expr, AstNodeKind.Operand)
            if (!xmlIsEmpty(operand)) {
                this.analyzeExpr(operand)
            }
            return
        }

        AstNodeCategory.ExprBinary -> {
            val lhs: *AstXmlNode = xmlChildPtr(expr, AstNodeKind.Lhs)
            if (!xmlIsEmpty(lhs)) {
                this.analyzeExpr(lhs)
            }
            val rhs: *AstXmlNode = xmlChildPtr(expr, AstNodeKind.Rhs)
            if (!xmlIsEmpty(rhs)) {
                this.analyzeExpr(rhs)
            }
            return
        }

        AstNodeCategory.ExprLambda -> {
            // The enclosing receiver is not captured (reference captures are deferred), and a
            // lambda's C++ receiver is the closure itself: `this` inside it would silently
            // name the wrong object, so report it here.
            val bodyContainer: *AstXmlNode = xmlChildPtr(expr, AstNodeKind.Body)
            var thisAt: AstXmlNode = xmlEmptyNode()
            var b: Int = 0
            while (b < bodyContainer.Children.count()) {
                if (xmlIsEmpty(thisAt)) {
                    thisAt = semLambdaThisNode(bodyContainer.Children[b])
                }
                b = b + 1
            }
            if (!xmlIsEmpty(thisAt)) {
                this.diag(
                    xmlLine(thisAt), xmlColumn(thisAt),
                    "a lambda cannot reach `this` yet: a lambda captures by value, and reference captures are deferred"
                )
            }
            this.pushScope()
            val names: List<Str> = xmlLambdaParams(expr)
            val paramTypes: List<AstXmlNode> = xmlChildren(expr, AstNodeKind.ParamType)
            var i: Int = 0
            while (i < names.size()) {
                var type: AstXmlNode = xmlEmptyNode()
                if (paramTypes.size() == names.size()) {
                    type = paramTypes[i]
                }
                if (!xmlIsEmpty(type)) {
                    this.resolveType(type)
                }
                this.declareValue(names[i], true, false, type)
                i = i + 1
            }
            if (paramTypes.size() != names.size()) {
                for (*paramType in paramTypes) {
                    this.resolveType(paramType)
                }
            }
            val savedLoopDepth: Int = this.loopDepth
            this.loopDepth = 0
            val body: List<AstXmlNode> = xmlChildren(xmlChildPtr(expr, AstNodeKind.Body), AstNodeKind.Stmt)
            for (*stmtNode in body) {
                this.analyzeStmt(stmtNode)
            }
            this.promoteForLoops(*body)
            this.loopDepth = savedLoopDepth
            this.popScope()
            return
        }
    }
}

// The first `this` a lambda's own body names, as the node to position the diagnostic at. A
// nested lambda is its own body and reports its own; empty when there is none.
fun semLambdaThisNode(node: *AstXmlNode): AstXmlNode {
    if (xmlKind(node) == AstNodeCategory.ExprLambda) {
        return xmlEmptyNode()
    }
    if (xmlKind(node) == AstNodeCategory.ExprName
        && xmlAttr(node, AstNodeAttributeKind.Name) == "this"
    ) {
        return node
    }
    for (*child in node.Children) {
        val found: AstXmlNode = semLambdaThisNode(child)
        if (!xmlIsEmpty(found)) {
            return found
        }
    }
    return xmlEmptyNode()
}

fun Analyzer.checkGenericNameArity(expr: *AstXmlNode): Unit {
    val argCount: Int = xmlCount(expr, AstNodeKind.TypeArg)
    val name: Str = xmlAttr(expr, AstNodeAttributeKind.Name)
    val overloads: *List<AstXmlNode> = this.functions.getPtr(name)
    if (overloads != null) {
        for (*overload in overloads) {
            if (xmlCount(overload, AstNodeKind.TypeParam) == argCount) {
                return
            }
        }
        this.diag(
            xmlLine(expr), xmlColumn(expr),
            `no overload of '@name' takes @argCount type argument(s)`
        )
        return
    }
    this.checkInstantiationArity(name, argCount, xmlLine(expr), xmlColumn(expr))
}

// A raw pointer cannot become a counted reference in place: `&x` shares a box, while
// `*T` points into somebody's storage. Every other handle conversion is inferred
// (`convertArgument`, `specs/functions.md`).
fun Analyzer.checkHandleArgument(callee: *Str, function: *AstXmlNode, index: Int, arg: *AstXmlNode): Unit {
    val params: List<AstXmlNode> = xmlChildren(function, AstNodeKind.Param)
    if (index >= params.size()) {
        return
    }
    val param: *AstXmlNode = xmlChildPtr(params[index], AstNodeKind.Type)
    if (xmlIsEmpty(param)) {
        return
    }
    if (xmlKind(param) == AstNodeCategory.TypePointer) {
        return // a borrow takes anything
    }
    if (!semaIsHandleType(param)) {
        return // a by-value parameter reads through
    }
    val actual: AstXmlNode = this.exprType(arg)
    if (xmlIsEmpty(actual) || xmlKind(actual) != AstNodeCategory.TypePointer) {
        return
    }
    val pointee: AstXmlNode = semPointeeOf(param)
    var pointeeText: Str = "T"
    if (!xmlIsEmpty(pointee)) {
        pointeeText = semaTypeText(pointee)
    }
    this.diag(
        xmlLine(arg),
        xmlColumn(arg),
        `'@callee' takes a counted reference ('&@pointeeText') and the argument is a raw pointer: a pointer cannot become a reference in place - make a reference variable one line before the call (var ref: &@pointeeText = &value)`
    )
}
