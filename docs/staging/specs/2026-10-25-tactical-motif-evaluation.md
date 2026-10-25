# Tactical Motif Evaluation (Stages 1 & 2)

milestone: Stage 1 & Stage 2 Prototype (Issue #607)

contract:
  - `docs/experiments/2026-10-25-bizjak-tactical-motif-v1-specification.md`: authoritative algorithm, vocabulary, BM25 scoring contract, and evaluation limitation specification committed before scoring.
  - `scripts/tactical_motif_detectors.py`: independent Python oracle detectors (`fork_N` evaluated at solution ply 1 with legal move replay; `back_rank_mate` evaluated at terminal ply with checkmate on back rank; malformed/illegal positions return `False`).
  - `scripts/tests/test_tactical_motif_detectors.py`: unittest test suite verifying detectors against $\ge 5$ verified positive and $\ge 5$ verified negative examples per motif, plus malformed FEN and illegal move edge cases.
  - `src/test/resources/tactical-motif-detector-test-cases.json`: pre-validated test cases for oracle detectors.
  - `scripts/verify_tactical_motif_detectors.py`: programmatic verification tool asserting detector correctness against test cases. Exits non-zero on failure.
  - `com.chessecho.similarity.FenPositionSimilarity`: evaluated unchanged (commit `8793d58`) as `baseline_ganguly_static` (query extracts static positions without reachability; candidate document extracts static positions with fractional reachability term frequency contributing to length normalization).
  - `com.chessecho.similarity.BizjakFeatureScorer`: new Kotlin component supporting:
    - `enum class ScoringVariant { STATIC_ONLY, DYNAMIC_ONLY, COMBINED }`
    - `data class TacticalPuzzleDocument(val puzzleId: String, val canonicalFen: String, val fen: String, val moves: List<String>, val isQuery: Boolean = false)`
    - `data class ScoredTacticalMatch(val puzzleId: String, val canonicalFen: String, val score: Double)`
    - `fun rank(query: TacticalPuzzleDocument, candidates: Collection<TacticalPuzzleDocument>): List<ScoredTacticalMatch>`
    - `fun extractTerms(puzzle: TacticalPuzzleDocument): Map<String, Double>`
    - Explicit handling: throws `IllegalArgumentException` on malformed FEN, illegal move syntax, or empty candidate collections.
  - `com.chessecho.similarity.TacticalMotifBenchmarkRunner`: Kotlin benchmark harness executing the 4 evaluation configurations (`baseline_ganguly_static`, `bizjak_static_only`, `bizjak_dynamic_only`, `bizjak_combined`), verifying corpus SHA-256 before scoring, calculating per-query and macro metrics (Hit@1, Hit@5, Hit@10, MRR), and writing results to `docs/experiments/tactical-motif-v1-results.json`.

invariant:
  - `FenPositionSimilarity.kt` and its tests remain unmodified from commit `8793d58`.
  - Reachability payload weights in Lucene are recognized as score multipliers on matching terms, whereas `FenPositionSimilarity` uses fractional term frequencies with query omission (length regularization). No claim of faithful reproduction of Lucene payload scoring is made.
  - `fork_N` and `back_rank_mate` are external evaluation labels and are strictly never encoded as index features, query terms, or document tokens.
  - Candidate pools are frozen with recorded SHA-256 digest before any scoring run begins; `TacticalMotifBenchmarkRunner` enforces this hash programmatically.
  - Leakage controls: candidate pools exclude any puzzle from the same `GameUrl`, exclude canonical-FEN duplicates, and exclude the query puzzle.
  - Scoring variants are evaluated on identical frozen candidate pools with deterministic ranking and ties broken by canonical FEN ascending.
  - No database schema changes, no HTTP API endpoints, no frontend edits, and no engine/Stockfish subprocess calls.
  - Prohibited claims: general position similarity, autonomous motif discovery, and solving arbitrary-FEN retrieval (#91) are explicitly prohibited from being claimed from these results alone.

test:
  - `python3 -m unittest scripts/tests/test_tactical_motif_detectors.py` and `python3 scripts/verify_tactical_motif_detectors.py` exit 0 with all positive and negative test cases passing.
  - `./gradlew ktlintCheck` passes.
  - `./gradlew test --tests '*.FenPositionSimilarityTest'` passes cleanly.

convention:
  - Kotlin 2.0 with standard library, Jackson Kotlin module, and `com.github.bhlangonijr.chesslib:1.3.7`.
  - Python 3.9.6 with `chess==1.11.2`.
  - Pure in-memory core similarity components: no Spring dependency injection, no database dependencies.
  - Repository branch `exp/tactical-motif-v1` branched from `8793d58`.
  - Pre-deployment repository state per `docs/engineering/repository-conventions.md`.

deferred:
  - Implementation of `BizjakFeatureScorer` and `TacticalMotifBenchmarkRunner` (deferred to subsequent implementation pass).
  - Corpus candidate pool extraction and scoring runs (deferred to subsequent pass).
  - Stage 3: Broader structural & decision similarity (positional patterns, pawn structures across game datasets).
  - Stage 4: Player weakness analysis across groups (#523).
  - Production database or API integration (#91 arbitrary-FEN retrieval).

## Working notes

### Pre-scoring Specification Defect Resolution
1. **BM25 Fidelity & Payload Scoring**:
   - Primary source (Bizjak §2.1.1, Ganguly 2014 §3.2) uses Lucene payload byte arrays multiplied into term score via `PayloadFunction`.
   - `FenPositionSimilarity` baseline (commit `8793d58`) uses fractional term frequencies with `includeReachability = false` for queries. Reachability acts solely as length normalization.
   - Exact Lucene behavior is unverified (unpublished code, proprietary CT-ART). Documented as an explicit approximation; faithful reproduction is not claimed.
2. **Scoring Contract for 4 Configurations**:
   - `baseline_ganguly_static`: frozen Stage 1 baseline as-is.
   - `bizjak_static_only`: static positions + static pawns.
   - `bizjak_dynamic_only`: dynamic general flags + dynamic sequence tokens.
   - `bizjak_combined`: equal-weight union of static and dynamic tokens.
3. **Evaluation Claims & Leakage**:
   - Metrics qualify retrieval in partially labeled candidate pools with unlabeled distractors.
   - Solution-path leakage documented: dynamic tokens directly encode positive class criteria.
   - Prohibited claims explicitly listed.
4. **Detector Validity**:
   - `fork_N` evaluated at solution ply 1 with legal move replay; malformed/illegal positions return `False`.
   - Pre-validation test cases created in `src/test/resources/tactical-motif-detector-test-cases.json` and verified with unit tests.
