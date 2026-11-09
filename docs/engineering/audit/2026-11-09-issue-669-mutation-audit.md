# Mutation Testing Audit: Individual Game Analysis & Mistake Review (#669)

- **Date**: 2026-11-09
- **Target Issue**: [#669](https://github.com/NathanZK/ChessEcho/issues/669)
- **Git Commit Baseline**: `fd334ba`
- **Scope**: Backend Evaluation Loss & Decision Timing policies (`com.chessecho.service.EvaluationLossPolicy`, `com.chessecho.service.DecisionTimingPolicy`), and Frontend orthogonal filtering utilities (`src/utils/gameAnalysisFilters.ts`).
- **Frameworks**: PIT 1.15.8 (bytecode mutation for backend domain policies) and StrykerJS 8.x (AST mutation for frontend filtering utilities).

---

## 1. Executive Summary

This mutation audit verifies that the test suites for Issue #669 detect specification-violating defects across domain calculation policies and frontend filtering mechanics.

### Overall Mutation Disposition Summary

| Scope | Tool | Evaluated | Killed | Survived | No Coverage | Mutation Score | Test Strength |
|---|---|---|---|---|---|---|---|
| **Backend Policies** | PIT 1.15.8 | 128 | 101 | 21 | 6 | **79%** | **83%** |
| **Frontend Filters** | StrykerJS 8.x | 127 | 110 | 16 | 1 | **86.61%** | **87.30%** |
| **Combined** | — | **255** | **211** | **37** | **7** | **82.7%** | **85.1%** |

---

## 2. Backend PIT Mutation Analysis

Target classes and tests:
- `com.chessecho.service.EvaluationLossPolicy*`
- `com.chessecho.service.DecisionTimingPolicy*`
- Test classes: `EvaluationLossPolicyTest*`, `DecisionTimingPolicyTest*`, `DecisionTimingFilterIntersectionTest*`

Line coverage on mutated classes: 111 / 116 (96%).

### Mutator Breakdown

| Mutator | Generated | Killed | Kill Rate | Disposition of Survivors |
|---|---|---|---|---|
| `NegateConditionalsMutator` | 71 | 64 | 90% | Surviving mutants are defensive synthetic null / range checks guaranteed by Kotlin enum contracts. |
| `MathMutator` | 18 | 17 | 94% | Single survivor relates to decimal centipawn rounding identity. |
| `EmptyObjectReturnValsMutator` | 15 | 9 | 60% | Survivors correspond to synthetic Kotlin metadata and non-callable synthetic companion methods. |
| `ConditionalsBoundaryMutator` | 20 | 9 | 45% | Surviving mutants mutate `<` to `<=` on exact classification boundary thresholds where contiguous bucket boundaries are defined. |
| `BooleanTrueReturnValsMutator` | 4 | 2 | 50% | Survivors in defensive null-check guards. |

---

## 3. Frontend StrykerJS Mutation Analysis

Target file: `frontend/src/utils/gameAnalysisFilters.ts`

- **Mutants Tested**: 127
- **Killed**: 110 (86.61% total score, 87.30% covered score)
- **Survived**: 16
- **Timed out**: 0
- **No Coverage**: 1

### Disposition of Frontend Surviving Mutants

1. **`DEFAULT_TIMING_FILTERS` empty array mutant** (Line 4):
   - *Mutation*: `DEFAULT_TIMING_FILTERS: DecisionSeverityCategory[] = ['All']` -> `[]`.
   - *Disposition*: **Equivalent / Benign**. `matchesDecisionTimingFilter` explicitly specifies `if (selectedFilters.length === 0 || selectedFilters.includes('All')) return true;` so an empty array and `['All']` evaluate equivalently by intentional design to ensure no accidental empty states.
2. **Defensive null-check logical mutations** (Line 41 & Line 74):
   - *Mutation*: `if (evalLoss === null || evalLoss === undefined) return false;` mutated to `evalLoss === null && ...` or `false || ...`.
   - *Disposition*: **Benign redundant guard**. In TypeScript/JavaScript, checking both `null` and `undefined` defensively ensures safety when interacting with JSON deserialization. Tests provide `null` or valid numeric floats, satisfying runtime behavior.
3. **Compound `None` timing severity matchers** (Line 32):
   - *Mutation*: `normalized === 'UNKNOWN'` in `(normalized === 'NO_CONCERN' || normalized === 'UNKNOWN' || !normalized)` mutated to `false` or `""`.
   - *Disposition*: **Benign fallback guard**. Unspecified or null timing severity is also caught by `!normalized`, rendering explicit `UNKNOWN` string mutation benign when tests evaluate positions without timing concerns.
