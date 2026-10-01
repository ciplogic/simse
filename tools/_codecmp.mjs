// Scratch: prove an edit to a Simse file was comments and whitespace only.
//
//   bun tools/_codecmp.mjs build/before/cppsrc/codegen/Codegen.kt cppsrc/codegen/Codegen.kt
//
// Strips `//` comments and *tokenizes* what is left: string and char literals verbatim,
// then maximal runs of identifier/number characters and of operator/punctuation characters,
// with whitespace between tokens ignored. Comparing token texts (not characters) is what
// makes `a << b` different from `a < < b` - whitespace inside an operator is code, and
// stripping whitespace blindly would hide it.
import { readFileSync } from "fs";

// The scanner's multi-char operators, longest first (cppsrc/lex/Scanner.kt,
// `makeMultiCharOperators`): an operator is matched greedily against this table, so `<<` is
// one token while `< <` is two.
const OPERATORS = [
  "->", "==", "!=", "<=", ">=", "&&", "||", "+=", "-=", "*=", "/=", "%=",
  "&=", "|=", "^=", "<<=", ">>=", "<<", ">>", "++", "--", "..",
];

function tokens(text) {
  const out = [];
  let i = 0;
  const n = text.length;
  const isWord = (c) => /[A-Za-z0-9_]/.test(c);
  while (i < n) {
    const c = text[i];
    if (c === " " || c === "\t" || c === "\r" || c === "\n") {
      i++;
      continue;
    }
    if (c === "/" && text[i + 1] === "/") {
      while (i < n && text[i] !== "\n") i++;
      continue;
    }
    if (c === '"' || c === "'") {
      let lit = c;
      i++;
      while (i < n) {
        const d = text[i];
        if (d === "\\") {
          lit += d + (text[i + 1] ?? "");
          i += 2;
          continue;
        }
        lit += d;
        i++;
        if (d === c) break;
      }
      out.push(lit);
      continue;
    }
    if (isWord(c)) {
      let word = "";
      while (i < n && isWord(text[i])) word += text[i++];
      out.push(word);
      continue;
    }
    let matched = "";
    for (const op of OPERATORS) {
      if (text.startsWith(op, i)) {
        matched = op;
        break;
      }
    }
    if (matched !== "") {
      out.push(matched);
      i += matched.length;
      continue;
    }
    out.push(c);
    i++;
  }
  return out;
}

const [before, after] = process.argv.slice(2);
const a = tokens(readFileSync(before, "utf8"));
const b = tokens(readFileSync(after, "utf8"));
let at = 0;
while (at < a.length && at < b.length && a[at] === b[at]) at++;
if (at === a.length && at === b.length) {
  console.log(`comments/whitespace only: ${after}  (${a.length} tokens)`);
  process.exit(0);
}
const win = (t) => t.slice(Math.max(0, at - 6), at + 6).join(" ");
console.log(`CODE CHANGED in ${after} at token ${at}\n  before: ${win(a)}\n  after : ${win(b)}`);
process.exit(1);
