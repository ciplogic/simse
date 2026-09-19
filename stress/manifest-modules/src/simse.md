module: alpha
module: beta

// The project file (specs/simse-md.md): the root is scanned as exactly these modules, so
// `stray.kt` beside this file is *not* scanned - it holds a second `main`, which could not
// be linked. A prose line like this one carries no entry, so it is ignored.
