# Tactical Motif Evaluation (Stages 1 & 2) Plan

spec: docs/staging/specs/2026-10-25-tactical-motif-evaluation.md
milestone: Stage 1 & Stage 2 Prototype (Issue #607)

- [x] T1: Deterministic fixture generation and independent detector validation suite
goal:       Implement Python oracle detectors (fork_N, back_rank_mate), detector test cases (>=5 positive, >=5 negative), and fixture generator script enforcing leakage controls to produce frozen candidate pools JSON with recorded SHA-256.
files:      scripts/generate_tactical_motif_fixtures.py, scripts/verify_tactical_motif_detectors.py, scripts/tests/test_tactical_motif_detectors.py, src/test/resources/tactical-motif-detector-test-cases.json, src/test/resources/tactical-motif-frozen-pools.json, docs/experiments/tactical-motif-frozen-pools.sha256
acceptance: python3 -m unittest scripts/tests/test_tactical_motif_detectors.py && python3 scripts/verify_tactical_motif_detectors.py
spec:       docs/staging/specs/2026-10-25-tactical-motif-evaluation.md#contract

- [x] T2: Implement BizjakFeatureScorer with static pawns, dynamic tokens, and BM25 scoring
goal:       Implement BizjakFeatureScorer in Kotlin supporting static_positions, static_pawns, dynamic_general, and dynamic_solution tokens with BM25 scoring for STATIC_ONLY, DYNAMIC_ONLY, and COMBINED variants and deterministic canonical FEN tie-breaking.
files:      src/main/kotlin/com/chessecho/similarity/BizjakFeatureScorer.kt, src/test/kotlin/com/chessecho/similarity/BizjakFeatureScorerTest.kt
acceptance: ./gradlew test --tests '*.BizjakFeatureScorerTest' && ./gradlew ktlintCheck
spec:       docs/staging/specs/2026-10-25-tactical-motif-evaluation.md#contract

- [x] T3: Implement TacticalMotifBenchmarkRunner and execute Stage 1 & Stage 2 evaluations
goal:       Implement TacticalMotifBenchmarkRunner, enforce pool SHA-256 programmatically, score the 4 configurations (baseline_ganguly_static, bizjak_static_only, bizjak_dynamic_only, bizjak_combined), and serialize full per-query rankings and macro metrics to docs/experiments/tactical-motif-v1-results.json.
files:      src/main/kotlin/com/chessecho/similarity/TacticalMotifBenchmarkRunner.kt, src/test/kotlin/com/chessecho/similarity/TacticalMotifBenchmarkRunnerTest.kt, docs/experiments/tactical-motif-v1-results.json
acceptance: ./gradlew test --tests '*.TacticalMotifBenchmarkRunnerTest'
spec:       docs/staging/specs/2026-10-25-tactical-motif-evaluation.md#contract

- [x] T4: Full validation, adversarial review, and report generation
goal:       Execute complete backend validation, verify outputs, reproducibility, and paper fidelity, conduct adversarial review, and generate the final evidence-backed result report.
files:      docs/experiments/tactical-motif-v1-results.json
acceptance: ./gradlew ktlintCheck && ./gradlew test
spec:       docs/staging/specs/2026-10-25-tactical-motif-evaluation.md#test

- [x] T5: Specification completion and baseline preservation
goal:       Complete durable algorithm specification under docs/experiments/, recording feature vocabularies, BM25 scoring, exact baseline results, reproducibility protocol, commit SHA reconciliation, and paper fidelity deviations.
files:      docs/experiments/2026-10-25-bizjak-tactical-motif-v1-specification.md
acceptance: git diff --exit-code docs/experiments/tactical-motif-frozen-pools.sha256 && python3 scripts/verify_tactical_motif_detectors.py
spec:       docs/staging/specs/2026-10-25-tactical-motif-evaluation.md#contract

- [x] T6: Per-query ranking failure and success analysis
goal:       Analyze query-level rankings across all 4 configurations to explain successes and failure modes (e.g., pawn structures in back_rank_mate, dynamic token concentration, static token dilution in combined fusion).
files:      docs/experiments/tactical-motif-v1-per-query-analysis.md
acceptance: test -f docs/experiments/tactical-motif-v1-per-query-analysis.md
spec:       docs/staging/specs/2026-10-25-tactical-motif-evaluation.md#contract

- [x] T7: Independent evaluation pool construction and leakage verification
goal:       Construct an independently frozen evaluation pool from unused Lichess puzzles with verified zero overlap in puzzle IDs, canonical FENs, and game URLs, and record its SHA-256 digest.
files:      scripts/generate_independent_motif_fixtures.py, src/test/resources/tactical-motif-independent-pools.json, docs/experiments/tactical-motif-independent-pools.sha256, scripts/verify_tactical_motif_detectors.py
acceptance: python3 scripts/verify_tactical_motif_detectors.py
spec:       docs/staging/specs/2026-10-25-tactical-motif-evaluation.md#contract

- [x] T8: Assessment against ChessEcho's player-weakness goal and final validation
goal:       Evaluate whether tactical-motif retrieval signals transfer to detecting recurring tactical weaknesses across user games versus puzzle retrieval; run all validation gates; commit and push to exp/tactical-motif-v1.
files:      docs/experiments/tactical-motif-retrieval-goal-assessment.md
acceptance: ./gradlew ktlintCheck && ./gradlew test && git push origin exp/tactical-motif-v1
spec:       docs/staging/specs/2026-10-25-tactical-motif-evaluation.md#contract
