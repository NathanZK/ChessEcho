CREATE TABLE position (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    hash VARCHAR(255) UNIQUE NOT NULL,
    fen TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE position_occurrence (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    game_id UUID NOT NULL REFERENCES game(id) ON DELETE CASCADE,
    position_id UUID NOT NULL REFERENCES position(id) ON DELETE CASCADE,
    chess_account_id UUID NOT NULL REFERENCES chess_account(id) ON DELETE CASCADE,
    ply_number INT NOT NULL,
    move_played VARCHAR(20) NOT NULL,
    player_color VARCHAR(10) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_position_occurrence_game_id ON position_occurrence(game_id);
CREATE INDEX idx_position_occurrence_position_id ON position_occurrence(position_id);
CREATE INDEX idx_position_occurrence_chess_account_id ON position_occurrence(chess_account_id);
