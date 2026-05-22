---
name: adr-new
description: Create a new numbered Architecture Decision Record (ADR) under docs/adr/, pre-filled with the project's standard template (Status / Date / Deciders frontmatter, then Context / Decision / Consequences / Open). Use this skill whenever the user wants to draft, create, or record an ADR, decision record, architecture decision, design decision, or "let's write that down" — even if they don't say the word "ADR". Trigger on phrases like "draft an ADR for X", "we should record this decision", "add a decision record", "ADR for picking Y", or after a long discussion where the user signals "ok let's lock this in".
---

# adr-new

Create a new ADR file under `docs/adr/` for The-Dogs.

## Steps

1. **Find the next ADR number.** Run `ls docs/adr/` (or Glob `docs/adr/*.md`). ADR files are named `NNNN-slug.md` where `NNNN` is zero-padded to 4 digits. Pick `NNNN = (max existing) + 1`.

2. **Ask the user for** (skip if already provided in the request):
   - Short title (becomes both the file slug — kebab-case — and the H1)
   - Status: `Proposed`, `Accepted`, `Deprecated`, `Superseded by ADR-NNNN`
   - Whether this supersedes a prior ADR (and if so, which one — to mark that one Superseded too)

3. **Write the file** at `docs/adr/<NNNN>-<slug>.md` with this exact template (preserving heading levels, dashes, frontmatter style — match the existing ADRs in the project):

```markdown
# ADR-<NNNN> — <Title>

- **Status:** <Proposed | Accepted | Deprecated | Superseded by ADR-NNNN>
- **Date:** <YYYY-MM-DD — today>
- **Deciders:** Aleksander Torka (Owner)
<-- If superseding: "- **Supersedes:** [ADR-NNNN](NNNN-<slug>.md)" -->

## Context

<Why this decision needs to be made. The forces at play. Constraints.>

## Decision

<What was decided. Concrete enough that the AI team can implement it.>

## Consequences

**Positive**
- <bullet>

**Negative / risks**
- <bullet>

## Alternatives considered

- **<Alt 1>** — <why rejected>

## Open

- <items still TBD>

## Notes

- <anything else worth retaining for future readers>
```

4. **If superseding** a prior ADR, also edit the prior file's frontmatter:
   - `Status:` → `Superseded by [ADR-<NNNN>](<NNNN>-<slug>.md)`

5. **Update `docs/ARCHITECTURE.md` §12** "Architecture Decision Records" to list the new ADR. Mark superseded entries.

6. **If the decision affects an open item in `docs/AI_NATIVE.md` §8** "Open Decisions for the Human", flip the row's status to ✅ and add the ADR link.

## Conventions to follow

- Match the tone and structure of existing ADRs (read `docs/adr/0009-*.md`, `0011-*.md`, `0012-*.md` as references — terse, opinionated, no marketing prose).
- Date in ISO format `YYYY-MM-DD`.
- Use markdown reference links to other ADRs, not bare URLs.
- One-line H1 per ADR; no emoji.
- The body should be useful 6 months later when someone asks "why did we do this?"

## When NOT to use

- If the user just wants a docs spec for a feature → use `spec-new` skill instead.
- If it's a tiny config choice that doesn't outlive a sprint → just commit it without an ADR.
- If the decision is already covered by an existing ADR → amend that one (add a Notes section dated entry) rather than creating a new ADR.
