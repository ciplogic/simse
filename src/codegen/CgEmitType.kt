// CgEmitType.kt
//
// Emitting the declarations: forward types, classes, enums, type aliases, name
// collection, and the native/static/resource/string tables. Extension methods on
// `Emitter` (Codegen.kt).

package codegen
import compiler

import sema
import common
import linear
import optimizations
import profiling
import resources


// Storage for every file-level static, value-initialized so a read before the generated
// pass below fills in the initializers yields the empty value, not indeterminate data
// (specs/statics.md). A prelude static is emitted only when a reached body reads it.
fun Emitter.emitStatics(): Unit {
    for (*entry in this.statics) {
        if (entry.prelude && !this.staticReached(entry)) {
            continue
        }
        this.curFile = entry.file
        val typeNode: *AstXmlNode = xmlChildPtr(entry.decl, AstNodeKind.Type)
        val storage: Str = this.qualify(entry.packageName, xmlAttr(entry.decl, AstNodeAttributeKind.Name))
        if (this.failed) {
            return
        }
        this.sourceComment(entry.decl)
        val typeText: Str = this.type(typeNode)
        this.line(0, `@typeText @storage{};`)
        if (this.failed) {
            return
        }
    }
}

// Whether a prelude static's storage is emitted. `collectNames` records calls, not variable
// reads, so a prelude static's reach is the reach of the code that reads it: the storage and
// the install that fills it are the resources pair (`resourcesInstall` is recorded when the
// program carries a table, `resourcesEntries` when the API is called).
fun Emitter.staticReached(entry: *CgStatic): Bool {
    val name: Str = xmlAttr(entry.decl, AstNodeAttributeKind.Name)
    if (name == "resourceStore") {
        return this.referencedNames.has("resourcesInstall")
                || this.referencedNames.has("resourcesEntries")
    }
    return false
}

// Whether any emitted static has an initializer, i.e. whether the pass is needed.
fun Emitter.hasStaticInit(): Bool {
    for (*entry in this.statics) {
        if (entry.prelude && !this.staticReached(entry)) {
            continue
        }
        if (!xmlIsEmpty(xmlChildPtr(entry.decl, AstNodeKind.Init))) {
            return true
        }
    }
    return false
}

// Whether the generated initialization pass is needed at all: a static initializer, or the
// resource table's install, which is its first step (`specs/resources.md`).
fun Emitter.needsStaticInit(): Bool {
    return this.hasStaticInit() || this.resourceStored.size() > 0
}

// The generated initialization pass (specs/statics.md), run before `main`'s body so the
// language owns the order; a program must not depend on one static initializing before
// another. The resource table is built first: `Resources` is an API over it, not a program
// static, so a static initializer that reads a resource sees the install already done.
fun Emitter.emitStaticInit(): Unit {
    if (!this.needsStaticInit()) {
        return
    }
    this.line(0, "// File-level static storage (specs/statics.md): initialized before main's body.")
    this.line(0, "void simse_initStatics() {")
    if (this.resourceStored.size() > 0) {
        this.line(
            1,
            "resourcesInstall(__sm_stringTable, __sm_stringCount, __sm_resourceIndex, __sm_resourceCount);"
        )
    }
    for (*entry in this.statics) {
        if (entry.prelude && !this.staticReached(entry)) {
            continue
        }
        val init: *AstXmlNode = xmlChildPtr(entry.decl, AstNodeKind.Init)
        if (xmlIsEmpty(init)) {
            continue
        }
        this.curFile = entry.file
        val storage: Str = this.qualify(entry.packageName, xmlAttr(entry.decl, AstNodeAttributeKind.Name))
        val exprText: Str = this.expr(init, 0, xmlChildPtr(entry.decl, AstNodeKind.Type))
        this.line(
            1,
            `@storage = @exprText;`
        )
        if (this.failed) {
            return
        }
    }
    this.line(0, "}")
}

// Every aggregate the program declares, named before any is defined: packages are
// emitted in source order and a generated struct may hold a pointer to another package's
// type, so the definition would otherwise come too late.
// A type whose C++ is hand-written: a prelude declaration carrying the cpp generator (a
// header, already included) or the res generator (a resource section). The emitter skips
// it and the header/section defines it. An unmarked type is generated from its
// declaration, like a program type (specs/attributes.md).
fun Emitter.typeIsRaw(decl: *AstXmlNode): Bool {
    val generator: Str = xmlAttr(decl, AstNodeAttributeKind.Generator)
    return generator == "cpp" || generator == "res"
}

fun Emitter.emitForwardTypes(emitted: *Dictionary<Str, Bool>): Unit {
    for (*input in this.inputs) {
        val decls: List<AstXmlNode> = xmlDecls(input.module)
        for (*decl in decls) {
        if (decl.name != AstNodeKind.DataClass) {
            continue
        }
        if (this.typeIsRaw(decl)) {
            continue
        }
        val fwdName: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
        // A name belongs to one declaration: a later declaration of it shadows an earlier
        // one (an explicit import over `rtl`, specs/modules.md "Shadowing"), so only the
        // winner of the name is emitted.
        if (this.typePackage(fwdName) != this.inputPackage(input)) {
            continue
        }
        if (input.prelude && !emitted.has(fwdName)) {
            continue
        }
        val tmpl: Str = this.templateClause(xmlTypeParamNames(decl))
        if (tmpl != "") {
            this.line(0, tmpl)
        }
        val name: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
        val qualifyText: Str = this.qualify(this.typePackage(name), name)
        this.line(0, `struct @qualifyText;`)
    }
    }
}

// The aggregate definitions, in scan order except where a declaration must go first: a struct
// field that holds another declaration's type *by value* needs it complete, so that
// declaration is pulled forward (`emitTypeByName`). A handle - `*T`, `&T`, `Array<T>`,
// `Span<T>`, `RawArray<T>`, `PList<T>` - stores a pointer, so its element is no dependency.
// Without the pull, a declaration that moves later in the scan (a module) makes the generated
// C++ read "uses undefined struct".
fun Emitter.emitTypes(emitted: *Dictionary<Str, Bool>): Unit {
    // The winner of a shared name (`typePackage`) -> the file that declares it, and whether
    // that file is the prelude (a prelude type is emitted only when the program reaches it).
    var files: Dictionary<Str, Str> = Dictionary<Str, Str>()
    var prel: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    for (*input in this.inputs) {
        val pkg: Str = this.inputPackage(input)
        val decls: List<AstXmlNode> = xmlDecls(input.module)
        for (*decl in decls) {
            if (decl.name != AstNodeKind.DataClass && decl.name != AstNodeKind.Enum
                && decl.name != AstNodeKind.TypeAlias
            ) {
                continue
            }
            val tname: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
            if (this.typePackage(tname) == pkg && !files.has(tname)) {
                files.insert(tname, input.fileName)
                prel.insert(tname, input.prelude)
            }
        }
    }
    var defined: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    for (*input in this.inputs) {
        val pkg: Str = this.inputPackage(input)
        val decls: List<AstXmlNode> = xmlDecls(input.module)
        for (*decl in decls) {
            if (decl.name != AstNodeKind.DataClass && decl.name != AstNodeKind.Enum
                && decl.name != AstNodeKind.TypeAlias
            ) {
                continue
            }
            val tname: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
            if (this.typePackage(tname) != pkg) {
                continue
            }
            this.emitTypeByName(tname, files, prel, emitted, defined)
            if (this.failed) {
                return
            }
        }
    }
}

// One declaration, its by-value dependencies first. `defined` also records the declarations
// that are not written out at all (a raw type's C++ is a header or a section, a shadowed name
// is not the winner, an unreached prelude type stays out), so every name is decided once.
fun Emitter.emitTypeByName(
    name: *Str,
    files: *Dictionary<Str, Str>,
    prel: *Dictionary<Str, Bool>,
    emitted: *Dictionary<Str, Bool>,
    defined: *Dictionary<Str, Bool>
): Unit {
    if (defined.has(name)) {
        return
    }
    val file: *Str = files.getPtr(name)
    if (file == null) {
        defined.insert(name, true)
        return
    }
    val decl: *AstXmlNode = this.types.getPtr(name)
    if (decl == null) {
        defined.insert(name, true)
        return
    }
    defined.insert(name, true)
    val preludePtr: *Bool = prel.getPtr(name)
    val prelude: Bool = *preludePtr
    if (prelude && !emitted.has(name)) {
        return
    }
    if (this.typeIsRaw(decl)) {
        return
    }
    if (decl.name == AstNodeKind.DataClass) {
        this.setActiveTypeParams(xmlTypeParamNames(decl))
        for (*field in xmlChildren(decl, AstNodeKind.Field)) {
            this.emitTypeDeps(xmlChildPtr(field, AstNodeKind.Type), files, prel, emitted, defined)
            if (this.failed) {
                return
            }
        }
        this.curFile = *file
        // A `union class`'s struct holds the tag enum by value, so the enum is pulled in
        // first even though it is not a field.
        if (xmlAttr(decl, AstNodeAttributeKind.IsUnionClass) == "true") {
            this.emitOneDep(unionTagName(xmlAttr(decl, AstNodeAttributeKind.Name)), files, prel, emitted, defined)
            if (this.failed) {
                return
            }
        }
        this.emitDataClass(decl)
        return
    }
    if (decl.name == AstNodeKind.Enum) {
        this.curFile = *file
        this.emitEnum(decl)
        this.emitEnumConversion(decl)
        return
    }
    // The remaining declaration is a typealias. A prelude alias is never written out: its
    // spellings resolve to the target (`type`), so the C++ has no `using` and no header for
    // one (`StrView` is `Span<Char>` everywhere). A program's alias keeps its `using`.
    if (prelude) {
        return
    }
    this.setActiveTypeParams(xmlTypeParamNames(decl))
    this.emitTypeDeps(xmlChildPtr(decl, AstNodeKind.TargetType), files, prel, emitted, defined)
    if (this.failed) {
        return
    }
    this.curFile = *file
    this.emitTypeAlias(decl)
}

// The declared types a type node holds *by value*, so a declaration naming them needs each
// complete (`List<Foo>` stores its elements inline; `Array<Foo>` and `*Foo` do not).
fun Emitter.emitTypeDeps(
    node: *AstXmlNode,
    files: *Dictionary<Str, Str>,
    prel: *Dictionary<Str, Bool>,
    emitted: *Dictionary<Str, Bool>,
    defined: *Dictionary<Str, Bool>
): Unit {
    if (xmlIsEmpty(node)) {
        return
    }
    val kind: AstNodeCategory = xmlKind(node)
    if (kind == AstNodeCategory.TypeReference || kind == AstNodeCategory.TypePointer
        || kind == AstNodeCategory.TypeFunction || kind == AstNodeCategory.TypeYield
    ) {
        return
    }
    if (kind == AstNodeCategory.TypeNamed) {
        this.emitOneDep(xmlAttr(node, AstNodeAttributeKind.Name), files, prel, emitted, defined)
        return
    }
    if (kind != AstNodeCategory.TypeGeneric) {
        return
    }
    val name: Str = xmlAttr(node, AstNodeAttributeKind.Name)
    // A handle: the elements live behind a pointer, so they need not be complete - but the
    // handle's own declaration does, so a field naming one pulls it in like any other type
    // (the prelude's `Span` is generated from its declaration, so it needs this).
    if (name == "Array" || name == "Span" || name == "RawArray" || name == "PList") {
        this.emitOneDep(name, files, prel, emitted, defined)
        return
    }
    // A declared generic (`Box<Foo>`) stores its arguments the way `List` stores its element.
    this.emitOneDep(name, files, prel, emitted, defined)
    for (*arg in xmlChildren(node, AstNodeKind.TypeArg)) {
        this.emitTypeDeps(arg, files, prel, emitted, defined)
        if (this.failed) {
            return
        }
    }
}

// The dependency a name denotes, when it is a declaration of this compilation: a built-in
// (`Int`, `List`) and a type parameter are not.
fun Emitter.emitOneDep(
    name: *Str,
    files: *Dictionary<Str, Str>,
    prel: *Dictionary<Str, Bool>,
    emitted: *Dictionary<Str, Bool>,
    defined: *Dictionary<Str, Bool>
): Unit {
    if (this.activeTypeParams.has(name) || !files.has(name)) {
        return
    }
    this.emitTypeByName(name, files, prel, emitted, defined)
}

fun Emitter.emitDataClass(decl: *AstXmlNode): Unit {
    if (xmlAttr(decl, AstNodeAttributeKind.IsUnionClass) == "true") {
        this.emitUnionClass(decl)
        return
    }
    this.setActiveTypeParams(xmlTypeParamNames(decl))
    val fields: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Field)
    for (*field in fields) {
        if (xmlIsEmpty(xmlChildPtr(field, AstNodeKind.Type))) {
            val xmlAttrText: Str = xmlAttr(field, AstNodeAttributeKind.Name)
            this.fail(
                field,
                `unsupported: field '@xmlAttrText' without a type`
            )
            return
        }
    }
    if (this.failed) {
        return
    }

    val name: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
    val emittedName: Str = this.qualify(this.typePackage(name), name)
    val typeParams: List<Str> = xmlTypeParamNames(decl)
    this.sourceComment(decl)
    val tmpl: Str = this.templateClause(typeParams)
    // Generated aggregates follow the language's 4-byte packing rule
    // (specs/memory-model.md) - except a `native class`, whose whole point is to mirror a
    // native layout, so the packing is not applied and the struct keeps the host's
    // alignment.
    val nativeLayout: Bool = xmlAttr(decl, AstNodeAttributeKind.IsNativeClass) == "true"
    if (!nativeLayout) {
        this.line(0, "SIMSE_PACK_PUSH")
    }
    if (tmpl != "") {
        this.line(0, tmpl)
    }
    this.line(0, `struct @emittedName {`)
    for (*field in fields) {
        val typeText2: Str = this.type(xmlChildPtr(field, AstNodeKind.Type))
        val xmlAttrText2: Str = xmlAttr(field, AstNodeAttributeKind.Name)
        this.line(
            1,
            `@typeText2 @xmlAttrText2;`
        )
    }
    if (!xmlIsEmpty(this.cgUninitMethod(decl))) {
        // A class with `unInit` has a real destructor: declared here, defined with the
        // bodies (`emitUninit`), so its body may call anything the prototypes declare.
        this.line(1, `~@emittedName();`)
        if (this.failed) {
            return
        }
    }
    this.line(0, "};")
    if (!nativeLayout) {
        this.line(0, "SIMSE_PACK_POP")
    }
}

// A `union class`: the tag, the arms, the tag comparison, and the generated surface as free
// functions (the method convention: the receiver is the first parameter). The arms live in
// a generated base (`Sm<Name>Storage`) rather than in the class itself, because *which
// form* the storage takes follows the arms. When every arm is trivially copyable the base
// declares only the empty default constructor and the whole class stays trivially copyable -
// copies, returns and destruction are the plain value operations. When an arm owns storage
// (`Str`, a `List`, a handle, ...) the base carries the managed form: a destructor that
// destroys the live arm by tag, copy/move constructors and assignments, and setters that
// place the new arm after destroying the old one, reached through `simse_destroy` (src/rtl/types.hpp). A *generic*
// union cannot decide the form when it is emitted, so it gets `Sm<Name>Storage<SmManaged,
// T...>` with both partial specializations and picks one from the actual arm types
// (`SmUnionManaged<...>` in src/rtl/types.hpp): `Opt2<Int>` is the trivial form,
// `Opt2<Str>` the managed one. The tag enum itself is a separate declaration, emitted just
// before by `emitTypeByName`.
fun Emitter.emitUnionClass(decl: *AstXmlNode): Unit {
    this.setActiveTypeParams(xmlTypeParamNames(decl))
    val name: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
    val emittedName: Str = this.qualify(this.typePackage(name), name)
    val tagType: Str = this.qualify(this.typePackage(name), unionTagName(name))
    val storageName: Str = this.qualify(this.typePackage(name), unionStorageName(name))
    val typeParams: List<Str> = xmlTypeParamNames(decl)
    val tmpl: Str = this.templateClause(typeParams)
    val generic: Bool = typeParams.size() > 0
    // The name to spell outside the class body: a generic struct is `Res2<T>`, and its
    // injected-class-name is not visible to the free functions below.
    var emittedRef: Str = emittedName
    if (generic) {
        emittedRef = emittedName + "<" + cgJoin(typeParams, ", ") + ">"
    }
    val fields: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Field)
    // A concrete union's form is decided here, from the declared arm types
    // (`unionArmManaged`); a generic union's is decided in C++ per instantiation, from the
    // emitted arm types in declaration order.
    var managed: Bool = false
    for (*field in fields) {
        if (this.unionArmManaged(xmlChildPtr(field, AstNodeKind.Type))) {
            managed = true
        }
    }
    var managedArgs: List<Str> = List<Str>()
    if (generic) {
        for (*field in fields) {
            managedArgs.append(this.type(xmlChildPtr(field, AstNodeKind.Type)))
        }
    }
    val managedText: Str = "SmUnionManaged<" + cgJoin(managedArgs, ", ") + ">"
    val typeArgs: Str = cgJoin(typeParams, ", ")
    this.sourceComment(decl)
    this.line(0, "SIMSE_PACK_PUSH")
    if (generic) {
        this.emitUnionStorageTemplate(fields, tagType, storageName, typeParams)
    } else {
        this.line(0, `struct @storageName {`)
        this.emitUnionStorageMembers(fields, tagType, storageName, managed)
        this.line(0, "};")
    }
    if (this.failed) {
        return
    }
    if (generic) {
        this.line(0, tmpl)
        this.line(0, `struct @emittedName : @storageName<@managedText, @typeArgs> {`)
        this.line(0, "};")
    } else {
        this.line(0, `struct @emittedName : @storageName {`)
        this.line(0, "};")
    }
    this.line(0, "SIMSE_PACK_POP")
    if (this.failed) {
        return
    }
    // The tag comparison: the struct against its tag enum, so `when (u)`'s `u == SmUTypes.A`
    // is this operator and no operand moves. The pointer form is the one a borrowed
    // parameter uses (`u: *U`), the reference form a local's (`u: U`).
    if (tmpl != "") {
        this.line(0, tmpl)
    }
    this.line(
        0,
        `inline Bool operator==(const @emittedRef& self, @tagType tag) { return self._type == tag; }`
    )
    if (tmpl != "") {
        this.line(0, tmpl)
    }
    this.line(
        0,
        `inline Bool operator==(const @emittedRef* self, @tagType tag) { return self->_type == tag; }`
    )
    if (tmpl != "") {
        this.line(0, tmpl)
    }
    this.line(
        0,
        `inline Bool operator!=(const @emittedRef& self, @tagType tag) { return self._type != tag; }`
    )
    if (tmpl != "") {
        this.line(0, tmpl)
    }
    this.line(
        0,
        `inline Bool operator!=(const @emittedRef* self, @tagType tag) { return self->_type != tag; }`
    )
    for (*method in xmlChildren(decl, AstNodeKind.Function)) {
        if (xmlAttr(method, AstNodeAttributeKind.IsUnionGenerated) != "true") {
            continue
        }
        val methodName: Str = xmlAttr(method, AstNodeAttributeKind.Name)
        val symbol: Str = this.qualify(this.typePackage(name), methodName)
        // Two arms of one type (`union class U(var User: Str, var Email: Str)`) would share
        // one C++ `initByValue` signature, so only the first such arm is emitted: a by-value
        // construction resolves to the earlier field, the `Res2<Str>` convention. A generic
        // union keeps both and constrains the later one out (below), because its arm types
        // are only known per instantiation. The later field stays reachable through
        // `set<Field>`.
        if (!generic && methodName == "initByValue" && xmlCount(method, AstNodeKind.Param) == 1
            && this.unionArmTypeSeen(fields, xmlAttr(method, AstNodeAttributeKind.Text))
        ) {
            continue
        }
        val ret: Str = this.type(xmlChildPtr(method, AstNodeKind.ReturnType))
        var params: Str = emittedRef + "* self"
        for (*param in xmlChildren(method, AstNodeKind.Param)) {
            val paramType: Str = this.type(xmlChildPtr(param, AstNodeKind.Type))
            val paramName: Str = xmlAttr(param, AstNodeAttributeKind.Name)
            params = params + ", " + paramType + " " + paramName
        }
        if (this.failed) {
            return
        }
        if (tmpl != "") {
            this.line(0, tmpl)
        }
        // A generic union's one-argument `initByValue` arms are picked at C++ by the
        // argument's type; an instantiation that makes two arms' types equal (`Res2<Str>`)
        // would be an ambiguous call, so every arm but the first is constrained out when an
        // earlier arm has the same type - the earlier arm wins. A concrete union with two
        // same-typed arms was already handled above (the later arm's `initByValue` is not
        // emitted), so it needs no constraint here.
        if (generic && methodName == "initByValue" && xmlCount(method, AstNodeKind.Param) == 1) {
            val constraint: Str = this.unionInitConstraint(fields, xmlAttr(method, AstNodeAttributeKind.Text))
            if (constraint != "") {
                this.line(0, constraint)
            }
        }
        this.line(0, `inline @ret @symbol(@params) {`)
        this.emitUnionMethodBody(method, methodName, tagType)
        this.line(0, "}")
    }
}

// The `requires` clause that keeps a generic union's arm constructors distinguishable: for
// the arm `fieldName`, every *earlier* arm's type is excluded, so an instantiation where
// they coincide resolves to the earlier arm (`Res2<Str>(text)` builds `Value`) instead of
// failing as an ambiguous call. Empty for the first arm and for a single-arm union.
fun Emitter.unionInitConstraint(fields: *List<AstXmlNode>, fieldName: *Str): Str {
    var earlier: List<Str> = List<Str>()
    var armType: Str = ""
    for (*field in fields) {
        val name: Str = xmlAttr(field, AstNodeAttributeKind.Name)
        if (name == fieldName) {
            armType = this.type(xmlChildPtr(field, AstNodeKind.Type))
            break
        }
        earlier.append(this.type(xmlChildPtr(field, AstNodeKind.Type)))
    }
    if (armType == "" || earlier.size() == 0) {
        return ""
    }
    var conditions: List<Str> = List<Str>()
    for (*other in earlier) {
        conditions.append("!std::is_same_v<" + armType + ", " + other + ">")
    }
    return "requires (" + cgJoin(conditions, " && ") + ")"
}

// Whether an earlier field than `fieldName` has the same emitted C++ type. A concrete
// union's second such arm would emit a duplicate `initByValue` overload, so it is skipped
// and the earlier arm wins the by-value construction (the `unionInitConstraint` convention,
// resolved at emit time instead of in C++).
fun Emitter.unionArmTypeSeen(fields: *List<AstXmlNode>, fieldName: *Str): Bool {
    var seen: List<Str> = List<Str>()
    for (*field in fields) {
        val name: Str = xmlAttr(field, AstNodeAttributeKind.Name)
        val armType: Str = this.type(xmlChildPtr(field, AstNodeKind.Type))
        if (name == fieldName) {
            for (*other in seen) {
                if (other == armType) {
                    return true
                }
            }
            return false
        }
        seen.append(armType)
    }
    return false
}

// One generated method's C++ body. The arm comes from the method's `Text` attribute (the
// field name the parser recorded), so `set`/`get`/`initByValue` never re-match types here.
// Every arm write goes through the storage's own `set<Field>` - plain assignment in the
// trivial form, destroy-and-place in the managed one - so a generic union's body is the
// same C++ for every instantiation and the form stays the storage's business.
fun Emitter.emitUnionMethodBody(method: *AstXmlNode, methodName: *Str, tagType: *Str): Unit {
    val fieldName: Str = xmlAttr(method, AstNodeAttributeKind.Text)
    if (methodName == "getTypeOf") {
        this.line(1, "return self->_type;")
        return
    }
    if (methodName == "isOfType") {
        this.line(1, "return self->_type == typeToCheck;")
        return
    }
    if (methodName == "setNone") {
        this.line(1, "self->setNone();")
        return
    }
    if (methodName == "initByValue" && xmlCount(method, AstNodeKind.Param) == 0) {
        this.line(1, "self->setNone();")
        return
    }
    if (methodName == "initByValue" || methodName.startsWith("set")) {
        val setter: Str = "set" + upperFirst(fieldName)
        this.line(1, `self->@setter(std::move(value));`)
        return
    }
    // `get<Field>`: the live arm's address when the tag says this arm, `null` otherwise. A
    // C++ union's arms share their address, so every matching getter hands back the same
    // pointer and no arm is copied; the tag test is what makes a wrong-arm read `null`.
    this.line(1, `if (self->_type == @tagType::@fieldName) {`)
    this.line(2, `return &self->@fieldName;`)
    this.line(1, "}")
    this.line(1, "return nullptr;")
}

// A generic union's storage: both forms, as partial specializations of
// `Sm<Name>Storage<SmManaged, T...>`, declared before the class that names one of them as
// its base. The public struct's base clause passes `SmUnionManaged<...>`, so the form
// follows the instantiation's actual arm types (src/rtl/types.hpp).
fun Emitter.emitUnionStorageTemplate(
    fields: *List<AstXmlNode>, tagType: *Str, storageName: *Str, typeParams: *List<Str>
): Unit {
    var paramParts: List<Str> = List<Str>()
    for (*param in typeParams) {
        paramParts.append("class " + param)
    }
    val paramsText: Str = cgJoin(paramParts, ", ")
    val typeArgs: Str = cgJoin(typeParams, ", ")
    this.line(0, `template <Bool SmManaged, @paramsText> struct @storageName;`)
    this.line(0, `template <@paramsText> struct @storageName<false, @typeArgs> {`)
    this.emitUnionStorageMembers(fields, tagType, storageName, false)
    this.line(0, "};")
    this.line(0, `template <@paramsText> struct @storageName<true, @typeArgs> {`)
    this.emitUnionStorageMembers(fields, tagType, storageName, true)
    this.line(0, "};")
}

// The storage's members, one indent level inside the struct. Both forms carry a
// user-provided default constructor whose body starts no arm: the union's implicit one is
// deleted as soon as an arm has a non-trivial *or absent* default constructor
// (`Opt<StrView>`'s `Span` has none), and the empty `None` value must not construct an arm
// at all - the tag's own member initializer is what a fresh value reads. The trivial form
// adds nothing else, so the storage - and the class derived from it - stays trivially
// copyable. The managed form owns the copy/move constructors, the destructor and the
// assignments, because a union's own are deleted as soon as one arm is non-trivial; every
// path that places an arm does it by hand, so nothing stays constructed. Either form's
// `set<Field>` moves the tag as it writes, and `setNone` is the empty tag.
fun Emitter.emitUnionStorageMembers(
    fields: *List<AstXmlNode>, tagType: *Str, storageName: *Str, managed: Bool
): Unit {
    this.line(1, `@tagType _type = @tagType::None;`)
    if (fields.size() > 0) {
        this.line(1, "union {")
        for (*field in fields) {
            val fieldType: Str = this.type(xmlChildPtr(field, AstNodeKind.Type))
            val fieldName: Str = xmlAttr(field, AstNodeAttributeKind.Name)
            this.line(2, `@fieldType @fieldName;`)
        }
        this.line(1, "};")
    }
    this.line(1, `@storageName() {}`)
    if (managed) {
        this.line(1, `@storageName(const @storageName& other) { this->copyFrom(other); }`)
        this.line(1, `@storageName(@storageName&& other) noexcept { this->moveFrom(other); }`)
        this.line(1, `~@storageName() { this->destroyActive(); }`)
        this.line(1, `@storageName& operator=(const @storageName& other) {`)
        this.line(2, "if (this != &other) {")
        this.line(3, "this->destroyActive();")
        this.line(3, "this->copyFrom(other);")
        this.line(2, "}")
        this.line(2, "return *this;")
        this.line(1, "}")
        this.line(1, `@storageName& operator=(@storageName&& other) noexcept {`)
        this.line(2, "if (this != &other) {")
        this.line(3, "this->destroyActive();")
        this.line(3, "this->moveFrom(other);")
        this.line(2, "}")
        this.line(2, "return *this;")
        this.line(1, "}")
        this.line(1, "void destroyActive() {")
        this.line(2, "switch (this->_type) {")
        for (*field in fields) {
            val fieldName: Str = xmlAttr(field, AstNodeAttributeKind.Name)
            this.line(3, `case @tagType::@fieldName: simse_destroy(this->@fieldName); break;`)
        }
        this.line(3, "default: break;")
        this.line(2, "}")
        this.line(2, `this->_type = @tagType::None;`)
        this.line(1, "}")
        this.line(1, `void copyFrom(const @storageName& other) {`)
        this.line(2, "switch (other._type) {")
        for (*field in fields) {
            val fieldName: Str = xmlAttr(field, AstNodeAttributeKind.Name)
            val fieldType: Str = this.type(xmlChildPtr(field, AstNodeKind.Type))
            this.line(
                3,
                `case @tagType::@fieldName: ::new ((void *) &this->@fieldName) @fieldType(other.@fieldName); this->_type = @tagType::@fieldName; break;`
            )
        }
        this.line(3, "default: break;")
        this.line(2, "}")
        this.line(1, "}")
        this.line(1, `void moveFrom(@storageName& other) {`)
        this.line(2, "switch (other._type) {")
        for (*field in fields) {
            val fieldName: Str = xmlAttr(field, AstNodeAttributeKind.Name)
            val fieldType: Str = this.type(xmlChildPtr(field, AstNodeKind.Type))
            this.line(
                3,
                `case @tagType::@fieldName: ::new ((void *) &this->@fieldName) @fieldType(std::move(other.@fieldName)); this->_type = @tagType::@fieldName; break;`
            )
        }
        this.line(3, "default: break;")
        this.line(2, "}")
        this.line(1, "}")
    }
    for (*field in fields) {
        val fieldName: Str = xmlAttr(field, AstNodeAttributeKind.Name)
        val fieldType: Str = this.type(xmlChildPtr(field, AstNodeKind.Type))
        val setter: Str = "set" + upperFirst(fieldName)
        this.line(1, `void @setter(@fieldType value) {`)
        if (!managed) {
            this.line(2, `_type = @tagType::@fieldName;`)
            this.line(2, `@fieldName = std::move(value);`)
        } else {
            this.line(2, `if (_type == @tagType::@fieldName) {`)
            this.line(3, `@fieldName = std::move(value);`)
            this.line(3, "return;")
            this.line(2, "}")
            this.line(2, "this->destroyActive();")
            this.line(2, `_type = @tagType::@fieldName;`)
            this.line(2, `::new ((void *) &@fieldName) @fieldType(std::move(value));`)
        }
        this.line(1, "}")
    }
    this.line(1, "void setNone() {")
    if (managed) {
        this.line(2, "this->destroyActive();")
    } else {
        this.line(2, `_type = @tagType::None;`)
    }
    this.line(1, "}")
}

// Whether an arm's C++ type is *not* trivially copyable, so a *concrete* union gets the
// managed form (a generic union decides per instantiation in C++, with `SmUnionManaged`,
// because a bare type parameter's triviality is only known there). Conservative by design:
// only a type proven trivial stays in the plain form, and guessing managed for a trivial
// type costs the struct its triviality, never correctness. A `typealias` is followed, a
// data class (a union class included) is trivial when every field is, an enum and the
// scalars are, and `Span`/`StrView` are; everything else - `Str`, the containers, the
// handles, the callables, an unknown name - is managed.
fun Emitter.unionArmManaged(typeNode: *AstXmlNode): Bool {
    var guard: Int = 0
    var current: AstXmlNode = typeNode
    while (guard < 32) {
        guard = guard + 1
        if (xmlIsEmpty(current)) {
            return true
        }
        val kind: AstNodeCategory = xmlKind(current)
        if (kind == AstNodeCategory.TypePointer) {
            return false
        }
        if (kind == AstNodeCategory.TypeReference || kind == AstNodeCategory.TypeFunction
            || kind == AstNodeCategory.TypeYield
        ) {
            return true
        }
        if (kind == AstNodeCategory.TypeIntLit) {
            return false
        }
        if (kind == AstNodeCategory.TypeGeneric) {
            val genericName: Str = xmlAttr(current, AstNodeAttributeKind.Name)
            if (genericName == "Span") {
                return false
            }
            if (genericName == "Opt") {
                val args: List<AstXmlNode> = xmlChildren(current, AstNodeKind.TypeArg)
                if (args.size() == 1) {
                    current = args[0]
                    continue
                }
            }
            return true
        }
        if (kind != AstNodeCategory.TypeNamed) {
            return true
        }
        val name: Str = xmlAttr(current, AstNodeAttributeKind.Name)
        if (unionArmTrivialName(name)) {
            return false
        }
        val decl: *AstXmlNode = this.types.getPtr(name)
        if (decl == null) {
            return true
        }
        if (decl.name == AstNodeKind.Enum) {
            return false
        }
        if (decl.name == AstNodeKind.TypeAlias) {
            current = xmlChild(decl, AstNodeKind.TargetType)
            continue
        }
        if (decl.name == AstNodeKind.DataClass && !this.typeIsRaw(decl)) {
            for (*field in xmlChildren(decl, AstNodeKind.Field)) {
                if (this.unionArmManaged(xmlChildPtr(field, AstNodeKind.Type))) {
                    return true
                }
            }
            return false
        }
        return true
    }
    return true
}

// The built-in type names whose C++ is trivially copyable: the scalars, the char/bool pair
// and the opaque pointer. `RawPtr` desugars to a pointer before this is reached; `Str`,
// `List`, `Array`, `Dictionary`, `Opt` and the handles are deliberately absent.
fun unionArmTrivialName(name: *Str): Bool {
    if (name == "Unit" || name == "Bool" || name == "Char" || name == "RawPtr") {
        return true
    }
    if (name == "Int" || name == "Int8" || name == "Int16" || name == "Int32"
        || name == "Int64"
    ) {
        return true
    }
    return name == "Float32" || name == "Float64"
}

fun Emitter.emitEnum(decl: *AstXmlNode): Unit {
    this.sourceComment(decl)
    val name: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
    val tmpl: Str = this.templateClause(xmlTypeParamNames(decl))
    if (tmpl != "") {
        this.line(0, tmpl)
    }
    // One line: a member with an explicit value keeps it (`Green = 4`).
    var parts: List<Str> = List<Str>()
    val members: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.EnumMember)
    for (*member in members) {
        if (xmlAttr(member, AstNodeAttributeKind.HasValue) == "true") {
            val xmlAttrText3: Str = xmlAttr(member, AstNodeAttributeKind.Name)
            val xmlAttrText4: Str = xmlAttr(member, AstNodeAttributeKind.Value)
            parts.append(
                `@xmlAttrText3 = @xmlAttrText4`
            )
        } else {
            parts.append(xmlAttr(member, AstNodeAttributeKind.Name))
        }
    }
    val qualifyText2: Str = this.qualify(this.typePackage(name), name)
    val cgJoinText: Str = cgJoin(*parts, ", ")
    this.line(
        0,
        `enum class @qualifyText2 { @cgJoinText };`
    )
}

// The enum's one emitted conversion: `fromInt` is the direct cast back
// (`specs/declarations.md`); `toInt` needs no helper - the call site spells
// `static_cast<Int>(x)` (`Emitter.call`).
fun Emitter.emitEnumConversion(decl: *AstXmlNode): Unit {
    val typeParams: List<Str> = xmlTypeParamNames(decl)
    if (typeParams.size() > 0) {
        return
    }
    val enumName: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
    val emittedName: Str = this.qualify(this.typePackage(enumName), enumName)
    val fromIntName: Str =
        this.qualify(this.typePackage(enumName), "simse_" + enumName + "_fromInt")
    this.line(
        0,
        `inline @emittedName @fromIntName(Int value) { return (@emittedName) value; }`
    )
}

fun Emitter.emitTypeAlias(decl: *AstXmlNode): Unit {
    val target: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.TargetType)
    if (xmlIsEmpty(target)) {
        val xmlAttrText5: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
        this.fail(
            decl,
            `unsupported: typealias '@xmlAttrText5' without a target type`
        )
        return
    }
    this.setActiveTypeParams(xmlTypeParamNames(decl))
    val targetText: Str = this.type(target)
    if (this.failed) {
        return
    }
    this.sourceComment(decl)
    val tmpl: Str = this.templateClause(xmlTypeParamNames(decl))
    if (tmpl != "") {
        this.line(0, tmpl)
    }
    val qualifyText3: Str = this.qualify(
                this.typePackage(xmlAttr(decl, AstNodeAttributeKind.Name)),
                xmlAttr(decl, AstNodeAttributeKind.Name)
            )
    this.line(
        0,
        `using @qualifyText3 = @targetText;`
    )
}

fun Emitter.emitNativeDeclarations(): Unit {
    this.setActiveTypeParams(List<Str>())
    for (*nativeInfo in this.nativeDecls) {
        if (nativeInfo.prelude) {
            continue
        }
        this.curFile = nativeInfo.file
        val decl: AstXmlNode = nativeInfo.decl
        this.setActiveTypeParams(xmlTypeParamNames(decl))
        if (nativeInfo.symbol.find("::") != -1) {
            val nativeInfoSymbolText: Str = nativeInfo.symbol
            this.fail(
                decl,
                `unsupported: namespaced symbol '@nativeInfoSymbolText' needs a global wrapper`
            )
            return
        }
        val returnNode: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.ReturnType)
        var ret: Str = "void"
        if (!xmlIsEmpty(returnNode)) {
            ret = this.type(returnNode)
        }
        if (this.failed) {
            return
        }
        val params: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Param)
        var paramTexts: List<Str> = List<Str>()
        for (*param in params) {
        val paramType: *AstXmlNode = xmlChildPtr(param, AstNodeKind.Type)
        if (xmlIsEmpty(paramType)) {
            val xmlAttrText6: Str = xmlAttr(param, AstNodeAttributeKind.Name)
            this.fail(
                param,
                `unsupported: generated parameter '@xmlAttrText6' without a type`
            )
            return
        }
        val mapped: Str = this.type(paramType)
        if (this.failed) {
            return
        }
        var name: Str = xmlAttr(param, AstNodeAttributeKind.Name)
        if (name == "this") {
            name = "self"
        }
        val pk: AstNodeCategory = xmlKind(paramType)
        if (pk == AstNodeCategory.TypePointer || pk == AstNodeCategory.TypeReference) {
            paramTexts.append(`@mapped @name`)
        } else {
            paramTexts.append(`const @mapped& @name`)
        }
    }
        this.sourceComment(decl)
        val tmpl: Str = this.templateClause(xmlTypeParamNames(decl))
        if (tmpl != "") {
            this.line(0, tmpl)
        }
        val nativeInfoSymbolText2: Str = nativeInfo.symbol
        val cgJoinText2: Str = cgJoin(paramTexts, ", ")
        this.line(
            0,
            `@ret @nativeInfoSymbolText2(@cgJoinText2);`
        )
    }
}

// The symbol a call on a *type name* reaches (`Resources.get(k)`): the declaration's own
// symbol, looked up among the explicit-`this` natives; empty when there is none.
fun Emitter.staticCallSymbol(receiverName: *Str, calleeName: *Str): Str {
    val extensions: *List<CgNativeExt> = this.nativeExtensions.getPtr(calleeName)
    if (extensions == null) {
        return ""
    }
    for (*ext in extensions) {
        if (this.outerTypeName(ext.receiver) == receiverName) {
            return ext.symbol
        }
    }
    return ""
}

// Every call name in a node's subtree; a call also records the symbol it reaches,
// because that symbol may name C++ emitted elsewhere (a `res` declaration's text is
// emitted only when its section is reached).
fun Emitter.collectNames(node: *AstXmlNode, names: *Dictionary<Str, Bool>): Unit {
    // The string literals ride the same walk, the emitter's one pass over the whole
    // program; a literal the *lowering* invents keeps its own spelling.
    if (xmlKind(node) == AstNodeCategory.ExprStrLit) {
        this.literals.add(xmlAttr(node, AstNodeAttributeKind.Text))
    }
    if (xmlKind(node) == AstNodeCategory.ExprCall) {
        val callee: *AstXmlNode = xmlChildPtr(node, AstNodeKind.Callee)
        val name: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
        if (name != "") {
            names.insert(name, true)
            if (this.initByValueTypes.has(name)) {
                names.insert("initByValue", true)
            }
            val named: Opt<Str> = this.nativeSymbols.get(name)
            if (named.hasValue() && !this.nativeExtensions.has(name)) {
                // The flat symbol map holds one symbol per name, so it is only unambiguous
                // for a plain native; an explicit-`this` extension's symbol comes from
                // `staticCallSymbol` at the call site that has a receiver type to match.
                names.insert(named.value(), true)
            }
        }
        if (xmlKind(callee) == AstNodeCategory.ExprMember) {
            val recv: *AstXmlNode = xmlChildPtr(callee, AstNodeKind.Receiver)
            if (xmlKind(recv) == AstNodeCategory.ExprName) {
                val symbol: Str = this.staticCallSymbol(
                    xmlAttr(recv, AstNodeAttributeKind.Name), name
                )
                if (symbol != "") {
                    names.insert(symbol, true)
                }
            }
        }
        // A function passed by *name* (a comparator, `items.sort(compareLessThan)`) is a
        // bare name in an argument position, not a call - but it is reached the same way,
        // so its own prelude body has to be emitted or the emitted C++ calls a function
        // nothing declared.
        for (*arg in node.Children) {
            if (arg.name == AstNodeKind.Arg && xmlKind(arg) == AstNodeCategory.ExprName) {
                names.insert(xmlAttr(arg, AstNodeAttributeKind.Name), true)
            }
        }
    }
    this.collectTypeNames(node)
    var i: Int = 0
    while (i < node.Children.count()) {
        this.collectNames(node.Children[i], names)
        i = i + 1
    }
}

// The resources pool with the program's literals (`specs/resources.md`): the list already
// arrives spelled as C++ literals (`resources.resStoredLiterals`), so pooling is an append.
fun Emitter.collectResourceLiterals(): Unit {
    var i: Int = 0
    while (i < this.resourceStored.size()) {
        this.literals.add(this.resourceStored[i])
        i = i + 1
    }
}

// The resource table: a string-table index per key and per value, and the installer that
// hands them to `Resources` at start-up. Written after the string table, which it reads.
fun Emitter.emitResourceTable(): Unit {
    if (this.resourceStored.size() == 0) {
        return
    }
    var indices: List<Int> = List<Int>()
    for (*text in this.resourceStored) {
        indices.append(this.literals.indexOf(text))
    }
    this.line(0, "// The resources the compiler read from `_res.md` files (specs/resources.md):")
    this.line(0, "// string-table indices, key then value; the generated initialization pass builds")
    this.line(0, "// them into the program's `Resources` table (`resourcesInstall`, src/rtl/resources.kt).")
    val cgIntListTextText: Str = cgIntListText(indices)
    this.line(0, `static Int __sm_resourceIndex[] = @cgIntListTextText;`)
    val sizeText: Str = (this.resourceStored.size() / 2).toString()
    this.line(
        0,
        `static const Int __sm_resourceCount = @sizeText;`
    )
    this.line(0, "")
}

// One pool and two run-length encoded indexes, expanded and decoded before `main` runs
// (`impl_specs/rtl-abi.md`, "String literals"): each index is "what to subtract from the
// previous value" with an implicit 0 before the first entry (`cgRunLengthEncode`,
// `strtable.hpp`). The `static_assert` is the check that the pool and the lengths agree.
fun Emitter.emitStringTable(): Unit {
    if (this.literals.count() == 0) {
        return
    }
    val count: Int = this.literals.count()

    // `value[i] = value[i-1] - series[i]`, with an implicit 0 before the first entry. The
    // offset increment is the previous entry's length while no two literals share text.
    var starts: List<Int> = List<Int>()
    var lengths: List<Int> = List<Int>()
    var total: Int = 0
    var previousStart: Int = 0
    var previousLength: Int = 0
    var i: Int = 0
    while (i < count) {
        val length: Int = cgLiteralByteLength(this.literals.entry(i))
        val start: Int = previousLength
        starts.append(previousStart - start)
        lengths.append(previousLength - length)
        previousStart = start
        previousLength = length
        total = total + length
        i = i + 1
    }
    val startStream: List<Int> = cgRunLengthEncode(starts)
    val lengthStream: List<Int> = cgRunLengthEncode(lengths)
    var worst: Int = 0
    for (value in startStream) {
        if (cgMagnitudeOf(value) > worst) {
            worst = cgMagnitudeOf(value)
        }
    }
    for (value in lengthStream) {
        if (cgMagnitudeOf(value) > worst) {
            worst = cgMagnitudeOf(value)
        }
    }
    var element: Str = "Int"
    if (worst <= 32767) {
        element = "Int16"
    }

    this.line(0, "// The program's string literals: one pool, and two run-length encoded index")
    this.line(0, "// series (offsets as deltas, then lengths), each as what to subtract from the")
    this.line(0, "// previous value; strtable.hpp has the stream format.")
    this.line(0, `static const Int __sm_stringCount = @count;`)
    this.line(0, "static const char __sm_stringPool[] =")

    // The literals again, adjacent, a space between so they stay separate tokens; wrapped
    // short of the standard's 65 536-character logical source line limit.
    var packed: Str = "    "
    i = 0
    while (i < count) {
        val literal: Str = this.literals.entry(i)
        if (packed.size() + 1 + literal.size() > 60000) {
            this.line(0, packed)
            packed = "    "
        }
        packed.appendStr(literal)
        packed.append(' ')
        i = i + 1
    }
    this.line(0, packed)
    this.line(0, ";")
    val cgIntListTextText2: Str = cgIntListText(startStream)
    this.line(
        0,
        `static const @element __sm_stringStarts[] = @cgIntListTextText2;`
    )
    val cgIntListTextText3: Str = cgIntListText(lengthStream)
    this.line(
        0,
        `static const @element __sm_stringLens[] = @cgIntListTextText3;`
    )
    val totalText: Str = total.toString()
    this.line(
        0,
        `static_assert(sizeof(__sm_stringPool) - 1 == @totalText, "the string pool and its length index disagree");`
    )
    this.line(0, "static Span<Char> __sm_stringTable[__sm_stringCount];")
    this.line(0, "static struct __SmStringTableInitType {")
    this.line(1, "__SmStringTableInitType() {")
    this.line(2, "Int starts[__sm_stringCount];")
    this.line(2, "Int lens[__sm_stringCount];")
    this.line(2, "simse_strTableExpand(__sm_stringStarts, starts, __sm_stringCount);")
    this.line(2, "simse_strTableExpand(__sm_stringLens, lens, __sm_stringCount);")
    this.line(2, "simse_strTableDecode(__sm_stringPool, starts, lens, __sm_stringTable,")
    this.line(3, "__sm_stringCount);")
    this.line(1, "}")
    this.line(0, "} __sm_stringTableInit;")
    this.line(0, "")
}

// Whether a prelude body is one the program reaches: its name is called, and - for an
// extension - the program names the receiver's type too (impl_specs/for.md).
fun Emitter.reachesPreludeBody(fn: *CgFn): Bool {
    if (!fn.hasBody) {
        return false
    }
    val name: Str = fn.name
    if (!this.referencedNames.has(name)) {
        return false
    }
    val receiverName: Str = this.outerTypeName(fn.receiver)
    if (receiverName == "") {
        return true
    }
    // A receiver that is the function's own type parameter says nothing - any type can be one.
    if (xmlIsTypeParam(receiverName, fn.templateParams)) {
        return true
    }
    if (this.referencedTypes.has(receiverName)) {
        return true
    }
    // The name is called but no body of it names its receiver's type (`"".isEmpty()` need
    // never name `Str`), so the whole group is emitted - the body the call reaches must exist.
    return !this.preludeReceiverNamed(name)
}

// Whether any function named `name` is one `emitFunctions` will actually write: a program's own
// declaration always, a prelude one only when the program reaches its body. The coloring uses
// this so a prelude `suspend fun` a program never calls (`tasksSpawn`) does not pull the task
// runtime in just by being declared `suspend`.
fun Emitter.asyncEmitted(name: *Str): Bool {
    for (*fn in this.functions) {
        if (fn.name != *name) {
            continue
        }
        if (!fn.prelude) {
            return true
        }
        if (this.reachesPreludeBody(fn)) {
            return true
        }
    }
    return false
}

// Whether any prelude body of `name` has its receiver's type referenced: the per-overload
// half of the rule above, which tells `List`'s `iter` from `Span`'s.
fun Emitter.preludeReceiverNamed(name: *Str): Bool {
    for (*other in this.functions) {
        if (!other.prelude || !other.hasBody) {
            continue
        }
        if (other.name != name) {
            continue
        }
        val otherReceiver: Str = this.outerTypeName(other.receiver)
        if (otherReceiver != "" && this.referencedTypes.has(otherReceiver)) {
            return true
        }
    }
    return false
}

// The type names that declare an `initByValue` extension. A construction of one is a call
// the lowering turns into a setter, so the AST walk (below) records the convention's name
// from the constructor call itself - otherwise the prelude body a synthesized setter reaches
// would never be emitted.
fun Emitter.collectInitByValueTypes(): Unit {
    for (*fn in this.functions) {
        if (fn.name == "initByValue") {
            val recv: Str = this.outerTypeName(fn.receiver)
            if (recv != "") {
                this.initByValueTypes.insert(recv, true)
            }
        }
    }
    val exts: *List<CgNativeExt> = this.nativeExtensions.getPtr("initByValue")
    if (exts != null) {
        for (*ext in exts) {
            val recv: Str = this.outerTypeName(ext.receiver)
            if (recv != "") {
                this.initByValueTypes.insert(recv, true)
            }
        }
    }
}

// Fills `referencedNames` and `referencedTypes` from the program, never from the prelude's
// own unused bodies, then closes them over the prelude it reaches: an emitted body may
// call another, and a native's signature names the types a call reaches.
fun Emitter.collectProgramNames(): Unit {
    this.collectInitByValueTypes()
    for (*input in this.inputs) {
        if (!input.prelude) {
            this.collectNames(input.module, *this.referencedNames)
        }
    }
    // The resource table's installer is a generated call (the initialization pass), not the
    // program's own, so the reach is recorded here (the rule `simse_list_append`'s `main`
    // argument list follows); the storage it fills is gated with it (`staticReached`).
    if (this.resourceStored.size() > 0) {
        this.referencedNames.insert("resourcesInstall", true)
    }
    var changed: Bool = true
    var reachedSections: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    while (changed) {
        changed = false
        val namesBefore: Int = this.referencedNames.size()
        val typesBefore: Int = this.referencedTypes.size()
        // The ported `Opt`/`Res` members and builders are declared under collision-safe
        // names (src/rtl/optres.kt). A call inside a `return (...)` block is mapped only at
        // emission, too late for the prelude-body rule, so a named union pulls its whole
        // small set: `Opt` its two members and two builders, `Res` its three and two.
        if (this.referencedTypes.has("Opt")) {
            this.referencedNames.insert("simse_optValue", true)
            this.referencedNames.insert("simse_optHasValue", true)
            this.referencedNames.insert("simse_optSome", true)
            this.referencedNames.insert("simse_optNone", true)
        }
        if (this.referencedTypes.has("Res")) {
            this.referencedNames.insert("simse_resValue", true)
            this.referencedNames.insert("simse_resHasValue", true)
            this.referencedNames.insert("simse_resError", true)
            this.referencedNames.insert("simse_resOk", true)
            this.referencedNames.insert("simse_resErr", true)
        }
        // An `operator fun get`/`set` (specs/functions.md) is reached through the *index*
        // syntax: the emitter synthesizes the call at the index site, so no call node exists
        // for the walk above to see. A program that names the receiver's type can index one
        // of its values, so the type's operators are marked reached with it; a program that
        // names none cannot call them at all.
        for (*fn in this.functions) {
            if (!fn.prelude || !fn.hasBody) {
                continue
            }
            if (xmlAttr(fn.decl, AstNodeAttributeKind.IsOperator) != "true") {
                continue
            }
            val receiverName: Str = this.outerTypeName(fn.receiver)
            if (receiverName != "" && this.referencedTypes.has(receiverName)) {
                this.referencedNames.insert(fn.name, true)
            }
        }
        // A shared resource section (`strops`, `dictops`, ...) is emitted whole once any of
        // its symbols is reached, so every declaration its text holds is emitted with it -
        // and every signature's types come along (`Opt<Int> simse_str_toInt(...)`). The
        // section is the first `@SmGen("res", ...)` argument; a `cpp` declaration's
        // arguments name a symbol, not a section, so it is left out here.
        for (*fn in this.functions) {
            if (!fn.prelude || fn.hasBody) {
                continue
            }
            if (xmlAttr(fn.decl, AstNodeAttributeKind.Generator) != "res") {
                continue
            }
            val genArgs: Str = xmlAttr(fn.decl, AstNodeAttributeKind.GeneratorArgs)
            val section: Str = cgGeneratorArg(genArgs, 0)
            val symbol: Str = cgGeneratorArg(genArgs, 1)
            if (section == "") {
                continue
            }
            if (this.referencedNames.has(fn.name) || this.referencedNames.has(symbol)) {
                if (!reachedSections.has(section)) {
                    reachedSections.insert(section, true)
                }
            }
        }
        for (*fn in this.functions) {
            if (!fn.prelude) {
                continue
            }
            if (fn.hasBody) {
                if (!this.reachesPreludeBody(fn)) {
                    continue
                }
                this.collectNames(fn.decl, *this.referencedNames)
                continue
            }
            // A native extension is reached by its *symbol* (`simse_str_toInt`), which a
            // call site records, not always by its language name; either one - or a
            // sibling in the same emitted section - marks the declaration's own types as
            // reached, so the `Opt` its signature returns is emitted with it.
            //
            // A declaration with an *explicit* symbol (`@SmGen("cpp", "resourcesGet")`) is
            // reached by that symbol and not by its language name: many declarations share a
            // language name (`get`, `has`, `count`), so the name alone would drag one in
            // when an unrelated declaration of the same name is reached - `Span.get`, the
            // operator, would carry the resources API into every program that indexes a
            // span.
            var reachedName: Bool = false
            if (xmlAttr(fn.decl, AstNodeAttributeKind.HasNativeSymbol) == "true") {
                reachedName = this.referencedNames.has(
                    sourceGenUnquote(xmlAttr(fn.decl, AstNodeAttributeKind.NativeSymbol))
                )
            } else {
                reachedName = this.referencedNames.has(fn.name)
            }
            if (!reachedName) {
                if (xmlAttr(fn.decl, AstNodeAttributeKind.Generator) == "res") {
                    val section: Str = cgGeneratorArg(
                        xmlAttr(fn.decl, AstNodeAttributeKind.GeneratorArgs), 0
                    )
                    if (section != "") {
                        reachedName = reachedSections.has(section)
                    }
                }
            }
            if (!reachedName) {
                continue
            }
            this.collectTypeNames(fn.receiver)
            this.collectTypeNames(xmlChildPtr(fn.decl, AstNodeKind.ReturnType))
            val params: List<AstXmlNode> = xmlChildren(fn.decl, AstNodeKind.Param)
            for (*param in params) {
                this.collectTypeNames(xmlChildPtr(param, AstNodeKind.Type))
            }
        }
        if (this.referencedNames.size() != namesBefore
            || this.referencedTypes.size() != typesBefore
        ) {
            changed = true
        }
    }
}
