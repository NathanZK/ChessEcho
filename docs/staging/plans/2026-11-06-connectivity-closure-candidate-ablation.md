# Plan: Connectivity Closure Candidate Ablation (Issue #722)

Reference Spec: [`docs/staging/specs/2026-11-06-connectivity-closure-candidate-ablation.md`](file:///Users/nathankebede/AntigravityProfiles/third/.gemini/antigravity/worktrees/ChessEcho/implement_issue_702_praxis/docs/staging/specs/2026-11-06-connectivity-closure-candidate-ablation.md)

## Declarative Contract & Boundaries
```
contract:   com.chessecho.research.ConnectivityClosureAblationRunner
topology:   single
invariants:
  - ZERO_ENGINE_SEARCHES: 100% reuse of backend/src/test/resources/research/engine_evaluation_cache_d16_mpv1.json.
  - ZERO_PRODUCTION_CHANGES: Zero edits to backend/src/main/ or database schemas. All code strictly under backend/src/test/kotlin/com/chessecho/research/.
  - REUSE_EXACT_ISSUE_722_Q250: Evaluates the exact 250 probe queries from Issue #722 (139 White, 111 Black; mean candidate universe size 5,001.80).
  - CANONICAL_POSITION_INEQUALITY: U(q) strictly requires c.canonicalFen != q.canonicalFen && c.sideToMove == q.sideToMove.
  - POSITIVE_SCORE_CANDIDATE_ELIGIBILITY: C_N(q) = TopN({c in U(q) : BM25(q, c) > 0.0}). Zero-score candidates never enter candidate pools through tie-breaking.
  - PAPER_ALIGNED_4_FIELD_TOKENS: Primary representation follows family|source_piece|target_piece|target_square. Source squares and player colors are strictly excluded.
  - BASELINE_INGESTION_REUSE: 18 baseline configurations ingested directly from candidate_preservation_ablation_results.json without re-execution.
  - EXACT_CONFIGURATIONS: 15 directly executed connectivity configurations (Systems C1, C2, C3 across 5 budgets: 50, 80, 200, 500, 1000) and 18 baseline configurations (total 33).
test:       backend/src/test/kotlin/com/chessecho/research/ConnectivityClosureAblationTest.kt
artifact:   backend/src/test/resources/research/connectivity_closure_ablation_results.json
report:     docs/research/issue-722-connectivity-closure-ablation-report.md
```

---

## Milestone Tasks

- [x] T1: Data models and extractor interface scaffold
  goal: Define ablation data hierarchy, capture diagnostic structures, and extractor contract matching §8.2 of the specification.
  files: backend/src/test/kotlin/com/chessecho/research/ConnectivityClosureAblationModels.kt
  acceptance: `./gradlew testClasses` compiles with zero errors and no collisions with CandidatePreservationAblationModels.kt.
  spec: docs/staging/specs/2026-11-06-connectivity-closure-candidate-ablation.md#82-json-output-schema-contract

- [x] T2: Paper-aligned connectivity closure extractor and unit test suite
  goal: Implement paper-aligned 4-field relational closure extractor (attack|S|T|sq, defense|S|T|sq) with chesslib piece-to-piece attack/defense geometry.
  files: backend/src/test/kotlin/com/chessecho/research/ConnectivityClosureExtractor.kt, backend/src/test/kotlin/com/chessecho/research/ConnectivityClosureExtractorTest.kt
  acceptance: `./gradlew test --tests "com.chessecho.research.ConnectivityClosureExtractorTest"` passes green, asserting pinned piece behavior, empty square rejection, scholar's mate, source square omission, target square retention, and color omission.
  spec: docs/staging/specs/2026-11-06-connectivity-closure-candidate-ablation.md#51-primary-relational-closure-extractor-contract

- [x] T3: Empty-document protocols and candidate eligibility unit test suites
  goal: Verify safe 0.0 BM25 evaluation for empty-closure positions, canonical-position inequality, positive BM25 candidate eligibility, deterministic tie-breaking, and pool exhaustion tracking.
  files: backend/src/test/kotlin/com/chessecho/research/ConnectivityEmptyDocumentTest.kt, backend/src/test/kotlin/com/chessecho/research/ConnectivityCandidateEligibilityTest.kt
  acceptance: `./gradlew test --tests "com.chessecho.research.ConnectivityEmptyDocumentTest" && ./gradlew test --tests "com.chessecho.research.ConnectivityCandidateEligibilityTest"` passes green.
  spec: docs/staging/specs/2026-11-06-connectivity-closure-candidate-ablation.md#53-positive-score-candidate-eligibility-rule

- [x] T4: Continuation capture diagnostics evaluator and verification test
  goal: Implement Diagnostics D1 (immediate major captures at ply 0) and D2 (preparatory continuation captures at ply k >= 1) with dual-layer accounting and root-player tracking over Corpus A.
  files: backend/src/test/kotlin/com/chessecho/research/ContinuationCaptureDiagnosticEvaluator.kt, backend/src/test/kotlin/com/chessecho/research/ContinuationCaptureDiagnosticTest.kt
  acceptance: `./gradlew test --tests "com.chessecho.research.ContinuationCaptureDiagnosticTest"` passes green, asserting immediate N x Q matchRate == 1.0, preparatory separation, and safe zero handling.
  spec: docs/staging/specs/2026-11-06-connectivity-closure-candidate-ablation.md#7-capture-continuation-diagnostic-events-vs-unique-positions

- [x] T5: End-to-end ablation runner, baseline ingestion, and artifact serialization
  goal: Assemble the connectivity candidate ablation runner across 15 executed configurations (C1, C2, C3 over budgets 50, 80, 200, 500, 1000) and 18 ingested baseline configurations, serializing results to JSON.
  files: backend/src/test/kotlin/com/chessecho/research/ConnectivityClosureAblationRunner.kt, backend/src/test/kotlin/com/chessecho/research/ConnectivityClosureAblationTest.kt, backend/src/test/resources/research/connectivity_closure_ablation_results.json
  acceptance: `./gradlew test --tests "com.chessecho.research.ConnectivityClosureAblationTest"` completes green with 0 engine searches, serializing valid JSON conforming to §8.2.
  spec: docs/staging/specs/2026-11-06-connectivity-closure-candidate-ablation.md#8-expected-artifacts--output-data-schema-contract

- [x] T6: Research report extension synthesis and hypothesis resolution
  goal: Document experimental findings, vocabulary distributions, candidate overlap Jaccard, scaling curves, and capture correspondence in the research report extension.
  files: docs/research/issue-722-connectivity-closure-ablation-report.md
  acceptance: Markdown report accurately cross-references values from connectivity_closure_ablation_results.json with zero speculative claims.
  spec: docs/staging/specs/2026-11-06-connectivity-closure-candidate-ablation.md#2-neutral-falsifiable-hypotheses
