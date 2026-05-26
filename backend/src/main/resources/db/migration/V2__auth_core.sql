-- V2__auth_core.sql — AUTH-01: Role table, user_role join, user schema additions
-- Spec: docs/specs/auth-core-jwt.md | ADR: ADR-0003

-- citext for case-insensitive email comparisons
CREATE EXTENSION IF NOT EXISTS citext;

-- Dedicated role table (replaces implicit enum strings in user_roles)
CREATE TABLE IF NOT EXISTS role (
    id   SMALLINT PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

INSERT INTO role (id, name) VALUES (1, 'ROLE_TRAINER'), (2, 'ROLE_ADMIN')
ON CONFLICT (id) DO NOTHING;

-- Add display_name and enabled to users (backfill with safe defaults)
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS display_name TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS enabled     BOOLEAN NOT NULL DEFAULT TRUE;

-- New join table (entity-backed, replaces @ElementCollection enum strings)
CREATE TABLE IF NOT EXISTS user_role (
    user_id UUID     NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id SMALLINT NOT NULL REFERENCES role(id),
    PRIMARY KEY (user_id, role_id)
);

-- Migrate existing user_roles (enum string column) -> user_role (FK join table)
INSERT INTO user_role (user_id, role_id)
SELECT ur.user_id, r.id
FROM   user_roles ur
JOIN   role r ON r.name = ur.role
ON CONFLICT DO NOTHING;

-- Drop the old enum-string collection table
DROP TABLE IF EXISTS user_roles;
