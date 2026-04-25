# The-Dogs

**Enterprise SaaS for dog trainers.** Installable PWA with an Angular frontend and a Spring Boot backend, built end-to-end by an AI agent team under a harness-engineer model.

MVP modules: **Dogs** (CRUD), **Notes** per dog (CRUD), **Scheduler** (client meeting calendar).

## Repository layout

```
The-Dogs/
├── docs/        # Architecture, ADRs, AI-native workflow, feature specs
├── frontend/    # Angular 21 PWA (npm)
└── backend/     # Spring Boot 3.5 (Maven, Hibernate, Lombok, PostgreSQL)
```

## Documentation

- [Architecture](docs/ARCHITECTURE.md) — stack, versions, MCP servers, repo layout, security, CI/CD
- [AI-Native Development Guide](docs/AI_NATIVE.md) — agent team, per-feature workflow, skills
- [Architecture Decision Records](docs/adr/) — every non-obvious decision

## Status

Project bootstrap. No application code yet — `frontend/` and `backend/` are empty until the scaffold skills run. See [docs/AI_NATIVE.md §5](docs/AI_NATIVE.md) for the planned skills.

## License

Pending — see [ADR-0008](docs/adr/0008-license.md).
