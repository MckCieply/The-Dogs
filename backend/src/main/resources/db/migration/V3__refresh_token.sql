-- AUTH-03: refresh token table. See docs/specs/auth-refresh-logout.md and ADR-0003.
-- Stores persistent, hashed refresh tokens supporting rotation and family-based theft detection.

CREATE TABLE refresh_token (
    id          UUID PRIMARY KEY,
    family_id   UUID NOT NULL,
    parent_id   UUID REFERENCES refresh_token(id) ON DELETE SET NULL,
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,   -- hex SHA-256 of the opaque refresh token value
    issued_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    revoked_at  TIMESTAMPTZ,
    user_agent  TEXT,
    ip_bucket   TEXT  -- /24 for IPv4, /64 for IPv6; nullable
);

-- Lookup by hash is the hot path for refresh and logout
CREATE INDEX idx_refresh_token_hash ON refresh_token (token_hash);

-- Revoke whole family on theft detection
CREATE INDEX idx_refresh_token_family ON refresh_token (family_id);

-- Active tokens per user (partial index — skips revoked rows)
CREATE INDEX idx_refresh_token_user_active
    ON refresh_token (user_id) WHERE revoked_at IS NULL;
