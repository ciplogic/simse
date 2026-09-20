// GenTypes.kt
//
// The source generators' vocabulary (impl_specs/generators.md): what a generator is told,
// what it may read and change, and what it answers. The manager (`SourceGen.kt`) dispatches
// by the name an `@SmGen` attribute spelled, and one file per generator holds the function
// it runs (`CppGen.kt`, `ResGen.kt`, `KtGen.kt`) - the shape `cppsrc/lex/Scanner.kt` gives
// its token matchers: a table built by a `make...()`, one `add...` line per entry.
//
// **What a generator may touch.** A generator reads and writes *data* - the AST nodes
// (`AstXmlNode` and the `xml*` accessors), the resources the compiler read, the output
// sections (`Sections`), and the containers the RTL provides - and it must not call the
// compiler's own code: the parser, the semantic pass, the emitter, the driver. That is
// what keeps a generator from breaking every time a compiler API changes, and it is why
// the sink and the state in this package are the *only* things a generator is handed: a
// generator that wants the program to do something says so in its answer
// (`SourceGenTransform`) and the manager does it.
//
// A generator is asked in *phases*, because what it can answer depends on how much of the
// program is known by then. `Declare` is asked while the program is being collected - the
// symbol a call reaches has to exist before any body is emitted - `Reparse` is the driver's
// pass over the sources a generator produced (before the program is checked, since what
// comes out of it is compiled with the program), and `Emit` is asked once every body has
// been emitted, where the *reach set* is complete: that is what decides whether a prelude
// generator's text lands in the program at all.

package sourcegen

import common
import resources

// What a generator did, in the terms its caller acts on.
//
//   None             nothing was generated: the declaration's C++ is elsewhere but already
//                    linked (a header's), so the caller still emits its prototype
//   ChangedOutput    text was written - C++ into the sections, or Simse source for a
//                    reparse. The generated text declares the symbol itself, so the
//                    declaration gets no prototype of its own
//   ReparseRequired  the generator wrote Simse source (`ctx.source`) that the compiler has
//                    to parse and compile with the program
//   AlreadyExisting  the key `SourceGenTransform.key` is already defined in this
//                    compilation, so nothing was generated - the call still reaches the
//                    symbol, which is what makes a generator idempotent
enum class SourceTransformation {
    None,
    ChangedOutput,
    ReparseRequired,
    AlreadyExisting
}

// When a generator is asked.
enum class SourceGenPhase {
    Declare,
    Reparse,
    Emit
}

// A generator's answer: what it did, and the key what it did is filed under. The key is
// the generator's own spelling of what it produced (`jsonSerialize<Int>`, a resource
// section's name), and `FullCompiledState.definitions` remembers it: a generator that
// would produce the same thing twice asks that dictionary first and answers
// `AlreadyExisting` instead of emitting a second copy.
data class SourceGenTransform(
    var change: SourceTransformation,
    var key: Str
)

// One declaration a generator answered, kept so the `Emit` phase can ask again: by then the
// program's calls are known, and a *prelude* declaration nothing reaches is skipped.
data class SourceGenRequest(
    var generator: Str,
    var declaration: AstXmlNode,
    var parameters: List<Str>,
    var symbol: Str,
    var declName: Str,
    var fileName: Str,
    var prelude: Bool
)

// The compilation a generator looks at: the files that were read (the prelude first), the
// resources they carry, the *compiler's own* resources, and what the generators have
// produced so far. One per compilation - the driver fills it (`sourceGenBegin`) and the
// manager keeps it, which is why it is a static here rather than a value threaded through
// the stages: a generator sees it whole, and the stages between them do not have to carry
// it.
data class FullCompiledState(
    var fileNames: List<Str>,
    var modules: List<AstXmlNode>,
    var preludeCount: Int,
    var resources: List<ResourceItem>,
    var compilerResources: List<ResourceItem>,
    var definitions: Dictionary<Str, Str>,
    var requests: List<SourceGenRequest>
)

// Everything one dispatch may read or change. `symbol` is what a call reaches (its own
// declaration's name until a generator says otherwise), `source` is the Simse source a
// `ReparseRequired` answer hands to the driver, and `error` is how a generator reports a
// problem it found - the manager reports it in the caller's own terms (a positioned
// diagnostic while the program is being collected, a message from the driver's pass).
//
// `sections` and `reachedNames` are null in the `Reparse` phase: the driver's pass runs
// before the emitter exists, so a generator must not touch them there.
data class SourceGenContext(
    var name: Str,
    var phase: SourceGenPhase,
    var declaration: AstXmlNode,
    var declName: Str,
    var parameters: List<Str>,
    var symbol: Str,
    var source: Str,
    var error: Str,
    var fileName: Str,
    var prelude: Bool,
    var state: *FullCompiledState,
    var sections: *Sections,
    var reachedNames: *Dictionary<Str, Bool>
) {
    // Whether the program reaches this declaration: by the name a call spells, or by the
    // symbol a call reaches. A *prelude* declaration nothing reaches is skipped, so a
    // program pays only for the generated text it calls - the rule a prelude function with
    // a body follows.
    fun isReached(): Bool {
        if (this.reachedNames == null) {
            return true
        }
        if (this.reachedNames.has(this.declName)) {
            return true
        }
        return this.reachedNames.has(this.symbol)
    }

    // The generator's own argument `index`, or "" - `@SmGen("res", "listops", "sym")` has
    // "listops" at 0 and "sym" at 1 (`parameters` holds what follows the generator's name).
    fun parameter(index: Int): Str {
        if (index < 0 || index >= this.parameters.size()) {
            return ""
        }
        return this.parameters[index]
    }
}

// The function a generator is: it reads the context, writes what it produced into it, and
// answers what it did.
typealias OnSourceGen = (*SourceGenContext) -> SourceGenTransform

// One registered generator: the name an `@SmGen` attribute writes, the function that runs
// for it, and the two facts about its declarations that the *caller* acts on (a generator
// says what it produced; how the compiler treats a declaration of this kind is declared
// here, once, rather than re-derived per dispatch).
data class SourceGenerator(
    var name: Str,
    var transform: OnSourceGen,

    // The declaration gets a prototype of its own: its C++ is elsewhere but already linked
    // (a header's), so the call sites need the declaration.
    var declaresPrototype: Bool,

    // A declaration with an explicit `this` is registered as a receiver extension.
    var registersReceiver: Bool
)

// The `index`-th argument of an `@SmGen` attribute, or "" - the attribute's arguments as
// the parser joined them (`,` between them, string literals without their quotes), which is
// the one spelling this package reads. It is `cgGeneratorArg`'s rule
// (`cppsrc/codegen/Codegen.kt`), kept here so a generator does not depend on the emitter.
fun sourceGenArg(args: *Str, index: Int): Str {
    if (args.size() == 0 || index < 0) {
        return ""
    }
    val parts: List<Str> = args.split(",")
    if (index >= parts.size()) {
        return ""
    }
    return parts[index]
}

// The attribute arguments after the generator's name, as one list.
fun sourceGenArgs(args: *Str): List<Str> {
    var out: List<Str> = List<Str>()
    if (args.size() == 0) {
        return out
    }
    out = args.split(",")
    return out
}

// `text` without one wrapping pair of double quotes: an attribute argument is a literal as
// written, and a string literal keeps its quotes in the AST (`specs/attributes.md`).
// `cgUnquote`'s rule, for the same reason `sourceGenArg` is here.
fun sourceGenUnquote(text: Str): Str {
    if (text.size() >= 2 && text.substr(0, 1) == "\"" && text.substr(text.size() - 1, 1) == "\"") {
        return text.substr(1, text.size() - 2)
    }
    return text
}

// ---- the resources a generator reads --------------------------------------
//
// The lookup both generators that read text share (`KtGen.kt` reads Simse source, `ResGen.kt`
// C++): the *tree being compiled* first - the `_res.md` files under its module roots, which
// is what lets the RTL's own generated C++ be a resource file, since while the compiler is
// being built the tree's file is the newer one - and **the compiler's own resources**
// second.
//
// The second list is the `_res.md` files *beside the compiler's prelude*, read from disk by
// the driver at run time - exactly as the prelude's own `.kt` files are - and it is what
// hands every other program the RTL's C++ without that program carrying the RTL's resource
// file. It used to be the compiler's *pooled* table instead (the RTL's text embedded in the
// compiler's own pool, read back through the `Resources` API); the file is the source of
// truth, the compiler already reads the directory for the prelude, and carrying a second
// copy of 23 KB of that text as a string pool cost more than it bought - which is why
// `cppsrc/rtl/_res.md`'s sections are marked `!` (`specs/resources.md`, "What the program
// carries") and the RTL's `Resources` type is now a program-facing API only.

// True when either list carries `key`. A key may hold an *empty* text - a section with
// nothing under it - so this and the text below are separate questions.
fun sourceGenResHas(state: *FullCompiledState, key: *Str): Bool {
    if (resHas(state.resources, key)) {
        return true
    }
    return resHas(state.compilerResources, key)
}

// The text `key` holds - "" when neither list carries it, which `sourceGenResHas` tells
// apart from a key that carries nothing.
fun sourceGenResText(state: *FullCompiledState, key: *Str): Str {
    if (resHas(state.resources, key)) {
        return resValueOf(state.resources, key)
    }
    return resValueOf(state.compilerResources, key)
}
