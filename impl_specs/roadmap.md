# Roadmap to a self-hosted Simse

Status: planning baseline. Subject to revision as tasks are executed.

## Purpose

This is the concrete, executable breakdown of `impl_specs/plan-to-selfhost.md`
and the staged pipeline in `impl_specs/transpilation.md`. Each outstanding work
item lives in its own file under `impl_specs/tasks/` and has a status,
dependencies, scope, deliverables, acceptance criteria, and steps. Completed
tasks are pruned once verified; the capability matrix and git history retain
that detail.

## Current state

The compiler is ported to `.simse` and self-hosts. The C++ `simse_transpile`
emits `compiler_stage1.cpp` from the `.simse` compiler sources, and the resulting
stage-1 compiler reproduces that output byte-for-byte (a fixed point). The
scanner, parser, sema, codegen, and driver each have a `.simse` mirror
differentially verified against its C++ reference, and `simse` compiles a
directory into one amalgamated `.cpp`. For the per-component and per-feature
status, see `impl_specs/capability-matrix.md`.

## Conventions

- Status values: `Not started`, `In progress`, `Blocked`, `Done`.
- Each task file uses the same sections: Goal, Motivation, Scope (In/Out),
  Deliverables, Acceptance criteria, Steps, Risks / notes, References.
- Prefer small tasks that fit one working session; split a task if it grows.
- A task is `Done` only when its acceptance criteria are literally checked off
  and the project still builds and passes the harness.
- When a task reveals a new requirement, record it in the relevant spec
  (`specs/`) or in a new task file rather than leaving it only in code.
- A completed task file is deleted once its work is verified; the capability
  matrix and git history retain the detail.

## Tasks

| ID  | Task | Phase | Depends on | Status |
| --- | --- | --- | --- | --- |
| T25 | Modules and packages | D - Completion | - | Done |

## Non-goals (for now)

- An interpreter or VM backend, an optimizer, or multiple backends.
- A full standard library.
- Performance work on the compiler itself.
- A complete FFI; only the narrow `native fun` fallback.
