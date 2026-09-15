#pragma once

#include "../ast/Ast.h"

// A semantic step that runs on a *lowered* body, after the linear and expression
// lowering have produced the emitter's final vocabulary
// (impl_specs/linear-lowering.md): it gives every declaration the lowering
// introduces - and any `val`/`var` the program left unannotated - a type, so the
// emitter does not have to guess one while it emits and does not fall back to
// `auto`.
//
// It is deliberately *not* a reifier. A type parameter in scope is a perfectly
// good type to spell (the emitted C++ is a template, so the C++ compiler
// specializes it later), and an explicit instantiation substitutes its type
// arguments into the call's result. Anything the pass cannot spell in this body -
// a type parameter that is not in scope, a lambda, `&x`/`*x`/`copy(x)`/`null`, a
// call it cannot resolve - leaves the declaration untyped, exactly as it is today.
//
// The facts the pass needs are the program-level declarations the emitter has
// already collected; the pass itself never looks at files. This header also
// carries the type helpers that both the pass and the emitter use, so the two
// agree on what a receiver type is, what a handle is and when two types unify.
namespace sema {
    // ---- type helpers (shared with the emitter) ---------------------------

    ast::TypePtr namedType(const Str& name);

    ast::TypePtr genericType(const Str& name, const List<ast::TypePtr>& args);

    // The name of a built-in (RTL) type: it keeps its C++ spelling and needs no
    // declaration to be usable in a type position.
    bool isRtlTypeName(const Str& name);

    // The pointee after stripping any number of `&`/`*` handles.
    const ast::TypeExpr* pointee(const ast::TypePtr& type);

    // Whether a type is reached through a handle (`&T`, `*T`, or the `PList<T>`
    // alias of `&List<T>`).
    bool isHandleType(const ast::TypeExpr* type);

    // Whether a pointee is a container with `operator[]` element access.
    bool isIndexableContainer(const ast::TypeExpr* type);

    bool isTypeParamName(const Str& name, const List<Str>& typeParams);

    // Structural unification of a pattern (an extension receiver, which may mention
    // the extension's type parameters) against an actual type. A pattern that names
    // a type parameter matches anything.
    bool unifyType(const ast::TypeExpr& pattern, const ast::TypeExpr& actual,
                   const List<Str>& typeParams);

    // The same unification, but recording what each pattern type parameter matched
    // (`List<T>` against `List<Str>` binds T := Str) so a generic extension's
    // result type can be substituted. Fails on a conflicting binding.
    bool bindTypes(const ast::TypeExpr& pattern, const ast::TypeExpr& actual,
                   const List<Str>& typeParams, Dictionary<Str, ast::TypePtr>& bindings);

    // Replaces every type parameter of `type` from `bindings`. Returns null when
    // one of them is unbound - the caller then has no type to spell.
    ast::TypePtr substituteBindings(const ast::TypePtr& type,
                                    const Dictionary<Str, ast::TypePtr>& bindings,
                                    const List<Str>& typeParams);

    // ---- the pass ---------------------------------------------------------

    // A program-level function/method fact the inference can resolve a call with.
    struct FnFact {
        const ast::Decl* decl = nullptr;
        ast::TypePtr receiver; // null for a plain top-level function
        List<Str> templateParams;
    };

    // A `native fun` extension (`this` first parameter): its receiver pattern picks
    // the overload and its return type answers the call.
    struct ExtFact {
        ast::TypePtr receiver;
        ast::TypePtr returnType;
        List<Str> typeParams;
    };

    // Everything about the program the inference reads. The emitter fills this in
    // from its own symbol collection; it is all borrowed pointers and shared type
    // nodes, so filling it costs no copying of declarations.
    struct Facts {
        Dictionary<Str, const ast::Decl*> types; // data classes, enums, aliases
        Dictionary<Str, bool> enumNames;
        List<FnFact> functions;
        Dictionary<Str, List<ExtFact>> nativeExtensions;
        Dictionary<Str, ast::TypePtr> statics; // file-level `var`/`val`
    };

    // One function-like body being annotated: its declaration (which carries the
    // parameters), the type of `this` when there is one, and the type parameters in
    // scope (the class's plus the function's).
    struct Body {
        const ast::Decl* decl = nullptr;
        ast::TypePtr selfType; // the type of `this`, when there is one
        List<Str> typeParams;

        // A *lambda* body has no declaration. Its frame is its own parameters plus the
        // values it captures, which the language models as fields of the closure
        // instance (`specs/memory-model.md`): inside the body a captured name simply
        // *has* that type, so the pass seeds it like a parameter. The two lists are
        // parallel (`paramTypes` may be empty where a parameter's type is inferred
        // from the callable type the lambda is used against).
        List<Str> paramNames;
        List<ast::TypePtr> paramTypes;
        Dictionary<Str, ast::TypePtr> captures;
    };

    // Returns the same statements with the type of every untyped `VarDecl` the pass
    // can prove filled in (`Stmt.type`). Only the declarations that gain a type are
    // copied, so a body that is already fully annotated comes back unchanged and the
    // parsed AST is never modified.
    //
    // `inferred`, when given, receives every name the pass proved a type for - the
    // parameters, the captures, and every declaration - **including** the ones a C++
    // declaration cannot spell: a name holding a state machine is `..T`, which the
    // emitted C++ types `auto`, and the frame still has to know it (a `for` wraps what
    // it iterates in `smToYield()`, whose identity on a machine only the receiver's
    // type can establish). `Stmt.type` keeps its other meaning - "a type a declaration
    // can be written with" - so the machine stays out of it (`linear/Yield.cpp` relies
    // on that to reject a `for` over a machine crossing a `yield`).
    List<ast::StmtPtr> inferTypes(const List<ast::StmtPtr>& body, const Facts& facts,
                                  const Body& ctx,
                                  Dictionary<Str, ast::TypePtr>* inferred = nullptr);
}
