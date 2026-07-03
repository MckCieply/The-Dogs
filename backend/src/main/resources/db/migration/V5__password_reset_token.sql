-- V5__password_reset_token.sql — AUTH-05: password reset (admin-token flow, no SMTP)
-- Spec: docs/specs/auth-password-reset.md | ADR: ADR-0013
--
-- Only one ACTIVE token per user is allowed at a time: a second forgot-password
-- request during the validity window revokes the previous token (used_at = now())
-- and issues a new one. Enforced in RegistrationService — the partial index below
-- exists for lookup speed, not uniqueness, because revocation happens in the same
-- transaction as the new insert.

CREATE TABLE password_reset_token (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash          CHAR(64) NOT NULL UNIQUE,
    issued_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ NOT NULL,
    used_at             TIMESTAMPTZ,
    requested_ip_bucket TEXT
);

CREATE INDEX idx_password_reset_token_user_active
    ON password_reset_token (user_id) WHERE used_at IS NULL;
