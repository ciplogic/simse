// IlSlot.kt
//
// The frame and slot walk: seeding types, folding, declared/closure classification, the
// declaration groups and the node an operand becomes. `Emitter` extension functions;
// IlCodeGen.kt holds the IL model (IlFrame/IlText/...).

package codegen

import compiler

import sema
import common
import io
import linear
import optimizations
import profiling

// An operand becomes a leaf `AstXmlNode` - a slot is a name, a constant its literal, a
// place the path it came from, folded out of the instruction that built it.
fun Emitter.ilIntAt(map: *Dictionary<Int, Int>, key: Int, fallback: Int): Int {
    val found: *Int = map.getPtr(key)
    if (found != null) {
        return * found
    }
    return fallback
}

fun Emitter.ilBump(map: *Dictionary<Int, Int>, key: Int): Unit {
    map.insert(key, this.ilIntAt(map, key, 0) + 1)
}

// The operand at `index`, or -1 when out of range. Not `ilOperandAt`: a bare call binds
// by simple name across the whole compilation (`src/linear/LinearForm.kt`).
fun Emitter.ilOpOperand(operands: *List<Int>, index: Int): Int {
    if (index >= 0 && index < operands.size()) {
        return operands[index]
    }
    return -1
}

fun Emitter.ilNameNode(text: *Str): AstXmlNode {
    var node: AstXmlNode =
        AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprName, List<AstNodeAttribute>(), Array<AstXmlNode>())
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, text))
    return node
}

// The destination slot of an instruction, or -1 when it writes memory or jumps instead
// (`ilWritesDestination`).
fun Emitter.ilDst(op: *IlOp): Int {
    if (!ilWritesDestination(op.kind) || op.operands.size() == 0) {
        return -1
    }
    return op.operands[0]
}

// The frame's types, from the body's own tables. Declared types win over the pass's; a
// machine's type survives only here, its declaration never written (impl_specs/for.md).
fun Emitter.ilSeedFrameTypes(il: *IlBody): Unit {
    val proven: List<Str> = il.inferredTypes.keys()
    var i: Int = 0
    while (i < proven.size()) {
        val typeNode: *AstXmlNode = il.inferredTypes.getPtr(proven[i])
        this.localTypes.insert(proven[i], *typeNode)
        this.nameKinds.insert(proven[i], this.kindOf(typeNode))
        i = i + 1
    }
    i = 0
    while (i < il.vars.size()) {
        val slotType: AstXmlNode = ilVarType(il, i)
        if (!xmlIsEmpty(slotType)) {
            this.localTypes.insert(il.vars[i].name, slotType)
            this.nameKinds.insert(il.vars[i].name, this.kindOf(slotType))
        }
        i = i + 1
    }
}

fun Emitter.ilAnalyze(il: *IlBody, frame: *IlFrame): Unit {
    for ((*op, i) in il.ops) {
        if (op.kind != IlOpKind.Declare) {
            // An instruction that writes memory or jumps has no destination, but its
            // operands are still reads.
            val dst: Int = this.ilDst(op)
            if (dst >= 0 && dst < il.vars.size()) {
                // The *first* write initialises a slot: a loop target is written again
                // every iteration, and the declaration wants the initializer.
                if (!frame.defOp.has(dst)) {
                    frame.defOp.insert(dst, i)
                }
                this.ilBump(*frame.defineCount, dst)
            }
            var j: Int = 0
            while (j < op.operands.size()) {
                var read: Bool = true
                if (j == 0 && dst >= 0) {
                    read = false
                }
                val kind: IlOperandKind = ilOperandKind(op, j)
                if (kind != IlOperandKind.Var && kind != IlOperandKind.Value) {
                    // A label, a pool entry, a type, a callee.
                    read = false
                }
                if (read) {
                    val operand: Int = op.operands[j]
                    if (operand >= 0 && operand < il.vars.size()) {
                        this.ilBump(*frame.useCount, operand)
                    }
                }
                j = j + 1
            }
        }
    }
}

// Whether a value is inlined at its use: only an untyped temp with exactly one
// definition and one use (`auto x;` is not a declaration, so it cannot be declared).
fun Emitter.ilFolded(il: *IlBody, frame: *IlFrame, slot: Int): Bool {
    if (slot < 0 || slot >= il.vars.size()) {
        return false
    }
    if (il.vars[slot].kind != IlVarKind.Temp) {
        return false
    }
    val slotType: AstXmlNode = ilVarType(il, slot)
    if (!xmlIsEmpty(slotType)) {
        return false // a typed slot is declared
    }
    if (this.ilIntAt(frame.defineCount, slot, 0) != 1) {
        return false
    }
    if (this.ilIntAt(frame.useCount, slot, 0) != 1) {
        return false
    }
    // A closure is an aggregate, not an expression: it keeps its slot.
    return !this.ilSlotHoldsClosure(il, frame, slot)
}

// Whether a slot is declared with the frame at the top of the body (a typed slot).
fun Emitter.ilDeclaredAtTop(il: *IlBody, slot: Int): Bool {
    if (slot < 0 || slot >= il.vars.size()) {
        return false
    }
    val slotType: AstXmlNode = ilVarType(il, slot)
    return !xmlIsEmpty(slotType)
}

// Whether an instruction builds a closure class instance: an *aggregate*, not an
// expression, so it cannot stand inside another expression and its slot never folds.
fun Emitter.ilConstructsClosure(op: *IlOp, il: *IlBody): Bool {
    if (op.kind != IlOpKind.CallCtor) {
        return false
    }
    val typeAt: Int = this.ilOpOperand(op.operands, 1)
    if (typeAt < 0 || typeAt >= il.types.size()) {
        return false
    }
    return this.closureTypes.has(il.types[typeAt])
}

fun Emitter.ilSlotHoldsClosure(il: *IlBody, frame: *IlFrame, slot: Int): Bool {
    val def: Int = this.ilIntAt(frame.defOp, slot, -1)
    if (def < 0 || def >= il.ops.size()) {
        return false
    }
    return this.ilConstructsClosure(il.ops[def], il)
}

// The C++ of a slot's declared type. A closure class is spelled by its own name (with a
// generic owner's arguments): it is emitted into the `closures` section, so no type
// dictionary knows it.
fun Emitter.ilDeclTypeText(il: *IlBody, slot: Int): Str {
    val slotType: AstXmlNode = ilVarType(il, slot)
    if (xmlIsEmpty(slotType)) {
        return ""
    }
    val closureText: Opt<Str> = this.ilClosureTypeOfNodeOpt(slotType)
    if (closureText.hasValue()) {
        return closureText.value()
    }
    return this.type(slotType)
}

// The closure class a type node names, when it names one of the unit's classes.
fun Emitter.ilClosureTypeOfNodeOpt(typeNode: AstXmlNode): Opt<Str> {
    if (xmlKind(typeNode) != AstNodeCategory.TypeNamed) {
        return ()
    }
    val name: Str = xmlAttr(typeNode, AstNodeAttributeKind.Name)
    val found: *Str = this.closureTypes.getPtr(name)
    if (found == null) {
        return ()
    }
    return ( * found)
}

// The closure class a local name holds, when it holds one: its call goes to the class's
// free `_invoke` rather than to C++'s `operator()`. A function-typed value (`Func<...>`) is
// not one, so a call through it is left as it stands.
fun Emitter.ilClosureNameOf(name: *Str): Str {
    val typeNode: *AstXmlNode = this.localTypes.getPtr(name)
    if (typeNode == null || xmlKind(*typeNode) != AstNodeCategory.TypeNamed) {
        return ""
    }
    val typeName: Str = xmlAttr(*typeNode, AstNodeAttributeKind.Name)
    if (!this.closureTypes.has(typeName)) {
        return ""
    }
    return typeName
}

// One declarator of a line that has already written its type: `* b`, or `b`.
fun ilDeclarator(ptr: *Str, name: *Str): Str {
    if (ptr == "") {
        return name
    }
    return ptr + " " + name
}

// One type's declaration lines, wrapped at `kIlDeclWidth` columns. A wrapped line is a
// *continuation* with no type of its own; the caller indents it one level. A trailing
// `*` binds only the name it stands before, so it is written once per name.
val kIlDeclWidth: Int = 100

fun ilDeclLines(typeText: Str, names: *List<Str>): List<Str> {
    var base: Str = typeText
    var ptr: Str = ""
    while (base.size() > 0 && base[base.size() - 1] == '*') {
        ptr = ptr + "*"
        base = base.substr(0, base.size() - 1)
    }
    var out: List<Str> = List<Str>()
    var line = Str()
    var open: Bool = false
    var i: Int = 0
    while (i < names.size()) {
        val piece: Str = ilDeclarator(ptr, names[i])
        if (!open) {
            line.appendStrPtr(base)
            line.appendStrPtr(ptr)
            line.append(' ')
            line.appendStrPtr(names[i])
            open = true
        } else if (line.size() + 2 + piece.size() > kIlDeclWidth) {
            line.append(',')
            out.append(line)
            line = Str()
            line.appendStrPtr(piece)
        } else {
            line.appendStr(", ")
            line.appendStrPtr(piece)
        }
        i = i + 1
    }
    line.append(';')
    out.append(line)
    return out
}

// The frame's storage grouped by type: the lines to print at each declaration's position,
// the first of a type printing all its names. Safe because a declaration *is* storage and
// a group per type keeps the across-type order; a declaration a jump can cross ends the
// run and gets a block (`ilEmitOps`'s `blockEnd`).
fun Emitter.ilDeclGroups(
    il: *IlBody, frame: *IlFrame, blockEnd: *Dictionary<Int, IlCrossing>
): Dictionary<Int, List<Str>> {
    var lines: Dictionary<Int, List<Str>> = Dictionary<Int, List<Str>>()
    var i: Int = 0
    while (i < il.ops.size()) {
        if (il.ops[i].kind != IlOpKind.Declare || blockEnd.has(i)) {
            i = i + 1
            continue
        }
        var types: List<Str> = List<Str>()
        var groups: List<List<Str>> = List<List<Str>>()
        var at: Int = i
        while (at < il.ops.size() && il.ops[at].kind == IlOpKind.Declare && !blockEnd.has(at)) {
            val slot: Int = this.ilOpOperand(il.ops[at].operands, 0)
            if (slot < 0 || slot >= il.vars.size() || this.ilFolded(il, frame, slot)
                || !this.ilDeclaredAtTop(il, slot)
            ) {
                break
            }
            val typeText: Str = this.ilDeclTypeText(il, slot)
            if (typeText == "") {
                break
            }
            var index: Int = -1
            var t: Int = 0
            while (t < types.size()) {
                if (types[t] == typeText) {
                    index = t
                }
                t = t + 1
            }
            if (index < 0) {
                types.append(typeText)
                groups.append(List<Str>())
                index = types.size() - 1
            }
            groups[index].append(il.vars[slot].name)
            at = at + 1
        }
        if (at - i < 2) {
            // One declaration: nothing to group.
            i = i + 1
            continue
        }
        var printed: Int = i
        var t: Int = 0
        while (t < types.size()) {
            lines.insert(printed, ilDeclLines(types[t], *groups[t]))
            printed = printed + 1
            t = t + 1
        }
        while (printed < at) {
            lines.insert(printed, List<Str>())
            printed = printed + 1
        }
        i = at
    }
    return lines
}

// A constant operand: the pool already holds the text the C++ prints, so only the node
// kind is left (`true` a BoolLit, `"abc"` a StrLit, a digit run an IntLit or FloatLit).
fun Emitter.ilLiteralNode(text: *Str): AstXmlNode {
    var node: AstXmlNode =
        AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprIntLit, List<AstNodeAttribute>(), Array<AstXmlNode>())
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Text, text))
    var dot: Bool = false
    var i: Int = 0
    while (i < text.size()) {
        if (text[i] == '.') {
            dot = true
        }
        i = i + 1
    }
    if (text.isEmpty() || text[0] == '\"') {
        node.kind = AstNodeCategory.ExprStrLit
    } else if (text[0] == '\'') {
        node.kind = AstNodeCategory.ExprCharLit
    } else if (text == "true" || text == "false") {
        node.kind = AstNodeCategory.ExprBoolLit
        node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Value, text))
    } else if (dot) {
        node.kind = AstNodeCategory.ExprFloatLit
    }
    return node
}

fun Emitter.ilSlotNode(il: *IlBody, frame: *IlFrame, slot: Int, depth: Int): AstXmlNode {
    if (slot < 0 || slot >= il.vars.size() || depth > 24) {
        return xmlEmptyNode()
    }
    val name: Str = il.vars[slot].name
    // A receiver slot is the language's `this`, which the emitter spells `(*self)` /
    // `this->`. A lambda's free invoke is the one body whose `self` is a real value (its
    // class passed by copy), so there the name stands as it is (`emitClosureBodyText`).
    if (name == "self") {
        val selfType: AstXmlNode = ilVarType(il, slot)
        if (xmlIsEmpty(selfType) || this.isHandleType(selfType)) {
            return this.ilNameNode("this")
        }
    }
    if (this.ilFolded(il, frame, slot)) {
        return this.ilOpValueNode(il, frame, this.ilIntAt(frame.defOp, slot, -1), depth + 1)
    }
    return this.ilNameNode(name)
}

fun Emitter.ilOperandNode(il: *IlBody, frame: *IlFrame, operand: Int, depth: Int): AstXmlNode {
    if (operand < 0) {
        val index: Int = -1 - operand
        if (index >= il.pool.size()) {
            return xmlEmptyNode()
        }
        return this.ilLiteralNode(il.pool[index])
    }
    return this.ilSlotNode(il, frame, operand, depth)
}

fun Emitter.ilMemberNode(base: *AstXmlNode, name: *Str): AstXmlNode {
    if (xmlIsEmpty(base)) {
        return xmlEmptyNode()
    }
    var node: AstXmlNode =
        AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprMember, List<AstNodeAttribute>(), Array<AstXmlNode>())
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
    xmlAddChild(node, this.renameRole(base, AstNodeKind.Receiver))
    return node
}

// The *address* of a place, as the emitter spells a borrow: `&name` for a plain name,
// `simse_addressOf(...)` otherwise (`impl_specs/linear-il.md`). A place is the one value
// an instruction may not copy.
fun Emitter.ilBorrowNode(place: *AstXmlNode, depth: Int): AstXmlNode {
    if (xmlIsEmpty(place) || depth > 24) {
        return xmlEmptyNode()
    }
    var node: AstXmlNode =
        AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprDeref, List<AstNodeAttribute>(), Array<AstXmlNode>())
    xmlAddChild(node, this.renameRole(place, AstNodeKind.Operand))
    return node
}

fun Emitter.ilBinaryNode(lhs: *AstXmlNode, op: *Str, rhs: *AstXmlNode): AstXmlNode {
    if (xmlIsEmpty(lhs) || xmlIsEmpty(rhs)) {
        return xmlEmptyNode()
    }
    var node: AstXmlNode =
        AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprBinary, List<AstNodeAttribute>(), Array<AstXmlNode>())
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Op, op))
    xmlAddChild(node, this.renameRole(lhs, AstNodeKind.Lhs))
    xmlAddChild(node, this.renameRole(rhs, AstNodeKind.Rhs))
    return node
}

fun Emitter.ilLastDot(text: *Str): Int {
    var dot: Int = -1
    var i: Int = 0
    while (i < text.size()) {
        if (text[i] == '.') {
            dot = i
        }
        i = i + 1
    }
    return dot
}

// A non-local name in a value position: an enum member (`Color.Red`) or a file-level `var`.
fun Emitter.ilGetStaticNode(il: *IlBody, op: *IlOp): AstXmlNode {
    val textIndex: Int = this.ilOpOperand(op.operands, 1)
    if (textIndex < 0 || textIndex >= il.pool.size()) {
        return xmlEmptyNode()
    }
    val text: Str = il.pool[textIndex]
    val dot: Int = this.ilLastDot(text)
    if (dot <= 0) {
        return this.ilNameNode(text)
    }
    val base: AstXmlNode = this.ilNameNode(text.substr(0, dot))
    return this.ilMemberNode(base, text.substr(dot + 1, text.size() - dot - 1))
}

// The node for the type a *static* call is reached through. `asGenericName` is for a
// construction, whose callee the parser always produces as `Name<T>` (what makes it a
// `CallCtor`); a static call's base is generic only when the source wrote one.
fun Emitter.ilTypeBaseNode(il: *IlBody, typeIndex: Int, asGenericName: Bool): AstXmlNode {
    val baseType: AstXmlNode = ilTypeNode(il, typeIndex)
    if (xmlIsEmpty(baseType)) {
        return xmlEmptyNode()
    }
    val args: List<AstXmlNode> = xmlChildren(baseType, AstNodeKind.TypeArg)
    if (xmlKind(baseType) == AstNodeCategory.TypeGeneric && (asGenericName || args.size() > 0)) {
        var node: AstXmlNode = AstXmlNode(
            AstNodeKind.Expr,
            AstNodeCategory.ExprGenericName,
            List<AstNodeAttribute>(),
            Array<AstXmlNode>()
        )
        node.attributes.append(
            AstNodeAttribute(
                AstNodeAttributeKind.Name,
                xmlAttr(baseType, AstNodeAttributeKind.Name)
            )
        )
        for (*arg in args) {
            xmlAddChild(node, this.renameRole(arg, AstNodeKind.TypeArg))
        }
        return node
    }
    return this.ilNameNode(xmlAttr(baseType, AstNodeAttributeKind.Name))
}

// A call instruction as the expression the emitter spells.
fun Emitter.ilCallNode(il: *IlBody, frame: *IlFrame, op: *IlOp): AstXmlNode {
    val hasDst: Bool = this.ilDst(op) >= 0
    var methodAt: Int = 0
    if (hasDst) {
        methodAt = 1
    }
    val methodIndex: Int = this.ilOpOperand(op.operands, methodAt)
    if (methodIndex < 0 || methodIndex >= il.methods.size()) {
        this.ilWhy = "a call with no callee"
        return xmlEmptyNode()
    }
    val method: *IlMethod = *il.methods[methodIndex]

    var call: AstXmlNode =
        AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprCall, List<AstNodeAttribute>(), Array<AstXmlNode>())
    var first: Int = methodAt + 1
    var callee: AstXmlNode = xmlEmptyNode()
    var closureCall: Str = ""
    if (method.kind == IlMethodKind.Method) {
        val recv: AstXmlNode = this.ilSlotNode(il, frame, this.ilOpOperand(op.operands, first), 0)
        if (xmlIsEmpty(recv)) {
            val methodNameText: Str = method.name
            this.ilWhy = `the receiver of '@methodNameText'`
            return xmlEmptyNode()
        }
        callee = this.ilMemberNode(recv, method.name)
        first = first + 1
    } else if (method.staticBase >= 0) {
        val base: AstXmlNode = this.ilTypeBaseNode(il, method.staticBase, false)
        if (xmlIsEmpty(base)) {
            val methodNameText2: Str = method.name
            this.ilWhy = `the type '@methodNameText2' is reached through`
            return xmlEmptyNode()
        }
        callee = this.ilMemberNode(base, method.name)
    } else {
        // A call through a closure-typed value reaches the free method its class carries:
        // `<symbol>_invoke(x, args)`, the instance passed by copy. A `Func<...>` value is
        // not one - it calls as it stands.
        closureCall = this.ilClosureNameOf(method.name)
        if (closureCall != "") {
            callee = this.ilNameNode(closureCall + "_invoke")
        } else {
            callee = this.ilNameNode(method.name)
        }
    }
    if (xmlIsEmpty(callee)) {
        return xmlEmptyNode()
    }
    xmlAddChild(call, this.renameRole(callee, AstNodeKind.Callee))
    if (closureCall != "") {
        val selfNode: AstXmlNode = this.ilNameNode(method.name)
        xmlAddChild(call, this.renameRole(selfNode, AstNodeKind.Arg))
    }
    var i: Int = first
    while (i < op.operands.size()) {
        val arg: AstXmlNode = this.ilOperandNode(il, frame, op.operands[i], 0)
        if (xmlIsEmpty(arg)) {
            val methodNameText3: Str = method.name
            this.ilWhy = `an argument of '@methodNameText3'`
            return xmlEmptyNode()
        }
        xmlAddChild(call, this.renameRole(arg, AstNodeKind.Arg))
        i = i + 1
    }
    return call
}

// The expression a value-producing instruction computes, as a node. Both the
// instruction's right-hand side and what a *folded* slot stands for wherever it is read.
fun Emitter.ilOpValueNode(il: *IlBody, frame: *IlFrame, opIndex: Int, depth: Int): AstXmlNode {
    if (opIndex < 0 || opIndex >= il.ops.size() || depth > 24) {
        return xmlEmptyNode()
    }
    val op: *IlOp = *il.ops[opIndex]
    val kind: IlOpKind = op.kind
    when (kind) {
        IlOpKind.SetVar -> {
            return this.ilOperandNode(il, frame, this.ilOpOperand(op.operands, 1), depth)
        }

        IlOpKind.SetVar_Null -> {
            return AstXmlNode(
                AstNodeKind.Expr,
                AstNodeCategory.ExprNullLit,
                List<AstNodeAttribute>(),
                Array<AstXmlNode>()
            )
        }

        IlOpKind.BinaryOp -> {
            val opIndex2: Int = this.ilOpOperand(op.operands, 1)
            if (opIndex2 < 0 || opIndex2 >= il.pool.size()) {
                return xmlEmptyNode()
            }
            val lhs: AstXmlNode = this.ilOperandNode(il, frame, this.ilOpOperand(op.operands, 2), depth)
            val rhs: AstXmlNode = this.ilOperandNode(il, frame, this.ilOpOperand(op.operands, 3), depth)
            return this.ilBinaryNode(lhs, il.pool[opIndex2], rhs)
        }

        IlOpKind.UnaryOp -> {
            val opIndex2: Int = this.ilOpOperand(op.operands, 1)
            val operand: AstXmlNode = this.ilOperandNode(il, frame, this.ilOpOperand(op.operands, 2), depth)
            if (opIndex2 < 0 || opIndex2 >= il.pool.size() || xmlIsEmpty(operand)) {
                return xmlEmptyNode()
            }
            var node: AstXmlNode =
                AstXmlNode(
                    AstNodeKind.Expr,
                    AstNodeCategory.ExprUnary,
                    List<AstNodeAttribute>(),
                    Array<AstXmlNode>()
                )
            node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Op, il.pool[opIndex2]))
            xmlAddChild(node, this.renameRole(operand, AstNodeKind.Operand))
            return node
        }

        IlOpKind.GetField, IlOpKind.FieldAddr -> {
            val textIndex: Int = this.ilOpOperand(op.operands, 2)
            if (textIndex < 0 || textIndex >= il.pool.size()) {
                return xmlEmptyNode()
            }
            val base: AstXmlNode = this.ilSlotNode(il, frame, this.ilOpOperand(op.operands, 1), depth)
            val member: AstXmlNode = this.ilMemberNode(base, il.pool[textIndex])
            if (kind == IlOpKind.GetField) {
                return member
            }
            return this.ilBorrowNode(member, depth)
        }

        IlOpKind.IndexAddr, IlOpKind.GetIndex -> {
            val base: AstXmlNode = this.ilSlotNode(il, frame, this.ilOpOperand(op.operands, 1), depth)
            val index: AstXmlNode = this.ilOperandNode(il, frame, this.ilOpOperand(op.operands, 2), depth)
            if (xmlIsEmpty(base) || xmlIsEmpty(index)) {
                return xmlEmptyNode()
            }
            var node: AstXmlNode =
                AstXmlNode(
                    AstNodeKind.Expr,
                    AstNodeCategory.ExprIndex,
                    List<AstNodeAttribute>(),
                    Array<AstXmlNode>()
                )
            xmlAddChild(node, this.renameRole(base, AstNodeKind.Receiver))
            xmlAddChild(node, this.renameRole(index, AstNodeKind.Index))
            if (kind == IlOpKind.GetIndex) {
                return node
            }
            return this.ilBorrowNode(node, depth)
        }

        IlOpKind.Deref, IlOpKind.CopyValue, IlOpKind.Box -> {
            val operand: AstXmlNode = this.ilSlotNode(il, frame, this.ilOpOperand(op.operands, 1), depth)
            if (xmlIsEmpty(operand)) {
                return xmlEmptyNode()
            }
            var category: AstNodeCategory = AstNodeCategory.ExprDeref
            if (kind == IlOpKind.CopyValue) {
                category = AstNodeCategory.ExprCopy
            } else if (kind == IlOpKind.Box) {
                category = AstNodeCategory.ExprRef
            }
            var node: AstXmlNode =
                AstXmlNode(AstNodeKind.Expr, category, List<AstNodeAttribute>(), Array<AstXmlNode>())
            xmlAddChild(node, this.renameRole(operand, AstNodeKind.Operand))
            return node
        }

        IlOpKind.GetStatic -> {
            return this.ilGetStaticNode(il, op)
        }

        IlOpKind.GetStaticAddr -> {
            // The address of a file-level static: `&name`, never of a copy of it.
            val place: AstXmlNode = this.ilGetStaticNode(il, op)
            return this.ilBorrowNode(place, depth)
        }

        IlOpKind.Call, IlOpKind.CallVoid -> {
            return this.ilCallNode(il, frame, op)
        }

        IlOpKind.CallCtor -> {
            val callee: AstXmlNode = this.ilTypeBaseNode(il, this.ilOpOperand(op.operands, 1), true)
            if (xmlIsEmpty(callee)) {
                return xmlEmptyNode()
            }
            var call: AstXmlNode =
                AstXmlNode(
                    AstNodeKind.Expr,
                    AstNodeCategory.ExprCall,
                    List<AstNodeAttribute>(),
                    Array<AstXmlNode>()
                )
            xmlAddChild(call, this.renameRole(callee, AstNodeKind.Callee))
            var i: Int = 2
            while (i < op.operands.size()) {
                val arg: AstXmlNode = this.ilOperandNode(il, frame, op.operands[i], 0)
                if (xmlIsEmpty(arg)) {
                    return xmlEmptyNode()
                }
                xmlAddChild(call, this.renameRole(arg, AstNodeKind.Arg))
                i = i + 1
            }
            return call
        }
    }
    // `Cast`, `CallIndirect`, a lambda body, and anything the extractor marked: not
    // expressible yet.
    return xmlEmptyNode()
}

// Where each of the body's labels sits, or -1 when it has no `Label` instruction. One
// array per body: `ilJumpCrossing` asks per *declaration*.
fun Emitter.ilLabelPositions(il: *IlBody): List<Int> {
    var positions: List<Int> = List<Int>()
    var i: Int = 0
    while (i < il.labels.size()) {
        positions.append(-1)
        i = i + 1
    }
    i = 0
    while (i < il.ops.size()) {
        val op: *IlOp = *il.ops[i]
        if (op.kind == IlOpKind.Label && op.operands.size() > 0) {
            val label: Int = op.operands[0]
            if (label >= 0 && label < positions.size()) {
                positions[label] = i
            }
        }
        i = i + 1
    }
    return positions
}

// The scope a crossed declaration needs: the earliest label a jump lands on and the
// last jump that crosses ([stmt.dcl]/3, MSVC C2362).
fun Emitter.ilJumpCrossing(il: *IlBody, labelPos: *List<Int>, position: Int): IlCrossing {
    var crossing: IlCrossing = IlCrossing(-1, -1)
    var i: Int = 0
    while (i < position) {
        val op: *IlOp = *il.ops[i]
        var labelAt: Int = -1
        if (op.kind == IlOpKind.Goto) {
            labelAt = 0
        } else if (op.kind == IlOpKind.IfTrue || op.kind == IlOpKind.IfFalse) {
            labelAt = 1
        }
        if (labelAt >= 0) {
            val target: Int = this.ilOpOperand(op.operands, labelAt)
            if (target >= 0 && target < labelPos.size()) {
                val at: Int = labelPos[target]
                if (at >= position) {
                    if (crossing.end < 0 || at < crossing.end) {
                        crossing.end = at
                    }
                    crossing.lastJump = i
                }
            }
        }
        i = i + 1
    }
    return crossing
}
