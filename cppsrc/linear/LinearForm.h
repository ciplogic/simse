#pragma once

#include "../ast/Ast.h"
#include "../sema/TypeInfer.h"

// The linear IL: one flat instruction list per body (impl_specs/linear-il.md).
//
// The lowering (`Linear.h`, `Simplify.h`) leaves a body that is *almost* an
// instruction list already: control flow is labels and jumps, every value position
// is one operation deep, and the lowering's own storage is declared once at the top.
// This is the same data with the last trees removed - a statement's small expression
// tree, and a lambda's body - so a pass or a backend can read one instruction at a
// time without knowing anything about scopes.
//
// The tables are per body and hold what instructions refer to by index:
//
//   types    every type the body mentions (the frame's slot types included)
//   vars     the frame: name, type index, and what the slot is for
//   pool     text: string literals, field names, operators
//   methods  every callee the body calls, with enough of its shape to verify a call
//   labels   label names; a label instruction carries one, a jump names one
//   ops      the instructions, in order
//   lines    the source line of each instruction, parallel to `ops`
//
// Nothing here is *inferred*: the frame is typed by the type pass before this runs
// (`sema::inferTypes`), and the backend decides spelling - an append for `+` on two
// `Str`s, an address for a `*T` receiver - from the slot types. Those are lookups,
// not inference.
namespace linear {
    // `Expression` is the lowering's own storage (`_sm_expr<n>`, `simse_sw_<n>`):
    // declared, so a backend declares it where the hoisting put it. `Temp` is the
    // extractor's: a slot it synthesised for a position the statements did not hold in
    // a slot of its own. It is a declared slot like any other when the type rules can
    // name it (the frame carries the type, and the declaration goes to the top of the
    // instruction list with the rest of the frame); a slot whose type they cannot name
    // has no declaration to print, so a backend folds its single use into the
    // instruction that reads it.
    enum class IlVarKind { Argument, Local, Expression, Temp };
    // What the *shape* of a call is: the backend resolves the symbol (a native's C
    // name, a `_make_` factory, an extension lowered to a free function) from the
    // name and the operand types it finds in the frame, so the IL stays free of
    // anything but the program's own spelling.
    enum class IlMethodKind { Function, Method, Constructor };
    // `Var` is a **slot** - a destination, or a place read (`GetField`'s base);
    // `Value` is a slot *or* a constant, which is what most operand positions are.
    // The opcodes say nothing about types: the frame does.
    enum class IlOperandKind { Var, Value, Text, Type, Method, Label, None };

    // The instruction set: one opcode per IL instruction. An enum rather than the
    // opcode's *name*, because every pass and the backend dispatch on it - an `int`
    // compare instead of a string compare, and an instruction that carries four bytes
    // of opcode instead of a `Str` (which is what `IlOp` used to be). The order
    // matches `signatureTable()`'s, so the signature of an opcode is `table[kind]`
    // (impl_specs/linear-il.md has the prose).
    enum class IlOpKind {
        Label,
        Goto,
        IfTrue,
        IfFalse,
        Declare,
        DeclareInit,
        SetVar,
        SetVar_Null,
        BinaryOp,
        UnaryOp,
        Cast,
        Box,
        Deref,
        CopyValue,
        Store,
        GetField,
        SetField,
        GetIndex,
        SetIndex,
        FieldAddr,
        IndexAddr,
        GetStatic,
        GetStaticAddr,
        SetStatic,
        Call,
        CallVoid,
        CallIndirect,
        CallIndirectVoid,
        CallCtor,
        Return,
        ReturnVoid,
        Lambda,
        Unsupported,
    };

    // The opcode's spelling, for the dump (`--showLinearRepresentation`). The
    // backend never needs it: it switches on the enum.
    const char* ilOpKindText(IlOpKind kind);

    struct IlVar {
        Str name;
        int typeIndex = 0;
        IlVarKind kind = IlVarKind::Expression;
    };

    struct IlMethod {
        Str name;
        IlMethodKind kind = IlMethodKind::Function;
        int argCount = 0;
        // The type a *static* call is reached through (`Res<Str>.ok`,
        // `Color.fromInt`), or -1. A receiver call carries the receiver in its first
        // argument instead.
        int staticBase = -1;
        // The result's type index, or -1 when the call has none.
        int returnType = -1;
        // The type index of every argument at this call site (a constant argument
        // contributes the type of the constant).
        List<int> argTypes;
    };

    struct IlOp {
        IlOpKind kind = IlOpKind::Unsupported;
        // An operand is an `int` whose meaning is the opcode's operand kind at that
        // position: an index into a table, or a literal. A **negative** operand in a
        // `Value` position is a literal: it names `pool[-1-n]`, whose text the
        // backend prints verbatim (`0`, `"abc"`, `true`). That is what keeps a
        // constant inside the instruction that uses it, spelled the way the emitter
        // spells it (`i > 0`), instead of a slot that would have to be declared and
        // read. A `Var` operand is always a slot; a destination never is a literal.
        List<int> operands;
    };

    struct IlBody {
        Str file;
        int line = 0;   // the source line the body starts on
        Str symbol;     // the emitted name of the body's function
        Str signature;  // `<params> -> <return>`, for the dump's header
        List<Str> types;
        // The type behind each `types` entry, when the extractor had the node: a
        // frame slot's declared type, the type a construction names. The dump only
        // needs the text, but a backend spells C++ from these - `List<Str>` is
        // `List<Str>` to a reader and a node for its type arguments to the emitter.
        List<ast::TypePtr> typeNodes;
        // What the *type pass* proved for every name in this body (`sema::inferTypes`),
        // which is more than the frame's slots carry: a name holding a state machine
        // is typed `..T`, and a declaration is never written with that (the emitted
        // C++ uses `auto`, `linear/Yield.cpp` relies on the declaration staying
        // untyped). The backend still needs it, because `for` wraps what it iterates
        // in `smToYield()` and, on a machine, that wrap is the identity - a decision
        // only the receiver's type can make (impl_specs/for.md). Seeding a frame from
        // this is how a machine-typed slot stays typed without a statement tree.
        Dictionary<Str, ast::TypePtr> inferredTypes;
        List<IlVar> vars;
        List<Str> pool;
        List<IlMethod> methods;
        List<Str> labels;
        List<IlOp> ops;
        List<int> lines;
    };

    // The instruction set: one entry per opcode, with its operands' kinds in order
    // (`"Var,Text,Var,Var"`; a trailing `...` means "the kind before it repeats here",
    // which is how a call's arguments are spelled). The printer and the verifier read
    // this table, so it is the one place the IL's shape is written down. The rows are
    // in `IlOpKind` order.
    struct IlSignature {
        IlOpKind kind;
        const char* operands;
    };
    const List<IlSignature>& ilSignatures();
    const IlSignature* ilSignature(IlOpKind kind);

    // What operand `index` of `op` is, resolved from the signature table (including
    // a repeating trailing kind). A verifier, a printer and a backend all read the
    // operands through this rather than counting them by hand.
    IlOperandKind ilOperandKind(const IlOp& op, int index);

    // The type of a slot, when the body knows it; null for a slot whose type the
    // extractor could not spell (a synthesised place: it can only be *folded* into
    // the instruction that reads it, never declared).
    ast::TypePtr ilVarType(const IlBody& body, int slot);

    // The type behind a `types` entry, when the extractor had the node.
    ast::TypePtr ilTypeNode(const IlBody& body, int index);

    // Whether an op's first `Var` operand is its *destination* - the rule the table
    // leaves implicit: "the first `Var` operand of an op that produces a value is
    // where the value goes". A backend folds a `Declare` into the instruction that
    // writes the slot it declared when this is true and the two are adjacent (which
    // is how a declaration with an initializer stays one line); `Store`, `Return` and
    // a void call read their first operand and are not fold candidates.
    bool ilWritesDestination(IlOpKind kind);

    // What the extractor needs to know about the body's function.
    struct IlFunction {
        const ast::Decl* decl = nullptr;  // name, parameters, return type
        ast::TypePtr receiver;            // a method's receiver type, else null
        Str symbol;                       // the emitted name (for the dump's header)
        // File-level statics the body may name, with their type text: a name that is
        // neither a frame slot nor one of these is a `GetStatic` the extractor cannot
        // type (it stays `?` in the frame).
        Dictionary<Str, Str> statics;
        // The class this body is a method of, when the *lowering* built it (a state
        // machine): its fields are what `this.<name>` reaches, and the type rules need
        // them (`sema::Body::selfDecl`).
        const ast::Decl *selfDecl = nullptr;

        // What a *lambda* body needs to run its own type pass: the program facts (the
        // extractor types a lambda body itself, because a lambda's frame is not the
        // enclosing function's), the type parameters in scope there, and the flat
        // record the enclosing body's pass produced (the top-level body's frame).
        const sema::Facts *facts = nullptr;
        List<Str> typeParams;
        const Dictionary<Str, ast::TypePtr> *inferredTypes = nullptr;

        // A *lambda* body has no declaration: its parameters, the class it is the
        // `invoke` of, and the names it captures (which are fields of that class, not
        // slots of the frame). All of them are empty for a function.
        List<Str> paramNames;
        List<ast::TypePtr> paramTypes;
        Str closureSymbol;
        Dictionary<Str, bool> captures;
        Dictionary<Str, ast::TypePtr> captureTypes;  // the field types the class has
    };

    // A lambda, as the language's model says it is: a class with one field per
    // captured variable and one method (`invoke`; C++ spells it `operator()`), so a
    // callable value is an *instance* of it and `&lambda` a counted handle to one
    // (`specs/memory-model.md`). The capture set is computed - the free variables of
    // the body - and each is read and written as a field of the instance, which is
    // what makes the environment explicit in the instruction list.
    struct IlClosure {
        Str symbol;          // the emitted class name: `<owner>_closure<n>`
        Str signature;       // `(captures) (params) -> ret`, for the dump
        List<Str> captures;  // the fields, in order
        // The fields' types, which are the enclosing frame's: a class field has the
        // type of the variable it holds.
        List<ast::TypePtr> captureTypes;
        List<IlVar> params;  // the method's parameters
        int bodyIndex = -1;  // into `IlUnit::lambdas`
    };

    // One function-like body and the lambdas it constructs. The lambda bodies are
    // *flat* (a lambda's own lambdas are in the same list, so nothing nests), and the
    // closure table says which class each of them is and where its body is.
    struct IlUnit {
        IlBody body;
        List<IlBody> lambdas;
        List<IlClosure> closures;  // in first-construction order
    };

    // The dump of a whole unit: the body, then a section per lambda.
    Str printIlUnit(const IlUnit& unit);

    // The IL's spelling of a type: the language's own (`List<Str>*`, `&Int`, `Box<T>`,
    // `(Int, Int) -> Bool`), unqualified. The dump is read by a person; the backend
    // prints its own text from the slots' types.
    Str ilTypeText(const ast::TypeExpr& type);

    // Builds the IL of one body: the body itself plus the lambdas it constructs. Pure:
    // the statements are not modified. Anything the extractor cannot express yet is an
    // explicit marker instruction (`Unsupported`) rather than a silent omission.
    IlUnit extractIlUnit(const IlFunction& fn, const List<ast::StmtPtr>& body, const Str& file);

    // The dump: the tables, then one line per instruction, with the operands
    // resolved for the reader. Deterministic - it can be compared byte for byte.
    Str printIlBody(const IlBody& body);

    // `--showLinearRepresentation`: the emitter forms the IL of every body it
    // emits and writes the dump to stderr, so the linear form can be read next to
    // the code it produced (impl_specs/linear-il.md). Off unless the driver asks
    // for it, and it never touches the emitted C++.
    bool showIl();
    void setShowIl(bool value);
}
