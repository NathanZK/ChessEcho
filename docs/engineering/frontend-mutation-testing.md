# Opt-in Mutation Testing with StrykerJS

This guide documents the opt-in StrykerJS mutation testing integration for ChessEcho's Next.js/TypeScript frontend codebase (`frontend/`). Mutation testing evaluates unit test effectiveness by introducing synthetic faults (mutants) into source TypeScript code and checking whether existing Vitest suites fail (kill the mutant).

StrykerJS is integrated strictly as an on-demand, local developer tool. It is completely isolated from default build and test lifecycles (`npm test`, `npm run lint`, `npx tsc --noEmit`, `npm run build`) and is never run in CI.

---

## 1. Quickstart & CLI Execution

Mutation testing is executed on demand using npm scripts within the `frontend/` directory.

### Default Execution (Verified Candidate Targets)

When run without arguments, Stryker runs against the verified pure domain and calculation utilities configured in `frontend/stryker.config.mjs`:

```bash
cd frontend
npm run test:mutation
```

This defaults to mutating:
- `src/utils/urlUtils.ts` (tested by `src/__tests__/urlUtils.test.ts`)
- `src/utils/chessGame.ts` (tested by `src/__tests__/ChessGameHistory.test.ts`)
- `src/utils/positionImport.ts` (tested by `src/__tests__/positionImport.test.ts`)

### Parameterized Targeting

To evaluate another candidate utility file or subset, supply the `--mutate` argument:

```bash
cd frontend
npm run test:mutation -- --mutate="src/utils/urlUtils.ts"
```

Multiple comma-separated or glob patterns can be supplied:

```bash
cd frontend
npm run test:mutation -- --mutate="src/utils/urlUtils.ts,src/utils/chessGame.ts"
```

> [!IMPORTANT]
> **Mandatory Quoting in macOS zsh**:
> Always wrap `--mutate` values containing glob wildcards (`*`) in double quotes (e.g. `--mutate="src/utils/*.ts"`). In shells where glob expansion is enabled by default (such as zsh on macOS), unquoted wildcards cause the shell to attempt filename expansion before npm or Stryker starts.

### Exit Code and Tool Behavior

- **Exit Code 0 (Completed Run)**: Stryker exits with code `0` on any completed mutation run regardless of score, because `thresholds.break = null` is configured.
- **Exit Code 1 (Configuration / Empty Target Error)**: If `--mutate` matches no files or no tests are found for the mutated files, Stryker logs an actionable warning and exits with code `1` (`ConfigError: No tests were executed. Stryker will exit prematurely. Please check your configuration.`).
- **Exit Code Non-Zero (Crash / Test Failure in Baseline)**: If initial dry-run tests fail before mutation or an unhandled exception occurs, Stryker terminates with a non-zero exit code.

---

## 2. Reports and Maintenance

### Report Locations

Stryker generates human-readable HTML reports and structured JSON summaries:
- **HTML Summary**: `frontend/reports/mutation/mutation.html` (interactive web bundle)
- **JSON Report**: `frontend/reports/mutation/mutation.json` (Mutation Testing Elements JSON schema)

### Git Hygiene and Ephemeral Artifacts

- **Storage Destination**: All transient reports are written to `frontend/reports/` and working directories under `frontend/.stryker-tmp/`.
- **Git Hygiene**: Both `frontend/reports/` and `frontend/.stryker-tmp/` are ignored in `.gitignore`. Never commit raw report bundles into git.
- **Clean Survival**: Reports are preserved locally until manually removed or overwritten. To clean report artifacts:
  ```bash
  rm -rf frontend/reports frontend/.stryker-tmp
  ```

### Durable Audit Index

Historical mutation testing audit results across candidate files and test suites are documented and indexed in [docs/engineering/audit/index.md](audit/index.md).

---

## 3. Qualitative Runtime Expectations

Runtime for frontend mutation testing is determined by:
1. **Mutant count**: The volume of mutation operators generated across candidate AST nodes (e.g. 118 mutants in `urlUtils.ts`, 241 in `positionImport.ts`).
2. **Individual test execution time & worker pool**: Tests execute via `@stryker-mutator/vitest-runner` with in-process worker pooling (9 concurrency processes).
3. **Pure logic vs React/DOM rendering**: Pure TypeScript utilities (`urlUtils.ts`, `chessGame.ts`, `positionImport.ts`) execute in-memory with sub-second Vitest executions. Full React components (`components/*`) requiring jsdom bootstrapping, DOM reconciliations, and `@testing-library/react` wrappers incur substantial execution time multipliers.

---

## 4. Evidence-Based Mutant Interpretation Taxonomy

Do **not** evaluate mutation testing results by chasing an arbitrary percentage score (e.g. "must achieve 80% mutation coverage"). Surviving mutants must be inspected qualitatively based on the specific mutation and intended behavior.

ChessEcho classifies frontend mutants into the following structured taxonomy:

### 1. Actionable test gaps (Defects to Fix)
- **Description**: The mutant altered business logic or validation checks, but the test suite passed because of missing assertions, incomplete input boundaries, or missing scenarios.
- **Example from baseline**: Line 155 in `positionImport.ts` (`visibleText -= char` survived). If comments or variation branches fail to properly exclude character text, assertions did not catch the missing concatenation.
- **Action**: Add concrete regression tests exercising the uncovered scenario in subsequent remediation issues.

### 2. Heuristic and numerical variations (Preserve Directional Invariants)
- **Description**: Mutators alter regex anchors, string prefixes, or default boundary constants where tests intentionally assert higher-level contracts.
- **Action**: Do not introduce brittle over-assertions pinning internal regex structure solely to kill mutants.

### 3. TypeScript/Babel/Bundler synthetic artifacts (Benign Noise)
- **Description**: Transpiled or optional-chaining / nullish-coalescing guard branches where TypeScript types guarantee non-null values at runtime (e.g. `!platformGameId || typeof platformGameId !== 'string'`).
- **Action**: Acknowledge as defensive guards; do not contrive artificial tests or remove defensive type guards.

### 4. Unexecuted branches (Inspect Scenario Coverage)
- **Description**: Legitimate code branches not reached by existing test inputs (e.g. `url.pathname.startsWith('/game/computer/')` in `urlUtils.ts` when test fixtures only supply `live` and `daily` games).
- **Action**: Inspect whether the branch represents an unexercised practical scenario worth testing. If so, file a targeted issue or add test coverage.

### 5. Possible equivalent mutants (Benign Unkillable Mutants)
- **Description**: Code transformations that are syntactically different but produce semantically identical observable outcomes (e.g. `pathParts.length !== 3 || false` when earlier validation already bounds array length).
- **Action**: Cannot be killed without violating black-box contract principles; accept as unkillable.

### Strict Score Policy
- **No minimum score threshold**: No mutation coverage threshold is mandated or enforced in ChessEcho frontend.
- **No brittle over-assertion**: Never introduce brittle assertions or modify production application code solely to raise mutation scores.

---

## 5. Candidate Selection Criteria & Boundaries

### Prerequisites for Candidate Files
A frontend file is an appropriate candidate for mutation testing if and only if it meets:
1. **Pure algorithmic / domain calculation logic**: Core logic consisting of deterministic calculations, string parsing, FEN/PGN transformations, or URL validation.
2. **High risk of false confidence**: Logic where conventional line coverage can pass with weak, vacuous, or directional assertions.
3. **Complete isolation**: Zero dependencies on browser DOM (`document`, `window`), HTTP network calls, or React component rendering.
4. **Fast, isolated unit test execution**: The unit test suite executes rapidly in memory without DOM bootstrapping or timer mocks.

### Non-targets (Explicitly Excluded)
The following are non-targets for StrykerJS mutation testing:
- **React Components (`src/components/*`)**: UI rendering, layout, Tailwind styling, and JSX hierarchy.
- **Next.js Pages & Routes (`src/app/*`)**: Route handlers, layout wrappers, and navigation tabs.
- **Browser Store Singletons (`src/utils/browserStores.ts`)**: LocalStorage wrapper singletons.
- **Integration Test Suites**: Full component interaction tests involving multi-tab navigation or sound playback.
- **Unconstrained Whole-Codebase Sweeps**: Running mutation testing across all frontend source files without filters.

---

## 6. Troubleshooting

| Symptom | Cause | Resolution |
|---|---|---|
| `zsh: no matches found: --mutate=...*` | macOS zsh expanded the wildcard before passing arguments to npm. | Wrap the argument in quotes: `--mutate="src/utils/*.ts"`. |
| `ConfigError: No tests were executed...` | 1. Typo in `--mutate` path pattern.<br>2. Mutated file is not imported by any test file. | Verify file path spelling and ensure matching test imports the target file. |
| Tests run but 0 mutants killed | Test filter did not match tests exercising the mutated code. | Check that the target unit test directly imports and exercises the mutated module. |
| Stale report data | Previous runs under different targets remain in `reports/`. | Run `rm -rf frontend/reports/ frontend/.stryker-tmp/` before running against a new target. |
