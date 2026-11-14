# Mutation Testing Analysis & Calibration Protocol

This document establishes the evidence-based mutation calibration procedure for ChessProbe, reconciling native tool outputs with the Unified Mutant Outcome Classification and the Adjusted Score formula.

---

## 1. Unified Mutant Outcome Classification & Tool Mapping

| Unified Category | StrykerJS Status | Pitest Status | Meaning | Accounting Rule |
|---|---|---|---|---|
| `KILLED` | `Killed` | `KILLED` | Test assertion failure | Active numerator & denominator |
| `SURVIVED` | `Survived` | `SURVIVED` | All tests passed | Deficit in denominator |
| `NO_COVERAGE` | `NoCoverage` | `NO_COVERAGE` | Line unexecuted | Deficit in denominator |
| `TIMED_OUT` | `Timeout` | `TIMED_OUT` | Execution timeout | Counted as `KILLED` only if proven infinite loop; unresolved timeouts fail calibration |
| `COMPILE_ERROR` | `CompileError` | `NON_VIABLE` | Non-compilable AST / invalid bytecode | Excluded from active denominator |
| `RUNTIME_ERROR` | `RuntimeError` | `RUN_ERROR` / `MEMORY_ERROR` | Runner or environment error | Excluded from score; blocks calibration |
| `VERIFIED_EQUIVALENT` | `Ignored` (with verified ledger entry) | Documented equivalent | Proven identical observable behavior | Excluded prior to score calculation |
| `UNRESOLVED_EXCLUSION` | `Ignored` (lacking ledger proof) | Undocumented exclusion | Arbitrarily suppressed mutant | **Fails calibration (Exit 1)** |

---

## 2. Adjusted Score Calculation Formula

$$\text{Adjusted Score} = \begin{cases} 100.0\% & (\text{KILLED} + \text{SURVIVED} + \text{NO\_COVERAGE}) = 0 \\ \frac{\text{KILLED} + \text{TIMED\_OUT}_{\text{proven}}}{\text{KILLED} + \text{TIMED\_OUT}_{\text{proven}} + \text{SURVIVED} + \text{NO\_COVERAGE}} \times 100\% & \text{otherwise} \end{cases}$$

- If any unproven `TIMED_OUT` or `UNRESOLVED_EXCLUSION` mutants exist, the evaluation fails immediately with exit code 1.
- `VERIFIED_EQUIVALENT` mutants are excluded prior to calculation and must be registered in `config/verified-equivalents.json`.

---

## 3. High-Risk Targets & Mutator Selection

### Frontend (StrykerJS)
- **Targets**:
  - `src/utils/urlUtils.ts`
  - `src/utils/chessGame.ts`
  - `src/utils/positionImport.ts`
  - `src/utils/decisionTimingComparison.ts`
  - `src/hooks/useLineExploration.ts`
- **Active Mutators**:
  - `EqualityOperator`
  - `ConditionalExpression`
  - `BooleanLiteral`
  - `BlockStatement`
  - `ArrayDeclaration`
  (Configured via `mutator.excludedMutations` in `frontend/stryker.config.mjs`).

### Backend (Pitest)
- **Target Classes**:
  - `com.chessprobe.service.AdaptivePuzzleSchedulingPolicy*`
  - `com.chessprobe.service.EvaluationLossPolicy*`
- **Target Tests**:
  - `com.chessprobe.service.AdaptivePuzzleSchedulingPolicyTest*`
  - `com.chessprobe.service.EvaluationLossPolicyTest*`
- **Active Mutator Groups**:
  - `CONDITIONALS_BOUNDARY`
  - `INCREMENTS`
  - `INVERT_NEGS`
  - `MATH`
  - `NEGATE_CONDITIONALS`
  - `RETURNS`
  - `VOID_METHOD_CALLS`

---

## 4. Evidence Protocol for Equivalent Mutants & Timeout Proofs

### Equivalent Mutants
Excluding a mutant as `VERIFIED_EQUIVALENT` requires documented evidence containing:
1. Exact mutant ID, file path, line number, mutator, and replacement AST.
2. Semantic equivalence proof: mathematical or bytecode demonstration that mutant produces identical runtime state for all reachable inputs.
3. Justification for why code cannot be refactored to eliminate the redundant syntax.

All verified equivalents must be recorded in `config/verified-equivalents.json`. Any `Ignored` mutant lacking an entry in this ledger causes `scripts/eval-mutation-score.mjs` to reject the run with an `UNRESOLVED_EXCLUSION` failure.

### Timeout Proofs (Exact Mutant ID Matching)
Counting a `TIMED_OUT` mutant as `TIMED_OUT_proven` requires an exact mutant-ID match:
- Stryker: exact `mutantId` matching the numeric string ID from `mutation.json`.
- Pitest: exact `mutantId` matching `mutatedClass:lineNumber:mutator` or explicit `mutantId`.
- **Location-only matching (`file:line` without mutator or ID) is strictly forbidden.** A location match never proves an infinite loop because multiple distinct mutants can occupy the same line.
- Ledger entries must specify `type: "PROVEN_INFINITE_LOOP"` or `type: "PROVEN_TIMEOUT"` and non-empty `semanticProof`.
- Empty entries (`{}`), missing proof, duplicate IDs, or conflicting classifications (`EQUIVALENT` vs `PROVEN_INFINITE_LOOP`) are strictly rejected during ledger validation.

---

## 5. Persisted Calibration & Three-Campaign Protocol

### Three-Campaign Calibration CLI Procedure
Empirical calibration cannot be derived from a single run ($N=1$). To establish statistical validity, `--calibrate` requires exactly three distinct baseline campaign report pairs:

```bash
node scripts/eval-mutation-score.mjs --calibrate \
  --stryker=campaign1/mutation.json,campaign2/mutation.json,campaign3/mutation.json \
  --pitest=campaign1/mutations.xml,campaign2/mutations.xml,campaign3/mutations.xml \
  --config=config/mutation-thresholds.json \
  --ledger=config/verified-equivalents.json
```

### Calibration Rules & Validation
1. **Three Distinct Runs**: Exactly 3 Stryker and 3 Pitest reports must be supplied. Fewer or more reports fail immediately with an error. Single-run calibration is rejected.
2. **SHA-256 Provenance & Deduplication**: Each report file is hashed with SHA-256. Duplicate files or identical hash contents are rejected to prevent replaying identical campaign data.
3. **Report Completeness**: Reports must be complete and non-empty (containing $>0$ mutants). Empty reports fail calibration.
4. **Aggregation Rule**:
   - $\text{Baseline Adjusted Score} = \text{round}\left(\frac{S_1 + S_2 + S_3}{3} \times 100\right) / 100$
   - $\text{Threshold Floor} = \max(\text{MinimumFloorLimit}, \text{round}((\text{Baseline} - 5.0) \times 10) / 10)$
     - Frontend floor minimum: $60.0\%$
     - Backend floor minimum: $65.0\%$
5. **Persisted Record**: The resulting `config/mutation-thresholds.json` stores the full provenance of each campaign (file path, SHA-256, score, and mutant metrics), the aggregation rule, timestamp, and derived floors.

### Provisional Bootstrap vs. Nightly Enforcement
- Until 3 baseline campaigns are executed and calibrated, `config/mutation-thresholds.json` maintains `"status": "PROVISIONAL_BOOTSTRAP"`.
- Nightly CI workflows execute with `--allow-provisional` to permit non-blocking quality runs during the bootstrap phase.
- Without `--allow-provisional` or explicit CLI overrides, `scripts/eval-mutation-score.mjs` rejects provisional configurations to prevent premature enforcement claims.
