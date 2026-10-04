package app

// Two fields of one type in a `union class`: the declaration is allowed, the arms share the
// union's storage, and the tag tells them apart. `get<Field>` hands back a raw pointer to the
// live arm (`null` when the tag says another), and a by-value construction picks the *earlier*
// field - `setUser`/`setEmail` are the way to each arm by name. See `stress/unions` for the
// wider shape.

union class Account(var User: Str, var Email: Str)

fun main(): Int {
    // `Account()` starts `None`: no arm is live yet.
    var a: Account = Account()
    println(a.isOfType(SmAccountTypes.None))
    println(a.getUser() != null)
    println(a.getEmail() != null)

    // `setUser` moves the tag; only `getUser` is non-null then.
    a.setUser("ada")
    println(a.isOfType(SmAccountTypes.User))
    println(a.getUser().size())
    println(a.getEmail() != null)

    // `setEmail` moves it to the other arm - same storage, other tag.
    a.setEmail("ada@example.com")
    println(a.isOfType(SmAccountTypes.Email))
    println(a.getUser() != null)
    println(a.getEmail().size())

    // A by-value construction picks the earlier `User` field.
    var b = Account("first")
    println(b.isOfType(SmAccountTypes.User))
    println(b.getUser().size())
    println(b.getEmail() != null)
    return 0
}
