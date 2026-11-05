# Plan: Static Candidate Preservation & Dynamic Retrieval Ablation (Issue #722)

Reference Spec: [`docs/staging/specs/2026-11-05-candidate-preservation-ablation.md`](file:///Users/nathankebede/AntigravityProfiles/third/.gemini/antigravity/worktrees/ChessEcho/implement_issue_702_praxis/docs/staging/specs/2026-11-05-candidate-preservation-ablation.md)

## Declarative Contract & Boundaries
```
contract:   com.chessecho.research.CandidatePreservationAblationRunner
topology:   single
invariants:
  - ZERO_ENGINE_SEARCHES: 100% reuse of backend/src/test/resources/research/engine_evaluation_cache_d16_mpv1.json. Missing cache entries fail-fast immediately.
  - ZERO_PRODUCTION_CHANGES: Zero edits to backend/src/main/ or database schemas. All code strictly under backend/src/test/kotlin/com/chessecho/research/.
  - NO_MULTIPV3: Evaluation strictly uses MultiPV=1 baseline dynamic tokens.
  - SIX_ALPHA_GRID: Evaluates all six alphas: {0.00, 0.25, 0.50, 0.60, 0.75, 1.00}.
  - EXACT_CONFIGURATIONS: 109 candidate-pool configurations (103 primary configurations plus 6 intermediate unpruned Leiden community evaluations C_leiden-side,alpha for H4a/H4b decomposition).
  - SAME_SIDE_CANDIDATE_ELIGIBILITY: Same-side eligibility (sideToMove(c) == sideToMove(q) && c != q) applies strictly to evaluated candidate sets delivered to downstream scoring, not to graph construction universally.
  - GRAPH_TURN_SEPARATION_MECHANICS: System 3 retains its production unified cross-turn graph G_unified,alpha (splitBySideToMove = false) and filters opposite-turn candidates at the evaluation boundary (logging excluded counts). System 4 enforces turn constraints during graph edge construction (splitBySideToMove = true), structurally confining G_side,alpha to same-turn subgraphs.
test:       backend/src/test/kotlin/com/chessecho/research/CandidatePreservationAblationTest.kt
artifact:   backend/src/test/resources/research/candidate_preservation_ablation_results.json
```

---

## Milestone Tasks

- [x] T1: Data models and runner contract scaffold
  goal: Define ablation data hierarchy and runner interface matching the approved output schema.
  files: backend/src/test/kotlin/com/chessecho/research/CandidatePreservationAblationModels.kt
  acceptance: `./gradlew testClasses` compiles with zero errors.
  spec: docs/staging/specs/2026-11-05-candidate-preservation-ablation.md#12-output-data-schema-candidate_preservation_ablation_resultsjson

- [x] T2: Static cosine scorer parity verification test
  goal: Assert exact numerical parity between test harness static cosine evaluation and production PositionClusterSimilarityScorer across all six alphas.
  files: backend/src/test/kotlin/com/chessecho/research/StaticCosineScorerParityTest.kt
  acceptance: `./gradlew test --tests "com.chessecho.research.StaticCosineScorerParityTest"` passes with delta <= 1e-12 across standard, pawnless, and edge-case alpha fixtures.
  spec: docs/staging/specs/2026-11-05-candidate-preservation-ablation.md#system-2b-control-exhaustive-full-corpus-static-cosine-top-n-retrieval

- [x] T3: Full-corpus same-side dynamic reference engine and audit validator (System 1)
  goal: Implement same-side dynamic reference rankings over U(q) for BM25 and RSJ TF-IDF Cosine, validating positive scores and zero-score cutoff counts.
  files: backend/src/test/kotlin/com/chessecho/research/DynamicReferenceRankingEvaluator.kt
  acceptance: Unit test verifies 250 probe queries against corpus A, asserting 0 zero-score entries in R_10 and R_20, and deterministic tie-breaking.
  spec: docs/staging/specs/2026-11-05-candidate-preservation-ablation.md#3-dynamic-reference-ranking-audit--metric-definitions

- [x] T4: Direct static index and exhaustive cosine control evaluators (Systems 2A & 2B)
  goal: Implement candidate generation and retrieval metric evaluation for PositionInvertedIndex Top-N and Static Cosine Control Top-N across 5 budgets and 6 alphas.
  files: backend/src/test/kotlin/com/chessecho/research/DirectStaticRetrievalEvaluator.kt
  acceptance: Unit test verifies candidate counts as min(N, eligible candidates), self and opposite-side exclusion prior to truncation, deterministic tie-breaking (canonicalFen ascending, positionId ascending), and dynamic recall/purity calculations against System 1 across all 60 direct configurations.
  spec: docs/staging/specs/2026-11-05-candidate-preservation-ablation.md#system-2a-direct-static-top-n-inverted-index-candidate-retrieval

- [x] T5: Clustered baseline and side-constrained graph architecture evaluators (Systems 3 & 4A-4D)
  goal: Implement graph expansion, Leiden community partitioning, centroid pruning, and independent lineage tracking for Systems 3, 4A, 4B, 4C, and 4D.
  files: backend/src/test/kotlin/com/chessecho/research/GraphArchitectureAblationEvaluator.kt
  acceptance: Unit test verifies structural turn isolation in G_side,alpha, independently retains and evaluates both C_leiden-side,alpha (unpruned community) and S_side,alpha (centroid-pruned survivors) for every query and alpha, preserves the H4a/H4b loss decomposition, and computes independent BM25/TF-IDF lineage metrics.
  spec: docs/staging/specs/2026-11-05-candidate-preservation-ablation.md#system-4-side-constrained-graph-architecture-splitbysidetomove--true

- [x] T6: End-to-end ablation runner test execution and artifact serialization
  goal: Assemble and execute the full ablation experiment across all 109 configurations, serializing results to candidate_preservation_ablation_results.json.
  files: backend/src/test/kotlin/com/chessecho/research/CandidatePreservationAblationTest.kt, backend/src/test/resources/research/candidate_preservation_ablation_results.json
  acceptance: `./gradlew test --tests "com.chessecho.research.CandidatePreservationAblationTest"` completes green with 0 engine subprocess invocations, writing valid JSON artifact conforming to schema.
  spec: docs/staging/specs/2026-11-05-candidate-preservation-ablation.md#1-declarative-contract--invariants

- [x] T7: Empirical findings report synthesis and hypothesis resolution
  goal: Synthesize empirical results into the research report, evaluating hypotheses H1-H4 against measured distributions and lineage data.
  files: docs/research/issue-722-combined-similarity-report.md
  acceptance: Markdown report accurately cross-references values from candidate_preservation_ablation_results.json with zero speculative claims.
  spec: docs/staging/specs/2026-11-05-candidate-preservation-ablation.md#6-neutral-empirical-hypotheses
