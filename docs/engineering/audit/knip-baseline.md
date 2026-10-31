# Initial Knip Frontend Dead-Code & Unused Exports Baseline Audit

**Audit Date:** 2026-10-31  
**Tool Version:** Knip v6.44.1  
**Target Workspace:** `frontend/` (Next.js 16 App Router, React 19, TypeScript 5)  
**Tracking Issue:** [#685](https://github.com/NathanZK/ChessEcho/issues/685)  

---

## Executive Summary

The initial frontend codebase analysis using Knip was executed against all TypeScript and React source files in `frontend/src/` alongside Next.js App Router entry points, Vitest suites, Stryker mutator configurations, and PostCSS/Tailwind configurations.

The scan confirmed **zero unused files** and **zero unreferenced dependencies**. A total of 24 unused exported symbols were identified: 9 unused function/class/constant exports and 15 unused exported types/interfaces.

| Finding Category | Finding Count | Status & Review Action |
|---|---|---|
| Unused Files | 0 | Clean. Every file under `src/` is imported or serves as a valid route/config. |
| Unused Dependencies | 0 | Clean. All production dependencies in `package.json` are referenced. |
| Unused devDependencies | 0 | Clean. `@tailwindcss/postcss` and `tailwindcss` mapped to styling pipeline. |
| Unused Exports (Functions / Constants / Classes) | 9 | Triaged. Consists of dev helpers, singleton class exports, and component-local formatters. |
| Unused Exported Types (Interfaces / Type Aliases) | 15 | Triaged. Consists of API contract types, puzzle domain types, and domain state enums. |
| **Total Baseline Findings** | **24** | **All cataloged. Zero deletions in this change.** |

---

## Finding Inventory

### 1. Unused Exports (Functions, Constants, Classes) — 9 findings

| Export Name | Export Kind | File Location | Triage Assessment & Classification |
|---|---|---|---|
| `formatDecisionDuration` | function | `frontend/src/components/LongDecisionsView.tsx:39` | Component-local formatter exported unnecessarily; safe to remove `export` modifier in follow-up. |
| `formatSeverityLabel` | function | `frontend/src/components/LongDecisionsView.tsx:44` | Component-local formatter exported unnecessarily; safe to remove `export` modifier in follow-up. |
| `severityPillStyles` | function | `frontend/src/components/LongDecisionsView.tsx:59` | Component-local formatter exported unnecessarily; safe to remove `export` modifier in follow-up. |
| `formatMonthLabel` | function | `frontend/src/components/MonthPicker.tsx:36` | Component-local date formatter; safe to remove `export` modifier in follow-up. |
| `devLogin` | function | `frontend/src/services/api.ts:175` | Development-mode authentication helper for local testing; retain as valid dev contract. |
| `ContinuationCacheService` | class | `frontend/src/services/continuationService.ts:83` | Service class exported alongside singleton instance; evaluate if class export is required. |
| `MoveEvaluationCacheService` | class | `frontend/src/services/continuationService.ts:154` | Service class exported alongside singleton instance; evaluate if class export is required. |
| `SoundService` | class | `frontend/src/services/soundService.ts:148` | Service class exported alongside singleton instance; evaluate if class export is required. |
| `createStore` | function | `frontend/src/utils/browserStores.ts:21` | Generic factory for localStorage stores; retain if other stores are planned or scope internal. |

### 2. Unused Exported Types & Interfaces — 15 findings

| Type Name | Type Kind | File Location | Triage Assessment & Classification |
|---|---|---|---|
| `HeaderSessionStatus` | type | `frontend/src/components/Header.tsx:10` | Exported component prop type; safe to keep for typing or scope internal. |
| `HeaderAccountStatus` | type | `frontend/src/components/Header.tsx:11` | Exported component prop type; safe to keep for typing or scope internal. |
| `PracticalEvidenceResponse` | interface | `frontend/src/services/api.ts:61` | Shared backend DTO representation; retain for API contract synchronization. |
| `PhaseStats` | interface | `frontend/src/services/api.ts:332` | Shared backend DTO representation; retain for API contract synchronization. |
| `TerminationReason` | type | `frontend/src/services/api.ts:499` | Shared backend DTO representation; retain for API contract synchronization. |
| `PuzzleSchedulingEventType` | type | `frontend/src/services/api.ts:542` | Shared backend DTO representation; retain for API contract synchronization. |
| `SimilarPositionOccurrenceDto`| interface | `frontend/src/services/api.ts:918` | Shared backend DTO representation; retain for API contract synchronization. |
| `SelectionResult` | interface | `frontend/src/services/continuationService.ts:10` | Internal service return type; safe to unexport if consumed only internally. |
| `AcceptableMove` | interface | `frontend/src/types/puzzle.ts:1` | Puzzle domain contract; retain for API contract synchronization. |
| `MoveBreakdown` | interface | `frontend/src/types/puzzle.ts:6` | Puzzle domain contract; retain for API contract synchronization. |
| `BlindfoldTurn` | type | `frontend/src/utils/blindfoldGameState.ts:3` | Domain state type; retain for state-machine definitions. |
| `BlindfoldTerminalReason` | type | `frontend/src/utils/blindfoldGameState.ts:7` | Domain state type; retain for state-machine definitions. |
| `BlindfoldWinner` | type | `frontend/src/utils/blindfoldGameState.ts:9` | Domain state type; retain for state-machine definitions. |
| `BlindfoldMove` | interface | `frontend/src/utils/blindfoldGameState.ts:11` | Domain state type; retain for state-machine definitions. |
| `BlindfoldPlayerMoveFeedback` | interface | `frontend/src/utils/blindfoldGameState.ts:17` | Domain state type; retain for state-machine definitions. |

---

## Triage Policy and Recommendations

1. **Do Not Blindly Delete Public Contract Types:**
   Types like `PracticalEvidenceResponse`, `PhaseStats`, `SimilarPositionOccurrenceDto`, and `TerminationReason` in `src/services/api.ts` mirror backend REST API schemas documented in `API_CONTRACT.md`. Even if currently consumed implicitly or through inferred types, preserving explicit DTO contracts prevents frontend drift when backend schemas change.

2. **Component-Local Helper Un-exporting:**
   Functions like `formatDecisionDuration`, `formatSeverityLabel`, `severityPillStyles`, and `formatMonthLabel` are only used within their declaring view files. Follow-up maintenance issues may simply remove the `export` keyword to reduce the module's public surface area without altering runtime behavior.

3. **Singleton Class Exports:**
   `ContinuationCacheService`, `MoveEvaluationCacheService`, and `SoundService` export their class declarations in addition to default or named singleton instances. Follow-up cleanup can un-export the class declaration where only the instance is consumed.

4. **Integration into CI Workflow:**
   Knip should remain an on-demand audit command (`npm run knip -- --no-exit-code` or `npx knip`) until the 24 baseline findings are either un-exported or explicitly whitelisted in `knip.json`. This ensures CI remains strictly reliable and blocking without false alarms on intentional API contracts.
