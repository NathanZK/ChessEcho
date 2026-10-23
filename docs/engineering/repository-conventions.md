# ChessEcho repository conventions

This document is the single source of truth for the small conventions that
repeatedly caused friction across recent product issues. It is intentionally
short: it records defaults, not a full repository policy.

## Default: ChessEcho is pre-deployment

ChessEcho is assumed to be **pre-deployment** unless an issue explicitly
states otherwise. "Pre-deployment" means there is no production install with
data that must be preserved across a schema change, so there is no deployed
migration history that implementations must treat as immutable.

An issue changes this default only by explicitly stating a deployment state,
for example a line such as:

```text
Deployment state: already deployed; treat existing migrations as immutable.
```

Planners and implementers should treat the absence of such a line as
confirmation that the pre-deployment default still applies; they should not
infer a deployed state from unrelated context.

## Migration convention while pre-deployment

While the pre-deployment default applies, schema evolution is folded into the
baseline migration (`src/main/resources/db/migration/V1__baseline.sql`)
instead of being preserved as a synthetic, never-deployed version ladder
(for example, an unreleased `V2`/`V3`). `#80` (timed training) established
this pattern: schema changes needed for that feature were consolidated into
the baseline rather than added as a new versioned migration.

Once an issue's "Deployment state" line establishes that ChessEcho (or the
relevant environment) is deployed, this convention no longer applies for that
work: new schema changes must be added as new, additive, versioned Flyway
migrations, and the existing baseline must not be rewritten.

## Minimal issue contract and information layering

Product issues use the structure in
[`.github/ISSUE_TEMPLATE/product-issue.md`](../../.github/ISSUE_TEMPLATE/product-issue.md):
problem, goal, relevant failure modes, repository constraints, and observable
acceptance criteria.

To keep issues scanable and avoid redundant documentation, responsibilities are
layered across the workflow:

- **Issue:** Defines *what* and *why*—observable symptoms, desired outcome,
  concrete failure modes, repository constraints, and testable acceptance
  criteria. Does not prescribe detailed internal architecture or test step sequences.
- **Specification (`docs/staging/specs/`):** Defines technical contracts, component
  interactions, data models, schema changes, and error shapes before implementation.
- **Plan (`docs/staging/plans/`):** Decomposes work into executable TDD tasks,
  listing modified files, task sequencing, and targeted test commands.
- **Pull request (`.github/PULL_REQUEST_TEMPLATE.md`):** Summarizes what changed
  and provides independently reviewable verification evidence.

## Verification and review standards

Reviewers and agents follow shared verification standards rather than
re-specifying evidence doctrine in every issue:

### Evidence doctrine

- **Self-reports are not evidence:** An implementer's report of "passed" or "manually
  verified" is a claim. Independent review requires inspectable supporting evidence
  (test executions, command outputs, visual artifacts, or empirical measurements).
- **Behavioral testing:** Tests must exercise the intended behavior rather than
  merely asserting the implementation's own output or mocking away the unit under test.
- **Visual vs. interaction evidence:** Rendered UI changes require reviewable visual
  evidence from the running application (e.g. screenshots with sufficient surrounding
  context). Screenshots demonstrate visual appearance under captured conditions, not
  reachability or interaction. Interactive flows require reproducible interaction tests
  or explicit manual steps. Non-rendered work requires suitable non-visual evidence.
- **Attributable evidence:** Verification artifacts must be attributable to the
  reviewed revision and state, identifying any mocks, fixtures, or limitations.

### Risk-informed verification

Verification rigor should scale proportionately with the change's risk and failure modes:

- **Routine changes:** Verified with standard unit/component tests, linting, and type checking:
  - Backend validation: `./gradlew ktlintCheck`, `./gradlew detektMain detektTest`, and `./gradlew test`.
  - Frontend validation: `npm run lint`, `npx tsc --noEmit`, `npm run test`, and `npm run build` from `frontend/`.
- **Rendered UI state:** Verified with running-app visual proof plus flow interaction coverage.
- **Concurrency & data integrity:** Multi-worker, database, or race-condition risks
  warrant targeted integration tests (e.g. PostgreSQL Testcontainers).
- **Consequential business logic:** High-consequence, algorithmically dense business rules,
  scoring formulas, or security boundaries may specify targeted mutation testing
  (via PIT or Stryker) to audit whether assertions detect plausible logic errors.
  When mutation testing is performed, surviving equivalent or benign mutants must be
  documented rather than forcing an arbitrary 100% kill score. Mutation testing is
  an opt-in, risk-driven tool, not a mandatory gate for routine changes.

## Backend static analysis (Detekt & ktlint)

Backend static analysis pairs ktlint (formatting and lexical style) with Detekt (type-resolved semantic defect detection, complexity metrics, and documentation alignment):

- **Toolchain & execution:** Detekt 1.23.7 runs with type resolution via `./gradlew detektMain detektTest`. Detekt tasks are wired into `./gradlew check` and CI's `build` job.
- **Formatting authority:** Formatting is solely ktlint's domain. `detekt-formatting` is strictly prohibited to prevent rule duplication and competing auto-formatters.
- **Enforced rule groups:**
  - *Group A (Correctness & Exceptions):* `AvoidReferentialEquality` (String referential equality), `CastNullableToNonNullableType`, `ElseCaseInsteadOfExhaustiveWhen`, `EqualsAlwaysReturnsTrueOrFalse`, `UnconditionalJumpStatementInLoop`, `MapGetWithNotNullAssertionOperator`, `SwallowedException` (narrow exception name regex `^(_|expected|ignored)$`), `ThrowingExceptionFromFinally`, `ExceptionRaisedInUnexpectedLocation`, `TooGenericExceptionThrown`.
  - *Group B (Complexity):* `CognitiveComplexMethod` (threshold 15), `CyclomaticComplexMethod` (threshold 15), `NestedBlockDepth` (threshold 4), `ComplexCondition` (threshold 4), `LongMethod` (threshold 60), and `LargeClass` (threshold 600).
  - *Group C (KDoc):* `OutdatedDocumentation` (flags stale `@param` / `@property` tags against Kotlin signatures) and `DeprecatedBlockTag` (enforces `@Deprecated` annotations).
- **Test-scoped accommodations:** Function naming in test source sets supports backtick-delimited descriptive test names; magic numbers in chess coordinate calculations and tests are permitted. Semantic correctness and exception handling rules remain fully active across test sources.
- **Baseline and suppression policy:**
  - Baselines (`config/detekt/baseline-main.xml` and `config/detekt/baseline-test.xml`) record pre-existing legacy issues and include provenance headers.
  - New code and modifications introduced in pull requests must satisfy all blocking rules; new baseline entries must not be introduced for new development.
  - In-source suppressions (`@Suppress("RuleName")`) must be narrowly scoped to the specific function or statement and must be accompanied by an explanatory comment justifying why immediate refactoring is unsuitable.

## Out-of-scope and workflow conventions

Repository conventions and approved task scope take precedence over workflow defaults. Do not create repository conventions or artifacts, including documentation or changelogs, solely to satisfy a workflow unless the approved scope or an existing repository convention calls for them.

If work introduced during a task is later determined to be out of scope, remove it from that change set. If already committed, amend or squash the relevant work where practical; do not leave a standalone cleanup commit.
