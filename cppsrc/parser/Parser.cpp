#include "Parser.h"

#include <algorithm>
#include <filesystem>
#include <string>
#include <system_error>

using lex::Token;
using lex::TokenKind;

namespace parser {
    namespace {
        // Binary operator binding powers for the Pratt parser. Left-associative
        // (the recursive call uses bp + 1).
        int binaryBindingPower(const Str &op) {
            if (op == "||") return 10;
            if (op == "&&") return 20;
            if (op == "==" || op == "!=") return 30;
            if (op == "<" || op == ">" || op == "<=" || op == ">=") return 40;
            if (op == "+" || op == "-") return 50;
            if (op == "*" || op == "/" || op == "%") return 60;
            return -1;
        }

        bool isAssignOp(const Str &op) {
            return op == "=" || op == "+=" || op == "-="
                   || op == "*=" || op == "/=" || op == "%=";
        }

        // The step operators: `i++` and `i--`, which are statements rather than values.
        bool isStepOp(const Str &op) {
            return op == "++" || op == "--";
        }

        // The compound assignment a step is: `i++` is `i += 1`, `i--` is `i -= 1`.
        Str stepAssignOp(const Str &op) {
            if (op == "++") return Str("+=");
            return Str("-=");
        }

        // ---- node builders for the `for` desugaring ----------------------------
        // A `for` is spelled out as the `while` it means (see `parseFor`), so these
        // build the pieces that template needs. They carry the `for` token's position
        // so every statement and expression the template generates points a diagnostic
        // at the source line the user wrote. `linear/Yield.cpp` has the same builders
        // for its own rewrite; the Simse ring builds the same shape as XmlNodes.

        ast::ExprPtr nameExpr(const Str &name, const common::SourcePos &pos) {
            auto node = std::make_shared<ast::Expr>();
            node->kind = ast::ExprKind::Name;
            node->pos = pos;
            node->text = name;
            return node;
        }

        ast::ExprPtr intLiteral(int value, const common::SourcePos &pos) {
            auto node = std::make_shared<ast::Expr>();
            node->kind = ast::ExprKind::IntLit;
            node->pos = pos;
            node->text = std::to_string(value);
            return node;
        }

        ast::ExprPtr boolLiteral(bool value, const common::SourcePos &pos) {
            auto node = std::make_shared<ast::Expr>();
            node->kind = ast::ExprKind::BoolLit;
            node->pos = pos;
            node->boolValue = value;
            node->text = value ? "true" : "false";
            return node;
        }

        ast::ExprPtr unaryExpr(const Str &op, const ast::ExprPtr &operand,
                               const common::SourcePos &pos) {
            auto node = std::make_shared<ast::Expr>();
            node->kind = ast::ExprKind::Unary;
            node->pos = pos;
            node->text = op;
            node->lhs = operand;
            return node;
        }

        ast::ExprPtr binaryExpr(const Str &op, const ast::ExprPtr &lhs,
                                const ast::ExprPtr &rhs, const common::SourcePos &pos) {
            auto node = std::make_shared<ast::Expr>();
            node->kind = ast::ExprKind::Binary;
            node->pos = pos;
            node->text = op;
            node->lhs = lhs;
            node->rhs = rhs;
            return node;
        }

        // `<target>.<method>()`: the machine's `next`/`value`/`hasValue`.
        ast::ExprPtr methodCall(const Str &target, const Str &method,
                                const common::SourcePos &pos) {
            auto member = std::make_shared<ast::Expr>();
            member->kind = ast::ExprKind::Member;
            member->pos = pos;
            member->text = method;
            member->lhs = nameExpr(target, pos);

            auto call = std::make_shared<ast::Expr>();
            call->kind = ast::ExprKind::Call;
            call->pos = pos;
            call->lhs = member;
            return call;
        }

        ast::TypePtr namedType(const Str &name, const common::SourcePos &pos) {
            auto node = std::make_shared<ast::TypeExpr>();
            node->kind = ast::TypeKind::Named;
            node->pos = pos;
            node->name = name;
            return node;
        }

        ast::StmtPtr varDeclStmt(const Str &name, bool isVar, const ast::TypePtr &type,
                                 const ast::ExprPtr &init, const common::SourcePos &pos) {
            auto stmt = std::make_shared<ast::Stmt>();
            stmt->kind = ast::StmtKind::VarDecl;
            stmt->pos = pos;
            stmt->isVar = isVar;
            stmt->name = name;
            stmt->type = type;
            stmt->init = init;
            return stmt;
        }

        ast::StmtPtr assignStmt(const Str &name, const ast::ExprPtr &value,
                               const common::SourcePos &pos) {
            auto stmt = std::make_shared<ast::Stmt>();
            stmt->kind = ast::StmtKind::Assign;
            stmt->pos = pos;
            stmt->target = nameExpr(name, pos);
            stmt->op = "=";
            stmt->value = value;
            return stmt;
        }

        class Parser {
        public:
            Parser(List<Token> &tokens, const Str &fileName) {
                file = fileName;
                // Space and comments never reach the grammar, and neither does a newline
                // inside `(...)` or `[...]`: a condition or an argument list may be
                // wrapped, and a formatter is free to do it (Kotlin's rule, and the reason
                // a Kotlin-kind formatter can be pointed at these files,
                // specs/declarations.md).
                int bracketed = 0;
                for (Token &token: tokens) {
                    if (token.kind == TokenKind::Operator) {
                        if (token.text == "(" || token.text == "[") {
                            bracketed++;
                        } else if (token.text == ")" || token.text == "]") {
                            if (bracketed > 0) bracketed--;
                        }
                    }
                    if (token.kind == TokenKind::Space || token.kind == TokenKind::Comment) {
                        continue;
                    }
                    if (token.kind == TokenKind::EndOfLine && bracketed > 0) {
                        continue;
                    }
                    toks.push_back(token);
                }
                Token eof;
                eof.kind = TokenKind::Eof;
                eof.text = "";
                eof.pos = toks.empty() ? common::SourcePos{0, 1, 1} : toks.back().pos;
                toks.push_back(eof);
            }

            bool failed = false;
            Str error;

            ast::Module parse() {
                ast::Module module;
                module.pos = common::SourcePos{0, 1, 1};
                skipSeparators();
                // A mandatory, single, file-level `package a.b.c` as the first
                // declaration, before imports and other declarations. A second
                // `package` is rejected because parseDecl does not accept it.
                if (!checkText("package")) {
                    fail("expected 'package' declaration");
                    return module;
                }
                module.packagePos = peek().pos;
                advance(); // package
                Str first;
                if (!expectIdentifier(first)) return module;
                module.package.push_back(first);
                while (matchText(".")) {
                    Str next;
                    if (!expectIdentifier(next)) return module;
                    module.package.push_back(next);
                }
                skipSeparators();
                while (!atEnd() && !failed) {
                    if (checkText("import")) {
                        module.imports.push_back(parseImport());
                    } else {
                        ast::DeclPtr decl = parseDecl();
                        if (decl) {
                            module.declarations.push_back(decl);
                        }
                    }
                    skipSeparators();
                }
                return module;
            }

        private:
            List<Token> toks;
            int cursor = 0;
            Str file;
            // The `for` and `when` desugarings' names (`_sm_for<n>`, `_sm_step<n>`,
            // `_sm_index<n>`, `_sm_when<n>`) come from this counter: per file, so nested
            // constructs never collide and two runs of the same source produce the same
            // names.
            int nextTemplateId = 1;

            // ---- token cursor helpers -------------------------------------

            const Token &peek(int offset = 0) const {
                int index = cursor + offset;
                if (index < 0) index = 0;
                if (index >= (int) toks.size()) index = (int) toks.size() - 1;
                return toks[index];
            }

            bool atEnd() const {
                return peek().kind == TokenKind::Eof;
            }

            const Token &advance() {
                const Token &token = toks[cursor];
                if (cursor < (int) toks.size() - 1) {
                    cursor++;
                }
                return token;
            }

            bool checkText(const char *text) const {
                return peek().text == text;
            }

            bool checkKind(TokenKind kind) const {
                return peek().kind == kind;
            }

            bool matchText(const char *text) {
                if (checkText(text)) {
                    advance();
                    return true;
                }
                return false;
            }

            void setError(const common::SourcePos &pos, const Str &message) {
                if (failed) return;
                failed = true;
                error = file + ":" + std::to_string(pos.line) + ":" + std::to_string(pos.column)
                        + ": " + message;
            }

            bool fail(const Str &message) {
                setError(peek().pos, message);
                return false;
            }

            bool expectText(const char *text) {
                if (matchText(text)) {
                    return true;
                }
                return fail(Str("expected '") + text + "'");
            }

            bool expectIdentifier(Str &out) {
                if (checkKind(TokenKind::Identifier)) {
                    out = advance().text;
                    return true;
                }
                return fail("expected name");
            }

            void skipSeparators() {
                while (checkKind(TokenKind::EndOfLine) || checkText(";")) {
                    advance();
                }
            }

            // The separator between a data class's fields: Kotlin's `,`
            // (specs/declarations.md, "data class"). A `;` is accepted there too - it is
            // what the sources used before the spelling was aligned with Kotlin, and it
            // costs nothing to keep. Only the *field* list takes a comma: between
            // statements and methods it is not a separator, because there it would read
            // as an expression part.
            void skipFieldSeparators() {
                while (checkKind(TokenKind::EndOfLine) || checkText(";") || checkText(",")) {
                    advance();
                }
            }

            void skipNewlines() {
                while (checkKind(TokenKind::EndOfLine)) {
                    advance();
                }
            }

            bool atStmtEnd() const {
                return checkKind(TokenKind::EndOfLine) || atEnd()
                       || checkText(";") || checkText("}");
            }

            // ---- declarations ---------------------------------------------

            ast::Import parseImport() {
                ast::Import import;
                import.pos = peek().pos;
                advance(); // import
                Str component;
                if (!expectIdentifier(component)) return import;
                import.path.push_back(component);
                while (matchText(".")) {
                    Str next;
                    if (!expectIdentifier(next)) return import;
                    import.path.push_back(next);
                }
                return import;
            }

            ast::DeclPtr parseDecl() {
                if (checkText("var") || checkText("val")) return parseStaticVar();
                if (checkText("data")) return parseDataClass();
                if (checkText("enum")) return parseEnum();
                if (checkText("typealias")) return parseTypeAlias();
                if (checkText("native")) return parseFunction(true);
                if (checkText("fun")) return parseFunction(false);
                fail("expected declaration");
                return nullptr;
            }

            // A file-level `var`/`val`: static storage (specs/statics.md). The type is
            // required (no inference) and the initializer is optional. The children are
            // the same shape as a `Stmt.VarDecl`, so the emitters have one variable form;
            // the role is `Var` because this is a declaration, not a statement.
            ast::DeclPtr parseStaticVar() {
                auto decl = std::make_shared<ast::Decl>();
                decl->kind = ast::DeclKind::Var;
                decl->pos = peek().pos;
                decl->isVar = matchText("var");
                if (!decl->isVar) {
                    if (!expectText("val")) return decl;
                }
                if (!expectIdentifier(decl->name)) return decl;
                if (!expectText(":")) return decl;
                decl->type = parseType();
                if (!decl->type) return decl;
                if (matchText("=")) {
                    skipNewlines();
                    decl->init = parseExpr(0);
                    if (!decl->init) return decl;
                }
                return decl;
            }

            ast::DeclPtr parseDataClass() {
                auto decl = std::make_shared<ast::Decl>();
                decl->kind = ast::DeclKind::DataClass;
                decl->pos = peek().pos;
                advance(); // data
                if (!expectText("class")) return decl;
                if (!expectIdentifier(decl->name)) return decl;
                if (checkText("<")) {
                    if (!parseTypeParams(decl->typeParams)) return decl;
                }

                if (matchText("(")) {
                    skipFieldSeparators();
                    while (!checkText(")") && !atEnd()) {
                        ast::Field field;
                        field.pos = peek().pos;
                        if (matchText("var")) {
                            field.isVar = true;
                        } else if (matchText("val")) {
                            field.isVar = false;
                        } else {
                            fail("expected 'val' or 'var'");
                            return decl;
                        }
                        if (!expectIdentifier(field.name)) return decl;
                        if (matchText(":")) {
                            field.type = parseType();
                            if (!field.type) return decl;
                        }
                        decl->fields.push_back(field);
                        skipFieldSeparators();
                    }
                    if (!expectText(")")) return decl;
                }

                // The body brace may stand on its own line (a formatter wraps the
                // parameter list and leaves `{` behind it): a newline there is not a
                // declaration boundary.
                skipNewlines();
                if (checkText("{")) {
                    advance();
                    skipSeparators();
                    while (!checkText("}") && !atEnd()) {
                        if (!checkText("fun")) {
                            fail("expected method declaration");
                            return decl;
                        }
                        ast::DeclPtr method = parseFunction(false);
                        if (!method) return decl;
                        decl->methods.push_back(method);
                        skipSeparators();
                    }
                    if (!expectText("}")) return decl;
                }
                return decl;
            }

            ast::DeclPtr parseEnum() {
                auto decl = std::make_shared<ast::Decl>();
                decl->kind = ast::DeclKind::Enum;
                decl->pos = peek().pos;
                advance(); // enum
                // `enum class`, spelled the way `data class` is: there is no bare `enum`.
                if (!expectText("class")) return decl;
                if (!expectIdentifier(decl->name)) return decl;
                if (checkText("<")) {
                    if (!parseTypeParams(decl->typeParams)) return decl;
                }
                skipNewlines();
                if (!expectText("{")) return decl;
                skipSeparators();
                while (!checkText("}") && !atEnd()) {
                    ast::EnumMember member;
                    member.pos = peek().pos;
                    if (!expectIdentifier(member.name)) return decl;
                    if (matchText("=")) {
                        if (!checkKind(TokenKind::Number)) {
                            fail("expected enum value");
                            return decl;
                        }
                        Str value = advance().text;
                        if (value.find('.') != Str::npos) {
                            fail("enum value must be an integer");
                            return decl;
                        }
                        member.hasValue = true;
                        member.value = std::stoi(simse_toStdString(value));
                    }
                    decl->members.push_back(member);
                    skipSeparators();
                    matchText(",");
                    skipSeparators();
                }
                if (!expectText("}")) return decl;
                return decl;
            }

            ast::DeclPtr parseTypeAlias() {
                auto decl = std::make_shared<ast::Decl>();
                decl->kind = ast::DeclKind::TypeAlias;
                decl->pos = peek().pos;
                advance(); // typealias
                if (!expectIdentifier(decl->name)) return decl;
                if (checkText("<")) {
                    if (!parseTypeParams(decl->typeParams)) return decl;
                }
                if (!expectText("=")) return decl;
                decl->targetType = parseType();
                if (!decl->targetType) return decl;
                return decl;
            }

            bool parseTypeParams(List<Str> &out) {
                if (!expectText("<")) return false;
                skipNewlines();
                while (!checkText(">") && !atEnd()) {
                    Str name;
                    if (!expectIdentifier(name)) return false;
                    out.push_back(name);
                    skipNewlines();
                    if (!matchText(",")) break;
                    skipNewlines();
                }
                return expectText(">");
            }

            ast::DeclPtr parseFunction(bool isNative) {
                auto decl = std::make_shared<ast::Decl>();
                decl->kind = ast::DeclKind::Function;
                decl->pos = peek().pos;
                decl->isNative = isNative;

                if (isNative) {
                    advance(); // native
                    if (matchText("(")) {
                        if (checkKind(TokenKind::String)) {
                            decl->nativeSymbol = advance().text;
                            decl->hasNativeSymbol = true;
                        }
                        if (!expectText(")")) return decl;
                    }
                    if (!expectText("fun")) return decl;
                } else {
                    if (!expectText("fun")) return decl;
                }

                // Extension receiver: a type followed by '.', e.g. `Str.foo` or
                // `(*List<Int>).foo`. Try it, and rewind if there is no dot.
                if (!isNative && looksLikeTypeStart()) {
                    int savedCursor = cursor;
                    bool savedFailed = failed;
                    Str savedError = error;
                    ast::TypePtr receiver = parseType();
                    if (receiver && checkText(".")) {
                        advance(); // .
                        Str name;
                        if (!expectIdentifier(name)) return decl;
                        decl->hasReceiver = true;
                        decl->receiverType = receiver;
                        decl->name = name;
                    } else {
                        cursor = savedCursor;
                        failed = savedFailed;
                        error = savedError;
                    }
                }
                if (!decl->hasReceiver) {
                    if (!expectIdentifier(decl->name)) return decl;
                }

                if (checkText("<")) {
                    if (!parseTypeParams(decl->functionTypeParams)) return decl;
                }

                if (!expectText("(")) return decl;
                skipNewlines();
                while (!checkText(")") && !atEnd()) {
                    ast::Param param;
                    param.pos = peek().pos;
                    if (!parseParamName(param.name)) return decl;
                    if (matchText(":")) {
                        param.type = parseType();
                        if (!param.type) return decl;
                    }
                    decl->params.push_back(param);
                    skipNewlines();
                    if (!matchText(",")) break;
                    skipNewlines();
                }
                if (!expectText(")")) return decl;

                if (matchText(":")) {
                    decl->returnType = parseType();
                    if (!decl->returnType) return decl;
                }

                if (checkText("{")) {
                    decl->body = parseBlock();
                    if (failed) return decl;
                    decl->hasBody = true;
                }
                return decl;
            }

            bool looksLikeTypeStart() const {
                // `..` starts a `..T` receiver: `fun ..T.smToYield<T>()` is the wrap that
                // makes a machine iterable like any other source (impl_specs/for.md).
                return checkKind(TokenKind::Identifier)
                       || checkText("(") || checkText("&") || checkText("*")
                       || checkText("..");
            }

            bool parseParamName(Str &out) {
                if (checkKind(TokenKind::Identifier) || checkText("this")) {
                    out = advance().text;
                    return true;
                }
                return fail("expected parameter name");
            }

            // ---- types -----------------------------------------------------

            ast::TypePtr parseType() {
                common::SourcePos pos = peek().pos;
                if (matchText("&")) {
                    auto type = std::make_shared<ast::TypeExpr>();
                    type->kind = ast::TypeKind::Reference;
                    type->pos = pos;
                    type->inner = parseType();
                    return type->inner ? type : nullptr;
                }
                if (matchText("*")) {
                    auto type = std::make_shared<ast::TypeExpr>();
                    type->kind = ast::TypeKind::Pointer;
                    type->pos = pos;
                    type->inner = parseType();
                    return type->inner ? type : nullptr;
                }
                // `..T`: the function's body yields `T`, so it is lowered to a state
                // machine whose element type is `T` (impl_specs/yield.md).
                if (matchText("..")) {
                    auto type = std::make_shared<ast::TypeExpr>();
                    type->kind = ast::TypeKind::Yield;
                    type->pos = pos;
                    type->inner = parseType();
                    return type->inner ? type : nullptr;
                }
                if (checkText("(")) {
                    advance();
                    List<ast::TypePtr> params;
                    skipNewlines();
                    if (!checkText(")")) {
                        ast::TypePtr first = parseType();
                        if (!first) return nullptr;
                        params.push_back(first);
                        skipNewlines();
                        while (matchText(",")) {
                            skipNewlines();
                            ast::TypePtr next = parseType();
                            if (!next) return nullptr;
                            params.push_back(next);
                            skipNewlines();
                        }
                    }
                    if (!expectText(")")) return nullptr;
                    if (matchText("->")) {
                        auto type = std::make_shared<ast::TypeExpr>();
                        type->kind = ast::TypeKind::Function;
                        type->pos = pos;
                        type->paramTypes = params;
                        type->returnType = parseType();
                        return type->returnType ? type : nullptr;
                    }
                    if (params.size() == 1) {
                        return params[0];
                    }
                    fail("expected '->' in function type");
                    return nullptr;
                }
                if (checkKind(TokenKind::Identifier)) {
                    auto type = std::make_shared<ast::TypeExpr>();
                    type->pos = pos;
                    type->name = advance().text;
                    if (checkText("<")) {
                        type->kind = ast::TypeKind::Generic;
                        if (!parseGenericArgs(type->typeArgs)) return nullptr;
                    } else {
                        type->kind = ast::TypeKind::Named;
                    }
                    return type;
                }
                fail("expected type");
                return nullptr;
            }

            bool parseGenericArgs(List<ast::TypePtr> &out) {
                if (!expectText("<")) return false;
                skipNewlines();
                while (!checkText(">") && !atEnd()) {
                    ast::TypePtr arg;
                    if (checkKind(TokenKind::Number)) {
                        arg = std::make_shared<ast::TypeExpr>();
                        arg->kind = ast::TypeKind::IntLit;
                        arg->pos = peek().pos;
                        arg->text = advance().text;
                    } else {
                        arg = parseType();
                        if (!arg) return false;
                    }
                    out.push_back(arg);
                    skipNewlines();
                    if (!matchText(",")) break;
                    skipNewlines();
                }
                return expectText(">");
            }

            // ---- statements ------------------------------------------------

            // A declaration's body brace may stand on its own line (`data class X(...)`
            // then `{`): a newline before a `{` where a block is the only thing that can
            // follow is not a statement boundary, so the parser skips it here. Kotlin's
            // rule, and the reason a Kotlin-kind formatter can be pointed at these files.
            List<ast::StmtPtr> parseBlock() {
                List<ast::StmtPtr> body;
                skipNewlines();
                if (!expectText("{")) return body;
                skipSeparators();
                while (!checkText("}") && !atEnd()) {
                    if (!parseStmtInto(body)) return body;
                    skipSeparators();
                }
                expectText("}");
                return body;
            }

            // One source statement, appended to `out`. A statement *may* expand to
            // more than one: `for` is a declaration plus the loop it runs (`parseFor`)
            // and `when` is a binding plus the `if`/`else` chain it means (`parseWhen`),
            // and in both cases the binding has to sit outside what reads it.
            bool parseStmtInto(List<ast::StmtPtr> &out) {
                if (checkText("for")) return parseFor(out);
                if (checkText("when")) return parseWhen(out);
                ast::StmtPtr stmt = parseStmt();
                if (!stmt) return false;
                out.push_back(stmt);
                return true;
            }

            ast::StmtPtr parseStmt() {
                if (checkText("val") || checkText("var")) return parseVarDecl();
                if (checkText("if")) return parseIf();
                if (checkText("while")) return parseWhile();
                if (checkText("return")) return parseReturn();
                if (checkText("yield")) return parseYield();
                if (checkText("break")) {
                    auto stmt = std::make_shared<ast::Stmt>();
                    stmt->kind = ast::StmtKind::Break;
                    stmt->pos = peek().pos;
                    advance();
                    return stmt;
                }
                if (checkText("continue")) {
                    auto stmt = std::make_shared<ast::Stmt>();
                    stmt->kind = ast::StmtKind::Continue;
                    stmt->pos = peek().pos;
                    advance();
                    return stmt;
                }
                if (isStepOp(peek().text)) {
                    // A step's value is the assignment's, so there is nothing for a
                    // *prefix* one to hand back: it has to stand on its own as a
                    // statement.
                    fail(Str("`") + peek().text + "` stands on its own as a statement (`i" +
                         peek().text + "`)");
                    return nullptr;
                }

                common::SourcePos pos = peek().pos;
                ast::ExprPtr expr = parseExpr(0);
                if (!expr) return nullptr;
                if (isStepOp(peek().text)) {
                    // `i++` / `i--`: the step forms, which are the compound assignment
                    // below with a `1` (specs/memory-model.md). A step reached anywhere
                    // else - inside an expression, or in the prefix position a statement
                    // starts with - is the diagnostic above, because the statement's value
                    // is what it would hand back.
                    auto stmt = std::make_shared<ast::Stmt>();
                    stmt->kind = ast::StmtKind::Assign;
                    stmt->pos = pos;
                    stmt->target = expr;
                    stmt->op = stepAssignOp(advance().text);
                    stmt->value = intLiteral(1, pos);
                    return stmt;
                }
                if (isAssignOp(peek().text)) {
                    auto stmt = std::make_shared<ast::Stmt>();
                    stmt->kind = ast::StmtKind::Assign;
                    stmt->pos = pos;
                    stmt->target = expr;
                    stmt->op = advance().text;
                    skipNewlines();
                    stmt->value = parseExpr(0);
                    return stmt->value ? stmt : nullptr;
                }
                auto stmt = std::make_shared<ast::Stmt>();
                stmt->kind = ast::StmtKind::ExprStmt;
                stmt->pos = pos;
                stmt->expr = expr;
                return stmt;
            }

            ast::StmtPtr parseVarDecl() {
                auto stmt = std::make_shared<ast::Stmt>();
                stmt->kind = ast::StmtKind::VarDecl;
                stmt->pos = peek().pos;
                stmt->isVar = matchText("var");
                if (!stmt->isVar) {
                    if (!expectText("val")) return nullptr;
                }
                if (!expectIdentifier(stmt->name)) return nullptr;
                if (matchText(":")) {
                    stmt->type = parseType();
                    if (!stmt->type) return nullptr;
                }
                if (matchText("=")) {
                    skipNewlines();
                    stmt->init = parseExpr(0);
                    if (!stmt->init) return nullptr;
                }
                return stmt;
            }

            ast::StmtPtr parseIf() {
                auto stmt = std::make_shared<ast::Stmt>();
                stmt->kind = ast::StmtKind::If;
                stmt->pos = peek().pos;
                advance(); // if
                if (!expectText("(")) return nullptr;
                stmt->cond = parseExpr(0);
                if (!stmt->cond) return nullptr;
                if (!expectText(")")) return nullptr;
                stmt->thenBody = parseBlock();
                if (failed) return nullptr;
                if (matchText("else")) {
                    stmt->hasElse = true;
                    if (checkText("if")) {
                        ast::StmtPtr nested = parseIf();
                        if (!nested) return nullptr;
                        stmt->elseBody.push_back(nested);
                    } else {
                        stmt->elseBody = parseBlock();
                        if (failed) return nullptr;
                    }
                }
                return stmt;
            }

            ast::StmtPtr parseWhile() {
                auto stmt = std::make_shared<ast::Stmt>();
                stmt->kind = ast::StmtKind::While;
                stmt->pos = peek().pos;
                advance(); // while
                if (!expectText("(")) return nullptr;
                stmt->cond = parseExpr(0);
                if (!stmt->cond) return nullptr;
                if (!expectText(")")) return nullptr;
                stmt->body = parseBlock();
                if (failed) return nullptr;
                return stmt;
            }

            // `for` (specs/functions.md). Two forms, and each of them in two
            // flavours - binding each element, or binding a *pointer* to it
            // (`for (*v in c)`, `for ((*v, i) in c)`) - all iterating a *state
            // machine* (`..T` from a `yield`). Every form is lowered right here to the
            // `while` it means, so sema, the linear pass and the emitters never see a
            // `for`, and `break`/`continue` inside one are the `while`'s own:
            //
            //   for (v in m) { body }
            //       var _sm_for1 = m            // the machine, made once
            //       while (true) {
            //           var _sm_step1 = _sm_for1.next()
            //           if (!_sm_step1.hasValue()) { break }
            //           val v = _sm_step1.value()
            //           body
            //       }
            //
            //   for ((v, i) in m) { body }      // the same, plus the index
            //       var _sm_index1: Int = -1     // -1 so the pre-increment counts from 0
            //       while (true) {
            //           _sm_index1 = _sm_index1 + 1
            //           ...                      // then as above, plus `val i = _sm_index1`
            //
            // `*v` differs only in the wrap: the machine's element is then `*T`, so `v`
            // is the element's *place* rather than a copy of it (the prelude's
            // `smToYieldPtr`).
            //
            // The advance and the exhaustion test are the *first* statements of the
            // body rather than the loop's condition, and the index is pre-incremented
            // there too, for the same reason: `continue` jumps to the condition, so a
            // `next()` in the condition would not advance the machine on a `continue`,
            // and an index incremented at the end of the body would miss an iteration.
            // The `-1` start is what makes the pre-increment hand out 0 first.
            //
            // Everything the loop's body declares is fresh per iteration, and the
            // index is a *copy* of the counter: the names the user wrote (`v`, `i`)
            // belong to the loop body, while the counter itself is the template's.
            // `<target>.smToYield()`: the wrap the `for` forms put around what they
            // iterate. The name is the language's convention (`impl_specs/for.md`), not
            // a user's to take: a `for` never spells it in a diagnostic.
            ast::ExprPtr smToYieldCall(const ast::ExprPtr &target, const common::SourcePos &pos,
                                       const Str &wrap) {
                auto member = std::make_shared<ast::Expr>();
                member->kind = ast::ExprKind::Member;
                member->pos = pos;
                member->text = wrap;
                member->lhs = target;

                auto call = std::make_shared<ast::Expr>();
                call->kind = ast::ExprKind::Call;
                call->pos = pos;
                call->lhs = member;
                return call;
            }

            bool parseFor(List<ast::StmtPtr> &out) {
                const common::SourcePos pos = peek().pos;
                advance(); // for
                if (!expectText("(")) return false;

                Str valueName;
                Str indexName;
                bool withIndex = false;
                bool valueIsPointer = false;
                if (matchText("(")) {
                    withIndex = true;
                    valueIsPointer = matchText("*");
                    if (!expectIdentifier(valueName)) return false;
                    if (!expectText(",")) return false;
                    if (!expectIdentifier(indexName)) return false;
                    if (!expectText(")")) return false;
                } else {
                    valueIsPointer = matchText("*");
                    if (!expectIdentifier(valueName)) return false;
                }
                if (!matchText("in")) return fail("expected 'in'");
                skipNewlines();
                ast::ExprPtr machine = parseExpr(0);
                if (!machine) return false;
                if (!expectText(")")) return false;
                List<ast::StmtPtr> body = parseBlock();
                if (failed) return false;

                const Str machineName = Str("_sm_for") + std::to_string(nextTemplateId);
                const Str stepName = Str("_sm_step") + std::to_string(nextTemplateId);
                const Str counterName = Str("_sm_index") + std::to_string(nextTemplateId);
                nextTemplateId++;

                // The iterated expression is wrapped in the invisible `smToYield()` call:
                // a `for` iterates whatever has one, so a container walks itself in order
                // and a machine passes through (impl_specs/for.md). It is a *member* call,
                // because that is what binds the function's type parameter from the
                // receiver - a plain `smToYield(x)` would leave the loop variable untyped.
                const Str wrap = valueIsPointer ? Str("smToYieldPtr") : Str("smToYield");
                out.push_back(varDeclStmt(machineName, true, nullptr,
                                          smToYieldCall(machine, pos, wrap), pos));
                if (withIndex) {
                    out.push_back(varDeclStmt(counterName, true, namedType("Int", pos),
                                              intLiteral(-1, pos), pos));
                }

                List<ast::StmtPtr> loop;
                if (withIndex) {
                    loop.push_back(assignStmt(counterName,
                                              binaryExpr("+", nameExpr(counterName, pos),
                                                         intLiteral(1, pos), pos), pos));
                }
                loop.push_back(varDeclStmt(stepName, true, nullptr,
                                           methodCall(machineName, "next", pos), pos));

                auto exhausted = std::make_shared<ast::Stmt>();
                exhausted->kind = ast::StmtKind::If;
                exhausted->pos = pos;
                exhausted->cond = unaryExpr("!", methodCall(stepName, "hasValue", pos), pos);
                auto leave = std::make_shared<ast::Stmt>();
                leave->kind = ast::StmtKind::Break;
                leave->pos = pos;
                exhausted->thenBody.push_back(leave);
                loop.push_back(exhausted);

                // `val`: the loop variable is a fresh, per-iteration binding (as in
                // Kotlin's `for`), so assigning to it cannot be mistaken for a way to
                // move the machine along.
                loop.push_back(varDeclStmt(valueName, false, nullptr,
                                           methodCall(stepName, "value", pos), pos));
                if (withIndex) {
                    loop.push_back(varDeclStmt(indexName, false, nullptr,
                                               nameExpr(counterName, pos), pos));
                }
                for (const ast::StmtPtr &stmt: body) {
                    loop.push_back(stmt);
                }

                auto loopStmt = std::make_shared<ast::Stmt>();
                loopStmt->kind = ast::StmtKind::While;
                loopStmt->pos = pos;
                loopStmt->cond = boolLiteral(true, pos);
                loopStmt->body = loop;
                out.push_back(loopStmt);
                return true;
            }

            // `subj == a || subj == b`: one condition for an arm, so an arm with several
            // labels emits its body once and any expression is a legal label.
            ast::ExprPtr whenCondition(const Str &subject, const List<ast::ExprPtr> &labels,
                                      const common::SourcePos &pos) {
                ast::ExprPtr cond;
                for (const ast::ExprPtr &label: labels) {
                    ast::ExprPtr equals = binaryExpr("==", nameExpr(subject, pos), label, pos);
                    cond = cond ? binaryExpr("||", cond, equals, pos) : equals;
                }
                return cond;
            }

            // `when` (specs/functions.md): the language's selection statement, in
            // Kotlin's spelling and with Kotlin's semantics, desugared right here to the
            // `if`/`else` chain it means - so nothing downstream knows what a `when` is:
            //
            //   when (kind) {
            //       Kind.A, Kind.B -> { body1 }
            //       Kind.C -> { body2 }
            //       else -> { body3 }
            //   }
            //     ->
            //   var _sm_when1 = kind
            //   if (_sm_when1 == Kind.A || _sm_when1 == Kind.B) { body1 }
            //   else if (_sm_when1 == Kind.C) { body2 }
            //   else { body3 }
            //
            // The subject is bound to a name of the template's own (as `for` binds the
            // machine): the source evaluates it once, so the chain must too. The arm
            // bodies are blocks, `else` is the last arm, and arms do not fall through -
            // which is what makes a `break`/`continue` inside an arm the enclosing
            // loop's, as it is in Kotlin.
            bool parseWhen(List<ast::StmtPtr> &out) {
                const common::SourcePos pos = peek().pos;
                advance(); // when
                if (!expectText("(")) return false;
                ast::ExprPtr subject = parseExpr(0);
                if (!subject) return false;
                if (!expectText(")")) return false;
                skipNewlines();
                if (!expectText("{")) return false;
                skipSeparators();

                // The template's own name for the subject, before the arms are parsed:
                // a nested `for`/`when` in an arm body takes the next id (both rings
                // number the same way).
                const Str subjectName = Str("_sm_when") + std::to_string(nextTemplateId);
                nextTemplateId++;

                struct Arm {
                    List<ast::ExprPtr> labels;
                    common::SourcePos pos{};
                    List<ast::StmtPtr> body;
                    bool isElse = false;
                };

                bool sawElse = false;
                List<Arm> arms;
                while (!checkText("}") && !atEnd() && !failed) {
                    Arm arm;
                    arm.pos = peek().pos;
                    if (matchText("else")) {
                        if (sawElse) return fail("'when' can have only one 'else' arm");
                        sawElse = true;
                        arm.isElse = true;
                    } else {
                        if (sawElse) return fail("'else' must be the last arm of a 'when'");
                        // `is`/`in`/a range are Kotlin's pattern labels; the language has
                        // `==` against a value and that is all `when` matches on today.
                        if (checkText("is") || checkText("in")) {
                            return fail("'when' matches a value or 'else', not a pattern");
                        }
                        ast::ExprPtr label = parseExpr(0);
                        if (!label) return false;
                        arm.labels.push_back(label);
                        while (matchText(",")) {
                            skipNewlines();
                            ast::ExprPtr next = parseExpr(0);
                            if (!next) return false;
                            arm.labels.push_back(next);
                        }
                    }
                    skipNewlines();
                    if (!expectText("->")) return false;
                    arm.body = parseBlock();
                    if (failed) return false;
                    arms.push_back(arm);
                    skipSeparators();
                }
                if (!expectText("}")) return false;

                // The chain is built from the last arm back to the first, because
                // `else if` is an `if` in the previous arm's else body. An `else` arm
                // contributes its statements as the chain's tail instead of a node.
                List<ast::StmtPtr> tail;
                int last = (int) arms.size() - 1;
                if (last >= 0 && arms[last].isElse) {
                    tail = arms[last].body;
                    last--;
                }
                ast::StmtPtr chain;
                for (int i = last; i >= 0; i--) {
                    auto node = std::make_shared<ast::Stmt>();
                    node->kind = ast::StmtKind::If;
                    node->pos = arms[i].pos;
                    node->cond = whenCondition(subjectName, arms[i].labels, arms[i].pos);
                    node->thenBody = arms[i].body;
                    if (chain) {
                        node->hasElse = true;
                        node->elseBody.push_back(chain);
                    } else if (!tail.empty()) {
                        node->hasElse = true;
                        node->elseBody = tail;
                    }
                    chain = node;
                }
                out.push_back(varDeclStmt(subjectName, true, nullptr, subject, pos));
                if (chain) {
                    out.push_back(chain);
                } else {
                    // `else` was the only arm: its statements are the whole construct.
                    for (const ast::StmtPtr &stmt: tail) out.push_back(stmt);
                }
                return true;
            }

            ast::StmtPtr parseReturn() {
                auto stmt = std::make_shared<ast::Stmt>();
                stmt->kind = ast::StmtKind::Return;
                stmt->pos = peek().pos;
                advance(); // return
                if (!atStmtEnd()) {
                    stmt->returnValue = parseExpr(0);
                    if (!stmt->returnValue) return nullptr;
                }
                return stmt;
            }

            // `yield e`: the value the state machine hands out. It is a statement like
            // `return`, and the state-machine pass replaces it (impl_specs/yield.md) -
            // nothing downstream has to know what a yield is.
            ast::StmtPtr parseYield() {
                auto stmt = std::make_shared<ast::Stmt>();
                stmt->kind = ast::StmtKind::Yield;
                stmt->pos = peek().pos;
                advance(); // yield
                stmt->expr = parseExpr(0);
                if (!stmt->expr) return nullptr;
                return stmt;
            }

            // ---- expressions (Pratt) --------------------------------------

            ast::ExprPtr parseExpr(int minBindingPower) {
                ast::ExprPtr left = parseUnary();
                if (!left) return nullptr;
                while (true) {
                    // A line ending ends the expression unless the next non-blank
                    // line continues it with a binary operator (the mirrors break
                    // long boolean chains across lines).
                    int newlines = 0;
                    while (peek(newlines).kind == TokenKind::EndOfLine) newlines++;
                    if (newlines > 0) {
                        int lookaheadBP = binaryBindingPower(peek(newlines).text);
                        if (lookaheadBP < 0 || lookaheadBP < minBindingPower) break;
                        for (int k = 0; k < newlines; k++) advance();
                    }
                    int bp = binaryBindingPower(peek().text);
                    if (bp < 0 || bp < minBindingPower) break;
                    Str op = advance().text;
                    skipNewlines();
                    ast::ExprPtr right = parseExpr(bp + 1);
                    if (!right) return nullptr;
                    auto node = std::make_shared<ast::Expr>();
                    node->kind = ast::ExprKind::Binary;
                    node->pos = left->pos;
                    node->text = op;
                    node->lhs = left;
                    node->rhs = right;
                    left = node;
                }
                return left;
            }

            ast::ExprPtr parseUnary() {
                common::SourcePos pos = peek().pos;
                if (checkText("!") || checkText("-")) {
                    Str op = advance().text;
                    ast::ExprPtr operand = parseUnary();
                    if (!operand) return nullptr;
                    auto node = std::make_shared<ast::Expr>();
                    node->kind = ast::ExprKind::Unary;
                    node->pos = pos;
                    node->text = op;
                    node->lhs = operand;
                    return node;
                }
                if (checkText("&")) {
                    advance();
                    ast::ExprPtr operand = parseUnary();
                    if (!operand) return nullptr;
                    auto node = std::make_shared<ast::Expr>();
                    node->kind = ast::ExprKind::Ref;
                    node->pos = pos;
                    node->lhs = operand;
                    return node;
                }
                if (checkText("*")) {
                    advance();
                    ast::ExprPtr operand = parseUnary();
                    if (!operand) return nullptr;
                    auto node = std::make_shared<ast::Expr>();
                    node->kind = ast::ExprKind::Deref;
                    node->pos = pos;
                    node->lhs = operand;
                    return node;
                }
                return parsePostfix();
            }

            ast::ExprPtr parsePostfix() {
                ast::ExprPtr expr = parsePrimary();
                if (!expr) return nullptr;
                while (true) {
                    if (matchText("(")) {
                        List<ast::ExprPtr> args;
                        skipNewlines();
                        if (!checkText(")")) {
                            ast::ExprPtr first = parseExpr(0);
                            if (!first) return nullptr;
                            args.push_back(first);
                            skipNewlines();
                            while (matchText(",")) {
                                skipNewlines();
                                ast::ExprPtr next = parseExpr(0);
                                if (!next) return nullptr;
                                args.push_back(next);
                                skipNewlines();
                            }
                        }
                        if (!expectText(")")) return nullptr;
                        auto call = std::make_shared<ast::Expr>();
                        call->kind = ast::ExprKind::Call;
                        call->pos = expr->pos;
                        call->lhs = expr;
                        call->args = args;
                        expr = call;
                    } else if (matchText("[")) {
                        skipNewlines();
                        ast::ExprPtr index = parseExpr(0);
                        if (!index) return nullptr;
                        if (!expectText("]")) return nullptr;
                        auto node = std::make_shared<ast::Expr>();
                        node->kind = ast::ExprKind::Index;
                        node->pos = expr->pos;
                        node->lhs = expr;
                        node->rhs = index;
                        expr = node;
                    } else if (matchText(".")) {
                        Str name;
                        if (!expectIdentifier(name)) return nullptr;
                        auto node = std::make_shared<ast::Expr>();
                        node->kind = ast::ExprKind::Member;
                        node->pos = expr->pos;
                        node->lhs = expr;
                        node->text = name;
                        expr = node;
                    } else {
                        break;
                    }
                }
                return expr;
            }

            ast::ExprPtr parsePrimary() {
                common::SourcePos pos = peek().pos;
                if (checkKind(TokenKind::Number)) {
                    Str text = advance().text;
                    auto node = std::make_shared<ast::Expr>();
                    node->pos = pos;
                    node->text = text;
                    node->kind = text.find('.') == Str::npos
                                     ? ast::ExprKind::IntLit
                                     : ast::ExprKind::FloatLit;
                    return node;
                }
                if (checkKind(TokenKind::String)) {
                    auto node = std::make_shared<ast::Expr>();
                    node->kind = ast::ExprKind::StrLit;
                    node->pos = pos;
                    node->text = advance().text;
                    return node;
                }
                if (checkKind(TokenKind::Character)) {
                    auto node = std::make_shared<ast::Expr>();
                    node->kind = ast::ExprKind::CharLit;
                    node->pos = pos;
                    node->text = advance().text;
                    return node;
                }
                if (checkText("true") || checkText("false")) {
                    auto node = std::make_shared<ast::Expr>();
                    node->kind = ast::ExprKind::BoolLit;
                    node->pos = pos;
                    node->boolValue = checkText("true");
                    node->text = advance().text;
                    return node;
                }
                if (checkText("null")) {
                    advance();
                    auto node = std::make_shared<ast::Expr>();
                    node->kind = ast::ExprKind::NullLit;
                    node->pos = pos;
                    return node;
                }
                if (checkText("this")) {
                    advance();
                    auto node = std::make_shared<ast::Expr>();
                    node->kind = ast::ExprKind::Name;
                    node->pos = pos;
                    node->text = "this";
                    return node;
                }
                if (checkKind(TokenKind::Identifier)) {
                    Str name = peek().text;
                    if (name == "copy" && peek(1).text == "(") {
                        advance(); // copy
                        advance(); // (
                        ast::ExprPtr inner = parseExpr(0);
                        if (!inner) return nullptr;
                        if (!expectText(")")) return nullptr;
                        auto node = std::make_shared<ast::Expr>();
                        node->kind = ast::ExprKind::Copy;
                        node->pos = pos;
                        node->lhs = inner;
                        return node;
                    }
                    // `Name<...>` is only a generic qualified form when the
                    // balanced type arguments are followed by '.' or '('. Try it,
                    // and rewind to a plain Name (letting '<' be a comparison)
                    // otherwise.
                    if (peek(1).text == "<") {
                        int savedCursor = cursor;
                        bool savedFailed = failed;
                        Str savedError = error;
                        advance(); // name
                        List<ast::TypePtr> typeArgs;
                        bool ok = parseGenericArgs(typeArgs);
                        if (ok && (checkText(".") || checkText("("))) {
                            auto node = std::make_shared<ast::Expr>();
                            node->kind = ast::ExprKind::GenericName;
                            node->pos = pos;
                            node->text = name;
                            node->typeArgs = typeArgs;
                            return node;
                        }
                        cursor = savedCursor;
                        failed = savedFailed;
                        error = savedError;
                    }
                    advance();
                    auto node = std::make_shared<ast::Expr>();
                    node->kind = ast::ExprKind::Name;
                    node->pos = pos;
                    node->text = name;
                    return node;
                }
                if (checkText("(")) {
                    int savedCursor = cursor;
                    bool savedFailed = failed;
                    Str savedError = error;
                    ast::ExprPtr lambda = tryParseLambda();
                    if (lambda) return lambda;
                    cursor = savedCursor;
                    failed = savedFailed;
                    error = savedError;
                    advance(); // (
                    skipNewlines();
                    ast::ExprPtr inner = parseExpr(0);
                    if (!inner) return nullptr;
                    if (!expectText(")")) return nullptr;
                    return inner;
                }
                fail("expected expression");
                return nullptr;
            }

            ast::ExprPtr tryParseLambda() {
                if (!matchText("(")) return nullptr;
                common::SourcePos pos = peek().pos;
                List<Str> names;
                List<ast::TypePtr> types;
                skipNewlines();
                if (!checkText(")")) {
                    while (true) {
                        if (!checkKind(TokenKind::Identifier)) return nullptr;
                        names.push_back(advance().text);
                        ast::TypePtr type;
                        if (matchText(":")) {
                            type = parseType();
                            if (!type) return nullptr;
                        }
                        types.push_back(type);
                        skipNewlines();
                        if (!matchText(",")) break;
                        skipNewlines();
                    }
                }
                if (!checkText(")")) return nullptr;
                if (peek(1).text != "->") return nullptr;
                advance(); // )
                advance(); // ->
                auto node = std::make_shared<ast::Expr>();
                node->kind = ast::ExprKind::Lambda;
                node->pos = pos;
                node->paramNames = names;
                node->paramTypes = types;
                if (checkText("{")) {
                    node->body = parseBlock();
                    if (failed) return nullptr;
                } else {
                    ast::ExprPtr value = parseExpr(0);
                    if (!value) return nullptr;
                    auto stmt = std::make_shared<ast::Stmt>();
                    stmt->kind = ast::StmtKind::ExprStmt;
                    stmt->pos = value->pos;
                    stmt->expr = value;
                    node->body.push_back(stmt);
                }
                return node;
            }
        };
    }

    Res<ast::Module> parseModule(List<lex::Token> &tokens, const Str &fileName) {
        Parser parser(tokens, fileName);
        ast::Module module = parser.parse();
        if (parser.failed) {
            return resError<ast::Module>(parser.error);
        }
        return ok(module);
    }

    Res<ast::Module> parseFile(const Str &fileName) {
        List<lex::TokenMatcher> *rules = lex::getTokenRules();
        lex::Scanner scanner(rules);
        Res<List<lex::Token>> tokens = lex::readFileAndSkipSpacesTokens(&scanner, fileName);
        if (!tokens.isOk()) {
            return resError<ast::Module>(tokens.Error);
        }
        List<lex::Token> tokenList = tokens.Value;
        return parseModule(tokenList, fileName);
    }

}
