#include "TypeInfer.h"

using ast::ExprKind;
using ast::StmtKind;
using ast::TypeKind;

namespace sema {
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

    bool isRtlTypeName(const Str &name) {
        static const List<Str> names = {
            "Int", "Int8", "Int16", "Int32", "Int64",
            "Float32", "Float64", "Char", "Bool", "Str",
            "List", "Array", "RawArray", "Opt", "Res",
            "Dictionary", "SmallVector", "PList",
            "XmlNode", "Attribute", "AstXmlNode", "AstNodeAttribute",
            "Span", "StrView", "FileStream",
        };
        for (const Str &candidate: names) {
            if (candidate == name) return true;
        }
        return false;
    }

    const ast::TypeExpr *pointee(const ast::TypePtr &type) {
        const ast::TypeExpr *current = type.get();
        while (current && (current->kind == TypeKind::Reference
                           || current->kind == TypeKind::Pointer)
               && current->inner) {
            current = current->inner.get();
        }
        return current;
    }

    bool isHandleType(const ast::TypeExpr *type) {
        if (!type) return false;
        if (type->kind == TypeKind::Reference || type->kind == TypeKind::Pointer) {
            return true;
        }
        return type->kind == TypeKind::Generic && type->name == "PList";
    }

    bool isIndexableContainer(const ast::TypeExpr *type) {
        if (!type) return false;
        if (type->kind == TypeKind::Named) return type->name == "Str";
        if (type->kind == TypeKind::Generic) {
            const Str &name = type->name;
            return name == "List" || name == "Array" || name == "Dictionary"
                   || name == "SmallVector" || name == "Span";
        }
        return false;
    }

    bool isTypeParamName(const Str &name, const List<Str> &typeParams) {
        for (const Str &param: typeParams) {
            if (param == name) return true;
        }
        return false;
    }

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
            case TypeKind::Yield:
                // `..T` is a state machine (impl_specs/yield.md) and carries its element
                // type the way a pointer carries its pointee, so a pattern `..T` matches
                // `..Int` element-wise - which is what lets the prelude's
                // `fun ..T.smToYield(): ..T` be found for a machine.
                return a.kind == TypeKind::Yield && a.inner && pattern.inner
                       && unifyType(*pattern.inner, *a.inner, typeParams);
            case TypeKind::Function:
                return false;
        }
        return false;
    }

    namespace {
        // Records `name := type`, refusing a second, different binding. A pattern
        // type parameter that stays unbound is not an error here: the caller finds
        // out when it substitutes the result type and nothing is left to spell.
        bool bindOne(Dictionary<Str, ast::TypePtr> &bindings, const Str &name,
                     const ast::TypePtr &type) {
            auto existing = bindings.find(name);
            if (existing == bindings.end()) {
                bindings[name] = type;
                return true;
            }
            if (!existing->second || !type) return existing->second == type;
            return unifyType(*existing->second, *type, List<Str>());
        }
    }

    bool bindTypes(const ast::TypeExpr &pattern, const ast::TypeExpr &actual,
                   const List<Str> &typeParams, Dictionary<Str, ast::TypePtr> &bindings) {
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
                if (isTypeParamName(pattern.name, typeParams)) {
                    return bindOne(bindings, pattern.name,
                                   std::make_shared<ast::TypeExpr>(*actualPtr));
                }
                return a.kind == TypeKind::Named && a.name == pattern.name;
            case TypeKind::Generic: {
                if (isTypeParamName(pattern.name, typeParams)) {
                    return bindOne(bindings, pattern.name,
                                   std::make_shared<ast::TypeExpr>(*actualPtr));
                }
                if (a.kind != TypeKind::Generic) return false;
                if (a.name != pattern.name
                    && !(pattern.name == "List" && a.name == "PList")
                    && !(pattern.name == "PList" && a.name == "List")) {
                    return false;
                }
                if (a.typeArgs.size() != pattern.typeArgs.size()) return false;
                for (int i = 0; i < (int) a.typeArgs.size(); i++) {
                    if (!bindTypes(*pattern.typeArgs[i], *a.typeArgs[i], typeParams, bindings)) {
                        return false;
                    }
                }
                return true;
            }
            case TypeKind::Reference:
                return a.kind == TypeKind::Reference && a.inner && pattern.inner
                       && bindTypes(*pattern.inner, *a.inner, typeParams, bindings);
            case TypeKind::Pointer:
                return a.kind == TypeKind::Pointer && a.inner && pattern.inner
                       && bindTypes(*pattern.inner, *a.inner, typeParams, bindings);
            case TypeKind::Yield:
                // The machine's element type, bound the way a pointer's pointee is.
                return a.kind == TypeKind::Yield && a.inner && pattern.inner
                       && bindTypes(*pattern.inner, *a.inner, typeParams, bindings);
            case TypeKind::Function:
                return false;
        }
        return false;
    }

    ast::TypePtr substituteBindings(const ast::TypePtr &type,
                                    const Dictionary<Str, ast::TypePtr> &bindings,
                                    const List<Str> &typeParams) {
        if (!type) return nullptr;
        switch (type->kind) {
            case TypeKind::IntLit:
                return type;
            case TypeKind::Named: {
                if (!isTypeParamName(type->name, typeParams)) return type;
                auto found = bindings.find(type->name);
                return found == bindings.end() ? nullptr : found->second;
            }
            case TypeKind::Generic: {
                List<ast::TypePtr> args;
                for (const ast::TypePtr &arg: type->typeArgs) {
                    ast::TypePtr mapped = substituteBindings(arg, bindings, typeParams);
                    if (!mapped) return nullptr;
                    args.push_back(mapped);
                }
                auto copied = std::make_shared<ast::TypeExpr>(*type);
                copied->typeArgs = args;
                return copied;
            }
            case TypeKind::Reference:
            case TypeKind::Pointer:
            case TypeKind::Yield: {
                // `..T` (a machine yielding `T`, impl_specs/yield.md) carries its element
                // type the same way a pointer carries its pointee, so it substitutes the
                // same way: the type is unspellable either way, but its *element* type is
                // what a `for`'s loop variable is typed from.
                ast::TypePtr inner = substituteBindings(type->inner, bindings, typeParams);
                if (!inner) return nullptr;
                auto copied = std::make_shared<ast::TypeExpr>(*type);
                copied->inner = inner;
                return copied;
            }
            case TypeKind::Function: {
                List<ast::TypePtr> params;
                for (const ast::TypePtr &param: type->paramTypes) {
                    ast::TypePtr mapped = substituteBindings(param, bindings, typeParams);
                    if (!mapped) return nullptr;
                    params.push_back(mapped);
                }
                ast::TypePtr ret = substituteBindings(type->returnType, bindings, typeParams);
                if (!ret) return nullptr;
                auto copied = std::make_shared<ast::TypeExpr>(*type);
                copied->paramTypes = params;
                copied->returnType = ret;
                return copied;
            }
        }
        return nullptr;
    }

    namespace {
        // The inference for one body. It walks the lowered statements in order and
        // keeps one type per name in scope, so a name is typed by the declaration
        // that reaches it - not by emission order, which is what the emitter used to
        // rely on.
        class Infer {
        public:
            Infer(const Facts &facts, const Body &body) : facts(facts), body(body) {
                scopes.emplace_back();
                if (body.decl) {
                    for (const ast::Param &param: body.decl->params) {
                        if (param.type) mark(param.name, param.type);
                    }
                }
                // A lambda's frame: its own parameters (a type may be missing where the
                // callable type it is used against supplies it), then the values it
                // captures, which are fields of the closure instance and therefore
                // simply have their type inside the body.
                for (int i = 0; i < (int) body.paramNames.size(); i++) {
                    if (i < (int) body.paramTypes.size() && body.paramTypes[i]) {
                        mark(body.paramNames[i], body.paramTypes[i]);
                    }
                }
                for (const auto &entry: body.captures) mark(entry.first, entry.second);
            }

            List<ast::StmtPtr> statements(const List<ast::StmtPtr> &stmts) {
                List<ast::StmtPtr> out;
                for (const ast::StmtPtr &stmt: stmts) {
                    if (!stmt) {
                        out.push_back(stmt);
                        continue;
                    }
                    ast::StmtPtr annotated = statement(stmt);
                    out.push_back(annotated ? annotated : stmt);
                }
                return out;
            }

            // Every name the pass proved a type for, whatever a declaration can spell.
            const Dictionary<Str, ast::TypePtr> &proven() const {
                return allTypes;
            }

            // One expression, typed against the names the caller knows. The extractor's
            // frame is flat (one binding per name, no scopes), which is exactly what
            // `names` is; the scope is pushed and popped so the call leaves nothing
            // behind.
            ast::TypePtr typeOf(const ast::Expr &e, const Dictionary<Str, ast::TypePtr> &names) {
                scopes.emplace_back();
                for (const auto &entry: names) {
                    if (entry.second) mark(entry.first, entry.second);
                }
                ast::TypePtr type = infer(e);
                scopes.pop_back();
                return type;
            }

        private:
            const Facts &facts;
            const Body &body;
            List<Dictionary<Str, ast::TypePtr>> scopes;
            // The flat record of the same bindings, for the caller: a frame is keyed by
            // *name* (the lowering has already given each scope its own variables), and
            // it wants the machine-typed slots too, which `Stmt.type` never carries.
            Dictionary<Str, ast::TypePtr> allTypes;

            // Records a binding in the scope being built *and* in the flat record.
            void mark(const Str &name, const ast::TypePtr &type) {
                scopes.back()[name] = type;
                allTypes[name] = type;
            }

            // ---- scope ----------------------------------------------------

            ast::TypePtr lookup(const Str &name) {
                for (int i = (int) scopes.size() - 1; i >= 0; i--) {
                    auto found = scopes[i].find(name);
                    if (found != scopes[i].end()) return found->second;
                }
                return nullptr;
            }

            // A declaration is annotated only when its initializer is an expression
            // whose *value* the C++ type of the declaration can name. A lambda needs
            // its expected callable type and `null` has no type of its own; everything
            // else - including `&x`, `*x` and `copy(x)` - is typed below, so the
            // emitter never has to fall back to `auto` for it.
            static bool declarable(const ast::Expr &init) {
                switch (init.kind) {
                    case ExprKind::Lambda:
                    case ExprKind::NullLit:
                        return false;
                    default:
                        return true;
                }
            }

            // Whether the emitter can spell this type in this body. A type parameter
            // that is not in scope would name nothing in the emitted C++, and an
            // undeclared name would make the emitter fail; both mean "leave it to
            // `auto`" instead.
            bool spellable(const ast::TypeExpr &type) {
                switch (type.kind) {
                    case TypeKind::IntLit:
                        return true;
                    case TypeKind::Named:
                        return spellableName(type.name);
                    case TypeKind::Generic: {
                        if (!spellableName(type.name)) return false;
                        for (const ast::TypePtr &arg: type.typeArgs) {
                            if (!arg || !spellable(*arg)) return false;
                        }
                        return true;
                    }
                    case TypeKind::Reference:
                    case TypeKind::Pointer:
                        return type.inner && spellable(*type.inner);
                    case TypeKind::Yield:
                        // `..T` is not a value type: the function whose body yields is
                        // lowered to a state machine (impl_specs/yield.md), and a name
                        // holding one is typed by the C++ compiler (`auto`).
                        return false;
                    case TypeKind::Function: {
                        if (!type.returnType || !spellable(*type.returnType)) return false;
                        for (const ast::TypePtr &param: type.paramTypes) {
                            if (!param || !spellable(*param)) return false;
                        }
                        return true;
                    }
                }
                return false;
            }

            bool spellableName(const Str &name) {
                if (name == "Unit") return false; // `void` has no values
                return isTypeParamName(name, body.typeParams)
                       || facts.types.count(name) > 0
                       || isRtlTypeName(name);
            }

            // ---- statements -----------------------------------------------

            // The rewritten statement, or null when nothing about it changes (the
            // caller then keeps the original, so the parsed AST is never modified).
            ast::StmtPtr statement(const ast::StmtPtr &stmt) {
                switch (stmt->kind) {
                    case StmtKind::VarDecl: {
                        ast::TypePtr type = stmt->type;
                        if (!type && stmt->init) type = infer(*stmt->init);
                        // The name is recorded even when it stays untyped: the next
                        // declaration still resolves against it the way the emitter's
                        // own inference would.
                        if (type) mark(stmt->name, type);
                        if (stmt->type || !type || !stmt->init || !declarable(*stmt->init)
                            || !spellable(*type)) {
                            return nullptr;
                        }
                        auto annotated = std::make_shared<ast::Stmt>(*stmt);
                        annotated->type = type;
                        return annotated;
                    }
                    case StmtKind::Block: {
                        scopes.emplace_back();
                        List<ast::StmtPtr> inner = statements(stmt->body);
                        scopes.pop_back();
                        bool changed = inner.size() != stmt->body.size();
                        for (int i = 0; !changed && i < (int) inner.size(); i++) {
                            changed = inner[i] != stmt->body[i];
                        }
                        if (!changed) return nullptr;
                        auto rewritten = std::make_shared<ast::Stmt>(*stmt);
                        rewritten->body = inner;
                        return rewritten;
                    }
                    default:
                        return nullptr;
                }
            }

            // ---- expressions ----------------------------------------------

            bool isTypeName(const Str &name) {
                return facts.types.count(name) > 0 || isRtlTypeName(name);
            }

            ast::TypePtr infer(const ast::Expr &e) {
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
                        if (e.text == "this") return body.selfType;
                        ast::TypePtr local = lookup(e.text);
                        if (local) return local;
                        // File-level static storage (specs/statics.md).
                        auto stat = facts.statics.find(e.text);
                        if (stat != facts.statics.end()) return stat->second;
                        // A bare enum type name used as the receiver of a static
                        // conversion, e.g. `Color.Red` / `Color.fromInt(x)`.
                        if (facts.enumNames.count(e.text) > 0) return namedType(e.text);
                        return nullptr;
                    }
                    case ExprKind::GenericName:
                        return isTypeName(e.text) ? genericType(e.text, e.typeArgs) : nullptr;
                    case ExprKind::Member: {
                        if (e.lhs && e.lhs->kind == ExprKind::Name
                            && facts.enumNames.count(e.lhs->text) > 0) {
                            // An enum member expression has the enum's type.
                            return namedType(e.lhs->text);
                        }
                        ast::TypePtr baseType = e.lhs ? infer(*e.lhs) : nullptr;
                        const ast::TypeExpr *base = pointee(baseType);
                        if (!base) return nullptr;
                        if (base->kind == TypeKind::Generic && base->name == "Res") {
                            // The spec spells these `value`/`error` (specs/core-types.md);
                            // the RTL's own fields are `Value`/`Error`, and a name it does
                            // not remap is emitted as written - so both reach C++, and both
                            // have to type here.
                            if ((e.text == "value" || e.text == "Value") && !base->typeArgs.empty()) {
                                return base->typeArgs[0];
                            }
                            if (e.text == "error" || e.text == "Error") return namedType("Str");
                        }
                        if (base->kind == TypeKind::Named || base->kind == TypeKind::Generic) {
                            const ast::Decl *decl = nullptr;
                            auto declared = facts.types.find(base->name);
                            if (declared != facts.types.end()) {
                                decl = declared->second;
                            } else if (body.selfDecl != nullptr
                                       && body.selfDecl->name == base->name) {
                                // A class the lowering built (a state machine): its fields
                                // are not a declaration the program wrote, so they are
                                // reached through the body's own context.
                                decl = body.selfDecl;
                            }
                            if (decl != nullptr && decl->kind == ast::DeclKind::DataClass) {
                                for (const ast::Field &field: decl->fields) {
                                    if (field.name == e.text) {
                                        return instantiate(*decl, *base, field.type);
                                    }
                                }
                            }
                        }
                        return nullptr;
                    }
                    case ExprKind::Call:
                        return e.lhs ? callReturn(*e.lhs) : nullptr;
                    case ExprKind::Index: {
                        ast::TypePtr baseType = e.lhs ? infer(*e.lhs) : nullptr;
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
                    case ExprKind::Ref:
                        // `&x` boxes a copy for the call (`std::make_shared<T>(x)`),
                        // so the C++ type is a counted reference to whatever `x` is.
                        return handle(TypeKind::Reference, e.lhs ? infer(*e.lhs) : nullptr);
                    case ExprKind::Deref: {
                        // `*x` is the *address* of what `x` denotes: of a value's own
                        // storage (`&x`), of a counted reference's pointee (`x.get()`),
                        // or the pointer itself when `x` already is one - in which case
                        // the emitter reads through it, so the type is the pointee.
                        if (!e.lhs) return nullptr;
                        ast::TypePtr operand = infer(*e.lhs);
                        if (!operand) return nullptr;
                        if (operand->kind == TypeKind::Pointer) return operand->inner;
                        if (operand->kind == TypeKind::Reference) {
                            return handle(TypeKind::Pointer, operand->inner);
                        }
                        return handle(TypeKind::Pointer, operand);
                    }
                    case ExprKind::Copy: {
                        // `copy(x)` is the *value*: a plain read of a value, or the
                        // pointee of a handle (`*(x)`).
                        if (!e.lhs) return nullptr;
                        ast::TypePtr operand = infer(*e.lhs);
                        const ast::TypeExpr *value = operand ? pointee(operand) : nullptr;
                        return value ? std::make_shared<ast::TypeExpr>(*value) : nullptr;
                    }
                    case ExprKind::Unary:
                        return e.lhs ? infer(*e.lhs) : nullptr;
                    case ExprKind::Binary:
                        if (e.text == "==" || e.text == "!=" || e.text == "<" || e.text == ">"
                            || e.text == "<=" || e.text == ">=" || e.text == "&&" || e.text == "||") {
                            return namedType("Bool");
                        }
                        return e.lhs ? infer(*e.lhs) : nullptr;
                    case ExprKind::Lambda:
                        // A lambda's type comes from the callable type it is used
                        // against, which is the emitter's business (its parameters may
                        // even be inferred from there).
                        return nullptr;
                }
                return nullptr;
            }

            ast::TypePtr handle(TypeKind kind, const ast::TypePtr &inner) {
                if (!inner) return nullptr;
                auto type = std::make_shared<ast::TypeExpr>();
                type->kind = kind;
                type->inner = inner;
                return type;
            }

            // A data class member type with the class's type parameters bound to the
            // receiver's arguments (`Box<Int>.value` with `value: T` is `Int`).
            ast::TypePtr instantiate(const ast::Decl &decl, const ast::TypeExpr &base,
                                    const ast::TypePtr &memberType) {
                if (!memberType || decl.typeParams.empty()) return memberType;
                if (base.kind != TypeKind::Generic
                    || base.typeArgs.size() != decl.typeParams.size()) {
                    return memberType;
                }
                Dictionary<Str, ast::TypePtr> bindings;
                for (int i = 0; i < (int) decl.typeParams.size(); i++) {
                    bindings[decl.typeParams[i]] = base.typeArgs[i];
                }
                return substituteBindings(memberType, bindings, decl.typeParams);
            }

            // A callable's own return type. Calling a *value* - a parameter or local of
            // a function type (`predicate(x)` where `predicate: (Char) -> Bool`) - is an
            // indirect call, and its result is that type's return type, resolved through
            // a `typealias` (`CharPredicate`) exactly as the emitter resolves it.
            ast::TypePtr callableReturn(ast::TypePtr type) {
                const ast::TypeExpr *current = pointee(type);
                for (int guard = 0; current && guard < 16; guard++) {
                    if (current->kind == TypeKind::Function) return current->returnType;
                    if (current->kind != TypeKind::Named) return nullptr;
                    auto found = facts.types.find(current->name);
                    if (found == facts.types.end()) return nullptr;
                    const ast::Decl *decl = found->second;
                    if (!decl || decl->kind != ast::DeclKind::TypeAlias) return nullptr;
                    current = pointee(decl->targetType);
                }
                return nullptr;
            }

            ast::TypePtr callReturn(const ast::Expr &callee) {
                if (callee.kind == ExprKind::GenericName) {
                    if (isTypeName(callee.text)) return genericType(callee.text, callee.typeArgs);
                    return functionReturn(callee.text, callee.typeArgs, nullptr);
                }
                if (callee.kind == ExprKind::Name) {
                    if (isTypeName(callee.text)) return namedType(callee.text);
                    ast::TypePtr direct = functionReturn(callee.text, List<ast::TypePtr>(), nullptr);
                    if (direct) return direct;
                    return callableReturn(lookup(callee.text));
                }
                if (callee.kind == ExprKind::Member) return memberReturn(callee);
                return nullptr;
            }

            // The return type of a call, with the callee's type parameters bound from
            // an explicit instantiation (`identity<Int>(7)`) or from the receiver
            // (`Box<Int>.get()`). A function whose result still mentions a type
            // parameter is *not* monomorphized here - it stays symbolic and the
            // emitted C++ template specializes it later - but a parameter nothing
            // binds leaves no type to spell, so the call answers null.
            ast::TypePtr functionReturn(const Str &name, const List<ast::TypePtr> &typeArgs,
                                        const ast::TypeExpr *receiver) {
                for (const FnFact &fn: facts.functions) {
                    if (fn.decl->name != name || !fn.decl->returnType) continue;
                    if ((fn.receiver != nullptr) != (receiver != nullptr)) continue;
                    Dictionary<Str, ast::TypePtr> bindings;
                    if (fn.receiver && receiver
                        && !bindTypes(*fn.receiver, *receiver, fn.templateParams, bindings)) {
                        continue;
                    }
                    if (!typeArgs.empty()) {
                        if (typeArgs.size() != fn.templateParams.size()) continue;
                        bool bound = true;
                        for (int i = 0; i < (int) typeArgs.size() && bound; i++) {
                            bound = bindOne(bindings, fn.templateParams[i], typeArgs[i]);
                        }
                        if (!bound) continue;
                    }
                    ast::TypePtr result =
                            substituteBindings(fn.decl->returnType, bindings, fn.templateParams);
                    if (result) return result;
                }
                return nullptr;
            }

            // The result of a member call (`recv.name(...)`), resolved the way the
            // emitter lowers it: a Simse-declared extension/method first, then a
            // native extension, then the built-in accessors.
            ast::TypePtr memberReturn(const ast::Expr &callee) {
                ast::TypePtr receiverType = infer(*callee.lhs);
                const ast::TypeExpr *recv = pointee(receiverType);
                if (!recv) return nullptr;
                for (const FnFact &fn: facts.functions) {
                    if (fn.decl->isNative || !fn.receiver || fn.decl->name != callee.text
                        || !fn.decl->returnType) {
                        continue;
                    }
                    Dictionary<Str, ast::TypePtr> bindings;
                    if (!bindTypes(*fn.receiver, *recv, fn.templateParams, bindings)) continue;
                    ast::TypePtr result =
                            substituteBindings(fn.decl->returnType, bindings, fn.templateParams);
                    if (result) return result;
                }
                auto extensions = facts.nativeExtensions.find(callee.text);
                if (extensions != facts.nativeExtensions.end()) {
                    for (const ExtFact &ext: extensions->second) {
                        if (!ext.receiver || !ext.returnType) continue;
                        Dictionary<Str, ast::TypePtr> bindings;
                        if (!bindTypes(*ext.receiver, *recv, ext.typeParams, bindings)) continue;
                        ast::TypePtr result =
                                substituteBindings(ext.returnType, bindings, ext.typeParams);
                        if (result) return result;
                    }
                }
                if (recv->kind == TypeKind::Yield) {
                    // `..T` is a state machine (impl_specs/yield.md), and its two
                    // methods are part of the lowering's ABI: `next()` hands out the
                    // optional, `advance(*v)` answers whether there was a value. Typing
                    // them here is what makes a `for`'s loop variable a *typed* binding
                    // rather than an `auto` the emitter would have to guess a symbol
                    // for (which it cannot: `v.toString()` on an unknown receiver
                    // picks the `StrView` native).
                    if (callee.text == "next" && recv->inner) {
                        List<ast::TypePtr> args;
                        args.push_back(recv->inner);
                        return genericType("Opt", args);
                    }
                    if (callee.text == "advance") return namedType("Bool");
                    // A machine is already iterable: `x.smToYield()` on one is `x`, so the
                    // wrap a `for` puts around what it iterates is the identity there
                    // (impl_specs/for.md). `..T` is not a spellable type, so no function
                    // could take one.
                    if (callee.text == "smToYield") return receiverType;
                }
                if (recv->kind == TypeKind::Generic) {
                    // The constructors the spec spells as static forms (`Opt<int>.none()`,
                    // `Opt<int>.some(42)`, `Res<int>.ok(42)`, `Res<int>.err(...)`,
                    // specs/core-types.md). Nothing declares them - `Type.name(...)` lowers
                    // to `Type::name(...)` syntactically - so their result type is stated
                    // here: the type they are qualified by. It is per *name*, not "a static
                    // form answers its own type": `EnumType.fromInt(n)` answers
                    // `Opt<EnumType>` (specs/declarations.md) and is not in this list.
                    if ((recv->name == "Opt" && (callee.text == "none" || callee.text == "some"))
                        || (recv->name == "Res" && (callee.text == "ok" || callee.text == "err"))) {
                        return std::make_shared<ast::TypeExpr>(*recv);
                    }
                    if (callee.text == "value" && recv->name == "Opt" && !recv->typeArgs.empty()) {
                        return recv->typeArgs[0];
                    }
                    if ((callee.text == "size" || callee.text == "count")
                        && (recv->name == "List" || recv->name == "Array"
                            || recv->name == "Dictionary" || recv->name == "SmallVector"
                            || recv->name == "Span")) {
                        return namedType("Int");
                    }
                }
                if (recv->kind == TypeKind::Named && callee.text == "size"
                    && recv->name == "Str") {
                    return namedType("Int");
                }
                if (callee.text == "isOk" || callee.text == "hasValue") {
                    return namedType("Bool");
                }
                return nullptr;
            }
        };

        // The pass's own rules, asked about one expression (see the header).
        ast::TypePtr typeOfOne(const ast::Expr &expr, const Facts &facts, const Body &body,
                               const Dictionary<Str, ast::TypePtr> &names) {
            Infer infer(facts, body);
            return infer.typeOf(expr, names);
        }
    }

    List<ast::StmtPtr> inferTypes(const List<ast::StmtPtr> &body, const Facts &facts,
                                  const Body &ctx, Dictionary<Str, ast::TypePtr> *inferred) {
        Infer infer(facts, ctx);
        List<ast::StmtPtr> out = infer.statements(body);
        if (inferred) *inferred = infer.proven();
        return out;
    }

    ast::TypePtr typeOfExpr(const ast::Expr &expr, const Facts &facts, const Body &ctx,
                            const Dictionary<Str, ast::TypePtr> &names) {
        return typeOfOne(expr, facts, ctx, names);
    }
}
