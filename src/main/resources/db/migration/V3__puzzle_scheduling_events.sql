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
