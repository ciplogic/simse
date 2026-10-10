package fixtures

// Test vectors from another encoder: the hex of what .NET's GZipStream packs at
// CompressionLevel.Optimal - a fixed-Huffman stream, a stored one, a tiny one and a
// dynamic-Huffman one - so `decompress` is checked against streams this module did not
// write.

// The quick brown fox, four times (fixed Huffman).
fun sampleA(): Str {
    return "1f8b08000000000004002bc94855282ccd4cce56482aca2fcf5348cbaf50c82acd2d2856c82f4b2d5228014ae72456552aa4e4a7eb817983403100d1e24968b4000000"
}

// The first 256 bytes of `state = (state * 1103515245 + 12345) & 2147483647` from 1,
// read as `(state >> 16) & 255` (stored).
fun sampleB(): Str {
    return "1f8b0800000000000400010001fffec67e816b4bfbe2fb54f6bddf7c1ce18701bf31de56720f4767668759aa883c59ea56137bd285a1d83c54552f37ae655bda027998cce31a768e5fd9998f1f3f36ee43784d0dfabea6dae4868edc296d4eff56e17020fb8fb1580590c509dc53cdaa3b489952d3529d069feab5c206139849b2011eac3288319c52469571368f57f6391d16fa8874f5987c175c41bb6d718e0f7059c7011b2f333d91c01da50d0dab338d7e5e8f3ee66874a63ab1c39311a864c7dbcae060e1f3bf090067a2e325a0213187d562c5a84f7e2e096b949fb06da99e5a0b467080b6cf470ca6a52ad8acfba0ebb779247223924880c5a6a785b7d78c90e4ab63445266e39c3325f95e85bf448100010000"
}

// `abcabcabc` (fixed Huffman).
fun sampleC(): Str {
    return "1f8b08000000000004004b4c4a4e04230018482d4609000000"
}

// 512 bytes of `state = (state * 1103515245 + 12345) & 2147483647` from 7, read as
// `(state >> 16) & 15` - a skewed alphabet, which dynamic Huffman coding wins on.
fun sampleE(): Str {
    return "1f8b080000000000040015918909003108047d63fcfbeff67288208be28ed608b1d220360f35f91929b668308ca34321ccf78a4df5cc40206e270eb19c1ebd8270834b41ca03441933e13eddc7dcf5e55c8063e4ba455ab9d2438955b5baafca9ba5abc7f67864a4e82e16125dce6bb4ab613922c2a7b03d8f78f4eec8b3587760ba3c6338ee4d60a449bafd08f2eab2af8c476467ede16d7517a467f168178d72c98544593a0fe1edf50b4b354fe0aae3350fc46c4ea6f751d9b048a75e13a55a9fb2c7c9588b632f943b169254a5342061028d47eda0f8b679b3e12c37c64cd123710e02c243d808066ff89d23de91841f98421914027a77f43d8cd382a759ffd7bcaf4008c535b020cfae2c16d5507ae5d1e29094b77f5fa3011ed86b217883f8391928d50f972c55f500020000"
}
