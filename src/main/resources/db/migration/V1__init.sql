CREATE TABLE app_user
(
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email      VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMP        DEFAULT now()
);

CREATE TABLE chess_account
(
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID         NOT NULL REFERENCES app_user (id),
    platform   VARCHAR(20)  NOT NULL,
    username   VARCHAR(255) NOT NULL,
    created_at TIMESTAMP        DEFAULT now(),
    UNIQUE (platform, username)
);

CREATE TABLE async_job
(
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username        VARCHAR(255) NOT NULL,
    platform        VARCHAR(20)  NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'QUEUED',
    games_imported  INT                   DEFAULT 0,
    games_skipped   INT                   DEFAULT 0,
    error_message   TEXT,
    created_at      TIMESTAMP             DEFAULT now(),
    updated_at      TIMESTAMP             DEFAULT now()
);

CREATE INDEX idx_async_job_username_status ON async_job (username, status);

CREATE TABLE game
(
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    chess_account_id UUID         NOT NULL REFERENCES chess_account (id),
    platform_game_id VARCHAR(255) NOT NULL,
    pgn              TEXT         NOT NULL,
    time_control     VARCHAR(50),
    played_at        TIMESTAMP,
    result           VARCHAR(50),
    white_username   VARCHAR(255),
    black_username   VARCHAR(255),
    created_at       TIMESTAMP        DEFAULT now(),
    UNIQUE (chess_account_id, platform_game_id)
);

CREATE INDEX idx_game_chess_account_id ON game (chess_account_id);

CREATE TABLE position
(
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    hash       VARCHAR(255) UNIQUE NOT NULL,
    fen        TEXT                NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
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
    created_at       TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_position_occurrence_game_id ON position_occurrence (game_id);
CREATE INDEX idx_position_occurrence_position_id ON position_occurrence (position_id);
CREATE INDEX idx_position_occurrence_account_color_position ON position_occurrence (chess_account_id, player_color, position_id);

CREATE TABLE engine_analysis
(
    id                 UUID PRIMARY KEY,
    position_id        UUID        NOT NULL REFERENCES position (id) ON DELETE CASCADE,
    depth              INT         NOT NULL,
    baseline_eval_cp   INT,
    baseline_eval_mate INT,
    best_move          VARCHAR(10),
    analyzed_at        TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    UNIQUE (position_id)
);

CREATE INDEX idx_engine_analysis_position_id ON engine_analysis (position_id);

CREATE TABLE engine_move_evaluation
(
    id                 UUID PRIMARY KEY,
    engine_analysis_id UUID        NOT NULL REFERENCES engine_analysis (id) ON DELETE CASCADE,
    move               VARCHAR(10) NOT NULL,
    eval_cp            INT,
    eval_loss_from_best DOUBLE PRECISION,
    UNIQUE (engine_analysis_id, move)
);

CREATE INDEX idx_engine_move_evaluation_analysis_id ON engine_move_evaluation (engine_analysis_id);

CREATE TABLE user_position_weakness
(
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    chess_account_id UUID        NOT NULL REFERENCES chess_account (id) ON DELETE CASCADE,
    position_id      UUID        NOT NULL REFERENCES position (id) ON DELETE CASCADE,
    player_color     VARCHAR(10) NOT NULL,
    mistake_count    INT         NOT NULL DEFAULT 0,
    mistake_rate     DOUBLE PRECISION,
    average_loss     DOUBLE PRECISION,
    priority         DOUBLE PRECISION,
    moves_played     TEXT,
    game_urls        TEXT,
    updated_at       TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (chess_account_id, position_id, player_color)
);

CREATE INDEX idx_user_position_weakness_query ON user_position_weakness (chess_account_id, player_color, mistake_count, priority DESC);
CREATE INDEX idx_user_position_weakness_position_id ON user_position_weakness (position_id);

CREATE TABLE user_position_stats
(
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    chess_account_id UUID        NOT NULL REFERENCES chess_account (id) ON DELETE CASCADE,
    position_id      UUID        NOT NULL REFERENCES position (id) ON DELETE CASCADE,
    player_color     VARCHAR(10) NOT NULL,
    times_reached    INT         NOT NULL DEFAULT 0,
    updated_at       TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (chess_account_id, position_id, player_color)
);

CREATE INDEX idx_user_position_stats_account_color_times_reached ON user_position_stats (chess_account_id, player_color, times_reached DESC);
