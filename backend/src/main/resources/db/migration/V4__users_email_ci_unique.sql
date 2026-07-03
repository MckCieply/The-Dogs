-- V4__users_email_ci_unique.sql — AUTH-04: case-insensitive email uniqueness
-- Spec: docs/specs/auth-register.md
--
-- The users.email column stays varchar (switching to citext breaks Hibernate
-- schema validation, which does not know the citext type). Case-insensitive
-- uniqueness is enforced with a functional unique index instead; the
-- registration service queries with findByEmailIgnoreCase so lookups match
-- the same semantics.

CREATE UNIQUE INDEX IF NOT EXISTS uq_users_email_lower ON users (lower(email));
