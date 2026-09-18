package broken

// An attribute's arguments are literals: a name is read as a literal, and one that
// is not is a parse error (specs/attributes.md).

@SmGen(cpp)
fun tick(): Int
