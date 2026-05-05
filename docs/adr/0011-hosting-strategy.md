# ADR-0011 — Hosting Strategy: Self-Hosted Laptop + Cloudflare Tunnel

- **Status:** Accepted
- **Date:** 2026-05-05
- **Deciders:** Owner

## Context

The app needs a production hosting target. Evaluated options:

| Option | Cost | Complexity | Reliability concern |
|---|---|---|---|
| Fly.io free tier | $0 | Low | Cold starts, vendor risk (killed free tier precedent) |
| Railway Hobby | $5/mo | Low | Usage-based billing surprises |
| Hetzner CX22 VPS | €3.79/mo | Medium | Single VPS, no redundancy |
| Own laptop (self-hosted) | ~€5/mo electricity | Medium | Home internet, power cuts |

The app is in early stage with zero to a handful of users. Reliability SLA is informal. The owner already has a spare laptop with more RAM and CPU than any comparable cheap VPS.

## Decision

**Self-host on a spare laptop using Cloudflare Tunnel** for the initial production phase.

Key components of this setup:
- Docker Compose runs the full stack (postgres + backend + frontend/nginx) on the laptop
- **Cloudflare Tunnel** (`cloudflared`) connects the laptop to Cloudflare's edge — no port forwarding, no home IP exposure, free HTTPS
- A `docker-compose.prod.yml` override tightens the config: no exposed DB/API ports externally, restart policies, log rotation
- DDNS is unnecessary — Cloudflare Tunnel uses an outbound connection, so the home IP is irrelevant

## Migration path

When the app grows past ~50 active users or reliability becomes a business concern:

1. `docker compose down` on the laptop
2. Provision a Hetzner CX22 (€3.79/mo)
3. `git pull && docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d` on the VPS
4. Point the Cloudflare Tunnel to the VPS instead of the laptop — zero DNS changes

The entire migration is under 30 minutes because the stack is 12-factor and fully containerised.

## Consequences

**Positive**
- Zero ongoing hosting cost during development and early users
- More RAM/CPU than any comparable €5/mo VPS
- Cloudflare Tunnel provides DDoS protection, TLS termination, and hides the home IP for free
- Portability: the same `docker-compose.prod.yml` works identically on any future VPS

**Negative / risks**
- Home internet outage = app outage — acceptable at this stage
- Power cut = app outage — mitigated with a cheap UPS
- Laptop hardware failure = data loss if DB volume isn't backed up — **mitigation: daily `pg_dump` to a cloud storage bucket (B2/S3), automated via cron**
- Laptop running 24/7 increases wear — acceptable on a spare machine

## Open

- Postgres backup cron job to off-site storage (B2/Backblaze or similar) — to be implemented before first real user data is stored
