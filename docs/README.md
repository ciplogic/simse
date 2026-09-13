# Simse documentation

Start with the [README](../README.md) for what the language is and how to build
it, then:

| Document | Read it when you want to |
| --- | --- |
| [getting-started.md](getting-started.md) | install the prerequisites, build the compiler, compile and run your first program, run the tests |
| [language-tour.md](language-tour.md) | learn the language: values, control flow, functions, data classes, enums, generics, collections, memory, modules |
| [how-it-works.md](how-it-works.md) | understand the pipeline, the two compiler implementations, and what the generated C++ looks like |
| [state-of-the-field.md](state-of-the-field.md) | know what works, what is rough, what is missing, and how this compares to Rust/Go/Node/C++ |

Runnable sources for the examples used in these documents live in
[examples/](examples/): `hello`, `tour`, and `wordcount`.

For working *on* the compiler rather than with the language:

- [../guide4ai.md](../guide4ai.md) - orientation: build, test, invariants, change
  protocol (it is written for an AI session, but it is the shortest path into the
  code either way);
- [../specs/](../specs/) - the normative language specification;
- [../impl_specs/](../impl_specs/) - implementation plans and records, including
  the [user-facing roadmap](../impl_specs/user-language-roadmap.md) and the
  [capability matrix](../impl_specs/capability-matrix.md) with the measured
  numbers;
- [../stress/README.md](../stress/README.md) - the end-to-end corpus.
