# Frontend Mutation Testing Audit Baseline

- **Date**: 2026-10-27
- **Target Issue**: [#635](https://github.com/NathanZK/ChessEcho/issues/635)
- **Parent QA Strategy**: [#547](https://github.com/NathanZK/ChessEcho/issues/547)
- **Baseline Git Commit SHA**: `bc76eebcb7a75bfea7078226153f0f953e1f25bb`
- **Scope**: Core frontend domain calculation and parsing utilities (`frontend/src/utils/`): `urlUtils.ts`, `chessGame.ts`, `positionImport.ts`.
- **Frameworks & Runtimes**:
  - StrykerJS `@stryker-mutator/core` 10.1.1
  - Runner `@stryker-mutator/vitest-runner` 10.1.1
  - Vitest 4.1.12
  - Node.js v26.0.0 / npm 11.12.1
  - macOS Darwin 24.6.0
- **Execution Command**: `cd frontend && npm run test:mutation`
- **Config**: `frontend/stryker.config.mjs`

---

## 1. Executive Summary

This initial baseline establishes the reproducible mutation score and mutant inventory for the ChessEcho frontend's pure calculation utilities before any frontend remediation issues are implemented.

### Aggregate and Per-Target Mutant Breakdown

| File / Target | Test Suite | Scope | Total Mutants | Killed | Timed Out | Survived | No Coverage | Errors | Mutation Score (%) | Covered Score (%) |
|---|---|---|---|---|---|---|---|---|---|---|
| `src/utils/chessGame.ts` | `ChessGameHistory.test.ts` | Domain Unit | 27 | 16 (59.3%) | 4 (14.8%) | 6 (22.2%) | 1 (3.7%) | 0 | **74.07%** | **76.92%** |
| `src/utils/positionImport.ts` | `positionImport.test.ts` | Domain Unit | 241 | 143 (59.3%) | 15 (6.2%) | 56 (23.2%) | 27 (11.2%) | 0 | **65.56%** | **73.83%** |
| `src/utils/urlUtils.ts` | `urlUtils.test.ts` | Utility Unit | 118 | 98 (83.1%) | 0 (0.0%) | 17 (14.4%) | 3 (2.5%) | 0 | **83.05%** | **85.22%** |
| **All Files (Aggregate)** | **Full Unit Suites** | **Frontend Core** | **386** | **257 (66.6%)** | **19 (4.9%)** | **79 (20.5%)** | **31 (8.0%)** | **0** | **71.50%** | **77.75%** |

*Note on calculations*:
- **Mutation Score**: $(\text{Killed} + \text{TimedOut}) / \text{Total} \times 100\%$ ($276 / 386 = 71.50\%$).
- **Covered Score / Test Strength**: $(\text{Killed} + \text{TimedOut}) / (\text{Total} - \text{NoCoverage}) \times 100\%$ ($276 / 355 = 77.75\%$).
- **Timed Out mutants**: In accordance with the backend methodology, timed out mutants represent infinite loop or execution timeout detections; they are recorded separately from test assertion kills.

---

## 2. Detailed Per-Target Audit and Surviving Mutant Inventory

### 2.1 `src/utils/chessGame.ts`

- **Test Suite**: `frontend/src/__tests__/ChessGameHistory.test.ts` (5 tests passing in 6ms)
- **Results**: 27 mutants generated; 16 killed, 4 timed out, 6 survived, 1 no coverage.
- **Surviving Mutant Analysis**:
  1. *Line 17 (ConditionalExpression: true)*: `return game.fen() === selectedFen ? game : new Chess(selectedFen);` mutating to `true` (unconditionally returning `game`). When `game.fen() === selectedFen`, returning `game` produces an identical state, making this an equivalent mutant on matching positions (Taxonomy Category 5).
  2. *Line 27 (ConditionalExpression: true)*: `return clone.fen() === game.fen() ? clone : new Chess(game.fen());` mutating to `true`. Similarly, on valid PGN where `clone.fen() === game.fen()`, returning `clone` produces an identical result (Taxonomy Category 5).
  3. *Line 36 (CallExpression: `;`)*: Removing `nextGame.move(move)` survived because subsequent check line 37 falls back to `new Chess(resultingFen)`, which constructs the target position anyway.
  4. *Line 37 (ConditionalExpression / EqualityOperator)*: Mutating `nextGame.fen() === resultingFen` condition and equality branches.
- **Actionable Gap for Future Investigation**:
  - Test suite does not verify that `applyChessMoveOrUseFen` preserves full move history when the move succeeds versus falling back to a fresh board with empty history.

---

### 2.2 `src/utils/urlUtils.ts`

- **Test Suite**: `frontend/src/__tests__/urlUtils.test.ts` (27 tests passing in 4ms)
- **Results**: 118 mutants generated; 98 killed (83.1%), 0 timed out, 17 survived (14.4%), 3 no coverage (2.5%).
- **Meaningful Invariants Killed**:
  - Conversion scheme validation (`http:` and `https:`).
  - Exact hostname allowlist (`chess.com`, `www.chess.com`).
  - Path structure `/game/:type/:id` segmentation and argument extraction.
  - Query parameter encoding and move number specification (`?move=0`, `?move={plyNumber}`).
  - Daily vs live path detection.
- **Surviving Mutant Analysis**:
  1. *Line 18 (StringLiteral / ConditionalExpression)*: Mutating `url.protocol !== 'https:' && url.protocol !== 'http:'`. Defensive guard where URL parser normalizes lowercase protocols (Taxonomy Category 3).
  2. *Line 30 (ConditionalExpression: false)*: `pathParts.length !== 3 || false`. Because path parts are filtered by `Boolean`, `pathParts[0] !== 'game'` handles non-game URLs (Taxonomy Category 5).
  3. *Line 70 (ConditionalExpression / LogicalOperator)*: `if (!platformGameId || typeof platformGameId !== 'string')`. Type-checking defensive guard where TypeScript statically enforces `string` (Taxonomy Category 3).
  4. *Line 79 (MethodExpression: `endsWith`)*: Mutating `startsWith('http://')` to `endsWith`. When tests test `https://` URLs, the `http://` branch mutant survives because `https` evaluates second or independently (Taxonomy Category 4).
  5. *Line 83 (ConditionalExpression / StringLiteral)*: Hostname comparison mutants on `host !== 'chess.com' && host !== 'www.chess.com'`.
  6. *Lines 88 & 89 (MethodExpression: `endsWith`)*: Mutating `startsWith('/game/daily/')` and `startsWith('/game/computer/')`. Tests do not supply input ending with these paths (Taxonomy Category 4).
  7. *Line 101 (Regex anchors)*: In `^\d+$`, removing `^` or `$` anchors survived because tests only supply clean numeric strings (e.g. `'12345'`) or malformed strings with non-digits everywhere.
- **Actionable Gap for Future Investigation**:
  - Add test asserting that `buildExactPlyChessComUrl` rejects trailing non-digit text (e.g. `'12345abc'`) to kill regex boundary mutators on line 101.
  - Add test for `/game/computer/` path in `buildExactPlyChessComUrl`.

---

### 2.3 `src/utils/positionImport.ts`

- **Test Suite**: `frontend/src/__tests__/positionImport.test.ts` (11 tests passing in 6ms)
- **Results**: 241 mutants generated; 143 killed (59.3%), 15 timed out (6.2%), 56 survived (23.2%), 27 no coverage (11.2%).
- **Meaningful Invariants Killed**:
  - PGN variation detection (`(`) rejecting variations with `VARIATIONS_UNSUPPORTED`.
  - Multiple game detection via tag pair extraction after initial headers rejecting with `MULTIPLE_GAMES`.
  - Empty input rejection for FEN and PGN.
  - Plies accumulation with 0-index start ply and subsequent move plies.
  - White vs Black move color mapping and ply labels.
- **Surviving Mutant Analysis**:
  1. *Lines 117-143 (Tag string escaping scanner)*: Mutators in escape tracking (`char === '\\'`, `escaped = false`, `inTagString`). Survives because test fixtures do not include backslash-escaped quotes inside header tags (e.g. `[Event "Round \"1\""]`) (Taxonomy Category 4).
  2. *Lines 150-159 (Comment and tag transition state variables)*: Several conditional mutations in `scanPgn` survived because existing tests only assert the aggregate `ok: false` error string, rather than exercising nuanced combinations of unclosed comments and nested tag strings (Taxonomy Category 1).
  3. *Line 155 (AssignmentOperator: `-=`)*: In `visibleText += char`, mutator replacing `+=` with `-=` survived because subsequent logic only checks `visibleText` for tag regex matches, not exact move text content (Taxonomy Category 1).
  4. *Line 171 & 179 (Regex boundary / Cursor scanning)*: `hasTagPairAfterInitialHeaders` cursor loop equality mutators.
- **Actionable Gaps for Future Investigation**:
  - Test PGN header values containing escaped quotes (`\"`).
  - Strengthen assertions on `plies` array contents and start ply FEN when custom `SetUp: 1` headers are supplied.

---

## 3. Working Tree and Provenance

### 3.1 Pre-Run Application State (Baseline Provenance)
- **Application Commit SHA**: `bc76eebcb7a75bfea7078226153f0f953e1f25bb` (HEAD of branch `main` at integration start)
- **Pre-Run Working Tree State**: Clean application source repository (`git status` reported only untracked staging planning notes `docs/staging/`). No uncommitted modifications to production code or test files existed when baseline evaluation began.

### 3.2 Integration & Evidence Changes (Tooling Infrastructure Only)
The subsequent working-tree changes are strictly bounded to mutation testing infrastructure, configuration, and documentation:
- `frontend/package.json` and `frontend/package-lock.json`: Added pinned devDependencies `@stryker-mutator/core@10.1.1` and `@stryker-mutator/vitest-runner@10.1.1`; added `"test:mutation": "stryker run"` script.
- `frontend/stryker.config.mjs`: Opt-in StrykerJS ESM configuration for Vitest runner and candidate utilities.
- `frontend/eslint.config.mjs`: Added `.stryker-tmp/**` and `reports/**` to `globalIgnores`.
- `.gitignore` and `frontend/.gitignore`: Ignored ephemeral `.stryker-tmp/` and `reports/` directories.
- `docs/engineering/frontend-mutation-testing.md`: Operational engineering guide for frontend mutation testing.
- `docs/engineering/audit/frontend-mutation-audit.md`: Durable baseline audit report.
- `docs/engineering/audit/index.md`: Durable audit index entry ([§2.18](index.md#218-frontend-mutation-testing-baseline-core-calculation-utilities-issue-635)).

### 3.3 Application and Test Invariants
- **Production Code Untouched**: Zero lines of production code (`frontend/src/app/*`, `frontend/src/components/*`, `frontend/src/utils/*`) were modified.
- **Existing Tests Untouched**: Zero lines of test code (`frontend/src/__tests__/*`) were modified or weakened to inflate mutation scores.
- **Zero Product Remediation**: No planned product remediation issues (such as [#629](https://github.com/NathanZK/ChessEcho/issues/629) or UI redesigns) were implemented.
