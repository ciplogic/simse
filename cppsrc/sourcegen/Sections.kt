// Sections.kt
//
// The amalgamation's sections (impl_specs/generators.md): the ordered list of named
// pieces the emitted file is assembled from, and the one sink every writer adds to -
// the emitter a section at a time, a generator through `add`.
//
// This type lives in the generators' own package, not in `codegen`, for the boundary a
// generator is held to: a generator may read and write *data structures* (the AST nodes,
// the resources, this sink), but it must not call the compiler's code - the parser, the
// emitter, the semantic pass - because that is what would break a generator whenever a
// compiler API changes (impl_specs/generators.md, "What a generator may touch").
//
// A section is a name, the text the emitter wrote into it (in order), and the named
// items a *generator* added. Rendering walks the sections in order and, within one,
// writes the section's own text first and its items afterwards (in the dictionary's
// order). That split is what keeps this step byte-neutral: the emitter appends its
// lines, so its output cannot depend on the item dictionary, while a generator's
// addition lands where the section list says - not where the emission happens to be.
//
// The predefined sections are the emitter's own assembly stages, in the order `run`
// writes them. `forward` is the one the emitter leaves empty: it is where a generated
// *declaration* goes, so it lands before every type and body. A name the emitter does
// not know - a generator's own machinery - is appended at the end of the list, so it
// renders after the program, where it is out of the way.
//
// `add` is last-write-wins: a key that already exists is replaced. Two generators that
// both define a helper for the same key therefore collide, and the second one cannot
// know what the first did - documented behaviour, not a bug (impl_specs/generators.md,
// "Sections and named entries"). A generator that cares checks `has` first.

package sourcegen

// One section: its name, the emitter's own lines, and the named additions.
data class NamedSection(
    var name: Str,
    var text: Str,
    var items: Dictionary<Str, Str>
)

// The sections, and where the emitter is writing. `current` is an index into
// `sections`: a new section is always appended, so an index never moves.
data class Sections(
    var sections: List<NamedSection>,

    var current: Int
) {
    // The index of `name`, or -1. One linear scan over a handful of entries.
    fun indexOf(name: Str): Int {
        var i: Int = 0
        while (i < this.sections.size()) {
            if (this.sections[i].name == name) {
                return i
            }
            i = i + 1
        }
        return -1
    }

    // The section called `name`, created and appended when it does not exist yet.
    fun section(name: Str): Int {
        val found: Int = this.indexOf(name)
        if (found >= 0) {
            return found
        }
        this.sections.append(NamedSection(name, Str(), Dictionary<Str, Str>()))
        return this.sections.size() - 1
    }

    // Makes `name` the section the emitter writes into.
    fun begin(name: Str): Unit {
        this.current = this.section(name)
    }

    // Appends to the current section, in place.
    fun appendText(text: Str): Unit {
        val target: *NamedSection = *this.sections[this.current]
        target.text.appendStr(text)
    }

    fun appendLine(indent: Str, text: Str): Unit {
        val target: *NamedSection = *this.sections[this.current]
        target.text.appendStr(indent)
        target.text.appendStr(text)
        target.text.append('\n')
    }

    // A generator's addition under `key`; an existing key's text is replaced.
    fun add(name: Str, key: Str, text: Str): Unit {
        val at: Int = this.section(name)
        val target: *NamedSection = *this.sections[at]
        target.items.insert(key, text)
    }

    fun has(name: Str, key: Str): Bool {
        val target: *NamedSection = *this.sections[this.section(name)]
        return target.items.has(key)
    }

    fun get(name: Str, key: Str): Opt<Str> {
        val target: *NamedSection = *this.sections[this.section(name)]
        return target.items.get(key)
    }

    // The names of the sections, in the order they render - what a generator asks
    // about: the resource key `<resource>:<name>` is looked up for each of them.
    fun names(): List<Str> {
        var out: List<Str> = List<Str>()
        for (*section in this.sections) {
            out.append(section.name)
        }
        return out
    }

    // `text` appended as a block of its own: a blank line first, unless the output already
    // ends with one (or is empty). Nothing when `text` is empty, so an empty section costs
    // nothing. The check is what keeps one blank line from becoming two where a writer
    // already ended its text with one.
    fun appendBlock(out: *Str, text: Str): Unit {
        if (text.size() == 0) {
            return
        }
        if (out.size() > 0 && !out.endsWith("\n\n")) {
            out.append('\n')
        }
        out.appendStr(text)
    }

    // The whole amalgamation: every section in order, its own text then its items.
    //
    // Every block starts fresh - a section's own text and each item alike - so a generated
    // text never runs into the line before it, which is what keeps a resource's C++
    // readable in the amalgamation (`impl_specs/generators.md`).
    fun render(): Str {
        var out: Str = Str()
        for (*section in this.sections) {
            this.appendBlock(*out, section.text)
            val keys: List<Str> = section.items.keys()
            var k: Int = 0
            while (k < keys.size()) {
                this.appendBlock(*out, section.items.get(keys[k]).value())
                k = k + 1
            }
        }
        return out
    }
}

// The sections every compilation starts with, in the order the emitter assembles them:
// includes, support, profile, strings, resources, forward, types, statics, prototypes,
// init, bodies (impl_specs/generators.md). Each one is a *phase* of the assembly, which
// is what makes the order meaningful for a generator:
//
//   includes   the banner and the `#include`s
//   support    generated text the *preamble* needs (a table's decoder, a clock's
//              declaration); it renders before everything that follows, and the emitter
//              writes nothing into it itself
//   profile    the profiler's runtime, which a `--profile` build emits and which needs
//              what `support` declared
//   strings    the string-literal table and its initializer, which needs `support`'s
//              decoder
//   resources  the resource table and its installer
//   forward    generated declarations that must precede the program's types and bodies
//   types      the forward type declarations and the type definitions
//   statics    file-level static storage
//   prototypes every function's prototype
//   init       the generated static-initialization pass
//   bodies     every function body, and a generated definition that is not a declaration
//
// `current` starts at `includes`, the emitter's first stage.
fun sourceGenNewSections(): Sections {
    var list: List<NamedSection> = List<NamedSection>()
    list.append(NamedSection("includes", Str(), Dictionary<Str, Str>()))
    list.append(NamedSection("support", Str(), Dictionary<Str, Str>()))
    list.append(NamedSection("profile", Str(), Dictionary<Str, Str>()))
    list.append(NamedSection("strings", Str(), Dictionary<Str, Str>()))
    list.append(NamedSection("resources", Str(), Dictionary<Str, Str>()))
    list.append(NamedSection("forward", Str(), Dictionary<Str, Str>()))
    list.append(NamedSection("types", Str(), Dictionary<Str, Str>()))
    list.append(NamedSection("statics", Str(), Dictionary<Str, Str>()))
    list.append(NamedSection("prototypes", Str(), Dictionary<Str, Str>()))
    list.append(NamedSection("init", Str(), Dictionary<Str, Str>()))
    list.append(NamedSection("bodies", Str(), Dictionary<Str, Str>()))
    return Sections(list, 0)
}

// ---- the sink -------------------------------------------------------------
//
// One per compilation, and a static for the same reason the scanner's tables are
// (`cppsrc/lex/Scanner.kt`): the compiler is one compilation per process, and a static is
// what lets the emitter *and* every generator hold the sink the same way - as a pointer
// (`*Sections`) rather than as a value, which is also what keeps the emitted C++ legal
// whichever package the pointer is named from.
//
// The driver resets it before anything writes (`sourceGenBegin`), and the emitter takes
// its pointer from `sourceGenSink` when it is built.

var sourceGenOutput: Sections = sourceGenNewSections()

// The assembly the emitter writes into and the generators add to.
fun sourceGenSink(): *Sections {
    return * sourceGenOutput
}

// A fresh assembly: what `sourceGenBegin` leaves, so nothing of a previous compilation
// (there is one per process today, but the compiler should not depend on that) survives.
fun sourceGenResetSink(): Unit {
    sourceGenOutput = sourceGenNewSections()
}
