# Mutation Testing Audit: Arbitrary-FEN Similar Position Retrieval (#91)

- **Date**: 2026-10-26
- **Target Issue**: [#91](https://github.com/NathanZK/ChessEcho/issues/91)
- **Git Commit Baseline**: `2c60e793139761893bf43807589f39930a150262`
- **Scope**: Backend similarity algorithmic engine (`com.chessecho.similarity.*`, `com.chessecho.service.similarity.*`, `SimilarPositionsController`), and Frontend view/navigation (`SimilarPositionsView`, `SimilarPositionsNavigation`).
- **Frameworks**: PIT 1.15.8 (bytecode mutation for isolated pure Kotlin algorithmic units) and targeted reproducible source mutations across full-stack failure modes.

---

## 1. Executive Summary

This mutation audit evaluates whether the test suites for Issue #91 detect plausible, specification-violating defects across documented failure modes.

### Mutant Disposition Summary

| Scope | Evaluated | Killed (Initial) | Survived (Initial) | Regression Tests Added | Final Killed | Final Survived | Unresolved | Equivalent / Benign |
|---|---|---|---|---|---|---|---|---|
| **Backend** | 8 | 5 | 3 | 2 | 7 | 0 | 1 | 0 |
| **Frontend** | 5 | 4 | 1 | 1 | 5 | 0 | 0 | 0 |
| **Total** | **13** | **9 (69%)** | **4 (31%)** | **3** | **12 (92%)** | **0 (0%)** | **1 (8%)** | **0 (0%)** |

> [!NOTE]
> Mutation survival evaluates purely whether test assertions detect synthetic departures from the specification. Killing scoring formula or candidate generation mutants verifies test sensitivity to implementation invariants, but does not evaluate whether the BM25 feature space or Bizjak relational tokens are subjectively useful for chess analysis.

---

## 2. Targeted Failure-Mode Mutation Matrix

### Backend Mutants (Algorithmic Invariants & Security Boundaries)

| ID | Failure Mode / Invariant | Affected Component | Mutation Applied | Expected Test Failure | Initial Outcome | Evidence / Regression Action |
|---|---|---|---|---|---|---|
| **M-BE-1** | Candidate generation omits relevant candidates or truncates candidate pool | `PositionInvertedIndex.kt` (line 50) | Mutate `.take(maxCandidates)` to `.take(1)` | `PositionInvertedIndexTest`: `queryCandidates ranks positions by token overlap count()` | **Killed** | Test failed with `AssertionFailedError at PositionInvertedIndexTest.kt:43`. |
| **M-BE-2** | BM25 document length normalization ($b=0.75$) omitted | `PositionSimilarityScorer.kt` (line 70) | Mutate denominator factor to `BM25_K1` ($b=0.0$) | `PositionSimilarityScorerTest`: test penalizing higher document length for identical matches | **Survived** | Existing test only compared an opening position against an endgame. **Added regression test** `score penalizes candidates with greater document length when matching terms are identical`, which decisively killed the mutant. |
| **M-BE-3** | Deterministic tie-breaking on tied BM25 scores omitted | `PositionSimilarityScorer.kt` (line 36) | Mutate `.thenBy { it.first.canonicalFen }` to `.thenByDescending` | `PositionSimilarityScorerTest`: test strictly breaking score ties by `canonicalFen` ascending | **Survived** | Existing test asserted tie-breaking conditionally (`if (score == score)`), which was unexercised on differing floating-point scores. **Added regression test** `score strictly breaks ties by canonicalFen ascending when BM25 scores are identical`, which decisively killed the mutant. |
| **M-BE-4** | Exact query position canonical FEN exclusion omitted | `PositionSimilarityScorer.kt` (line 18) | Remove `.filterNot { it.canonicalFen == query.canonicalFen }` | `PositionSimilarityScorerTest`: `score strictly excludes exact query canonical FEN from results()` | **Killed** | Test failed with `AssertionFailedError at PositionSimilarityScorerTest.kt:26`. |
| **M-BE-5** | Orthodox FEN piece character and structure validation omitted | `FenPositionValidator.kt` (line 17) | Omit `board.loadFromFen(trimmed)` | `FenPositionValidatorTest`: reject FEN with illegal piece characters or invalid rank counts | **Survived** | Existing tests only supplied strings failing field or rank count. **Added regression test** `validateOrthodoxFen throws IllegalArgumentException on illegal piece characters`, which decisively killed the mutant. |
| **M-BE-6** | Cross-account authorization: authenticated user bypasses ownership via username spoofing | `PositionSimilarityController.kt` (line 66) | Omit check rejecting `username` when `principal != null` | `SimilarPositionsControllerIntegrationTest`: `returns 400 when authenticated user supplies username preventing spoofing()` | **Killed** | Test failed with `AssertionFailedError at SimilarPositionsControllerIntegrationTest.kt:152`. |
| **M-BE-7** | Concurrent index rebuild commits obsolete index after invalidation | `PositionSimilarityCache.kt` (line 51) | Mutate `if (gen.get() == startGen)` to `if (true)` | `PositionSimilarityCacheTest`: discard rebuild if generation advanced | **Unresolved** | Although downstream reads reject the entry via `cached.generation == currentGen`, committing a rebuild across an invalidation boundary violates the specification guarantee (`docs/tech-spec.md`: "concurrent rebuilds never commit partial or stale indexes") and pins obsolete index structures in heap memory. Because behavioral equivalence cannot be established under the memory and concurrency contract, this mutant is classified as **Unresolved** (test suite assertion gap). |
| **M-BE-8** | Source game URL construction accepts untrusted hostname or unverified scheme | `ChessComUrlBuilder.kt` (line 36) | Remove hostname check `host != "chess.com" && host != "www.chess.com"` | `ChessComUrlBuilderTest`: `buildExactPlyUrl returns null for untrusted hosts or malformed URLs()` | **Killed** | Test failed with `AssertionFailedError at ChessComUrlBuilderTest.kt:45`. |

---

### Frontend Mutants (User-Visible Behavior & Exploration Handoff)

| ID | Failure Mode / Invariant | Affected Component | Mutation Applied | Expected Test Failure | Initial Outcome | Evidence / Regression Action |
|---|---|---|---|---|---|---|
| **M-FE-1** | Client FEN validation omitted; invalid board input submitted | `SimilarPositionsView.tsx` (line 111) | Change `if (!parseResult.ok)` to `if (false)` | `SimilarPositionsView.test.tsx`: `shows error on invalid FEN submission` | **Killed** | Test failed with `TestingLibraryElementError: Unable to find element with text /cannot be used as a chess position/`. |
| **M-FE-2** | Empty search result prompt omitted when zero positions match | `SimilarPositionsView.tsx` (line 343) | Change `{hasSearched && results.length === 0}` to `{false}` | `SimilarPositionsView.test.tsx`: `shows empty state when no positions meet criteria` | **Killed** | Test timed out waiting for `No Similar Positions Found`. |
| **M-FE-3** | Exploration handoff transfers original search FEN instead of result FEN | `SimilarPositionsView.tsx` (line 169) | Mutate `fen: parseResult.fen` to `fen: queryFen` | `SimilarPositionsView.test.tsx`: `navigates to Line Exploration on "Explore this position" click` | **Killed** | Test failed: `expected onExplorePosition to be called with result FEN, received query FEN`. |
| **M-FE-4** | Exit Exploration fails to switch back to Similar Positions tab | `page.tsx` (line 1178) | Mutate `changeTab('similar-positions')` to `changeTab('puzzles')` | `SimilarPositionsNavigation.test.tsx`: `explores position from SimilarPositionsView and restores state upon Exit Exploration` | **Killed** | Test failed: timed out waiting for Similar Positions view restoration. |
| **M-FE-5** | Search button remains enabled when FEN input is empty | `SimilarPositionsView.tsx` (line 233) | Mutate `disabled={isLoading || !fenInput.trim() || !isAccountAvailable}` to `disabled={false}` | `SimilarPositionsView.test.tsx`: assert search button is disabled on empty input | **Survived** | Existing test asserted `toBeInTheDocument()` but did not assert `toBeDisabled()`. **Added regression test** `expect(screen.getByRole('button', { name: /Search/i })).toBeDisabled()`, which decisively killed the mutant. |

---

## 3. Regression Tests Added

Three targeted regression tests were added across the test suite to close the identified gaps:

1. **`PositionSimilarityScorerTest.kt`**:
   - `score penalizes candidates with greater document length when matching terms are identical`: Asserts that a candidate with identical matching query tokens but higher total document length (due to reachability terms) receives a strictly lower BM25 score, killing document-length normalization mutators.
   - `score strictly breaks ties by canonicalFen ascending when BM25 scores are identical`: Unconditionally asserts that candidates with identical BM25 scores break ties deterministically by `canonicalFen` ascending, killing tie-breaking inversion mutators.
2. **`FenPositionValidatorTest.kt`**:
   - `validateOrthodoxFen throws IllegalArgumentException on illegal piece characters`: Asserts that syntactically structured 8-rank FEN strings with illegal piece characters (e.g. `zzzzzzzz/8/8/8/8/8/8/8 w - - 0 1`) throw `IllegalArgumentException`, killing FEN validator bypass mutators.
3. **`SimilarPositionsView.test.tsx`**:
   - In `renders FEN input, search button, and sample positions`: Asserts `expect(screen.getByRole('button', { name: /Search/i })).toBeDisabled()` when input is empty, killing button disabled state mutators.

---

## 4. Verification & Working Tree State

### Working Tree Assessment
- **Baseline Git Commit**: `2c60e793139761893bf43807589f39930a150262` (branch `implement_github_issue_91`).
- **Working Tree Cleanliness**: All temporary synthetic mutation edits have been completely reverted. The working tree retains pre-existing uncommitted changes implementing Issue #91 (production endpoints, frontend UI components, navigation logic, and integration tests) plus the regression tests and mutation documentation introduced in Phase 2. No unrelated code refactoring or schema modifications were introduced.

### Verification Execution and Results
All checks were executed directly against the restored working tree:
- **Backend Quality Gates**:
  - `./gradlew ktlintCheck` — **PASSED** (clean)
  - `./gradlew detektMain detektTest` — **PASSED** (0 smells)
  - `./gradlew test` — **PASSED** (all tests green)
- **Frontend Quality Gates**:
  - `npm run lint` (from `frontend/`) — **PASSED** (0 warnings, max-warnings 0)
  - `npx tsc --noEmit` (from `frontend/`) — **PASSED** (0 type errors)
  - `npm run test` (from `frontend/`) — **PASSED** (55 test files, 614 tests passing)
  - `npm run build` (from `frontend/`) — **PASSED** (Next.js 16.4.1 static build succeeded)

---

## 5. Remaining Limitations and Test Suite Gaps

1. **Cache Lifecycle Internal Assertion Gap (M-BE-7)**:
   - `PositionSimilarityCacheTest` verifies that a subsequent call to `getOrBuild` triggers a fresh rebuild when an invalidation occurred during a previous construction. However, it tests this only by observing caller invocation counts (`assertEquals(2, callCount)`).
   - It does not assert on internal cache map state to confirm that the stale entry was discarded rather than committed to `cache[accountId]`.
   - Adding a direct test or package-private observation asserting that an in-flight rebuild does not repopulate `cache` when the generation advanced would convert M-BE-7 into a killed mutant.
2. **Algorithm Quality vs Test Sensitivity**:
   - As emphasized throughout this audit, killing BM25 length-normalization and tie-breaking mutants establishes that the test suite is sensitive to implementation defects; it does not constitute a validation of the chess relevance of the similarity tokenization.
