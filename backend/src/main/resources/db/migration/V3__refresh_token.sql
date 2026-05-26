-- AUTH-03: Refresh token rotation + theft detection
-- Spec: docs/specs/auth-refresh-logout.md | ADR: docs/adr/0003-auth-jwt-rbac.md
--
-- Token value is stored as SHA-256 of the raw 256-bit random bytes, hex-encoded (64 chars).
-- family_id groups tokens from a single login session; on theft detection the whole family
-- is revoked to invalidate all tokens from that session.
--
-- Locking note: SELECT FOR UPDATE on a single row by primary key or unique index holds a
-- row-level lock only — no table lock, online-safe.

CREATE TABLE refresh_token (
    id          UUID PRIMARY KEY,
    family_id   UUID NOT NULL,
    parent_id   UUID REFERENCES refresh_token(id) ON DELETE SET NULL,
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  CHAR(64) NOT NULL UNIQUE,
    issued_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    revoked_at  TIMESTAMPTZ,
    user_agent  TEXT,
    ip_bucket   TEXT
);

-- Primary lookup: hash is always the query predicate
CREATE INDEX idx_refresh_token_hash   ON refresh_token (token_hash);

-- Theft detection: revoke all tokens in a family
CREATE INDEX idx_refresh_token_family ON refresh_token (family_id);

-- Partial index: active-token lookup for the user (used by cleanup + session listing)
CREATE INDEX idx_refresh_token_user_active
    ON refresh_token (user_id) WHERE revoked_at IS NULL;
