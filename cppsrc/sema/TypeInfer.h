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

    // The `List<T>` a *type* names, looking through any handle (`*List<T>`, `&List<T>`)
    // and the alias form `PList<T>` (= `&List<T>`). Null when the type is not a list.
    // This is the *argument* side of the packing rule - what tells `addAll(*xs)` from
    // `addAll(1)` when the two have the same argument count (`specs/functions.md`,
    // "Packing the trailing arguments").
    const ast::TypeExpr* listTypeOf(const ast::TypeExpr* type);

    // Whether a *parameter*'s type is one a call may pack its trailing arguments into:
    // a by-value `List<T>` or a borrowed `*List<T>`. Deliberately not the counted
    // `&List<T>`/`PList<T>` - a packed list is a throwaway temporary, so a control block
    // and a reference count it never needed would be the cost of the convenience - and
    // not a type reached through a name the rule cannot see through, such as an alias.
    // Both the checker (which accepts the arity) and the IL extractor (which builds the
    // list) ask it, so the rule lives in one place.
    bool isPackTarget(const ast::TypeExpr* type);

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
        // The class `this` is an instance of, when it is one the *lowering* built: a
        // state machine (impl_specs/yield.md). Such a class has no declaration the
        // program wrote, so its fields are reachable only through this - and the
        // rules have to reach them, or `this.<field>` (a machine's `_sm_self`, say)
        // types as nothing and the emitter has to guess what it is.
        const ast::Decl* selfDecl = nullptr;

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

    // The type of **one expression**, with the names in scope given explicitly: the
    // same rules the pass applies to a whole body, asked about a single node.
    //
    // The IL extractor is the caller that needs this. Its frame is flat (a name per
    // slot, no scopes), and the slots it *synthesizes* - the place behind a read, a
    // value it has to declare - have no declaration in the source to take a type
    // from, so it asks here. That is what lets every slot of an instruction list
    // carry a type and every instruction be one operation over typed slots
    // (`impl_specs/linear-il.md`). Null when the rules cannot name the expression.
    ast::TypePtr typeOfExpr(const ast::Expr& expr, const Facts& facts, const Body& ctx,
                            const Dictionary<Str, ast::TypePtr>& names);
}
