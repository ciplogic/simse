package escapetwin

// The `&T`-returning twin ABI (impl_specs/escape-analysis-plan.md, Step 5): a
// *fresh-handle* function - every return builds the value it hands back, `&Ctor(...)` -
// is emitted twice. The authored `Ref<T> f(...)` keeps its `makeRef` for the calls that
// keep the handle, and a value twin `void f_v(T* _sm_out, ...)` stores the construction
// into the caller's storage, which a call whose handle never escapes takes: the promoted
// shape straight from the source spelling - stack storage, a raw pointer, and the box
// only where the handle is stored. Both spellings of one function in one program.

ref class User(var name: Str, var age: Int)

fun createUser(name: Str, age: Int): &User {
    return &User(name, age)
}

fun main(): Int {
    // The handle never escapes: the twin builds it on the stack, `user` is a raw pointer,
    // and no box exists on this path.
    val user = createUser("John", 100)
    if (user.age > 100) {
        return 1
    }
    println(user.name)

    // The handle is stored into the list: the authored box ABI, the one `makeRef`.
    var users: List<&User> = List<&User>()
    val kept = createUser("Ada", 36)
    users.append(kept)
    println(users.size())
    println(users[0].name)
    return 0
}
