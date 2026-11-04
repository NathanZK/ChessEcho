# Test Coverage Gap Audit

This risk-based audit identifies critical verification gaps across the ten registered user journeys and core invariants.

A **non-mocked gap** denotes a registered journey or invariant lacking automated verification against real backend services, persistence, or real browser execution (i.e. possessing only Tier 1 unit or Tier 2 mocked-API test coverage, with zero Tier 3 or Tier 4 coverage).

| Gap ID | Journey / Invariant | Current Coverage | Missing Evidence Tier | Risk & Failure Scenario | Target Future Phase |
|---|---|---|---|---|---|
| **GAP-01** | J08, J09, J10 (Full-stack auth & session lifecycle) | Tier 2 (`SessionBootstrap.test.tsx`), Tier 3 (`LocalAuthIntegrationTest.kt`) | Tier 4 (Full-stack browser + cookie persistence) | Browser cookie handling discrepancies (`SameSite`, `Secure`), CSRF token mismatch on client form submit, or silent session expiration. | Phase 1 (#712), Phase 6 (#717) |
| **GAP-02** | J03 (Dynamic similarity reranking pipeline) | Tier 2 (`SimilarPositionsView.test.tsx`), Tier 3 (`SimilarPositionsControllerIntegrationTest.kt`) | Tier 4 (Full-stack browser + Stockfish subprocess) | Client timeout on long engine continuation batches (100 candidates), UI lockup on subprocess crash, or dynamic token parsing divergence. | Phase 6 (#717) |
| **GAP-03** | J06, J07 (Game import & analysis polling) | Tier 2 (`ImportJobStore.test.tsx`), Tier 3 (`GameImportServiceBaseIntegrationTest.kt`) | Tier 4 (Full-stack browser polling to analysis) | Polling race conditions, premature polling termination on network jitter, or unhandled UI state when analysis finishes in background. | Phase 1 (#712), Phase 6 (#717) |
| **GAP-04** | INV-03 (Interactive mode isolation & boundary leaks) | Tier 2 (`PuzzlesFeaturesAndFixes.test.tsx`, `BlindfoldContinuationFlow.test.tsx`) | Tier 4 / Monkey (interaction sequence testing) | Rapid mode switching between Challenge, Blindfold, and Line Exploration leaves lingering keyboard event listeners or corrupted board orientation. | Phase 4 (#715), Phase 5 (#716) |
| **GAP-05** | J01, J07, INV-04 (Progress interval derivation) | Tier 1 (`ProgressServiceTest.kt`), Tier 2 (`PositionProgressView.test.tsx`), Tier 3 (`ProgressControllerIntegrationTest.kt`) | Tier 4 (Full-stack end-to-end progress timeline) | Disconnect / reconnect of Chess.com account fails to refresh personal Progress interval baseline in running UI without page reload. | Phase 1 (#712), Phase 6 (#717) |
