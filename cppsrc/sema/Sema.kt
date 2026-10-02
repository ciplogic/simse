// Sema.kt
//
// The name/type-resolution pass: it consumes the AstXmlNode AST (schema in
// impl_specs/ast-xmlnode.md). Positional information comes from the
// `line`/`column` attributes.

package sema
import compiler

import parser
import common

// The read-only AstXmlNode accessors (xmlAttr, xmlChild, ...) live in
// cppsrc/common/xmlutil.kt and arrive through `import common`.

// `type` is an empty AstXmlNode when unknown.
data class ValueBinding(
    var isMutable: Bool,

    var checkAssign: Bool,
    var type: AstXmlNode
)

fun semaIsBuiltinType(name: *Str): Bool {
    if (name == "Int" || name == "Int8" || name == "Int16" || name == "Int32"
        || name == "Int64" || name == "Float32" || name == "Float64" || name == "Char"
        || name == "Str" || name == "Bool" || name == "Unit" || name == "List"
        || name == "Array" || name == "RawArray" || name == "Opt" || name == "Res"
        || name == "Dictionary" || name == "SmallVector" || name == "PList"
    ) {
        return true
    }
    return false
}

fun semaBuiltinGenericArity(name: *Str): Int {
    when (name) {
        "List", "Array", "RawArray", "Opt", "Res", "PList" -> {
            return 1
        }

        "Dictionary", "SmallVector" -> {
            return 2
        }
    }
    return -1
}

// A handle: `&T`, `*T`, or the `PList<T>` alias of `&List<T>`. The extractor spells
// the same rule as `ilIsHandleType`, the emitter as `Emitter.isHandleType`.
fun semaIsHandleType(typeNode: *AstXmlNode): Bool {
    if (xmlIsEmpty(typeNode)) {
        return false
    }
    val kind: AstNodeCategory = xmlKind(typeNode)
    if (kind == AstNodeCategory.TypeReference || kind == AstNodeCategory.TypePointer) {
        return true
    }
    return kind == AstNodeCategory.TypeGeneric
            && xmlAttr(typeNode, AstNodeAttributeKind.Name) == "PList"
}

// A *view*: `Span<T>` (`StrView` is its `Span<Char>` alias, cppsrc/rtl/StrView.kt), which
// owns no storage, so the C++ has no conversion from an owned value (`checkViewArgument`).
fun semaIsSpanType(node: *AstXmlNode): Bool {
    return xmlKind(node) == AstNodeCategory.TypeGeneric
            && xmlAttr(node, AstNodeAttributeKind.Name) == "Span"
}

// An owned value a view cannot be handed as it stands: `Str` or `List<T>`, after
// unwrapping handles (`*Str` names the same owned storage).
fun semaIsOwnedViewSource(actual: *AstXmlNode): Bool {
    var base: *AstXmlNode = actual
    var guard: Int = 0
    while (guard < 64) {
        guard = guard + 1
        val kind: AstNodeCategory = xmlKind(base)
        if (kind == AstNodeCategory.TypePointer || kind == AstNodeCategory.TypeReference) {
            val inner: *AstXmlNode = xmlChildPtr(base, AstNodeKind.Inner)
            if (xmlIsEmpty(inner)) {
                return false
            }
            base = inner
            continue
        }
        if (kind == AstNodeCategory.TypeGeneric
            && xmlAttr(base, AstNodeAttributeKind.Name) == "PList"
        ) {
            val args: List<AstXmlNode> = xmlChildren(base, AstNodeKind.TypeArg)
            if (args.size() == 0) {
                return false
            }
            base = * args [0]
            continue
        }
        break
    }
    if (xmlKind(base) == AstNodeCategory.TypeNamed
        && xmlAttr(base, AstNodeAttributeKind.Name) == "Str"
    ) {
        return true
    }
    return xmlKind(base) == AstNodeCategory.TypeGeneric
            && xmlAttr(base, AstNodeAttributeKind.Name) == "List"
}

// Structural unification of an extension receiver pattern against the actual type;
// references/pointers on the actual side are auto-dereferenced.
fun semaUnifyReceiver(pattern: *AstXmlNode, actual: *AstXmlNode, typeParams: *List<Str>): Bool {
    var actualPtr: *AstXmlNode = actual
    val pk: AstNodeCategory = xmlKind(pattern)
    if (pk != AstNodeCategory.TypeReference && pk != AstNodeCategory.TypePointer) {
        while ((xmlKind(actualPtr) == AstNodeCategory.TypeReference || xmlKind(actualPtr) == AstNodeCategory.TypePointer)
            && !xmlIsEmpty(xmlChildPtr(actualPtr, AstNodeKind.Inner))
        ) {
            actualPtr = xmlChildPtr(actualPtr, AstNodeKind.Inner)
        }
    }
    val ak: AstNodeCategory = xmlKind(actualPtr)
    when (pk) {
        AstNodeCategory.TypeIntLit -> {
            return ak == AstNodeCategory.TypeIntLit && xmlAttr(actualPtr, AstNodeAttributeKind.Text) == xmlAttr(
                pattern,
                AstNodeAttributeKind.Text
            )
        }

        AstNodeCategory.TypeNamed -> {
            if (xmlIsTypeParam(xmlAttr(pattern, AstNodeAttributeKind.Name), typeParams)) {
                return true
            }
            return ak == AstNodeCategory.TypeNamed && xmlAttr(actualPtr, AstNodeAttributeKind.Name) == xmlAttr(
                pattern,
                AstNodeAttributeKind.Name
            )
        }

        AstNodeCategory.TypeGeneric -> {
            if (xmlIsTypeParam(xmlAttr(pattern, AstNodeAttributeKind.Name), typeParams)) {
                return true
            }
            if (ak != AstNodeCategory.TypeGeneric || xmlAttr(actualPtr, AstNodeAttributeKind.Name) != xmlAttr(
                    pattern,
                    AstNodeAttributeKind.Name
                )
            ) {
                return false
            }
            val patternArgs: List<AstXmlNode> = xmlChildren(pattern, AstNodeKind.TypeArg)
            val actualArgs: List<AstXmlNode> = xmlChildren(actualPtr, AstNodeKind.TypeArg)
            if (patternArgs.size() != actualArgs.size()) {
                return false
            }
            var i: Int = 0
            while (i < patternArgs.size()) {
                if (!semaUnifyReceiver(patternArgs[i], actualArgs[i], typeParams)) {
                    return false
                }
                i = i + 1
            }
            return true
        }

        AstNodeCategory.TypeReference -> {
            if (ak == AstNodeCategory.TypeReference && !xmlIsEmpty(xmlChildPtr(actualPtr, AstNodeKind.Inner))
                && !xmlIsEmpty(xmlChildPtr(pattern, AstNodeKind.Inner))
            ) {
                return semaUnifyReceiver(
                    xmlChildPtr(pattern, AstNodeKind.Inner),
                    xmlChildPtr(actualPtr, AstNodeKind.Inner),
                    typeParams
                )
            }
            return false
        }

        AstNodeCategory.TypePointer -> {
            if (ak == AstNodeCategory.TypePointer && !xmlIsEmpty(xmlChildPtr(actualPtr, AstNodeKind.Inner))
                && !xmlIsEmpty(xmlChildPtr(pattern, AstNodeKind.Inner))
            ) {
                return semaUnifyReceiver(
                    xmlChildPtr(pattern, AstNodeKind.Inner),
                    xmlChildPtr(actualPtr, AstNodeKind.Inner),
                    typeParams
                )
            }
            return false
        }
    }
    return false
}

// Whether a name is one the `for` desugaring made: the `_sm_for1`/`_sm_index1`
// counters, which are not a user's to take.
fun semaIsForTemplateName(name: *Str): Bool {
    return name.startsWith("_sm_for")
}

// The schema's spelling of a type node ("List<Int>", "*Str", "(Int) -> Bool"), for
// diagnostics that have to name one.
fun semaTypeText(node: *AstXmlNode): Str {
    val kind: AstNodeCategory = xmlKind(node)
    when (kind) {
        AstNodeCategory.TypeIntLit -> {
            return xmlAttr(node, AstNodeAttributeKind.Text)
        }

        AstNodeCategory.TypeNamed -> {
            return xmlAttr(node, AstNodeAttributeKind.Name)
        }

        AstNodeCategory.TypeGeneric -> {
            return fmtStr(
                "|<|>",
                xmlAttr(node, AstNodeAttributeKind.Name),
                semaTypeTextList(xmlChildren(node, AstNodeKind.TypeArg))
            )
        }

        AstNodeCategory.TypeReference -> {
            return "&" + semaTypeText(xmlChildPtr(node, AstNodeKind.Inner))
        }

        AstNodeCategory.TypePointer -> {
            if (xmlIsRawPtrType(node)) {
                return "RawPtr"
            }
            return "*" + semaTypeText(xmlChildPtr(node, AstNodeKind.Inner))
        }

        AstNodeCategory.TypeFunction -> {
            return fmtStr(
                "(|) -> |",
                semaTypeTextList(xmlChildren(node, AstNodeKind.ParamType)),
                semaTypeText(xmlChildPtr(node, AstNodeKind.ReturnType))
            )
        }
    }
    return "?"
}

fun semaTypeTextList(types: *List<AstXmlNode>): Str {
    var out: Str
    val count: Int = types.size()
    if (count == 0) {
        return out
    }
    // Parts are rendered first so the buffer can be reserved once: `out + part` copies.
    var parts: List<Str> = List<Str>()
    var len: Int = 2 * (count - 1)
    var i: Int = 0
    while (i < count) {
        val text: Str = semaTypeText(types[i])
        len += text.size()
        parts.append(text)
        i = i + 1
    }
    out.reserve(len)
    i = 0
    while (i < count) {
        if (i > 0) {
            out.appendStr(", ")
        }
        out.appendStr(parts[i])
        i = i + 1
    }
    return out
}

// One participating file and its parsed Module; the Module carries the package that
// namespaces its declarations.
data class SemaInput(
    var fileName: Str,

    var module: AstXmlNode
)

data class Analyzer(
    var inputs: List<SemaInput>,

    var file: Str,
    var types: Dictionary<Str, AstXmlNode>,
    var functions: Dictionary<Str, List<AstXmlNode>>,
    var globalTypes: Dictionary<Str, AstXmlNode>,
    var globalFunctions: Dictionary<Str, List<AstXmlNode>>,
    var globalStatics: Dictionary<Str, AstXmlNode>,
    var packageDecls: Dictionary<Str, List<AstXmlNode>>,
    var declaredPackages: List<Str>,
    var scopes: List<Dictionary<Str, ValueBinding>>,
    var typeScopes: List<List<Str>>,
    var loopDepth: Int,
    var diags: List<Str>,

// The type names that declare `unInit` (`collectUninitTypes`): such a type has a C++
// destructor, so a value of it would run that destructor once per copy.
    var uninitTypes: Dictionary<Str, Bool>
) {
}


fun newAnalyzer(inputs: *List<SemaInput>): Analyzer {
    return Analyzer(
        inputs,
        "",
        Dictionary<Str, AstXmlNode>(),
        Dictionary<Str, List<AstXmlNode>>(),
        Dictionary<Str, AstXmlNode>(),
        Dictionary<Str, List<AstXmlNode>>(),
        Dictionary<Str, AstXmlNode>(),
        Dictionary<Str, List<AstXmlNode>>(),
        List<Str>(),
        List<Dictionary<Str, ValueBinding>>(),
        List<List<Str>>(),
        0,
        List<Str>(),
        Dictionary<Str, Bool>()
    )
}

// Analyzes the whole compilation and returns "<fileName>:<line>:<col>: <message>"
// diagnostics; an empty list means clean.
fun analyze(inputs: *List<SemaInput>): List<Str> {
    var analyzer: Analyzer = newAnalyzer(inputs)
    analyzer.run()
    return analyzer.diags
}

