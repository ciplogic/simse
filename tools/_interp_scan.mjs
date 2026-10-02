// Scans the compiler's own sources for `@identifier` (or `@(identifier)`) inside backtick
// strings, which now interpolate: reports file:line and the identifier.
import { readdirSync, readFileSync, statSync } from "fs";
import { join } from "path";

function walk(dir, out) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) walk(p, out);
    else if (p.endsWith(".kt")) out.push(p);
  }
  return out;
}

const files = walk(process.argv[2] ?? "src", []);
let hits = 0;
for (const file of files) {
  const text = readFileSync(file, "utf8");
  let i = 0, line = 1, col = 1;
  while (i < text.length) {
    const ch = text[i];
    if (ch === "\n") { line++; col = 1; i++; continue; }
    if (ch === "/" && text[i + 1] === "/") {
      while (i < text.length && text[i] !== "\n") i++;
      continue;
    }
    if (ch === "/" && text[i + 1] === "*") {
      i += 2;
      while (i < text.length && !(text[i] === "*" && text[i + 1] === "/")) {
        if (text[i] === "\n") { line++; col = 1; }
        i++;
      }
      i += 2;
      continue;
    }
    if (ch === '"') {
      i++;
      while (i < text.length && text[i] !== '"') {
        if (text[i] === "\\") i++;
        if (text[i] === "\n") line++;
        i++;
      }
      i++;
      continue;
    }
    if (ch === "'") {
      i++;
      while (i < text.length && text[i] !== "'") {
        if (text[i] === "\\") i++;
        i++;
      }
      i++;
      continue;
    }
    if (ch === "`") {
      const startLine = line;
      i++;
      while (i < text.length && text[i] !== "`") {
        if (text[i] === "@" && /[A-Za-z_]/.test(text[i + 1] ?? "")) {
          let j = i + 2;
          while (j < text.length && /[A-Za-z0-9_]/.test(text[j])) j++;
          console.log(`${file}:${line}: @${text.slice(i + 1, j)}`);
          hits++;
        } else if (text[i] === "@" && text[i + 1] === "(" && /[A-Za-z_]/.test(text[i + 2] ?? "")) {
          let j = i + 2;
          while (j < text.length && /[A-Za-z0-9_]/.test(text[j])) j++;
          console.log(`${file}:${line}: @(${text.slice(i + 2, j)}`);
          hits++;
        }
        if (text[i] === "\n") { line++; col = 1; }
        i++;
      }
      i++;
      continue;
    }
    i++;
  }
}
console.log(`${hits} hit(s)`);
