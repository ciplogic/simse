#pragma once

#include "../ast/Ast.h"

// `yield`, lowered to a state machine (impl_specs/yield.md).
//
// There is no semantic understanding of `yield` anywhere: it is deconstructed, and
// the deconstruction happens on the *linear* body - the control flow is already
// labels and gotos, and the yielded value is already one operand. What is left is
// bookkeeping:
//
//   fun evens(n: Int): ..Int {          struct evens_yieldable {
//       var i = 0                         Int n;      // the parameters ...
//       while (i < n) {                   Int i;      // ... and the body's locals
//           yield i                       Int branch; // where the machine is
//       }                               }
//   }                                     Opt<Int> next() {
//                                             if (this.branch == 1) goto L1;
//                                             this.i = 0;      // branch 0: the start
//                                             ...
//                                             this.branch = 1;
//                                             return Opt<Int>.some(i);
//                                         L1:;
//                                             ...
//                                             this.branch = -1;
//                                             return Opt<Int>.none();
//                                         }
//
// The rules, in order:
//
//  - the **fields** are the parameters and every local of the body, so their values
//    survive between calls, plus `branch` (`0` is the start, `-1` is finished, `n` is
//    the resumption point after the n-th yield);
//  - the **dispatcher** is a chain of conditional jumps - `if (branch == n) goto Ln;`
//    - so branch 0 falls through and branch -1 ends the machine. There is no switch:
//    it would only be lowered to these jumps anyway;
//  - every **`yield e`** writes the branch, returns the optional, and lands on its own
//    label - the resumption point;
//  - every **`return`** and the **end of the body** finish the machine
//    (`branch = -1; return none;`), which is `yield break`.
//
// `next()` hands out `Opt<T>`; `advance(*T)` is the same machine without the copy
// (it writes through the caller's pointer and answers whether there was a value).
namespace linear {
    // Whether a body yields at all - i.e. whether it is a state machine.
    bool hasYield(const List<ast::StmtPtr>& body);

    // One method of the machine: its name, its parameters, and its body.
    struct YieldMethod {
        Str name;                        // `next` / `advance`
        List<ast::Param> params;         // the method's own parameters
        List<ast::StmtPtr> body;         // the state machine, in linear form
        int yieldCount = 0;
    };

    struct Yielded {
        List<ast::Field> fields;         // `branch`, then the parameters and the locals
        List<YieldMethod> methods;       // `next`, and `advance` when asked for
        // Why the body could not be turned into a machine (the caller reports it): an
        // untyped local, since a field needs a type, or a receiver that has no field.
        Str error;
    };

    // The machine's field for the receiver of an extension function (`_sm_self`). The
    // receiver is an ordinary parameter (`specs/functions.md`), so it lives in the
    // instance like every other value that crosses a yield; the emitter initialises the
    // field from its own `self` parameter.
    Str yieldReceiverField();

    // The state machine of one yielding body. `linearBody` is the lowered body (see
    // `Lowered`), `decl` the function it came from, and `elementType` the `..T` the
    // return type names. `str` is the name of the *value* parameter of `advance`;
    // when it is empty no `advance` method is generated.
    Yielded lowerYield(const ast::Decl& decl, const ast::TypePtr& elementType,
                       const List<ast::StmtPtr>& linearBody, const Str& valueTypeText);
}
