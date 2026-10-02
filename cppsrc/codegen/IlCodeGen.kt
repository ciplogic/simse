// IlCodeGen.kt
//
// The instruction-list backend: the IL's own types and the walks that turn a body's
// instruction list into C++ text (`emitBodyAt`). The IL is the only codegen, and an
// instruction it cannot spell is a hard error, never a fallback (impl_specs/linear-il.md).
// The walks are `Emitter` extension functions: a split file cannot reopen the class.

package codegen
import compiler

import sema
import common
import io
import linear
import optimizations
import profiling

data class IlFrame(
    // Keyed by *slot index*, not name: two scopes may declare the same name.
    var defOp: Dictionary<Int, Int>,

    var defineCount: Dictionary<Int, Int>,
    var useCount: Dictionary<Int, Int>
)

// A jump crossing a declaration needs a scope: a `goto` may not skip an initialization
// ([stmt.dcl]/3, MSVC C2362). `end` is the label it lands on, `lastJump` the last jump.
data class IlCrossing(
    var end: Int,

    var lastJump: Int
)

// One open block, and the label it ends before.
data class IlScope(
    var start: Int,

    var end: Int
)

// The text one body's instructions spell, or why they could not be spelled.
data class IlText(
    var ok: Bool,

    var text: Str,
    var reason: Str
)

// `--showLinearRepresentation`: the IL of the body the emitter is about to read, on
// stderr (impl_specs/linear-il.md).
fun Emitter.dumpIl(
    fn: *CgFn, decl: *AstXmlNode, body: *List<AstXmlNode>, facts: *SemFacts,
    inferred: *Dictionary<Str, AstXmlNode>
): Unit {
    if (!ilShow()) {
        return
    }
    val unit: IlUnit = ilExtractUnit(this.ilFunctionFor(fn, decl, facts, inferred), body, fn.file)
    // The dump shows the IL the emitter is about to read, so the fusion and the reuse run here too.
    this.ilFuseConcatUnit(unit)
    ilPromoteRefsUnit(unit, facts)
    ilReuseUnit(unit, *this.pureCallees)
    val text: Str = printIlUnit(unit)
    // `eprintln` adds a newline the dump already ends with: drop that byte.
    if (text.size() > 0) {
        eprintln(text.substr(0, text.size() - 1))
    }
}

fun Emitter.ilFunctionFor(
    fn: *CgFn, decl: *AstXmlNode, facts: *SemFacts,
    inferred: *Dictionary<Str, AstXmlNode>
): IlFunction {
    var symbol: Str = this.qualify(fn.packageName, xmlAttr(decl, AstNodeAttributeKind.Name))
    if (xmlIsEmpty(fn.receiver) && xmlAttr(decl, AstNodeAttributeKind.Name) == "main") {
        symbol = "main"
    }
    var info: IlFunction = IlFunction(
        decl, fn.receiver, symbol,
        Dictionary<Str, Str>(), xmlEmptyNode(), List<Str>(), List<AstXmlNode>(),
        "", Dictionary<Str, Bool>(), Dictionary<Str, AstXmlNode>(),
        facts, fn.templateParams, inferred
    )
    for (*entry in this.statics) {
        val typeNode: *AstXmlNode = xmlChildPtr(entry.decl, AstNodeKind.Type)
        if (!xmlIsEmpty(typeNode)) {
            info.statics.insert(
                xmlAttr(entry.decl, AstNodeAttributeKind.Name),
                ilTypeText(typeNode)
            )
        }
    }
    return info
}

// The extractor's view of a destructor body: like a machine method's, there is no `self`
// parameter - `this` is C++'s - so the class travels as `closureSymbol`/`selfDecl` and the
// frame's receiver slot is what `this` names.
fun Emitter.ilDestructorFor(
    fn: *CgFn, decl: *AstXmlNode, classDecl: AstXmlNode, symbol: Str, className: Str,
    facts: *SemFacts, inferred: *Dictionary<Str, AstXmlNode>
): IlFunction {
    var info: IlFunction = IlFunction(
        decl, xmlEmptyNode(), symbol,
        Dictionary<Str, Str>(), classDecl, List<Str>(), List<AstXmlNode>(),
        className, Dictionary<Str, Bool>(), Dictionary<Str, AstXmlNode>(),
        facts, fn.templateParams, inferred
    )
    for (*entry in this.statics) {
        val typeNode: *AstXmlNode = xmlChildPtr(entry.decl, AstNodeKind.Type)
        if (!xmlIsEmpty(typeNode)) {
            info.statics.insert(
                xmlAttr(entry.decl, AstNodeAttributeKind.Name),
                ilTypeText(typeNode)
            )
        }
    }
    return info
}

