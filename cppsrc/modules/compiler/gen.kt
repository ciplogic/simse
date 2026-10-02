// gen.kt
//
// The compiler module's generator interface (impl_specs/generators.md): what a source
// generator is told, and what it answers. A generator reads and writes *data* - the AST
// nodes (`xml*`), the resources, the `Sections` sink - and must not call the compiler's own
// code; that boundary is why it is handed only the sink and the state.
//
// The declarations live here, next to the AST (`cppsrc/modules/compiler/astxml.kt`), so a
// module's generator source names the compiler surface with one import (`import compiler`).
// The registry, the compilation a generator looks at and the built-in generators are the
// same package (`cppsrc/compiler/`), so one import covers everything a generator names.

package compiler


// A generator's answer: what it did, and the key it is filed under - the generator's own
// spelling of what it produced (a resource section's name). `FullCompiledState.definitions`
// remembers it, which is what a generator producing the same thing twice reads back.
data class SourceGenTransform(
    var change: SourceTransformation,
    var key: Str
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
