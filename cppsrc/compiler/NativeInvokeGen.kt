// NativeInvokeGen.kt
//
// The `native` generator: `@SmGen("native", library[, symbol])`. The declaration's C++ is a
// *run-time* binding to a symbol in a native shared library - the P/Invoke shape
// (impl_specs/native-interop.md): the generator emits a thunk that resolves the symbol once
// with `LoadLibraryA`/`GetProcAddress` on Windows and `dlopen`/`dlsym` elsewhere, and calls
// through it, so the program links nothing and
// needs no header. The declaration itself is an ordinary body-less method with a Simse
// signature; the *thunk* is what a call reaches (`ctx.symbol`).
//
// The reader of the declaration writes the signature the way C# writes a `[DllImport]`
// one - the Simse types are the ABI's (`Int32` is `int`, `*Int8` is a pointer) - and the
// generator maps them to the C++ the loader needs. A handle crosses as `void*` and comes back
// through a cast, which is exactly the raw-pointer reinterpretation a union-style native API
// (SDL's event, say) wants and cannot spell in Simse.

package compiler

// Self-registration (impl_specs/generators.md). `false`/`false`: the generator emits its own
// forward declaration and definition (the manager must not emit a second prototype, whose
// `const T&` spelling it could not match), and an explicit `this` is not a receiver pattern.
val nativeInvokeGenRegistered: Bool = registerSourceGen("native", nativeInvokeGen, false, false)

fun nativeInvokeGen(ctx: *SourceGenContext): SourceGenTransform {
    if (ctx.phase == SourceGenPhase.Declare) {
        return nativeInvokeDeclare(ctx)
    }
    if (ctx.phase == SourceGenPhase.Emit) {
        // The program-wide call (an empty declaration): this generator has no text that hangs
        // on the program itself.
        if (xmlIsEmpty(ctx.declaration)) {
            return SourceGenTransform(SourceTransformation.None, "")
        }
        return nativeInvokeEmit(ctx)
    }
    // `Reparse` is the driver's pass; this generator's text is C++ the emitter places.
    return SourceGenTransform(SourceTransformation.None, "")
}

// The symbol every call reaches is the generated thunk, not the native symbol: the thunk is
// what carries the loader and the conversions.
fun nativeInvokeDeclare(ctx: *SourceGenContext): SourceGenTransform {
    if (ctx.parameter(0) == "") {
        ctx.error = fmtStr(
            "native: '|' names no library (@SmGen(\"native\", \"<library>\", \"<symbol>\"))",
            ctx.declName
        )
        return SourceGenTransform(SourceTransformation.None, "")
    }
    ctx.symbol = nativeInvokeThunkName(ctx.declName)
    return SourceGenTransform(SourceTransformation.ChangedOutput, "")
}

fun nativeInvokeThunkName(declName: Str): Str {
    return fmtStr("__sm_native_|", declName)
}

// Reach-gated like a module's `emit: reached`: of the declarations a program (or the compiler's
// own build) names, only the ones a call reaches emit a thunk.
fun nativeInvokeEmit(ctx: *SourceGenContext): SourceGenTransform {
    if (!ctx.isReached()) {
        return SourceGenTransform(SourceTransformation.None, "")
    }
    val library: Str = ctx.parameter(0)
    var symbol: Str = ctx.parameter(1)
    if (symbol == "") {
        symbol = ctx.declName
    }
    val decl: *AstXmlNode = *ctx.declaration
    val thunk: Str = ctx.symbol

    val params: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Param)
    var thunkParams: List<Str> = List<Str>()
    var nativeParams: List<Str> = List<Str>()
    var argExprs: List<Str> = List<Str>()
    for (*param in params) {
        val typeNode: *AstXmlNode = xmlChildPtr(param, AstNodeKind.Type)
        val valueType: Str = nativeInvokeValueType(typeNode)
        if (valueType == "") {
            ctx.error = fmtStr("native: '|' has a parameter type the generator cannot map", ctx.declName)
            return SourceGenTransform(SourceTransformation.None, "")
        }
        var name: Str = xmlAttr(param, AstNodeAttributeKind.Name)
        if (name == "this") {
            name = "self"
        }
        if (xmlKind(typeNode) == AstNodeCategory.TypePointer) {
            thunkParams.append(fmtStr("| |", valueType, name))
        } else {
            thunkParams.append(fmtStr("const |& |", valueType, name))
        }
        nativeParams.append(nativeInvokeNativeType(typeNode))
        argExprs.append(nativeInvokeArgExpr(typeNode, name))
    }

    val retNode: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.ReturnType)
    var retValue: Str = "void"
    var retNative: Str = "void"
    if (!xmlIsEmpty(retNode)) {
        retValue = nativeInvokeValueType(retNode)
        retNative = nativeInvokeNativeType(retNode)
        if (retValue == "") {
            ctx.error = fmtStr("native: '|' has a return type the generator cannot map", ctx.declName)
            return SourceGenTransform(SourceTransformation.None, "")
        }
    }

    val thunkSig: Str = fmtStr("| |(|)", retValue, thunk, nativeInvokeJoin(thunkParams, ", "))
    val nativeSig: Str = fmtStr("| (*)(|)", retNative, nativeInvokeJoin(nativeParams, ", "))
    val args: Str = nativeInvokeJoin(argExprs, ", ")
    val forward: Str = thunkSig + ";"
    val body: Str = nativeInvokeBody(
        thunkSig, nativeSig, library, symbol, args, nativeInvokeDefault(retValue), nativeInvokeCall(retValue, args)
    )

    // The thunk is named from the declaration, not the package, so two packages may collide on
    // one name. The same binding (library and symbol) shares the thunk; a different one is
    // refused rather than silently mis-bound (`Sections.add` is last-write-wins).
    val bindKey: Str = fmtStr("|::|", library, symbol)
    val prior: Opt<Str> = ctx.state.definitions.get(thunk)
    if (prior.hasValue()) {
        if (prior.value() == bindKey) {
            return SourceGenTransform(SourceTransformation.AlreadyExisting, "")
        }
        ctx.error = fmtStr(
            "native: '|' (|) generates the thunk '|' with a different binding - rename one",
            ctx.declName, ctx.fileName, thunk
        )
        return SourceGenTransform(SourceTransformation.None, "")
    }
    ctx.state.definitions.insert(thunk, bindKey)

    nativeInvokeRuntime(ctx)
    ctx.sections.add("forward", thunk, forward)
    ctx.sections.add("bodies", thunk, body)
    return SourceGenTransform(SourceTransformation.ChangedOutput, "")
}

// `using Fn = <signature>;` (the raw cast the loader hands back), the null check, and the call.
fun nativeInvokeBody(
    signature: Str, nativeSig: Str, library: Str, symbol: Str, args: Str, missing: Str, call: Str
): Str {
    var body = Str()
    body.appendStr(signature)
    body.appendStr(" {\n")
    body.appendStr(fmtStr("    using Fn = |;\n", nativeSig))
    body.appendStr(fmtStr("    static Fn fn = (Fn) __sm_nativeResolve(\"|\", \"|\");\n", library, symbol))
    body.appendStr("    if (fn == nullptr) {\n")
    body.appendStr(fmtStr("        |\n", missing))
    body.appendStr("    }\n")
    body.appendStr(fmtStr("    |\n", call))
    body.appendStr("}")
    return body
}

// What a thunk answers when the library or the symbol could not be resolved.
fun nativeInvokeDefault(retValue: Str): Str {
    if (retValue == "void") {
        return "return;"
    }
    if (retValue == "Str") {
        return "return Str();"
    }
    if (retValue.endsWith("*")) {
        return "return nullptr;"
    }
    return fmtStr("return |{0};", retValue)
}

// The call through the resolved pointer, converted back to the declaration's Simse type: a
// handle comes back through the cast that takes it out (`(Int8*) fn(...)`), and a `Str` from the
// native's `const char*`.
fun nativeInvokeCall(retValue: Str, args: Str): Str {
    if (retValue == "void") {
        return fmtStr("fn(|);", args)
    }
    if (retValue == "Str") {
        return fmtStr("return Str(fn(|));", args)
    }
    if (retValue.endsWith("*")) {
        return fmtStr("return (|) fn(|);", retValue, args)
    }
    return fmtStr("return fn(|);", args)
}

// The C++ type of a declaration's Simse type: the spellings the RTL's own declarations use, so a
// thunk and the call site agree. "" when the generator cannot map it.
fun nativeInvokeValueType(typeNode: *AstXmlNode): Str {
    if (xmlIsEmpty(typeNode)) {
        return "void"
    }
    val kind: AstNodeCategory = xmlKind(typeNode)
    if (kind == AstNodeCategory.TypePointer) {
        val inner: Str = nativeInvokeValueType(xmlChildPtr(typeNode, AstNodeKind.Inner))
        if (inner == "") {
            return ""
        }
        return inner + "*"
    }
    if (kind != AstNodeCategory.TypeNamed) {
        return ""
    }
    val name: Str = xmlAttr(typeNode, AstNodeAttributeKind.Name)
    if (name == "Int" || name == "Int32") {
        return "Int32"
    }
    if (name == "Int8") {
        return "Int8"
    }
    if (name == "Int16") {
        return "Int16"
    }
    if (name == "Int64") {
        return "Int64"
    }
    if (name == "Float32") {
        return "Float32"
    }
    if (name == "Float64") {
        return "Float64"
    }
    if (name == "Bool") {
        return "Bool"
    }
    if (name == "Char") {
        return "Char"
    }
    if (name == "Str") {
        return "Str"
    }
    if (name == "Unit") {
        return "void"
    }
    return ""
}

// The type inside the native function pointer: a handle is the library's own pointer (`void*`,
// cast at the call), a `Str` is a C string.
fun nativeInvokeNativeType(typeNode: *AstXmlNode): Str {
    if (xmlIsEmpty(typeNode)) {
        return "void"
    }
    if (xmlKind(typeNode) == AstNodeCategory.TypePointer) {
        return "void*"
    }
    if (xmlAttr(typeNode, AstNodeAttributeKind.Name) == "Str") {
        return "const char*"
    }
    return nativeInvokeValueType(typeNode)
}

// The thunk parameter converted to what the library's function pointer takes.
fun nativeInvokeArgExpr(typeNode: *AstXmlNode, name: Str): Str {
    if (xmlKind(typeNode) == AstNodeCategory.TypePointer) {
        return fmtStr("(void*) |", name)
    }
    if (xmlAttr(typeNode, AstNodeAttributeKind.Name) == "Str") {
        return fmtStr("|.c_str()", name)
    }
    return name
}

fun nativeInvokeJoin(parts: *List<Str>, separator: Str): Str {
    var out = Str()
    var first: Bool = true
    for (*part in parts) {
        if (!first) {
            out.appendStr(separator)
        }
        out.appendStrPtr(part)
        first = false
    }
    return out
}

// The loader, once per program: the includes go to `includes` and the resolver to `support`,
// which render before the forward declarations and bodies that use them. Both are keyed, so the
// second reached declaration finds them already placed.
fun nativeInvokeRuntime(ctx: *SourceGenContext): Unit {
    if (!ctx.sections.has("includes", "nativeinvoke")) {
        ctx.sections.add("includes", "nativeinvoke", nativeInvokeIncludesText())
    }
    if (!ctx.sections.has("support", "nativeinvoke")) {
        ctx.sections.add("support", "nativeinvoke", nativeInvokeSupportText())
    }
}

fun nativeInvokeIncludesText(): Str {
    return `
// NativeInvoke (impl_specs/native-interop.md): the shared-library loader a
// @SmGen("native", ...) declaration binds its symbol with. Windows loads through
// windows.h's LoadLibraryA/GetProcAddress, everywhere else through dlfcn.h's
// dlopen/dlsym; either way the lookup is at run time rather than a link-time
// import, so the program links nothing.
    #ifdef _WIN32
    #ifndef WIN32_LEAN_AND_MEAN
    #define WIN32_LEAN_AND_MEAN
    #endif
    #ifndef NOMINMAX
    #define NOMINMAX
    #endif
    #include<windows.h>
    #else
    #include<dlfcn.h>
    #endif
    #include<cstring>
    `
}

fun nativeInvokeSupportText(): Str {
    return `
// Resolves 'symbol' from 'library', loading the library once and caching the handle (a small
// fixed table: a program names a handful of libraries at most). A missing library or symbol
// answers null, which the thunk turns into the declaration's default value rather than a
// crash. The handle is the loader's own (a FARPROC on Windows, a void* from dlsym elsewhere);
// the thunk casts the result to the declaration's function-pointer type, which is the one
// object-pointer-to-function-pointer conversion the language cannot spell.
    inline void * __sm_nativeResolve (const char * library, const char* symbol) {
        struct Entry {
            const char * library;
            void * module;
        };
        static Entry table[16];
        static int count = 0;
        void * module = nullptr;
        for (int i = 0; i < count; i++) {
        if (std::strcmp(table[i].library, library) == 0) {
            module = table[i].module;
            break;
        }
    }
        if (module == nullptr) {
            #ifdef _WIN32
                    module = (void *)::LoadLibraryA(library);
            #else
            module = ::dlopen(library, RTLD_NOW | RTLD_LOCAL);
            #endif
            if (module != nullptr && count < 16) {
                table[count].library = library;
                table[count].module = module;
                count++;
            }
        }
        if (module == nullptr) {
            return nullptr;
        }
        #ifdef _WIN32
            return (void *)::GetProcAddress((HMODULE) module, symbol);
        #else
        return ::dlsym(module, symbol);
        #endif
    }
    `
}
