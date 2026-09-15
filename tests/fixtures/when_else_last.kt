package fixtures

// Negative fixture: `else` is the last arm of a `when`. The desugaring makes it the
// chain's else body, so an arm after it would have nowhere to go.
fun pick(n: Int): Int {
    when (n) {
        1 -> {
            return 1
        }

        else -> {
            return 0
        }

        2 -> {
            return 2
        }
    }
    return -1
}
