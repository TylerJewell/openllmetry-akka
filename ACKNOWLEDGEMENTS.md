# Acknowledgements

This project is a port of **[traceloop/openllmetry](https://github.com/traceloop/openllmetry)**.

## Licence and copyright

traceloop/openllmetry is licensed under the **Apache License, Version 2.0**, read from its
`LICENSE` file rather than from a badge. Copyright is held by **Traceloop** and the
openllmetry contributors. A copy of that licence is kept here as `LICENSE-openllmetry`.

## What was copied

**No source was copied.** Not a file, not a function, not a fixture. Every Java file in this
repository was written against `openllmetry-port/specs/SPEC-001-openllmetry.md`, and that
specification was written from running the original and recording what it did — the record is
`openllmetry-port/docs/question-log.md`.

Two categories of text are shared and neither is copied from openllmetry:

- **Attribute names** such as `gen_ai.request.model` and `gen_ai.input.messages`, and
  provider identifiers such as `aws.bedrock`. These are OpenTelemetry's GenAI semantic
  conventions. openllmetry writes them because the standard says to, and so does this port.
  Reproducing them is the whole point of both projects; neither invented them.
- **Symbol names** appear in `bench/REPORT.md` and in the specification, naming which parts
  of the original are inside the ported slice. Names of functions, not their bodies.

## What is derived

The behaviour. Every rule in the specification is a description of something openllmetry
does, established by running it. Where this port behaves differently, it is listed in the
README under *Where it differs from traceloop/openllmetry*, with the reason.

Being a derived work is what a port is, and this one says so. This repository is licensed
under Apache 2.0 to match the original.

## Also used

- Akka
