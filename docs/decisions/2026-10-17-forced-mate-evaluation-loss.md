# Forced-mate evaluation loss and weakness suppression

## Context

Stockfish evaluation scores can indicate forced checkmate rather than centipawn advantages (`mate K`).
Previously, evaluation loss calculation was restricted to centipawns (`best_move_eval_cp`, `eval_cp`), with forced-mate outcomes either defaulting to 0.0 or failing to persist mate distance in incremental analysis. Furthermore, losing-mate defense faced the horizon effect, where negative centipawns could erroneously be treated as 0.0 loss rather than inconclusive.

## Choice

1. Add root-relative `baseline_eval_mate`, `best_move_eval_mate`, and `eval_mate` columns to `V1__baseline.sql`, `EngineAnalysis`, and `MoveEvaluation`, with database check constraints and domain constructors enforcing non-zero mate and mutual exclusivity between `cp` and `mate`.
2. Normalize Stockfish post-move opponent mate scores to root perspective (`mate 0 -> +1`, `mate -K -> +(K+1)`, `mate +K -> -K`).
3. Implement an 8-case piecewise evaluation loss function bounded in [0.0, 20.0] in `EngineAnalysisService.Companion.calculateEvalLoss`. Losing mate against negative centipawns returns `null`.
4. Downstream services (`WeaknessCalculationService`, `ProgressService`, `EvaluationEvidenceProducerService`, `GameImportService`) skip null-loss occurrences and exclude them from acceptable moves without deflating mistake rates or generating false positive success events.

## Ruled out

- Flat 10.0 pawn loss penalty without schema changes: drops baseline mate distance on reload during incremental analysis, corrupting candidate evaluations.
- Treating negative centipawns as defense prolongation (loss = 0.0) after a losing-mate baseline: confuses engine search horizon with a proven tactical escape.
- Polymorphic evaluation tables: breaks SQL SUM/AVG aggregation and introduces unnecessary multi-table join overhead.
