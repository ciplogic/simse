// CgSections.kt
//
// The amalgamation's sections (impl_specs/generators.md): the ordered list of named
// pieces the emitted file is assembled from, and the one sink every writer adds to -
// the emitter a section at a time, a generator through `add`.
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

package codegen

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
        val target: *NamedSection = *this.sections[this.section(name)]
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

    // The whole amalgamation: every section in order, its own text then its items.
    fun render(): Str {
        var out: Str = Str()
        for (*section in this.sections) {
            out.appendStrPtr(*section.text)
            val keys: List<Str> = section.items.keys()
            var k: Int = 0
            while (k < keys.size()) {
                out.appendStr(section.items.get(keys[k]).value())
                k = k + 1
            }
        }
        return out
    }
}

// The sections every compilation starts with, in the order the emitter assembles them:
// includes, forward, types, statics, prototypes, init, bodies
// (impl_specs/generators.md). `forward` starts empty - it is for generated
// declarations - and `current` starts at `includes`, the emitter's first stage.
fun cgNewSections(): Sections {
    var list: List<NamedSection> = List<NamedSection>()
    list.append(NamedSection("includes", Str(), Dictionary<Str, Str>()))
    list.append(NamedSection("forward", Str(), Dictionary<Str, Str>()))
    list.append(NamedSection("types", Str(), Dictionary<Str, Str>()))
    list.append(NamedSection("statics", Str(), Dictionary<Str, Str>()))
    list.append(NamedSection("prototypes", Str(), Dictionary<Str, Str>()))
    list.append(NamedSection("init", Str(), Dictionary<Str, Str>()))
    list.append(NamedSection("bodies", Str(), Dictionary<Str, Str>()))
    return Sections(list, 0)
}
