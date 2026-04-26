# ADR-0009 — Branching Strategy: dev / staging / prod

- **Status:** Accepted
- **Date:** 2026-05-03
- **Deciders:** Aleksander Torka (Owner)

## Context

We need a branching model that supports promotion through environments without ceremony, fits an AI team that lands many small commits, and gives CI clear targets to gate.

## Decision

Three long-lived branches map 1:1 to environments:

| Branch    | Environment | Purpose                                                                |
| --------- | ----------- | ---------------------------------------------------------------------- |
| `dev`     | local + dev | **Default branch.** Integration trunk. All feature branches merge here. |
| `staging` | staging     | Pre-production. Mirrors what will ship to prod next.                   |
| `prod`    | production  | What is currently deployed to production.                              |

### Flow

```
feat/* ─┐
fix/*  ─┤── PR ──▶ dev ── promote ──▶ staging ── promote ──▶ prod
chore/*─┘
```

- **Feature branches** (`feat/<slug>`, `fix/<slug>`, `chore/<slug>`) branch from `dev` and merge back to `dev` via PR.
- **Promotion `dev → staging`:** fast-forward or merge commit, triggered when a release candidate is ready. Runs full e2e + smoke suites in CI.
- **Promotion `staging → prod`:** fast-forward or merge commit, triggered when staging is signed off. Triggers the release workflow (build images, tag, draft release notes).
- **Hotfixes** branch from `prod` as `hotfix/<slug>`, merge back to `prod`, then are immediately back-merged to `staging` and `dev` to prevent regression.
- **No long-lived feature branches.** Anything not merged to `dev` within ~5 days gets rebased or closed.

### Branch protection (GitHub)

| Branch    | Required checks                                       | Merge type        | Force push | Deletions |
| --------- | ----------------------------------------------------- | ----------------- | ---------- | --------- |
| `dev`     | `ci-backend`, `ci-frontend`                           | squash            | blocked    | blocked   |
| `staging` | `ci-backend`, `ci-frontend`, `ci-e2e`                 | merge commit      | blocked    | blocked   |
| `prod`    | `ci-backend`, `ci-frontend`, `ci-e2e`, manual approval | merge commit     | blocked    | blocked   |

Linear history on `dev`. Merge commits on `staging` and `prod` so promotions are visually distinct in `git log`.

### Default branch

`dev` is the repository default — PRs target `dev` unless explicitly retargeted.

## Consequences

- AI agents always create feature branches from `dev` and target `dev` in PRs (encoded in the `feature-deliver` orchestrator skill).
- Three CI workflows are environment-aware: `ci-backend.yml` and `ci-frontend.yml` run on every PR + push; `ci-e2e.yml` runs on push to `staging` and `prod` (and nightly); `release.yml` runs on push to `prod` and on `v*` tags.
- The `release-manager` agent owns the promotion PRs (`dev → staging`, `staging → prod`).
- `release-notes` skill generates notes from commits between the previous and current `prod` tag.

## Alternatives considered

- **Pure trunk-based on a single branch with environment tags** — minimal ceremony, but loses the visual "what's in staging right now" that long-lived environment branches provide.
- **GitFlow (`main` + `develop` + `release/*` + `hotfix/*`)** — overkill for a single-team SaaS; release branches add latency.

## Notes for the AI team

- Never `git push --force` to `dev`, `staging`, or `prod` (already covered by branch protection; reinforced in `code-reviewer` checklist).
- Never branch a feature directly from `staging` or `prod`.
- Hotfix back-merges (`prod → staging → dev`) are the `release-manager`'s responsibility and must happen in the same PR cycle as the hotfix itself.
