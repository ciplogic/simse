// xmlutil.kt
//
// Shared accessors over the compiler's AST schema (impl_specs/ast-xmlnode.md):
// the read-only ones (attribute lookup, children by structural role, positions,
// counts, the type-parameter list) are used by the ported sema and code generator
// so the two agree on how nodes are read, and `xmlAddChild` is the one writer a
// tree builder needs.
//
// The node type is `AstXmlNode` (cppsrc/rtl/astxml.kt): the role of a node and
// the key of an attribute are enums, so every lookup here is an integer compare;
// attribute *values* are text, as the schema specifies.
//
// These are ordinary emitted Simse functions (NOT prelude declarations), so they
// are available through the `common` import that a file using them declares.

package common

// An empty node is the "absent" sentinel: the schema has no null, so a missing
// optional child (an untyped field, a missing return type, ...) is a role-less,
// kind-less node.
fun xmlEmptyNode(): AstXmlNode {
    return AstXmlNode(AstNodeKind.None, AstNodeCategory.None, List<AstNodeAttribute>(), Array<AstXmlNode>())
}

fun xmlIsEmpty(node: *AstXmlNode): Bool {
    return node.name == AstNodeKind.None
}

// Appends one child. `Children` is an `Array<AstXmlNode>` - a fixed-length,
// shareable block (specs/xml-node.md), so appending replaces the node's handle
// with a block one element longer through the list round trip the array API
// specifies; an alias of the old children array keeps the old children.
fun xmlAddChild(node: *AstXmlNode, child: AstXmlNode): Unit {
    var children: List<AstXmlNode> = node.Children.toList()
    children.append(child)
    node.Children = children.toArray()
}

// The same, for a whole batch: one new block instead of one per child.
fun xmlAddChildren(node: *AstXmlNode, children: *List<AstXmlNode>): Unit {
    var all: List<AstXmlNode> = node.Children.toList()
    var i: Int = 0
    while (i < children.size()) {
        all.append(children[i])
        i = i + 1
    }
    node.Children = all.toArray()
}

fun xmlAttr(node: *AstXmlNode, name: AstNodeAttributeKind): Str {
    for (*attr in node.attributes) {
        if (attr.name == name) {
            return attr.value
        }
    }
    return ""
}

// The node's category: what it is (the schema's `kind`), read straight off the
// node - a `Str` comparison used to be the alternative.
fun xmlKind(node: *AstXmlNode): AstNodeCategory {
    return node.kind
}

// The schema's text for a category ("Stmt.If", "Type.Generic", ...), for
// diagnostics that have to name a kind. The dump spells the same values on the
// C++ side (`ast::astNodeCategoryText`); the two are kept in step by hand, and a
// divergence would surface in the differentials.
fun xmlKindText(kind: AstNodeCategory): Str {
    when (kind) {
        AstNodeCategory.Module -> {
            return "Module"
        }

        AstNodeCategory.DataClass -> {
            return "DataClass"
        }

        AstNodeCategory.Enum -> {
            return "Enum"
        }

        AstNodeCategory.TypeAlias -> {
            return "TypeAlias"
        }

        AstNodeCategory.Function -> {
            return "Function"
        }

        AstNodeCategory.Var -> {
            return "Var"
        }

        AstNodeCategory.StmtVarDecl -> {
            return "Stmt.VarDecl"
        }

        AstNodeCategory.StmtAssign -> {
            return "Stmt.Assign"
        }

        AstNodeCategory.StmtIf -> {
            return "Stmt.If"
        }

        AstNodeCategory.StmtWhile -> {
            return "Stmt.While"
        }

        AstNodeCategory.StmtReturn -> {
            return "Stmt.Return"
        }

        AstNodeCategory.StmtBreak -> {
            return "Stmt.Break"
        }

        AstNodeCategory.StmtContinue -> {
            return "Stmt.Continue"
        }

        AstNodeCategory.StmtExprStmt -> {
            return "Stmt.ExprStmt"
        }

        AstNodeCategory.StmtLabel -> {
            return "Stmt.Label"
        }

        AstNodeCategory.StmtGoto -> {
            return "Stmt.Goto"
        }

        AstNodeCategory.StmtIfTrue -> {
            return "Stmt.IfTrue"
        }

        AstNodeCategory.StmtIfFalse -> {
            return "Stmt.IfFalse"
        }

        AstNodeCategory.StmtBlock -> {
            return "Stmt.Block"
        }

        AstNodeCategory.StmtYield -> {
            return "Stmt.Yield"
        }

        AstNodeCategory.ExprIntLit -> {
            return "Expr.IntLit"
        }

        AstNodeCategory.ExprFloatLit -> {
            return "Expr.FloatLit"
        }

        AstNodeCategory.ExprStrLit -> {
            return "Expr.StrLit"
        }

        AstNodeCategory.ExprCharLit -> {
            return "Expr.CharLit"
        }

        AstNodeCategory.ExprBoolLit -> {
            return "Expr.BoolLit"
        }

        AstNodeCategory.ExprNullLit -> {
            return "Expr.NullLit"
        }

        AstNodeCategory.ExprName -> {
            return "Expr.Name"
        }

        AstNodeCategory.ExprGenericName -> {
            return "Expr.GenericName"
        }

        AstNodeCategory.ExprMember -> {
            return "Expr.Member"
        }

        AstNodeCategory.ExprCall -> {
            return "Expr.Call"
        }

        AstNodeCategory.ExprIndex -> {
            return "Expr.Index"
        }

        AstNodeCategory.ExprUnary -> {
            return "Expr.Unary"
        }

        AstNodeCategory.ExprBinary -> {
            return "Expr.Binary"
        }

        AstNodeCategory.ExprLambda -> {
            return "Expr.Lambda"
        }

        AstNodeCategory.ExprRef -> {
            return "Expr.Ref"
        }

        AstNodeCategory.ExprDeref -> {
            return "Expr.Deref"
        }

        AstNodeCategory.ExprCopy -> {
            return "Expr.Copy"
        }

        AstNodeCategory.TypeIntLit -> {
            return "Type.IntLit"
        }

        AstNodeCategory.TypeNamed -> {
            return "Type.Named"
        }

        AstNodeCategory.TypeGeneric -> {
            return "Type.Generic"
        }

        AstNodeCategory.TypeReference -> {
            return "Type.Reference"
        }

        AstNodeCategory.TypePointer -> {
            return "Type.Pointer"
        }

        AstNodeCategory.TypeFunction -> {
            return "Type.Function"
        }

        AstNodeCategory.TypeYield -> {
            return "Type.Yield"
        }
    }
    return ""
}

fun xmlIntAttr(node: *AstXmlNode, name: AstNodeAttributeKind, fallback: Int): Int {
    val parsed: Opt<Int> = xmlAttr(node, name).toInt()
    if (parsed.hasValue()) {
        return parsed.value()
    }
    return fallback
}

fun xmlLine(node: *AstXmlNode): Int {
    return xmlIntAttr(node, AstNodeAttributeKind.Line, 0)
}

fun xmlColumn(node: *AstXmlNode): Int {
    return xmlIntAttr(node, AstNodeAttributeKind.Column, 0)
}

// The first child whose role is `role`, or an empty node.
fun xmlChild(node: *AstXmlNode, role: AstNodeKind): AstXmlNode {
    // The *pointer* form on purpose: `for (child in node.Children)` binds a copy of
    // each element, and `Children` is a reference-counted `Array` - the count churn
    // per element per lookup is the difference between a lookup and a copy, in the
    // compiler's hottest helper. `copy(child)` reads the one element that matched.
    for (*child in node.Children) {
        if (child.name == role) {
            return copy(child)
        }
    }
    return xmlEmptyNode()
}

// Every child whose role is `role`, in order.
fun xmlChildren(node: *AstXmlNode, role: AstNodeKind): List<AstXmlNode> {
    var out: List<AstXmlNode> = List<AstXmlNode>()
    for (*child in node.Children) {
        if (child.name == role) {
            out.append(copy(child))
        }
    }
    return out
}

fun xmlCount(node: *AstXmlNode, role: AstNodeKind): Int {
    var count: Int = 0
    for (*child in node.Children) {
        if (child.name == role) {
            count = count + 1
        }
    }
    return count
}

fun xmlHasChild(node: *AstXmlNode, role: AstNodeKind): Bool {
    for (*child in node.Children) {
        if (child.name == role) {
            return true
        }
    }
    return false
}

// The `name` attributes of a declaration's `TypeParam` children, in order.
fun xmlTypeParamNames(node: *AstXmlNode): List<Str> {
    val params: List<AstXmlNode> = xmlChildren(node, AstNodeKind.TypeParam)
    var names: List<Str> = List<Str>()
    for (*param in params) {
        names.append(xmlAttr(param, AstNodeAttributeKind.Name))
    }
    return names
}

fun xmlIsDecl(node: *AstXmlNode): Bool {
    val name: AstNodeKind = node.name
    return name == AstNodeKind.DataClass || name == AstNodeKind.Enum || name == AstNodeKind.TypeAlias
            || name == AstNodeKind.Function || name == AstNodeKind.Var
}

// The declaration children of a Module (imports excluded), in source order.
// `Var` is a file-level `var`/`val`: static storage (specs/statics.md).
fun xmlDecls(module: *AstXmlNode): List<AstXmlNode> {
    var out: List<AstXmlNode> = List<AstXmlNode>()
    for (*child in module.Children) {
        if (xmlIsDecl(child)) {
            out.append(copy(child))
        }
    }
    return out
}

fun xmlHasImports(module: *AstXmlNode): Bool {
    for (*child in module.Children) {
        if (child.name == AstNodeKind.Import) {
            return true
        }
    }
    return false
}

// The comma-joined `params` attribute of a lambda, split back into names. An
// empty attribute means no parameters (not one empty name).
fun xmlLambdaParams(expr: *AstXmlNode): List<Str> {
    val raw: Str = xmlAttr(expr, AstNodeAttributeKind.Params)
    if (raw == "") {
        return List<Str>()
    }
    return raw.split(",")
}

// Reads the list only; a `*List<Str>` avoids copying the caller's list.
fun xmlIsTypeParam(name: Str, typeParams: *List<Str>): Bool {
    var i: Int = 0
    while (i < typeParams.size()) {
        if (typeParams[i] == name) {
            return true
        }
        i = i + 1
    }
    return false
}
