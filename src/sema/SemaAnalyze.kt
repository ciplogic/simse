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
            if (xmlIsProtocolDecl(decl)) {
                this.checkProtocolDeclShape(decl)
            }
            this.analyzeFunction(decl)
            return
        }
    }
}

// The declaration-time `union class` rules (`specs/declarations.md`): no user-declared
// fields named like the tag's storage or a generated member, and no user method that
// collides with a generated name. Two fields of one type are allowed: a by-value
// construction picks the earlier one (`checkUnionConstruction`), and the later arm stays
// reachable through its `set<Field>`.
fun Analyzer.checkUnionDecl(decl: *AstXmlNode): Unit {
    val name: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
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

// The static constructor spellings `Opt<T>.some(v)`, `Opt<T>.none()`, `Res<T>.ok(v)` and
// `Res<T>.err(m)` (specs/core-types.md) name no declaration: the checker rewrites the call
// onto the prelude functions that build the arm (`simse_optSome`, ..., src/rtl/optres.kt),
// which the ordinary call path then checks, types, emits and reaches. The rewritten callee
// keeps the receiver's type arguments (`Res<Str>.ok(x)` becomes `simse_resOk<Str>(x)`), so
// nothing depends on C++ overload resolution - which matters for `Res<Str>`, whose two arms
// are both `Str`.
fun Analyzer.expandResOptCtor(call: *AstXmlNode): Unit {
    val callee: *AstXmlNode = xmlChildPtr(call, AstNodeKind.Callee)
    if (xmlKind(callee) != AstNodeCategory.ExprMember) {
        return
    }
    val receiver: *AstXmlNode = xmlChildPtr(callee, AstNodeKind.Receiver)
    if (xmlKind(receiver) != AstNodeCategory.ExprGenericName) {
        return
    }
    val owner: Str = xmlAttr(receiver, AstNodeAttributeKind.Name)
    // The receiver must be the prelude union itself: a user type that reuses the name is
    // the user's own (its `Opt` wins the visible scope), and its members resolve normally.
    val ownerDecl: *AstXmlNode = this.types.getPtr(owner)
    if (ownerDecl == null || xmlAttr(ownerDecl, AstNodeAttributeKind.IsUnionClass) != "true") {
        return
    }
    val member: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
    var symbol: Str = ""
    if (owner == "Opt" && member == "some") {
        symbol = "simse_optSome"
    } else if (owner == "Opt" && member == "none") {
        symbol = "simse_optNone"
    } else if (owner == "Res" && member == "ok") {
        symbol = "simse_resOk"
    } else if (owner == "Res" && member == "err") {
        symbol = "simse_resErr"
    }
    if (symbol == "") {
        return
    }
    var rewritten: AstXmlNode = AstXmlNode(
        AstNodeKind.Expr, AstNodeCategory.ExprGenericName,
        listOf<AstNodeAttribute>(
            AstNodeAttribute(AstNodeAttributeKind.Name, symbol),
            AstNodeAttribute(AstNodeAttributeKind.Line, xmlLine(callee).toString()),
            AstNodeAttribute(AstNodeAttributeKind.Column, xmlColumn(callee).toString())
        ),
        Array<AstXmlNode>()
    )
    val typeArgs: List<AstXmlNode> = xmlChildren(receiver, AstNodeKind.TypeArg)
    xmlAddChildren(rewritten, typeArgs)
    replaceRoleChild(call, AstNodeKind.Callee, rewritten)
}

// The member surface of the ported `Opt`/`Res` - `hasValue()`, `isOk()`, `value()`,
// `error()` - is declared under collision-safe names (src/rtl/optres.kt): a *prelude*
// function is spelled bare in the emitted C++ (`value(&x)`), and a generated body is full
// of locals that would shadow one (`var value: T`). The checker renames a call on a union
// `Opt`/`Res` receiver to the `simse_*` spelling, so no language-level member name ever
// reaches a C++ scope where a local could shadow it.
fun Analyzer.expandResOptMember(call: *AstXmlNode): Unit {
    val callee: *AstXmlNode = xmlChildPtr(call, AstNodeKind.Callee)
    if (xmlKind(callee) != AstNodeCategory.ExprMember) {
        return
    }
    val member: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
    if (member != "value" && member != "error" && member != "hasValue" && member != "isOk") {
        return
    }
    val receiver: *AstXmlNode = xmlChildPtr(callee, AstNodeKind.Receiver)
    val unionDecl: AstXmlNode = this.unionDeclOf(receiver)
    if (xmlIsEmpty(unionDecl)) {
        return
    }
    val owner: Str = xmlAttr(unionDecl, AstNodeAttributeKind.Name)
    var target: Str = ""
    if (owner == "Opt" && member == "value") {
        target = "simse_optValue"
    } else if (owner == "Opt" && (member == "hasValue" || member == "isOk")) {
        target = "simse_optHasValue"
    } else if (owner == "Res" && member == "value") {
        target = "simse_resValue"
    } else if (owner == "Res" && (member == "hasValue" || member == "isOk")) {
        target = "simse_resHasValue"
    } else if (owner == "Res" && member == "error") {
        target = "simse_resError"
    }
    if (target == "") {
        return
    }
    var renamed: AstXmlNode = AstXmlNode(
        AstNodeKind.Expr, AstNodeCategory.ExprMember,
        listOf<AstNodeAttribute>(
            AstNodeAttribute(AstNodeAttributeKind.Name, target),
            AstNodeAttribute(AstNodeAttributeKind.Line, xmlLine(callee).toString()),
            AstNodeAttribute(AstNodeAttributeKind.Column, xmlColumn(callee).toString())
        ),
        Array<AstXmlNode>()
    )
    var receiverChild: AstXmlNode = receiver
    receiverChild.name = AstNodeKind.Receiver
    xmlAddChild(renamed, receiverChild)
    replaceRoleChild(call, AstNodeKind.Callee, renamed)
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
    val outerKind: AstNodeCategory = xmlKind(outer)
    if (outerKind != AstNodeCategory.TypeNamed && outerKind != AstNodeCategory.TypeGeneric) {
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
    this.checkProtocolConstraints(decl)
    this.collectCurrentConstraints(decl)
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
    this.currentConstraints = Dictionary<Str, List<Str>>()
}

// The declaration's `when` clause, as the constraints available to calls in its body
// (`typeSatisfiesProtocol` reads it when a parameter is passed on).
fun Analyzer.collectCurrentConstraints(decl: *AstXmlNode): Unit {
    this.currentConstraints = Dictionary<Str, List<Str>>()
    for (*constraint in semProtocolConstraints(decl)) {
        val existing: *List<Str> = this.currentConstraints.getPtr(constraint.param)
        if (existing != null) {
            existing.append(constraint.protocol)
        } else {
            var fresh: List<Str> = List<Str>()
            fresh.append(constraint.protocol)
            this.currentConstraints.insert(constraint.param, fresh)
        }
    }
}

// The shape a `protocol` declaration must have (specs/declarations.md, "Protocols"): the
// receiver is the subject - a type parameter when the protocol declares type parameters,
// any type name otherwise - and every signature parameter has a type, because the matcher
// reads those types.
fun Analyzer.checkProtocolDeclShape(decl: *AstXmlNode): Unit {
    val nameText: Str = semProtocolName(decl)
    val typeParams: List<Str> = xmlTypeParamNames(decl)
    val receiver: AstXmlNode = semaReceiverPattern(decl)
    val line: Int = xmlLine(decl)
    val column: Int = xmlColumn(decl)
    val receiverKind: AstNodeCategory = xmlKind(receiver)
    if (typeParams.size() > 0) {
        if (receiverKind != AstNodeCategory.TypeNamed
            || xmlAttr(receiver, AstNodeAttributeKind.Name) != typeParams[0]
        ) {
            val subjectText: Str = typeParams[0]
            this.diag(
                line, column,
                `protocol '@nameText': the receiver must be the first type parameter ('fun <@subjectText> @subjectText.method(...)')`
            )
        }
    } else if (receiverKind != AstNodeCategory.TypeNamed) {
        this.diag(line, column, `protocol '@nameText': the receiver must name the implemented type`)
    }
    for (*param in semProtocolValueParams(decl)) {
        if (xmlIsEmpty(xmlChildPtr(param, AstNodeKind.Type))) {
            val paramName: Str = xmlAttr(param, AstNodeAttributeKind.Name)
            this.diag(
                xmlLine(param), xmlColumn(param),
                `protocol '@nameText': parameter '@paramName' needs a type`
            )
        }
    }
}

// The `when T: P` constraints a declaration carries: each names one of the declaration's
// own type parameters and a declared protocol (specs/declarations.md, "Protocols"). The
// diagnostic position is the declaration's: the clause has no node of its own.
fun Analyzer.checkProtocolConstraints(decl: *AstXmlNode): Unit {
    val constraints: List<ProtocolConstraint> = semProtocolConstraints(decl)
    if (constraints.size() == 0) {
        return
    }
    val typeParams: List<Str> = xmlTypeParamNames(decl)
    val line: Int = xmlLine(decl)
    val column: Int = xmlColumn(decl)
    for (*constraint in constraints) {
        val paramName: Str = constraint.param
        val protocolName: Str = constraint.protocol
        if (!typeParams.contains(paramName)) {
            this.diag(
                line, column,
                `'@paramName' is not a type parameter of this declaration; a protocol constraint names one of its own`
            )
            continue
        }
        if (!this.globalProtocols.has(protocolName)) {
            this.diag(
                line, column,
                `unknown protocol '@protocolName': declare it with 'protocol @protocolName fun ...'`
            )
        }
    }
    // Two protocols of one type parameter that declare the same method: the call would
    // have two candidate implementations, so it is reported where the clause is.
    var i: Int = 0
    while (i < constraints.size()) {
        var j: Int = i + 1
        while (j < constraints.size()) {
            if (constraints[i].param == constraints[j].param) {
                val first: *AstXmlNode = this.globalProtocols.getPtr(constraints[i].protocol)
                val second: *AstXmlNode = this.globalProtocols.getPtr(constraints[j].protocol)
                if (first != null && second != null) {
                    val method: Str = semProtocolMethodName(*first)
                    if (method == semProtocolMethodName(*second) && method != "") {
                        val paramNameText: Str = constraints[i].param
                        val firstName: Str = constraints[i].protocol
                        val secondName: Str = constraints[j].protocol
                        this.diag(
                            line, column,
                            `'@paramNameText' is constrained by both '@firstName' and '@secondName', which declare '@method': the call would be ambiguous`
                        )
                    }
                }
            }
            j = j + 1
        }
        i = i + 1
    }
}

// Whether one type (as written at a call) satisfies a protocol: some declaration in the
// program matches the protocol's signature on that type. A bound that is the *caller's*
// own type parameter is satisfied when the caller's `when` clause requires the protocol.
fun Analyzer.typeSatisfiesProtocol(actual: AstXmlNode, protocolName: Str): Bool {
    val protocolPtr: *AstXmlNode = this.globalProtocols.getPtr(protocolName)
    if (protocolPtr == null) {
        // An unknown protocol is `checkProtocolConstraints`'s report, not this one's.
        return true
    }
    val protocolDecl: AstXmlNode = * protocolPtr
    val methodName: Str = semProtocolMethodName(protocolDecl)
    val outer: AstXmlNode = this.semaReceiverOuter(actual)
    val outerName: Str = xmlAttr(outer, AstNodeAttributeKind.Name)
    if (this.typeParamVisible(outerName)) {
        val covered: *List<Str> = this.currentConstraints.getPtr(outerName)
        if (covered != null && covered.contains(protocolName)) {
            return true
        }
        return false
    }
    // The type's own methods: a class body supplies the receiver.
    val typeDecl: AstXmlNode = this.protocolTypeDecl(outerName)
    if (!xmlIsEmpty(typeDecl) && xmlKind(typeDecl) == AstNodeCategory.DataClass) {
        val receiver: AstXmlNode = semClassReceiver(typeDecl)
        if (semaUnifyReceiver(receiver, actual, xmlTypeParamNames(typeDecl))) {
            for (*method in xmlChildren(typeDecl, AstNodeKind.Function)) {
                if (xmlAttr(method, AstNodeAttributeKind.Name) == methodName
                    && semProtocolMatches(protocolDecl, method, receiver)
                ) {
                    return true
                }
            }
        }
    }
    // Receiver functions declared anywhere in the program.
    val keys: List<Str> = this.globalFunctions.keys()
    for (*key in keys) {
        val overloads: *List<AstXmlNode> = this.globalFunctions.getPtr(*key)
        if (overloads == null) {
            continue
        }
        for (*fn in overloads) {
            if (xmlAttr(fn, AstNodeAttributeKind.Name) != methodName) {
                continue
            }
            val receiver: AstXmlNode = semaReceiverPattern(fn)
            if (xmlIsEmpty(receiver)) {
                continue
            }
            // The implementation must be written on *this* type, not another one that
            // happens to match the protocol's shape.
            if (!semaUnifyReceiver(receiver, actual, xmlTypeParamNames(fn))) {
                continue
            }
            if (semProtocolMatches(protocolDecl, fn, receiver)) {
                return true
            }
        }
    }
    return false
}

// The declaration of a type name, from what is visible first and the whole program second:
// a protocol is satisfied by declarations the call site may not import.
fun Analyzer.protocolTypeDecl(name: *Str): AstXmlNode {
    val visible: *AstXmlNode = this.types.getPtr(name)
    if (visible != null) {
        return * visible
    }
    val keys: List<Str> = this.globalTypes.keys()
    for (*key in keys) {
        val decl: *AstXmlNode = this.globalTypes.getPtr(*key)
        if (decl != null && xmlAttr(decl, AstNodeAttributeKind.Name) == name) {
            return * decl
        }
    }
    return xmlEmptyNode()
}

// The receiver a class method is written on: the class itself, generic when it is.
fun semClassReceiver(decl: *AstXmlNode): AstXmlNode {
    val typeParams: List<Str> = xmlTypeParamNames(decl)
    if (typeParams.size() == 0) {
        return semNamedType(xmlAttr(decl, AstNodeAttributeKind.Name))
    }
    var args: List<AstXmlNode> = List<AstXmlNode>()
    for (*param in typeParams) {
        var arg: AstXmlNode = semNamedType(*param)
        arg.name = AstNodeKind.TypeArg
        args.append(arg)
    }
    return semGenericType(xmlAttr(decl, AstNodeAttributeKind.Name), args)
}

// A call to a constrained generic function: every constraint the call's own type arguments
// or arguments fix is checked here, so a type that does not satisfy a protocol is a
// language diagnostic instead of a C++ error on the emitted dispatch call. A constraint no
// argument fixes is left to instantiation.
fun Analyzer.checkProtocolCall(call: *AstXmlNode, callee: *AstXmlNode): Unit {
    val kind: AstNodeCategory = xmlKind(callee)
    if (kind != AstNodeCategory.ExprName && kind != AstNodeCategory.ExprGenericName) {
        return
    }
    val name: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
    val overloads: *List<AstXmlNode> = this.functions.getPtr(name)
    if (overloads == null) {
        return
    }
    val argNodes: List<AstXmlNode> = xmlChildren(call, AstNodeKind.Arg)
    val argCount: Int = argNodes.size()
    for (*target in overloads) {
        val constraints: List<ProtocolConstraint> = semProtocolConstraints(target)
        if (constraints.size() == 0) {
            continue
        }
        // The overload's own arity (the receiver is not a parameter).
        if (xmlCount(target, AstNodeKind.Param) - semReceiverParams(target) != argCount) {
            continue
        }
        val typeParams: List<Str> = xmlTypeParamNames(target)
        var bindings: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
        val typeArgs: List<AstXmlNode> = xmlChildren(callee, AstNodeKind.TypeArg)
        var t: Int = 0
        while (t < typeArgs.size() && t < typeParams.size()) {
            semBindOne(*bindings, typeParams[t], typeArgs[t])
            t = t + 1
        }
        var argTypes: List<AstXmlNode> = List<AstXmlNode>()
        for (*arg in argNodes) {
            argTypes.append(this.exprType(arg))
        }
        semBindCallArgs(target, xmlEmptyNode(), *argTypes, *typeParams, *bindings)
        for (*constraint in constraints) {
            val boundPtr: *AstXmlNode = bindings.getPtr(constraint.param)
            if (boundPtr == null || xmlIsEmpty(*boundPtr)) {
                continue
            }
            if (this.typeSatisfiesProtocol(*boundPtr, constraint.protocol)) {
                continue
            }
            val protocolPtr: *AstXmlNode = this.globalProtocols.getPtr(constraint.protocol)
            if (protocolPtr == null) {
                continue
            }
            val protocolDecl: AstXmlNode = * protocolPtr
            val protocolText: Str = constraint.protocol
            val methodName: Str = semProtocolMethodName(protocolDecl)
            val signature: Str = semProtocolSignatureText(protocolDecl)
            val actualText: Str = semaTypeText(*boundPtr)
            this.diag(
                xmlLine(call), xmlColumn(call),
                `'@actualText' does not satisfy protocol '@protocolText': no '@methodName' matching '@signature' is in scope`
            )
        }
    }
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
                // A write through an indexer needs `operator set`; only `get` declared is a
                // read (specs/functions.md, "Operator functions").
                if (xmlKind(target) == AstNodeCategory.ExprIndex) {
                    this.checkOperatorIndexWrite(target)
                }
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
    // Before the callee is analyzed: the static constructor spellings become ordinary calls
    // to the prelude builders (`expandResOptCtor`), and the ported `Opt`/`Res` members are
    // renamed to their collision-safe prelude spellings (`expandResOptMember`).
    this.expandResOptCtor(expr)
    this.expandResOptMember(expr)
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
    this.checkProtocolCall(expr, callee)
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
