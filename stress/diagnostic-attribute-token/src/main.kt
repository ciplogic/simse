package broken

// A `@` not followed by an identifier is a scanner error: it is the attribute token's
// rule that accepts a `@` at all (specs/attributes.md), so there is nothing left for
// the character to be. The case expects the transpile to fail with the diagnostic
// below.

@ fun tick(): Int
