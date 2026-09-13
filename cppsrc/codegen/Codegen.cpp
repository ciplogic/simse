#include "Codegen.h"

#include "../linear/Linear.h"
#include "../linear/Simplify.h"

#include <algorithm>
#include <string>

using ast::DeclKind;
using ast::ExprKind;
using ast::StmtKind;
using ast::TypeKind;

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
            bool prelude = false;    // resolved but never emitted
            Str packageName;         // picks the emitted-symbol prefix ("" for rtl)
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

        bool isRtlTypeName(const Str &name) {
            static const List<Str> names = {
                "Int", "Int8", "Int16", "Int32", "Int64",
                "Float32", "Float64", "Char", "Bool", "Str",
                "List", "Array", "RawArray", "Opt", "Res",
                "Dictionary", "SmallVector", "PList",
                "Attribute", "XmlNode", "Cursor", "FileStream", "StrView",
            };
            for (const Str &candidate: names) {
                if (candidate == name) return true;
            }
            return false;
        }

        class Emitter {
        public:
            explicit Emitter(const List<Input> &inputs) : inputs(inputs) {
            }

            Res<Str> run() {
                collect();
                prelude();
                emitNativeDeclarations();
                if (failed) return resError<Str>(error);
                emitTypes();
                if (failed) return resError<Str>(error);
                emitStatics();
                if (failed) return resError<Str>(error);
                emitFunctions(true);
                if (failed) return resError<Str>(error);
                emitStaticInit();
                if (failed) return resError<Str>(error);
                emitFunctions(false);
                if (failed) return resError<Str>(error);
                return ok(out);
            }

        private:
            const List<Input> &inputs;

            Str out;
            bool failed = false;
            Str error;
            Str curFile;

            Dictionary<Str, const ast::Decl *> types;
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
                line(0, "// " + curFile + ":" + std::to_string(pos.line));
            }

            // ---- symbol collection ----------------------------------------

            ast::TypePtr namedTypeExpr(const Str &name) {
                auto type = std::make_shared<ast::TypeExpr>();
                type->kind = TypeKind::Named;
                type->name = name;
                return type;
            }

            // The receiver type of a class method: `Name<A, B>` for a generic
            // class, `Name` otherwise.
            ast::TypePtr classReceiver(const ast::Decl &decl) {
                if (decl.typeParams.empty()) {
                    return namedTypeExpr(decl.name);
                }
                auto type = std::make_shared<ast::TypeExpr>();
                type->kind = TypeKind::Generic;
                type->name = decl.name;
                for (const Str &param: decl.typeParams) {
                    type->typeArgs.push_back(namedTypeExpr(param));
                }
                return type;
            }

            void addFunction(const ast::Decl *decl, ast::TypePtr receiver, const Str &file,
                             const List<Str> &templateParams, bool prelude, const Str &package) {
                Fn fn;
                fn.decl = decl;
                fn.receiver = receiver;
                fn.file = file;
                fn.templateParams = templateParams;
                fn.prelude = prelude;
                fn.packageName = package;
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
            // by name only, like the `hasPlainFunction` probes at the call sites.
            Str functionPackage(const Str &name) {
                for (const Fn &fn: functions) {
                    if (fn.decl->isNative || fn.decl->name != name) continue;
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
                            // Prelude data classes (XmlNode, Attribute, Cursor) map
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
                                            input.prelude, pkg);
                            }
                        }
                    }
                }
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
                // A declared type shadows an RTL type *name*: the compiler's own
                // `common.StrView` is a different type from the RTL's `StrView`. The
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

            void emitFunctions(bool prototypeOnly) {
                for (const Fn &fn: functions) {
                    if (fn.prelude) continue;
                    curFile = fn.file;
                    emitFunction(fn, prototypeOnly);
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

            // The C++ parameter form of a receiver. Value receivers are taken by
            // reference so mutations through `this` reach the caller (data-class
            // methods like `setSource`/`advance` rely on this). Counted
            // references and raw pointers keep their handle form.
            Str receiverParam(const ast::TypePtr &receiverType) {
                Str mapped = type(*receiverType);
                if (receiverType->kind == TypeKind::Reference
                    || receiverType->kind == TypeKind::Pointer) {
                    return mapped + " self";
                }
                return mapped + "& self";
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

            void emitFunction(const Fn &fn, bool prototypeOnly) {
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
                Str ret = isMain ? "int" : (decl.returnType ? type(*decl.returnType) : Str("void"));
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
                // Structured control flow is lowered to labels/gotos and then
                // simplified before emission (impl_specs/linear-lowering.md);
                // the emitter below only knows the linear forms.
                emitStmts(linear::simplifyBody(linear::lowerBody(decl.body)), 1);
                if (failed) return;
                line(0, "}");
            }

            // ---- statements -----------------------------------------------

            void emitStmts(const List<ast::StmtPtr> &stmts, int level) {
                for (const ast::StmtPtr &stmt: stmts) {
                    emitStmt(*stmt, level);
                    if (failed) return;
                }
            }

            void emitStmt(const ast::Stmt &stmt, int level) {
                switch (stmt.kind) {
                    case StmtKind::VarDecl: {
                        Str typeText;
                        if (stmt.type) {
                            typeText = type(*stmt.type);
                        } else if (stmt.init) {
                            typeText = "auto";
                        } else {
                            fail(stmt.pos, "unsupported: '" + stmt.name
                                           + "' has neither a type nor an initializer");
                            return;
                        }
                        if (failed) return;
                        Str text = typeText + " " + stmt.name;
                        if (stmt.init) text += " = " + expr(*stmt.init, 0, stmt.type);
                        line(level, text + ";");

                        if (stmt.type) {
                            nameKinds[stmt.name] = kindOf(*stmt.type);
                            localTypes[stmt.name] = stmt.type;
                        } else if (stmt.init) {
                            nameKinds[stmt.name] = stmt.init->kind == ExprKind::Ref
                                                       ? NameKind::Shared
                                                       : (stmt.init->kind == ExprKind::Deref
                                                              ? NameKind::Pointer
                                                              : NameKind::Value);
                            ast::TypePtr inferred = inferType(*stmt.init);
                            if (inferred) localTypes[stmt.name] = inferred;
                        }
                        return;
                    }
                    case StmtKind::Assign:
                        if (stmt.op != "=") {
                            fail(stmt.pos, "unsupported: assignment operator '" + stmt.op + "'");
                            return;
                        }
                        line(level, expr(*stmt.target, 0) + " = "
                                     + expr(*stmt.value, 0, inferType(*stmt.target)) + ";");
                        return;
                    case StmtKind::Return:
                        if (stmt.returnValue) {
                            line(level, "return " + expr(*stmt.returnValue, 0, curReturnType) + ";");
                        } else {
                            line(level, "return;");
                        }
                        return;
                    case StmtKind::Label:
                        line(level, stmt.name + ":;");
                        return;
                    case StmtKind::Goto:
                        line(level, "goto " + stmt.name + ";");
                        return;
                    case StmtKind::IfTrue:
                        line(level, "if (" + expr(*stmt.cond, 0) + ") goto " + stmt.name + ";");
                        return;
                    case StmtKind::IfFalse:
                        line(level, "if (!(" + expr(*stmt.cond, 0) + ")) goto " + stmt.name + ";");
                        return;
                    case StmtKind::Block:
                        line(level, "{");
                        emitStmts(stmt.body, level + 1);
                        if (failed) return;
                        line(level, "}");
                        return;
                    case StmtKind::ExprStmt:
                        line(level, expr(*stmt.expr, 0) + ";");
                        return;
                    case StmtKind::If:
                    case StmtKind::While:
                    case StmtKind::Switch:
                    case StmtKind::Break:
                    case StmtKind::Continue:
                        fail(stmt.pos, "internal: structured statement reached the emitter "
                                       "(linear lowering did not run)");
                        return;
                }
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
            // syntactic name-kind tracking.

            ast::TypePtr namedType(const Str &name) {
                auto type = std::make_shared<ast::TypeExpr>();
                type->kind = TypeKind::Named;
                type->name = name;
                return type;
            }

            ast::TypePtr genericType(const Str &name, const List<ast::TypePtr> &args) {
                auto type = std::make_shared<ast::TypeExpr>();
                type->kind = TypeKind::Generic;
                type->name = name;
                type->typeArgs = args;
                return type;
            }

            // The pointee after stripping any number of `&`/`*` handles.
            const ast::TypeExpr *pointee(const ast::TypePtr &type) {
                const ast::TypeExpr *current = type.get();
                while (current && (current->kind == TypeKind::Reference
                                   || current->kind == TypeKind::Pointer)
                       && current->inner) {
                    current = current->inner.get();
                }
                return current;
            }

            // Whether a type is reached through a handle (`&T`, `*T`, or the
            // `PList<T>` alias of `&List<T>`), so member access uses `->`, indexing
            // dereferences, and call receivers deref for value extension patterns.
            bool isHandleType(const ast::TypeExpr *type) {
                if (!type) return false;
                if (type->kind == TypeKind::Reference || type->kind == TypeKind::Pointer) {
                    return true;
                }
                return type->kind == TypeKind::Generic && type->name == "PList";
            }

            // Whether a pointee is a container with `operator[]` element access.
            // Indexing through a raw pointer auto-dereferences only for these;
            // pointer arithmetic (`p[i]`) is kept for raw arrays of scalars.
            bool isIndexableContainer(const ast::TypeExpr *type) {
                if (!type) return false;
                if (type->kind == TypeKind::Named) return type->name == "Str";
                if (type->kind == TypeKind::Generic) {
                    const Str &name = type->name;
                    return name == "List" || name == "Array" || name == "Dictionary"
                           || name == "SmallVector";
                }
                return false;
            }

            bool isTypeParamName(const Str &name, const List<Str> &typeParams) {
                for (const Str &param: typeParams) {
                    if (param == name) return true;
                }
                return false;
            }

            // Structural unification of an extension receiver pattern (which may
            // mention the extension's type parameters) against an actual receiver
            // type. Mirrors the sema checker's rule.
            bool unifyType(const ast::TypeExpr &pattern, const ast::TypeExpr &actual,
                           const List<Str> &typeParams) {
                const ast::TypeExpr *actualPtr = &actual;
                if (pattern.kind != TypeKind::Reference && pattern.kind != TypeKind::Pointer) {
                    while ((actualPtr->kind == TypeKind::Reference
                            || actualPtr->kind == TypeKind::Pointer) && actualPtr->inner) {
                        actualPtr = actualPtr->inner.get();
                    }
                }
                const ast::TypeExpr &a = *actualPtr;
                switch (pattern.kind) {
                    case TypeKind::IntLit:
                        return a.kind == TypeKind::IntLit && a.text == pattern.text;
                    case TypeKind::Named:
                        if (isTypeParamName(pattern.name, typeParams)) return true;
                        return a.kind == TypeKind::Named && a.name == pattern.name;
                    case TypeKind::Generic:
                        if (isTypeParamName(pattern.name, typeParams)) return true;
                        if (a.kind != TypeKind::Generic) return false;
                        // `PList<T>` is the alias of `&List<T>`; match it against a
                        // `List<T>` receiver pattern (the call dereferences).
                        if (a.name != pattern.name
                            && !(pattern.name == "List" && a.name == "PList")
                            && !(pattern.name == "PList" && a.name == "List")) {
                            return false;
                        }
                        if (a.typeArgs.size() != pattern.typeArgs.size()) return false;
                        for (int i = 0; i < (int) a.typeArgs.size(); i++) {
                            if (!unifyType(*pattern.typeArgs[i], *a.typeArgs[i], typeParams)) {
                                return false;
                            }
                        }
                        return true;
                    case TypeKind::Reference:
                        return a.kind == TypeKind::Reference && a.inner && pattern.inner
                               && unifyType(*pattern.inner, *a.inner, typeParams);
                    case TypeKind::Pointer:
                        return a.kind == TypeKind::Pointer && a.inner && pattern.inner
                               && unifyType(*pattern.inner, *a.inner, typeParams);
                    case TypeKind::Function:
                        return false;
                }
                return false;
            }

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

            // The receiver argument for a lowered call, dereferencing a counted
            // reference or raw pointer when the callee's receiver is a value.
            Str receiverArg(const ast::TypePtr &pattern, const ast::Expr &recv) {
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

            Str memberAccess(const ast::Expr &base, const Str &name) {
                bool arrow = false;
                ast::TypePtr baseType = inferType(base);
                if (baseType) {
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

            // A non-native function with the given name and parameter count.
            const ast::Decl *findFunction(const Str &name, int argCount) {
                for (const Fn &fn: functions) {
                    if (fn.decl->isNative || fn.decl->name != name) continue;
                    if ((int) fn.decl->params.size() == argCount) return fn.decl;
                }
                return nullptr;
            }

            bool isUnitType(const ast::TypeExpr *type) {
                return !type || (type->kind == TypeKind::Named && type->name == "Unit");
            }

            // Best-effort return type of a lambda body: a single trailing
            // expression is the result, otherwise the first `return` value.
            ast::TypePtr inferLambdaReturn(const ast::Expr &e) {
                if (e.body.size() == 1 && e.body[0]->kind == StmtKind::ExprStmt
                    && e.body[0]->expr) {
                    return inferType(*e.body[0]->expr);
                }
                for (const ast::StmtPtr &stmt: e.body) {
                    if (stmt->kind == StmtKind::Return && stmt->returnValue) {
                        return inferType(*stmt->returnValue);
                    }
                }
                return nullptr;
            }

            // Lowers a lambda to a C++ lambda with by-value captures, assignable
            // to the RTL `Func<Ret(Params)>`. Parameter types come from the
            // explicit annotations or from the expected callable type; the return
            // type from the expected callable type or the body.
            Str lambda(const ast::Expr &e, const ast::TypePtr &expected) {
                const ast::TypeExpr *callable = expectedCallable(expected);

                Dictionary<Str, NameKind> savedKinds = nameKinds;
                Dictionary<Str, ast::TypePtr> savedTypes = localTypes;

                List<Str> params;
                for (int i = 0; i < (int) e.paramNames.size(); i++) {
                    ast::TypePtr paramType;
                    if (i < (int) e.paramTypes.size()) paramType = e.paramTypes[i];
                    if (!paramType && callable && i < (int) callable->paramTypes.size()) {
                        paramType = callable->paramTypes[i];
                    }
                    if (!paramType) {
                        nameKinds = savedKinds;
                        localTypes = savedTypes;
                        fail(e.pos, "unsupported: lambda parameter '" + e.paramNames[i]
                                    + "' has no type and no expected callable type");
                        return "/*unsupported*/";
                    }
                    params.push_back(type(*paramType) + " " + e.paramNames[i]);
                    nameKinds[e.paramNames[i]] = kindOf(*paramType);
                    localTypes[e.paramNames[i]] = paramType;
                    if (failed) {
                        nameKinds = savedKinds;
                        localTypes = savedTypes;
                        return "/*unsupported*/";
                    }
                }

                ast::TypePtr returnType = (callable && callable->returnType)
                                              ? callable->returnType
                                              : inferLambdaReturn(e);
                bool unitReturn = isUnitType(returnType.get());

                Str head = "[=](" + join(params, ", ") + ")";
                if (!unitReturn) head += " -> " + type(*returnType);
                if (failed) {
                    nameKinds = savedKinds;
                    localTypes = savedTypes;
                    return "/*unsupported*/";
                }

                Str body;
                bool singleExpression = e.body.size() == 1
                                        && e.body[0]->kind == StmtKind::ExprStmt
                                        && e.body[0]->expr;
                if (singleExpression && !unitReturn) {
                    body = "return " + expr(*e.body[0]->expr, 0, returnType) + ";";
                } else {
                    Str saved = out;
                    out.clear();
                    emitStmts(linear::simplifyBody(linear::lowerBody(e.body)), 1);
                    body = out;
                    out = saved;
                }

                nameKinds = savedKinds;
                localTypes = savedTypes;
                if (failed) return "/*unsupported*/";

                if (body.find('\n') == Str::npos) {
                    return head + " { " + body + " }";
                }
                return head + " {\n" + body + "}";
            }

            Str exprInner(const ast::Expr &e, const ast::TypePtr &expected) {
                switch (e.kind) {
                    case ExprKind::IntLit:
                    case ExprKind::FloatLit:
                    case ExprKind::StrLit:
                    case ExprKind::CharLit:
                        return e.text;
                    case ExprKind::BoolLit:
                        return e.boolValue ? "true" : "false";
                    case ExprKind::NullLit:
                        return nullTo(expected);
                    case ExprKind::Name:
                        if (e.text == "this") return "self";
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
                        return lambda(e, expected);
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
                            Str all = receiverArg(ext->receiver, *callee.lhs);
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
                        Str all = expr(*callee.lhs, 9);
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
