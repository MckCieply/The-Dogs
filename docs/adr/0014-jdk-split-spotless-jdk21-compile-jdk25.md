# ADR-0014 — JDK split: Spotless/google-java-format on JDK 21, compilation on JDK 25

- **Status:** Proposed
- **Date:** 2026-05-27
- **Deciders:** Owner (review required)
- **Proposed by:** docs-writer (flow-b-1, implicit in ci-backend.yml changes from feat/f001 + fix/ci commits)

## Context

The project targets Java 25 (records, pattern matching, virtual threads) as stated in [ADR-0001](0001-stack-and-build-tools.md). The chosen formatter, `google-java-format`, uses internal `javac` APIs (`com.sun.tools.javac.api.DiagnosticFormatter` family) that were removed in JDK 25. Running `spotless:check` or `spotless:apply` under JDK 25 therefore fails with an `InaccessibleObjectException` regardless of the `google-java-format` version.

`google-java-format` 1.25.2 (the latest release) is compatible with JDK 21 LTS and produces output identical in formatting semantics to earlier versions.

## Decision

Split the CI backend job into two JDK setup steps:

1. Install **JDK 21** (Temurin). Run `./mvnw -B spotless:check`.
2. Install **JDK 25** (Temurin). Run `./mvnw -B verify` (compile + tests).

Local development: developers run `./mvnw spotless:apply` with whichever JDK they have installed. If using JDK 25 locally, they must either switch to JDK 21 for formatting or use the `spotless:off`/`spotless:on` guards and rely on CI to catch formatting violations.

Spotless version: **2.46.1**. google-java-format version: **1.25.2**.

## Consequences

- CI correctly enforces Google Java style while compiling against JDK 25 features.
- Local DX is slightly degraded for developers on JDK 25 only: `mvn verify` will succeed but `spotless:apply` will fail. Mitigation: document this in the backend README or `.mvn/jvm.config`.
- When `google-java-format` ships a release compatible with JDK 25 internals (or when JDK 25 stabilises the relevant APIs), this split can be collapsed back to a single JDK step. Track via Dependabot.

## Alternatives considered

- **Use a different formatter** (e.g., `palantir-java-format`) — rejected; Google Java Format is already enforced and changing formatters would reformat the entire codebase.
- **Downgrade to Java 21 as the compile target** — rejected; Java 25 is the stated target in ADR-0001 and provides language features used in the codebase.
- **Skip formatting in CI** — rejected; formatting is a required gate to prevent drift.
