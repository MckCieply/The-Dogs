# ADR-0016 — JWT signing: HS256 with symmetric secret key

- **Status:** Proposed
- **Date:** 2026-05-27
- **Deciders:** Owner (review required)
- **Proposed by:** docs-writer (flow-b-1, implicit in feat(auth-01) implementation; ADR-0003 left the algorithm open as "HS256 or RS256")

## Context

[ADR-0003](0003-auth-jwt-rbac.md) decided to use self-issued JWTs signed with "HS256 or RS256 (secret/key in env)" but did not fix the algorithm. The AUTH-01 implementation (`TokenService.java`, `SecurityConfig.java`) uses **HS256 with a symmetric `SecretKey`** derived from a UTF-8 byte encoding of the `${jwt.secret}` environment variable via JJWT's `Keys.hmacShaKeyFor(...)`.

## Decision

Use **HS256** (HMAC-SHA-256) with a symmetric secret key for JWT signing and verification in the MVP.

- The secret is provided via `${jwt.secret}` and must be at least 32 characters (256 bits) to satisfy JJWT's minimum key-length assertion for HS256.
- Both `TokenService` (signing) and `SecurityConfig`'s `JwtDecoder` (verification) use the same `SecretKey` instance, preventing algorithm confusion attacks.
- The algorithm is pinned explicitly in both the builder (`Jwts.SIG.HS256`) and the decoder (`NimbusJwtDecoder.withSecretKey(...).macAlgorithm(HS256)`) to reject tokens signed with a different algorithm.

## Consequences

- Simple key management for a single-backend deployment: one secret rotated via env var.
- Not suitable for multi-backend or microservice scenarios where the verification key must be distributed without the signing key. If the architecture evolves in that direction, migrate to RS256/ES256 (asymmetric key pair) without changing the `oauth2-resource-server` wiring — only the key source changes.
- The `${jwt.secret}` value must be treated as a credential: not committed to version control, rotated on suspected compromise, stored in the prod `.env` file which is `.gitignore`d.
- Open security finding #22 (`RefreshTokenStore` has no TTL, no size cap) is a separate concern from the signing algorithm and is tracked independently.

## Alternatives considered

- **RS256 with a key pair** — better for distributed verification but adds operational complexity (key generation, rotation, distribution) with no benefit for a single-backend MVP. Can be adopted later without an API contract change.
- **ES256** — similar tradeoffs to RS256, smaller signatures. Deferred for the same reason.
