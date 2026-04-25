# ADR-0005 — UI Component Library: PrimeNG + lucide-angular

- **Status:** Accepted
- **Date:** 2026-05-03
- **Deciders:** Owner

## Context

The owner asked for open-source-licensed component and icon libraries, mentioned Lucide as a candidate, and wants no later changes to this decision. Domain needs include a heavyweight calendar/scheduler widget, polished data tables, and rich form controls — typical enterprise SaaS UI.

## Decision

- **Component library:** **PrimeNG 18.x** — Apache License 2.0.
  - Comprehensive: data table, forms, dialogs, calendar/scheduler integration (FullCalendar wrapper), file upload, charts.
  - Themed via CSS variables; integrates with Tailwind 4 utilities.
  - Strong enterprise track record, active maintenance, broad Context7 documentation coverage.
- **Icon library:** **lucide-angular** — ISC License (MIT-equivalent permissive).
  - User-preferred, tree-shakeable, Angular-native bindings.
  - PrimeIcons remain available as a secondary set where a PrimeNG component requires them.

## Consequences

- License compatibility with proprietary product — confirmed (Apache-2.0 and ISC are both permissive, no copyleft).
- PrimeNG theme tokens drive the design system; Tailwind handles layout/spacing utilities. `frontend-engineer` agent uses PrimeNG components first, custom components only when none fits.
- Bundle size cost is real (~hundreds of kB). Mitigation: only import the modules used; lazy-load feature modules; track Lighthouse performance budget in CI.

## Alternatives considered

- **Angular Material** (MIT) — smaller surface; lacks an out-of-the-box scheduler; would force more custom UI for the calendar module.
- **Spartan UI** (MIT) — modern shadcn-style, but younger ecosystem and limited components.
- **Taiga UI** (Apache-2.0) — capable, but smaller English-language community and less Context7 coverage.

## Forbidden

- Adding any UI dependency under GPL/AGPL or "source-available, non-commercial" licenses. Enforced by `license-scan` skill.
