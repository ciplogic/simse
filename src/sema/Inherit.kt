// Inherit.kt
//
// Inheritance, as the language defines it (specs/declarations.md, "Inheritance"): a class's
// `: Base` clause is *field appending* - the derived object is the base's fields first,
// then the class's own - so the base's layout is the start of the derived's, and a derived
// value may stand where the base is expected: by value as a copy of the prefix, by raw
// pointer as the same address. The relation is one attribute on the class (the parser wrote
// it); this file is the one place every stage reads it - the checker, the type rules, the
// linear extractor and the emitter - so the flattening rule has a single spelling.

package sema
import compiler

import common

// The base class a declaration names, or "" - one plain name, as the parser recorded it.
fun semaBaseName(decl: *AstXmlNode): Str {
    return xmlAttr(decl, AstNodeAttributeKind.Base)
}

// The declaration of `decl`'s base in `types` - visible names for the checker, every
// declaration for the emitter and the extractor. Null when the class has no base, or when
// the name is not a type (the checker reports the latter separately).
fun semaBaseDecl(types: *Dictionary<Str, AstXmlNode>, decl: *AstXmlNode): *AstXmlNode {
    val name: Str = semaBaseName(decl)
    if (name == "") {
        return null
    }
    return types.getPtr(name)
}

// Whether the class `derived` (a declaration) has `baseName` on its chain of bases. A guard
// stops a cycle: the walk runs on declarations the checker may not have reported yet, and it
// must not hang.
fun semDerivesFrom(types: *Dictionary<Str, AstXmlNode>, derived: *AstXmlNode, baseName: *Str): Bool {
    var current: *AstXmlNode = derived
    var guard: Int = 0
    while (current != null && guard < 64) {
        guard = guard + 1
        if (xmlAttr(*current, AstNodeAttributeKind.Name) == *baseName) {
            return true
        }
        current = semaBaseDecl(types, *current)
    }
    return false
}

// The same question asked by name, where the caller only has the type's name. An *exact*
// match is not a derivation: it needs no conversion.
fun semDerivesFromName(types: *Dictionary<Str, AstXmlNode>, derived: *Str, base: *Str): Bool {
    if (*derived == "" || *base == "" || *derived == *base) {
        return false
    }
    val decl: *AstXmlNode = types.getPtr(derived)
    if (decl == null || decl.name != AstNodeKind.DataClass) {
        return false
    }
    return semDerivesFrom(types, decl, base)
}

// The class's fields in layout order: the base chain's fields first (the root's ahead of the
// nearer base's), then the class's own. A class with no base answers its own fields, so this
// is the declaration's flattened field list - what the struct emits, what a constructor's
// arguments match, and what a value conversion copies.
fun semAllFields(types: *Dictionary<Str, AstXmlNode>, decl: *AstXmlNode): List<AstXmlNode> {
    var out: List<AstXmlNode> = List<AstXmlNode>()
    val base: *AstXmlNode = semaBaseDecl(types, decl)
    if (base != null) {
        for (field in semAllFields(types, *base)) {
            out.append(field)
        }
    }
    for (*field in xmlChildren(decl, AstNodeKind.Field)) {
        out.append(*field)
    }
    return out
}

// The field `member` as `decl` sees it - its own or one of the chain's - or an empty node
// when no declaration has it. This is what makes `p3.x` reach a `Point2D` field, and what
// gives the member a type at every read.
fun semFindField(types: *Dictionary<Str, AstXmlNode>, decl: *AstXmlNode, member: *Str): AstXmlNode {
    var current: *AstXmlNode = decl
    var guard: Int = 0
    while (current != null && guard < 64) {
        guard = guard + 1
        for (*field in xmlChildren(*current, AstNodeKind.Field)) {
            if (xmlAttr(field, AstNodeAttributeKind.Name) == *member) {
                return *field
            }
        }
        current = semaBaseDecl(types, *current)
    }
    return xmlEmptyNode()
}

// The data class a type names, looking through a pointer or a handle: `Derived`, `*Derived`
// and `&Derived` all answer "Derived". Anything else - a built-in, a container, a type
// parameter, an unknown name - answers "". A conversion decision is the only reader, so an
// unresolvable name must answer nothing rather than guess.
fun semClassTypeName(types: *Dictionary<Str, AstXmlNode>, typeNode: *AstXmlNode): Str {
    var current: AstXmlNode = typeNode
    var guard: Int = 0
    while (!xmlIsEmpty(current) && guard < 64) {
        guard = guard + 1
        val kind: AstNodeCategory = xmlKind(current)
        if (kind == AstNodeCategory.TypeReference || kind == AstNodeCategory.TypePointer) {
            current = xmlChild(current, AstNodeKind.Inner)
            continue
        }
        if (kind != AstNodeCategory.TypeNamed && kind != AstNodeCategory.TypeGeneric) {
            return ""
        }
        val name: Str = xmlAttr(current, AstNodeAttributeKind.Name)
        val decl: *AstXmlNode = types.getPtr(name)
        if (decl == null || decl.name != AstNodeKind.DataClass) {
            return ""
        }
        return name
    }
    return ""
}

// The declaration-time rules of inheritance (specs/declarations.md, "Inheritance"): only a
// plain `data class` may derive, its base must be one too - visible, non-generic, without an
// `unInit` (which would make it handle-only) - the chain must be acyclic and wholly visible
// where the class is declared, no field name may repeat across the chain (the layout is one
// flat list, so a repeat would be a collision and not a shadow), and neither may a method
// name at the same arity (overriding is not part of the form).
fun Analyzer.checkInheritance(decl: *AstXmlNode): Unit {
    val name: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
    val baseName: Str = semaBaseName(decl)
    if (baseName == "") {
        return
    }
    val line: Int = xmlLine(decl)
    val column: Int = xmlColumn(decl)
    if (xmlAttr(decl, AstNodeAttributeKind.IsUnionClass) == "true"
        || xmlAttr(decl, AstNodeAttributeKind.IsRefClass) == "true"
        || xmlAttr(decl, AstNodeAttributeKind.IsNativeClass) == "true"
    ) {
        this.diag(
            line, column,
            `'@name' cannot derive from '@baseName': only a plain 'data class' can`
        )
        return
    }
    val base: *AstXmlNode = this.types.getPtr(baseName)
    if (base == null) {
        this.diag(
            line, column,
            `'@baseName' is not a type in scope: import its package or check the spelling`
        )
        return
    }
    if (xmlKind(base) != AstNodeCategory.DataClass) {
        this.diag(
            line, column,
            `the base of '@name' must be a data class; '@baseName' is not`
        )
        return
    }
    if (xmlAttr(*base, AstNodeAttributeKind.IsUnionClass) == "true"
        || xmlAttr(*base, AstNodeAttributeKind.IsRefClass) == "true"
        || xmlAttr(*base, AstNodeAttributeKind.IsNativeClass) == "true"
    ) {
        this.diag(
            line, column,
            `'@baseName' cannot be a base: only a plain 'data class' can`
        )
        return
    }
    if (semaDeclaresUninit(*base)) {
        this.diag(
            line, column,
            `'@baseName' cannot be a base: it declares 'unInit', so it is handle-only`
        )
        return
    }
    if (xmlCount(*base, AstNodeKind.TypeParam) > 0) {
        this.diag(
            line, column,
            `'@baseName' cannot be a base: a base class takes no type parameters`
        )
        return
    }
    // Every link of the chain must be visible here: the layout is flattened from the whole
    // chain, so a link the checker cannot see would be a field list the program cannot name.
    var chain: List<Str> = List<Str>()
    chain.append(name)
    var current: Str = baseName
    var guard: Int = 0
    while (current != "" && guard < 64) {
        guard = guard + 1
        if (chain.contains(current)) {
            this.diag(
                line, column,
                `the base chain of '@name' is a cycle through '@current'`
            )
            return
        }
        val step: *AstXmlNode = this.types.getPtr(current)
        if (step == null) {
            this.diag(
                line, column,
                `the base chain of '@name' reaches '@current', which is not in scope: import its package`
            )
            return
        }
        chain.append(current)
        current = semaBaseName(*step)
    }
    var seen: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    for (*field in semAllFields(*this.types, decl)) {
        val fieldName: Str = xmlAttr(field, AstNodeAttributeKind.Name)
        if (seen.has(fieldName)) {
            this.diag(
                xmlLine(field), xmlColumn(field),
                `'@name' already has a field '@fieldName' (inherited or declared)`
            )
            continue
        }
        seen.insert(fieldName, true)
    }
    // A method name may not repeat down the chain at the same arity: two bodies under one
    // signature would be one call with no rule to pick between them. Overriding is not part
    // of the form; an inherited method is inherited as it stands.
    for (*method in xmlChildren(decl, AstNodeKind.Function)) {
        val methodName: Str = xmlAttr(method, AstNodeAttributeKind.Name)
        val arity: Int = xmlCount(method, AstNodeKind.Param)
        var above: *AstXmlNode = semaBaseDecl(*this.types, decl)
        var methodGuard: Int = 0
        while (above != null && methodGuard < 64) {
            methodGuard = methodGuard + 1
            for (*baseMethod in xmlChildren(*above, AstNodeKind.Function)) {
                if (xmlAttr(baseMethod, AstNodeAttributeKind.Name) == methodName
                    && xmlCount(baseMethod, AstNodeKind.Param) == arity
                ) {
                    val baseNameText: Str = xmlAttr(*above, AstNodeAttributeKind.Name)
                    this.diag(
                        xmlLine(method), xmlColumn(method),
                        `'@name.@methodName' would replace '@baseNameText.@methodName': overriding is not part of inheritance`
                    )
                    break
                }
            }
            above = semaBaseDecl(*this.types, *above)
        }
    }
}
