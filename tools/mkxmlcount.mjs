// mkxmlcount.mjs - throwaway: count XmlNode / child-list activity in a generated
// amalgamation, so the cost of eagerly allocating `Children` can be measured.
//
//   bun tools/mkxmlcount.mjs <in.cpp> <out.cpp>
//
// Counts:
//   * XmlNode values constructed (every `XmlNode(...)` call site, pass-through)
//   * child lists allocated (`makeList<XmlNode>()`)
//   * appends into a child list, and how many lists ever received one
//   * a histogram of list sizes at the end (lists are retained by the probe, so
//     the histogram covers every list the run ever created)

import { readFileSync, writeFileSync } from "node:fs";

const [, , inPath, outPath] = process.argv;
if (!inPath || !outPath) {
  console.error("usage: bun tools/mkxmlcount.mjs <in.cpp> <out.cpp>");
  process.exit(1);
}

let s = readFileSync(inPath, "utf8");

function after(anchor, text) {
  const at = s.indexOf(anchor);
  if (at < 0) throw new Error(`anchor not found: ${anchor}`);
  s = s.slice(0, at + anchor.length) + text + s.slice(at + anchor.length);
}

function before(anchor, text) {
  const at = s.indexOf(anchor);
  if (at < 0) throw new Error(`anchor not found: ${anchor}`);
  s = s.slice(0, at) + text + s.slice(at);
}

// Wrap every `XmlNode(...)` *call* in a pass-through counter. The matcher skips
// the declaration-ish uses (`XmlNode name`, `XmlNode*`, `XmlNode x = ...`), and
// balances parentheses so nested calls come out right.
function wrapNodeCalls(text) {
  const call = /(?<![\w>])XmlNode\s*\(/g;
  let out = "";
  let cursor = 0;
  let match;
  while ((match = call.exec(text)) !== null) {
    const open = match.index + match[0].length - 1;
    let depth = 0;
    let i = open;
    let inStr = false;
    let inChr = false;
    for (; i < text.length; i++) {
      const c = text[i];
      if (inStr) {
        if (c === "\\") i++;
        else if (c === '"') inStr = false;
        continue;
      }
      if (inChr) {
        if (c === "\\") i++;
        else if (c === "'") inChr = false;
        continue;
      }
      if (c === '"') inStr = true;
      else if (c === "'") inChr = true;
      else if (c === "(") depth++;
      else if (c === ")") {
        depth--;
        if (depth === 0) break;
      }
    }
    if (depth !== 0) throw new Error("unbalanced parens after XmlNode(");
    out += text.slice(cursor, match.index) + "xmlCountNode(" + text.slice(match.index, i + 1) + ")";
    cursor = i + 1;
    call.lastIndex = cursor;
  }
  return out + text.slice(cursor);
}

const helper = `
// ---- XmlNode counters (throwaway copy) -------------------------------------
#include <cstdio>
#include <unordered_map>
namespace XmlCount {
    // Leaked: the report runs after function-local statics would be destroyed.
    inline long long& value(int which) {
        static long long* cells = new long long[4]();
        return cells[which];
    }
    inline long long& nodes() { return value(0); }
    inline long long& lists() { return value(1); }
    inline long long& appends() { return value(2); }
    inline long long& firstAppends() { return value(3); }

    // Every list the run created, retained so its final size can be reported.
    inline std::vector<std::shared_ptr<List<XmlNode>>>& created() {
        static auto* v = new std::vector<std::shared_ptr<List<XmlNode>>>();
        return *v;
    }
    inline std::unordered_map<const void*, long long>& appendsOf() {
        static auto* m = new std::unordered_map<const void*, long long>();
        return *m;
    }

    inline PList<XmlNode> makeChildren() {
        PList<XmlNode> list = makeList<XmlNode>();
        lists()++;
        created().push_back(list);
        return list;
    }

    inline void dump() {
        long long empty = 0, h1 = 0, h2 = 0, h3 = 0, h4 = 0, h5to8 = 0, h9plus = 0;
        long long children = 0;
        for (const std::shared_ptr<List<XmlNode>>& list: created()) {
            long long count = list->size();
            children += count;
            if (count == 0) empty++;
            else if (count == 1) h1++;
            else if (count == 2) h2++;
            else if (count == 3) h3++;
            else if (count == 4) h4++;
            else if (count <= 8) h5to8++;
            else h9plus++;
        }
        std::fprintf(stderr, "xml: nodes=%lld childLists=%lld appends=%lld firstAppends=%lld\\n",
                     nodes(), lists(), appends(), firstAppends());
        std::fprintf(stderr, "xml: lists by size: 0=%lld 1=%lld 2=%lld 3=%lld 4=%lld 5-8=%lld 9+=%lld (children=%lld)\\n",
                     empty, h1, h2, h3, h4, h5to8, h9plus, children);
        std::fprintf(stderr, "xml: never-used lists=%lld of %lld (%.1f%%)\\n",
                     empty, lists(), lists() ? 100.0 * (double) empty / (double) lists() : 0.0);
        std::fprintf(stderr, "xml: a child list costs %zu B (List<XmlNode>), node %zu B\\n",
                     sizeof(List<XmlNode>), sizeof(XmlNode));
        std::fprintf(stderr, "xml: heap held by child lists=%lld B, of which never used=%lld B\\n",
                     lists() * (long long) sizeof(List<XmlNode>),
                     empty * (long long) sizeof(List<XmlNode>));
    }
}
template <class T>
T&& xmlCountNode(T&& value) {
    XmlCount::nodes()++;
    return static_cast<T&&>(value);
}
inline void xmlCountAppend(List<XmlNode>& self, const XmlNode& value) {
    XmlCount::appends()++;
    long long& seen = XmlCount::appendsOf()[&self];
    if (seen == 0) XmlCount::firstAppends()++;
    seen++;
    simse_list_append(self, value);
}
// ---------------------------------------------------------------------------
`;

// 1. count child-list allocations (before the helper lands, so the helper's own
// call to makeList is not rewritten into recursion)
s = s.split("makeList<XmlNode>()").join("XmlCount::makeChildren()");

// 2. the counters + the child-append wrapper
after('#include <type_traits>', helper);

// 3. route appends into a `Children` handle through the counter
s = s.replace(/simse_list_append\(\(\*([A-Za-z0-9_.>\-]+)\.Children\), /g,
  "xmlCountAppend((*$1.Children), ");

// 4. count node constructions
s = wrapNodeCalls(s);

// 5. report on the successful path
before("    if (!(!simse_writeFile(output, emitted.Value))) goto L69;",
  "    XmlCount::dump();\n");

writeFileSync(outPath, s);
console.log(`wrote ${outPath}`);
