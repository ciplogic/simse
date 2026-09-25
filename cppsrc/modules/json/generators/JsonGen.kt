// JsonGen.kt
//
// The `json` module's source generator (cppsrc/modules/json/generators/). A declaration
// `@SmGen("json") fun T.toJson<T>(): Str` - the one the module's `api.kt` carries, beside this
// file - asks the compiler for a JSON serializer, and this generator *builds that Simse source in
// code* rather than reading it from a resource: it is the generator kind that reads the
// program's own type structure.
//
// A module's generators live in a `generators/` subfolder by convention, and they are part of
// the compiler's own build - `cppsrc/modules/**` is scanned with the rest of the tree, and each
// generator registers itself (`specs/simse-md.md`). The generated *output* is merged in memory
// by the driver's reparse pass; nothing is written to disk.
//
// The API is an *extension*, not a free generic function, and that is forced by the emitter: a
// plain-name call is resolved by name and arity alone (`Emitter.findFunction`), so a set of
// `toJson(*T)` overloads cannot be typed at a call site - the argument's handle (a `*T` needs the
// value's address) is decided from the callee's parameter, and "the callee" is whichever overload
// the name+arity lookup happened to return. A member call is resolved by the *receiver's* type,
// the shape `toString` has, so `value.toJson()` picks the generated serializer for `value`'s own
// type - one overload per type, and the right one every time.
//
// The output is the transitive closure: a data class becomes an object of its fields'
// serializers, a scalar a leaf, recursing into sub-types. Each type is emitted **once** however
// many classes name it - a program with a hundred `Int` fields carries one `Int.toJson`, not a
// hundred - and a type the program already gives a `toJson` of its own is left alone. That is the
// "don't generate twice" rule: the set is keyed by the type's name, and the program's own
// declarations are read first.
//
// The generated module is package `rtl` (the driver's synthetic module, impl_specs/generators.md)
// and begins with one `import` per package a serialized class lives in, which is what lets it
// name a program-defined class. The fixed text - the quoting helper - is the `json:helpers`
// section of `cppsrc/rtl/_res.md`, so it stays readable Simse rather than an escaped literal.

package json

import common
import sourcegen

// Self-registration (impl_specs/generators.md). `false`/`false`: nothing is emitted for the
// declaration, not even a prototype - the generated extensions carry their own receiver.
val jsonGenRegistered: Bool = registerSourceGen("json", jsonGen, false, false)

fun jsonGen(ctx: *SourceGenContext): SourceGenTransform {
    if (ctx.phase == SourceGenPhase.Declare) {
        // The symbol a call reaches: the declaration's own name (`toJson`), which every generated
        // extension carries, so a call site needs nothing of this declaration.
        return SourceGenTransform(SourceTransformation.ChangedOutput, "")
    }
    if (ctx.phase != SourceGenPhase.Reparse) {
        // Nothing is *assembled*: the output is Simse the driver compiles with the program.
        return SourceGenTransform(SourceTransformation.None, "")
    }
    // One block for the whole program, however many `json` declarations there are.
    if (ctx.state.definitions.has("json:source")) {
        return SourceGenTransform(SourceTransformation.AlreadyExisting, "")
    }
    ctx.state.definitions.insert("json:source", ctx.name)
    val source: Str = jsonBuildSource(ctx)
    if (ctx.error.size() > 0) {
        return SourceGenTransform(SourceTransformation.None, "")
    }
    if (source == "") {
        return SourceGenTransform(SourceTransformation.None, "")
    }
    ctx.source = source
    return SourceGenTransform(SourceTransformation.ReparseRequired, "json:source")
}

// The program's type structure, as the generator reads it: the data classes by name, the package
// each lives in, the order they were declared in (so the output is deterministic), and the
// receiver types a program's own `toJson` already covers.
data class JsonTypes(
    var classes: Dictionary<Str, AstXmlNode>,
    var packages: Dictionary<Str, Str>,
    var order: List<Str>,
    var declared: Dictionary<Str, Bool>
)

// Whether a module asks for JSON: it spells `import json`.
fun jsonWants(module: *AstXmlNode): Bool {
    val imports: List<AstXmlNode> = xmlChildren(module, AstNodeKind.Import)
    for (*imp in imports) {
        if (xmlAttr(imp, AstNodeAttributeKind.Path) == "json") {
            return true
        }
    }
    return false
}

// The package the `json` module's own declarations live in - the trigger `toJson` and the
// generator's own sources. A data class declared there (the generator's `JsonTypes`, say) is the
// module's, not a program's to serialize, so it is skipped.
fun jsonModulePackage(ctx: *SourceGenContext): Str {
    var m: Int = ctx.state.preludeCount
    while (m < ctx.state.modules.size()) {
        val module: *AstXmlNode = *ctx.state.modules[m]
        val decls: List<AstXmlNode> = xmlDecls(module)
        for (*decl in decls) {
            if (decl.name == AstNodeKind.Function
                && xmlAttr(decl, AstNodeAttributeKind.Generator) == "json"
            ) {
                return xmlAttr(module, AstNodeAttributeKind.Package)
            }
        }
        m = m + 1
    }
    return ""
}

// Every *program* module (the prelude carries no type a program serializes), in module then
// declaration order. A `toJson` the program writes itself is recorded so the generator leaves it
// alone - the other half of "don't generate twice".
//
// **Only a program that imports `json` is serialized.** A module that does not - the compiler
// building itself, whose tree carries this module's `api.kt` - has nothing to serialize, so the
// generator emits nothing and stays out of its way.
fun jsonCollect(ctx: *SourceGenContext): JsonTypes {
    var classes: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
    var packages: Dictionary<Str, Str> = Dictionary<Str, Str>()
    var order: List<Str> = List<Str>()
    var declared: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    var wanted: Bool = false
    var m: Int = ctx.state.preludeCount
    while (m < ctx.state.modules.size()) {
        if (jsonWants(*ctx.state.modules[m])) {
            wanted = true
        }
        m = m + 1
    }
    if (!wanted) {
        return JsonTypes(classes, packages, order, declared)
    }
    m = ctx.state.preludeCount
    val modulePackage: Str = jsonModulePackage(ctx)
    while (m < ctx.state.modules.size()) {
        val module: *AstXmlNode = *ctx.state.modules[m]
        val pkg: Str = xmlAttr(module, AstNodeAttributeKind.Package)
        if (pkg == modulePackage) {
            // The generator's own module, not a program's data.
            m = m + 1
            continue
        }
        val decls: List<AstXmlNode> = xmlDecls(module)
        for (*decl in decls) {
            if (decl.name == AstNodeKind.DataClass) {
                val name: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
                if (classes.has(name)) {
                    // Two classes sharing a name cannot both be named by one `import`.
                    ctx.error = fmtStr(
                        "json: two data classes are named '|'; a serializer needs one", name
                    )
                    return JsonTypes(classes, packages, order, declared)
                }
                classes.insert(name, decl)
                packages.insert(name, pkg)
                order.append(name)
            }
            if (decl.name == AstNodeKind.Function
                && xmlAttr(decl, AstNodeAttributeKind.Name) == "toJson"
                && xmlAttr(decl, AstNodeAttributeKind.HasReceiver) == "true"
            ) {
                val receiver: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.Receiver)
                if (!xmlIsEmpty(receiver) && xmlKind(receiver) == AstNodeCategory.TypeNamed) {
                    declared.insert(xmlAttr(receiver, AstNodeAttributeKind.Name), true)
                }
            }
        }
        m = m + 1
    }
    return JsonTypes(classes, packages, order, declared)
}

// The scalars a serializer can be a leaf of. A numeric type is its own `toString`; a `Bool` is
// the JSON literal; a `Str`/`Char` is quoted with the escapes JSON needs.
fun jsonIsScalar(name: *Str): Bool {
    return name == "Int" || name == "Int8" || name == "Int16" || name == "Int32"
            || name == "Int64" || name == "Float32" || name == "Float64"
            || name == "Bool" || name == "Str" || name == "Char"
}

fun jsonScalarBody(name: *Str): Str {
    var out: Str = fmtStr("fun |.toJson(): Str {\n", name)
    if (name == "Bool") {
        out.appendStr("    if (this) {\n        return \"true\"\n    }\n    return \"false\"\n")
    } else if (name == "Str") {
        out.appendStr("    return jsonQuoted(this)\n")
    } else if (name == "Char") {
        out.appendStr("    return jsonQuoted(this.toString())\n")
    } else {
        out.appendStr("    return this.toString()\n")
    }
    out.appendStr("}\n")
    return out
}

// One object: `{` then `"field":` + the field's own serializer per field, `,` between.
fun jsonClassBody(name: *Str, fields: *List<AstXmlNode>): Str {
    var out: Str = fmtStr("fun |.toJson(): Str {\n    var out: Str = \"{\"\n", name)
    var i: Int = 0
    while (i < fields.size()) {
        val fieldName: Str = xmlAttr(*fields[i], AstNodeAttributeKind.Name)
        if (i > 0) {
            out.appendStr("    out.append(',')\n")
        }
        out.appendStr("    out.append('\"')\n")
        out.appendStr("    out.appendStr(\"")
        out.appendStr(fieldName)
        out.appendStr("\")\n")
        out.appendStr("    out.append('\"')\n    out.append(':')\n")
        out.appendStr("    out.appendStr(this.")
        out.appendStr(fieldName)
        out.appendStr(".toJson())\n")
        i = i + 1
    }
    out.appendStr("    out.append('}')\n    return out\n}\n")
    return out
}

// One type ensured: its serializer emitted once, then its field types (so the closure is
// transitive). The key is inserted *before* the recursion, which is what terminates a class that
// reaches itself.
fun jsonEnsure(
    name: *Str, ctx: *SourceGenContext, types: *JsonTypes,
    keys: *Dictionary<Str, Bool>, bodies: *List<Str>, flags: *Dictionary<Str, Bool>
): Unit {
    if (keys.has(name) || types.declared.has(name)) {
        return
    }
    keys.insert(name, true)
    if (jsonIsScalar(name)) {
        bodies.append(jsonScalarBody(name))
        if (name == "Str" || name == "Char") {
            flags.insert("quoted", true)
        }
        return
    }
    val decl: *AstXmlNode = types.classes.getPtr(name)
    if (decl == null) {
        ctx.error = fmtStr("json: '|' is not a data class or a supported scalar", name)
        return
    }
    if (xmlCount(decl, AstNodeKind.TypeParam) > 0) {
        ctx.error = fmtStr("json: the generic data class '|' is not supported yet", name)
        return
    }
    val fields: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Field)
    bodies.append(jsonClassBody(name, *fields))
    var i: Int = 0
    while (i < fields.size()) {
        val fieldName: Str = xmlAttr(*fields[i], AstNodeAttributeKind.Name)
        val typeNode: *AstXmlNode = xmlChildPtr(*fields[i], AstNodeKind.Type)
        if (xmlIsEmpty(typeNode) || xmlKind(typeNode) != AstNodeCategory.TypeNamed) {
            // A `List<T>`, a `*T` field or a type alias: the closure needs a serializer for a
            // shape that has no name of its own yet.
            ctx.error = fmtStr(
                "json: field '|' of '|' is not a data class or a scalar (a generated serializer for a generic or pointer field is not implemented yet)",
                fieldName, name
            )
            return
        }
        jsonEnsure(xmlAttr(typeNode, AstNodeAttributeKind.Name), ctx, types, keys, bodies, flags)
        if (ctx.error.size() > 0) {
            return
        }
        i = i + 1
    }
}

// The whole generated block: the imports a program-defined class needs, the quoting helper when
// a `Str`/`Char` is serialized, then one serializer per type.
fun jsonBuildSource(ctx: *SourceGenContext): Str {
    val types: JsonTypes = jsonCollect(ctx)
    if (ctx.error.size() > 0) {
        return ""
    }
    var keys: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    var bodies: List<Str> = List<Str>()
    var flags: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    var i: Int = 0
    while (i < types.order.size()) {
        jsonEnsure(types.order[i], ctx, *types, *keys, *bodies, *flags)
        if (ctx.error.size() > 0) {
            return ""
        }
        i = i + 1
    }
    if (bodies.size() == 0) {
        return ""
    }

    // `import` per package a *serialized* class lives in (a scalar is `rtl`'s own, already in
    // scope). Deduped, in declaration order.
    var imports: List<Str> = List<Str>()
    i = 0
    while (i < types.order.size()) {
        val name: Str = types.order[i]
        if (keys.has(name)) {
            val pkg: *Str = types.packages.getPtr(name)
            if (pkg != null && * pkg != "" && * pkg != "rtl" && !imports.contains(* pkg)) {
                imports.append(*pkg)
            }
        }
        i = i + 1
    }

    var out: Str = Str()
    i = 0
    while (i < imports.size()) {
        out.appendStr("import ")
        out.appendStr(imports[i])
        out.append('\n')
        i = i + 1
    }
    if (imports.size() > 0) {
        out.append('\n')
    }
    if (flags.has("quoted")) {
        val helper: Str = sourceGenResText(ctx.state, "json:helpers")
        if (helper == "") {
            ctx.error = "json: the json:helpers resource is missing (a section of cppsrc/rtl/_res.md)"
            return ""
        }
        out.appendStr(helper)
    }
    i = 0
    while (i < bodies.size()) {
        out.appendStr(bodies[i])
        i = i + 1
    }
    return out
}
