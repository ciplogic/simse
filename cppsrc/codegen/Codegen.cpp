#include "Codegen.h"

#include "../linear/Linear.h"
#include "../linear/Simplify.h"
#include "../linear/ExpressionLowering.h"
#include "../linear/LinearForm.h"
#include "../linear/Yield.h"
#include "../sema/TypeInfer.h"

#include <algorithm>
#include <cstdio>
#include <string>

using linear::IlOpKind;
using ast::DeclKind;
using ast::ExprKind;
using ast::StmtKind;
using ast::TypeKind;

// The type helpers live with the semantic step that shares them (sema/TypeInfer.h),
// so the inference and the emitter agree on what a handle is, what a receiver
// pattern matches, and when two types unify.
using sema::genericType;
using sema::isHandleType;
using sema::isIndexableContainer;
using sema::isRtlTypeName;
using sema::isTypeParamName;
using sema::namedType;
using sema::pointee;
using sema::unifyType;

namespace codegen {
    namespace {
        // How a name's storage is reached, used to pick `.` vs `->`, `*x` vs
        // `x.get()`, and the `copy` lowering. We only track the cases the v1
        // subset needs; anything unknown is treated as a plain value.
        enum class NameKind { Value, Shared, Pointer };

        Str join(const List<Str> &parts, const Str &separator) {
            Str out;
            for (int i = 0; i < (int) parts.size(); i++) {
                if (i > 0) out += separator;
                out += parts[i];
            }
            return out;
        }

        struct Fn {
            const ast::Decl *decl = nullptr;
            ast::TypePtr receiver; // null for plain top-level functions
            Str file;
            List<Str> templateParams; // C++ template type parameters
            bool prelude = false;    // resolved; emitted only when it has a body
            Str packageName;         // picks the emitted-symbol prefix ("" for rtl)
            // A data class's method: emitted like a receiver function, but *not* a
            // plain function - a call by name never reaches it, so the lookups that
            // resolve a plain call must not find it under that name.
            bool isMethod = false;
        };

        // A native function declaration to emit at the top of the amalgamated
        // file (and to call by symbol).
        struct NativeDecl {
            const ast::Decl *decl = nullptr;
            Str file;
            Str symbol;
            bool prelude = false; // comes from the RTL; not emitted
        };

        // A file-level static (`Var`, specs/statics.md): storage plus an optional
        // initializer, emitted under its package's prefix like any other
        // declaration.
        struct Static {
            const ast::Decl *decl = nullptr;
            Str packageName;
            Str file;
        };

        // A native extension method, e.g. `native("sym") fun append<T>(this:
        // List<T>, value: T)`. The receiver pattern picks the right overload when
        // several extensions share a name (`append` on `List<T>` vs on `Str`).
        struct NativeExt {
            Str symbol;
            ast::TypePtr receiver;
            ast::TypePtr returnType;
            List<Str> typeParams;
        };

        // Strips the surrounding quotes from a string token's raw text.
        Str unquote(const Str &text) {
            if (text.length() >= 2 && text.front() == '"' && text.back() == '"') {
                return text.substr(1, text.length() - 2);
            }
            return text;
        }

        class Emitter {
        public:
            explicit Emitter(const List<Input> &inputs) : inputs(inputs) {
            }

            Res<Str> run() {
                collect();
                collectProgramNames();
                // The semantic step on the lowered body reads these
                // (sema/TypeInfer.h). They are threaded to the emitters rather than
                // stored on the emitter: the facts are one value per program, and a
                // body's emitter only borrows it.
                const sema::Facts facts = collectFacts();
                prelude();
                emitNativeDeclarations();
                if (failed) return resError<Str>(error);
                sortLiterals();
                emitStringTable();
                emitForwardTypes();
                if (failed) return resError<Str>(error);
                emitTypes();
                if (failed) return resError<Str>(error);
                emitStatics();
                if (failed) return resError<Str>(error);
                emitFunctions(true, facts);
                if (failed) return resError<Str>(error);
                emitStaticInit();
                if (failed) return resError<Str>(error);
                emitFunctions(false, facts);
                if (failed) return resError<Str>(error);
                return ok(out);
            }

        private:
            const List<Input> &inputs;

            Str out;
            bool failed = false;
            Str error;
            Str curFile;
            // Whether the file being emitted is a prelude one (see `sourceComment`).
            bool curPrelude = false;
            // The names the program calls, for the prelude rule in `emitFunctions`.
            Dictionary<Str, bool> referencedNames;
            // The types the program names, for the same rule's per-container part: the
            // prelude has a `smToYield` per container (`List`, `Array`, `Span`), and a
            // program that iterates one of them should not carry the others' machines.
            Dictionary<Str, bool> referencedTypes;

            Dictionary<Str, const ast::Decl *> types;
            // The state machines the emitter registered as data classes (one per yielding
            // function): the type pass never saw them, so the spelling helpers would not
            // know what `this.<field>` is without it. Owned here because `types` holds
            // pointers into it.
            List<std::shared_ptr<ast::Decl>> machineTypes;
            Dictionary<Str, bool> enumNames;
            // Non-prelude data classes we emitted; their construction lowers to
            // the `_make_<Name>` factory instead of an emitted constructor.
            Dictionary<Str, bool> dataClassNames;
            List<Fn> functions;
            Dictionary<Str, bool> receiverFnNames;
            List<NativeDecl> nativeDecls;
            Dictionary<Str, Str> nativeSymbols; // Simse name -> C++ symbol
            // Explicit-`this` native extensions: method name -> overloads.
            Dictionary<Str, List<NativeExt>> nativeExtensions;

            // C++ type parameters currently in scope (for generic declarations).
            Dictionary<Str, bool> activeTypeParams;

            // Per-function name kinds and declared/inferred types.
            Dictionary<Str, NameKind> nameKinds;
            Dictionary<Str, ast::TypePtr> localTypes;
            // Emitted-symbol prefixes per package (`ns<index>_`; `rtl` and
            // unattributed names have none), and the package each declared type
            // came from.
            Dictionary<Str, Str> nsPrefixes;
            Dictionary<Str, Str> typePackages;
            // File-level statics in declaration order: storage, then the pass that
            // assigns their initializers before `main` (specs/statics.md).
            List<Static> statics;
            Dictionary<Str, Static> staticsByName;
            NameKind selfKind = NameKind::Value;
            ast::TypePtr selfType;
            // A closure class's method is the one place `this` is C++'s own `this`: a
            // member function has no `self` parameter, so the receiver spells `(*this)`
            // and a member `this->field`.
            bool inClosureMethod = false;
            // The enclosing function's declared return type, used to lower a bare
            // `return null`.
            ast::TypePtr curReturnType;

            // ---- diagnostics ----------------------------------------------

            void fail(const common::SourcePos &pos, const Str &message) {
                if (failed) return;
                failed = true;
                error = curFile + ":" + std::to_string(pos.line) + ":" + std::to_string(pos.column)
                        + ": " + message;
            }

            void line(int level, const Str &text) {
                out += Str(level * 4, ' ');
                out += text;
                out += '\n';
            }

            void sourceComment(const common::SourcePos &pos) {
                // A prelude function *with a body* is emitted now (`List<T>.smToYield`), and
                // where it came from is the compiler's own RTL, not the program the user is
                // building: naming it would put a machine-specific path in their file.
                if (curPrelude) return;
                line(0, "// " + curFile + ":" + std::to_string(pos.line));
            }

            // ---- symbol collection ----------------------------------------

            // The receiver type of a class method: `Name<A, B>` for a generic
            // class, `Name` otherwise.
            ast::TypePtr classReceiver(const ast::Decl &decl) {
                if (decl.typeParams.empty()) {
                    return namedType(decl.name);
                }
                auto type = std::make_shared<ast::TypeExpr>();
                type->kind = TypeKind::Generic;
                type->name = decl.name;
                for (const Str &param: decl.typeParams) {
                    type->typeArgs.push_back(namedType(param));
                }
                return type;
            }

            void addFunction(const ast::Decl *decl, ast::TypePtr receiver, const Str &file,
                             const List<Str> &templateParams, bool prelude, const Str &package,
                             bool isMethod = false) {
                Fn fn;
                fn.decl = decl;
                fn.receiver = receiver;
                fn.file = file;
                fn.templateParams = templateParams;
                fn.prelude = prelude;
                fn.packageName = package;
                fn.isMethod = isMethod;
                functions.push_back(fn);
                if (receiver) {
                    receiverFnNames[decl->name] = true;
                }
            }

            // ---- package qualification -------------------------------------
            //
            // Every declaration is emitted under its package's prefix: `rtl` - the
            // namespace the built-in types live in (specs/modules.md, the implicit
            // import) - is emitted bare, and every other package gets `ns<index>_`
            // from the global dictionary below. The dictionary assigns indices in
            // sorted package order, so the numbering never depends on discovery
            // order and the output stays reproducible. This is what keeps two
            // packages' same-named declarations apart in the amalgamated
            // translation unit without spelling a package name out.

            Str inputPackage(const Input &input) {
                return join(input.module.package, ".");
            }

            void collectPackages() {
                List<Str> names;
                for (const Input &input: inputs) {
                    Str pkg = inputPackage(input);
                    // `rtl` is the built-in namespace, and an empty package is a
                    // programmatically built module (the merged prelude); neither
                    // is indexed, so neither is ever prefixed.
                    if (pkg == "rtl" || pkg.empty()) continue;
                    bool seen = false;
                    for (const Str &existing: names) {
                        if (existing == pkg) {
                            seen = true;
                            break;
                        }
                    }
                    if (!seen) names.push_back(pkg);
                }
                std::sort(names.begin(), names.end());
                for (int i = 0; i < (int) names.size(); i++) {
                    nsPrefixes[names[i]] = "ns" + std::to_string(i + 1) + "_";
                }
            }

            // The prefix of a package: empty for `rtl`, and for a name the emitter
            // cannot attribute to any package (leaving it alone beats mangling it
            // into a symbol that does not exist).
            Str nsPrefix(const Str &package) {
                auto found = nsPrefixes.find(package);
                return found == nsPrefixes.end() ? Str() : found->second;
            }

            Str qualify(const Str &package, const Str &name) {
                return nsPrefix(package) + name;
            }

            // The package a declared type (data class, enum, typealias) came from.
            Str typePackage(const Str &name) {
                auto found = typePackages.find(name);
                return found == typePackages.end() ? Str() : found->second;
            }

            // The package of the plain (non-native) function `name`, or "". Matches
            // by name only, like the `hasPlainFunction` probes at the call sites - and
            // a method is not a plain function, so a call by name cannot resolve to
            // one (two packages may spell the same method name).
            Str functionPackage(const Str &name) {
                for (const Fn &fn: functions) {
                    if (fn.decl->isNative || fn.isMethod || fn.decl->name != name) continue;
                    return fn.packageName;
                }
                return Str();
            }

            // The declared type of a file-level static, for expression inference.
            ast::TypePtr staticType(const Str &name) {
                auto found = staticsByName.find(name);
                return found == staticsByName.end() ? nullptr : found->second.decl->type;
            }

            void collect() {
                collectPackages();
                for (const Input &input: inputs) {
                    Str pkg = inputPackage(input);
                    for (const ast::DeclPtr &decl: input.module.declarations) {
                        if (decl->kind == DeclKind::Var) {
                            // A file-level static: storage and an initializer for the
                            // generated pass (specs/statics.md). Prelude inputs declare the
                            // runtime surface, not program statics, so they are skipped.
                            if (!input.prelude) {
                                Static entry;
                                entry.decl = decl.get();
                                entry.packageName = pkg;
                                entry.file = input.fileName;
                                statics.push_back(entry);
                                staticsByName[decl->name] = entry;
                            }
                            continue;
                        }
                        if (decl->kind == DeclKind::Function) {
                            if (decl->isNative) {
                                NativeDecl native;
                                native.decl = decl.get();
                                native.file = input.fileName;
                                native.prelude = input.prelude;
                                native.symbol = decl->hasNativeSymbol
                                                    ? unquote(decl->nativeSymbol)
                                                    : decl->name;
                                nativeDecls.push_back(native);
                                nativeSymbols[decl->name] = native.symbol;
                                // A native with an explicit `this` first parameter is an
                                // extension method; member calls lower to symbol(receiver, ...).
                                if (!decl->params.empty() && decl->params[0].name == "this") {
                                    NativeExt ext;
                                    ext.symbol = native.symbol;
                                    ext.receiver = decl->params[0].type;
                                    ext.returnType = decl->returnType;
                                    ext.typeParams = decl->functionTypeParams;
                                    nativeExtensions[decl->name].push_back(ext);
                                }
                            }
                            addFunction(decl.get(),
                                        decl->hasReceiver ? decl->receiverType : nullptr,
                                        input.fileName, decl->functionTypeParams, input.prelude, pkg);
                            continue;
                        }
                        types[decl->name] = decl.get();
                        typePackages[decl->name] = pkg;
                        if (decl->kind == DeclKind::Enum) {
                            enumNames[decl->name] = true;
                        }
                        if (decl->kind == DeclKind::DataClass) {
                            // Prelude data classes (XmlNode, Attribute, Span) map
                            // onto RTL C++ types whose methods are C++ members, so
                            // their mirror methods must NOT be lowered to free
                            // functions: a member call falls through to
                            // `recv.method()`. Non-prelude data classes (the
                            // mirrors) keep the free-function-with-receiver shape.
                            if (input.prelude) continue;
                            dataClassNames[decl->name] = true;
                            ast::TypePtr receiver = classReceiver(*decl);
                            for (const ast::DeclPtr &method: decl->methods) {
                                List<Str> methodParams = decl->typeParams;
                                for (const Str &param: method->functionTypeParams) {
                                    methodParams.push_back(param);
                                }
                                addFunction(method.get(), receiver, input.fileName, methodParams,
                                            input.prelude, pkg, true);
                            }
                        }
                    }
                }
            }

            // The program-level facts the semantic step on the lowered body reads
            // (sema/TypeInfer.h), built from the tables `collect` filled. Filling them
            // copies no declarations: the type nodes are shared.
            sema::Facts collectFacts() {
                sema::Facts facts;
                for (const auto &entry: types) facts.types[entry.first] = entry.second;
                for (const auto &entry: enumNames) facts.enumNames[entry.first] = entry.second;
                for (const Fn &fn: functions) {
                    sema::FnFact fact;
                    fact.decl = fn.decl;
                    fact.receiver = fn.receiver;
                    fact.templateParams = fn.templateParams;
                    facts.functions.push_back(fact);
                }
                for (const auto &entry: nativeExtensions) {
                    List<sema::ExtFact> overloads;
                    for (const NativeExt &ext: entry.second) {
                        sema::ExtFact fact;
                        fact.receiver = ext.receiver;
                        fact.returnType = ext.returnType;
                        fact.typeParams = ext.typeParams;
                        overloads.push_back(fact);
                    }
                    facts.nativeExtensions[entry.first] = overloads;
                }
                for (const auto &entry: staticsByName) {
                    facts.statics[entry.first] = entry.second.decl->type;
                }
                return facts;
            }

            // ---- type mapping ---------------------------------------------

            void setActiveTypeParams(const List<Str> &params) {
                activeTypeParams.clear();
                for (const Str &param: params) {
                    activeTypeParams[param] = true;
                }
            }

            Str templateClause(const List<Str> &params) {
                if (params.empty()) return "";
                List<Str> parts;
                for (const Str &param: params) {
                    parts.push_back("class " + param);
                }
                return "template <" + join(parts, ", ") + ">";
            }

            // Renders the template argument list for a generic name, applying the
            // built-in parameter reorder (SmallVector<N, T> -> SmallVector<T, N>).
            Str typeArgsString(const Str &baseName, const List<ast::TypePtr> &args) {
                List<Str> rendered;
                for (const ast::TypePtr &arg: args) {
                    rendered.push_back(type(*arg));
                }
                if (baseName == "SmallVector" && rendered.size() == 2) {
                    Str first = rendered[0];
                    rendered[0] = rendered[1];
                    rendered[1] = first;
                }
                return join(rendered, ", ");
            }

            Str typeName(const Str &name, const common::SourcePos &pos) {
                if (name == "Unit") return "void";
                if (activeTypeParams.count(name) > 0) return name;
                // A declared type shadows an RTL type *name*: a compiler-side view
                // type of the same name is a different type from the RTL's. The
                // RTL's own prelude types keep their C++ spelling unprefixed.
                if (types.count(name) > 0) {
                    const Str packageName = typePackage(name);
                    if (packageName == "rtl") return name;
                    return qualify(packageName, name);
                }
                if (isRtlTypeName(name)) return name;
                fail(pos, "unsupported type '" + name + "'");
                return "/*unsupported*/";
            }

            Str type(const ast::TypeExpr &typeExpr) {
                switch (typeExpr.kind) {
                    case TypeKind::IntLit:
                        return typeExpr.text;
                    case TypeKind::Named:
                        return typeName(typeExpr.name, typeExpr.pos);
                    case TypeKind::Generic:
                        return typeName(typeExpr.name, typeExpr.pos)
                               + "<" + typeArgsString(typeExpr.name, typeExpr.typeArgs) + ">";
                    case TypeKind::Reference:
                        return "std::shared_ptr<"
                               + (typeExpr.inner ? type(*typeExpr.inner) : Str("void")) + ">";
                    case TypeKind::Pointer:
                        return (typeExpr.inner ? type(*typeExpr.inner) : Str("void")) + "*";
                    case TypeKind::Function: {
                        Str ret = typeExpr.returnType ? type(*typeExpr.returnType) : Str("void");
                        List<Str> params;
                        for (const ast::TypePtr &param: typeExpr.paramTypes) {
                            params.push_back(type(*param));
                        }
                        return "Func<" + ret + "(" + join(params, ", ") + ")>";
                    }
                }
                return "/*unsupported*/";
            }

            NameKind kindOf(const ast::TypeExpr &typeExpr) {
                if (typeExpr.kind == TypeKind::Reference) return NameKind::Shared;
                if (typeExpr.kind == TypeKind::Pointer) return NameKind::Pointer;
                if (typeExpr.kind == TypeKind::Generic && typeExpr.name == "PList") {
                    return NameKind::Shared;
                }
                return NameKind::Value;
            }

            // ---- declarations ---------------------------------------------

            // Storage for every file-level static, value-initialized so it starts empty:
            // the generated pass below fills in the initializers, and a read that happens
            // first yields the empty value rather than indeterminate data
            // (specs/statics.md).
            void emitStatics() {
                for (const Static &entry: statics) {
                    curFile = entry.file;
                    Str storage = qualify(entry.packageName, entry.decl->name);
                    if (failed) return;
                    sourceComment(entry.decl->pos);
                    line(0, type(*entry.decl->type) + " " + storage + "{};");
                    if (failed) return;
                }
            }

            // Whether any static has an initializer, i.e. whether the pass is needed.
            bool hasStaticInit() {
                for (const Static &entry: statics) {
                    if (entry.decl->init) return true;
                }
                return false;
            }

            // The generated initialization pass (specs/statics.md): the initializers of the
            // file-level statics, run before the body of `main`. It is emitted here rather
            // than as C++ static initialization so that the language owns the order, and it
            // is emitted in declaration order - which the language does not guarantee, so a
            // program must not depend on one static being initialized before another.
            void emitStaticInit() {
                if (!hasStaticInit()) return;
                line(0, "// File-level static storage (specs/statics.md): initialized before main's body.");
                line(0, "void simse_initStatics() {");
                for (const Static &entry: statics) {
                    if (!entry.decl->init) continue;
                    curFile = entry.file;
                    Str storage = qualify(entry.packageName, entry.decl->name);
                    line(1, storage + " = " + expr(*entry.decl->init, 0, entry.decl->type) + ";");
                    if (failed) return;
                }
                line(0, "}");
            }

            // Every aggregate the program declares, named once *before* any of them is
            // defined. A generated struct may hold a *pointer* to a type from another
            // package (`linear::IlFunction`'s facts), and packages are emitted in scan
            // order, so the definition would otherwise be used before it exists. A
            // forward declaration is all a pointer, a reference or a parameter needs -
            // and it is what lets a generated data class name another package's type at
            // all, which is otherwise a constraint the emitters have to work around.
            void emitForwardTypes() {
                for (const Input &input: inputs) {
                    if (input.prelude) continue;
                    for (const ast::DeclPtr &decl: input.module.declarations) {
                        if (decl->kind != DeclKind::DataClass) continue;
                        Str tmpl = templateClause(decl->typeParams);
                        if (!tmpl.empty()) line(0, tmpl);
                        line(0, "struct " + qualify(typePackage(decl->name), decl->name) + ";");
                    }
                }
            }

            void emitTypes() {
                for (const Input &input: inputs) {
                    if (input.prelude) continue;
                    curFile = input.fileName;
                    for (const ast::DeclPtr &decl: input.module.declarations) {
                        if (decl->kind == DeclKind::DataClass) {
                            emitDataClass(*decl);
                        } else if (decl->kind == DeclKind::Enum) {
                            emitEnum(*decl);
                            emitEnumConversion(*decl);
                        } else if (decl->kind == DeclKind::TypeAlias) {
                            emitTypeAlias(*decl);
                        }
                        if (failed) return;
                    }
                }
            }

            void emitDataClass(const ast::Decl &decl) {
                setActiveTypeParams(decl.typeParams);
                List<Str> params;
                List<Str> values;
                for (const ast::Field &field: decl.fields) {
                    if (!field.type) {
                        fail(field.pos, "unsupported: field '" + field.name + "' without a type");
                        return;
                    }
                    params.push_back(type(*field.type) + " " + field.name);
                    values.push_back(field.name);
                }
                if (failed) return;

                sourceComment(decl.pos);
                Str emittedName = qualify(typePackage(decl.name), decl.name);
                Str tmpl = templateClause(decl.typeParams);
                // Generated aggregates follow the language's 4-byte packing rule
                // (specs/memory-model.md); the macros come from rtl/types.hpp.
                line(0, "SIMSE_PACK_PUSH");
                if (!tmpl.empty()) line(0, tmpl);
                line(0, "struct " + emittedName + " {");
                for (const ast::Field &field: decl.fields) {
                    line(1, type(*field.type) + " " + field.name + ";");
                }
                line(0, "};");
                line(0, "SIMSE_PACK_POP");

                // The struct stays an aggregate (so it is default-constructible
                // where C++ needs it, e.g. the payload of a failed Res<T>);
                // construction goes through a `_make_<Name>` factory so callers
                // keep the `Name(args)` shape without an emitted constructor.
                Str target = emittedName;
                if (!decl.typeParams.empty()) target += "<" + join(decl.typeParams, ", ") + ">";
                if (!tmpl.empty()) line(0, tmpl);
                line(0, target + " " + qualify(typePackage(decl.name), "_make_" + decl.name)
                       + "(" + join(params, ", ") + ") {");
                line(1, "return " + target + "{" + join(values, ", ") + "};");
                line(0, "}");
            }

            void emitEnum(const ast::Decl &decl) {
                sourceComment(decl.pos);
                Str tmpl = templateClause(decl.typeParams);
                if (!tmpl.empty()) line(0, tmpl);
                line(0, "enum class " + qualify(typePackage(decl.name), decl.name) + " {");
                for (const ast::EnumMember &member: decl.members) {
                    Str text = member.name;
                    if (member.hasValue) text += " = " + std::to_string(member.value);
                    line(1, text + ",");
                }
                line(0, "};");
            }

            // A checked `Enum.fromInt(Int): Opt<Enum>` helper. Duplicate member
            // values are allowed, so the value test is an if-chain, not a switch.
            void emitEnumConversion(const ast::Decl &decl) {
                if (!decl.typeParams.empty()) return; // generic enums are unusual
                List<Int> values;
                List<Str> names;
                int next = 0;
                for (const ast::EnumMember &member: decl.members) {
                    if (member.hasValue) next = member.value;
                    values.push_back(next);
                    names.push_back(member.name);
                    next += 1;
                }
                Str emittedName = qualify(typePackage(decl.name), decl.name);
                line(0, "inline Opt<" + emittedName + "> "
                       + qualify(typePackage(decl.name), "simse_" + decl.name + "_fromInt")
                       + "(Int value) {");
                for (int i = 0; i < (int) names.size(); i++) {
                    line(1, "if (value == " + std::to_string(values[i]) + ") return Opt<" + emittedName
                           + ">::some(" + emittedName + "::" + names[i] + ");");
                }
                line(1, "return Opt<" + emittedName + ">::none();");
                line(0, "}");
            }

            void emitTypeAlias(const ast::Decl &decl) {
                if (!decl.targetType) {
                    fail(decl.pos, "unsupported: typealias '" + decl.name + "' without a target type");
                    return;
                }
                setActiveTypeParams(decl.typeParams);
                Str target = type(*decl.targetType);
                if (failed) return;
                sourceComment(decl.pos);
                Str tmpl = templateClause(decl.typeParams);
                if (!tmpl.empty()) line(0, tmpl);
                line(0, "using " + qualify(typePackage(decl.name), decl.name) + " = " + target + ";");
            }

            // Native functions are declared once at the top of the file; their
            // symbols are hand-written C++ (impl_specs/native-interop.md).
            void emitNativeDeclarations() {
                setActiveTypeParams({});
                for (const NativeDecl &native: nativeDecls) {
                    if (native.prelude) continue; // provided by the RTL header
                    curFile = native.file;
                    const ast::Decl &decl = *native.decl;
                    setActiveTypeParams(decl.functionTypeParams);
                    if (native.symbol.find("::") != Str::npos) {
                        fail(decl.pos, "unsupported: namespaced native symbol '" + native.symbol
                                       + "' needs a global wrapper");
                        return;
                    }
                    Str ret = decl.returnType ? type(*decl.returnType) : Str("void");
                    if (failed) return;
                    List<Str> params;
                    for (const ast::Param &param: decl.params) {
                        if (!param.type) {
                            fail(param.pos, "unsupported: native parameter '" + param.name
                                            + "' without a type");
                            return;
                        }
                        Str mapped = type(*param.type);
                        if (failed) return;
                        // An explicit `this` receiver is emitted as `self` so the
                        // declaration is valid C++ and matches the call lowering.
                        Str name = param.name == "this" ? Str("self") : param.name;
                        if (param.type->kind == TypeKind::Pointer
                            || param.type->kind == TypeKind::Reference) {
                            params.push_back(mapped + " " + name);
                        } else {
                            params.push_back("const " + mapped + "& " + name);
                        }
                    }
                    sourceComment(decl.pos);
                    Str tmpl = templateClause(decl.functionTypeParams);
                    if (!tmpl.empty()) line(0, tmpl);
                    line(0, ret + " " + native.symbol + "(" + join(params, ", ") + ");");
                }
            }

            // ---- prelude reachability ---------------------------------------

            // The program's string literals, as the read-only table at the top of the
            // file (`__sm_stringTable`). The walk above collects them in first-encounter
            // order; they are then sorted, so the table is canonical and two rings that
            // walk in different orders still agree on every index.
            Dictionary<Str, int> literalAt;
            List<Str> literals;

            void sortLiterals() {
                std::sort(literals.begin(), literals.end());
                literalAt.clear();
                for (int i = 0; i < (int) literals.size(); i++) literalAt[literals[i]] = i;
            }

            // One entry per distinct literal, built once before `main` runs. A literal
            // site then *reads* an entry instead of building a `Str`, so a use that only
            // reads the text - a comparison, or a `const Str&` argument - never constructs
            // one, and a literal longer than the inline capacity never allocates at the
            // site. An owned position (`x = "..."`, `return`, a by-value parameter) still
            // copies, because that is what the language's value semantics say.
            void emitStringTable() {
                if (literals.empty()) return;
                line(0, "// The program's string literals: one table, built once, read by every");
                line(0, "// site that mentions one (impl_specs/rtl-abi.md).");
                line(0, "static const Str __sm_stringTable[" + std::to_string(literals.size())
                                + "] = {");
                for (const Str &literal: literals) line(1, literal + ",");
                line(0, "};");
                line(0, "");
            }

            // A prelude body costs a program only what it uses: `List<T>.smToYield` is
            // written in Simse (impl_specs/for.md), and a program that never iterates a
            // container should not carry its machine. The rule is a name reachability over
            // the *calls*: a callee is a name (`f(x)`), a generic name (`f<Int>(x)`) or a
            // member (`x.m(...)`), and in all three the call site spells it as `text`.
            void collectNames(const ast::Expr &expr, Dictionary<Str, bool> &names) {
                // The string literals ride the same walk: this is the emitter's one pass
                // over the whole program, so the table below covers every body it will
                // emit. A literal the *lowering* invents is not in the parsed program and
                // keeps its own spelling at the site.
                if (expr.kind == ExprKind::StrLit && literalAt.count(expr.text) == 0) {
                    literalAt[expr.text] = 0;
                    literals.push_back(expr.text);
                }
                if (expr.kind == ExprKind::Call && expr.lhs) {
                    const ast::Expr &callee = *expr.lhs;
                    if ((callee.kind == ExprKind::Name || callee.kind == ExprKind::GenericName
                         || callee.kind == ExprKind::Member)
                        && !callee.text.empty()) {
                        names[callee.text] = true;
                    }
                }
                if (expr.lhs) collectNames(*expr.lhs, names);
                if (expr.rhs) collectNames(*expr.rhs, names);
                for (const ast::TypePtr &arg: expr.typeArgs) {
                    collectTypeNames(arg);
                }
                for (const ast::TypePtr &param: expr.paramTypes) {
                    collectTypeNames(param);
                }
                for (const ast::ExprPtr &arg: expr.args) {
                    if (arg) collectNames(*arg, names);
                }
                for (const ast::StmtPtr &stmt: expr.body) {
                    if (stmt) collectNames(*stmt, names);
                }
            }

            void collectNames(const ast::Stmt &stmt, Dictionary<Str, bool> &names) {
                collectTypeNames(stmt.type);
                const ast::ExprPtr *expressions[] = {&stmt.init,   &stmt.cond, &stmt.target,
                                                     &stmt.value,   &stmt.returnValue,
                                                     &stmt.expr};
                for (const ast::ExprPtr *expression: expressions) {
                    if (*expression) collectNames(**expression, names);
                }
                for (const ast::StmtPtr &child: stmt.body) {
                    if (child) collectNames(*child, names);
                }
                for (const ast::StmtPtr &child: stmt.thenBody) {
                    if (child) collectNames(*child, names);
                }
                for (const ast::StmtPtr &child: stmt.elseBody) {
                    if (child) collectNames(*child, names);
                }
            }

            void collectNames(const ast::Decl &decl, Dictionary<Str, bool> &names) {
                collectTypeNames(decl.type);
                collectTypeNames(decl.targetType);
                collectTypeNames(decl.receiverType);
                collectTypeNames(decl.returnType);
                for (const ast::Field &field: decl.fields) {
                    collectTypeNames(field.type);
                }
                for (const ast::Param &param: decl.params) {
                    collectTypeNames(param.type);
                }
                if (decl.init) collectNames(*decl.init, names);
                for (const ast::StmtPtr &stmt: decl.body) {
                    if (stmt) collectNames(*stmt, names);
                }
                for (const ast::DeclPtr &method: decl.methods) {
                    if (method) collectNames(*method, names);
                }
            }

            void collectNames(const ast::Module &module, Dictionary<Str, bool> &names) {
                for (const ast::DeclPtr &decl: module.declarations) {
                    if (decl) collectNames(*decl, names);
                }
            }

            // Whether a prelude body is one the program reaches: its name is called, and -
            // for an extension - the program names the receiver's type as well. The
            // prelude has one `smToYield` per container (`impl_specs/for.md`), each
            // container's machine is that container's only, and the class name is the
            // receiver's (`outerTypeName`).
            bool reachesPreludeBody(const Fn &fn) {
                if (!fn.decl->hasBody) return false;
                if (referencedNames.count(fn.decl->name) == 0) return false;
                const Str receiverName = outerTypeName(fn.receiver);
                if (receiverName.empty()) return true;
                // A receiver that is the function's own type parameter says nothing -
                // any type can be one.
                const bool isTypeParam =
                    std::find(fn.decl->functionTypeParams.begin(),
                              fn.decl->functionTypeParams.end(), receiverName)
                    != fn.decl->functionTypeParams.end();
                return isTypeParam || referencedTypes.count(receiverName) > 0;
            }

            // Fills `referencedNames` and `referencedTypes` from the program - never from
            // the prelude's own unused bodies - and closes both over the prelude the
            // program reaches: an emitted body may call another, and a native's signature
            // is what says which types a call reaches (`xs.toArray()` reaches an `Array`).
            void collectProgramNames() {
                for (const Input &input: inputs) {
                    if (input.prelude) continue;
                    collectNames(input.module, referencedNames);
                }
                bool changed = true;
                while (changed) {
                    changed = false;
                    const size_t namesBefore = referencedNames.size();
                    const size_t typesBefore = referencedTypes.size();
                    for (const Fn &fn: functions) {
                        if (!fn.prelude) continue;
                        if (fn.decl->hasBody) {
                            if (!reachesPreludeBody(fn)) continue;
                            collectNames(*fn.decl, referencedNames);
                            continue;
                        }
                        if (referencedNames.count(fn.decl->name) == 0) continue;
                        collectTypeNames(fn.receiver);
                        collectTypeNames(fn.decl->returnType);
                        for (const ast::Param &param: fn.decl->params) {
                            collectTypeNames(param.type);
                        }
                    }
                    if (referencedNames.size() != namesBefore
                        || referencedTypes.size() != typesBefore) {
                        changed = true;
                    }
                }
            }

            void emitFunctions(bool prototypeOnly, const sema::Facts &facts) {
                for (const Fn &fn: functions) {
                    // A prelude input is declarations-only *unless it has a body*, and a
                    // prelude `fun` with a body is a function the language itself
                    // provides - `List<T>.smToYield(): ..T` (impl_specs/for.md), emitted
                    // when the program reaches it (`reachesPreludeBody`).
                    if (fn.prelude && !reachesPreludeBody(fn)) continue;
                    curFile = fn.file;
                    curPrelude = fn.prelude;
                    emitFunction(fn, prototypeOnly, facts);
                    if (failed) return;
                }
            }

            void beginScope(const Fn &fn, NameKind selfK, const ast::TypePtr &selfTypePtr) {
                nameKinds.clear();
                localTypes.clear();
                selfKind = selfK;
                selfType = selfTypePtr;
                if (fn.receiver) {
                    nameKinds["self"] = selfK;
                }
                for (const ast::Param &param: fn.decl->params) {
                    if (param.type) {
                        nameKinds[param.name] = kindOf(*param.type);
                        localTypes[param.name] = param.type;
                    }
                }
            }

            // The C++ parameter form of a receiver. A **value** receiver is passed as a
            // raw pointer to the receiver object: that is the language's own borrow
            // form (`*T`, specs/memory-model.md), it lets a method mutate the caller's
            // object without a C++ reference, and it makes `self` one thing everywhere
            // (`self->field`). A receiver already declared as a handle keeps it - a
            // counted reference (`&T`) stays `std::shared_ptr<T>`, so `self` can still
            // be stored in a list and keeps its refcount; a raw pointer stays `T*`.
            Str receiverParam(const ast::TypePtr &receiverType) {
                Str mapped = type(*receiverType);
                if (receiverType->kind == TypeKind::Reference
                    || receiverType->kind == TypeKind::Pointer) {
                    return mapped + " self";
                }
                return mapped + "* self";
            }

            // Whether a top-level `main` takes the argv form: a single
            // `List<Str>` parameter. The emitter lowers it to
            // `int main(int argc, char** argv)` and builds the Simse list.
            static bool isMainArgs(const ast::Decl &decl) {
                if (decl.params.size() != 1) return false;
                const ast::TypeExpr *type = decl.params[0].type.get();
                return type && type->kind == TypeKind::Generic && type->name == "List"
                       && type->typeArgs.size() == 1 && type->typeArgs[0]
                       && type->typeArgs[0]->kind == TypeKind::Named
                       && type->typeArgs[0]->name == "Str";
            }

            // The outer name of a type, ignoring handles and arguments: `*List<Int>` and
            // `List<Str>` are both `List` - the name a receiver and a machine class are
            // spelled with.
            static Str outerTypeName(const ast::TypePtr &type) {
                const ast::TypeExpr *t = type.get();
                while (t && (t->kind == TypeKind::Reference || t->kind == TypeKind::Pointer)
                       && t->inner) {
                    t = t->inner.get();
                }
                if (!t || (t->kind != TypeKind::Named && t->kind != TypeKind::Generic)) {
                    return "";
                }
                return t->name;
            }

            // Every type name in a type expression, nesting included: `List<Array<Int>>`
            // names both. Fills `referencedTypes` (see its declaration).
            void collectTypeNames(const ast::TypePtr &type) {
                if (!type) return;
                const Str outer = outerTypeName(type);
                if (!outer.empty()) referencedTypes[outer] = true;
                for (const ast::TypePtr &arg: type->typeArgs) {
                    collectTypeNames(arg);
                }
                collectTypeNames(type->inner);
                collectTypeNames(type->returnType);
                for (const ast::TypePtr &param: type->paramTypes) {
                    collectTypeNames(param);
                }
            }

            // The name a machine's class is derived from: the function's own, prefixed
            // with the receiver's outer type name when the function is an extension
            // (`List<T>`'s `smToYield` is `List_smToYield`). The prelude provides a
            // `smToYield` per container (`impl_specs/for.md`), so the function name alone
            // would give every container's machine the same class name.
            static Str machineName(const Fn &fn) {
                if (!fn.receiver) return fn.decl->name;
                const Str outer = outerTypeName(fn.receiver);
                if (outer.empty()) return fn.decl->name;
                return outer + "_" + fn.decl->name;
            }

            // The names the body's own C++ scope already has: the parameters (and `self`,
            // and the two the argv form of `main` writes). A hoisted declaration may not
            // collide with one of them, so the hoisting renames it away
            // (`linear::finishForEmission`'s `reserved`).
            static List<Str> reservedNames(const ast::Decl &decl, bool hasSelf, bool argv) {
                List<Str> names;
                if (hasSelf) names.push_back("self");
                for (const ast::Param &param: decl.params) names.push_back(param.name);
                if (argv) names.push_back("simse_argIndex");
                return names;
            }

            void emitFunction(const Fn &fn, bool prototypeOnly, const sema::Facts &facts) {
                const ast::Decl &decl = *fn.decl;
                // Native functions are declared by emitNativeDeclarations, not here.
                if (decl.isNative) return;

                bool isMain = !fn.receiver && decl.name == "main";
                if (isMain && prototypeOnly) return;
                bool mainArgs = isMain && isMainArgs(decl);
                if (isMain && !decl.params.empty() && !mainArgs) {
                    fail(decl.pos, "unsupported: main with parameters");
                    return;
                }

                setActiveTypeParams(fn.templateParams);
                // A body that yields is lowered to a state machine, and the function to a
                // factory for it (impl_specs/yield.md) - so the return type of the
                // emitted function is the machine's class, not the `..T` the source
                // wrote.
                const bool yielding = decl.returnType
                                      && decl.returnType->kind == ast::TypeKind::Yield;
                // The class the machine is: its *name* is what the emitted function
                // returns, and for a generic function the class is a template, so the
                // name carries the function's type parameters wherever it is a type
                // (inside the class the injected-class-name covers `self`).
                const Str yieldClass = qualify(fn.packageName, machineName(fn)) + "_yieldable";
                Str yieldType = yieldClass;
                if (yielding && !decl.functionTypeParams.empty()) {
                    yieldType = yieldClass + "<" + join(decl.functionTypeParams, ", ") + ">";
                }
                Str ret = isMain ? "int"
                                 : (yielding ? yieldType
                                             : (decl.returnType ? type(*decl.returnType)
                                                                : Str("void")));
                if (failed) return;

                List<Str> params;
                bool hasSelf = false;
                NameKind selfK = NameKind::Value;
                ast::TypePtr selfTypePtr;
                if (fn.receiver) {
                    params.push_back(receiverParam(fn.receiver));
                    hasSelf = true;
                    selfK = kindOf(*fn.receiver);
                    selfTypePtr = fn.receiver;
                    if (failed) return;
                }
                // The argv form's parameter is built from argc/argv, not passed.
                if (!mainArgs) {
                    for (const ast::Param &param: decl.params) {
                        if (!param.type) {
                            fail(param.pos, "unsupported: parameter '" + param.name + "' without a type");
                            return;
                        }
                        if (param.name == "this" && !hasSelf) {
                            params.push_back(receiverParam(param.type));
                            hasSelf = true;
                            selfK = kindOf(*param.type);
                            selfTypePtr = param.type;
                        } else {
                            params.push_back(type(*param.type) + " " + param.name);
                        }
                        if (failed) return;
                    }
                }
                // A bare `this` uses `self`; make sure it resolves even without an
                // explicit receiver declaration.
                if (!hasSelf) {
                    selfK = NameKind::Value;
                }

                Str signature = mainArgs
                                    ? Str("int main(int argc, char** argv)")
                                    : (ret + " " + (isMain ? Str("main") : qualify(fn.packageName, decl.name))
                                       + "(" + join(params, ", ") + ")");
                Str tmpl = templateClause(fn.templateParams);
                if (yielding) {
                    // The machine + the factory, and nothing else: the body of the source
                    // function *is* the machine.
                    emitYieldable(fn, decl, yieldClass, yieldType, prototypeOnly, selfK, selfTypePtr,
                                  facts);
                    return;
                }
                if (prototypeOnly) {
                    if (!tmpl.empty()) line(0, tmpl);
                    line(0, signature + ";");
                    return;
                }
                if (!decl.hasBody) {
                    return;
                }

                sourceComment(decl.pos);
                if (!tmpl.empty()) line(0, tmpl);
                line(0, signature + " {");
                beginScope(fn, selfK, selfTypePtr);
                if (isMain && hasStaticInit()) {
                    // Static storage is initialized before the body runs (specs/statics.md).
                    line(1, "simse_initStatics();");
                }
                if (mainArgs) {
                    Str argName = decl.params[0].name;
                    line(1, "List<Str> " + argName + " = List<Str>();");
                    line(1, "int simse_argIndex = 1;");
                    line(1, "while (simse_argIndex < argc) {");
                    line(2, "simse_list_append(" + argName + ", Str(argv[simse_argIndex]));");
                    line(2, "simse_argIndex = simse_argIndex + 1;");
                    line(1, "}");
                }
                curReturnType = decl.returnType;
                // Structured control flow is lowered to labels/gotos, its
                // expressions are extracted into temporaries, and the blocks the
                // lowering wrapped around a declaration are folded again - in a loop,
                // because each stage can leave work for the others
                // (impl_specs/linear-lowering.md). The type pass then spells the
                // declarations, which is what lets their own storage move to the top
                // of the body (`finishForEmission`) and the folding finish the job:
                // the emitter below knows the linear forms only.
                List<ast::StmtPtr> lowered = linear::lowerForEmission(decl.body);
                sema::Body semantics;
                semantics.decl = &decl;
                semantics.selfType = selfTypePtr;
                semantics.typeParams = fn.templateParams;
                // The proof of the pass, kept: it is what tells the backend a slot holds
                // a machine, which a declaration can never say (`..T` is not spellable).
                Dictionary<Str, ast::TypePtr> inferred;
                List<ast::StmtPtr> typed = sema::inferTypes(lowered, facts, semantics, &inferred);
                List<ast::StmtPtr> finalBody = linear::finishForEmission(
                        typed, reservedNames(decl, fn.receiver != nullptr, mainArgs));
                dumpIl(fn, decl, finalBody, facts, inferred);
                emitBody(fn, decl, finalBody, facts, inferred);
                if (failed) return;
                line(0, "}");
            }

            // `--showLinearRepresentation`: the IL of the body the emitter is about
            // to read, on stderr (impl_specs/linear-il.md). The extraction is pure,
            // so the emitted C++ is the same with and without it.
            void dumpIl(const Fn &fn, const ast::Decl &decl, const List<ast::StmtPtr> &body,
                        const sema::Facts &facts,
                        const Dictionary<Str, ast::TypePtr> &inferred) {
                if (!linear::showIl()) return;
                fprintf(stderr, "%s",
                        linear::printIlUnit(
                                linear::extractIlUnit(ilFunction(fn, decl, &facts, &inferred),
                                                      body, fn.file)).c_str());
            }

            // ---- emitting one body -------------------------------------------
            // A body is emitted from its instruction list - the IL is the *only* codegen
            // (impl_specs/linear-il.md). A body the IL cannot spell is a bug in the
            // extractor, not something to fall back from: it fails with the reason.
            void emitBody(const Fn &fn, const ast::Decl &decl, const List<ast::StmtPtr> &body,
                          const sema::Facts &facts,
                          const Dictionary<Str, ast::TypePtr> &inferred) {
                emitBodyAt(ilFunction(fn, decl, &facts, &inferred), body, fn.file, 1);
            }

            // The same, for a body whose frame is not a declaration's: a lambda's, or a
            // state machine's method (both are "parameters plus a class whose fields the
            // body reads and writes").
            void emitBodyAt(const linear::IlFunction &info, const List<ast::StmtPtr> &body,
                            const Str &file, int level) {
                const int prefix = (int) out.size();
                const linear::IlUnit il = linear::extractIlUnit(info, body, file);
                Str text;
                Str reason;
                if (!emitIlBodyText(il, level, text, reason)) {
                    fail(info.decl ? info.decl->pos : common::SourcePos{},
                         "internal: the body of '" + info.symbol
                                 + "' is not expressible in the IL (" + reason + ")");
                    return;
                }
                // A lambda is a closure class, which the text above *constructs* but does
                // not define: the class goes just above the body that builds it.
                Str classes;
                if (!il.closures.empty() && !emitClosureClasses(il, classes, reason)) {
                    fail(info.decl ? info.decl->pos : common::SourcePos{},
                         "internal: a closure class could not be written (" + reason + ")");
                    return;
                }
                out = out.substr(0, prefix) + classes + text;
            }

            // What the extractor needs to know about the body's function: the
            // declaration (name, parameters, return type), the receiver, the emitted
            // symbol, and the file-level statics the body may name.
            linear::IlFunction ilFunction(const Fn &fn, const ast::Decl &decl,
                                          const sema::Facts *facts = nullptr,
                                          const Dictionary<Str, ast::TypePtr> *inferred = nullptr) {
                linear::IlFunction info;
                info.decl = &decl;
                info.receiver = fn.receiver;
                info.symbol = (!fn.receiver && decl.name == "main")
                                  ? Str("main")
                                  : qualify(fn.packageName, decl.name);
                info.facts = facts;
                info.typeParams = fn.templateParams;
                info.inferredTypes = inferred;
                for (const Static &entry: statics) {
                    if (!entry.decl || !entry.decl->type) continue;
                    info.statics[entry.decl->name] = linear::ilTypeText(*entry.decl->type);
                }
                return info;
            }

            // A state machine's method, as the extractor's body context: no declaration,
            // the method's parameters, and - like a lambda - a class whose fields the body
            // reaches through `this`. The *fields* are deliberately not captures: the
            // lowering already spelled every field read and write as an explicit `this.x`
            // member, so a bare name in the body is the method's own - and a parameter that
            // happens to share a field's name (`advance(value: *T)` against a field `value`)
            // must resolve to the parameter.
            linear::IlFunction ilMachineMethod(const Str &className,
                                              const linear::YieldMethod &method,
                                              const sema::Facts *facts = nullptr,
                                              const Dictionary<Str, ast::TypePtr> *inferred = nullptr) {
                linear::IlFunction info;
                info.symbol = className + "::" + method.name;
                info.closureSymbol = className;
                info.facts = facts;
                info.inferredTypes = inferred;
                for (const ast::Param &param: method.params) {
                    info.paramNames.push_back(param.name);
                    info.paramTypes.push_back(param.type);
                }
                for (const Static &entry: statics) {
                    if (!entry.decl || !entry.decl->type) continue;
                    info.statics[entry.decl->name] = linear::ilTypeText(*entry.decl->type);
                }
                return info;
            }

            // ---- emitting from the IL ---------------------------------------

            // The unit being emitted, so an instruction can reach the closures the body
            // constructs and the bodies they carry.
            const linear::IlUnit *ilUnit = nullptr;
            // The body currently being emitted: a construction's type operand indexes
            // *its own* body's type table, which for a lambda is a nested one.
            const linear::IlBody *ilBody = nullptr;
            Dictionary<Str, bool> closureSymbols;   // the classes this unit constructs
            Dictionary<Str, bool> emittedClosures;  // ... of which these are written out
            Dictionary<Str, bool> emittedYieldables; // the state machines already written
            //
            // The C++ of a body comes from its instruction list - the IL is the *only*
            // codegen (impl_specs/linear-il.md). The IL is not a second language with a
            // second spelling: an operand becomes a leaf `ast::Expr` - a slot is a name,
            // a constant is its literal, a place is the path it came from, folded back
            // out of the instruction that built it - and the helpers above write the
            // text.
            struct IlFrame {
                // Keyed by *slot index*: two scopes may declare the same name, and the
                // frame keeps them apart (the lowering gives each its own slot), so an
                // analysis keyed by name would merge two different variables.
                Dictionary<int, int> defOp;       // slot -> the op that first writes it
                Dictionary<int, int> defineCount;
                Dictionary<int, int> useCount;
            };

            static int ilIntAt(const Dictionary<Str, int> &map, const Str &key, int fallback) {
                auto found = map.find(key);
                return found == map.end() ? fallback : found->second;
            }

            static int ilIntAt(const Dictionary<int, int> &map, int key, int fallback) {
                auto found = map.find(key);
                return found == map.end() ? fallback : found->second;
            }

            static void ilBump(Dictionary<int, int> &map, int key) {
                map[key] = ilIntAt(map, key, 0) + 1;
            }

            static int ilOperandAt(const List<int> &operands, int index) {
                return index >= 0 && index < (int) operands.size() ? operands[index] : -1;
            }

            static ast::ExprPtr ilNameNode(const Str &text) {
                auto node = std::make_shared<ast::Expr>();
                node->kind = ExprKind::Name;
                node->text = text;
                return node;
            }

            // The frame's types, from the body's own tables - no statement tree is read.
            // First what the *type pass* proved for every name in the body, then the
            // slots' declared types (which win: they are the spelled ones, and the map
            // may still hold a name the shadowing pass renamed). The pass's record is
            // what carries a machine: a slot holding one is `..T`, a declaration is
            // never written with that (the emitted C++ uses `auto`), so the frame is
            // the only place the type survives - and the emitter needs it, because
            // `x.smToYield()` on a machine *is* `x`, an identity decided from the
            // receiver's type (see `call`, impl_specs/for.md).
            void ilSeedFrameTypes(const linear::IlBody &il) {
                for (const auto &entry: il.inferredTypes) {
                    localTypes[entry.first] = entry.second;
                    nameKinds[entry.first] = kindOf(*entry.second);
                }
                for (int i = 0; i < (int) il.vars.size(); i++) {
                    ast::TypePtr slotType = linear::ilVarType(il, i);
                    if (!slotType) continue;
                    localTypes[il.vars[i].name] = slotType;
                    nameKinds[il.vars[i].name] = kindOf(*slotType);
                }
            }

            // The destination slot of an instruction, or -1 when it writes memory or
            // jumps instead (`ilWritesDestination` is the one place that is stated).
            static int ilDst(const linear::IlOp &op) {
                if (!linear::ilWritesDestination(op.kind) || op.operands.empty()) return -1;
                return op.operands[0];
            }

            void ilAnalyze(const linear::IlBody &il, IlFrame &frame) {
                for (int i = 0; i < (int) il.ops.size(); i++) {
                    const linear::IlOp &op = il.ops[i];
                    if (op.kind == IlOpKind::Declare) continue; // a declaration reads nothing
                    // An instruction that writes memory or jumps has no destination,
                    // but its operands are reads like any other - so the two are
                    // counted apart, and the first operand is only skipped when it is
                    // in fact the destination.
                    const int dst = ilDst(op);
                    if (dst >= 0 && dst < (int) il.vars.size()) {
                        // The *first* write is what initialises a slot: a loop target is
                        // written again every iteration, and the declaration that spells
                        // it wants the initializer, not the increment.
                        if (frame.defOp.count(dst) == 0) frame.defOp[dst] = i;
                        ilBump(frame.defineCount, dst);
                    }
                    for (int j = 0; j < (int) op.operands.size(); j++) {
                        if (j == 0 && dst >= 0) continue;
                        const linear::IlOperandKind kind = linear::ilOperandKind(op, j);
                        if (kind != linear::IlOperandKind::Var
                            && kind != linear::IlOperandKind::Value) {
                            continue; // a label, a pool entry, a type, a callee
                        }
                        const int operand = op.operands[j];
                        if (operand < 0 || operand >= (int) il.vars.size()) continue;
                        ilBump(frame.useCount, operand);
                    }
                }
            }

            // Whether an instruction's result is inlined at its use instead of being
            // assigned to a slot: the extractor's own temporaries (a place, a
            // constant expression) with a single definition and a single use are
            // skipped, and the expression stands where they were read - which is where
            // the statement path inlined it.
            bool ilFolded(const linear::IlBody &il, const IlFrame &frame, int slot) const {
                if (slot < 0 || slot >= (int) il.vars.size()) return false;
                if (il.vars[slot].kind != linear::IlVarKind::Temp) return false;
                if (ilIntAt(frame.defineCount, slot, 0) != 1) return false;
                if (ilIntAt(frame.useCount, slot, 0) != 1) return false;
                // A closure is an aggregate, not an expression: it keeps its slot.
                return !ilSlotHoldsClosure(il, frame, slot);
            }

            // Whether an instruction builds a closure class instance, which is an
            // *aggregate* and not an expression: it cannot stand inside another
            // expression, so its slot is never folded away.
            bool ilConstructsClosure(const linear::IlOp &op) const {
                if (op.kind != IlOpKind::CallCtor || ilBody == nullptr) return false;
                const int typeAt = ilOperandAt(op.operands, 1);
                if (typeAt < 0 || typeAt >= (int) ilBody->types.size()) return false;
                return closureSymbols.count(ilBody->types[typeAt]) > 0;
            }

            // The same question, asked about a slot: is the instruction that defines it
            // a closure construction?
            bool ilSlotHoldsClosure(const linear::IlBody &il, const IlFrame &frame, int slot) const {
                const int def = ilIntAt(frame.defOp, slot, -1);
                if (def < 0 || def >= (int) il.ops.size()) return false;
                return ilConstructsClosure(il.ops[def]);
            }

            // The C++ of a slot's declared type. A closure class is spelled by its own
            // name: it is emitted just above the body that constructs it, so no type
            // dictionary knows it (`type` would refuse an unsupported name).
            Str ilDeclTypeText(const linear::IlBody &il, int slot, const ast::TypeExpr &slotType) {
                const int typeIndex = il.vars[slot].typeIndex;
                if (typeIndex >= 0 && typeIndex < (int) il.types.size()
                    && closureSymbols.count(il.types[typeIndex]) > 0) {
                    return il.types[typeIndex];
                }
                return type(slotType);
            }

            // A constant operand: the pool holds the text the C++ prints, so all that
            // is left is to give it the node kind the emitter expects (`true` is a
            // BoolLit, `"abc"` a StrLit, a digit run an IntLit or a FloatLit).
            static ast::ExprPtr ilLiteralNode(const Str &text) {
                auto node = std::make_shared<ast::Expr>();
                node->text = text;
                bool dot = false;
                for (int i = 0; i < (int) text.size(); i++) {
                    if (text[i] == '.') dot = true;
                }
                if (text.empty()) {
                    node->kind = ExprKind::StrLit;
                } else if (text[0] == '"') {
                    node->kind = ExprKind::StrLit;
                } else if (text[0] == '\'') {
                    node->kind = ExprKind::CharLit;
                } else if (text == "true" || text == "false") {
                    node->kind = ExprKind::BoolLit;
                    node->boolValue = text == "true";
                } else {
                    node->kind = dot ? ExprKind::FloatLit : ExprKind::IntLit;
                }
                return node;
            }

            ast::ExprPtr ilSlotNode(const linear::IlBody &il, const IlFrame &frame, int slot,
                                    int depth) {
                if (slot < 0 || slot >= (int) il.vars.size() || depth > 24) return nullptr;
                const Str &name = il.vars[slot].name;
                if (name == "self") return ilNameNode("this");
                if (ilFolded(il, frame, slot)) {
                    return ilOpValueNode(il, frame, ilIntAt(frame.defOp, slot, -1), depth + 1);
                }
                return ilNameNode(name);
            }

            ast::ExprPtr ilOperandNode(const linear::IlBody &il, const IlFrame &frame, int operand,
                                       int depth) {
                if (operand < 0) {
                    const int index = -1 - operand;
                    if (index >= (int) il.pool.size()) return nullptr;
                    return ilLiteralNode(il.pool[index]);
                }
                return ilSlotNode(il, frame, operand, depth);
            }

            static ast::ExprPtr ilMemberNode(const ast::ExprPtr &base, const Str &name) {
                if (!base) return nullptr;
                auto node = std::make_shared<ast::Expr>();
                node->kind = ExprKind::Member;
                node->text = name;
                node->lhs = base;
                return node;
            }

            static ast::ExprPtr ilBinaryNode(const ast::ExprPtr &lhs, const Str &op,
                                             const ast::ExprPtr &rhs) {
                if (!lhs || !rhs) return nullptr;
                auto node = std::make_shared<ast::Expr>();
                node->kind = ExprKind::Binary;
                node->text = op;
                node->lhs = lhs;
                node->rhs = rhs;
                return node;
            }

            // A name in a value position that is not a local: an enum member
            // (`Color.Red`, spelled `ns1_Color::Red`) or a file-level `var`.
            ast::ExprPtr ilGetStaticNode(const linear::IlBody &il, const linear::IlOp &op) {
                const int textIndex = ilOperandAt(op.operands, 1);
                if (textIndex < 0 || textIndex >= (int) il.pool.size()) return nullptr;
                const Str &text = il.pool[textIndex];
                int dot = -1;
                for (int i = 0; i < (int) text.size(); i++) {
                    if (text[i] == '.') dot = i;
                }
                if (dot <= 0) return ilNameNode(text);
                return ilMemberNode(ilNameNode(text.substr(0, dot)), text.substr(dot + 1));
            }

            // The node for the type a *static* call is reached through: `Color.fromInt`
            // keeps its name, `Res<Str>.ok` its type arguments.
            //
            // `asGenericName` is for a construction, whose callee the parser always
            // produced as `Name<T>` (that is what makes it a `CallCtor`); a static
            // call's base is a generic name only when the source wrote one.
            ast::ExprPtr ilTypeBaseNode(const linear::IlBody &il, int typeIndex,
                                        bool asGenericName) {
                ast::TypePtr baseType = linear::ilTypeNode(il, typeIndex);
                if (!baseType) return nullptr;
                if (baseType->kind == TypeKind::Generic
                    && (asGenericName || !baseType->typeArgs.empty())) {
                    auto node = std::make_shared<ast::Expr>();
                    node->kind = ExprKind::GenericName;
                    node->text = baseType->name;
                    node->typeArgs = baseType->typeArgs;
                    return node;
                }
                return ilNameNode(baseType->name);
            }

            // A call instruction as the expression the emitter spells: the callee from
            // the method table, the arguments from the operands.
            // Why an instruction could not be expressed: set where the
            // attempt gives up, read by the caller that turns it into a reason line.
            Str ilWhy;

            ast::ExprPtr ilCallNode(const linear::IlBody &il, const IlFrame &frame,
                                    const linear::IlOp &op) {
                const bool hasDst = ilDst(op) >= 0;
                const int methodAt = hasDst ? 1 : 0;
                const int methodIndex = ilOperandAt(op.operands, methodAt);
                if (methodIndex < 0 || methodIndex >= (int) il.methods.size()) {
                    ilWhy = "a call with no callee";
                    return nullptr;
                }
                const linear::IlMethod &method = il.methods[methodIndex];

                auto call = std::make_shared<ast::Expr>();
                call->kind = ExprKind::Call;
                int first = methodAt + 1;
                if (method.kind == linear::IlMethodKind::Method) {
                    ast::ExprPtr recv = ilSlotNode(il, frame, ilOperandAt(op.operands, first), 0);
                    if (!recv) {
                        ilWhy = "the receiver of '" + method.name + "'";
                        return nullptr;
                    }
                    call->lhs = ilMemberNode(recv, method.name);
                    first++;
                } else if (method.staticBase >= 0) {
                    ast::ExprPtr base = ilTypeBaseNode(il, method.staticBase, false);
                    if (!base) {
                        ilWhy = "the type '" + method.name + "' is reached through";
                        return nullptr;
                    }
                    call->lhs = ilMemberNode(base, method.name);
                } else {
                    call->lhs = ilNameNode(method.name);
                }
                if (!call->lhs) return nullptr;
                for (int i = first; i < (int) op.operands.size(); i++) {
                    ast::ExprPtr arg = ilOperandNode(il, frame, op.operands[i], 0);
                    if (!arg) {
                        ilWhy = "an argument of '" + method.name + "'";
                        return nullptr;
                    }
                    call->args.push_back(arg);
                }
                return call;
            }

            // The expression a value-producing instruction computes, as a node. This is
            // both the right-hand side of the instruction and what a *folded* slot
            // stands for wherever it is read.
            ast::ExprPtr ilOpValueNode(const linear::IlBody &il, const IlFrame &frame, int opIndex,
                                       int depth) {
                if (opIndex < 0 || opIndex >= (int) il.ops.size() || depth > 24) return nullptr;
                const linear::IlOp &op = il.ops[opIndex];
                const IlOpKind kind = op.kind;
                if (kind == IlOpKind::SetVar) {
                    return ilOperandNode(il, frame, ilOperandAt(op.operands, 1), depth);
                }
                if (kind == IlOpKind::SetVar_Null) {
                    auto node = std::make_shared<ast::Expr>();
                    node->kind = ExprKind::NullLit;
                    return node;
                }
                if (kind == IlOpKind::BinaryOp) {
                    const int opIndex2 = ilOperandAt(op.operands, 1);
                    if (opIndex2 < 0 || opIndex2 >= (int) il.pool.size()) return nullptr;
                    return ilBinaryNode(ilOperandNode(il, frame, ilOperandAt(op.operands, 2), depth),
                                        il.pool[opIndex2],
                                        ilOperandNode(il, frame, ilOperandAt(op.operands, 3),
                                                      depth));
                }
                if (kind == IlOpKind::UnaryOp) {
                    const int opIndex2 = ilOperandAt(op.operands, 1);
                    ast::ExprPtr operand =
                            ilOperandNode(il, frame, ilOperandAt(op.operands, 2), depth);
                    if (opIndex2 < 0 || opIndex2 >= (int) il.pool.size() || !operand) {
                        return nullptr;
                    }
                    auto node = std::make_shared<ast::Expr>();
                    node->kind = ExprKind::Unary;
                    node->text = il.pool[opIndex2];
                    node->lhs = operand;
                    return node;
                }
                if (kind == IlOpKind::GetField || kind == IlOpKind::FieldAddr) {
                    const int textIndex = ilOperandAt(op.operands, 2);
                    if (textIndex < 0 || textIndex >= (int) il.pool.size()) return nullptr;
                    return ilMemberNode(
                            ilSlotNode(il, frame, ilOperandAt(op.operands, 1), depth),
                            il.pool[textIndex]);
                }
                if (kind == IlOpKind::IndexAddr) {
                    ast::ExprPtr base = ilSlotNode(il, frame, ilOperandAt(op.operands, 1), depth);
                    ast::ExprPtr index = ilOperandNode(il, frame, ilOperandAt(op.operands, 2), depth);
                    if (!base || !index) return nullptr;
                    auto node = std::make_shared<ast::Expr>();
                    node->kind = ExprKind::Index;
                    node->lhs = base;
                    node->rhs = index;
                    return node;
                }
                if (kind == IlOpKind::GetIndex) {
                    ast::ExprPtr base = ilSlotNode(il, frame, ilOperandAt(op.operands, 1), depth);
                    ast::ExprPtr index = ilOperandNode(il, frame, ilOperandAt(op.operands, 2), depth);
                    if (!base || !index) return nullptr;
                    auto node = std::make_shared<ast::Expr>();
                    node->kind = ExprKind::Index;
                    node->lhs = base;
                    node->rhs = index;
                    return node;
                }
                if (kind == IlOpKind::Deref || kind == IlOpKind::CopyValue || kind == IlOpKind::Box) {
                    ast::ExprPtr operand = ilSlotNode(il, frame, ilOperandAt(op.operands, 1), depth);
                    if (!operand) return nullptr;
                    auto node = std::make_shared<ast::Expr>();
                    node->kind = kind == IlOpKind::Deref ? ExprKind::Deref
                                                  : (kind == IlOpKind::CopyValue ? ExprKind::Copy
                                                                          : ExprKind::Ref);
                    node->lhs = operand;
                    return node;
                }
                if (kind == IlOpKind::GetStatic) return ilGetStaticNode(il, op);
                if (kind == IlOpKind::Call || kind == IlOpKind::CallVoid) {
                    return ilCallNode(il, frame, op);
                }
                if (kind == IlOpKind::CallCtor) {
                    ast::ExprPtr callee = ilTypeBaseNode(il, ilOperandAt(op.operands, 1), true);
                    if (!callee) return nullptr;
                    auto call = std::make_shared<ast::Expr>();
                    call->kind = ExprKind::Call;
                    call->lhs = callee;
                    for (int i = 2; i < (int) op.operands.size(); i++) {
                        ast::ExprPtr arg = ilOperandNode(il, frame, op.operands[i], 0);
                        if (!arg) return nullptr;
                        call->args.push_back(arg);
                    }
                    return call;
                }
                // `Cast` (no source node), `CallIndirect` (a callable slot), a lambda
                // body, and anything the extractor marked: not expressible yet.
                return nullptr;
            }

            // Where a jump crosses a declaration, C++ wants a scope: a `goto` may not
            // skip an initialization ([stmt.dcl]/3, MSVC C2362). The flat form has no
            // scopes of its own, so the backend opens the *one* block that keeps the
            // declaration legal - the same one the statement path keeps - and closes it
            // at the label the jump lands on.
            //
            // A declaration is safe inside an open block only when that block starts
            // *after* every jump that crosses it: a jump inside the block would be
            // entering the block's scope past the declaration, which is the same error.
            struct IlCrossing {
                int end = -1;      // the earliest label a crossing jump lands on
                int lastJump = -1; // the last jump that crosses
            };

            static IlCrossing ilJumpCrossing(const linear::IlBody &il, int position) {
                IlCrossing crossing;
                Dictionary<int, int> labelPos;
                for (int i = 0; i < (int) il.ops.size(); i++) {
                    const linear::IlOp &op = il.ops[i];
                    if (op.kind != IlOpKind::Label || op.operands.empty()) continue;
                    labelPos[op.operands[0]] = i;
                }
                for (int i = 0; i < position; i++) {
                    const linear::IlOp &op = il.ops[i];
                    const int labelAt = op.kind == IlOpKind::Goto ? 0
                                        : (op.kind == IlOpKind::IfTrue || op.kind == IlOpKind::IfFalse) ? 1
                                                                                        : -1;
                    if (labelAt < 0) continue;
                    auto found = labelPos.find(ilOperandAt(op.operands, labelAt));
                    if (found == labelPos.end()) continue;
                    if (found->second < position) continue;
                    if (crossing.end < 0 || found->second < crossing.end) crossing.end = found->second;
                    crossing.lastJump = i;
                }
                return crossing;
            }

            // The right-hand side of one instruction, as C++: the expression the
            // instruction computes, or - for a construction that is an aggregate - the
            // brace form, which has no expression node.
            bool ilValueText(const linear::IlBody &il, const IlFrame &frame, int opIndex,
                             const ast::TypePtr &expected, Str &text) {
                if (opIndex < 0 || opIndex >= (int) il.ops.size()) return false;
                const linear::IlOp &op = il.ops[opIndex];
                if (op.kind == IlOpKind::CallCtor) {
                    const int typeAt = ilOperandAt(op.operands, 1);
                    if (typeAt >= 0 && typeAt < (int) il.types.size()
                        && closureSymbols.count(il.types[typeAt]) > 0) {
                        List<Str> captured;
                        for (int j = 2; j < (int) op.operands.size(); j++) {
                            ast::ExprPtr arg = ilOperandNode(il, frame, op.operands[j], 0);
                            if (!arg) return false;
                            captured.push_back(expr(*arg, 0));
                        }
                        text = il.types[typeAt] + "{" + join(captured, ", ") + "}";
                        return true;
                    }
                }
                ast::ExprPtr node = ilOpValueNode(il, frame, opIndex, 0);
                if (!node) return false;
                text = expr(*node, 0, expected);
                return true;
            }

            static bool ilFail(Str &reason, const Str &text) {
                reason = text;
                return false;
            }

            // One body, instruction by instruction. Each instruction is one statement
            // of the emitted C++ (a `Declare` pairs with the instruction that writes
            // it), which is the whole point of the form.
            bool emitIlOps(const linear::IlBody &il, const IlFrame &frame, int level, Str &reason) {
                // Where a jump can cross a declaration, C++ wants a scope: a `goto` may
                // not skip an initialization ([stmt.dcl]/3, MSVC C2362). The flat form
                // has no scopes of its own, so the backend opens the *one* block that
                // keeps the declaration legal - the same one the statement path keeps -
                // and closes it at the label the jump lands on.
                Dictionary<int, int> blockEnd; // declaration position -> the label it must end before
                for (int i = 0; i < (int) il.ops.size(); i++) {
                    const linear::IlOp &op = il.ops[i];
                    if (op.kind != IlOpKind::Declare && op.kind != IlOpKind::DeclareInit) continue;
                    // A declaration that prints nothing - the folding inlines it at its use -
                    // keeps nothing legal, so it asks for no block either.
                    if (ilFolded(il, frame, ilOperandAt(op.operands, 0))) continue;
                    const IlCrossing crossing = ilJumpCrossing(il, i);
                    if (crossing.end >= 0) blockEnd[i] = crossing.end;
                }
                struct IlScope {
                    int start = 0;
                    int end = -1;
                };
                List<IlScope> scopes;
                int consumedByDeclare = -1; // an instruction a declaration printed

                for (int i = 0; i < (int) il.ops.size(); i++) {
                    // A block ends where its jump lands: the target stays outside it.
                    while (!scopes.empty() && scopes[scopes.size() - 1].end == i) {
                        scopes.pop_back();
                        level--;
                        line(level, "}");
                    }
                    if (i == consumedByDeclare) continue;
                    const linear::IlOp &op = il.ops[i];
                    const IlOpKind kind = op.kind;
                    const int dst = ilDst(op);

                    if (blockEnd.count(i) > 0) {
                        const int end = blockEnd[i];
                        const int lastJump = ilJumpCrossing(il, i).lastJump;
                        bool covered = false;
                        for (const IlScope &scope: scopes) {
                            if (scope.start > lastJump && scope.end >= end) covered = true;
                        }
                        if (!covered) {
                            line(level, "{");
                            level++;
                            IlScope scope;
                            scope.start = i;
                            scope.end = end;
                            scopes.push_back(scope);
                        }
                    }

                    if (kind == IlOpKind::Declare || kind == IlOpKind::DeclareInit) {
                        const int slot = ilOperandAt(op.operands, 0);
                        if (slot < 0 || slot >= (int) il.vars.size()) {
                            return ilFail(reason, "a declare with no slot");
                        }
                        if (ilFolded(il, frame, slot)) continue; // inlined at its use
                        ast::TypePtr slotType = linear::ilVarType(il, slot);
                        // A slot the type pass could not spell is still declarable when
                        // the declaration initialises it: `auto`, exactly as the
                        // statement path writes it.
                        const bool initialized = kind == IlOpKind::DeclareInit;
                        if (!slotType && !initialized) {
                            return ilFail(reason, "the slot '" + il.vars[slot].name
                                                 + "' has neither a type nor an initializer");
                        }
                        Str decl = slotType ? Str(ilDeclTypeText(il, slot, *slotType) + " "
                                                                 + il.vars[slot].name)
                                            : Str("auto " + il.vars[slot].name);
                        // An initialised declaration is one line, exactly as the
                        // statement path writes it (`Str out = "";`) - and the
                        // instruction that computes the value can be a few further
                        // down, with the initializer's own temporaries in between
                        // (which are folded away, so they print nothing). A bare one is
                        // the hoisting's `T x;`, with the assignment left where it was.
                        if (kind == IlOpKind::DeclareInit) {
                            // A jump may cross it: the block opened above keeps the
                            // initialization legal, so it stays one line.
                            const int def = ilIntAt(frame.defOp, slot, -1);
                            Str valueText;
                            if (!ilValueText(il, frame, def, slotType, valueText)) {
                                // The initializer has no expression form - a closure is an
                                // aggregate, not a call - so the slot is declared bare and
                                // the instruction assigns it.
                                line(level, decl + ";");
                                continue;
                            }
                            line(level, decl + " = " + valueText + ";");
                            consumedByDeclare = def;
                            continue;
                        }
                        line(level, decl + ";");
                        continue;
                    }
                    if (kind == IlOpKind::Label) {
                        const int label = ilOperandAt(op.operands, 0);
                        if (label < 0 || label >= (int) il.labels.size()) {
                            return ilFail(reason, "a label with no name");
                        }
                        line(level, il.labels[label] + ":;");
                        continue;
                    }
                    if (kind == IlOpKind::Goto || kind == IlOpKind::IfTrue || kind == IlOpKind::IfFalse) {
                        const int label = ilOperandAt(op.operands, kind == IlOpKind::Goto ? 0 : 1);
                        if (label < 0 || label >= (int) il.labels.size()) {
                            return ilFail(reason, "a jump with no label");
                        }
                        const Str target = il.labels[label];
                        if (kind == IlOpKind::Goto) {
                            line(level, "goto " + target + ";");
                            continue;
                        }
                        ast::ExprPtr cond = ilOperandNode(il, frame,
                                                          ilOperandAt(op.operands, 0), 0);
                        if (!cond) return ilFail(reason, "a jump with no condition");
                        const Str test = expr(*cond, 0);
                        line(level, kind == IlOpKind::IfTrue ? "if (" + test + ") goto " + target + ";"
                                                      : "if (!(" + test + ")) goto " + target + ";");
                        continue;
                    }
                    if (dst >= 0 && ilFolded(il, frame, dst)) continue; // inlined at its use
                    if (dst >= 0) {
                        ast::TypePtr slotType = linear::ilVarType(il, dst);
                        if (!slotType) {
                            return ilFail(reason, "the slot '" + il.vars[dst].name
                                                 + "' has no type to assign");
                        }
                        Str valueText;
                        if (!ilValueText(il, frame, i, slotType, valueText)) {
                            return ilFail(reason, ilWhy.empty()
                                                          ? Str("'") + ilOpKindText(kind)
                                                                + Str("' cannot be expressed yet")
                                                          : "cannot express " + ilWhy);
                        }
                        line(level, il.vars[dst].name + " = " + valueText + ";");
                        continue;
                    }
                    if (kind == IlOpKind::Store) {
                        ast::ExprPtr ptr = ilSlotNode(il, frame, ilOperandAt(op.operands, 0), 0);
                        ast::ExprPtr value = ilOperandNode(il, frame,
                                                           ilOperandAt(op.operands, 1), 0);
                        if (!ptr || !value) return ilFail(reason, "a store with no pointer");
                        auto target = std::make_shared<ast::Expr>();
                        target->kind = ExprKind::Deref;
                        target->lhs = ptr;
                        line(level, expr(*target, 0) + " = " + expr(*value, 0, inferType(*target))
                                     + ";");
                        continue;
                    }
                    if (kind == IlOpKind::SetField || kind == IlOpKind::SetIndex) {
                        ast::ExprPtr target;
                        if (kind == IlOpKind::SetField) {
                            const int textIndex = ilOperandAt(op.operands, 1);
                            if (textIndex < 0 || textIndex >= (int) il.pool.size()) {
                                return ilFail(reason, "a field write with no name");
                            }
                            target = ilMemberNode(
                                    ilSlotNode(il, frame, ilOperandAt(op.operands, 0), 0),
                                    il.pool[textIndex]);
                        } else {
                            ast::ExprPtr base = ilSlotNode(il, frame,
                                                           ilOperandAt(op.operands, 0), 0);
                            ast::ExprPtr index = ilOperandNode(il, frame,
                                                               ilOperandAt(op.operands, 1), 0);
                            if (base && index) {
                                target = std::make_shared<ast::Expr>();
                                target->kind = ExprKind::Index;
                                target->lhs = base;
                                target->rhs = index;
                            }
                        }
                        ast::ExprPtr value = ilOperandNode(il, frame,
                                                           ilOperandAt(op.operands, 2), 0);
                        if (!target || !value) return ilFail(reason, "a write with no target");
                        line(level, expr(*target, 0) + " = " + expr(*value, 0, inferType(*target))
                                     + ";");
                        continue;
                    }
                    if (kind == IlOpKind::SetStatic) {
                        const int textIndex = ilOperandAt(op.operands, 0);
                        ast::ExprPtr value = ilOperandNode(il, frame,
                                                           ilOperandAt(op.operands, 1), 0);
                        if (textIndex < 0 || textIndex >= (int) il.pool.size() || !value) {
                            return ilFail(reason, "a static write with no target");
                        }
                        const Str &text = il.pool[textIndex];
                        int dot = -1;
                        for (int j = 0; j < (int) text.size(); j++) {
                            if (text[j] == '.') dot = j;
                        }
                        ast::ExprPtr target = dot <= 0
                                                  ? ilNameNode(text)
                                                  : ilMemberNode(ilNameNode(text.substr(0, dot)),
                                                                 text.substr(dot + 1));
                        line(level, expr(*target, 0) + " = " + expr(*value, 0, inferType(*target))
                                     + ";");
                        continue;
                    }
                    if (kind == IlOpKind::CallCtor && dst >= 0 && ilUnit != nullptr) {
                        // A closure: a construction of the class the lambda is, which C++
                        // spells as an aggregate of its captured fields. (Handled by
                        // `ilValueText`, so this arm only keeps the instruction from
                        // reaching the call spelling below.)
                        const int typeAt = ilOperandAt(op.operands, 1);
                        if (typeAt >= 0 && typeAt < (int) il.types.size()
                            && closureSymbols.count(il.types[typeAt]) > 0) {
                            Str valueText;
                            if (!ilValueText(il, frame, i, nullptr, valueText)) {
                                return ilFail(reason, "a closure construction");
                            }
                            line(level, il.vars[dst].name + " = " + valueText + ";");
                            continue;
                        }
                    }
                    if (kind == IlOpKind::CallVoid || kind == IlOpKind::CallIndirectVoid) {
                        ast::ExprPtr call = ilCallNode(il, frame, op);
                        if (!call) {
                            return ilFail(reason, ilWhy.empty() ? Str("a void call")
                                                               : "cannot express " + ilWhy);
                        }
                        line(level, expr(*call, 0) + ";");
                        continue;
                    }
                    if (kind == IlOpKind::Return || kind == IlOpKind::ReturnVoid) {
                        if (kind == IlOpKind::ReturnVoid) {
                            line(level, "return;");
                            continue;
                        }
                        ast::ExprPtr value = ilOperandNode(il, frame,
                                                           ilOperandAt(op.operands, 0), 0);
                        if (!value) return ilFail(reason, "a return with no value");
                        line(level, "return " + expr(*value, 0, curReturnType) + ";");
                        continue;
                    }
                    if (kind == IlOpKind::Lambda) return ilFail(reason, "a lambda body");
                    if (kind == IlOpKind::Unsupported) return ilFail(reason, "an unsupported shape");
                    return ilFail(reason, Str("the instruction '") + ilOpKindText(kind) + "'");
                }
                while (!scopes.empty()) {
                    scopes.pop_back();
                    level--;
                    line(level, "}");
                }
                return true;
            }

            bool emitIlOpsText(const linear::IlBody &il, int level, Str &text, Str &reason) {
                IlFrame frame;
                ilAnalyze(il, frame);
                const Str savedOut = out;
                const bool savedFailed = failed;
                const Str savedError = error;
                const linear::IlBody *savedBody = ilBody;
                ilBody = &il;
                out.clear();
                bool ok = emitIlOps(il, frame, level, reason);
                if (ok && failed) {
                    ok = false;
                    reason = "the emitter reported: " + error;
                }
                if (ok) text = out;
                out = savedOut;
                failed = savedFailed;
                error = savedError;
                ilBody = savedBody;
                return ok;
            }

            // The C++ of one function body, from its IL. The frame's types are installed
            // so the spelling helpers (`memberAccess`, the call resolution) see the same
            // world the type pass gave them; nothing that survived a previous body
            // is left behind.
            bool emitIlBodyText(const linear::IlUnit &unit, int level, Str &text, Str &reason) {
                const linear::IlBody &il = unit.body;
                const Dictionary<Str, NameKind> savedKinds = nameKinds;
                const Dictionary<Str, ast::TypePtr> savedTypes = localTypes;
                const linear::IlUnit *savedUnit = ilUnit;
                const Dictionary<Str, bool> savedClosures = closureSymbols;
                ilSeedFrameTypes(il);
                ilUnit = &unit;
                closureSymbols.clear();
                for (const linear::IlClosure &closure: unit.closures) {
                    closureSymbols[closure.symbol] = true;
                }
                const bool ok = emitIlOpsText(il, level, text, reason);
                nameKinds = savedKinds;
                localTypes = savedTypes;
                ilUnit = savedUnit;
                closureSymbols = savedClosures;
                return ok;
            }

            // C++ of a lambda's body, as the class's method: the frame is the lambda's
            // (its parameters and the closure instance), and `self` is C++'s `this`.
            bool emitClosureMethodText(const linear::IlUnit &unit,
                                       const linear::IlClosure &closure, int level, Str &text,
                                       Str &reason) {
                if (closure.bodyIndex < 0 || closure.bodyIndex >= (int) unit.lambdas.size()) {
                    return ilFail(reason, "a closure with no body");
                }
                const linear::IlBody &body = unit.lambdas[closure.bodyIndex];
                const Dictionary<Str, NameKind> savedKinds = nameKinds;
                const Dictionary<Str, ast::TypePtr> savedTypes = localTypes;
                const NameKind savedSelfKind = selfKind;
                const ast::TypePtr savedSelfType = selfType;
                const bool savedClosure = inClosureMethod;
                nameKinds.clear();
                localTypes.clear();
                ilSeedFrameTypes(body);
                auto classType = std::make_shared<ast::TypeExpr>();
                classType->kind = TypeKind::Named;
                classType->name = closure.symbol;
                selfKind = NameKind::Value;
                selfType = classType;
                inClosureMethod = true;
                const linear::IlUnit *savedUnit = ilUnit;
                ilUnit = &unit;
                const bool ok = emitIlOpsText(body, level, text, reason);
                ilUnit = savedUnit;
                nameKinds = savedKinds;
                localTypes = savedTypes;
                selfKind = savedSelfKind;
                selfType = savedSelfType;
                inClosureMethod = savedClosure;
                return ok;
            }

            // The class a lambda is: one field per captured variable, and one method -
            // the language's `invoke`, which C++ spells `operator()`. A `[=]` capture
            // list becomes an explicit struct, which is what lets a lambda live in the
            // instruction list (and what `&lambda` counts references to).
            bool emitClosureClass(const linear::IlUnit &unit, const linear::IlClosure &closure,
                                  Str &text, Str &reason) {
                Str out2 = "struct " + closure.symbol + " {\n";
                for (int i = 0; i < (int) closure.captures.size(); i++) {
                    const ast::TypePtr &fieldType = i < (int) closure.captureTypes.size()
                                                            ? closure.captureTypes[i]
                                                            : nullptr;
                    if (!fieldType) {
                        return ilFail(reason, "the capture '" + closure.captures[i]
                                             + "' has no type");
                    }
                    out2 += "    " + type(*fieldType) + " " + closure.captures[i] + ";\n";
                }
                List<Str> params;
                for (const linear::IlVar &param: closure.params) {
                    ast::TypePtr paramType = linear::ilTypeNode(unit.lambdas[closure.bodyIndex],
                                                               param.typeIndex);
                    if (!paramType) {
                        return ilFail(reason, "the lambda parameter '" + param.name
                                             + "' has no type");
                    }
                    params.push_back(type(*paramType) + " " + param.name);
                }
                out2 += "    auto operator()(" + join(params, ", ") + ") {\n";
                Str bodyText;
                if (!emitClosureMethodText(unit, closure, 2, bodyText, reason)) return false;
                out2 += bodyText;
                out2 += "    }\n";
                out2 += "};\n\n";
                text = out2;
                return true;
            }

            // Writes out the classes of every lambda this body constructs, the first time
            // one is needed. A definition has to precede its construction, and the
            // functions are emitted in a fixed order, so "just before the body" is both
            // legal and reproducible.
            bool emitClosureClasses(const linear::IlUnit &unit, Str &text, Str &reason) {
                Str out2;
                for (const linear::IlClosure &closure: unit.closures) {
                    if (emittedClosures.count(closure.symbol) > 0) continue;
                    Str classText;
                    if (!emitClosureClass(unit, closure, classText, reason)) return false;
                    out2 += classText;
                    emittedClosures[closure.symbol] = true;
                }
                text = out2;
                return true;
            }

            // A yielding function, emitted as the state machine it was lowered to
            // (impl_specs/yield.md):
            //
            //   struct evens_yieldable { Int n; Int i; Int branch; Opt<Int> next() {...} };
            //   ns1_evens_yieldable ns1_evens(Int n) { ...machine.n = n; ... }
            //
            // The function builds a machine on the stack and returns it by value, so a
            // local iterator is a local struct; `&evens(n)` boxes a copy for a life
            // that outlives the frame (the language's `&T`, as everywhere else).
            void emitYieldable(const Fn &fn, const ast::Decl &decl, const Str &className,
                               const Str &classType, bool prototypeOnly, NameKind selfK,
                               const ast::TypePtr &selfTypePtr, const sema::Facts &facts) {
                if (decl.returnType->inner == nullptr) {
                    fail(decl.pos, "unsupported: '..' without an element type");
                    return;
                }
                const ast::TypePtr &elementType = decl.returnType->inner;

                // A machine is emitted once: the prototype pass writes the class and the
                // factory's declaration, the definition pass only fills the factory in.
                if (emittedYieldables.count(className) == 0) {
                    emittedYieldables[className] = true;
                    // The linear body first: `yield` is a *lowering*, and it trades on the
                    // control flow being labels and gotos with the value already one
                    // operand (impl_specs/yield.md).
                    List<ast::StmtPtr> lowered = linear::lowerForEmission(decl.body);
                    sema::Body semantics;
                    semantics.decl = &decl;
                    semantics.selfType = selfTypePtr;
                    semantics.typeParams = fn.templateParams;
                    // The map is the machine's methods' frame too: the bodies are the
                    // same statements (the lowering rewrites them in place), so the
                    // names the pass proved are the names they carry.
                    Dictionary<Str, ast::TypePtr> inferred;
                    List<ast::StmtPtr> typed = sema::inferTypes(lowered, facts, semantics, &inferred);
                    // The machine's methods: the body's storage is the machine's fields
                    // (`this->`), and the names a method has of its own are the pointer
                    // `advance` writes through, the dispatcher's branch and the receiver
                    // field - a local of any of those names would alias one of them.
                    List<Str> machineReserved;
                    machineReserved.push_back("value");
                    machineReserved.push_back("branch");
                    machineReserved.push_back("_sm_self");
                    List<ast::StmtPtr> finalBody = linear::finishForEmission(typed, machineReserved);
                    const linear::Yielded machine =
                            linear::lowerYield(decl, elementType, finalBody, Str("advance"));
                    if (!machine.error.empty()) {
                        fail(decl.pos, machine.error);
                        return;
                    }
                    emitMachine(fn, decl, className, elementType, machine, facts, inferred);
                    if (failed) return;
                }

                const Str factory = classType + " " + qualify(fn.packageName, decl.name);
                const List<Str> factoryParams = parameterList(fn, decl);
                const Str tmpl = templateClause(fn.templateParams);
                if (prototypeOnly) {
                    if (!tmpl.empty()) line(0, tmpl);
                    line(0, factory + "(" + join(factoryParams, ", ") + ");");
                    return;
                }
                sourceComment(decl.pos);
                if (!tmpl.empty()) line(0, tmpl);
                line(0, factory + "(" + join(factoryParams, ", ") + ") {");
                line(1, classType + " machine{};");
                for (const ast::Param &param: decl.params) {
                    line(1, "machine." + param.name + " = " + param.name + ";");
                }
                if (decl.receiverType) {
                    // The receiver of an extension function crosses a yield like any other
                    // value, so it is a field and the factory fills it from its own `self`
                    // parameter (linear::yieldReceiverField).
                    line(1, "machine." + linear::yieldReceiverField() + " = self;");
                }
                line(1, "machine.branch = 0;");
                line(1, "return machine;");
                line(0, "}");
            }

            // The parameters of the factory: the receiver first when the function has one
            // (an extension function's receiver is an ordinary parameter in the emitted
            // C++, `T* self` for a value receiver), then the declaration's own.
            List<Str> parameterList(const Fn &fn, const ast::Decl &decl) {
                List<Str> params;
                if (fn.receiver) {
                    params.push_back(receiverParam(fn.receiver));
                    if (failed) return params;
                }
                for (const ast::Param &param: decl.params) {
                    if (!param.type) {
                        fail(param.pos, "unsupported: parameter '" + param.name + "' without a type");
                        return params;
                    }
                    params.push_back(type(*param.type) + " " + param.name);
                    if (failed) return params;
                }
                return params;
            }

            // The machine is a class the type pass never saw - it is the lowering's own
            // output - so its fields are registered as a data class here. That is what
            // tells the spelling helpers what `this.<field>` is: a receiver field is a
            // *pointer* (`T* self`), so `this._sm_self.size()` reaches through it rather
            // than taking its address, and a method's parameter that shares a field's name
            // still resolves to the parameter (the frame, not this table, decides names).
            void registerMachineType(const Str &className, const linear::Yielded &machine) {
                auto decl = std::make_shared<ast::Decl>();
                decl->kind = ast::DeclKind::DataClass;
                decl->name = className;
                decl->fields = machine.fields;
                types[className] = decl.get();
                machineTypes.push_back(decl);
            }

            // The machine itself: the fields, then one method per way of advancing it.
            void emitMachine(const Fn &fn, const ast::Decl &decl, const Str &className,
                             const ast::TypePtr &elementType, const linear::Yielded &machine,
                             const sema::Facts &facts,
                             const Dictionary<Str, ast::TypePtr> &inferred) {
                sourceComment(decl.pos);
                registerMachineType(className, machine);
                // A generic function's machine is a class template: its fields are typed
                // with the function's type parameters, so the emitted C++ has to declare
                // them where it uses them (impl_specs/yield.md).
                const Str tmpl = templateClause(fn.templateParams);
                if (!tmpl.empty()) line(0, tmpl);
                line(0, "struct " + className + " {");
                for (const ast::Field &field: machine.fields) {
                    if (!field.type) {
                        fail(decl.pos, "yield: the field '" + field.name + "' has no type");
                        return;
                    }
                    line(1, type(*field.type) + " " + field.name + "{};");
                    if (failed) return;
                }
                for (const linear::YieldMethod &method: machine.methods) {
                    List<Str> params;
                    for (const ast::Param &param: method.params) {
                        params.push_back(type(*param.type) + " " + param.name);
                        if (failed) return;
                    }
                    const Str result = method.name == "advance"
                                               ? Str("Bool")
                                               : Str("Opt<") + type(*elementType) + ">";
                    line(1, result + " " + method.name + "(" + join(params, ", ") + ") {");
                    // The method is a C++ member function, so the machine's own values
                    // are reached through `this` - the same spelling the closure
                    // classes use.
                    const bool savedClosure = inClosureMethod;
                    const NameKind savedSelfKind = selfKind;
                    const ast::TypePtr savedSelfType = selfType;
                    const ast::TypePtr savedReturn = curReturnType;
                    const Dictionary<Str, NameKind> savedKinds = nameKinds;
                    const Dictionary<Str, ast::TypePtr> savedTypes = localTypes;
                    inClosureMethod = true;
                    selfKind = NameKind::Value;
                    auto classType = std::make_shared<ast::TypeExpr>();
                    classType->kind = ast::TypeKind::Named;
                    classType->name = className;
                    selfType = classType;
                    curReturnType = elementType;
                    // The method's own parameters are what tells a pointer receiver from
                    // a value one (`*value = x` writes through it).
                    for (const ast::Param &param: method.params) {
                        if (!param.type) continue;
                        nameKinds[param.name] = kindOf(*param.type);
                        localTypes[param.name] = param.type;
                    }
                    // The body is emitted from the IL like any other (the machine *is*
                    // the lowering's output): the frame is the machine's, so its
                    // fields are read and written through `self`, exactly as a lambda
                    // body reads its captures.
                    emitBodyAt(ilMachineMethod(className, method, &facts, &inferred), method.body,
                               fn.file, 2);
                    inClosureMethod = savedClosure;
                    selfKind = savedSelfKind;
                    selfType = savedSelfType;
                    curReturnType = savedReturn;
                    nameKinds = savedKinds;
                    localTypes = savedTypes;
                    if (failed) return;
                    line(1, "}");
                }
                line(0, "};");
                line(0, "");
            }

            // ---- expressions ----------------------------------------------

            static int precedence(const ast::Expr &expr) {
                if (expr.kind == ExprKind::Binary) {
                    const Str &op = expr.text;
                    if (op == "||") return 1;
                    if (op == "&&") return 2;
                    if (op == "==" || op == "!=") return 3;
                    if (op == "<" || op == ">" || op == "<=" || op == ">=") return 4;
                    if (op == "+" || op == "-") return 5;
                    if (op == "*" || op == "/" || op == "%") return 6;
                    return 1;
                }
                switch (expr.kind) {
                    case ExprKind::Unary:
                    case ExprKind::Deref:
                    case ExprKind::Copy:
                        return 7;
                    case ExprKind::Ref:
                    case ExprKind::Call:
                    case ExprKind::Index:
                    case ExprKind::Member:
                        return 9;
                    default:
                        return 10;
                }
            }

            // `expected` is the contextual type used to lower a bare `null`: an
            // `Opt<T>` context becomes `Opt<T>()`, a `*T`/`&T` context `nullptr`.
            Str expr(const ast::Expr &e, int minPrecedence, const ast::TypePtr &expected = nullptr) {
                int p = precedence(e);
                Str s;
                if (p < minPrecedence) s += "(";
                s += exprInner(e, expected);
                if (p < minPrecedence) s += ")";
                return s;
            }

            NameKind operandKind(const ast::Expr &e) {
                if (e.kind == ExprKind::Name) {
                    if (e.text == "this") return selfKind;
                    auto it = nameKinds.find(e.text);
                    if (it != nameKinds.end()) return it->second;
                }
                return NameKind::Value;
            }

            // ---- lightweight type inference ---------------------------------
            //
            // Codegen needs just enough type information to pick the right
            // lowering for a member/index/call receiver: `.` vs `->`, whether to
            // auto-dereference a `&T`/`*T` handle, which overloaded native
            // extension applies (`append` on `List<T>` vs on `Str`), and the
            // `Res<T>.value`/`.error` field spelling. It is deliberately best
            // effort: an unknown type yields null and the caller falls back to the
            // syntactic name-kind tracking. The *proven* types come from the
            // semantic step that runs on the lowered body (sema/TypeInfer.h), which
            // annotates the declarations this pass could only guess at; the helpers
            // both use (`namedType`, `pointee`, `unifyType`, ...) live there.
            //
            // A lookup here is asked about one call/member at a time, so it may
            // return a type that still mentions a type parameter (`List<T>`): the
            // emitted C++ is a template, and the C++ compiler specializes it later.

            ast::TypePtr functionReturn(const Str &name) {
                for (const Fn &fn: functions) {
                    if (fn.decl->name == name && fn.decl->returnType) {
                        return fn.decl->returnType;
                    }
                }
                return nullptr;
            }

            // The return type of a member call (`recv.name(...)`) when it resolves
            // to a known extension function or a built-in accessor.
            ast::TypePtr memberCallReturn(const ast::Expr &callee) {
                ast::TypePtr receiverType = inferType(*callee.lhs);
                const ast::TypeExpr *recv = pointee(receiverType);
                for (const Fn &fn: functions) {
                    if (fn.decl->isNative || !fn.receiver) continue;
                    if (fn.decl->name != callee.text) continue;
                    if (recv && unifyType(*fn.receiver, *recv, fn.templateParams)
                        && fn.decl->returnType) {
                        return fn.decl->returnType;
                    }
                }
                auto extensions = nativeExtensions.find(callee.text);
                if (extensions != nativeExtensions.end()) {
                    for (const NativeExt &ext: extensions->second) {
                        if (recv && ext.receiver && unifyType(*ext.receiver, *recv, ext.typeParams)
                            && ext.returnType) {
                            return ext.returnType;
                        }
                    }
                }
                if (recv && recv->kind == TypeKind::Generic) {
                    if (callee.text == "value" && recv->name == "Opt" && !recv->typeArgs.empty()) {
                        return recv->typeArgs[0];
                    }
                }
                if (recv && (callee.text == "size" || callee.text == "count")) {
                    if (recv->name == "List" || recv->name == "Str" || recv->name == "Array"
                        || recv->name == "Dictionary" || recv->name == "SmallVector") {
                        return namedType("Int");
                    }
                }
                if (callee.text == "isOk" || callee.text == "hasValue") {
                    return namedType("Bool");
                }
                return nullptr;
            }

            ast::TypePtr inferType(const ast::Expr &e) {
                switch (e.kind) {
                    case ExprKind::IntLit:
                        return namedType("Int");
                    case ExprKind::FloatLit:
                        return namedType("Float64");
                    case ExprKind::StrLit:
                        return namedType("Str");
                    case ExprKind::CharLit:
                        return namedType("Char");
                    case ExprKind::BoolLit:
                        return namedType("Bool");
                    case ExprKind::NullLit:
                        return nullptr;
                    case ExprKind::Name: {
                        if (e.text == "this") return selfType;
                        auto it = localTypes.find(e.text);
                        if (it != localTypes.end()) return it->second;
                        // File-level static storage (specs/statics.md).
                        ast::TypePtr staticNode = staticType(e.text);
                        if (staticNode) return staticNode;
                        // A bare enum type name used as the receiver of a static
                        // conversion, e.g. `Color.Red` / `Color.fromInt(x)`.
                        if (enumNames.count(e.text) > 0) return namedType(e.text);
                        return nullptr;
                    }
                    case ExprKind::GenericName:
                        return genericType(e.text, e.typeArgs);
                    case ExprKind::Member: {
                        if (e.lhs && e.lhs->kind == ExprKind::Name
                            && enumNames.count(e.lhs->text) > 0) {
                            // An enum member expression has the enum's type.
                            return namedType(e.lhs->text);
                        }
                        ast::TypePtr baseType = inferType(*e.lhs);
                        const ast::TypeExpr *base = pointee(baseType);
                        if (!base) return nullptr;
                        if (base->kind == TypeKind::Generic && base->name == "Res") {
                            if (e.text == "value" && !base->typeArgs.empty()) return base->typeArgs[0];
                            if (e.text == "error") return namedType("Str");
                        }
                        if (base->kind == TypeKind::Named || base->kind == TypeKind::Generic) {
                            auto it = types.find(base->name);
                            if (it != types.end() && it->second->kind == DeclKind::DataClass) {
                                for (const ast::Field &field: it->second->fields) {
                                    if (field.name == e.text) return field.type;
                                }
                            }
                        }
                        return nullptr;
                    }
                    case ExprKind::Call: {
                        const ast::Expr &callee = *e.lhs;
                        if (callee.kind == ExprKind::GenericName) {
                            if (types.count(callee.text) > 0 || isRtlTypeName(callee.text)) {
                                return genericType(callee.text, callee.typeArgs);
                            }
                            return functionReturn(callee.text);
                        }
                        if (callee.kind == ExprKind::Name) {
                            if (types.count(callee.text) > 0 || isRtlTypeName(callee.text)) {
                                return namedType(callee.text);
                            }
                            return functionReturn(callee.text);
                        }
                        if (callee.kind == ExprKind::Member) {
                            return memberCallReturn(callee);
                        }
                        return nullptr;
                    }
                    case ExprKind::Index: {
                        ast::TypePtr baseType = inferType(*e.lhs);
                        const ast::TypeExpr *base = pointee(baseType);
                        if (!base) return nullptr;
                        if (base->kind == TypeKind::Named && base->name == "Str") return namedType("Char");
                        if (base->kind != TypeKind::Generic || base->typeArgs.empty()) return nullptr;
                        if (base->name == "SmallVector" && base->typeArgs.size() == 2) {
                            return base->typeArgs[1]; // <N, T>
                        }
                        if (base->name == "Dictionary" && base->typeArgs.size() == 2) {
                            return base->typeArgs[1];
                        }
                        return base->typeArgs[0];
                    }
                    case ExprKind::Ref: {
                        auto type = std::make_shared<ast::TypeExpr>();
                        type->kind = TypeKind::Reference;
                        type->inner = inferType(*e.lhs);
                        return type;
                    }
                    case ExprKind::Deref: {
                        auto type = std::make_shared<ast::TypeExpr>();
                        type->kind = TypeKind::Pointer;
                        type->inner = inferType(*e.lhs);
                        return type;
                    }
                    case ExprKind::Copy:
                    case ExprKind::Unary:
                        return inferType(*e.lhs);
                    case ExprKind::Binary: {
                        if (e.text == "==" || e.text == "!=" || e.text == "<" || e.text == ">"
                            || e.text == "<=" || e.text == ">=" || e.text == "&&" || e.text == "||") {
                            return namedType("Bool");
                        }
                        return inferType(*e.lhs);
                    }
                    case ExprKind::Lambda: {
                        // Best-effort callable type; return type is left unknown
                        // here (the lambda lowering fills it in).
                        auto fnType = std::make_shared<ast::TypeExpr>();
                        fnType->kind = TypeKind::Function;
                        for (const ast::TypePtr &paramType: e.paramTypes) {
                            fnType->paramTypes.push_back(paramType);
                        }
                        return fnType;
                    }
                }
                return nullptr;
            }

            // The receiver argument for a lowered *Simse* call: a value receiver is a
            // raw pointer in the emitted code, so the argument is the receiver
            // object's address. `simse_addressOf` covers both a place (`&x`) and a
            // temporary, whose pointer is valid for the call it is passed to
            // (cppsrc/rtl/types.hpp); a counted reference is unwrapped with `.get()`,
            // and a raw pointer is already that address. A handle receiver keeps its
            // form: a counted reference stays a counted reference, so a method that
            // takes `this: &T` can store `self` and keep its refcount.
            Str receiverArg(const ast::TypePtr &pattern, const ast::Expr &recv) {
                if (isHandleType(pattern.get())) {
                    return expr(recv, 9);
                }
                ast::TypePtr recvType = inferType(recv);
                if (recvType) {
                    if (recvType->kind == TypeKind::Reference
                        || (recvType->kind == TypeKind::Generic && recvType->name == "PList")) {
                        return "(" + expr(recv, 9) + ").get()";
                    }
                    if (recvType->kind == TypeKind::Pointer) {
                        return expr(recv, 9);
                    }
                }
                return "simse_addressOf(" + expr(recv, 9) + ")";
            }

            // The receiver argument for a lowered *native* call: the host's own
            // signature decides whether it wants a value, a reference or a pointer, so
            // the receiver expression is passed as it is - dereferenced through a
            // handle, because the RTL's value receivers are written `T&` there.
            Str nativeReceiverArg(const ast::TypePtr &pattern, const ast::Expr &recv) {
                if (isHandleType(pattern.get())) {
                    return expr(recv, 9);
                }
                ast::TypePtr recvType = inferType(recv);
                if (isHandleType(recvType.get())) {
                    return "(*" + expr(recv, 9) + ")";
                }
                return expr(recv, 9);
            }

            // Finds a Simse-declared extension/method whose receiver matches the
            // given receiver expression's type. Returns null when unknown.
            const Fn *findExtensionFn(const Str &name, const ast::Expr &recvExpr) {
                ast::TypePtr recvType = inferType(recvExpr);
                const ast::TypeExpr *recv = pointee(recvType);
                if (!recv) return nullptr;
                for (const Fn &fn: functions) {
                    if (fn.decl->isNative || !fn.receiver) continue;
                    if (fn.decl->name != name) continue;
                    if (unifyType(*fn.receiver, *recv, fn.templateParams)) return &fn;
                }
                return nullptr;
            }

            const NativeExt *findNativeExt(const Str &name, const ast::Expr &recvExpr) {
                auto it = nativeExtensions.find(name);
                if (it == nativeExtensions.end()) return nullptr;
                ast::TypePtr recvType = inferType(recvExpr);
                const ast::TypeExpr *recv = pointee(recvType);
                if (!recv) return nullptr;
                for (const NativeExt &ext: it->second) {
                    if (ext.receiver && unifyType(*ext.receiver, *recv, ext.typeParams)) return &ext;
                }
                return nullptr;
            }

            // The first Simse-declared receiver function with this name: used when the
            // receiver's own type could not be inferred but the callee is known.
            const Fn *findReceiverFnByName(const Str &name) {
                for (const Fn &fn: functions) {
                    if (fn.decl->isNative || !fn.receiver) continue;
                    if (fn.decl->name == name) return &fn;
                }
                return nullptr;
            }

            Str memberAccess(const ast::Expr &base, const Str &name) {
                bool arrow = false;
                ast::TypePtr baseType = inferType(base);
                if (base.kind == ExprKind::Name && base.text == "this"
                    && selfKind == NameKind::Value) {
                    // A value receiver is a raw pointer in the emitted code (`T* self`),
                    // so its members are reached with `->` whatever the language type
                    // of the receiver is.
                    arrow = true;
                } else if (baseType) {
                    arrow = isHandleType(baseType.get());
                } else if (base.kind == ExprKind::Name) {
                    // Fallback for a receiver whose type we could not infer.
                    if (base.text == "this") {
                        arrow = selfKind != NameKind::Value;
                    } else {
                        auto it = nameKinds.find(base.text);
                        if (it != nameKinds.end() && it->second == NameKind::Shared) {
                            arrow = true;
                        }
                    }
                }
                // Res<T> exposes its payload as the `value`/`error` properties; the
                // RTL field spellings are Value/Error.
                Str field = name;
                const ast::TypeExpr *recv = pointee(baseType);
                if (recv && recv->kind == TypeKind::Generic && recv->name == "Res") {
                    if (name == "value") field = "Value";
                    else if (name == "error") field = "Error";
                }
                // A value receiver's own member access reads through its pointer
                // (`self->field`). The bare name `this` reads as the object
                // (`(*self)`), which is what a *value* use of the receiver needs, so
                // this one spot spells the pointer instead.
                if (base.kind == ExprKind::Name && base.text == "this"
                    && selfKind == NameKind::Value) {
                    return (inClosureMethod ? Str("this->") : Str("self->")) + field;
                }
                return expr(base, 9) + (arrow ? "->" : ".") + field;
            }

            // Lowers a bare `null` given the surrounding expected type.
            Str nullTo(const ast::TypePtr &expected) {
                const ast::TypeExpr *target = expected.get();
                if (target && target->kind == TypeKind::Generic && target->name == "Opt") {
                    return "Opt<" + typeArgsString("Opt", target->typeArgs) + ">()";
                }
                // Both `*T` and `&T` (std::shared_ptr) accept nullptr.
                return "nullptr";
            }

            // Expands a non-generic typealias to its target, so a callable alias
            // (`Mapper`) can be inspected structurally.
            ast::TypePtr resolveAlias(ast::TypePtr type) {
                int guard = 0;
                while (type && type->kind == TypeKind::Named && ++guard < 100) {
                    auto it = types.find(type->name);
                    if (it == types.end()) break;
                    const ast::Decl *decl = it->second;
                    if (decl->kind != DeclKind::TypeAlias || !decl->targetType) break;
                    if (!decl->typeParams.empty()) break; // generic alias: not expanded
                    type = decl->targetType;
                }
                return type;
            }

            // The callable (Function) type behind an expected type, expanding
            // aliases; null when the expected type is not a callable.
            const ast::TypeExpr *expectedCallable(const ast::TypePtr &expected) {
                ast::TypePtr resolved = resolveAlias(expected);
                if (resolved && resolved->kind == TypeKind::Function) return resolved.get();
                return nullptr;
            }

            // A non-native function with the given name and parameter count. A method
            // is not one: a plain call reaches only what the module declares.
            const ast::Decl *findFunction(const Str &name, int argCount) {
                for (const Fn &fn: functions) {
                    if (fn.decl->isNative || fn.isMethod || fn.decl->name != name) continue;
                    if ((int) fn.decl->params.size() == argCount) return fn.decl;
                }
                return nullptr;
            }

            bool isUnitType(const ast::TypeExpr *type) {
                return !type || (type->kind == TypeKind::Named && type->name == "Unit");
            }

            Str exprInner(const ast::Expr &e, const ast::TypePtr &expected) {
                switch (e.kind) {
                    case ExprKind::IntLit:
                    case ExprKind::FloatLit:
                    case ExprKind::CharLit:
                        return e.text;
                    case ExprKind::StrLit: {
                        // A table entry is a `const Str` *glvalue*: a comparison or a
                        // `const Str&` parameter binds it without building anything, while
                        // an owned position copies it exactly as it copied the literal.
                        auto found = literalAt.find(e.text);
                        if (found != literalAt.end()) {
                            return "__sm_stringTable[" + std::to_string(found->second) + "]";
                        }
                        return e.text;
                    }
                    case ExprKind::BoolLit:
                        return e.boolValue ? "true" : "false";
                    case ExprKind::NullLit:
                        return nullTo(expected);
                    case ExprKind::Name:
                        if (e.text == "this") {
                            // The receiver is the *object* in the language and a raw
                            // pointer in the emitted code when it is a value receiver
                            // (`T* self`), so reading it reads through the pointer; a
                            // handle receiver is its handle, as before. Inside a closure
                            // class the receiver is C++'s `this`.
                            if (inClosureMethod) return selfKind == NameKind::Value
                                                                ? Str("(*this)")
                                                                : Str("this");
                            return selfKind == NameKind::Value ? Str("(*self)") : Str("self");
                        }
                        if (localTypes.count(e.text) > 0) return e.text;
                        // A file-level static is emitted under its package's prefix; a bare
                        // name that is not a local is otherwise a reference to a top-level
                        // function used as a value (e.g. a callable argument), so it carries
                        // that function's prefix, and a known prelude native resolves to its
                        // symbol instead.
                        if (staticsByName.count(e.text) > 0) {
                            return qualify(staticsByName[e.text].packageName, e.text);
                        }
                        if (!functionPackage(e.text).empty()) {
                            return qualify(functionPackage(e.text), e.text);
                        }
                        if (nativeSymbols.count(e.text) > 0) return nativeSymbols[e.text];
                        return e.text;
                    case ExprKind::GenericName:
                        fail(e.pos, "unsupported: generic-qualified expression '" + e.text + "<...>'");
                        return "/*unsupported*/";
                    case ExprKind::Member:
                        if (e.lhs && e.lhs->kind == ExprKind::Name && enumNames.count(e.lhs->text) > 0) {
                            return qualify(typePackage(e.lhs->text), e.lhs->text) + "::" + e.text;
                        }
                        return memberAccess(*e.lhs, e.text);
                    case ExprKind::Call:
                        return call(e);
                    case ExprKind::Index: {
                        Str baseExpr = expr(*e.lhs, 9);
                        ast::TypePtr baseType = inferType(*e.lhs);
                        // A counted reference always auto-dereferences when indexed.
                        // A raw pointer does too when it points at a container
                        // (`*List<T>`, `*Str`, ...); indexing a raw array of
                        // scalars stays plain pointer arithmetic.
                        bool deref = false;
                        if (baseType) {
                            if (isHandleType(baseType.get())
                                && baseType->kind != TypeKind::Pointer) {
                                deref = true;
                            } else if (baseType->kind == TypeKind::Pointer) {
                                deref = isIndexableContainer(baseType->inner.get());
                            }
                        }
                        if (deref) {
                            return "(*" + baseExpr + ")[" + expr(*e.rhs, 0) + "]";
                        }
                        return baseExpr + "[" + expr(*e.rhs, 0) + "]";
                    }
                    case ExprKind::Unary:
                        return e.text + expr(*e.lhs, 7);
                    case ExprKind::Binary: {
                        // `x == null` / `x != null`: an Opt has no operator==, so
                        // use hasValue(); pointers and shared_ptr compare against
                        // nullptr directly.
                        if (e.lhs && e.rhs
                            && (e.lhs->kind == ExprKind::NullLit || e.rhs->kind == ExprKind::NullLit)) {
                            const ast::Expr &other = e.lhs->kind == ExprKind::NullLit ? *e.rhs : *e.lhs;
                            ast::TypePtr otherTypePtr = inferType(other);
                            const ast::TypeExpr *otherType = pointee(otherTypePtr);
                            if (otherType && otherType->kind == TypeKind::Generic
                                && otherType->name == "Opt") {
                                Str hasValue = expr(other, 9) + ".hasValue()";
                                if (e.text == "==") return "!(" + hasValue + ")";
                                if (e.text == "!=") return "(" + hasValue + ")";
                                fail(e.pos, "unsupported: Opt-vs-null comparison '" + e.text + "'");
                                return "/*unsupported*/";
                            }
                        }
                        int p = precedence(e);
                        ast::TypePtr lhsExpected =
                            (e.lhs->kind == ExprKind::NullLit) ? inferType(*e.rhs) : nullptr;
                        ast::TypePtr rhsExpected =
                            (e.rhs->kind == ExprKind::NullLit) ? inferType(*e.lhs) : nullptr;
                        return expr(*e.lhs, p, lhsExpected) + " " + e.text + " "
                               + expr(*e.rhs, p + 1, rhsExpected);
                    }
                    case ExprKind::Lambda:
                        // A lambda is a closure *class* here, built by the instruction
                        // list; an expression node reaching this point means the
                        // lowering did not turn it into one (impl_specs/linear-il.md).
                        fail(e.pos, "unsupported: a lambda outside a closure construction");
                        return "/*unsupported*/";
                    case ExprKind::Ref: {
                        // `&List<T>()` is the counted empty-list construction.
                        if (e.lhs && e.lhs->kind == ExprKind::Call && e.lhs->lhs
                            && e.lhs->lhs->kind == ExprKind::GenericName
                            && e.lhs->lhs->text == "List" && e.lhs->args.empty()) {
                            return "makeList<" + typeArgsString("List", e.lhs->lhs->typeArgs) + ">()";
                        }
                        Str operand = expr(*e.lhs, 0);
                        return "std::make_shared<std::remove_cvref_t<decltype((" + operand + "))>>("
                               + operand + ")";
                    }
                    case ExprKind::Deref: {
                        Str operand = expr(*e.lhs, 7);
                        ast::TypePtr operandType = inferType(*e.lhs);
                        NameKind kind = operandType ? kindOf(*operandType) : operandKind(*e.lhs);
                        if (kind == NameKind::Shared) {
                            return "(" + operand + ").get()";
                        }
                        if (kind == NameKind::Value) {
                            // `*value` is the raw-pointer form: the address of the
                            // value, no copy (specs/memory-model.md). A plain name
                            // is an lvalue, so `&name`; anything else may be a
                            // temporary, which simse_addressOf binds for the call.
                            if (e.lhs && e.lhs->kind == ExprKind::Name) return "&" + operand;
                            return "simse_addressOf(" + operand + ")";
                        }
                        return "*" + operand;
                    }
                    case ExprKind::Copy: {
                        Str operand = expr(*e.lhs, 0);
                        ast::TypePtr operandType = inferType(*e.lhs);
                        NameKind kind = operandType ? kindOf(*operandType) : operandKind(*e.lhs);
                        if (kind == NameKind::Shared || kind == NameKind::Pointer) {
                            return "*(" + operand + ")";
                        }
                        return "(" + operand + ")";
                    }
                }
                return "/*unsupported*/";
            }

            Str call(const ast::Expr &e) {
                const ast::Expr &callee = *e.lhs;
                if (callee.kind == ExprKind::GenericName) {
                    // A generic type construction (`List<Int>()`) or a generic
                    // function call (`identity<Int>(x)`); both lower directly. A
                    // generic *native* function (e.g. `dictionaryOf<K, V>()`) lowers
                    // to its explicit symbol instead.
                    List<Str> args;
                    for (const ast::ExprPtr &arg: e.args) {
                        args.push_back(expr(*arg, 0));
                    }
                    Str calleeName = qualify(functionPackage(callee.text), callee.text);
                    auto native = nativeSymbols.find(callee.text);
                    bool hasPlainFunction = false;
                    for (const Fn &candidate: functions) {
                        if (!candidate.decl->isNative && candidate.decl->name == callee.text) {
                            hasPlainFunction = true;
                            break;
                        }
                    }
                    if (dataClassNames.count(callee.text) > 0) {
                        calleeName = qualify(typePackage(callee.text), "_make_" + callee.text);
                    } else if (!hasPlainFunction && native != nativeSymbols.end()) {
                        calleeName = native->second;
                    }
                    return calleeName + "<" + typeArgsString(callee.text, callee.typeArgs)
                           + ">(" + join(args, ", ") + ")";
                }
                if (callee.kind == ExprKind::Name) {
                    if (callee.text == "println" || callee.text == "print") {
                        Str arg = e.args.empty() ? Str("") : expr(*e.args[0], 0);
                        Str s = "std::cout << std::boolalpha << (" + arg + ")";
                        if (callee.text == "println") s += " << std::endl";
                        return s;
                    }
                    const ast::Decl *target = findFunction(callee.text, (int) e.args.size());
                    List<Str> args;
                    for (int i = 0; i < (int) e.args.size(); i++) {
                        // A receiver function called by name takes the receiver first,
                        // and a value receiver is a raw pointer in the emitted code
                        // (`T* self`), so that argument is the object's address.
                        if (i == 0 && target && target->hasReceiver && target->receiverType
                            && !isHandleType(target->receiverType.get())) {
                            args.push_back(receiverArg(target->receiverType, *e.args[0]));
                            continue;
                        }
                        ast::TypePtr expectedArg =
                            (target && i < (int) target->params.size()) ? target->params[i].type
                                                                        : nullptr;
                        args.push_back(expr(*e.args[i], 0, expectedArg));
                    }
                    auto native = nativeSymbols.find(callee.text);
                    // A user-declared plain function of the same name wins over a
                    // prelude native (e.g. the scanner's own `isDigit(Char)` must
                    // not be redirected to the `Char.isDigit()` extension).
                    bool hasPlainFunction = false;
                    for (const Fn &candidate: functions) {
                        if (!candidate.decl->isNative && candidate.decl->name == callee.text) {
                            hasPlainFunction = true;
                            break;
                        }
                    }
                    Str calleeName = qualify(functionPackage(callee.text), callee.text);
                    if (dataClassNames.count(callee.text) > 0) {
                        calleeName = qualify(typePackage(callee.text), "_make_" + callee.text);
                    } else if (!hasPlainFunction && native != nativeSymbols.end()) {
                        calleeName = native->second;
                    }
                    return calleeName + "(" + join(args, ", ") + ")";
                }
                if (callee.kind == ExprKind::Member) {
                    List<Str> args;
                    for (const ast::ExprPtr &arg: e.args) {
                        args.push_back(expr(*arg, 0));
                    }
                    // Enum conversions: `x.toInt()` and `Enum.fromInt(v)`.
                    if (callee.text == "toInt") {
                        ast::TypePtr enumReceiverType = inferType(*callee.lhs);
                        const ast::TypeExpr *enumReceiver = pointee(enumReceiverType);
                        if (enumReceiver && enumReceiver->kind == TypeKind::Named
                            && enumNames.count(enumReceiver->name) > 0) {
                            return "static_cast<Int>(" + expr(*callee.lhs, 9) + ")";
                        }
                    }
                    if (callee.text == "fromInt" && callee.lhs
                        && callee.lhs->kind == ExprKind::Name
                        && enumNames.count(callee.lhs->text) > 0) {
                        return qualify(typePackage(callee.lhs->text),
                                       "simse_" + callee.lhs->text + "_fromInt")
                               + "(" + join(args, ", ") + ")";
                    }
                    // Generic-qualified static call: `Res<T>.ok(x)` lowers to
                    // `Res<T>::ok(x)` (RTL Opt/Res provide static constructors).
                    if (callee.lhs && callee.lhs->kind == ExprKind::GenericName) {
                        return qualify(typePackage(callee.lhs->text), callee.lhs->text) + "<"
                               + typeArgsString(callee.lhs->text, callee.lhs->typeArgs)
                               + ">::" + callee.text + "(" + join(args, ", ") + ")";
                    }

                    // Machine identity: `x.smToYield()` on a machine *is* `x`. That is the
                    // wrap a `for` puts around what it iterates, and `..T` is not a
                    // spellable type, so the identity is the backend's rather than a
                    // function's (impl_specs/for.md).
                    if (callee.text == "smToYield" && callee.lhs) {
                        ast::TypePtr identityType = inferType(*callee.lhs);
                        const ast::TypeExpr *identityRecv = pointee(identityType);
                        if (identityRecv && identityRecv->kind == TypeKind::Yield) {
                            return expr(*callee.lhs, 0);
                        }
                    }
                    ast::TypePtr receiverType = inferType(*callee.lhs);
                    const ast::TypeExpr *receiver = pointee(receiverType);
                    if (receiver) {
                        // Receiver type is known: pick the extension (or native
                        // extension) overload whose receiver unifies with it.
                        const Fn *fn = findExtensionFn(callee.text, *callee.lhs);
                        if (fn) {
                            Str all = receiverArg(fn->receiver, *callee.lhs);
                            for (const Str &arg: args) all += ", " + arg;
                            return qualify(fn->packageName, fn->decl->name) + "(" + all + ")";
                        }
                        const NativeExt *ext = findNativeExt(callee.text, *callee.lhs);
                        if (ext) {
                            Str all = nativeReceiverArg(ext->receiver, *callee.lhs);
                            for (const Str &arg: args) all += ", " + arg;
                            return ext->symbol + "(" + all + ")";
                        }
                        // Known receiver but no Simse/native extension: an RTL
                        // member call such as List::size or Res::isOk.
                        return memberAccess(*callee.lhs, callee.text)
                               + "(" + join(args, ", ") + ")";
                    }

                    // Unknown receiver type: keep the name-based precedence.
                    if (receiverFnNames.count(callee.text) > 0) {
                        const Fn *byName = findReceiverFnByName(callee.text);
                        Str all = byName ? receiverArg(byName->receiver, *callee.lhs)
                                         : expr(*callee.lhs, 9);
                        for (const Str &arg: args) all += ", " + arg;
                        return qualify(functionPackage(callee.text), callee.text) + "(" + all + ")";
                    }
                    auto extension = nativeExtensions.find(callee.text);
                    if (extension != nativeExtensions.end() && !extension->second.empty()) {
                        Str all = expr(*callee.lhs, 9);
                        for (const Str &arg: args) all += ", " + arg;
                        return extension->second[0].symbol + "(" + all + ")";
                    }
                    return memberAccess(*callee.lhs, callee.text)
                           + "(" + join(args, ", ") + ")";
                }
                fail(e.pos, "unsupported: call target");
                return "/*unsupported*/";
            }

            // ---- prelude --------------------------------------------------

            void prelude() {
                out += "// Generated by simse_transpile. Do not edit.\n";
                out += "#include \"cppsrc/rtl/simse.hpp\"\n";
                out += "#include <iostream>\n";
                out += "#include <type_traits>\n";
                out += "\n";
            }
        };
    }

    Res<Str> emitProgram(const List<Input> &inputs) {
        Emitter emitter(inputs);
        return emitter.run();
    }
}
