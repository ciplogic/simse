// TypeCheck.kt
//
// The assignability check (impl_specs/linear-form-plan.md, steps 1 and 1b): the compiler
// computes the types and compares them - an argument against its parameter, an initializer
// against its declaration, a value against its assignment's target, a return against the
// declared return, a member against its class - and a comparison that fails is an *error*,
// the compiler's, at the expression's own line, in the language's names. Before 2026-11-27
// it computed the types and declined to compare, so an ordinary type mistake reached
// cl.exe as an error in the generated code (`Span<Char>` where `Str` was written).
// `--no-check-types` turns the comparison off. It runs where the two types already are -
// the extractor's argument conversion and statement walk - and reports through `tcReport`,
// one line per finding, which the driver prints and fails on. The comparison is
// `semArgumentReaches`, the one definition the conversion and the check share, so a shape
// the conversion converts is exactly a shape the check allows.

package sema
import compiler

import common

var semCheckTypesFlag: Bool = true

fun semCheckTypes(): Bool {
    return semCheckTypesFlag
}

fun semSetCheckTypes(value: Bool): Unit {
    semCheckTypesFlag = value
}

// The reports, one line each in `<file>:<line>:<column>: <message>` form - what the driver
// prints through `diagRender`. A finding with no position reports nothing: a report the
// reader cannot see is noise.
var tcReports: List<Str> = List<Str>()

fun tcReport(file: *Str, line: Int, column: Int, message: *Str): Unit {
    if (line <= 0) {
        return
    }
    tcReports.append(`@file:@line:@column: @message`)
}

fun tcReportLines(): List<Str> {
    return tcReports
}

// The articles of the language: the type's name decides (`an Int`, `an Opt`, `an Array`;
// `a Str`, `a List`; `a Unit` - the letter is not the sound), and a handle or a callable
// spells a symbol, where `a` reads.
fun tcArticleText(typeNode: *AstXmlNode): Str {
    val kind: AstNodeCategory = xmlKind(typeNode)
    if (kind != AstNodeCategory.TypeNamed && kind != AstNodeCategory.TypeGeneric) {
        return "a"
    }
    val name: Str = xmlAttr(typeNode, AstNodeAttributeKind.Name)
    if (name.size() == 0) {
        return "a"
    }
    val first: Char = name[0]
    if (first == 'A' || first == 'E' || first == 'I' || first == 'O') {
        return "an"
    }
    return "a"
}

// The stringification builtins, whose argument conversion is the emitter's business, not
// the call's: `print`/`println` take any value (the runtime overloads spell the number),
// and `fmtStr`/`fmtStrWith` take parts of any type (the expansion appends a numeric part
// through its own helper). A part the rules type as a number is the interface, not a
// mistake.
fun tcIsStringifyBuiltin(name: *Str): Bool {
    return name == "print" || name == "println" || name == "fmtStr" || name == "fmtStrWith"
}

// Whether a type node names one of the given type parameters, at any depth. The linear
// package has its own walk (`ilTypeMentions`); this is the same question for the checks.
fun tcTypeMentionsAny(typeNode: *AstXmlNode, names: *List<Str>): Bool {
    if (names.size() == 0) {
        return false
    }
    val kind: AstNodeCategory = xmlKind(typeNode)
    if (kind == AstNodeCategory.TypeNamed || kind == AstNodeCategory.TypeGeneric) {
        val name: Str = xmlAttr(typeNode, AstNodeAttributeKind.Name)
        for (param in names) {
            if (param == name) {
                return true
            }
        }
    }
    for (*child in typeNode.Children) {
        if (tcTypeMentionsAny(child, names)) {
            return true
        }
    }
    return false
}

fun tcIsRawPtr(typeNode: *AstXmlNode): Bool {
    return xmlKind(typeNode) == AstNodeCategory.TypeNamed
            && xmlAttr(typeNode, AstNodeAttributeKind.Name) == "RawPtr"
}

// The numeric scalars, which convert to one another implicitly - both ways, the way the C++
// the emitter writes computes it (a narrowing is a warning there, `C4244`, and spelling the
// narrowing explicitly is `impl_specs/linear-form-plan.md` step 2's business). `Bool` is
// not in the set: a truth value is not a number.
fun tcIsNumericName(name: *Str): Bool {
    return name == "Int" || name == "Int8" || name == "Int16" || name == "Int32"
            || name == "Int64" || name == "Float32" || name == "Float64" || name == "Char"
}

// Whether a value of type `actual` reaches a position declared `param`: the conversion
// table (specs/functions.md, "Handles at a call") as one question, so the conversion
// (`IlExtractor.convertArgument`) and the check read the same definition. Only the *handle*
// is inferred - a `*T`/`&T` position reads a same-pointee value or handle through, a `*T`
// position takes a value's address, a `&T` boxes - so the *pointees* are what is compared,
// through the callee's own type parameters, a derived-to-base value included (specs/
// declarations.md). A `Str` reaches a view parameter as a view of itself (`spanOfStr`), the
// type pass's own overload rule (`SemInfer.argReachesParam`). Any `*T` widens to `RawPtr`
// (specs/memory-model.md). A type the rules cannot name, one that names the enclosing
// body's own type parameter (a generic body resolves at instantiation), or a parameter that
// is one of the callee's bare type parameters (the receiver binds it) reaches: nothing to
// compare, nothing to report.
fun semArgumentReaches(
    callee: *AstXmlNode, param: *AstXmlNode, actual: *AstXmlNode,
    types: *Dictionary<Str, AstXmlNode>, scopeParams: *List<Str>
): Bool {
    if (xmlIsEmpty(param) || xmlIsEmpty(actual)) {
        return true
    }
    if (semIsBareTypeParam(callee, param)) {
        return true
    }
    if (tcTypeMentionsAny(param, scopeParams) || tcTypeMentionsAny(actual, scopeParams)) {
        return true
    }
    val wanted: AstXmlNode = semPointeeOf(param)
    val given: AstXmlNode = semPointeeOf(actual)
    if (xmlIsEmpty(wanted) || xmlIsEmpty(given)) {
        return true
    }
    // Both sides through their alias chains (`StrView` is `Span<Char>` at every position).
    val wantedR: AstXmlNode = semResolveTypeAlias(wanted, *types)
    val givenR: AstXmlNode = semResolveTypeAlias(given, *types)
    if (semIsStrType(givenR) && semaIsSpanType(wantedR)) {
        return true
    }
    // ... and the other direction: a view reaches a `Str` position as the `Str` of itself
    // (the C++ converting constructor the runtime spells).
    if (semaIsSpanType(givenR) && semIsStrType(wantedR)) {
        return true
    }
    // A parameter that names a type nothing declares - not a builtin, not a program type -
    // is a type parameter this call's own signature does not declare, so the receiver or
    // the call's explicit instantiation binds it (`Opt2<T>.initByValue(value: T)`, the
    // construction convention): the sides cannot be compared here, and the construction is
    // what ties them.
    if (xmlKind(wantedR) == AstNodeCategory.TypeNamed) {
        val wantedName: Str = xmlAttr(wantedR, AstNodeAttributeKind.Name)
        if (!tcIsBuiltinName(wantedName) && types.getPtr(wantedName) == null) {
            return true
        }
    }
    // Two numeric scalars reach each other (the implicit conversion above).
    if (xmlKind(wantedR) == AstNodeCategory.TypeNamed && xmlKind(givenR) == AstNodeCategory.TypeNamed
        && tcIsNumericName(xmlAttr(wantedR, AstNodeAttributeKind.Name))
        && tcIsNumericName(xmlAttr(givenR, AstNodeAttributeKind.Name))
    ) {
        return true
    }
    if (semUnifyType(wantedR, givenR, xmlTypeParamNames(callee))) {
        return true
    }
    // A derived value stands where its base is wanted.
    val givenName: Str = semClassTypeName(*types, givenR)
    val wantedName: Str = semClassTypeName(*types, wantedR)
    if (givenName != "" && wantedName != "" && semDerivesFromName(*types, givenName, wantedName)) {
        return true
    }
    if (xmlKind(actual) == AstNodeCategory.TypePointer && tcIsRawPtr(wantedR)) {
        return true
    }
    // Two callable shapes answer each other when their contracts do: the parameters in
    // order and the return. `semUnifyType` does not walk a `TypeFunction`, and a callable
    // *value* argument (a variable of type `(Int) -> Int`) reaches a callable parameter
    // exactly when the shapes agree - the same check the lambda contract makes at emission.
    if (xmlKind(wantedR) == AstNodeCategory.TypeFunction
        && xmlKind(givenR) == AstNodeCategory.TypeFunction
    ) {
        return tcCallablesAgree(wantedR, givenR, types, scopeParams)
    }
    return false
}

// Two callable types agree when the parameter counts match, each parameter pair agrees
// (the same reach question, in order), and the returns agree.
fun tcCallablesAgree(
    wanted: *AstXmlNode, given: *AstXmlNode,
    types: *Dictionary<Str, AstXmlNode>, scopeParams: *List<Str>
): Bool {
    val wantedParams: List<AstXmlNode> = xmlChildren(wanted, AstNodeKind.ParamType)
    val givenParams: List<AstXmlNode> = xmlChildren(given, AstNodeKind.ParamType)
    if (wantedParams.size() != givenParams.size()) {
        return false
    }
    var i: Int = 0
    while (i < wantedParams.size()) {
        if (!semArgumentReaches(xmlEmptyNode(), wantedParams[i], givenParams[i], types, scopeParams)) {
            return false
        }
        i++
    }
    val wantedRet: AstXmlNode = xmlChild(wanted, AstNodeKind.ReturnType)
    val givenRet: AstXmlNode = xmlChild(given, AstNodeKind.ReturnType)
    if (xmlIsEmpty(wantedRet) || xmlIsEmpty(givenRet)) {
        return true // a return the rules cannot name is not compared (the lambda contract's rule)
    }
    return semArgumentReaches(xmlEmptyNode(), wantedRet, givenRet, types, scopeParams)
}

// The scalar types, which have no members at all: every operation on them is a call of an
// extension (`toString`, `size`), so a member *read* is a mistake whatever the name.
fun tcIsScalarName(name: *Str): Bool {
    return name == "Int" || name == "Int8" || name == "Int16" || name == "Int32"
            || name == "Int64" || name == "Float32" || name == "Float64" || name == "Bool"
            || name == "Char"
}

// The type names the language itself spells (the scalars, the containers and handles, the
// machine and callable shapes): anything a position names that is neither one of these nor
// a type the program declares is a type parameter somewhere else's to bind.
fun tcIsBuiltinName(name: *Str): Bool {
    return tcIsScalarName(name) || name == "Str" || name == "RawPtr" || name == "Unit"
            || name == "List" || name == "Array" || name == "Span" || name == "StrView"
            || name == "Dictionary" || name == "Opt" || name == "Res" || name == "PList"
            || name == "SmallVector" || name == "Range" || name == "SteppedRange"
}

// The members a `union class` generates beside its arms (specs/declarations.md;
// `SemaAnalyze.unionGeneratedName`): reads and calls of these name the generated surface,
// never a field.
fun tcIsUnionGenerated(member: *Str): Bool {
    return member == "getTypeOf" || member == "isOfType" || member == "setNone"
            || member == "initByValue" || member == "destroyActive" || member == "copyFrom"
            || member == "moveFrom" || member.startsWith("get<") || member.startsWith("set<")
            || member.startsWith("get") || member.startsWith("set")
}

// Whether `decl` - a class or a union - declares `member` as a field, a method of its own
// or of its base chain, or (a union) on the generated surface. `Opt`/`Res` spell their
// payload and error members in both cases (specs/core-types.md).
fun tcDeclaresMember(facts: *SemFacts, decl: *AstXmlNode, typeName: *Str, member: *Str): Bool {
    if (!xmlIsEmpty(semFindField(*facts.types, *decl, member))) {
        return true
    }
    var current: *AstXmlNode = decl
    var guard: Int = 0
    while (current != null && guard < 64) {
        guard++
        for (*method in xmlChildren(*current, AstNodeKind.Function)) {
            if (xmlAttr(method, AstNodeAttributeKind.Name) == member) {
                return true
            }
        }
        current = semaBaseDecl(*facts.types, *current)
    }
    if (xmlAttr(*decl, AstNodeAttributeKind.IsUnionClass) == "true" && tcIsUnionGenerated(member)) {
        return true
    }
    if ((typeName == "Opt" || typeName == "Res")
        && (member == "value" || member == "Value" || member == "error" || member == "Error"
            || member == "hasValue" || member == "isOk")
    ) {
        return true
    }
    return false
}
