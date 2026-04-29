# Design

This directory holds **design artifacts and mockups** for The-Dogs — wireframes, screen mockups, flow diagrams, and any other visual specs that accompany a feature spec under `docs/specs/`.

## Status

**Tooling and format are not yet decided.** Candidates being considered include AI-driven design tools (e.g., Claude Design), Figma, Excalidraw, plain SVG/PNG checked into the repo, or a mix. The decision will be made when a feature first needs non-trivial design work — most likely the **Scheduler module**, which has the richest UI surface in the MVP.

When the decision lands it will be captured as an ADR (`docs/adr/NNNN-design-tooling.md`) and this README will be updated with the chosen workflow.

## Conventions (placeholder, will solidify with the tooling decision)

- One sub-directory per feature, mirroring `docs/specs/` (e.g., `docs/design/scheduler/`).
- Source files committed where possible; exported images committed alongside.
- Each feature spec under `docs/specs/<feature>.md` links to its design artifacts here.

## Note on the AI team

The current 8-agent team ([ADR-0010](../adr/0010-ai-team-consolidation.md)) does **not** include a dedicated `ui-designer` role. If/when design becomes a regular workflow step, that decision will be revisited via a superseding ADR.
