# Issue Authoring Examples and Traceability Guide

This guide provides concrete before-and-after examples across three distinct issue categories:
1. **Focused Bug Fix** (based on [#495](https://github.com/NathanZK/ChessEcho/issues/495))
2. **Full-Stack / UI Feature** (based on [#90](https://github.com/NathanZK/ChessEcho/issues/90))
3. **Research / Experiment** (based on [#526](https://github.com/NathanZK/ChessEcho/issues/526))

Each example demonstrates how to author clear, scanable issues with proportional detail while strictly preserving all functional behaviors, failure modes, constraints, edge cases, and verification requirements.

---

## 1. Focused Bug Fix: Stockfish Executable Path

### Context & Original Issue ([#495](https://github.com/NathanZK/ChessEcho/issues/495))
Original size: **2,512 characters (~330 words)**.
Issue #495 was already well-scoped and concise. It demonstrates that under the revised template, focused bug fixes remain brief and direct without requiring synthetic expansion or bureaucratic overhead.

### Streamlined Issue Text

```markdown
## Problem
`chess.stockfish.path` is configured from `STOCKFISH_PATH` in `application.yml` and Compose,
but every `StockfishService` process launch currently executes literal `stockfish` on `PATH`.
Deployments configuring a non-default executable path have no effect.

## Goal
All Stockfish entry points launch the configured executable and surface launch failures explicitly.

## Relevant failure modes
- A deployment sets `STOCKFISH_PATH` to a custom binary, but the service ignores it and
  runs an arbitrary binary found on `PATH` or fails silently.
- Updating only one launch path leaves other analysis operations using a different engine,
  making behavior dependent on endpoint.
- Silently falling back to `stockfish` on launch failure masks incorrect deployments.

## Repository constraints
- Deployment state: pre-deployment.
- Reuse existing `chess.stockfish.path`; do not introduce a second path setting.
- Keep limited to executable selection and tests; do not redesign process lifecycle or pin Docker packages.
- Preserve explicit error behavior; do not silently fall back.

## Acceptance criteria
1. All process creation paths in `StockfishService` use the configured path (MultiPV, baseline, single-move).
2. Property resolves from `STOCKFISH_PATH` with default fallback to `stockfish`.
3. Focused test proves configured non-default executable is invoked by each path, and missing/unlaunchable
   binary fails explicitly without falling back to `PATH`.
4. Search depth, engine threading, scheduling, and puzzle completion semantics remain unchanged.

## Validation
- Backend: `./gradlew ktlintCheck` and targeted `StockfishServiceTest` (or `./gradlew test`).
- Frontend: not applicable.
```

### Traceability and Semantic Preservation Matrix

| Requirement / Item in Original #495 | Location in Streamlined Version | Semantic Status | Notes / Justification |
|---|---|---|---|
| Problem: `STOCKFISH_PATH` ignored, hardcoded `stockfish` on PATH | `## Problem` | **Preserved** | Retained exact configuration property names and symptoms. |
| Goal: All entry points launch configured binary | `## Goal` | **Preserved** | 100% identical intent. |
| Failure Mode 1: Custom path ignored / runs wrong binary | `## Relevant failure modes` (Item 1) | **Preserved** | Concrete mechanism and consequence intact. |
| Failure Mode 2: Inconsistent engines across endpoints | `## Relevant failure modes` (Item 2) | **Preserved** | Multi-path failure condition preserved. |
| Failure Mode 3: Silent fallback masks bad deployment | `## Relevant failure modes` (Item 3) | **Preserved** | Preservation of fail-closed principle intact. |
| Constraint: Reuse existing path setting; no lifecycle redesign | `## Repository constraints` | **Preserved** | Scope boundaries and non-scope retained. |
| AC1: All process creation paths use configured path | `## Acceptance criteria` (AC1) | **Preserved** | MultiPV, baseline, single-move enumerated. |
| AC2: `STOCKFISH_PATH` property resolution & default | `## Acceptance criteria` (AC2) | **Preserved** | Default behavior testable. |
| AC3: Targeted test proving invocation and explicit error | `## Acceptance criteria` (AC3) | **Preserved** | Specific test condition preserved. |
| AC4: Non-goals (no change to depth, threading, scheduling) | `## Acceptance criteria` (AC4) | **Preserved** | Explicit boundary preserved. |
| Validation: `./gradlew ktlintCheck` and targeted tests | `## Validation` | **Preserved** | Standard targeted backend validation. |

*Conclusion:* 100% semantic preservation. No requirements were altered or lost.

---

## 2. Full-Stack / UI Feature: Line Exploration from Supplied Positions

### Context & Original Issue ([#90](https://github.com/NathanZK/ChessEcho/issues/90))
Original size: **6,425 characters (~870 words)**.
Issue #90 contained strong functional requirements, but suffered from severe cognitive bloat because every single acceptance criterion repeated identical evidence boilerplate (*"On the reviewed revision..."*, *"Record revision and setup"*, and screenshot framing rules).

### Streamlined Issue Text
Streamlined size: **2,840 characters (~380 words)** — a measured **56% reduction** in character count.

```markdown
## Problem
Users cannot analyze or practice arbitrary positions in Line Exploration without having a
detected weakness or an imported Chess.com account.

## Goal
Allow users to enter Line Exploration from a user-supplied FEN string or single-game PGN main line
from the shared header shell, with move history preserved.

## Relevant failure modes
- Discarded move history during PGN handoff causes loss of repetition-detection and history-dependent rules in chess.js.
- An off-by-one PGN selection starts exploration from a different position than the user selected.
- Reusing the puzzle workspace without distinguishing the session presents user-supplied positions as scored training puzzles.
- Adding supplied-position entry regresses existing weakness- or game-based Line Exploration flows.
- Malformed FEN or PGN crashes the board or traps the user without actionable error recovery.

## Repository constraints
- Deployment state: pre-deployment.
- Shared entry action in Header; distinct from existing tabs; do not add a fourth top-level tab.
- Entry must work without an active puzzle, detected weakness, or imported account.
- Use existing Line Exploration and continuation/evaluation services; do not build a parallel analysis engine.
- For PGN: support one game's main line; selectable by move number and side; carry SAN history through selected ply. FEN starts without prior history.
- Suppress puzzle chrome (title, feedback, nav, hints, settings, timers, outcome recording) for supplied sessions.
- Similar-position research and additional sources are out of scope.

## Acceptance criteria
1. **Shared entry point:** Header exposes "Explore a position" modal at mobile (narrow) and desktop layouts without requiring an active puzzle or account.
2. **FEN handoff:** Valid FEN entry transitions to Line Exploration at that exact position with exploration controls active.
3. **PGN selection & history:** Valid single-game PGN parses main line into selectable positions labeled by move number and side (including initial position); selecting a ply seeds Line Exploration at that position with full SAN history preserved in the chess state.
4. **Input validation:** Malformed FEN and malformed/multi-game/variation PGNs display actionable user-facing errors without modifying board or session state.
5. **Truthful exploration context:** User-supplied sessions suppress puzzle-specific UI (title, feedback cards, navigation, hints, settings, timer) and never record puzzle events or outcomes; exploration controls (continuation, evaluate move) remain active.
6. **No regression on existing entry points:** Existing puzzle- and weakness-based exploration entry points continue functioning unchanged.
7. **Shared architecture:** User-supplied positions use the existing Line Exploration state transitions and continuation/evaluation endpoints; no parallel continuation pipeline is created.

## Validation
- Frontend: `npm run lint`, `npx tsc --noEmit`, `npm run test`, and `npm run build`.
- Review evidence: per repository conventions, attach running-app visual evidence showing the mobile/desktop entry point, valid FEN handoff, and PGN ply selection in Line Exploration.
```

### Traceability and Semantic Preservation Matrix

| Requirement / Item in Original #90 | Location in Streamlined Version | Semantic Status | Notes / Justification |
|---|---|---|---|
| Problem: Cannot explore without weakness/account | `## Problem` | **Preserved** | Exact problem description preserved. |
| Goal: Header action for FEN / PGN handoff | `## Goal` | **Preserved** | Preserves core product intent. |
| Failure Mode 1: Invalid FEN/PGN crashes board | `## Relevant failure modes` (Item 5) | **Preserved** | Error recovery risk retained. |
| Failure Mode 2: Off-by-one PGN or lost history | `## Relevant failure modes` (Item 1 & 2) | **Preserved** | Repetition-rule and off-by-one risks retained. |
| Failure Mode 3: Requiring active puzzle blocks user | `## Repository constraints` (Constraint 3) | **Preserved** | Captured as core architectural constraint. |
| Failure Mode 4: Reusing puzzle chrome confuses user | `## Relevant failure modes` (Item 3) | **Preserved** | Scored puzzle presentation risk retained. |
| Failure Mode 5: Regression on existing exploration | `## Relevant failure modes` (Item 4) | **Preserved** | Regression risk retained. |
| Constraint: Header action, not 4th tab | `## Repository constraints` (Constraint 2) | **Preserved** | UI boundary constraint retained. |
| Constraint: Reuse existing mechanisms, no parallel engine | `## Repository constraints` (Constraint 4) | **Preserved** | Architectural constraint retained. |
| Constraint: 1-game PGN main line, move + side labels | `## Repository constraints` (Constraint 5) | **Preserved** | Exact PGN parsing scope retained. |
| Constraint: FEN starts without prior history | `## Repository constraints` (Constraint 5) | **Preserved** | FEN history rule retained. |
| Constraint: Suppress puzzle chrome & outcome recording | `## Repository constraints` (Constraint 6) | **Preserved** | Scope restriction retained. |
| AC1: Shared entry at mobile & desktop widths | `## Acceptance criteria` (AC1) | **Preserved** | Narrow mobile + desktop responsive check retained. |
| AC2: FEN entry lands on exact position in exploration | `## Acceptance criteria` (AC2) | **Preserved** | Handoff behavior retained. |
| AC3: PGN selection with move number/side & SAN history | `## Acceptance criteria` (AC3) | **Preserved** | Labeled plies and internal history retained. |
| AC4: Invalid FEN and PGN error handling | `## Acceptance criteria` (AC4) | **Preserved** | Actionable error display without board alteration retained. |
| AC5: Suppressed puzzle controls and outcome recording | `## Acceptance criteria` (AC5) | **Preserved** | All suppressed controls and event blocking retained. |
| AC6: Existing puzzle/weakness exploration regression | `## Acceptance criteria` (AC6) | **Preserved** | Regression coverage retained. |
| AC7: Shared state & continuation pipeline | `## Acceptance criteria` (AC7) | **Preserved** | Architecture check retained. |
| Boilerplate: *"Record revision and setup"* in AC 1–7 | `## Validation` & Shared Conventions | **Consolidated** | Intentional relocation: revision attribution is a shared repository standard in `repository-conventions.md`, not an item to repeat 7 times. |
| Boilerplate: Screenshot scope caveats in AC 1–6 | `## Validation` & Shared Conventions | **Consolidated** | Intentional relocation: what screenshots prove vs interaction tests is governed in `repository-conventions.md`. |

*Conclusion:* Independent verification confirms complete semantic preservation. All 7 functional criteria, 5 failure modes, and 6 constraints are intact. Measured character reduction is 56% (from 6,425 to 2,840 characters), achieved entirely by removing repeated boilerplate phrases.

---

## 3. Research / Experiment: FEN Similarity Retrieval Effectiveness

### Context & Original Issue ([#526](https://github.com/NathanZK/ChessEcho/issues/526))
Original size: **10,293 characters (~1,400 words)**.
Issue #526 was heavily congested because acceptance criteria embedded raw Python code blocks, specific PRNG seed numbers, serialization formatting rules (`json.dumps(..., separators=(',', ':'))`), and metric formulas.

### Streamlined Issue Text
Streamlined size: **4,620 characters (~620 words)** — a measured **55% reduction** in character count.

All scientific hypotheses, pool composition invariants, evaluation gates, and execution commands remain directly in the issue text. Low-level procedural generation details (PRNG seed integers `52220261007`/`52220261008`, 100,000 trial caps, ply range 12–40, and Python JSON byte serialization formatting) are relocated to the executable generator and verifier scripts (`generate_corpus.py`, `verify_corpus.py`) and experiment specification (e.g. `docs/experiments/<topic>.md`), preventing issue congestion while maintaining full reproducibility.

```markdown
## Problem
We lack empirical measurement of whether the `FenPositionSimilarity` implementation effectively
retrieves chess positions that share tactical and structural features, and whether ranking
stability is distinct from true retrieval effectiveness.

## Goal
Evaluate `FenPositionSimilarity` retrieval effectiveness against an objective, rule-preserving
symmetry equivalence class and matched controls, measuring Recall@10 and AP@10 against predeclared gates.

## Relevant failure modes
- Evaluator leaks query labels or symmetries into the candidate scoring loop, producing trivial or invalid rankings.
- Positive orbit transforms produce illegal chess positions or change pawn-structure invariants, corrupting ground truth.
- Controls are trivial (e.g. mismatched piece inventory or different turn), allowing retrieval to succeed on surface counts rather than position structure.
- Implementation passes determinism or stability gates while having zero discriminative retrieval power.

## Repository constraints
- Deployment state: pre-deployment.
- Immutable baseline: Branch from frozen commit #522; do not modify production Kotlin code.
- Experiment artifacts: All scripts, corpus generators, verifiers, and results reside under
  `experiment/fen-similarity-effectiveness/` plus test-only harness code. Do not open a production PR.
- Tooling: Python 3.9.6 with `chess==1.11.2` for corpus/verifier; Gradle/JUnit to invoke Kotlin similarity.
  No external APIs, paid services, or LLM judges.

## Acceptance criteria
1. **Target relation & positive orbit:** Ground truth positives are defined as the three non-identity
   rule-preserving symmetry transforms of each query (horizontal reflection, rank reflection with color swap,
   180-degree rotation with color swap). Queries must have zero castling rights and four distinct valid symmetry keys.
2. **Corpus construction & matched controls:** Deterministic corpus generation produces 24 unique query orbits.
   Candidate pools contain exactly 3 symmetry positives plus 128 matched controls (131 candidates total per query).
   Controls must share the query's full piece inventory, zero castling rights, and no en-passant target, with a
   43/85 turn split matching the positive orbit distribution so side-to-move cannot reveal labels.
3. **Independent corpus verification:** A separate verifier (with zero similarity-engine dependencies) replays
   all UCI move histories from initial board state, verifies legality, confirms transformation mappings and
   positive labels, and validates SHA-256 corpus integrity.
4. **Effectiveness metrics:** The unchanged similarity implementation ranks all 131 candidates per query.
   Evaluator computes macro Recall@10 (hits in top 10 / 3) and macro AP@10 (sum of precision at hits <= 10 / 3).
   Full-precision rankings and scores are preserved.
5. **Predeclared effectiveness gates:** Experiment passes only if macro Recall@10 >= 0.80 and macro AP@10 >= 0.50.
   Failure to meet either threshold or corpus verification failure constitutes a failed gate.
6. **Bounded conclusion & reporting:** Conclusions are strictly bounded to retrieval effectiveness for the
   declared symmetry class on this reproducible corpus (no claims of universal similarity or ground truth).
   Results are reported in one issue comment with corpus hash, evaluator hashes, exact metric outcomes, and gate decision.

## Validation
- Corpus generation: `python3 experiment/fen-similarity-effectiveness/generate_corpus.py` (records corpus SHA-256).
- Independent verification: `python3 experiment/fen-similarity-effectiveness/verify_corpus.py` (passes all integrity checks).
- Baseline regression: `./gradlew ktlintCheck` and `./gradlew test` (proves production code is unchanged).
- Evaluation & gate: `python3 experiment/fen-similarity-effectiveness/evaluate_results.py` (computes Recall@10, AP@10, gate decision).
- Evidence: attach execution logs, complete metric distribution, and reproducible commit hash in issue comment per repository conventions.
```

### Traceability and Semantic Preservation Matrix

| Requirement / Item in Original #526 | Location in Streamlined Version | Semantic Status | Notes / Justification |
|---|---|---|---|
| Problem: Lack of empirical retrieval effectiveness measurement | `## Problem` | **Preserved** | Core problem and hypothesis preserved. |
| Goal: Evaluate against symmetry orbit & controls with gates | `## Goal` | **Preserved** | Preserves objective and primary metrics. |
| Failure Mode 1: Evaluator leaks ground truth into ranking | `## Relevant failure modes` (Item 1) | **Preserved** | Information leakage risk retained. |
| Failure Mode 2: Transformations generate illegal positions | `## Relevant failure modes` (Item 2) | **Preserved** | Chess legality failure mode retained. |
| Failure Mode 3: Controls trivialized by piece counts/turn | `## Relevant failure modes` (Item 3) | **Preserved** | Control confounder risk retained. |
| Failure Mode 4: Stability gate passes without retrieval | `## Relevant failure modes` (Item 4) | **Preserved** | Metric divergence risk retained. |
| Constraint: Frozen base commit #522, no production PR | `## Repository constraints` (Constraint 2) | **Preserved** | Branching and immutability boundary retained. |
| Constraint: Code isolated under `experiment/...` | `## Repository constraints` (Constraint 3) | **Preserved** | Path isolation constraint retained. |
| Constraint: Python 3.9.6, `chess==1.11.2`, Gradle/JUnit | `## Repository constraints` (Constraint 4) | **Preserved** | Tooling and dependency constraints retained. |
| AC: 3 symmetry transforms (horizontal, rank, rotation) | `## Acceptance criteria` (AC1) | **Preserved** | Exact 3 orbit relations defined. |
| AC: Zero castling rights, 4 distinct symmetry keys | `## Acceptance criteria` (AC1) | **Preserved** | Exact query eligibility rules retained. |
| AC: 24 queries, 131 candidates (3 positives + 128 controls) | `## Acceptance criteria` (AC2) | **Preserved** | Exact pool sizes retained. |
| AC: Matched piece inventory, zero castling, 43/85 turn split | `## Acceptance criteria` (AC2) | **Preserved** | Exact control matching criteria retained. |
| AC: Independent verifier, move replay, SHA-256 integrity | `## Acceptance criteria` (AC3) | **Preserved** | Independent verification checks retained. |
| AC: Metrics: Recall@10, AP@10, macro means, full rankings | `## Acceptance criteria` (AC4) | **Preserved** | Evaluation metrics and ranking retention retained. |
| AC: Gate: Macro Recall@10 >= 0.80 and Macro AP@10 >= 0.50 | `## Acceptance criteria` (AC5) | **Preserved** | Exact numerical decision thresholds retained. |
| AC: Bounded conclusion & single result comment | `## Acceptance criteria` (AC6) | **Preserved** | Reporting boundaries and comment format retained. |
| Implementation: Exact PRNG seeds (`52220261007`, `52220261008`) and ply range (12–40) | Generator Script (`generate_corpus.py`) & Experiment Record | **Relocated** | Intentional relocation: PRNG seed values and sampling loops belong in the executable generator script or dedicated experiment spec (`docs/experiments/<topic>.md`), not the issue. Preserved in #526 experiment commit `c5fb9c0`. |
| Implementation: Exact `json.dumps(..., separators=(',', ':'))` byte formatting | Verifier Script (`verify_corpus.py`) & Experiment Record | **Relocated** | Intentional relocation: Byte serialization formatting belongs in generator/verifier implementation, not issue criteria. Corpus SHA-256 (`efd04b0b...`) guarantees cryptographic integrity. |
| Descriptive metric: RR@10 and chance baseline (10/131 = 0.0763) | Evaluator Script (`evaluate_results.py`) & Result Report | **Relocated** | Intentional relocation: Descriptive references and secondary metrics are reported in evaluation output; the issue retains the authoritative Recall@10 and AP@10 gates. |
| Validation: 4 exact script and test commands | `## Validation` | **Preserved** | All 4 execution commands retained. |

*Conclusion and Preservation Analysis:*
1. **Preserved in Issue:** 100% of the scientific requirements, relevance definitions, pool compositions (24 query orbits, 131 candidates, 43/85 turn splits, piece-inventory matching), independent move-replay verification, predeclared acceptance gates (macro Recall@10 >= 0.80 and macro AP@10 >= 0.50), fail-closed reporting rules, and validation execution commands remain directly in the issue text.
2. **Relocated to Scripts / Specification:** Low-level procedural generation constants (PRNG seeds, trial limits, ply sampling bounds, and JSON byte encoding flags) are intentionally relocated to the executable scripts (`generate_corpus.py`, `verify_corpus.py`) and experiment documentation (`docs/experiments/<topic>.md`). In #526, these are permanently captured in experiment commit `c5fb9c0` and issue comment `#issuecomment-6028562293`.
3. **Reproducibility Status:** Nothing is lost or irreproducible. The corpus SHA-256 (`efd04b0bc86f086709189ec2d9d52335e1f8ab1ec59843190a07001e443cc1b8`), rankings SHA-256 (`b687769aceac9394ba22ab688e84556335904e20ebe31d97aa7ce4eb5fd2982d`), and complete metric distributions are cryptographically recorded in the historical record. Measured character reduction in the issue is 55% (from 10,293 to 4,620 characters).

---

## 4. Summary of Authoring Principles

When authoring new ChessEcho product issues:
1. **Focus on Observable Outcomes:** State what behavior or result is expected, what conditions trigger it, and what constitutes failure.
2. **State Task-Specific Verification:** Include the specific check required (e.g. running UI screenshot, concurrency integration test, targeted mutation audit) without pasting paragraphs of general review philosophy.
3. **Reference Shared Conventions:** Point to `docs/engineering/repository-conventions.md` for general evidence rules rather than repeating them in every criterion.
4. **Layer Details Appropriately:** Keep the issue focused on problem, intent, failure modes, constraints, and criteria. Place deep architectural wiring in the Praxis Spec and task breakdowns in the Praxis Plan.
