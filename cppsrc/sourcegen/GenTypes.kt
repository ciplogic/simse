// GenTypes.kt
//
// The source generators' vocabulary (impl_specs/generators.md): what a generator is told,
// what it may touch, and what it answers. A generator reads and writes *data* - the AST
// nodes (`xml*`), the resources, the `Sections` sink - and must not call the compiler's own
// code; that boundary is why it is handed only the sink and the state.
//
// A generator is asked in *phases*: `Declare` while the program is collected (the symbol a
// call reaches must exist before any body), `Reparse` for source the driver will compile,
// and `Emit` once every body is emitted, when the reach set decides what lands.

package sourcegen

import common
import resources

// What a generator did, in the terms its caller acts on. `None` means the C++ is linked
// elsewhere (the caller still emits the prototype); `AlreadyExisting` means `key` was already
// defined, so nothing was generated but the call still reaches the symbol.
enum class SourceTransformation {
    None,
    ChangedOutput,
    ReparseRequired,
    AlreadyExisting
}

enum class SourceGenPhase {
    Declare,
    Reparse,
    Emit
}

// A generator's answer: what it did, and the key it is filed under - the generator's own
// spelling of what it produced (a resource section's name). `FullCompiledState.definitions`
// remembers it, which is what a generator producing the same thing twice reads back.
data class SourceGenTransform(
    var change: SourceTransformation,
    var key: Str
)

// Kept so the `Emit` phase can ask again, when the program's calls are known.
data class SourceGenRequest(
    var generator: Str,
    var declaration: AstXmlNode,
    var parameters: List<Str>,
    var symbol: Str,
    var declName: Str,
    var fileName: Str,
    var prelude: Bool
)

// The compilation a generator looks at: the files read (the prelude first), the resources they
// carry, the *compiler's own* resources, and what generators have produced so far.
data class FullCompiledState(
    var fileNames: List<Str>,
    var modules: List<AstXmlNode>,
    var preludeCount: Int,
    var resources: List<ResourceItem>,
    var compilerResources: List<ResourceItem>,
    var definitions: Dictionary<Str, Str>,
    var requests: List<SourceGenRequest>
)

// Everything one dispatch may read or change. `sections` and `reachedNames` are null in the
// `Reparse` phase (the emitter does not exist yet), so a generator must not touch them there.
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
    // By the name a call spells, or the symbol a call reaches; a *prelude* declaration nothing
    // reaches is skipped.
    fun isReached(): Bool {
        if (this.reachedNames == null) {
            return true
        }
        if (this.reachedNames.has(this.declName)) {
            return true
        }
        return this.reachedNames.has(this.symbol)
    }

    // Argument `index` (`parameters` holds what follows the generator's name), or "".
    fun parameter(index: Int): Str {
        if (index < 0 || index >= this.parameters.size()) {
            return ""
        }
        return this.parameters[index]
    }
}

typealias OnSourceGen = (*SourceGenContext) -> SourceGenTransform

// One registered generator: the name an `@SmGen` writes, its function, and the two facts the
// *caller* acts on, declared once rather than re-derived per dispatch.
data class SourceGenerator(
    var name: Str,
    var transform: OnSourceGen,

    // The declaration gets a prototype of its own: its C++ is elsewhere but already linked.
    var declaresPrototype: Bool,

    // A declaration with an explicit `this` registers as a receiver extension.
    var registersReceiver: Bool
)

// The `index`-th argument of an `@SmGen` attribute, or "". The arguments are the parser's
// join (`,` between, string literals unquoted); this is `cgGeneratorArg`'s rule, kept here so
// a generator does not depend on the emitter.
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

fun sourceGenArgs(args: *Str): List<Str> {
    var out: List<Str> = List<Str>()
    if (args.size() == 0) {
        return out
    }
    out = args.split(",")
    return out
}

// `text` without one wrapping pair of double quotes: a string literal keeps its quotes in the
// AST (`specs/attributes.md`). `cgUnquote`'s rule, kept here for the same reason.
fun sourceGenUnquote(text: Str): Str {
    if (text.size() >= 2 && text[0] == '\"' && text[text.size() - 1] == '\"') {
        return text.substr(1, text.size() - 2)
    }
    return text
}

// The lookup KtGen and ResGen share: the *tree being compiled* first (`_res.md` under its
// module roots), the compiler's own resources second (the `_res.md` beside the prelude, read
// from disk). While the compiler is being built the tree's file is the newer one.

// A key may hold an *empty* text, so this and `sourceGenResText` are separate questions.
fun sourceGenResHas(state: *FullCompiledState, key: *Str): Bool {
    if (resHas(state.resources, key)) {
        return true
    }
    return resHas(state.compilerResources, key)
}

// "" when neither list carries `key`, which `sourceGenResHas` tells apart from carrying nothing.
fun sourceGenResText(state: *FullCompiledState, key: *Str): Str {
    if (resHas(state.resources, key)) {
        return resValueOf(state.resources, key)
    }
    return resValueOf(state.compilerResources, key)
}
