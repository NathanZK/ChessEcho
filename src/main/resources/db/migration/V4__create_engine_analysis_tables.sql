-- Truncate existing data since V3 incorrectly saved positions AFTER moves rather than BEFORE
TRUNCATE position, position_occurrence CASCADE;

CREATE TABLE engine_analysis (
    id UUID PRIMARY KEY,
    position_id UUID NOT NULL REFERENCES position(id) ON DELETE CASCADE,
    depth INT NOT NULL,
    baseline_eval_cp INT,
    baseline_eval_mate INT,
    best_move VARCHAR(10),
    analyzed_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE INDEX idx_engine_analysis_position_id ON engine_analysis(position_id);

CREATE TABLE engine_move_evaluation (
    id UUID PRIMARY KEY,
    engine_analysis_id UUID NOT NULL REFERENCES engine_analysis(id) ON DELETE CASCADE,
    move VARCHAR(10) NOT NULL,
    eval_cp INT,
    eval_mate INT,
    UNIQUE (engine_analysis_id, move)
);

CREATE INDEX idx_engine_move_evaluation_analysis_id ON engine_move_evaluation(engine_analysis_id);
