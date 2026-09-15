#include "LinearForm.h"

#include "Linear.h"
#include "Simplify.h"

#include <string>

// The extraction is a *projection*: it reads the body the emitter is about to
// consume and writes it down as instructions, without changing the statements.
// That is deliberate (impl_specs/linear-il.md, "Open decisions" 6): the dump is
// what lets the form be read and argued about before anything else moves onto it.
//
// Two shapes the instruction list cannot express yet stay visible as marker
// instructions rather than being dropped:
//
//   Lambda       - a lambda's body is still a tree inside an expression;
//                  flattening it is the closure work (spec item 5).
//   Unsupported  - anything else the extractor does not recognise, carrying the
//                  reason as pool text. A body whose dump has none is a body the
//                  IL covers in full.
namespace linear {
    using ast::Expr;
    using ast::ExprKind;
    using ast::ExprPtr;
    using ast::Stmt;
    using ast::StmtKind;
    using ast::StmtPtr;

    namespace {
        // ---- the instruction set ------------------------------------------------

        // One entry per opcode, with its operands' kinds in order. A trailing
        // `...` means the kind before it repeats (a call's arguments). The
        // printer and the verifier read this table, so it is the one place the
        // IL's shape is written down; `impl_specs/linear-il.md` keeps the prose.
        // The rows are in `IlOpKind` order, and `ilSignature` relies on that.
        const List<IlSignature> &signatureTable() {
            static const List<IlSignature> table = {
                    {IlOpKind::Label, "Label"},
                    {IlOpKind::Goto, "Label"},
                    {IlOpKind::IfTrue, "Value,Label"},
                    {IlOpKind::IfFalse, "Value,Label"},
                    {IlOpKind::Declare, "Var"},
                    // A declaration the instruction *after* it initialises: the two are
                    // one line of C++ (`Str out = "";`). The distinction is real
                    // because the hoisting turns a declaration's initializer into a
                    // separate assignment, which is a bare `Declare`.
                    {IlOpKind::DeclareInit, "Var"},
                    // One assignment op for every kind of value: the destination
                    // slot's type is what a backend spells from, so the opcode says
                    // nothing about it - `SetVar x, y` copies a slot, `SetVar x, 5`
                    // and `SetVar x, "abc"` carry the constant in the operand.
                    {IlOpKind::SetVar, "Var,Value"},
                    // ... except `null`, whose spelling comes from the destination's
                    // type (`Opt<T>()` vs `nullptr`) rather than from a literal.
                    {IlOpKind::SetVar_Null, "Var"},
                    {IlOpKind::BinaryOp, "Var,Text,Value,Value"},
                    {IlOpKind::UnaryOp, "Var,Text,Value"},
                    {IlOpKind::Cast, "Var,Value"},       // Enum.toInt(): the one cast
                    {IlOpKind::Box, "Var,Var"},          // &x       -> a counted handle
                    {IlOpKind::Deref, "Var,Var"},        // *x       -> a borrow (`&x`), a
                                                         //            `.get()`, or a load
                                                         //            of a `*T`, by the
                                                         //            operand's type - a
                                                         //            lookup the backend
                                                         //            makes
                    {IlOpKind::CopyValue, "Var,Var"},    // copy(x)  -> the value behind a handle
                    {IlOpKind::Store, "Var,Value"},      // *p = v
                    {IlOpKind::GetField, "Var,Var,Text"},
                    {IlOpKind::SetField, "Var,Text,Value"},
                    {IlOpKind::GetIndex, "Var,Var,Value"},
                    {IlOpKind::SetIndex, "Var,Value,Value"},
                    {IlOpKind::FieldAddr, "Var,Var,Text"},
                    {IlOpKind::IndexAddr, "Var,Var,Value"},
                    {IlOpKind::GetStatic, "Var,Text"},
                    {IlOpKind::SetStatic, "Text,Value"},
                    {IlOpKind::Call, "Var,Method,Value..."},
                    {IlOpKind::CallVoid, "Method,Value..."},
                    {IlOpKind::CallIndirect, "Var,Var,Value..."},
                    {IlOpKind::CallIndirectVoid, "Var,Value..."},
                    {IlOpKind::CallCtor, "Var,Type,Value..."},
                    {IlOpKind::Return, "Value"},
                    {IlOpKind::ReturnVoid, ""},
                    {IlOpKind::Lambda, "Var"},
                    {IlOpKind::Unsupported, "Var,Text"},
            };
            return table;
        }

        // The opcode's spelling, in `IlOpKind` order (the dump reads it).
        const char *const opKindTextTable[] = {
                "Label", "Goto", "IfTrue", "IfFalse", "Declare", "DeclareInit", "SetVar",
                "SetVar_Null", "BinaryOp", "UnaryOp", "Cast", "Box", "Deref", "CopyValue",
                "Store", "GetField", "SetField", "GetIndex", "SetIndex", "FieldAddr",
                "IndexAddr", "GetStatic", "SetStatic", "Call", "CallVoid", "CallIndirect",
                "CallIndirectVoid", "CallCtor", "Return", "ReturnVoid", "Lambda",
                "Unsupported",
        };

        Str varKindText(IlVarKind kind) {
            switch (kind) {
                case IlVarKind::Argument: return "Argument";
                case IlVarKind::Local: return "Local";
                case IlVarKind::Expression: return "Expression";
                case IlVarKind::Temp: return "Temp";
            }
            return "?";
        }

        Str methodKindText(IlMethodKind kind) {
            switch (kind) {
                case IlMethodKind::Function: return "Function";
                case IlMethodKind::Method: return "Method";
                case IlMethodKind::Constructor: return "Constructor";
            }
            return "?";
        }

        Str joinList(const List<Str> &parts, const char *separator) {
            Str out;
            for (int i = 0; i < (int) parts.size(); i++) {
                if (i > 0) out += separator;
                out += parts[i];
            }
            return out;
        }

        Str intText(int value) {
            return Str(std::to_string(value));
        }

        // The type a receiver slot has in the frame: the language's own spelling of
        // what the backend declares (`*T self` for a value receiver, `&T` for a
        // handle, `*T` for an explicit pointer receiver).
        Str receiverTypeText(const ast::TypeExpr &type) {
            if (type.kind == ast::TypeKind::Reference) {
                return Str("&") + (type.inner ? ilTypeText(*type.inner) : Str("?"));
            }
            if (type.kind == ast::TypeKind::Pointer) {
                return Str("*") + (type.inner ? ilTypeText(*type.inner) : Str("?"));
            }
            return Str("*") + ilTypeText(type);
        }

        // The same, as a node: a value receiver's slot is a *pointer* to the receiver
        // type, which is what makes `self->field` and `(*self)` come out of the
        // emitter's own rules.
        ast::TypePtr receiverTypeNode(const ast::TypeExpr &type) {
            if (type.kind == ast::TypeKind::Reference || type.kind == ast::TypeKind::Pointer) {
                return std::make_shared<ast::TypeExpr>(type);
            }
            auto pointer = std::make_shared<ast::TypeExpr>();
            pointer->kind = ast::TypeKind::Pointer;
            pointer->pos = type.pos;
            pointer->inner = std::make_shared<ast::TypeExpr>(type);
            return pointer;
        }

        // An integer literal is spelled by its digits, which the pool holds like any
        // other literal's text - there is no numeric operand kind to parse.

        // ---- the closure of a lambda ---------------------------------------------
        //
        // The names a lambda body reads from *outside* itself: every read that is not
        // one of its parameters and not a name it declares. That set is the closure -
        // what the class's fields are - and it is computed, not guessed. Order is
        // first-read, because the fields' order (and so every table the IL builds) has
        // to be reproducible.
        void collectExprNames(const Expr &e, List<Str> &order, Dictionary<Str, bool> &seen) {
            if (e.kind == ExprKind::Name) {
                if (e.text != "this" && seen.count(e.text) == 0) {
                    seen[e.text] = true;
                    order.push_back(e.text);
                }
                return;
            }
            // A nested lambda is its own closure: its free names are resolved against
            // *its* frame when it is extracted. (A nested lambda reading one of this
            // lambda's captures is not projected yet - the captures are fields, so it
            // would have to read the field into a slot first.)
            if (e.kind == ExprKind::Lambda) return;
            if (e.lhs) collectExprNames(*e.lhs, order, seen);
            if (e.rhs) collectExprNames(*e.rhs, order, seen);
            for (const ExprPtr &arg: e.args) {
                if (arg) collectExprNames(*arg, order, seen);
            }
        }

        void collectStmtNames(const List<StmtPtr> &list, Dictionary<Str, bool> &declared,
                              List<Str> &order, Dictionary<Str, bool> &seen) {
            for (const StmtPtr &stmtPtr: list) {
                if (!stmtPtr) continue;
                const Stmt &stmt = *stmtPtr;
                if (stmt.kind == StmtKind::VarDecl && !stmt.name.empty()) {
                    declared[stmt.name] = true;
                }
                if (stmt.cond) collectExprNames(*stmt.cond, order, seen);
                if (stmt.target) collectExprNames(*stmt.target, order, seen);
                if (stmt.value) collectExprNames(*stmt.value, order, seen);
                if (stmt.init) collectExprNames(*stmt.init, order, seen);
                if (stmt.expr) collectExprNames(*stmt.expr, order, seen);
                if (stmt.returnValue) collectExprNames(*stmt.returnValue, order, seen);
                collectStmtNames(stmt.body, declared, order, seen);
                collectStmtNames(stmt.thenBody, declared, order, seen);
                collectStmtNames(stmt.elseBody, declared, order, seen);
            }
        }

        class Extractor {
        public:
            Extractor(const IlFunction &fn, const Str &file, IlUnit &unit, int &closureCounter)
                : fn(fn), unit(unit), closureCounter(closureCounter) {
                out.file = file;
                out.line = fn.decl ? fn.decl->pos.line : 0;
                out.symbol = fn.symbol;
                // The types the enclosing pass proved, so the frame carries the slots a
                // declaration could not name (a `..T` machine). The extractor still adds
                // each slot's own declared type as it goes; both are keys by name.
                if (fn.inferredTypes) out.inferredTypes = *fn.inferredTypes;
                captures = &fn.captures;
                buildFrame();
                out.signature = signatureText();
            }

            IlBody run(const List<StmtPtr> &body) {
                stmts(body);
                return std::move(out);
            }

        private:
            const IlFunction &fn;
            IlUnit &unit;
            int &closureCounter;
            IlBody out;
            const Dictionary<Str, bool> *captures = nullptr;
            Dictionary<Str, int> varAt;     // slot name -> `vars` index
            Dictionary<Str, int> typeAt;
            Dictionary<Str, int> poolAt;
            Dictionary<Str, int> methodAt;  // a call's shape key -> `methods` index
            Dictionary<Str, int> labelAt;
            int nextBase = 1;               // `_sm_base<n>` for a synthesized slot
            int line = 0;

            // ---- tables ---------------------------------------------------------

            int addVar(const Str &name, const Str &typeText, IlVarKind kind,
                       const ast::TypePtr &type = nullptr) {
                IlVar slot;
                slot.name = name;
                slot.typeIndex = typeIndex(typeText, type);
                slot.kind = kind;
                out.vars.push_back(slot);
                varAt[name] = (int) out.vars.size() - 1;
                return (int) out.vars.size() - 1;
            }

            // The type table: text for the dump, the node (when there is one) for a
            // backend. The first node seen for a text wins, so the table does not
            // depend on which mention happened to carry it.
            int typeIndex(const Str &text, const ast::TypePtr &node = nullptr) {
                auto found = typeAt.find(text);
                if (found != typeAt.end()) {
                    const int index = found->second;
                    if (node && index < (int) out.typeNodes.size() && !out.typeNodes[index]) {
                        out.typeNodes[index] = node;
                    }
                    return index;
                }
                out.types.push_back(text);
                out.typeNodes.push_back(node);
                typeAt[text] = (int) out.types.size() - 1;
                return (int) out.types.size() - 1;
            }

            int poolIndex(const Str &text) {
                auto found = poolAt.find(text);
                if (found != poolAt.end()) return found->second;
                out.pool.push_back(text);
                poolAt[text] = (int) out.pool.size() - 1;
                return (int) out.pool.size() - 1;
            }

            int labelIndex(const Str &name) {
                auto found = labelAt.find(name);
                if (found != labelAt.end()) return found->second;
                out.labels.push_back(name);
                labelAt[name] = (int) out.labels.size() - 1;
                return (int) out.labels.size() - 1;
            }

            // A method's identity is its name, its kind, the type it is reached
            // through (a static call), and the types it is passed: the same name
            // over two receivers is two entries.
            int methodIndex(const Str &name, IlMethodKind kind, int staticBase, int returnType,
                            const List<int> &argTypes) {
                Str key = name + "|" + methodKindText(kind) + "|" + intText(staticBase);
                for (int argType: argTypes) key += "|" + intText(argType);
                auto found = methodAt.find(key);
                if (found != methodAt.end()) return found->second;
                IlMethod method;
                method.name = name;
                method.kind = kind;
                method.argCount = (int) argTypes.size();
                method.staticBase = staticBase;
                method.returnType = returnType;
                method.argTypes = argTypes;
                out.methods.push_back(method);
                methodAt[key] = (int) out.methods.size() - 1;
                return (int) out.methods.size() - 1;
            }

            bool hasVar(const Str &name) {
                return varAt.find(name) != varAt.end();
            }

            int varIndex(const Str &name) {
                auto found = varAt.find(name);
                return found == varAt.end() ? -1 : found->second;
            }

            int freshSlot(const Str &typeText) {
                return freshSlot(typeText, nullptr);
            }

            int freshSlot(const Str &typeText, const ast::TypePtr &type) {
                int slot = addVar(Str("_sm_base") + intText(nextBase++), typeText,
                                  IlVarKind::Temp, type);
                // A slot the extractor synthesised exists nowhere in the statements,
                // so the instruction list has to say where it comes from: here, in
                // front of the instruction that first writes it.
                emit(IlOpKind::Declare, {slot});
                return slot;
            }

            void emit(IlOpKind kind, const List<int> &operands) {
                IlOp op;
                op.kind = kind;
                op.operands = operands;
                out.ops.push_back(op);
                out.lines.push_back(line);
            }

            void unsupported(const Str &what) {
                emit(IlOpKind::Unsupported, {freshSlot(Str("?")), poolIndex(what)});
            }

            // ---- the frame ------------------------------------------------------

            void buildFrame() {
                if (!fn.closureSymbol.empty()) {
                    // A lambda: the receiver is the closure instance - the class whose
                    // fields the captures are - and the parameter list is the lambda's.
                    auto classType = std::make_shared<ast::TypeExpr>();
                    classType->kind = ast::TypeKind::Named;
                    classType->name = fn.closureSymbol;
                    addVar("self", Str("*") + fn.closureSymbol, IlVarKind::Argument,
                           receiverTypeNode(*classType));
                    for (int i = 0; i < (int) fn.paramNames.size(); i++) {
                        ast::TypePtr paramType = i < (int) fn.paramTypes.size()
                                                         ? fn.paramTypes[i]
                                                         : nullptr;
                        addVar(fn.paramNames[i],
                               paramType ? ilTypeText(*paramType) : Str("?"),
                               IlVarKind::Argument, paramType);
                    }
                    return;
                }
                const ast::Decl *decl = fn.decl;
                bool hasSelf = false;
                if (fn.receiver) {
                    addVar("self", receiverTypeText(*fn.receiver), IlVarKind::Argument,
                           receiverTypeNode(*fn.receiver));
                    hasSelf = true;
                }
                for (const ast::Param &param: decl->params) {
                    if (param.name == "this" && !hasSelf) {
                        addVar("self",
                               param.type ? receiverTypeText(*param.type) : Str("?"),
                               IlVarKind::Argument,
                               param.type ? receiverTypeNode(*param.type) : nullptr);
                        hasSelf = true;
                        continue;
                    }
                    addVar(param.name, param.type ? ilTypeText(*param.type) : Str("?"),
                           IlVarKind::Argument, param.type);
                }
            }

            Str signatureText() {
                List<Str> params;
                for (const IlVar &slot: out.vars) {
                    if (slot.kind != IlVarKind::Argument) continue;
                    params.push_back(out.types[slot.typeIndex] + " " + slot.name);
                }
                // A lambda's result is whatever its body returns, which C++ deduces
                // (`auto operator()`); a function's is its declared type.
                Str retText = Str("?");
                if (fn.decl && fn.decl->returnType) retText = ilTypeText(*fn.decl->returnType);
                return "(" + joinList(params, ", ") + ") -> " + retText;
            }

            // ---- statements -----------------------------------------------------

            void stmts(const List<StmtPtr> &list) {
                for (const StmtPtr &stmt: list) {
                    if (stmt) statement(*stmt);
                }
            }

            void statement(const Stmt &stmt) {
                line = stmt.pos.line;
                switch (stmt.kind) {
                    case StmtKind::Label:
                        emit(IlOpKind::Label, {labelIndex(stmt.name)});
                        return;
                    case StmtKind::Goto:
                        emit(IlOpKind::Goto, {labelIndex(stmt.name)});
                        return;
                    case StmtKind::IfTrue:
                        emit(IlOpKind::IfTrue, {operand(stmt.cond), labelIndex(stmt.name)});
                        return;
                    case StmtKind::IfFalse:
                        emit(IlOpKind::IfFalse, {operand(stmt.cond), labelIndex(stmt.name)});
                        return;
                    case StmtKind::VarDecl: {
                        Str typeText = stmt.type ? ilTypeText(*stmt.type) : Str("?");
                        const IlVarKind kind = isSlotName(stmt.name) ? IlVarKind::Expression
                                                                    : IlVarKind::Local;
                        int slot = addVar(stmt.name, typeText, kind, stmt.type);
                        emit(stmt.init ? IlOpKind::DeclareInit : IlOpKind::Declare, {slot});
                        if (stmt.init) into(slot, *stmt.init);
                        return;
                    }
                    case StmtKind::Assign:
                        assign(stmt);
                        return;
                    case StmtKind::Return:
                        if (stmt.returnValue) {
                            emit(IlOpKind::Return, {operand(*stmt.returnValue)});
                        } else {
                            emit(IlOpKind::ReturnVoid, {});
                        }
                        return;
                    case StmtKind::ExprStmt:
                        if (stmt.expr && stmt.expr->kind == ExprKind::Call) {
                            call(-1, *stmt.expr);
                            return;
                        }
                        // A read with no destination: keep it visible rather than
                        // dropping it silently.
                        unsupported("expression statement");
                        return;
                    case StmtKind::Block:
                        stmts(stmt.body);
                        return;
                    case StmtKind::If:
                    case StmtKind::While:
                    case StmtKind::Break:
                    case StmtKind::Continue:
                        unsupported("structured statement reached the IL");
                        return;
                }
                unsupported("statement");
            }

            void assign(const Stmt &stmt) {
                const Expr &target = *stmt.target;
                const Expr &value = *stmt.value;
                switch (target.kind) {
                    case ExprKind::Name: {
                        // A write to a captured variable writes the closure's field.
                        if (captures != nullptr && captures->count(target.text) > 0) {
                            emit(IlOpKind::SetField, {varIndex("self"), poolIndex(target.text),
                                              operand(value)});
                            return;
                        }
                        int slot = varIndex(target.text);
                        if (slot >= 0) {
                            into(slot, value);
                            return;
                        }
                        emit(IlOpKind::SetStatic, {poolIndex(target.text), operand(value)});
                        return;
                    }
                    case ExprKind::Member: {
                        if (isTypeBase(*target.lhs)) {
                            emit(IlOpKind::SetStatic, {poolIndex(baseText(*target.lhs) + "." + target.text),
                                               operand(value)});
                            return;
                        }
                        emit(IlOpKind::SetField, {receiver(*target.lhs), poolIndex(target.text),
                                          operand(value)});
                        return;
                    }
                    case ExprKind::Index:
                        emit(IlOpKind::SetIndex, {receiver(*target.lhs), operand(*target.rhs),
                                          operand(value)});
                        return;
                    case ExprKind::Deref:
                        // `*p = v`: the emitter writes through the pointer's type.
                        emit(IlOpKind::Store, {operand(*target.lhs), operand(value)});
                        return;
                    default:
                        unsupported("assignment target");
                        return;
                }
            }

            // ---- values ---------------------------------------------------------

            // The value `expr` produces, as an operand: a frame slot, a literal (a
            // negative operand, see `literalOperand`), or - when the position holds
            // more than a name - a slot the extractor synthesizes for the place.
            int operand(const ExprPtr &expr) {
                return expr ? operand(*expr) : freshSlot(Str("?"));
            }

            int operand(const Expr &e) {
                switch (e.kind) {
                    case ExprKind::IntLit: case ExprKind::FloatLit:
                    case ExprKind::StrLit: case ExprKind::CharLit:
                        // A literal in a value position rides the instruction as an
                        // operand: the pool holds the token's own text, so a backend
                        // prints exactly what the statement path printed - `i > 0`,
                        // not `_sm_base1 = 0; ... i > _sm_base1`. A `null` is the one
                        // exception (see below).
                        return literalOperand(e.text);
                    case ExprKind::BoolLit:
                        return literalOperand(e.boolValue ? Str("true") : Str("false"));
                    case ExprKind::NullLit: {
                        // `null`'s spelling depends on the expected type (`Opt<T>()`
                        // vs `nullptr`), which only the destination slot's type
                        // states for certain - so it is materialised.
                        int slot = freshSlot(Str("?"));
                        emit(IlOpKind::SetVar_Null, {slot});
                        return slot;
                    }
                    case ExprKind::Name: return name(e);
                    case ExprKind::Member: case ExprKind::Index: case ExprKind::Call:
                    case ExprKind::Binary: case ExprKind::Unary: case ExprKind::Ref:
                    case ExprKind::Deref: case ExprKind::Copy: {
                        int slot = freshSlot(Str("?"));
                        into(slot, e);
                        return slot;
                    }
                    case ExprKind::Lambda:
                        return lambda(e, -1);
                    case ExprKind::GenericName:
                        unsupported("type name in a value position");
                        return freshSlot(Str("?"));
                }
                unsupported("expression");
                return freshSlot(Str("?"));
            }

            // Writes `e` into `slot`, one instruction per operation. This is where the
            // IL's opcode set is exercised: every value the lowering produces is one
            // operation deep, so one case here is one instruction.
            void into(int slot, const Expr &e) {
                switch (e.kind) {
                    case ExprKind::IntLit: case ExprKind::FloatLit: case ExprKind::StrLit:
                    case ExprKind::CharLit:
                        emit(IlOpKind::SetVar, {slot, literalOperand(e.text)});
                        return;
                    case ExprKind::BoolLit:
                        emit(IlOpKind::SetVar,
                             {slot, literalOperand(e.boolValue ? Str("true") : Str("false"))});
                        return;
                    case ExprKind::NullLit:
                        emit(IlOpKind::SetVar_Null, {slot});
                        return;
                    case ExprKind::Name:
                        emit(IlOpKind::SetVar, {slot, name(e)});
                        return;
                    case ExprKind::Member:
                        if (isTypeBase(*e.lhs)) {
                            emit(IlOpKind::GetStatic,
                                 {slot, poolIndex(baseText(*e.lhs) + "." + e.text)});
                            return;
                        }
                        emit(IlOpKind::GetField, {slot, valueOf(*e.lhs), poolIndex(e.text)});
                        return;
                    case ExprKind::Index:
                        emit(IlOpKind::GetIndex, {slot, valueOf(*e.lhs), operand(*e.rhs)});
                        return;
                    case ExprKind::Binary:
                        emit(IlOpKind::BinaryOp,
                             {slot, poolIndex(e.text), operand(*e.lhs), operand(*e.rhs)});
                        return;
                    case ExprKind::Unary:
                        emit(IlOpKind::UnaryOp, {slot, poolIndex(e.text), operand(*e.lhs)});
                        return;
                    case ExprKind::Ref:
                        emit(IlOpKind::Box, {slot, operand(*e.lhs)});
                        return;
                    case ExprKind::Deref:
                        // `*x` is a borrow, `.get()`, or a load depending on what `x`
                        // is - the backend reads that from the operand's slot type.
                        emit(IlOpKind::Deref, {slot, operand(*e.lhs)});
                        return;
                    case ExprKind::Copy:
                        emit(IlOpKind::CopyValue, {slot, operand(*e.lhs)});
                        return;
                    case ExprKind::Call:
                        call(slot, e);
                        return;
                    case ExprKind::Lambda: {
                        // A lambda whose value is dropped still constructs its class.
                        lambda(e, slot);
                        return;
                    }
                    case ExprKind::GenericName:
                        unsupported("type name in a value position");
                        return;
                }
                unsupported("expression");
            }

            // A read of a place: the value behind it, as a slot.
            int valueOf(const Expr &e) {
                if (e.kind == ExprKind::Name) return name(e);
                int slot = freshSlot(Str("?"));
                into(slot, e);
                return slot;
            }

            // A base that is a *place*: a call's receiver, or an assignment target's
            // base. A plain name is that slot; anything deeper becomes an **address**
            // slot (`FieldAddr`/`IndexAddr`), so a call that mutates its receiver
            // reaches the original and not a copy.
            int receiver(const Expr &e) {
                switch (e.kind) {
                    case ExprKind::Name: return name(e);
                    case ExprKind::Member: {
                        if (isTypeBase(*e.lhs)) return valueOf(e);
                        int slot = freshSlot(Str("*?"));
                        emit(IlOpKind::FieldAddr, {slot, receiver(*e.lhs), poolIndex(e.text)});
                        return slot;
                    }
                    case ExprKind::Index: {
                        int slot = freshSlot(Str("*?"));
                        emit(IlOpKind::IndexAddr, {slot, receiver(*e.lhs), operand(*e.rhs)});
                        return slot;
                    }
                    case ExprKind::Deref:
                        return operand(*e.lhs);
                    default:
                        return valueOf(e);
                }
            }

            // ---- calls -----------------------------------------------------------

            // `dst < 0` means the result is dropped (`CallVoid`).
            void call(int dst, const Expr &e) {
                const Expr &callee = *e.lhs;
                const bool hasDst = dst >= 0;
                const int returnType = hasDst ? out.vars[dst].typeIndex : -1;
                const bool member = callee.kind == ExprKind::Member;
                const bool staticCall = member && isTypeBase(*callee.lhs);
                const bool receiverCall = member && !staticCall;

                // The receiver is evaluated first - it is the leftmost thing the
                // C++ call reads - and a `Method` call carries it as its first
                // argument, so the count of `Var` operands after the callee is
                // exactly what `IlMethod::argCount` says.
                int recvSlot = -1;
                if (receiverCall) recvSlot = receiver(*callee.lhs);

                List<int> args;
                List<int> argTypes;
                for (const ExprPtr &arg: e.args) {
                    int slot = operand(arg);
                    args.push_back(slot);
                    argTypes.push_back(operandType(slot, arg));
                }
                List<int> fullTypes;
                if (recvSlot >= 0) fullTypes.push_back(out.vars[recvSlot].typeIndex);
                for (int argType: argTypes) fullTypes.push_back(argType);

                List<int> operands;
                if (hasDst) operands.push_back(dst);

                if (receiverCall) {
                    operands.push_back(methodIndex(callee.text, IlMethodKind::Method, -1,
                                                   returnType, fullTypes));
                    operands.push_back(recvSlot);
                    for (int arg: args) operands.push_back(arg);
                    emit(hasDst ? IlOpKind::Call : IlOpKind::CallVoid, operands);
                    return;
                }
                if (staticCall) {
                    // A static call: `Res<Str>.ok(x)`, `Color.fromInt(v)`. The type is
                    // part of the method's identity, and the node lets a backend spell
                    // its type arguments as the source wrote them.
                    operands.push_back(methodIndex(callee.text, IlMethodKind::Function,
                                                   typeIndex(baseText(*callee.lhs),
                                                             calleeToTypePtr(*callee.lhs)),
                                                   returnType, argTypes));
                    for (int arg: args) operands.push_back(arg);
                    emit(hasDst ? IlOpKind::Call : IlOpKind::CallVoid, operands);
                    return;
                }
                if (callee.kind == ExprKind::GenericName) {
                    // A construction: `Point(1, 2)`, `List<Str>()`. The type operand is
                    // the *node* the callee was, so a backend can spell the type
                    // arguments the way the source wrote them.
                    if (!hasDst) {
                        unsupported("constructor call with no destination");
                        return;
                    }
                    operands.push_back(typeIndex(baseText(callee), calleeToTypePtr(callee)));
                    for (int arg: args) operands.push_back(arg);
                    emit(IlOpKind::CallCtor, operands);
                    return;
                }
                // A plain function (or a native - the backend resolves the symbol).
                operands.push_back(methodIndex(callee.text, IlMethodKind::Function, -1,
                                               returnType, argTypes));
                for (int arg: args) operands.push_back(arg);
                emit(hasDst ? IlOpKind::Call : IlOpKind::CallVoid, operands);
            }

            // ---- lambdas ---------------------------------------------------------

            // The symbol prefix a synthesized class takes: the body's own emitted name,
            // so two bodies never spell the same class and the dump says where it came
            // from (`ns1_cgJoin_closure1`).
            Str ownerSymbol() const {
                return fn.symbol.empty() ? Str("_closure_owner") : fn.symbol;
            }

            // A lambda, projected the way the language models it: a class with one field
            // per *captured* variable and one method (`invoke`, C++'s `operator()`), so
            // the value is an instance of it and `&lambda` a counted handle to one. The
            // capture set is computed from the body, which is what makes the environment
            // explicit: inside the body a captured name is a *field* of `self`, and the
            // construction passes the values the enclosing frame holds.
            // `dst < 0` means the value goes nowhere (the class is still constructed).
            int lambda(const Expr &e, int dst) {
                const Str symbol = ownerSymbol() + "_closure" + intText(closureCounter++);

                List<Str> read;
                Dictionary<Str, bool> readSeen;
                collectExprNames(e, read, readSeen);
                Dictionary<Str, bool> declared;
                for (const Str &param: e.paramNames) {
                    declared[param] = true;
                }
                collectStmtNames(e.body, declared, read, readSeen);

                // The closure: what the body reads that the *enclosing* frame holds. A
                // name that is neither (a static, a type, a function) is not captured -
                // the enclosing `name()` resolves it the same way it always did.
                List<Str> captured;
                for (const Str &name: read) {
                    if (declared.count(name) > 0) continue;
                    if (name == "this") continue;
                    if (!hasVar(name)) continue;
                    captured.push_back(name);
                }

                IlFunction info;
                info.symbol = symbol;
                info.paramNames = e.paramNames;
                info.paramTypes = e.paramTypes;
                info.closureSymbol = symbol;
                info.statics = fn.statics;
                // A lambda body runs its *own* type pass here, so it needs the facts and
                // the type parameters in scope - and it must not inherit the enclosing
                // body's frame (its own map is set below, from its own pass).
                info.facts = fn.facts;
                info.typeParams = fn.typeParams;
                for (const Str &name: captured) {
                    info.captures[name] = true;
                    // The field's type is the enclosing slot's: the class's field has it,
                    // so a read of it in the body is typed without inference.
                    ast::TypePtr type = ilVarType(out, varIndex(name));
                    if (type) info.captureTypes[name] = type;
                }

                // The lambda's body has a frame of its own, so it is lowered here, the
                // way the emitter lowers it - including the single-expression body,
                // which is the `return` it stands for.
                List<StmtPtr> body = e.body;
                if (body.size() == 1 && body[0] && body[0]->kind == StmtKind::ExprStmt
                    && body[0]->expr) {
                    auto ret = std::make_shared<Stmt>();
                    ret->kind = StmtKind::Return;
                    ret->pos = body[0]->pos;
                    ret->returnValue = body[0]->expr;
                    body.clear();
                    body.push_back(ret);
                }
                // ... and it is *typed* here too, with a frame of its own: parameters and
                // captures. Without this pass the body's own declarations stay untyped,
                // and a slot the frame cannot name sends every spelling decision that
                // needs a type the wrong way (`v.toString()` picks the `StrView`
                // overload; a `..T` receiver hides the `smToYield` identity).
                List<StmtPtr> lowered = lowerForEmission(body);
                Dictionary<Str, ast::TypePtr> lambdaTypes;
                if (fn.facts != nullptr) {
                    sema::Body semantics;
                    semantics.selfType = sema::namedType(symbol);
                    semantics.typeParams = fn.typeParams;
                    semantics.paramNames = info.paramNames;
                    semantics.paramTypes = info.paramTypes;
                    semantics.captures = info.captureTypes;
                    lowered = sema::inferTypes(lowered, *fn.facts, semantics, &lambdaTypes);
                }
                Extractor inner(info, out.file, unit, closureCounter);
                IlBody innerBody = inner.run(finishForEmission(lowered, info.paramNames));
                innerBody.inferredTypes = lambdaTypes;

                IlClosure closure;
                closure.symbol = symbol;
                closure.captures = captured;
                for (const Str &capture: captured) {
                    auto found = info.captureTypes.find(capture);
                    closure.captureTypes.push_back(found == info.captureTypes.end()
                                                           ? nullptr
                                                           : found->second);
                }
                for (const IlVar &slot: innerBody.vars) {
                    if (slot.kind != IlVarKind::Argument || slot.name == "self") continue;
                    closure.params.push_back(slot);
                }
                closure.signature = "(" + joinList(captured, ", ") + ") " + innerBody.signature;
                closure.bodyIndex = (int) unit.lambdas.size();
                unit.lambdas.push_back(innerBody);
                unit.closures.push_back(closure);

                // The value: a construction of the class, with the captured values.
                auto classType = std::make_shared<ast::TypeExpr>();
                classType->kind = ast::TypeKind::Named;
                classType->name = symbol;
                const int target = dst >= 0 ? dst : freshSlot(symbol, classType);
                List<int> operands;
                operands.push_back(target);
                operands.push_back(typeIndex(symbol, classType));
                for (const Str &capture: captured) {
                    // The capture is a slot of *this* frame (that is what put it in the
                    // closure), so the construction passes its value by index.
                    operands.push_back(varIndex(capture));
                }
                emit(IlOpKind::CallCtor, operands);
                return target;
            }

            // ---- names, types and literals ---------------------------------------

            int name(const Expr &e) {
                // A captured variable is a *field* of the closure, not a slot of this
                // frame: reading it reads through `self`.
                if (captures != nullptr && captures->count(e.text) > 0) {
                    auto found = fn.captureTypes.find(e.text);
                    ast::TypePtr type = found == fn.captureTypes.end() ? nullptr : found->second;
                    int slot = freshSlot(type ? ilTypeText(*type) : Str("?"), type);
                    emit(IlOpKind::GetField, {slot, varIndex("self"), poolIndex(e.text)});
                    return slot;
                }
                if (e.text == "this") {
                    int slot = varIndex("self");
                    if (slot >= 0) return slot;
                }
                int slot = varIndex(e.text);
                if (slot >= 0) return slot;
                // A file-level static the extractor was told about, or a name only the
                // backend can resolve (`GetStatic` in both cases).
                auto known = fn.statics.find(e.text);
                Str typeText = known == fn.statics.end() ? Str("?") : known->second;
                int fresh = freshSlot(typeText);
                emit(IlOpKind::GetStatic, {fresh, poolIndex(e.text)});
                return fresh;
            }

            bool isTypeBase(const Expr &e) {
                if (e.kind == ExprKind::GenericName) return true;
                if (e.kind != ExprKind::Name) return false;
                if (e.text == "this" || hasVar(e.text)) return false;
                if (captures != nullptr && captures->count(e.text) > 0) return false;
                return fn.statics.find(e.text) == fn.statics.end();
            }

            Str baseText(const Expr &e) {
                if (e.kind == ExprKind::Name) return e.text;
                return ilTypeText(calleeToType(e));
            }

            // The AST node `GenericName` carries its type arguments; the IL wants the
            // type as text, so reuse `ilTypeText` over a `TypeExpr` built from it.
            ast::TypeExpr calleeToType(const Expr &e) {
                ast::TypeExpr type;
                type.kind = ast::TypeKind::Generic;
                type.name = e.text;
                type.typeArgs = e.typeArgs;
                type.pos = e.pos;
                return type;
            }

            ast::TypePtr calleeToTypePtr(const Expr &e) {
                return std::make_shared<ast::TypeExpr>(calleeToType(e));
            }

            // A literal expression's type, as the frame spells it. A literal operand
            // is a *value* with a type like any other, and the method table's
            // `argTypes` should read the same whether the argument was a slot or a
            // constant.
            static Str literalTypeText(const Expr &e) {
                switch (e.kind) {
                    case ExprKind::IntLit: return "Int";
                    case ExprKind::FloatLit: return "Float64";
                    case ExprKind::StrLit: return "Str";
                    case ExprKind::CharLit: return "Char";
                    case ExprKind::BoolLit: return "Bool";
                    default: return "?";
                }
            }

            int operandType(int slot, const ExprPtr &expr) {
                if (slot >= 0) return out.vars[slot].typeIndex;
                return expr ? typeIndex(literalTypeText(*expr)) : typeIndex(Str("?"));
            }

            // A literal operand: the pool index of the text a backend prints
            // verbatim, tagged negative so it cannot be read as a slot index (only a
            // `Value` operand may be a literal; a destination never is).
            int literalOperand(const Str &text) {
                return -1 - poolIndex(text);
            }
        };

        // ---- the dump -----------------------------------------------------------

        Str padRight(const Str &text, int width) {
            Str out = text;
            while ((int) out.size() < width) out += ' ';
            return out;
        }

        // `"Var,Method,Var..."` -> its tokens. Spelling a signature once per print
        // is fine: the dump is a debug aid, not a pass.
        List<Str> operandTokens(const IlSignature &signature) {
            List<Str> tokens;
            Str current;
            const Str spec = signature.operands;
            for (int i = 0; i < (int) spec.size(); i++) {
                if (spec[i] == ',') {
                    tokens.push_back(current);
                    current = Str();
                    continue;
                }
                current += spec[i];
            }
            if (!current.empty()) tokens.push_back(current);
            return tokens;
        }

        IlOperandKind kindOfToken(const Str &token) {
            // `Var...` repeats the kind before it: the `...` is not part of it.
            Str base = token;
            if (base.size() > 3 && base[base.size() - 1] == '.' && base[base.size() - 2] == '.'
                && base[base.size() - 3] == '.') {
                base = base.substr(0, base.size() - 3);
            }
            if (base == "Var") return IlOperandKind::Var;
            if (base == "Value") return IlOperandKind::Value;
            if (base == "Text") return IlOperandKind::Text;
            if (base == "Type") return IlOperandKind::Type;
            if (base == "Method") return IlOperandKind::Method;
            if (base == "Label") return IlOperandKind::Label;
            return IlOperandKind::None;
        }

        bool repeats(const Str &token) {
            return token.size() > 3 && token[token.size() - 1] == '.' && token[token.size() - 2] == '.'
                   && token[token.size() - 3] == '.';
        }

        IlOperandKind operandKindAt(const List<Str> &tokens, int index) {
            if (tokens.empty()) return IlOperandKind::None;
            const int last = (int) tokens.size() - 1;
            if (index < last) return kindOfToken(tokens[index]);
            if (index == last || repeats(tokens[last])) return kindOfToken(tokens[last]);
            return IlOperandKind::None;
        }

        Str poolText(const IlBody &body, int index) {
            if (index < 0 || index >= (int) body.pool.size()) return Str("?p") + intText(index);
            return body.pool[index];
        }

        const IlMethod *methodAt(const IlBody &body, int index) {
            if (index < 0 || index >= (int) body.methods.size()) return nullptr;
            return &body.methods[index];
        }

        // An operand the dump reads without trusting the instruction's arity: an
        // out-of-range read shows up as a `?` in the text instead of a crash.
        int operandAt(const List<int> &operands, int index) {
            if (index < 0 || index >= (int) operands.size()) return -1;
            return operands[index];
        }

        // A `Var`-position operand as the dump shows it: the slot's name, or - when
        // the operand is a literal (see `literalOperand`: a negative index) - the
        // literal's own text. The two cannot be confused because a destination is
        // never a literal, and every other `Var` position can be either.
        Str varName(const IlBody &body, int index) {
            if (index < 0) return poolText(body, -1 - index);
            if (index >= (int) body.vars.size()) return Str("?v") + intText(index);
            return body.vars[index].name;
        }

        Str typeName(const IlBody &body, int index) {
            if (index < 0 || index >= (int) body.types.size()) return Str("?t") + intText(index);
            return body.types[index];
        }

        // The language spelling of a slot's type, for the dump's `Declare` lines.
        Str varTypeName(const IlBody &body, int slot) {
            if (slot < 0 || slot >= (int) body.vars.size()) return Str("?");
            return typeName(body, body.vars[slot].typeIndex);
        }

        Str labelName(const IlBody &body, int index) {
            if (index < 0 || index >= (int) body.labels.size()) return Str("?L") + intText(index);
            return body.labels[index];
        }

        // A pool entry as the dump shows it: a literal already carries its own
        // quotes; a name or an operator gets them so the two are told apart.
        Str poolAsText(const Str &text) {
            if (text.empty()) return Str("\"\"");
            const char first = text[0];
            const bool literal = first == '"' || first == '\'' || (first >= '0' && first <= '9');
            return literal ? text : Str("\"") + text + Str("\"");
        }

        // The operand as the reader wants it: the name from the table it indexes,
        // with the raw index only in the dump's `?` fallbacks.
        Str renderOperand(const IlBody &body, IlOperandKind kind, int value) {
            switch (kind) {
                case IlOperandKind::Var:
                case IlOperandKind::Value: return varName(body, value);
                case IlOperandKind::Text: return poolAsText(poolText(body, value));
                case IlOperandKind::Type: return typeName(body, value);
                case IlOperandKind::Method: {
                    const IlMethod *method = methodAt(body, value);
                    return method == nullptr ? Str("?m") + intText(value) : method->name;
                }
                case IlOperandKind::Label: return labelName(body, value);
                case IlOperandKind::None: return Str("?");
            }
            return Str("?");
        }

        // The operands of a call, from `first` on, as a comma-separated list.
        Str argList(const IlBody &body, const List<int> &operands, int first) {
            List<Str> args;
            for (int i = first; i < (int) operands.size(); i++) {
                args.push_back(varName(body, operands[i]));
            }
            return joinList(args, ", ");
        }

        // What the instruction means, spelled the way the language would write it.
        // This is the dump's reason to exist: reading instructions, not trees.
        //
        // Every read of an operand is bounds-checked: the dump must survive an
        // instruction the extractor built with the wrong arity (it then shows the
        // `?` markers instead of taking the compiler down).
        Str opComment(const IlBody &body, const IlOp &op) {
            const IlOpKind kind = op.kind;
            const List<int> &operands = op.operands;

            if (kind == IlOpKind::Declare) {
                const int slot = operandAt(operands, 0);
                return "var " + varName(body, slot) + ": " + varTypeName(body, slot);
            }
            if (kind == IlOpKind::Label) return labelName(body, operandAt(operands, 0)) + ":";
            if (kind == IlOpKind::Goto) return "goto " + labelName(body, operandAt(operands, 0));
            if (kind == IlOpKind::IfTrue || kind == IlOpKind::IfFalse) {
                const Str condition = varName(body, operandAt(operands, 0));
                const Str target = labelName(body, operandAt(operands, 1));
                return kind == IlOpKind::IfTrue ? "if (" + condition + ") goto " + target
                                        : "if (!" + condition + ") goto " + target;
            }
            if (kind == IlOpKind::SetVar) {
                return varName(body, operandAt(operands, 0)) + " = "
                       + varName(body, operandAt(operands, 1));
            }
            if (kind == IlOpKind::SetVar_Null) return varName(body, operandAt(operands, 0)) + " = null";
            if (kind == IlOpKind::BinaryOp) {
                return varName(body, operandAt(operands, 0)) + " = "
                       + varName(body, operandAt(operands, 2)) + " "
                       + poolText(body, operandAt(operands, 1)) + " "
                       + varName(body, operandAt(operands, 3));
            }
            if (kind == IlOpKind::UnaryOp) {
                return varName(body, operandAt(operands, 0)) + " = "
                       + poolText(body, operandAt(operands, 1))
                       + varName(body, operandAt(operands, 2));
            }
            if (kind == IlOpKind::Cast) {
                return varName(body, operandAt(operands, 0)) + " = cast "
                       + varName(body, operandAt(operands, 1));
            }
            if (kind == IlOpKind::Box) {
                return varName(body, operandAt(operands, 0)) + " = &"
                       + varName(body, operandAt(operands, 1));
            }
            if (kind == IlOpKind::Deref) {
                return varName(body, operandAt(operands, 0)) + " = *"
                       + varName(body, operandAt(operands, 1));
            }
            if (kind == IlOpKind::CopyValue) {
                return varName(body, operandAt(operands, 0)) + " = copy("
                       + varName(body, operandAt(operands, 1)) + ")";
            }
            if (kind == IlOpKind::Store) {
                return "*" + varName(body, operandAt(operands, 0)) + " = "
                       + varName(body, operandAt(operands, 1));
            }
            if (kind == IlOpKind::GetField) {
                return varName(body, operandAt(operands, 0)) + " = "
                       + varName(body, operandAt(operands, 1)) + "."
                       + poolText(body, operandAt(operands, 2));
            }
            if (kind == IlOpKind::SetField) {
                return varName(body, operandAt(operands, 1)) + "."
                       + poolText(body, operandAt(operands, 0)) + " = "
                       + varName(body, operandAt(operands, 2));
            }
            if (kind == IlOpKind::GetIndex) {
                return varName(body, operandAt(operands, 0)) + " = "
                       + varName(body, operandAt(operands, 1)) + "["
                       + varName(body, operandAt(operands, 2)) + "]";
            }
            if (kind == IlOpKind::SetIndex) {
                return varName(body, operandAt(operands, 1)) + "["
                       + varName(body, operandAt(operands, 2)) + "] = "
                       + varName(body, operandAt(operands, 3));
            }
            if (kind == IlOpKind::FieldAddr) {
                return varName(body, operandAt(operands, 0)) + " = &"
                       + varName(body, operandAt(operands, 1)) + "."
                       + poolText(body, operandAt(operands, 2));
            }
            if (kind == IlOpKind::IndexAddr) {
                return varName(body, operandAt(operands, 0)) + " = &"
                       + varName(body, operandAt(operands, 1)) + "["
                       + varName(body, operandAt(operands, 2)) + "]";
            }
            if (kind == IlOpKind::GetStatic) {
                return varName(body, operandAt(operands, 0)) + " = "
                       + poolText(body, operandAt(operands, 1));
            }
            if (kind == IlOpKind::SetStatic) {
                return poolText(body, operandAt(operands, 0)) + " = "
                       + varName(body, operandAt(operands, 1));
            }
            if (kind == IlOpKind::Call || kind == IlOpKind::CallVoid) {
                const bool hasDst = kind == IlOpKind::Call;
                const Str dst = hasDst
                                    ? varName(body, operandAt(operands, 0)) + " = "
                                    : Str();
                const int methodOp = operandAt(operands, hasDst ? 1 : 0);
                const int first = hasDst ? 2 : 1;
                const IlMethod *method = methodAt(body, methodOp);
                if (method == nullptr) {
                    return dst + "?m" + intText(methodOp) + "(" + argList(body, operands, first)
                           + ")";
                }
                if (method->kind == IlMethodKind::Method && first < (int) operands.size()) {
                    return dst + varName(body, operands[first]) + "." + method->name + "("
                           + argList(body, operands, first + 1) + ")";
                }
                return dst + method->name + "(" + argList(body, operands, first) + ")";
            }
            if (kind == IlOpKind::CallIndirect || kind == IlOpKind::CallIndirectVoid) {
                const bool hasDst = kind == IlOpKind::CallIndirect;
                const Str dst = hasDst
                                    ? varName(body, operandAt(operands, 0)) + " = "
                                    : Str();
                const int calleeAt = hasDst ? 1 : 0;
                return dst + varName(body, operandAt(operands, calleeAt)) + "("
                       + argList(body, operands, calleeAt + 1) + ")";
            }
            if (kind == IlOpKind::CallCtor) {
                return varName(body, operandAt(operands, 0)) + " = new "
                       + typeName(body, operandAt(operands, 1)) + "("
                       + argList(body, operands, 2) + ")";
            }
            if (kind == IlOpKind::Return) return "return " + varName(body, operandAt(operands, 0));
            if (kind == IlOpKind::ReturnVoid) return "return";
            if (kind == IlOpKind::Lambda) return varName(body, operandAt(operands, 0)) + " = <lambda>";
            if (kind == IlOpKind::Unsupported) {
                return "<unsupported: " + poolText(body, operandAt(operands, 1)) + ">";
            }
            return Str();
        }

        void appendTable(Str &out, const char *label, const List<Str> &entries) {
            if (entries.empty()) return;
            out += label;
            for (int i = 0; i < (int) entries.size(); i++) {
                if (i > 0) out += "   ";
                out += entries[i];
            }
            out += "\n";
        }

        bool &ilFlag() {
            static bool value = false;
            return value;
        }
    }

    const List<IlSignature> &ilSignatures() {
        return signatureTable();
    }

    const IlSignature *ilSignature(IlOpKind kind) {
        // The table is in `IlOpKind` order, so the row *is* the opcode: no search,
        // which matters because the backend asks for a signature per operand.
        const List<IlSignature> &table = signatureTable();
        const int index = (int) kind;
        if (index < 0 || index >= (int) table.size()) return nullptr;
        return &table[index];
    }

    const char *ilOpKindText(IlOpKind kind) {
        const int index = (int) kind;
        if (index < 0 || index >= (int) (sizeof(opKindTextTable) / sizeof(opKindTextTable[0]))) {
            return "?";
        }
        return opKindTextTable[index];
    }

    IlOperandKind ilOperandKind(const IlOp &op, int index) {
        const IlSignature *signature = ilSignature(op.kind);
        if (signature == nullptr) return IlOperandKind::None;
        return operandKindAt(operandTokens(*signature), index);
    }

    ast::TypePtr ilVarType(const IlBody &body, int slot) {
        if (slot < 0 || slot >= (int) body.vars.size()) return nullptr;
        return ilTypeNode(body, body.vars[slot].typeIndex);
    }

    ast::TypePtr ilTypeNode(const IlBody &body, int index) {
        if (index < 0 || index >= (int) body.typeNodes.size()) return nullptr;
        return body.typeNodes[index];
    }

    bool ilWritesDestination(IlOpKind kind) {
        // Every op whose first operand is written rather than read. Written out
        // rather than derived, because deriving it means reading the operands *and*
        // knowing which of them produce a value, which is the thing being stated.
        switch (kind) {
            case IlOpKind::SetVar:
            case IlOpKind::SetVar_Null:
            case IlOpKind::BinaryOp:
            case IlOpKind::UnaryOp:
            case IlOpKind::Cast:
            case IlOpKind::Box:
            case IlOpKind::Deref:
            case IlOpKind::CopyValue:
            case IlOpKind::GetField:
            case IlOpKind::GetIndex:
            case IlOpKind::FieldAddr:
            case IlOpKind::IndexAddr:
            case IlOpKind::GetStatic:
            case IlOpKind::Call:
            case IlOpKind::CallIndirect:
            case IlOpKind::CallCtor:
            case IlOpKind::Lambda:
            case IlOpKind::Unsupported:
                return true;
            default:
                return false;
        }
    }

    Str ilTypeText(const ast::TypeExpr &type) {
        switch (type.kind) {
            case ast::TypeKind::Named:
                return type.name;
            case ast::TypeKind::IntLit:
                return type.text;
            case ast::TypeKind::Generic: {
                List<Str> args;
                for (const ast::TypePtr &arg: type.typeArgs) {
                    args.push_back(arg ? ilTypeText(*arg) : Str("?"));
                }
                return type.name + "<" + joinList(args, ", ") + ">";
            }
            case ast::TypeKind::Reference:
                return Str("&") + (type.inner ? ilTypeText(*type.inner) : Str("?"));
            case ast::TypeKind::Pointer:
                return Str("*") + (type.inner ? ilTypeText(*type.inner) : Str("?"));
            case ast::TypeKind::Function: {
                List<Str> params;
                for (const ast::TypePtr &param: type.paramTypes) {
                    params.push_back(param ? ilTypeText(*param) : Str("?"));
                }
                Str ret = type.returnType ? ilTypeText(*type.returnType) : Str("Unit");
                return "(" + joinList(params, ", ") + ") -> " + ret;
            }
        }
        return "?";
    }

    IlUnit extractIlUnit(const IlFunction &fn, const List<StmtPtr> &body, const Str &file) {
        IlUnit unit;
        // The closure symbols are numbered per *unit* (not per body), so a lambda inside
        // a lambda still gets a name of its own.
        int closureCounter = 1;
        Extractor extractor(fn, file, unit, closureCounter);
        unit.body = extractor.run(body);
        return unit;
    }

    Str printIlUnit(const IlUnit &unit) {
        Str out = printIlBody(unit.body);
        for (const IlClosure &closure: unit.closures) {
            out += "\n## closure " + closure.symbol + "  captures ("
                   + joinList(closure.captures, ", ") + ")  " + closure.signature + "\n";
            if (closure.bodyIndex >= 0 && closure.bodyIndex < (int) unit.lambdas.size()) {
                out += printIlBody(unit.lambdas[closure.bodyIndex]);
            }
        }
        return out;
    }

    Str printIlBody(const IlBody &body) {
        Str out;
        out += "# " + body.file + ":" + intText(body.line) + "  " + body.symbol + " "
               + body.signature + "\n";

        List<Str> types;
        for (int i = 0; i < (int) body.types.size(); i++) {
            types.push_back(intText(i) + " " + body.types[i]);
        }
        appendTable(out, "types:   ", types);

        List<Str> vars;
        for (int i = 0; i < (int) body.vars.size(); i++) {
            const IlVar &slot = body.vars[i];
            vars.push_back(intText(i) + " " + slot.name + ":" + intText(slot.typeIndex) + ":"
                           + varKindText(slot.kind));
        }
        appendTable(out, "vars:    ", vars);

        List<Str> pool;
        for (int i = 0; i < (int) body.pool.size(); i++) {
            pool.push_back(intText(i) + " " + poolAsText(body.pool[i]));
        }
        appendTable(out, "pool:    ", pool);

        List<Str> methods;
        for (int i = 0; i < (int) body.methods.size(); i++) {
            const IlMethod &method = body.methods[i];
            Str text = intText(i) + " " + method.name + ":" + methodKindText(method.kind) + ":"
                       + intText(method.argCount);
            if (method.staticBase >= 0) text += ":static=" + typeName(body, method.staticBase);
            if (method.returnType >= 0) text += ":ret=" + typeName(body, method.returnType);
            methods.push_back(text);
        }
        appendTable(out, "methods: ", methods);

        List<Str> labels;
        for (int i = 0; i < (int) body.labels.size(); i++) {
            labels.push_back(intText(i) + " " + body.labels[i]);
        }
        appendTable(out, "labels:  ", labels);

        for (int i = 0; i < (int) body.ops.size(); i++) {
            const IlOp &op = body.ops[i];
            const IlSignature *signature = ilSignature(op.kind);
            List<Str> tokens;
            if (signature != nullptr) tokens = operandTokens(*signature);

            List<Str> rendered;
            for (int j = 0; j < (int) op.operands.size(); j++) {
                rendered.push_back(renderOperand(body, operandKindAt(tokens, j), op.operands[j]));
            }

            Str text = padRight(intText(i), 4) + ",  " + padRight(ilOpKindText(op.kind), 16)
                       + joinList(rendered, ", ");
            Str comment = opComment(body, op);
            if (!comment.empty()) {
                text = padRight(text, 74) + "# " + comment;
            }
            const int sourceLine = i < (int) body.lines.size() ? body.lines[i] : 0;
            if (sourceLine > 0) text += "  (line " + intText(sourceLine) + ")";
            out += text + "\n";
        }
        return out;
    }

    bool showIl() {
        return ilFlag();
    }

    void setShowIl(bool value) {
        ilFlag() = value;
    }
}
