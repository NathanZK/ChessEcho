# Plan: Direct Dynamic-Token Generation Using Shallower Stockfish Search (Depth 8, MultiPV 1/3/5) - Issue #723

Reference Spec: [`docs/staging/specs/2026-11-06-depth-8-dynamic-token-generation.md`](file:///Users/nathankebede/AntigravityProfiles/third/.gemini/antigravity/worktrees/ChessEcho/implement_issue_702_praxis/docs/staging/specs/2026-11-06-depth-8-dynamic-token-generation.md) (Revision 2.2)

## Declarative Contract & Boundaries
```yaml
contract:   com.chessecho.research.Depth8MultiPvResearch
topology:   single
invariants:
  - ZERO_PRODUCTION_CHANGES: Zero edits to backend/src/main/ or production controllers/services. All code strictly under backend/src/test/kotlin/com/chessecho/research/.
  - IMMUTABLE_REFERENCE_DATA: Zero edits or regeneration to backend/src/test/resources/research/engine_evaluation_cache_d16_mpv1.json (10,252 records) or corpus_a.json (10,000 positions).
  - AUTHORITATIVE_Q250: Evaluates the exact 250 probe queries from Issue #722 (139 White, 111 Black; mean universe size 5,001.80).
  - CONFIGURATION_CACHE_ISOLATION: Three strictly separated cache files: engine_evaluation_cache_d8_mpv1.json, _mpv3.json, _mpv5.json.
  - CANONICAL_15_PARAM_PREIMAGE: SHA-256 cache key generated from exact 15-parameter specification covering engine, binary hash, depth, multipv, threads, hash, ponder, syzygy, analyseMode, hashPolicy, ext, extLimits, extTimeoutMs, tokenVersion, and normalizedFen.
  - PERSISTENT_WORKER_REUSE: SingleEngineWorker.resolveForcingPvExtension preserves subprocess streams without quit, temporarily resets MultiPV to 1 during horizon extension, and enforces a 10-second watchdog timeout.
  - DETERMINISTIC_TT_RESET: ucinewgame + isready before every root evaluation (hashPolicy: clearHash).
  - EXPERIMENTAL_CONDITIONS: 6 primary retrieval conditions (C1: MPV1_R1, C2: MPV3_R1, C3: MPV3_R13, C4: MPV5_R1, C5: MPV5_R13, C6: MPV5_R15) evaluated at 6 discrete budgets (N in {50, 80, 100, 200, 500, 1000}) against both BM25 and TF-IDF references.
  - QUALITY_COST_FRONTIER: Partitioned into token generation (nominal + extension + nodes), index construction, and query-time scoring latency.
artifacts:
  - backend/src/test/resources/research/depth8_multipv_timing_pilot_results.json
  - backend/src/test/resources/research/engine_evaluation_cache_d8_mpv1.json
  - backend/src/test/resources/research/engine_evaluation_cache_d8_mpv3.json
  - backend/src/test/resources/research/engine_evaluation_cache_d8_mpv5.json
  - backend/src/test/resources/research/depth8_multipv_retrieval_results.json
report:     docs/research/issue-723-depth8-dynamic-retrieval-report.md
```

---

## Milestone Tasks

- [ ] T1: Data models, serialization DTOs, and 15-parameter cache preimage generator
  goal: Define DTO structures for Artifact 1 (timing pilot), Artifact 2 (engine evaluation records), Artifact 3 (retrieval results), and implement the exact 15-parameter SHA-256 preimage generator.
  files: backend/src/test/kotlin/com/chessecho/research/Depth8MultiPvModels.kt, backend/src/test/kotlin/com/chessecho/research/Depth8CacheSerializationTest.kt
  acceptance: `./gradlew test --tests "com.chessecho.research.Depth8CacheSerializationTest"` passes green, asserting exact 15-parameter string formatting, normalized FEN usage, SHA-256 hex hashing, and JSON round-trip serialization matching §10 schemas.
  spec: docs/staging/specs/2026-11-06-depth-8-dynamic-token-generation.md#10-output-artifacts--serialization-schemas

- [ ] T2: Persistent worker extension resolver and transposition table policy verification
  goal: Implement persistent worker horizon continuation resolution that preserves subprocess streams (no `quit`), temporarily sets `MultiPV 1` during extension search from $B_k$, enforces 10s watchdog timeout, assigns termination reason precedence (`QUIET_STOP`, `TERMINAL`, `PLY_LIMIT`, `NODE_LIMIT`, `TIMEOUT`, `PV_EXHAUSTED`), and verifies cold-start TT resets.
  files: backend/src/test/kotlin/com/chessecho/research/Depth8EngineWorker.kt, backend/src/test/kotlin/com/chessecho/research/EngineTranspositionTablePolicyTest.kt, backend/src/test/kotlin/com/chessecho/research/Depth8HorizonExtensionTest.kt
  acceptance: `./gradlew test --tests "com.chessecho.research.EngineTranspositionTablePolicyTest" && ./gradlew test --tests "com.chessecho.research.Depth8HorizonExtensionTest"` passes green, asserting TT clearing between positions and correct bounded PV extension outcomes.
  spec: docs/staging/specs/2026-11-06-depth-8-dynamic-token-generation.md#44-engine-execution-controls--transposition-table-policy

- [ ] T3: Pre-execution timing pilot runner and feasibility verification (50 positions)
  goal: Implement and execute the 50-position stratified timing pilot (15 early-game, 25 middle-game, 10 late-game) with warm-up and cyclic configuration interleaving ([A,B,C], [B,C,A], [C,A,B]). Measure $T_{\text{nominal}}$, $T_{\text{ext}}$, $T_{\text{pos}}$, node distributions, and verify the 60-minute combined corpus feasibility gate ($\sum T_{\text{pos}} \le 2,160$ ms).
  files: backend/src/test/kotlin/com/chessecho/research/Depth8MultiPvTimingPilotRunner.kt, backend/src/test/kotlin/com/chessecho/research/Depth8MultiPvTimingPilotTest.kt, backend/src/test/resources/research/depth8_multipv_timing_pilot_results.json
  acceptance: `./gradlew test --tests "com.chessecho.research.Depth8MultiPvTimingPilotTest"` passes green, generating `depth8_multipv_timing_pilot_results.json` with status `"PASSED"` and confirming combined projected runtime $\le 60$ minutes.
  spec: docs/staging/specs/2026-11-06-depth-8-dynamic-token-generation.md#7-quality-cost-evaluation--pre-execution-timing-pilot

- [ ] T4: Corpus A offline batch generation runner for MPV 1, MPV 3, and MPV 5
  goal: Implement the parallel batch generation runner using 6 persistent workers to evaluate all 10,000 positions in Corpus A across Configurations A (MPV1), B (MPV3), and C (MPV5), populating the three isolated cache files with incremental flushes, progress tracking, and watchdog recovery.
  files: backend/src/test/kotlin/com/chessecho/research/Depth8MultiPvCorpusGenerator.kt, backend/src/test/kotlin/com/chessecho/research/Depth8MultiPvCorpusGenerationTest.kt, backend/src/test/resources/research/engine_evaluation_cache_d8_mpv1.json, backend/src/test/resources/research/engine_evaluation_cache_d8_mpv3.json, backend/src/test/resources/research/engine_evaluation_cache_d8_mpv5.json
  acceptance: `./gradlew test --tests "com.chessecho.research.Depth8MultiPvCorpusGenerationTest"` passes green, verifying all 10,000 positions are evaluated for MPV1, MPV3, and MPV5 with zero missing or corrupt records.
  spec: docs/staging/specs/2026-11-06-depth-8-dynamic-token-generation.md#10-output-artifacts--serialization-schemas

- [ ] T5: Cross-configuration Rank-1 consistency check and MultiPV contribution analysis
  goal: Evaluate Rank-1 move-1 identity rate, full PV concordance, centipawn score differences, token concordance across MPV1/3/5, vocabulary expansion, token novelty ($\text{Novelty}(K, p)$), Bizjak motif category distributions, and BM25 false-positive collision rates.
  files: backend/src/test/kotlin/com/chessecho/research/Depth8Rank1ConsistencyTest.kt, backend/src/test/kotlin/com/chessecho/research/Depth8MultiPvContributionAnalyzer.kt
  acceptance: `./gradlew test --tests "com.chessecho.research.Depth8Rank1ConsistencyTest"` passes green and outputs complete empirical concordance and novelty statistics across all 10,000 positions.
  spec: docs/staging/specs/2026-11-06-depth-8-dynamic-token-generation.md#42-cross-configuration-rank-1-consistency-check

- [ ] T6: Dynamic inverted index construction and candidate retrieval benchmark
  goal: Build dynamic inverted indices (BM25 and TF-IDF) for Conditions C1–C6 and aggregation schemes (Models A–D). Execute retrieval for Q250 across budgets $N \in \{50, 80, 100, 200, 500, 1000\}$ against fixed depth-16 BM25 and TF-IDF ground truth. Measure Recall@10/20, Precision@10/N, MRR_top1, MRR_firstRef, pool exhaustion, and asymmetric diagnostic querying.
  files: backend/src/test/kotlin/com/chessecho/research/Depth8DynamicInvertedIndex.kt, backend/src/test/kotlin/com/chessecho/research/Depth8MultiPvRetrievalBenchmarkTest.kt, backend/src/test/resources/research/depth8_multipv_retrieval_results.json
  acceptance: `./gradlew test --tests "com.chessecho.research.Depth8MultiPvRetrievalBenchmarkTest"` passes green, producing `depth8_multipv_retrieval_results.json` matching the Artifact 3 schema with all metrics populated.
  spec: docs/staging/specs/2026-11-06-depth-8-dynamic-token-generation.md#6-corrected-retrieval-evaluation--fixed-ground-truth

- [ ] T7: Diagnostic research report authoring and GitHub Issue #723 disposition
  goal: Author evidence-backed research report synthesizing findings across Hypotheses H1, H2, and H3; compare depth-8 retrieval against static baselines (Systems 3, C1, C2); publish quality-cost frontier tables; and post final comment to GitHub Issue #723.
  files: docs/research/issue-723-depth8-dynamic-retrieval-report.md
  acceptance: `docs/research/issue-723-depth8-dynamic-retrieval-report.md` exists on disk with complete empirical tables, and GitHub Issue #723 comment is posted.
  spec: docs/staging/specs/2026-11-06-depth-8-dynamic-token-generation.md#11-execution-sequence--phasing
