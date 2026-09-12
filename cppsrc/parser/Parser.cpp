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

        class Parser {
        public:
            Parser(List<Token> &tokens, const Str &fileName) {
                file = fileName;
                for (Token &token: tokens) {
                    if (token.kind == TokenKind::Space || token.kind == TokenKind::Comment) {
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
                if (checkText("data")) return parseDataClass();
                if (checkText("enum")) return parseEnum();
                if (checkText("typealias")) return parseTypeAlias();
                if (checkText("native")) return parseFunction(true);
                if (checkText("fun")) return parseFunction(false);
                fail("expected declaration");
                return nullptr;
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
                    skipSeparators();
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
                        skipSeparators();
                    }
                    if (!expectText(")")) return decl;
                }

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
                if (!expectIdentifier(decl->name)) return decl;
                if (checkText("<")) {
                    if (!parseTypeParams(decl->typeParams)) return decl;
                }
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
                return checkKind(TokenKind::Identifier)
                       || checkText("(") || checkText("&") || checkText("*");
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

            List<ast::StmtPtr> parseBlock() {
                List<ast::StmtPtr> body;
                if (!expectText("{")) return body;
                skipSeparators();
                while (!checkText("}") && !atEnd()) {
                    ast::StmtPtr stmt = parseStmt();
                    if (!stmt) return body;
                    body.push_back(stmt);
                    skipSeparators();
                }
                expectText("}");
                return body;
            }

            ast::StmtPtr parseStmt() {
                if (checkText("val") || checkText("var")) return parseVarDecl();
                if (checkText("if")) return parseIf();
                if (checkText("while")) return parseWhile();
                if (checkText("switch")) return parseSwitch();
                if (checkText("return")) return parseReturn();
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

                common::SourcePos pos = peek().pos;
                ast::ExprPtr expr = parseExpr(0);
                if (!expr) return nullptr;
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

            ast::StmtPtr parseSwitch() {
                auto stmt = std::make_shared<ast::Stmt>();
                stmt->kind = ast::StmtKind::Switch;
                stmt->pos = peek().pos;
                advance(); // switch
                if (!expectText("(")) return nullptr;
                stmt->cond = parseExpr(0);
                if (!stmt->cond) return nullptr;
                if (!expectText(")")) return nullptr;
                if (!expectText("{")) return nullptr;
                skipSeparators();
                while (!checkText("}") && !atEnd()) {
                    ast::SwitchCase switchCase;
                    switchCase.pos = peek().pos;
                    if (matchText("case")) {
                        skipNewlines();
                        switchCase.label = parseExpr(0);
                        if (!switchCase.label) return nullptr;
                        if (!expectText(":")) return nullptr;
                    } else if (matchText("default")) {
                        switchCase.isDefault = true;
                        if (!expectText(":")) return nullptr;
                    } else {
                        fail("expected 'case' or 'default'");
                        return nullptr;
                    }
                    skipSeparators();
                    // The arm body runs until the next label or the closing brace.
                    while (!checkText("case") && !checkText("default")
                           && !checkText("}") && !atEnd()) {
                        ast::StmtPtr child = parseStmt();
                        if (!child) return nullptr;
                        switchCase.body.push_back(child);
                        skipSeparators();
                    }
                    stmt->cases.push_back(switchCase);
                }
                if (!expectText("}")) return nullptr;
                return stmt;
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
        List<lex::TokenMatcher> rules = lex::getTokenRules();
        lex::Scanner scanner(&rules);
        Res<List<lex::Token>> tokens = lex::readFileAndSkipSpacesTokens(&scanner, fileName);
        if (!tokens.isOk()) {
            return resError<ast::Module>(tokens.Error);
        }
        List<lex::Token> tokenList = tokens.Value;
        return parseModule(tokenList, fileName);
    }

}
