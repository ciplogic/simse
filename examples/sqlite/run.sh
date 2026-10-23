#!/usr/bin/env bash
# Builds and runs the three sqlite examples, with every C++ compiler on the box.
set -u
cd "$(dirname "$0")/../.." || exit 1   # the repository root

CXXS=()
for cxx in g++ clang++ clang++-19 clang++-18; do
    command -v "$cxx" >/dev/null 2>&1 && CXXS+=("$cxx")
done
[ ${#CXXS[@]} -eq 0 ] && { echo "no C++ compiler found"; exit 1; }
SIMSE=${SIMSE:-./simse}
[ -x "$SIMSE" ] || { echo "no compiler at $SIMSE (build it: docs/building-on-linux.md)"; exit 1; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

status=0
for demo in basics builder linq; do
    src="examples/sqlite/$demo/src"
    out="$work/$demo.cpp"
    if ! "$SIMSE" --root "$src" --module modules/sqlite $( [ "$demo" = linq ] && echo --module modules/linq ) -o "$out"; then
        echo "TRANSPILE FAIL $demo"; status=1; continue
    fi
    for cxx in "${CXXS[@]}"; do
        exe="$work/$demo-$cxx"
        if ! "$cxx" -std=c++20 -O2 -DNDEBUG -I. "$out" -o "$exe" 2>"$work/$demo.build.log"; then
            echo "BUILD FAIL $demo [$cxx]"; head -3 "$work/$demo.build.log"; status=1; continue
        fi
        echo "== $demo [$cxx]"
        timeout 60 "$exe" || status=1
    done
done
exit $status
