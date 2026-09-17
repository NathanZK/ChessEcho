CREATE UNIQUE INDEX uk_app_user_email_canonical
    ON app_user (lower(email))
    WHERE email IS NOT NULL;

CREATE TABLE local_credential
(
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    app_user_id   UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    password_hash VARCHAR(255) NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_local_credential_app_user UNIQUE (app_user_id)
);

CREATE INDEX idx_local_credential_app_user ON local_credential (app_user_id);
