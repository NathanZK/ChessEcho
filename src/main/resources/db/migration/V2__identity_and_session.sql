-- Issue #113 — persistent identity and server-side session foundation.
-- Additive only; V1__init.sql is never edited.

-- Identity: decouple the internal identity from email so a provider-neutral
-- principal can exist without one. UNIQUE(email) is retained (Postgres permits
-- multiple NULLs).
ALTER TABLE app_user ALTER COLUMN email DROP NOT NULL;

-- Provider-neutral external identity, uniquely resolved by (issuer, subject) and
-- linked to the immutable app_user. email_snapshot is optional metadata only and
-- never keys the identity or triggers a merge.
CREATE TABLE auth_identity
(
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    app_user_id    UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    issuer         VARCHAR(255) NOT NULL,
    subject        VARCHAR(255) NOT NULL,
    email_snapshot VARCHAR(255),
    email_verified BOOLEAN,
    created_at     TIMESTAMP    NOT NULL DEFAULT now(),
    last_seen_at   TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uk_auth_identity_issuer_subject UNIQUE (issuer, subject)
);
CREATE INDEX idx_auth_identity_app_user ON auth_identity (app_user_id);

-- Opaque server-side session. Only the SHA-256 hex of the opaque secret is
-- stored; the raw secret lives solely in the HttpOnly cookie. Lifecycle is
-- enforced by idle + absolute expiry and explicit revocation.
CREATE TABLE auth_session
(
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    token_hash          VARCHAR(64)  NOT NULL UNIQUE,
    app_user_id         UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    dev_principal       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP    NOT NULL DEFAULT now(),
    last_seen_at        TIMESTAMP    NOT NULL DEFAULT now(),
    idle_expires_at     TIMESTAMP    NOT NULL,
    absolute_expires_at TIMESTAMP    NOT NULL,
    revoked_at          TIMESTAMP
);
CREATE INDEX idx_auth_session_app_user ON auth_session (app_user_id);
CREATE INDEX idx_auth_session_expiry ON auth_session (absolute_expires_at);
