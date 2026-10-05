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
    platform   VARCHAR(20)  NOT NULL,
    username   VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_chess_account_platform CHECK (platform = btrim(platform) AND platform IN ('CHESS_COM')),
    CONSTRAINT ck_chess_account_username CHECK (username = btrim(username) AND btrim(username) <> '')
);

CREATE UNIQUE INDEX uk_chess_account_platform_username_ci
    ON chess_account (lower(platform), lower(username));
CREATE INDEX idx_chess_account_platform_username ON chess_account (platform, username);

CREATE TABLE account_connection
(
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    app_user_id      UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    chess_account_id UUID NOT NULL REFERENCES chess_account (id) ON DELETE CASCADE,
    connected_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_account_connection_user_account UNIQUE (app_user_id, chess_account_id),
    CONSTRAINT uk_account_connection_user UNIQUE (app_user_id)
);

CREATE INDEX idx_account_connection_account ON account_connection (chess_account_id);

CREATE TABLE async_job
(
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- #457: NO ACTION, not CASCADE/SET NULL. There is no AppUser deletion path today, and no
    -- policy authorizes destroying job audit records or silently re-attributing them to a guest.
    app_user_id          UUID REFERENCES app_user (id) ON DELETE NO ACTION,
    chess_account_id     UUID REFERENCES chess_account (id) ON DELETE SET NULL,
    username             VARCHAR(255) NOT NULL,
    platform             VARCHAR(20)  NOT NULL,
    status               VARCHAR(20)  NOT NULL DEFAULT 'QUEUED',
    games_imported       INT         NOT NULL DEFAULT 0,
    games_skipped        INT         NOT NULL DEFAULT 0,
    games_processed      INT         NOT NULL DEFAULT 0,
    analysis_status      VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED',
    error_message        TEXT,
    failed_position_ids  TEXT,
    from_date            VARCHAR(7),
    to_date              VARCHAR(7),
    time_controls_csv    VARCHAR(64),
    player_color         VARCHAR(10),
    analysis_multi_pv    INT,
    max_eligible_games      INT,
    eligible_games_selected INT         NOT NULL DEFAULT 0,
    configuration_state  VARCHAR(20) NOT NULL DEFAULT 'UNRESOLVED',
    worker_token         UUID,
    lease_expires_at     TIMESTAMP WITH TIME ZONE,
    started_at           TIMESTAMP WITH TIME ZONE,
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
    CONSTRAINT ck_async_job_analysis_multi_pv
        CHECK (analysis_multi_pv IS NULL OR analysis_multi_pv > 0),
    CONSTRAINT ck_async_job_max_eligible_games
        CHECK (max_eligible_games IS NULL OR max_eligible_games > 0),
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
CREATE INDEX idx_async_job_processing_lease ON async_job (status, lease_expires_at);
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
    CONSTRAINT uk_position_occurrence_identity UNIQUE (game_id, position_id, ply_number, player_color),
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

ALTER TABLE game
    ADD COLUMN imported_archive_id UUID REFERENCES imported_archive (id) ON DELETE SET NULL;

CREATE INDEX idx_game_imported_archive_id ON game (imported_archive_id);

CREATE TABLE archive_derived_processing
(
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    imported_archive_id UUID NOT NULL REFERENCES imported_archive (id) ON DELETE CASCADE,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count       INT NOT NULL DEFAULT 0,
    last_error          TEXT,
    worker_token        UUID,
    started_at          TIMESTAMP WITH TIME ZONE,
    lease_expires_at    TIMESTAMP WITH TIME ZONE,
    completed_at        TIMESTAMP WITH TIME ZONE,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_archive_derived_processing_archive UNIQUE (imported_archive_id),
    CONSTRAINT ck_archive_derived_processing_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_archive_derived_processing_attempts CHECK (attempt_count >= 0)
);

CREATE INDEX idx_archive_derived_processing_status ON archive_derived_processing (status, updated_at);
CREATE INDEX idx_archive_derived_processing_archive ON archive_derived_processing (imported_archive_id);

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

-- Issue #423: run-scoped, immutable reference-corpus provenance for E6 nested
-- checkpoints. Independent of human_move_distribution and
-- human_move_bfs_seen_game, which keep their legacy semantics.
CREATE TABLE human_move_corpus_run
(
    id                          UUID PRIMARY KEY,
    rating_band                 VARCHAR(20)  NOT NULL,
    seed_players                TEXT         NOT NULL,
    excluded_players            TEXT         NOT NULL,
    max_qualifying_games        INT,
    max_games_per_player        INT          NOT NULL,
    max_players                 INT,
    max_depth                   INT,
    batch_size                  INT          NOT NULL,
    algorithm_version           VARCHAR(64)  NOT NULL,
    source_revision             VARCHAR(255) NOT NULL,
    request_json                TEXT         NOT NULL,
    request_sha256              VARCHAR(64)  NOT NULL,
    status                      VARCHAR(20)  NOT NULL,
    committed_frontier          INT          NOT NULL DEFAULT 0,
    rejected_game_count         INT          NOT NULL DEFAULT 0,
    archive_fetch_failure_count INT          NOT NULL DEFAULT 0,
    stop_reason                 VARCHAR(64),
    failure_details             TEXT,
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at                 TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_human_move_corpus_run_rating_band CHECK (rating_band IN (
        '400-600', '600-800', '800-1000', '1000-1200', '1200-1400',
        '1400-1600', '1600-1800', '1800-2000', '2000-2200', '2200+')),
    CONSTRAINT ck_human_move_corpus_run_status CHECK (status IN ('RUNNING', 'COMPLETED', 'INCOMPLETE', 'FAILED')),
    CONSTRAINT ck_human_move_corpus_run_committed_frontier CHECK (committed_frontier >= 0),
    CONSTRAINT ck_human_move_corpus_run_counts CHECK (rejected_game_count >= 0 AND archive_fetch_failure_count >= 0),
    CONSTRAINT ck_human_move_corpus_run_request_sha256 CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_human_move_corpus_run_source_revision CHECK (length(trim(source_revision)) > 0),
    CONSTRAINT ck_human_move_corpus_run_finished CHECK ((status = 'RUNNING') = (finished_at IS NULL))
);

CREATE TABLE human_move_corpus_game
(
    id                  UUID PRIMARY KEY,
    run_id              UUID          NOT NULL REFERENCES human_move_corpus_run (id) ON DELETE RESTRICT,
    qualifying_ordinal  INT           NOT NULL,
    provider_game_id    VARCHAR(2048) NOT NULL,
    traversed_player    VARCHAR(255)  NOT NULL,
    opponent            VARCHAR(255)  NOT NULL,
    opponent_side       VARCHAR(5)    NOT NULL,
    opponent_rating     INT           NOT NULL,
    rules               VARCHAR(64),
    time_class          VARCHAR(32)   NOT NULL,
    bfs_depth           INT           NOT NULL,
    pgn                 TEXT          NOT NULL,
    pgn_sha256          VARCHAR(64)   NOT NULL,
    observation_total   INT           NOT NULL,
    distinct_move_count INT           NOT NULL,
    committed_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_human_move_corpus_game_provider UNIQUE (run_id, provider_game_id),
    CONSTRAINT uk_human_move_corpus_game_ordinal UNIQUE (run_id, qualifying_ordinal),
    CONSTRAINT ck_human_move_corpus_game_qualifying_ordinal CHECK (qualifying_ordinal >= 1),
    CONSTRAINT ck_human_move_corpus_game_opponent_side CHECK (opponent_side IN ('WHITE', 'BLACK')),
    CONSTRAINT ck_human_move_corpus_game_bfs_depth CHECK (bfs_depth >= 0),
    CONSTRAINT ck_human_move_corpus_game_observation_total CHECK (observation_total >= 1),
    CONSTRAINT ck_human_move_corpus_game_distinct_move_count CHECK (distinct_move_count >= 1)
);

-- uk_human_move_corpus_observation_move (leading game_id) also serves per-game lookups.
CREATE TABLE human_move_corpus_observation
(
    id                UUID PRIMARY KEY,
    game_id           UUID         NOT NULL REFERENCES human_move_corpus_game (id) ON DELETE RESTRICT,
    position_id       UUID         NOT NULL REFERENCES position (id) ON DELETE RESTRICT,
    position_hash     VARCHAR(255) NOT NULL,
    move_played       VARCHAR(20)  NOT NULL,
    observation_count INT          NOT NULL,
    CONSTRAINT uk_human_move_corpus_observation_move UNIQUE (game_id, position_hash, move_played),
    CONSTRAINT ck_human_move_corpus_observation_observation_count CHECK (observation_count >= 1)
);

CREATE INDEX idx_human_move_corpus_observation_position ON human_move_corpus_observation (position_id);

-- Issue #426: independent, imported research evidence and scoped operational projections.
-- These never contribute to the legacy band-wide distribution.
CREATE TABLE human_move_corpus_import
(
    source_run_id      UUID PRIMARY KEY,
    rating_band        VARCHAR(20) NOT NULL,
    algorithm_version  VARCHAR(64) NOT NULL,
    source_revision    VARCHAR(255) NOT NULL,
    request_sha256     VARCHAR(64) NOT NULL,
    request_json       TEXT NOT NULL,
    raw_available      BOOLEAN NOT NULL DEFAULT TRUE,
    imported_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE human_move_corpus_artifact_snapshot
(
    content_digest    VARCHAR(64) PRIMARY KEY,
    source_run_id     UUID NOT NULL REFERENCES human_move_corpus_import (source_run_id),
    covered_prefix    INT NOT NULL CHECK (covered_prefix >= 1),
    manifest_json     TEXT NOT NULL,
    verified_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_corpus_snapshot_run ON human_move_corpus_artifact_snapshot (source_run_id);

-- Issue #431: lossless qualifying move locations. These rows are retained
-- independently of #426 imported raw rows and the fixed v1 artifact format.
CREATE TABLE human_move_corpus_occurrence
(
    id                  UUID PRIMARY KEY,
    source_run_id       UUID NOT NULL REFERENCES human_move_corpus_run (id) ON DELETE RESTRICT,
    qualifying_ordinal  INT NOT NULL CHECK (qualifying_ordinal >= 1),
    provider_game_id    VARCHAR(2048) NOT NULL,
    pre_move_ply        INT NOT NULL CHECK (pre_move_ply >= 1),
    move_played         VARCHAR(20) NOT NULL CHECK (length(trim(move_played)) > 0),
    position_hash       VARCHAR(255) NOT NULL CHECK (length(trim(position_hash)) > 0),
    content_digest      VARCHAR(64) REFERENCES human_move_corpus_artifact_snapshot (content_digest) ON DELETE RESTRICT,
    covered_prefix      INT,
    CONSTRAINT uk_human_move_corpus_occurrence_location UNIQUE (source_run_id, qualifying_ordinal, pre_move_ply),
    CONSTRAINT ck_human_move_corpus_occurrence_binding CHECK (
        (content_digest IS NULL AND covered_prefix IS NULL)
            OR (content_digest ~ '^[0-9a-f]{64}$' AND covered_prefix IS NOT NULL AND covered_prefix >= qualifying_ordinal)
    )
);
CREATE INDEX idx_human_move_corpus_occurrence_position
    ON human_move_corpus_occurrence (source_run_id, position_hash, move_played);

CREATE TABLE human_move_corpus_occurrence_binding
(
    id                 UUID PRIMARY KEY,
    source_run_id      UUID NOT NULL UNIQUE REFERENCES human_move_corpus_run (id) ON DELETE RESTRICT,
    content_digest     VARCHAR(64) NOT NULL REFERENCES human_move_corpus_artifact_snapshot (content_digest) ON DELETE RESTRICT,
    covered_prefix     INT NOT NULL CHECK (covered_prefix >= 1),
    occurrence_digest  VARCHAR(64) NOT NULL CHECK (occurrence_digest ~ '^[0-9a-f]{64}$'),
    occurrence_count   INT NOT NULL CHECK (occurrence_count > 0),
    finalized_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE human_move_corpus_imported_game
(
    id                  UUID PRIMARY KEY,
    source_run_id       UUID NOT NULL REFERENCES human_move_corpus_import (source_run_id),
    qualifying_ordinal  INT NOT NULL CHECK (qualifying_ordinal >= 1),
    provider_game_id    VARCHAR(2048) NOT NULL,
    traversed_player    VARCHAR(255) NOT NULL,
    opponent            VARCHAR(255) NOT NULL,
    opponent_side       VARCHAR(5) NOT NULL,
    opponent_rating     INT NOT NULL,
    rules               VARCHAR(64),
    time_class          VARCHAR(32) NOT NULL,
    bfs_depth           INT NOT NULL,
    pgn                 TEXT NOT NULL,
    pgn_sha256          VARCHAR(64) NOT NULL,
    observation_total   INT NOT NULL,
    distinct_move_count INT NOT NULL,
    committed_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_corpus_imported_game_ordinal UNIQUE (source_run_id, qualifying_ordinal),
    CONSTRAINT uk_corpus_imported_game_provider UNIQUE (source_run_id, provider_game_id)
);

CREATE TABLE human_move_corpus_imported_observation
(
    id                UUID PRIMARY KEY,
    game_id           UUID NOT NULL REFERENCES human_move_corpus_imported_game (id) ON DELETE CASCADE,
    position_id       UUID NOT NULL REFERENCES position (id),
    position_hash     VARCHAR(255) NOT NULL,
    move_played       VARCHAR(20) NOT NULL,
    observation_count INT NOT NULL CHECK (observation_count > 0),
    CONSTRAINT uk_corpus_imported_observation UNIQUE (game_id, position_hash, move_played)
);

CREATE TABLE human_move_corpus_projection
(
    id                   UUID PRIMARY KEY,
    content_digest       VARCHAR(64) NOT NULL REFERENCES human_move_corpus_artifact_snapshot (content_digest),
    source_run_id        UUID NOT NULL REFERENCES human_move_corpus_import (source_run_id),
    prefix_n             INT NOT NULL,
    rating_band          VARCHAR(20) NOT NULL,
    min_observations     INT NOT NULL CHECK (min_observations >= 1),
    calculation_version  VARCHAR(64) NOT NULL,
    distribution_sha256  VARCHAR(64),
    finalized            BOOLEAN NOT NULL DEFAULT FALSE,
    verified             BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_corpus_projection_scope UNIQUE (content_digest, source_run_id, prefix_n, rating_band, min_observations, calculation_version)
);

CREATE TABLE human_move_corpus_projection_row
(
    id                UUID PRIMARY KEY,
    projection_id     UUID NOT NULL REFERENCES human_move_corpus_projection (id) ON DELETE CASCADE,
    position_hash     VARCHAR(255) NOT NULL,
    move_played       VARCHAR(20) NOT NULL,
    observation_count INT NOT NULL CHECK (observation_count > 0),
    CONSTRAINT uk_corpus_projection_row UNIQUE (projection_id, position_hash, move_played)
);

CREATE FUNCTION guard_human_move_corpus_run_update()
RETURNS TRIGGER AS
$$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.rating_band IS DISTINCT FROM OLD.rating_band
        OR NEW.seed_players IS DISTINCT FROM OLD.seed_players
        OR NEW.excluded_players IS DISTINCT FROM OLD.excluded_players
        OR NEW.max_qualifying_games IS DISTINCT FROM OLD.max_qualifying_games
        OR NEW.max_games_per_player IS DISTINCT FROM OLD.max_games_per_player
        OR NEW.max_players IS DISTINCT FROM OLD.max_players
        OR NEW.max_depth IS DISTINCT FROM OLD.max_depth
        OR NEW.batch_size IS DISTINCT FROM OLD.batch_size
        OR NEW.algorithm_version IS DISTINCT FROM OLD.algorithm_version
        OR NEW.source_revision IS DISTINCT FROM OLD.source_revision
        OR NEW.request_json IS DISTINCT FROM OLD.request_json
        OR NEW.request_sha256 IS DISTINCT FROM OLD.request_sha256
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'human_move_corpus_run % identity and configuration are immutable', OLD.id;
    END IF;
    IF OLD.status <> 'RUNNING' THEN
        RAISE EXCEPTION 'human_move_corpus_run % is terminal (%) and immutable', OLD.id, OLD.status;
    END IF;
    IF NEW.committed_frontier < OLD.committed_frontier THEN
        RAISE EXCEPTION 'human_move_corpus_run % committed_frontier cannot decrease', OLD.id;
    END IF;
    IF NEW.committed_frontier > OLD.committed_frontier AND (
        NEW.committed_frontier <> OLD.committed_frontier + 1
            OR NOT EXISTS (SELECT 1
                           FROM human_move_corpus_game g
                           WHERE g.run_id = NEW.id
                             AND g.qualifying_ordinal = NEW.committed_frontier
                             AND g.distinct_move_count = (SELECT count(*)
                                                          FROM human_move_corpus_observation o
                                                          WHERE o.game_id = g.id)
                             AND g.observation_total = (SELECT coalesce(sum(o.observation_count), 0)
                                                        FROM human_move_corpus_observation o
                                                        WHERE o.game_id = g.id))) THEN
        RAISE EXCEPTION 'human_move_corpus_run % committed_frontier may only advance onto its next complete game', OLD.id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_human_move_corpus_run_update
    BEFORE UPDATE ON human_move_corpus_run
    FOR EACH ROW
    EXECUTE FUNCTION guard_human_move_corpus_run_update();

CREATE FUNCTION reject_human_move_corpus_mutation()
RETURNS TRIGGER AS
$$
BEGIN
    RAISE EXCEPTION '% on % is not permitted: corpus rows are immutable', TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_human_move_corpus_run_delete
    BEFORE DELETE ON human_move_corpus_run
    FOR EACH ROW
    EXECUTE FUNCTION reject_human_move_corpus_mutation();

CREATE TRIGGER trg_human_move_corpus_game_immutable
    BEFORE UPDATE OR DELETE ON human_move_corpus_game
    FOR EACH ROW
    EXECUTE FUNCTION reject_human_move_corpus_mutation();

CREATE TRIGGER trg_human_move_corpus_observation_immutable
    BEFORE UPDATE OR DELETE ON human_move_corpus_observation
    FOR EACH ROW
    EXECUTE FUNCTION reject_human_move_corpus_mutation();

CREATE FUNCTION guard_human_move_corpus_game_insert()
RETURNS TRIGGER AS
$$
DECLARE
    run_status   VARCHAR(20);
    run_frontier INT;
BEGIN
    SELECT r.status, r.committed_frontier
    INTO run_status, run_frontier
    FROM human_move_corpus_run r
    WHERE r.id = NEW.run_id
    FOR UPDATE;
    IF NOT FOUND OR run_status <> 'RUNNING' THEN
        RAISE EXCEPTION 'human_move_corpus_run % is not RUNNING', NEW.run_id;
    END IF;
    IF NEW.qualifying_ordinal <> run_frontier + 1 THEN
        RAISE EXCEPTION 'qualifying_ordinal % is not the next contiguous ordinal % of run %',
            NEW.qualifying_ordinal, run_frontier + 1, NEW.run_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_human_move_corpus_game_insert
    BEFORE INSERT ON human_move_corpus_game
    FOR EACH ROW
    EXECUTE FUNCTION guard_human_move_corpus_game_insert();

CREATE FUNCTION guard_human_move_corpus_observation_insert()
RETURNS TRIGGER AS
$$
DECLARE
    game_ordinal INT;
    run_status   VARCHAR(20);
    run_frontier INT;
BEGIN
    SELECT g.qualifying_ordinal, r.status, r.committed_frontier
    INTO game_ordinal, run_status, run_frontier
    FROM human_move_corpus_game g
             JOIN human_move_corpus_run r ON r.id = g.run_id
    WHERE g.id = NEW.game_id
    FOR UPDATE OF r;
    IF NOT FOUND OR run_status <> 'RUNNING' OR game_ordinal <= run_frontier THEN
        RAISE EXCEPTION 'observations may only be added to the uncommitted game being written (game %)', NEW.game_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_human_move_corpus_observation_insert
    BEFORE INSERT ON human_move_corpus_observation
    FOR EACH ROW
    EXECUTE FUNCTION guard_human_move_corpus_observation_insert();

CREATE FUNCTION guard_human_move_corpus_occurrence_insert()
RETURNS TRIGGER AS
$$
DECLARE
    game_run_id UUID;
    game_ordinal INT;
    game_provider_id VARCHAR(2048);
    run_status VARCHAR(20);
    run_frontier INT;
BEGIN
    IF NEW.content_digest IS NOT NULL THEN
        RAISE EXCEPTION 'new occurrence evidence cannot be pre-bound';
    END IF;
    SELECT g.run_id, g.qualifying_ordinal, g.provider_game_id, r.status, r.committed_frontier
    INTO game_run_id, game_ordinal, game_provider_id, run_status, run_frontier
    FROM human_move_corpus_game g
    JOIN human_move_corpus_run r ON r.id = g.run_id
    WHERE g.run_id = NEW.source_run_id AND g.qualifying_ordinal = NEW.qualifying_ordinal
    FOR UPDATE OF r;
    IF NOT FOUND OR run_status <> 'RUNNING' OR game_ordinal <= run_frontier
        OR game_provider_id IS DISTINCT FROM NEW.provider_game_id THEN
        RAISE EXCEPTION 'occurrence does not belong to the uncommitted source game';
    END IF;
    IF EXISTS (SELECT 1 FROM human_move_corpus_occurrence_binding b WHERE b.source_run_id = NEW.source_run_id) THEN
        RAISE EXCEPTION 'occurrence evidence is already bound for source run %', NEW.source_run_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_human_move_corpus_occurrence_insert
    BEFORE INSERT ON human_move_corpus_occurrence
    FOR EACH ROW
    EXECUTE FUNCTION guard_human_move_corpus_occurrence_insert();

CREATE FUNCTION guard_human_move_corpus_occurrence_update()
RETURNS TRIGGER AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'DELETE on human_move_corpus_occurrence is not permitted';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.source_run_id IS DISTINCT FROM OLD.source_run_id
        OR NEW.qualifying_ordinal IS DISTINCT FROM OLD.qualifying_ordinal
        OR NEW.provider_game_id IS DISTINCT FROM OLD.provider_game_id
        OR NEW.pre_move_ply IS DISTINCT FROM OLD.pre_move_ply
        OR NEW.move_played IS DISTINCT FROM OLD.move_played
        OR NEW.position_hash IS DISTINCT FROM OLD.position_hash
        OR OLD.content_digest IS NOT NULL
        OR NEW.content_digest IS NULL
        OR NEW.covered_prefix IS NULL
        OR EXISTS (SELECT 1 FROM human_move_corpus_occurrence_binding b WHERE b.source_run_id = OLD.source_run_id)
        OR NOT EXISTS (
            SELECT 1 FROM human_move_corpus_artifact_snapshot s
            WHERE s.content_digest = NEW.content_digest
              AND s.source_run_id = NEW.source_run_id
              AND s.covered_prefix = NEW.covered_prefix
        ) THEN
        RAISE EXCEPTION 'occurrence rows are immutable except for their one-time verified artifact binding';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_human_move_corpus_occurrence_immutable
    BEFORE UPDATE OR DELETE ON human_move_corpus_occurrence
    FOR EACH ROW
    EXECUTE FUNCTION guard_human_move_corpus_occurrence_update();

CREATE FUNCTION reject_human_move_corpus_occurrence_binding_mutation()
RETURNS TRIGGER AS
$$
BEGIN
    RAISE EXCEPTION '% on human_move_corpus_occurrence_binding is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_human_move_corpus_occurrence_binding_immutable
    BEFORE UPDATE OR DELETE ON human_move_corpus_occurrence_binding
    FOR EACH ROW
    EXECUTE FUNCTION reject_human_move_corpus_occurrence_binding_mutation();

CREATE FUNCTION verify_human_move_corpus_occurrence_binding()
RETURNS TRIGGER AS
$$
DECLARE
    actual_count BIGINT;
    expected_count BIGINT;
    source_games BIGINT;
    source_min INT;
    source_max INT;
BEGIN
    SELECT COUNT(*)
    INTO actual_count
    FROM human_move_corpus_occurrence o
    JOIN human_move_corpus_game g
      ON g.run_id = o.source_run_id
     AND g.qualifying_ordinal = o.qualifying_ordinal
     AND g.provider_game_id = o.provider_game_id
    WHERE o.source_run_id = NEW.source_run_id
      AND o.qualifying_ordinal <= NEW.covered_prefix
      AND o.content_digest = NEW.content_digest
      AND o.covered_prefix = NEW.covered_prefix;
    SELECT COALESCE(SUM(observation_total), 0)
    INTO expected_count
    FROM human_move_corpus_game
    WHERE run_id = NEW.source_run_id AND qualifying_ordinal <= NEW.covered_prefix;
    SELECT COUNT(*), COALESCE(MIN(qualifying_ordinal), 0), COALESCE(MAX(qualifying_ordinal), 0)
    INTO source_games, source_min, source_max
    FROM human_move_corpus_game
    WHERE run_id = NEW.source_run_id AND qualifying_ordinal <= NEW.covered_prefix;
    IF source_games <> NEW.covered_prefix OR source_min <> 1 OR source_max <> NEW.covered_prefix
        OR actual_count <> NEW.occurrence_count OR actual_count <> expected_count
        OR EXISTS (
            SELECT 1
            FROM human_move_corpus_occurrence o
            JOIN human_move_corpus_game g
              ON g.run_id = o.source_run_id AND g.qualifying_ordinal = o.qualifying_ordinal
            WHERE o.source_run_id = NEW.source_run_id
              AND o.qualifying_ordinal <= NEW.covered_prefix
              AND g.provider_game_id IS DISTINCT FROM o.provider_game_id
        ) THEN
        RAISE EXCEPTION 'occurrence binding for source run % does not cover its complete verified prefix', NEW.source_run_id;
    END IF;
    IF EXISTS (
        WITH occurrence_aggregate AS (
            SELECT qualifying_ordinal, position_hash, move_played, COUNT(*)::BIGINT AS occurrence_count
            FROM human_move_corpus_occurrence
            WHERE source_run_id = NEW.source_run_id AND qualifying_ordinal <= NEW.covered_prefix
            GROUP BY qualifying_ordinal, position_hash, move_played
        ), source_aggregate AS (
            SELECT g.qualifying_ordinal, o.position_hash, o.move_played, SUM(o.observation_count)::BIGINT AS occurrence_count
            FROM human_move_corpus_observation o
            JOIN human_move_corpus_game g ON g.id = o.game_id
            WHERE g.run_id = NEW.source_run_id AND g.qualifying_ordinal <= NEW.covered_prefix
            GROUP BY g.qualifying_ordinal, o.position_hash, o.move_played
        )
        (SELECT * FROM occurrence_aggregate EXCEPT SELECT * FROM source_aggregate)
        UNION ALL
        (SELECT * FROM source_aggregate EXCEPT SELECT * FROM occurrence_aggregate)
    ) THEN
        RAISE EXCEPTION 'occurrence binding for source run % diverges from aggregate checkpoint evidence', NEW.source_run_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_human_move_corpus_occurrence_binding_insert
    BEFORE INSERT ON human_move_corpus_occurrence_binding
    FOR EACH ROW
    EXECUTE FUNCTION verify_human_move_corpus_occurrence_binding();

-- Commit-time check: a game row may only become durable together with its
-- complete observation set and the frontier advance onto its ordinal.
CREATE FUNCTION verify_human_move_corpus_game_committed()
RETURNS TRIGGER AS
$$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM human_move_corpus_game g
        JOIN human_move_corpus_run r ON r.id = g.run_id
        WHERE g.id = NEW.id
          AND g.qualifying_ordinal <= r.committed_frontier
          AND g.distinct_move_count = (
              SELECT count(*) FROM human_move_corpus_observation o WHERE o.game_id = g.id
          )
          AND g.observation_total = (
              SELECT coalesce(sum(o.observation_count), 0)
              FROM human_move_corpus_observation o
              WHERE o.game_id = g.id
          )
          AND (
              NOT EXISTS (
                  SELECT 1
                  FROM human_move_corpus_occurrence o
                  WHERE o.source_run_id = g.run_id
                    AND o.qualifying_ordinal = g.qualifying_ordinal
              )
              OR (
                  g.observation_total = (
                      SELECT count(*)
                      FROM human_move_corpus_occurrence o
                      WHERE o.source_run_id = g.run_id
                        AND o.qualifying_ordinal = g.qualifying_ordinal
                  )
                  AND NOT EXISTS (
                      (SELECT position_hash, move_played, observation_count
                       FROM human_move_corpus_observation WHERE game_id = g.id
                       EXCEPT
                       SELECT position_hash, move_played, count(*)::INT
                       FROM human_move_corpus_occurrence
                       WHERE source_run_id = g.run_id AND qualifying_ordinal = g.qualifying_ordinal
                       GROUP BY position_hash, move_played)
                      UNION ALL
                      (SELECT position_hash, move_played, count(*)::INT
                       FROM human_move_corpus_occurrence
                       WHERE source_run_id = g.run_id AND qualifying_ordinal = g.qualifying_ordinal
                       GROUP BY position_hash, move_played
                       EXCEPT
                       SELECT position_hash, move_played, observation_count
                       FROM human_move_corpus_observation WHERE game_id = g.id)
                  )
              )
          )
    ) THEN
        RAISE EXCEPTION 'human_move_corpus_game % must commit with complete aggregate and occurrence evidence',
            NEW.id;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_human_move_corpus_game_committed
    AFTER INSERT ON human_move_corpus_game
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION verify_human_move_corpus_game_committed();

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
    -- #457: NO ACTION, not CASCADE/SET NULL. SET NULL would reclassify a personal claim as a
    -- guest claim and collide under the NULLS NOT DISTINCT index below; CASCADE would destroy
    -- personal training history. Revisit only under an explicit account-erasure policy.
    app_user_id           UUID REFERENCES app_user (id) ON DELETE NO ACTION,
    chess_account_id      UUID         NOT NULL REFERENCES chess_account (id) ON DELETE CASCADE,
    position_id           UUID         NOT NULL REFERENCES position (id) ON DELETE CASCADE,
    player_color          VARCHAR(10)  NOT NULL,
    event_type            VARCHAR(32)  NOT NULL,
    position_occurrence_id UUID REFERENCES position_occurrence (id) ON DELETE CASCADE,
    occurred_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    submission_id         UUID,
    submitted_move        VARCHAR(255),
    CONSTRAINT ck_puzzle_submission CHECK (
        (submission_id IS NULL AND submitted_move IS NULL) OR
        (submission_id IS NOT NULL AND submitted_move IS NOT NULL
         AND length(trim(submitted_move)) > 0 AND submitted_move = trim(submitted_move)
         AND app_user_id IS NOT NULL AND position_occurrence_id IS NULL
         AND event_type IN ('SOLVED', 'FAILED'))
    ),
    CONSTRAINT ck_puzzle_event_player_color CHECK (player_color IN ('WHITE', 'BLACK')),
    CONSTRAINT ck_puzzle_event_type CHECK (
        event_type IN (
            'SOLVED', 'FAILED',
            'GAME_REENCOUNTERED', 'GAME_MISTAKE', 'GAME_HANDLED_SUCCESSFULLY'
        )
    )
);

-- #457: source-linked scheduling events are the import-replay claim mechanism. The claim
-- namespace is personal, so app_user_id participates in the key. NULLS NOT DISTINCT keeps the
-- pre-existing guest (app_user_id IS NULL) idempotency, which a plain nullable key would lose.
CREATE UNIQUE INDEX uk_puzzle_event_source_type
    ON puzzle_scheduling_event (app_user_id, position_occurrence_id, event_type)
    NULLS NOT DISTINCT
    WHERE position_occurrence_id IS NOT NULL;
CREATE INDEX idx_puzzle_event_account_position
    ON puzzle_scheduling_event (chess_account_id, position_id, player_color, occurred_at);
CREATE INDEX idx_puzzle_event_source
    ON puzzle_scheduling_event (position_occurrence_id);
CREATE INDEX idx_puzzle_event_user_account_position
    ON puzzle_scheduling_event (app_user_id, chess_account_id, position_id, player_color, occurred_at);
CREATE UNIQUE INDEX uk_puzzle_event_user_submission
    ON puzzle_scheduling_event (app_user_id, submission_id);

CREATE TABLE training_attempt (
    id UUID PRIMARY KEY,
    puzzle_id VARCHAR(255) NOT NULL,
    mode VARCHAR(50) NOT NULL CHECK (mode IN ('STOPWATCH', 'COUNTDOWN')),
    elapsed_ms BIGINT NOT NULL CHECK (elapsed_ms >= 0),
    allowed_ms BIGINT CHECK (allowed_ms IS NULL OR allowed_ms >= 0),
    outcome VARCHAR(50) NOT NULL CHECK (outcome IN ('SUBMITTED', 'EXPIRED', 'CANCELLED')),
    chess_account_id UUID,
    app_user_id UUID,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (chess_account_id) REFERENCES chess_account(id) ON DELETE SET NULL,
    -- #457: NO ACTION, not CASCADE/SET NULL. See app_user_id notes on puzzle_scheduling_event.
    FOREIGN KEY (app_user_id) REFERENCES app_user(id) ON DELETE NO ACTION
);

CREATE INDEX idx_puzzle_id ON training_attempt (puzzle_id);
CREATE INDEX idx_account_id ON training_attempt (chess_account_id);
CREATE INDEX idx_created_at ON training_attempt (created_at);

-- #430: minimal immutable E6 evaluation evidence. Additive to the existing
-- #426 artifact-snapshot and #431 occurrence tables; neither is modified.
CREATE TABLE evaluation_evidence_snapshot
(
    id                     UUID PRIMARY KEY,
    source_run_id          UUID NOT NULL REFERENCES human_move_corpus_run (id) ON DELETE RESTRICT,
    content_digest         VARCHAR(64) NOT NULL CHECK (content_digest ~ '^[0-9a-f]{64}$'),
    covered_prefix         INT NOT NULL CHECK (covered_prefix >= 1),
    prefix_n               INT NOT NULL CHECK (prefix_n >= 1),
    rating_band            VARCHAR(64) NOT NULL,
    min_observations       INT NOT NULL CHECK (min_observations >= 0),
    calculation_version    VARCHAR(255) NOT NULL,
    distribution_sha256    VARCHAR(64) CHECK (distribution_sha256 IS NULL OR distribution_sha256 ~ '^[0-9a-f]{64}$'),
    occurrence_evidence_id UUID,
    thresholds             DOUBLE PRECISION[] NOT NULL,
    min_mistake_count      INT NOT NULL CHECK (min_mistake_count >= 0),
    min_times_reached      INT NOT NULL CHECK (min_times_reached >= 0),
    color                  VARCHAR(10) NOT NULL CHECK (color IN ('WHITE', 'BLACK', 'BOTH')),
    platform               VARCHAR(32) NOT NULL,
    observation_window_days INT,
    source_revision        VARCHAR(255),
    engine_identity        VARCHAR(255),
    parser_identity        VARCHAR(255),
    evidence_digest        VARCHAR(64) NOT NULL CHECK (evidence_digest ~ '^[0-9a-f]{64}$'),
    roster_json            JSONB,
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_evaluation_evidence_snapshot_run UNIQUE (source_run_id)
);

CREATE TABLE evaluation_evidence_row
(
    id                  UUID PRIMARY KEY,
    snapshot_id         UUID NOT NULL REFERENCES evaluation_evidence_snapshot (id) ON DELETE RESTRICT,
    player_id           UUID NOT NULL,
    game_id             UUID NOT NULL,
    occurrence_id       UUID NOT NULL REFERENCES human_move_corpus_occurrence (id) ON DELETE RESTRICT,
    position_identity   VARCHAR(255) NOT NULL CHECK (length(trim(position_identity)) > 0),
    pre_move_ply        INT NOT NULL CHECK (pre_move_ply >= 1),
    move_played         VARCHAR(20) NOT NULL CHECK (length(trim(move_played)) > 0),
    player_color        VARCHAR(10) NOT NULL CHECK (player_color IN ('WHITE', 'BLACK')),
    loss                DOUBLE PRECISION NOT NULL CHECK (loss >= 0.0),
    engine_depth        INT NOT NULL CHECK (engine_depth > 0),
    observed_outcome    VARCHAR(10) NOT NULL CHECK (observed_outcome IN ('WIN', 'DRAW', 'LOSS')),
    objective_outcome   VARCHAR(10) CHECK (objective_outcome IN ('WEAK', 'SOUND')),
    practical_candidate BOOLEAN,
    practical_eligible  BOOLEAN,
    practical_wins      INT CHECK (practical_wins >= 0),
    practical_draws     INT CHECK (practical_draws >= 0),
    practical_losses    INT CHECK (practical_losses >= 0),
    CONSTRAINT uk_evaluation_evidence_row_game_occurrence
        UNIQUE (snapshot_id, player_id, game_id, occurrence_id),
    CONSTRAINT ck_evaluation_evidence_row_practical_complete CHECK (
        (practical_candidate IS NULL AND practical_eligible IS NULL AND practical_wins IS NULL AND
         practical_draws IS NULL AND practical_losses IS NULL) OR
        (practical_candidate IS NOT NULL AND practical_eligible IS NOT NULL AND practical_wins IS NOT NULL AND
         practical_draws IS NOT NULL AND practical_losses IS NOT NULL)
    ),
    CONSTRAINT ck_evaluation_evidence_row_eligible_candidate CHECK (NOT practical_eligible OR practical_candidate)
);

CREATE INDEX idx_evaluation_evidence_row_snapshot ON evaluation_evidence_row (snapshot_id);

CREATE FUNCTION reject_evaluation_evidence_mutation()
RETURNS TRIGGER AS
$$
BEGIN
    RAISE EXCEPTION '% on % is not permitted', TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_evaluation_evidence_snapshot_immutable
    BEFORE UPDATE OR DELETE ON evaluation_evidence_snapshot
    FOR EACH ROW
    EXECUTE FUNCTION reject_evaluation_evidence_mutation();

CREATE TRIGGER trg_evaluation_evidence_row_immutable
    BEFORE UPDATE OR DELETE ON evaluation_evidence_row
    FOR EACH ROW
    EXECUTE FUNCTION reject_evaluation_evidence_mutation();
