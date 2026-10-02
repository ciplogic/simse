// Codegen.kt
//
// The C++ emitter: the AstXmlNode AST (impl_specs/ast-xmlnode.md) goes in, one
// amalgamated translation unit comes out. An empty type node means "no/unknown
// type".

package codegen
import compiler

import sema
import common
import linear
import optimizations
import profiling
import resources

// One parsed input. `prelude` inputs are collected but emitted only when they carry a
// body (the RTL's declarations are natives, whose C++ is the header's).
data class CgInput(
    var fileName: Str,

    var module: AstXmlNode,
    var prelude: Bool
)

// A function/method to emit, with its receiver type (empty for plain functions).
// `package` picks the emitted-symbol prefix: `ns<index>_`, or none for `rtl`.
data class CgFn(
    var decl: AstXmlNode,

    var receiver: AstXmlNode,
    var file: Str,
    var templateParams: List<Str>,
    var prelude: Bool,
    var packageName: Str,
    var isMethod: Bool,

    // Read *once* at collection, not walked from the node on every lookup walk (every
    // call site tests these three on each candidate): a declaration does not change
    // while emission reads it.
    var name: Str,
    var isNative: Bool,
    var hasBody: Bool,

    // The `data` modifier: the writer's claim that the function is pure (no side effects,
    // the result a function of its arguments). `pureCallees` collects those names.
    var isPure: Bool,

    // The non-receiver parameter count, read once like the flags above: two same-name extensions
    // of one receiver (the `initByValue` pair) are told apart by arity.
    var paramCount: Int
)

// A `native fun` declaration to emit once at the top (and call by symbol).
data class CgNativeDecl(
    var decl: AstXmlNode,

    var file: Str,
    var symbol: Str,
    var prelude: Bool
)

// An explicit-`this` native extension; the receiver pattern selects the overload.
data class CgNativeExt(
    var symbol: Str,

    var receiver: AstXmlNode,
    var returnType: AstXmlNode,
    var typeParams: List<Str>,
    // The number of non-receiver arguments, so two overloads of one name resolve by arity.
    var argCount: Int
)

// A file-level static (`Var`, specs/statics.md): storage plus an optional
// initializer, emitted under its package's prefix like any other declaration.
data class CgStatic(
    var decl: AstXmlNode,

    var packageName: Str,
    var file: Str
)


// How a name's storage is reached, for `.` vs `->`, `*x` vs `x.get()`, `copy`.
enum class NameKind { Value, Shared, Pointer }

// Reads the parts only; each is appended through the borrow the pointer `for` hands
// out (`appendStrPtr`), so no element is copied. A one-byte separator keeps its own
// path; everything else is `common.joinStrs`.
fun cgJoin(parts: *List<Str>, separator: *Str): Str {
    if (separator.size() == 1) {
        return cgJoinChar(parts, separator[0])
    }
    return joinStrs(parts, separator)
}

// A receiver argument followed by the call's own arguments, comma-separated, and the
// receiver alone when there are none. The receiver is a prefix rather than an element,
// so it cannot just be prepended to the list `cgJoin` takes.
fun cgReceiverArgs(receiver: Str, args: *List<Str>): Str {
    if (args.size() == 0) {
        return receiver
    }
    return fmtStr("|, |", receiver, cgJoin(args, ", "))
}

fun cgJoinChar(parts: *List<Str>, separator: Char): Str {
    var out: Str = ""
    if (parts.size() == 0) {
        return out
    }
    out.reserve(cgJoinLength(parts, 1))
    var first: Bool = true
    for (*part in parts) {
        if (!first) {
            out.append(separator)
        }
        out.appendStrPtr(part)
        first = false
    }
    return out
}

fun cgJoinLength(parts: *List<Str>, separatorLen: Int): Int {
    val count: Int = parts.size()
    var len: Int = 0
    if (count > 1) {
        len = separatorLen * (count - 1)
    }
    for (*part in parts) {
        len += part.size()
    }
    return len
}

fun cgIndent(level: Int): Str {
    var out: Str = ""
    out.reserve(level * 4)
    var i: Int = 0
    while (i < level) {
        out.appendStr("    ")
        i = i + 1
    }
    return out
}

// The RTL type-name list lives with the semantics that share it: `semIsRtlTypeName`
// (sema/TypeInfer.kt).

// One argument of a `@SmGen` attribute, from the comma-joined `GeneratorArgs`; an index
// outside the list is the empty string.
fun cgGeneratorArg(args: *Str, index: Int): Str {
    if (args.size() == 0 || index < 0) {
        return ""
    }
    val parts: List<Str> = args.split(",")
    if (index >= parts.size()) {
        return ""
    }
    return parts[index]
}

// Binary operator precedence for wrapping. The levels are the shared table
// (`common.opPrecedenceRank`), and `11`/`12` are the unary and postfix positions (at or
// above one needs no parentheses); the parser's `binaryBindingPower` reads the same table.
fun cgPrecedence(e: *AstXmlNode): Int {
    if (xmlKind(e) == AstNodeCategory.ExprBinary) {
        val rank: Int = opPrecedenceRank(xmlAttr(e, AstNodeAttributeKind.Op))
        if (rank < 0) {
            return 1
        }
        return rank
    }
    val kind: AstNodeCategory = xmlKind(e)
    when (kind) {
        AstNodeCategory.ExprUnary, AstNodeCategory.ExprDeref, AstNodeCategory.ExprCopy -> {
            return 11
        }

        AstNodeCategory.ExprRef, AstNodeCategory.ExprCall, AstNodeCategory.ExprIndex,
        AstNodeCategory.ExprMember -> {
            return 12
        }
    }
    return 13
}

// Whether a top-level `main` takes the argv form: a single `List<Str>` parameter.
fun cgIsMainArgs(decl: *AstXmlNode): Bool {
    val params: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Param)
    if (params.size() != 1) {
        return false
    }
    val paramType: *AstXmlNode = xmlChildPtr(params[0], AstNodeKind.Type)
    if (xmlIsEmpty(paramType)) {
        return false
    }
    if (xmlKind(paramType) != AstNodeCategory.TypeGeneric || xmlAttr(
            paramType,
            AstNodeAttributeKind.Name
        ) != "List"
    ) {
        return false
    }
    val args: List<AstXmlNode> = xmlChildren(paramType, AstNodeKind.TypeArg)
    if (args.size() != 1) {
        return false
    }
    return xmlKind(args[0]) == AstNodeCategory.TypeNamed && xmlAttr(args[0], AstNodeAttributeKind.Name) == "Str"
}

data class Emitter(
    var inputs: List<CgInput>,

// The resources the program carries, each key and value already spelled as the C++ literal
// holding its bytes (`resources.resStoredLiterals`, which owns the escape rules); a
// compile-only (`!`) section is absent. A list of `Str` rather than the entries, to stay
// ahead of the resources package's own types in file order (`CgStringTable.kt`).
    var resourceStored: List<Str>,

    var sections: *Sections,
    var failed: Bool,
    var error: Str,
    var curFile: Str,
    var curPrelude: Bool,

// The names the program calls, for the prelude rule in `emitFunctions`.
    var referencedNames: Dictionary<Str, Bool>,

// The types that declare an `initByValue` extension: a construction of one reaches that
// convention, which the AST walk records (the setter call is the lowering's, not the AST's).
    var initByValueTypes: Dictionary<Str, Bool>,

// The program's string literals; the walk below pools them (CgStringTable.kt).
    var literals: StringTable,

// The types the program names, for the prelude rule's per-container part: an `iter` per
// container, and a program that iterates one should not carry the others' machines.
    var referencedTypes: Dictionary<Str, Bool>,
    var types: Dictionary<Str, AstXmlNode>,
    var enumNames: Dictionary<Str, Bool>,
    var dataClassNames: Dictionary<Str, Bool>,
    var functions: List<CgFn>,
    var receiverFnNames: Dictionary<Str, Bool>,
    var nativeDecls: List<CgNativeDecl>,

// The two tables a generator's answer is registered in (impl_specs/generators.md; the
// pass lives in `cppsrc/compiler`).
    var nativeSymbols: Dictionary<Str, Str>,
    var nativeExtensions: Dictionary<Str, List<CgNativeExt>>,
    var activeTypeParams: Dictionary<Str, Bool>,
    var nameKinds: Dictionary<Str, NameKind>,
    var localTypes: Dictionary<Str, AstXmlNode>,
    var selfKind: NameKind,
    var selfType: AstXmlNode,
    var curReturnType: AstXmlNode,
    var nsPrefixes: Dictionary<Str, Str>,
    var typePackages: Dictionary<Str, Str>,
    var statics: List<CgStatic>,
    var staticsByName: Dictionary<Str, CgStatic>,

// The classes this unit constructs, versus the ones already written out: a closure class
// is emitted just above the body that builds it, once.
    var closureSymbols: Dictionary<Str, Bool>,
    var emittedClosures: Dictionary<Str, Bool>,
    var emittedYieldables: Dictionary<Str, Bool>,

// The decl of the machine being emitted (the last one registered): the extractor needs it
// as the class `this` is an instance of.
    var machineDecl: AstXmlNode,

// Why an instruction could not be expressed: set where the attempt gives up, read by the
// caller that turns it into a reason line.
    var ilWhy: Str,

// Inside a closure class's method (or a machine's): the receiver is C++'s `this`,
// because a member function has no `self` parameter.
    var inClosureMethod: Bool,

// The measured bodies' names, in the index order the profiler's constant table uses, and
// the memo that hands each name its index (`--profile`; empty when the flag is off).
    var profNames: List<Str>,
    var profNameIndex: Dictionary<Str, Int>,

// The callees whose repeated call the reuse pass may merge (`linear/ReusePure.kt`): the
// names of functions declared `data` (pure), filled as the declarations are collected. The
// length accessors are declarations too (`size`/`count`, cppsrc/rtl/rtl.kt), so nothing is
// listed here by hand. Keyed by *name*: a `data` mark is a promise, and a call is folded
// only between two calls naming the same callee and the same argument.
    var pureCallees: Dictionary<Str, Bool>,

// The coloring pass's result (impl_specs/async.md): the names that can suspend, each to the type
// a call to it answers (an empty node for `Unit`). A call whose callee is here is a suspension,
// and the `suspend` lowering turns the body holding it into a task.
    var asyncFns: Dictionary<Str, AstXmlNode>
) {
}

fun newEmitter(inputs: *List<CgInput>, resourceStored: *List<Str>): Emitter {
    return Emitter(
        inputs,
        resourceStored,
        sourceGenSink(),
        false,
        "",
        "",
        false,
        Dictionary<Str, Bool>(),
        Dictionary<Str, Bool>(),
        StringTable(List<Str>(), Dictionary<Str, Int>()),
        Dictionary<Str, Bool>(),
        Dictionary<Str, AstXmlNode>(),
        Dictionary<Str, Bool>(),
        Dictionary<Str, Bool>(),
        List<CgFn>(),
        Dictionary<Str, Bool>(),
        List<CgNativeDecl>(),
        Dictionary<Str, Str>(),
        Dictionary<Str, List<CgNativeExt>>(),
        Dictionary<Str, Bool>(),
        Dictionary<Str, NameKind>(),
        Dictionary<Str, AstXmlNode>(),
        NameKind.Value,
        xmlEmptyNode(),
        xmlEmptyNode(),
        Dictionary<Str, Str>(),
        Dictionary<Str, Str>(),
        List<CgStatic>(),
        Dictionary<Str, CgStatic>(),
        Dictionary<Str, Bool>(),
        Dictionary<Str, Bool>(),
        Dictionary<Str, Bool>(),
        xmlEmptyNode(),
        "",
        false,
        List<Str>(),
        Dictionary<Str, Int>(),
        Dictionary<Str, Bool>(),
        Dictionary<Str, AstXmlNode>()
    )
}

// Amalgamates every input into one translation unit, byte-identical for identical inputs; on
// failure the error is formatted "<file>:<line>:<col>: <message>".
//
// `resourceStored` is what the program carries from its `_res.md` files
// (`resources.resStoredLiterals`, specs/resources.md), already spelled as the C++ literal that
// holds its bytes, in the order the files were read.
fun emitProgram(inputs: *List<CgInput>, resourceStored: *List<Str>): Res<Str> {
    var emitter: Emitter = newEmitter(inputs, resourceStored)
    return emitter.run()
}

