CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE app_user
(
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email      VARCHAR(255) UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE auth_identity
(
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    app_user_id    UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    issuer         VARCHAR(255) NOT NULL,
    subject        VARCHAR(255) NOT NULL,
    email_snapshot VARCHAR(255),
    email_verified BOOLEAN,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_auth_identity_issuer_subject UNIQUE (issuer, subject),
    CONSTRAINT ck_auth_identity_issuer_subject_not_blank
        CHECK (btrim(issuer) <> '' AND btrim(subject) <> '')
);

CREATE INDEX idx_auth_identity_app_user ON auth_identity (app_user_id);
CREATE INDEX idx_auth_identity_issuer_subject ON auth_identity (issuer, subject);

CREATE TABLE auth_session
(
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    token_hash          VARCHAR(64)  NOT NULL UNIQUE,
    app_user_id         UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    dev_principal       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    idle_expires_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    absolute_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at          TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_auth_session_token_hash CHECK (token_hash ~ '^[0-9a-fA-F]{64}$'),
    CONSTRAINT ck_auth_session_idle_expiry CHECK (idle_expires_at >= created_at),
    CONSTRAINT ck_auth_session_absolute_expiry CHECK (absolute_expires_at >= idle_expires_at),
    CONSTRAINT ck_auth_session_revoked_at CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

CREATE INDEX idx_auth_session_app_user ON auth_session (app_user_id);
CREATE INDEX idx_auth_session_expiry ON auth_session (absolute_expires_at);
CREATE INDEX idx_auth_session_token_hash ON auth_session (token_hash);

CREATE TABLE chess_account
(
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID REFERENCES app_user (id) ON DELETE SET NULL,
    platform   VARCHAR(20)  NOT NULL,
    username   VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_chess_account_platform CHECK (platform = btrim(platform) AND platform IN ('CHESS_COM')),
    CONSTRAINT ck_chess_account_username CHECK (username = btrim(username) AND btrim(username) <> '')
);

CREATE UNIQUE INDEX uk_chess_account_platform_username_ci
    ON chess_account (lower(platform), lower(username));
CREATE INDEX idx_chess_account_user_id ON chess_account (user_id);
CREATE INDEX idx_chess_account_platform_username ON chess_account (platform, username);

CREATE TABLE async_job
(
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    chess_account_id     UUID REFERENCES chess_account (id) ON DELETE SET NULL,
    username             VARCHAR(255) NOT NULL,
    platform             VARCHAR(20)  NOT NULL,
    status               VARCHAR(20)  NOT NULL DEFAULT 'QUEUED',
    games_imported       INT         NOT NULL DEFAULT 0,
    games_skipped        INT         NOT NULL DEFAULT 0,
    games_processed      INT         NOT NULL DEFAULT 0,
    analysis_status      VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED',
    error_message        TEXT,
    from_date            VARCHAR(7),
    to_date              VARCHAR(7),
    time_controls_csv    VARCHAR(64),
    player_color         VARCHAR(10),
    configuration_state  VARCHAR(20) NOT NULL DEFAULT 'UNRESOLVED',
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_async_job_status
        CHECK (status IN ('QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_async_job_platform
        CHECK (platform IN ('CHESS_COM')),
    CONSTRAINT ck_async_job_username
        CHECK (username = btrim(username) AND btrim(username) <> ''),
    CONSTRAINT ck_async_job_player_color
        CHECK (player_color IS NULL OR player_color IN ('WHITE', 'BLACK', 'BOTH')),
    CONSTRAINT ck_async_job_from_date
        CHECK (from_date IS NULL OR from_date ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    CONSTRAINT ck_async_job_to_date
        CHECK (to_date IS NULL OR to_date ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    CONSTRAINT ck_async_job_date_range
        CHECK (from_date IS NULL OR to_date IS NULL OR from_date <= to_date),
    CONSTRAINT ck_async_job_time_controls
        CHECK (
            time_controls_csv IS NULL OR
            time_controls_csv IN (
                'BLITZ',
                'BULLET',
                'CLASSICAL',
                'RAPID',
                'BLITZ,BULLET',
                'BLITZ,CLASSICAL',
                'BLITZ,RAPID',
                'BULLET,CLASSICAL',
                'BULLET,RAPID',
                'CLASSICAL,RAPID',
                'BLITZ,BULLET,CLASSICAL',
                'BLITZ,BULLET,RAPID',
                'BLITZ,CLASSICAL,RAPID',
                'BULLET,CLASSICAL,RAPID',
                'BLITZ,BULLET,CLASSICAL,RAPID'
            )
        ),
    CONSTRAINT ck_async_job_configuration_state
        CHECK (configuration_state IN ('READY', 'UNRESOLVED')),
    CONSTRAINT ck_async_job_ready_configuration
        CHECK (
            configuration_state <> 'READY' OR
            (
                chess_account_id IS NOT NULL
                AND
                btrim(username) <> ''
                AND platform = 'CHESS_COM'
                AND player_color IS NOT NULL
                AND time_controls_csv IS NOT NULL
                AND btrim(time_controls_csv) <> ''
            )
        )
);

CREATE INDEX idx_async_job_chess_account_id ON async_job (chess_account_id);
CREATE INDEX idx_async_job_username_status ON async_job (lower(platform), lower(username), status);
CREATE INDEX idx_async_job_updated_at ON async_job (updated_at);
CREATE UNIQUE INDEX uk_async_job_active_account
    ON async_job (chess_account_id)
    WHERE status IN ('QUEUED', 'PROCESSING');

CREATE FUNCTION mark_async_jobs_unresolved_before_account_delete()
RETURNS TRIGGER AS
$$
BEGIN
    UPDATE async_job
    SET configuration_state = 'UNRESOLVED',
        updated_at = CURRENT_TIMESTAMP
    WHERE chess_account_id = OLD.id
      AND configuration_state = 'READY';
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_chess_account_async_jobs_unresolved
    BEFORE DELETE ON chess_account
    FOR EACH ROW
    EXECUTE FUNCTION mark_async_jobs_unresolved_before_account_delete();

CREATE TABLE game
(
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    chess_account_id UUID         NOT NULL REFERENCES chess_account (id) ON DELETE CASCADE,
    platform_game_id VARCHAR(255) NOT NULL,
    pgn              TEXT         NOT NULL,
    time_control     VARCHAR(50),
    played_at        TIMESTAMP WITH TIME ZONE,
    result           VARCHAR(50),
    white_username   VARCHAR(255),
    black_username   VARCHAR(255),
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_game_account_platform_game UNIQUE (chess_account_id, platform_game_id)
);

CREATE INDEX idx_game_chess_account_id ON game (chess_account_id);
CREATE INDEX idx_game_account_played_at ON game (chess_account_id, played_at DESC);

CREATE TABLE position
(
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    hash       VARCHAR(255) NOT NULL UNIQUE,
    fen        TEXT         NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_position_hash ON position (hash);

CREATE TABLE position_occurrence
(
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    game_id          UUID        NOT NULL REFERENCES game (id) ON DELETE CASCADE,
    position_id      UUID        NOT NULL REFERENCES position (id) ON DELETE CASCADE,
    chess_account_id UUID        NOT NULL REFERENCES chess_account (id) ON DELETE CASCADE,
    ply_number       INT         NOT NULL,
    move_played      VARCHAR(20) NOT NULL,
    player_color     VARCHAR(10) NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_position_occurrence_ply CHECK (ply_number >= 0),
    CONSTRAINT ck_position_occurrence_player_color CHECK (player_color IN ('WHITE', 'BLACK'))
);

CREATE INDEX idx_position_occurrence_game_id ON position_occurrence (game_id);
CREATE INDEX idx_position_occurrence_position_id ON position_occurrence (position_id);
CREATE INDEX idx_position_occurrence_chess_account_id ON position_occurrence (chess_account_id);
CREATE INDEX idx_position_occurrence_account_color_position
    ON position_occurrence (chess_account_id, player_color, position_id);

CREATE TABLE engine_analysis
(
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    position_id      UUID        NOT NULL REFERENCES position (id) ON DELETE CASCADE,
    depth            INT         NOT NULL,
    baseline_eval_cp INT,
    best_move        VARCHAR(10),
    best_move_eval_cp INT,
    analyzed_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_engine_analysis_depth CHECK (depth > 0),
    CONSTRAINT uk_engine_analysis_position UNIQUE (position_id)
);

CREATE INDEX idx_engine_analysis_position_id ON engine_analysis (position_id);

CREATE TABLE engine_move_evaluation
(
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    engine_analysis_id UUID             NOT NULL REFERENCES engine_analysis (id) ON DELETE CASCADE,
    move               VARCHAR(10)      NOT NULL,
    eval_cp            INT,
    eval_loss_from_best DOUBLE PRECISION,
    CONSTRAINT uk_engine_move_evaluation_analysis_move UNIQUE (engine_analysis_id, move)
);

CREATE INDEX idx_engine_move_evaluation_analysis_id ON engine_move_evaluation (engine_analysis_id);

CREATE TABLE user_position_weakness
(
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    chess_account_id UUID             NOT NULL REFERENCES chess_account (id) ON DELETE CASCADE,
    position_id      UUID             NOT NULL REFERENCES position (id) ON DELETE CASCADE,
    player_color     VARCHAR(10)      NOT NULL,
    mistake_count    INT              NOT NULL DEFAULT 0,
    mistake_rate     DOUBLE PRECISION,
    average_loss     DOUBLE PRECISION,
    priority         DOUBLE PRECISION,
    moves_played     TEXT,
    game_urls        TEXT,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_position_weakness_scope UNIQUE (chess_account_id, position_id, player_color),
    CONSTRAINT ck_user_position_weakness_color CHECK (player_color IN ('WHITE', 'BLACK', 'BOTH'))
);

CREATE INDEX idx_user_position_weakness_chess_account_id ON user_position_weakness (chess_account_id);
CREATE INDEX idx_user_position_weakness_query
    ON user_position_weakness (chess_account_id, player_color, mistake_count, priority DESC);
CREATE INDEX idx_user_position_weakness_position_id ON user_position_weakness (position_id);

CREATE TABLE user_position_stats
(
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    chess_account_id UUID        NOT NULL REFERENCES chess_account (id) ON DELETE CASCADE,
    position_id      UUID        NOT NULL REFERENCES position (id) ON DELETE CASCADE,
    player_color     VARCHAR(10) NOT NULL,
    times_reached    INT         NOT NULL DEFAULT 0,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_position_stats_scope UNIQUE (chess_account_id, position_id, player_color),
    CONSTRAINT ck_user_position_stats_color CHECK (player_color IN ('WHITE', 'BLACK', 'BOTH'))
);

CREATE INDEX idx_user_position_stats_chess_account_id ON user_position_stats (chess_account_id);
CREATE INDEX idx_user_position_stats_account_color_times_reached
    ON user_position_stats (chess_account_id, player_color, times_reached DESC);

CREATE TABLE imported_archive
(
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    chess_account_id UUID         NOT NULL REFERENCES chess_account (id) ON DELETE CASCADE,
    archive_url      VARCHAR(512) NOT NULL,
    year_month       VARCHAR(7)   NOT NULL,
    game_count       INT         NOT NULL DEFAULT 0,
    imported_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_imported_archive_account_url UNIQUE (chess_account_id, archive_url),
    CONSTRAINT ck_imported_archive_year_month CHECK (year_month ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    CONSTRAINT ck_imported_archive_game_count CHECK (game_count >= 0)
);

CREATE INDEX idx_imported_archive_chess_account_id ON imported_archive (chess_account_id);

CREATE TABLE human_move_distribution
(
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    position_id       UUID         NOT NULL REFERENCES position (id) ON DELETE CASCADE,
    rating_band       VARCHAR(20)  NOT NULL,
    move_played       VARCHAR(20)  NOT NULL,
    observation_count INT          NOT NULL DEFAULT 0,
    CONSTRAINT uk_human_move_distribution_scope UNIQUE (position_id, rating_band, move_played),
    CONSTRAINT ck_human_move_distribution_observation_count CHECK (observation_count >= 0)
);

CREATE INDEX idx_human_move_dist_pos_band ON human_move_distribution (position_id, rating_band);

CREATE TABLE human_move_bfs_seen_game
(
    game_url VARCHAR(2048) PRIMARY KEY,
    seen_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

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

CREATE TABLE puzzle_scheduling_event
(
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    chess_account_id      UUID         NOT NULL REFERENCES chess_account (id) ON DELETE CASCADE,
    position_id           UUID         NOT NULL REFERENCES position (id) ON DELETE CASCADE,
    player_color          VARCHAR(10)  NOT NULL,
    event_type            VARCHAR(32)  NOT NULL,
    position_occurrence_id UUID REFERENCES position_occurrence (id) ON DELETE CASCADE,
    occurred_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_puzzle_event_player_color CHECK (player_color IN ('WHITE', 'BLACK')),
    CONSTRAINT ck_puzzle_event_type CHECK (
        event_type IN (
            'PRESENTED', 'STARTED', 'SOLVED', 'FAILED', 'SKIPPED',
            'GAME_REENCOUNTERED', 'GAME_MISTAKE', 'GAME_HANDLED_SUCCESSFULLY'
        )
    )
);

CREATE UNIQUE INDEX uk_puzzle_event_source_type
    ON puzzle_scheduling_event (position_occurrence_id, event_type)
    WHERE position_occurrence_id IS NOT NULL;
CREATE INDEX idx_puzzle_event_account_position
    ON puzzle_scheduling_event (chess_account_id, position_id, player_color, occurred_at);
CREATE INDEX idx_puzzle_event_source
    ON puzzle_scheduling_event (position_occurrence_id);

CREATE TABLE training_attempt (
    id UUID PRIMARY KEY,
    puzzle_id VARCHAR(255) NOT NULL,
    mode VARCHAR(50) NOT NULL CHECK (mode IN ('STOPWATCH', 'COUNTDOWN')),
    elapsed_ms BIGINT NOT NULL CHECK (elapsed_ms >= 0),
    allowed_ms BIGINT CHECK (allowed_ms IS NULL OR allowed_ms >= 0),
    outcome VARCHAR(50) NOT NULL CHECK (outcome IN ('SUBMITTED', 'EXPIRED', 'CANCELLED')),
    chess_account_id UUID,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (chess_account_id) REFERENCES chess_account(id) ON DELETE SET NULL
);

CREATE INDEX idx_puzzle_id ON training_attempt (puzzle_id);
CREATE INDEX idx_account_id ON training_attempt (chess_account_id);
CREATE INDEX idx_created_at ON training_attempt (created_at);
