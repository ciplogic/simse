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
            if (xmlAttr(decl, AstNodeAttributeKind.IsUnionClass) == "true") {
                this.checkUnionDecl(decl)
            }
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
            // A method's `this` is an instance of *this* class, so `this.field` resolves.
            val savedClassType: AstXmlNode = this.classType
            this.classType = semNamedType(xmlAttr(decl, AstNodeAttributeKind.Name))
            for (*method in methods) {
                this.analyzeFunction(method)
            }
            this.classType = savedClassType
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

// The declaration-time `union class` rules (`specs/declarations.md`): no generic form yet,
// no two fields of one type (the arm constructor could not tell them apart - distinct types,
// two different enums included, are fine), and no user method that collides with a generated
// name. The construction rules are `checkUnionConstruction` (SemaCall.kt).
fun Analyzer.checkUnionDecl(decl: *AstXmlNode): Unit {
    val name: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
    if (xmlTypeParamNames(decl).size() > 0) {
        this.diag(
            xmlLine(decl), xmlColumn(decl),
            `unsupported: a generic union class ('@name')`
        )
    }
    val fields: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Field)
    for (*field in fields) {
        val fieldName: Str = xmlAttr(field, AstNodeAttributeKind.Name)
        if (fieldName == "None") {
            this.diag(
                xmlLine(field), xmlColumn(field),
                `union class '@name': 'None' is the tag's empty state; rename the field`
            )
        }
        if (fieldName == "_type") {
            this.diag(
                xmlLine(field), xmlColumn(field),
                `union class '@name': '_type' is the tag's storage; rename the field`
            )
        }
        if (fieldName == "destroyActive" || fieldName == "copyFrom" || fieldName == "moveFrom") {
            this.diag(
                xmlLine(field), xmlColumn(field),
                `union class '@name': '@fieldName' is a generated member; rename the field`
            )
        }
    }
    var i: Int = 0
    while (i < fields.size()) {
        val left: AstXmlNode = this.unionResolveAlias(xmlChildPtr(fields[i], AstNodeKind.Type))
        var j: Int = i + 1
        while (j < fields.size()) {
            val right: AstXmlNode = this.unionResolveAlias(xmlChildPtr(fields[j], AstNodeKind.Type))
            if (semaSameType(left, right)) {
                val leftName: Str = xmlAttr(fields[i], AstNodeAttributeKind.Name)
                val rightName: Str = xmlAttr(fields[j], AstNodeAttributeKind.Name)
                this.diag(
                    xmlLine(fields[j]), xmlColumn(fields[j]),
                    `union class '@name': fields '@leftName' and '@rightName' have the same type`
                )
            }
            j = j + 1
        }
        i = i + 1
    }
    for (*method in xmlChildren(decl, AstNodeKind.Function)) {
        if (xmlAttr(method, AstNodeAttributeKind.IsUnionGenerated) == "true") {
            continue
        }
        val methodName: Str = xmlAttr(method, AstNodeAttributeKind.Name)
        if (unionGeneratedName(methodName, fields)) {
            this.diag(
                xmlLine(method), xmlColumn(method),
                `union class '@name' generates '@methodName': rename the method`
            )
        }
    }
}

// Whether `methodName` is one a `union class` generates: the fixed tag surface, the
// managed form's members, or a field's `get<Field>`/`set<Field>` arm.
fun unionGeneratedName(methodName: *Str, fields: *List<AstXmlNode>): Bool {
    if (methodName == "getTypeOf" || methodName == "isOfType" || methodName == "setNone"
        || methodName == "initByValue" || methodName == "destroyActive"
        || methodName == "copyFrom" || methodName == "moveFrom"
    ) {
        return true
    }
    for (*field in fields) {
        val suffix: Str = upperFirst(xmlAttr(field, AstNodeAttributeKind.Name))
        if (methodName == "get" + suffix || methodName == "set" + suffix) {
            return true
        }
    }
    return false
}

// A comparison against a union class is a **tag comparison**: `when (u)`'s arms, which the
// parser has already desugared into `u == <label>`, and a hand-written `u == A` alike. The
// class's generated `==`/`!=` operators compare it with its tag enum, so the comparison
// needs no rewriting at all - except that a bare arm name (`A`, `None`) has to be spelled
// as the tag member `SmUTypes.A`, which only the checker can qualify. A name bound as a
// local, parameter or static stays the user's own expression (shadowing), and an unbound
// name that is no arm is reported rather than left to fail in C++.
fun Analyzer.expandUnionTagTest(expr: *AstXmlNode, lhs: *AstXmlNode, rhs: *AstXmlNode): Unit {
    val unionDecl: AstXmlNode = this.unionDeclOf(lhs)
    if (xmlIsEmpty(unionDecl)) {
        return
    }
    if (xmlKind(rhs) != AstNodeCategory.ExprName) {
        // A qualified tag member (`SmUTypes.A`) is already an enum value; the operator takes
        // it from there.
        return
    }
    val member: Str = xmlAttr(rhs, AstNodeAttributeKind.Name)
    if (this.lookupValue(member).hasValue()) {
        return
    }
    if (!unionHasArm(unionDecl, member)) {
        val unionName: Str = xmlAttr(unionDecl, AstNodeAttributeKind.Name)
        val arms: Str = unionArmList(unionDecl)
        this.diag(
            xmlLine(rhs), xmlColumn(rhs),
            `union class '@unionName' has no arm '@member'; the arms are @arms`
        )
        return
    }
    val tagName: Str = unionTagName(xmlAttr(unionDecl, AstNodeAttributeKind.Name))
    replaceRoleChild(expr, AstNodeKind.Rhs, unionEnumMemberAccess(tagName, member, rhs))
}

// The union class `expr` is a value of, or empty: through handles, pointers and aliases, the
// same peeling a member call does.
fun Analyzer.unionDeclOf(expr: *AstXmlNode): AstXmlNode {
    val type: AstXmlNode = this.exprType(expr)
    if (xmlIsEmpty(type)) {
        return xmlEmptyNode()
    }
    val outer: AstXmlNode = this.semaReceiverOuter(type)
    if (xmlKind(outer) != AstNodeCategory.TypeNamed) {
        return xmlEmptyNode()
    }
    val decl: *AstXmlNode = this.types.getPtr(xmlAttr(outer, AstNodeAttributeKind.Name))
    if (decl == null || decl.name != AstNodeKind.DataClass
        || xmlAttr(decl, AstNodeAttributeKind.IsUnionClass) != "true"
    ) {
        return xmlEmptyNode()
    }
    return *decl
}

// Whether `member` names an arm: a field, or the tag's `None`.
fun unionHasArm(decl: *AstXmlNode, member: *Str): Bool {
    if (member == "None") {
        return true
    }
    for (*field in xmlChildren(decl, AstNodeKind.Field)) {
        if (xmlAttr(field, AstNodeAttributeKind.Name) == member) {
            return true
        }
    }
    return false
}

// The arms as `a == b` lists them in the "no arm" diagnostic: `None` first, then the fields.
fun unionArmList(decl: *AstXmlNode): Str {
    var arms: List<Str> = List<Str>()
    arms.append("None")
    for (*field in xmlChildren(decl, AstNodeKind.Field)) {
        arms.append(xmlAttr(field, AstNodeAttributeKind.Name))
    }
    return joinStrs(arms, ", ")
}

// One child of `node` replaced in place; the role keeps its position, so the tree shape the
// later stages walk is the parsed one. The replacement takes the role it is placed in
// (`Rhs`, `Lhs`), exactly as the parser's `attach` does.
fun replaceRoleChild(node: *AstXmlNode, role: AstNodeKind, child: AstXmlNode): Unit {
    var placed: AstXmlNode = child
    placed.name = role
    var replaced: Bool = false
    var kids: List<AstXmlNode> = List<AstXmlNode>()
    for (*existing in node.Children) {
        if (!replaced && existing.name == role) {
            kids.append(placed)
            replaced = true
        } else {
            kids.append(existing)
        }
    }
    if (!replaced) {
        kids.append(placed)
    }
    node.Children = kids.toArray()
}

// `SmUTypes.<member>`, at `at`'s position: the shape a parsed `Enum.Member` has.
fun unionEnumMemberAccess(tagName: *Str, member: *Str, at: *AstXmlNode): AstXmlNode {
    var recvAttrs: List<AstNodeAttribute> = listOf<AstNodeAttribute>(
        AstNodeAttribute(AstNodeAttributeKind.Name, tagName),
        AstNodeAttribute(AstNodeAttributeKind.Line, xmlLine(at).toString()),
        AstNodeAttribute(AstNodeAttributeKind.Column, xmlColumn(at).toString())
    )
    var recv: AstXmlNode = AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprName, recvAttrs, Array<AstXmlNode>())
    recv.name = AstNodeKind.Receiver
    var memberAttrs: List<AstNodeAttribute> = listOf<AstNodeAttribute>(
        AstNodeAttribute(AstNodeAttributeKind.Name, member),
        AstNodeAttribute(AstNodeAttributeKind.Line, xmlLine(at).toString()),
        AstNodeAttribute(AstNodeAttributeKind.Column, xmlColumn(at).toString())
    )
    var access: AstXmlNode =
        AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprMember, memberAttrs, Array<AstXmlNode>())
    xmlAddChild(access, recv)
    return access
}

// `<lhs>.getTypeOf()`, for a comparison that named a qualified tag member.
fun Analyzer.analyzeFunction(decl: *AstXmlNode): Unit {
    this.pushTypeScope()
    val typeParams: List<Str> = xmlTypeParamNames(decl)
    var i: Int = 0
    while (i < typeParams.size()) {
        this.declareType(typeParams[i])
        i = i + 1
    }
    this.pushScope()
    // `this` is the receiver for an extension function, and the enclosing class's instance
    // for a method - so a `for` over `this.field` has a type to resolve (`iteratedType`).
    var thisType: AstXmlNode = this.classType
    if (xmlAttr(decl, AstNodeAttributeKind.HasReceiver) == "true") {
        val receiver: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.Receiver)
        if (!xmlIsEmpty(receiver)) {
            this.resolveType(receiver)
            thisType = *receiver
        }
    }
    this.declareValue("this", true, false, thisType)
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
            // The `for` rewrite resolves the iterated expression (`spanForAt`), so it has to
            // run while the scope that declares the receiver is still up - a `for` over a
            // local of the arm's own (`val xs = ...; for (x in xs)`) is the common case.
            this.promoteForLoops(*thenBody)
            this.popScope()
            val elseBlock: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Else)
            if (!xmlIsEmpty(elseBlock)) {
                this.pushScope()
                val elseBody: List<AstXmlNode> = xmlChildren(elseBlock, AstNodeKind.Stmt)
                for (*elseStmt in elseBody) {
                    this.analyzeStmt(elseStmt)
                }
                this.promoteForLoops(*elseBody)
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
            // Same as the `if` arms: the rewrite runs in the scope that declares the
            // iterated local, before the scope goes away.
            this.promoteForLoops(*body)
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

// One `ExprCall`, with `boxed` saying it is the operand of `&` (`&C(...)`) - the one
// construction a handle-only class allows (`checkValueConstruction`); the rest of the
// analysis is the same either way.
fun Analyzer.analyzeCall(expr: *AstXmlNode, boxed: Bool): Unit {
    val callee: *AstXmlNode = xmlChildPtr(expr, AstNodeKind.Callee)
    if (!xmlIsEmpty(callee)) {
        this.analyzeExpr(callee)
    }
    // The pointer walk is deliberate: `xmlChildren` would hand the checker *copies* of the
    // arguments, and a rewrite of one (a `union class` tag comparison, say) would be dropped
    // instead of reaching the emitter.
    for (*arg in expr.Children) {
        if (arg.name == AstNodeKind.Arg) {
            this.analyzeExpr(arg)
        }
    }
    this.checkCallArity(expr)
    this.checkExtensionCallArity(expr)
    this.checkUninitCall(expr, callee)
    if (!boxed) {
        this.checkValueConstruction(expr, callee)
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
            this.analyzeCall(expr, false)
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

        AstNodeCategory.ExprUnary, AstNodeCategory.ExprDeref, AstNodeCategory.ExprCopy -> {
            val operand: *AstXmlNode = xmlChildPtr(expr, AstNodeKind.Operand)
            if (!xmlIsEmpty(operand)) {
                this.analyzeExpr(operand)
            }
            return
        }

        AstNodeCategory.ExprRef -> {
            // `&C(...)` is the *one* construction a handle-only class allows
            // (`checkValueConstruction`): the operand is analyzed in "boxed" context, so the
            // rule lets the construction be.
            val operand: *AstXmlNode = xmlChildPtr(expr, AstNodeKind.Operand)
            if (!xmlIsEmpty(operand)) {
                if (xmlKind(operand) == AstNodeCategory.ExprCall) {
                    this.analyzeCall(operand, true)
                } else {
                    this.analyzeExpr(operand)
                }
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
            val op: Str = xmlAttr(expr, AstNodeAttributeKind.Op)
            if ((op == "==" || op == "!=") && !xmlIsEmpty(lhs) && !xmlIsEmpty(rhs)) {
                this.expandUnionTagTest(expr, lhs, rhs)
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
