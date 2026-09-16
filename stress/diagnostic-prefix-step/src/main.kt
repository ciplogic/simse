package prefix

// A step's value is the assignment's, so there is nothing for a *prefix* one to hand
// back: `++i` is rejected at the `++`, and the diagnostic names the form that works
// (specs/memory-model.md, "Compound assignment and the step operators").
//
// The postfix form is a statement (`i++`), and it cannot be used inside an expression
// either - the same diagnostic covers `x = i++`.
fun main(): Int {
    var i: Int = 0
    ++i
    println(i.toString())
    return 0
}
