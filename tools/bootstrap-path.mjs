// bootstrap-path.mjs - where the published bootstrap lives.
//
// The bootstrap is generated C++, not hand-written source, so it is published in its own
// repository (`simse_bootstrap`) instead of living in this tree. The tools resolve it from:
//
//   SIMSE_BOOTSTRAP          the file itself
//   SIMSE_BOOTSTRAP_REPO     that checkout; the file is <repo>/builds/simse_bootstrap.cpp
//   ../simse_bootstrap       the default sibling checkout
//   src/simse_bootstrap.cpp  a legacy in-tree copy, still honored when it exists
//
// `build.js --bootstrap` and `tools/bootstrap.js --write` refresh the file this resolves to.

import { existsSync } from "node:fs";
import * as path from "node:path";

import { REPO } from "./msvc.mjs";

export function bootstrapFile() {
  if (process.env.SIMSE_BOOTSTRAP) return path.resolve(process.env.SIMSE_BOOTSTRAP);

  const checkout = process.env.SIMSE_BOOTSTRAP_REPO;
  const published = path.join(
      path.resolve(checkout || path.resolve(REPO, "..", "simse_bootstrap")),
      "builds", "simse_bootstrap.cpp");
  if (existsSync(published)) return published;

  // A checkout that predates the split still carries the file in-tree.
  if (!checkout) {
    const legacy = path.join(REPO, "src", "simse_bootstrap.cpp");
    if (existsSync(legacy)) return legacy;
  }
  return published;
}
