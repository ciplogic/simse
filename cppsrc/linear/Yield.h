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
//       var i = 0                         Int branch;   // where the machine is
//       while (i < n) {                   Int current;  // what it last yielded
//           yield i                       Int n;        // the parameters ...
//       }                                 Int i;        // ... and the body's locals
//   }                                     Bool advance() {
//                                             if (this->branch == -1) goto LYend;
//                                             if (this->branch == 1) goto LY1;
//                                             this->i = 0;     // branch 0: the start
//                                             ...
//                                             this->current = this->i;
//                                             this->branch = 1;
//                                             return true;
//                                         LY1:;                // the resumption point
//                                             ...
//                                         LYend:;
//                                             this->branch = -1;
//                                             return false;
//                                         }
//                                         Int value() { return this->current; }
//                                     };
//
// The rules, in order:
//
//  - the **fields** are `branch`, `current`, the receiver of an extension function,
//    the parameters and every local of the body, so their values survive between calls
//    (`branch`: `0` is the start, `-1` is finished, `n` is the resumption point after
//    the n-th yield);
//  - the **dispatcher** is a chain of conditional jumps - `if (branch == n) goto Ln;`
//    - so branch 0 falls through and branch -1 ends the machine. There is no switch:
//    it would only be lowered to these jumps anyway;
//  - every **`yield e`** writes `current` and the branch, returns `true`, and lands on
//    its own label - the resumption point;
//  - every **`return`** and the **end of the body** finish the machine
//    (`branch = -1; return false;`), which is `yield break`.
//
// `advance()` steps the machine and answers whether there was a value, leaving what it
// yielded in `current`; `value()` reads that field out as the element type - so the
// protocol constructs nothing per element (impl_specs/for.md). A body name that would
// collide with one of those members is emitted under a mangled one (`yieldFieldName`).
namespace linear {
    // Whether a body yields at all - i.e. whether it is a state machine.
    bool hasYield(const List<ast::StmtPtr>& body);

    // One method of the machine: its name, its parameters, and its body.
    struct YieldMethod {
        Str name;                        // `advance` / `value`
        List<ast::Param> params;         // the method's own parameters
        List<ast::StmtPtr> body;         // the state machine, in linear form
        int yieldCount = 0;
    };

    struct Yielded {
        List<ast::Field> fields;         // `branch`, `current`, then the receiver, the parameters and the locals
        List<YieldMethod> methods;       // the stepping method, and `value`
        // Why the body could not be turned into a machine (the caller reports it): an
        // untyped local, since a field needs a type, or a receiver that has no field.
        Str error;
    };

    // The machine's field for the receiver of an extension function (`_sm_self`). The
    // receiver is an ordinary parameter (`specs/functions.md`), so it lives in the
    // instance like every other value that crosses a yield; the emitter initialises the
    // field from its own `self` parameter.
    Str yieldReceiverField();

    // The name a body's own field is emitted under. A machine's members (`branch`,
    // `current`, the receiver field, and the methods `advance`/`value`) share the class
    // with the body's fields, so a name that would collide is mangled - the rewrite and
    // the factory's `machine.x = x` both go through here, and nothing else in the emitted
    // C++ names a field.
    Str yieldFieldName(const Str& name);

    // The state machine of one yielding body. `linearBody` is the lowered body (see
    // `Lowered`), `decl` the function it came from, and `elementType` the `..T` the
    // return type names. `valueTypeText` is the name of the *stepping* method
    // (`advance`); when it is empty no methods are generated.
    Yielded lowerYield(const ast::Decl& decl, const ast::TypePtr& elementType,
                       const List<ast::StmtPtr>& linearBody, const Str& valueTypeText);
}
