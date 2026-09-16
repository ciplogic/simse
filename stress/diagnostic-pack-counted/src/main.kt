package app

// A `&List<T>` parameter is a *counted* reference (`std::shared_ptr`), and a packed
// list is a throwaway temporary: building one to hand it a reference it drops again
// at the end of the same statement would cost a control block and a count for
// nothing, so the counted forms are deliberately not pack targets
// (specs/functions.md). The call is the arity error it always was - the zero-copy
// spelling of the same call is the borrow, `fun sum(values: *List<Int>)`.

fun sum(values: &List<Int>): Int {
    return values.size()
}

fun main(): Int {
    println(sum(1, 2, 3).toString())
    return 0
}
