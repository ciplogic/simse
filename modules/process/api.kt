package process

// The `process` module: run a command line and answer what it did - the part of Node's
// `child_process` (and C#'s `Process`) a tool needs. A command goes through the shell, which
// is what the C runtime's `system` does (`cmd /c` on Windows, `/bin/sh -c` elsewhere), so a
// compound command line works as written.
//
// The C++ is this module's `_res.md`, section `process`, `emit: reached` - a program that
// never runs a command carries none of it. Naming the module: `--module modules/process`.

// Run `command` to completion and answer its exit code (0 = success; negative only when the
// command could not be run at all).
@SmGen("res", "process", "simse_process_run")
fun run(command: Str): Int

// Run `command` to completion, answer everything it wrote to stdout and stderr joined (the
// two streams are merged, so the text is the command's whole answer in order), and write its
// exit code through `exitCode`.
@SmGen("res", "process", "simse_process_capture")
fun capture(command: Str, exitCode: *Int): Str
