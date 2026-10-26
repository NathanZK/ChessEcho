# Plan: Position Similarity, Clustering, and Weakness Determination Roadmap Reconciliation

spec: docs/staging/specs/2026-10-26-similarity-clustering-roadmap.md

## Tasks

- [x] T1: Prepare temporary issue payload directory and verify prerequisite repository state
  goal: Create `build/tmp/roadmap` and verify GitHub CLI access and Milestone 6 state.
  files: `build/tmp/roadmap`
  acceptance: `gh api "repos/NathanZK/ChessEcho/milestones/6" --jq .title` returns `06 - Position Similarity`.
  spec: docs/staging/specs/2026-10-26-similarity-clustering-roadmap.md#summary--declarations

- [x] T2: Assign Issue #607 to Milestone 6 and close with research handoff evidence
  goal: Assign Milestone `06 - Position Similarity` to #607 and close with completion comment citing commit `7d11b3f` and comment #6401620119.
  files: `build/tmp/roadmap/close_607.md`
  acceptance: `gh issue view 607 --json state,milestone` reports `state: CLOSED` and `milestone.title: "06 - Position Similarity"`.
  spec: docs/staging/specs/2026-10-26-similarity-clustering-roadmap.md#1-issue-607-closing-comment

- [x] T3: Create Issue NEW-1 (board symmetries experiment) in Milestone 6
  goal: Create the static board symmetries experiment issue in Milestone 6 with P2 and research/experiment labels, ensuring idempotency.
  files: `build/tmp/roadmap/new1_body.md`
  acceptance: `gh issue list --search "Experiment: evaluate board symmetries (horizontal, vertical, combined) on static FEN similarity in:title" --json number` returns a valid issue number in Milestone 6.
  spec: docs/staging/specs/2026-10-26-similarity-clustering-roadmap.md#4-new-1-body-experiment-evaluate-board-symmetries-horizontal-vertical-combined-on-static-fen-similarity

- [x] T4: Create Issue NEW-2 (bounded engine quiescence search) in Milestone 6
  goal: Create the engine quiescence search capability issue in Milestone 6 with P2, backend, and research labels, ensuring idempotency.
  files: `build/tmp/roadmap/new2_body.md`
  acceptance: `gh issue list --search "Research & capability: bounded engine quiescence search for arbitrary positions in:title" --json number` returns a valid issue number in Milestone 6.
  spec: docs/staging/specs/2026-10-26-similarity-clustering-roadmap.md#5-new-2-body-research--capability-bounded-engine-quiescence-search-for-arbitrary-positions

- [x] T5: Create Issue NEW-3 (dynamic FEN similarity and acceptable moves) in Milestone 6
  goal: Create the dynamic FEN similarity issue in Milestone 6 evaluating Bizjak baseline and acceptable-move extensions on move sequences, ensuring idempotency.
  files: `build/tmp/roadmap/new3_body.md`
  acceptance: `gh issue list --search "Research & experiment: dynamic FEN similarity with Bizjak baseline and acceptable-move evaluation in:title" --json number` returns a valid issue number in Milestone 6.
  spec: docs/staging/specs/2026-10-26-similarity-clustering-roadmap.md#6-new-3-body-research--experiment-dynamic-fen-similarity-with-bizjak-baseline-and-acceptable-move-evaluation

- [x] T6: Create Issue NEW-4 (position clustering representations and coherence) in Milestone 6
  goal: Create the position clustering research issue in Milestone 6 with P2, backend, and research labels, ensuring idempotency.
  files: `build/tmp/roadmap/new4_body.md`
  acceptance: `gh issue list --search "Research & evaluation: position clustering representations and coherence on personal games in:title" --json number` returns a valid issue number in Milestone 6.
  spec: docs/staging/specs/2026-10-26-similarity-clustering-roadmap.md#7-new-4-body-research--evaluation-position-clustering-representations-and-coherence-on-personal-games

- [x] T7: Update Issue #91 for full-stack static personal-game similarity
  goal: Update Issue #91 with revised title, P1/full-stack/enhancement labels, Milestone 6, and complete product-issue specification.
  files: `build/tmp/roadmap/issue91_body.md`
  acceptance: `gh issue view 91 --json title,labels,milestone` confirms title `Find similar personal-game positions from an arbitrary FEN (full-stack)` in Milestone 6.
  spec: docs/staging/specs/2026-10-26-similarity-clustering-roadmap.md#2-issue-91-body-find-similar-personal-game-positions-from-an-arbitrary-fen-full-stack

- [x] T8: Update Issue #523 for weakness determination across related positions
  goal: Update Issue #523 with revised title, P3 labels, Milestone 6, concrete reference to NEW-4, and complete product-issue specification.
  files: `build/tmp/roadmap/issue523_body.md`
  acceptance: `gh issue view 523 --json title,labels,milestone` confirms title `Measure chess weakness across related positions compared to exact-FEN recurrence` in Milestone 6.
  spec: docs/staging/specs/2026-10-26-similarity-clustering-roadmap.md#3-issue-523-body-measure-chess-weakness-across-related-positions-compared-to-exact-fen-recurrence

- [x] T9: Update Issue #78 with completed prototype evidence and reconciled roadmap
  goal: Update parent research Issue #78 to record completed #607 prototype results and reference concrete issue numbers across the Milestone 6 sequence.
  files: `build/tmp/roadmap/issue78_body.md`
  acceptance: `gh issue view 78 --json body` contains references to completed #607 results, #91, #523, and all newly created issue numbers.
  spec: docs/staging/specs/2026-10-26-similarity-clustering-roadmap.md#8-issue-78-update-research-decision-similar-position-retrieval

- [x] T10: Verify Milestone 6 state, issue template compliance, and commit receipt log
  goal: Verify all issues in Milestone 6, confirm states, labels, and template compliance, and commit the execution receipt log.
  files: `docs/experiments/2026-10-26-roadmap-reconciliation-log.md`
  acceptance: `gh issue list --milestone "06 - Position Similarity" --state all` lists all audited issues; receipt log committed under `docs/experiments/`.
  spec: docs/staging/specs/2026-10-26-similarity-clustering-roadmap.md#issue-reconciliation-matrix
