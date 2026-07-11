-- V6__email_verification.sql — AUTH-09: email confirmation (admin-token flow, no SMTP)
-- Mirrors V5__password_reset_token.sql / ADR-0013: tokens are hashed at rest and
-- delivered out-of-band (dev log or ROLE_ADMIN endpoint) until SMTP lands.
--
-- Existing accounts are grandfathered as verified (DEFAULT TRUE): they registered
-- before verification existed and locking them out retroactively would be a
-- self-inflicted incident. RegistrationService explicitly inserts FALSE for every
-- account created from now on.

ALTER TABLE users
    ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT TRUE;

CREATE TABLE email_verification_token (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash          VARCHAR(64) NOT NULL UNIQUE,
    issued_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ NOT NULL,
    used_at             TIMESTAMPTZ,
    requested_ip_bucket TEXT
);

CREATE INDEX idx_email_verification_token_user_active
    ON email_verification_token (user_id) WHERE used_at IS NULL;
