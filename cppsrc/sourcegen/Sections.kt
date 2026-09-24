// Sections.kt
//
// The amalgamation's sink (impl_specs/generators.md): an ordered list of named pieces, the
// emitter adding a section at a time and a generator through `add`. Rendering writes each
// section's own text then its items, which is what keeps the step byte-neutral whichever
// writer put the text there.
//
// The predefined sections are the emitter's assembly *phases*; a name the emitter does not
// know is appended at the end. `add` is last write wins: a generator that cares checks `has`.

package sourcegen

data class NamedSection(
    var name: Str,
    var text: Str,
    var items: Dictionary<Str, Str>
)

// `current` is an index into `sections`: a new section is always appended, so it never moves.
data class Sections(
    var sections: List<NamedSection>,

    var current: Int
) {
    fun indexOf(name: *Str): Int {
        var i: Int = 0
        while (i < this.sections.size()) {
            if (this.sections[i].name == name) {
                return i
            }
            i = i + 1
        }
        return -1
    }

    fun section(name: *Str): Int {
        val found: Int = this.indexOf(name)
        if (found >= 0) {
            return found
        }
        this.sections.append(NamedSection(name, Str(), Dictionary<Str, Str>()))
        return this.sections.size() - 1
    }

    fun begin(name: *Str): Unit {
        this.current = this.section(name)
    }

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

    fun add(name: *Str, key: *Str, text: *Str): Unit {
        val at: Int = this.section(name)
        val target: *NamedSection = *this.sections[at]
        target.items.insert(key, text)
    }

    fun has(name: *Str, key: *Str): Bool {
        val target: *NamedSection = *this.sections[this.section(name)]
        return target.items.has(key)
    }

    fun get(name: *Str, key: *Str): Opt<Str> {
        val target: *NamedSection = *this.sections[this.section(name)]
        return target.items.get(key)
    }

    // In render order: the resource key `<resource>:<name>` is looked up for each.
    fun names(): List<Str> {
        var out: List<Str> = List<Str>()
        for (*section in this.sections) {
            out.append(section.name)
        }
        return out
    }

    // A block of its own: a blank line first, unless the output already ends with one.
    fun appendBlock(out: *Str, text: Str): Unit {
        if (text.size() == 0) {
            return
        }
        if (out.size() > 0 && !out.endsWith("\n\n")) {
            out.append('\n')
        }
        out.appendStr(text)
    }

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

// The predefined sections in assembly order (`impl_specs/generators.md`); `current` starts at
// `includes`, the emitter's first stage.
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

// One per compilation, held as a static so the emitter and every generator take it the same
// way - as `*Sections`, passed and never read through (`impl_specs/generators.md`). The
// driver resets it before anything writes (`sourceGenBegin`).

var sourceGenOutput: Sections = sourceGenNewSections()

fun sourceGenSink(): *Sections {
    return * sourceGenOutput
}

// A fresh assembly, so nothing of a previous compilation survives.
fun sourceGenResetSink(): Unit {
    sourceGenOutput = sourceGenNewSections()
}
