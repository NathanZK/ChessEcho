
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
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    chess_account_id UUID         NOT NULL REFERENCES chess_account (id),
    platform_game_id VARCHAR(255) NOT NULL,
    pgn             TEXT         NOT NULL,
    time_control    VARCHAR(50),
    played_at       TIMESTAMP,
    result          VARCHAR(10),
    white_username  VARCHAR(255),
    black_username  VARCHAR(255),
    created_at      TIMESTAMP        DEFAULT now(),
    UNIQUE (chess_account_id, platform_game_id)
);

CREATE INDEX idx_game_chess_account_id ON game (chess_account_id);

CREATE TABLE position
(
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    position_hash VARCHAR(64) NOT NULL UNIQUE,
    fen           TEXT        NOT NULL,
    created_at    TIMESTAMP        DEFAULT now()
);

CREATE INDEX idx_position_hash ON position (position_hash);

CREATE TABLE position_occurrence
(
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    game_id     UUID         NOT NULL REFERENCES game (id),
    position_id UUID         NOT NULL REFERENCES position (id),
    ply_number  INT          NOT NULL,
    move_played VARCHAR(10),
    player_color VARCHAR(20) NOT NULL,
    created_at  TIMESTAMP        DEFAULT now()
);

CREATE INDEX idx_position_occurrence_position_id ON position_occurrence (position_id);
CREATE INDEX idx_position_occurrence_game_id ON position_occurrence (game_id);

CREATE TABLE engine_analysis
(
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    position_id     UUID        NOT NULL UNIQUE REFERENCES position (id),
    best_move       VARCHAR(10),
    evaluation_cp   INT,
    acceptable_moves JSONB,
    created_at      TIMESTAMP        DEFAULT now()
);
