# Opt-in Mutation Testing with PIT

This guide documents the opt-in PIT (Pitest) mutation testing integration for ChessEcho's backend Kotlin codebase. Mutation testing evaluates unit test effectiveness by introducing synthetic faults (mutants) into compiled bytecode and checking whether existing tests fail (kill the mutant).

PIT is integrated strictly as an on-demand, local developer tool. It is completely isolated from default build and test lifecycles (`./gradlew test`, `./gradlew check`, `./gradlew build`) and is never run in CI.

---

## 1. Quickstart & CLI Execution

Mutation testing is executed on demand using the `pitest` Gradle task. Target classes and test suites are configured via project properties (`-Ppitest.targetClasses` and `-Ppitest.targetTests`).

### Default Execution (Verified Pilot Target)

When run without properties, PIT runs against the verified pilot target `AdaptivePuzzleSchedulingPolicy`:

```bash
./gradlew pitest
```

This defaults to:
- `targetClasses`: `com.chessecho.service.AdaptivePuzzleSchedulingPolicy*`
- `targetTests`: `com.chessecho.service.AdaptivePuzzleSchedulingPolicyTest*`

### Parameterized Targeting

To evaluate another candidate class and its corresponding unit test suite, supply both properties:

```bash
./gradlew pitest \
  -Ppitest.targetClasses="com.chessecho.service.MyService*" \
  -Ppitest.targetTests="com.chessecho.service.MyServiceTest*"
```

Multiple comma-separated patterns can be supplied:

```bash
./gradlew pitest \
  -Ppitest.targetClasses="com.chessecho.service.PolicyA*,com.chessecho.service.PolicyB*" \
  -Ppitest.targetTests="com.chessecho.service.PolicyATest*,com.chessecho.service.PolicyBTest*"
```

> [!IMPORTANT]
> **Mandatory Quoting in macOS zsh**:
> Always wrap property values containing wildcards (`*`) in double quotes (e.g., `-Ppitest.targetClasses="..."`). In shells where glob expansion is enabled by default (such as zsh on macOS), unquoted wildcards cause the shell to attempt filename expansion before Gradle starts, failing immediately with `zsh: no matches found`.

### Fail-Safe Property Validation

To prevent silent test mismatches (e.g., executing a targeted class against unrelated default tests), the build enforces a strict paired-property contract at configuration time:
- **Blank Normalization**: Blank or whitespace-only property values are normalized to absent.
- **Both Omitted or Blank**: If neither property is supplied, or if both are blank/whitespace-only, both default to `AdaptivePuzzleSchedulingPolicy` and its test.
- **Both Supplied**: If both properties are supplied with non-blank values, both are parsed (comma-delimited, trimmed) and used explicitly. Explicit non-blank inputs that contain no usable patterns (such as comma-only `","`) fail fast with:
  ```text
  'pitest.targetClasses' was provided but contains no usable target patterns.
  ```
- **Partially Supplied**: If exactly one property is supplied (one provided and one omitted, or one valid and one blank/whitespace-only), Gradle fails fast with an `InvalidUserDataException`:
  ```text
  Both 'pitest.targetClasses' and 'pitest.targetTests' must be specified together, but only one was provided.
  ```

---

## 2. Reports and Maintenance

### Report Locations

PIT generates human-readable HTML reports and structured XML summaries:
- **HTML Summary**: `build/reports/pitest/index.html`
- **XML Report**: `build/reports/pitest/mutations.xml`

Because `timestampedReports` is disabled (`false`), reports are written directly to this directory rather than in timestamped subfolders.

### Stale-Report Cleanup

When switching between different target classes or packages, previous reports under `build/reports/pitest/` may retain stale subdirectories for previously mutated packages. To ensure clean reports when switching targets:

```bash
# Option A: Remove the PIT reports directory directly
rm -rf build/reports/pitest/

# Option B: Run a clean build
./gradlew clean
```

### Durable Audit Index

Historical mutation testing audit results across assessed candidate classes and test suites are documented and indexed in [docs/engineering/audit/index.md](audit/index.md).

### Persisting Reports Outside `build/`

By default, PIT reports are generated under `build/reports/pitest/` and are deleted whenever `./gradlew clean` runs.

To preserve the generated mutation testing report across clean builds without committing build outputs into version control:

1. **Automatic Persistence on Run**:
   Execute PIT with `-Ppitest.archive=true` to automatically persist the report upon completion:
   ```bash
   ./gradlew pitest -Ppitest.archive=true
   ```

2. **On-Demand Copy of Existing Report**:
   Copy an already-generated report without re-running PIT:
   ```bash
   ./gradlew archivePitest
   ```
   > [!NOTE]
   > `./gradlew archivePitest` only copies an existing report from `build/reports/pitest/`. It does not implicitly execute PIT.

3. **Storage Destination & Scope**:
   - **Destination**: `pit-reports/` in the project root directory (outside `build/`).
   - **Git Hygiene**: `/pit-reports/` is gitignored to avoid contaminating application source control.
   - **Contents Preserved**: The full report directory tree is synchronized, preserving `mutations.xml`, root `index.html`, `style.css`, and all package HTML subdirectories.
   - **Survival Semantics**: Persisted reports survive `./gradlew clean` (which only deletes `build/`). However, because this is a local, unversioned copy of the latest run, it does not survive repository checkout deletion, machine loss, or ephemeral container termination.

4. **Stale Report & Failure Defense**:
   - `pitest` deletes `build/reports/pitest/` at task startup (`doFirst`). If a PIT run fails early or crashes, no stale output from prior runs can linger.
   - `archivePitest` validates that PIT did not fail and that `build/reports/pitest/mutations.xml` exists before synchronizing. If PIT failed or no report was generated, `archivePitest` throws an error and leaves the destination directory untouched.

---

## 3. Qualitative Runtime Expectations

Runtime for mutation testing is determined qualitatively by three interacting factors:
1. **Mutant count**: The volume of mutation operators generated across candidate bytecode.
2. **Individual test execution time**: Because test scenarios are executed repeatedly for each mutation unit, test suite duration directly multiplies analysis runtime.
3. **Execution environment**: Host CPU performance and worker thread availability for PIT's minion processes.

For pure domain and algorithmic calculation units with fast, self-contained unit tests (such as `AdaptivePuzzleSchedulingPolicyTest`), execution finishes rapidly because tests execute in-memory with zero I/O or bootstrapping overhead.

Conversely, classes that transitively couple to Spring application contexts, JPA persistence, or database interactions (such as Testcontainers-backed integration suites) incur substantial startup latency and per-test execution duration, requiring significantly longer runs. Such classes are not suitable targets for mutation testing.

No artificial hard time cutoffs are enforced; developers should assess candidates qualitatively based on mutant volume, test suite execution speed, and execution environment.

---

## 4. Evidence-Based Mutant Interpretation Taxonomy

Do **not** evaluate mutation testing results by chasing an arbitrary percentage score (e.g., "must achieve 80% mutation coverage"). Surviving mutants must be inspected qualitatively based on the specific mutation and intended behavior.

ChessEcho classifies mutants into the following structured taxonomy:

### 1. Actionable test gaps (Defects to Fix)
- **Description**: The mutant altered business logic or return values, but the test suite passed because of vacuous, weak, or missing assertions.
- **Example from pilot (#544)**: `EmptyObjectReturnValsMutator` mutated `policy.order(...)` to return `emptyList()`. The test asserted that `first.map { it.positionId } == second.map { it.positionId }`. Because both lists were empty `[]`, the assertion passed vacuously despite 100% line coverage.
- **Action**: Strengthen assertions to assert explicit, concrete expected candidate outputs or state transitions.

### 2. Heuristic and numerical variations (Preserve Directional Invariants)
- **Description**: Mutators alter arithmetic constants, decay coefficients, priority weights, or decay time constants (e.g., `MathMutator` swapping `* 0.5` to `/ 0.5`).
- **Guideline**: Tests often intentionally verify directional properties (e.g., `assertTrue(recentWeight > oldWeight)`) rather than coupling to brittle floating-point values.
- **Action**: Do **not** introduce brittle assertions pinning floating-point constants merely to kill mutants, as doing so impedes future formula tuning.

### 3. Kotlin-generated or inlined bytecode artifacts (Benign Surviving Mutants)
- **Description**: Synthetic bytecode transformations injected by the Kotlin compiler or standard library inlines.
- **Example from pilot (#544)**: In inlined `events.count { it.type == FAILED }`, PIT mutates `CollectionsKt.throwCountOverflow`, which checks if iteration count exceeds `Int.MAX_VALUE` (2.1 billion items). Normal unit tests with 10–20 events can never exercise this branch.
- **Action**: Acknowledge as benign compiler noise; do not contrive artificial tests or alter production code to reach them.

### 4. Unexecuted branches (Inspect Scenario Coverage)
- **Description**: Legitimate code branches not reached by existing test inputs (e.g., secondary comparison branches in stdlib `maxOfOrNull` when test fixtures supply only single-element lists).
- **Action**: Inspect whether the branch represents an unexercised practical scenario worth testing. If it represents a real application scenario, add a multi-item test case; do not automatically dismiss surviving branches without inspecting them.

### 5. Possible equivalent mutants (Benign Unkillable Mutants)
- **Description**: Code transformations that are syntactically different but produce semantically identical observable outcomes (e.g., replacing `<` with `<=` when the boundary value is impossible or yields identical branch results).
- **Action**: Cannot be killed by valid tests without violating black-box contract principles; accept as unkillable.

### Strict Score Policy
- **No minimum score threshold**: No mutation coverage threshold is mandated or enforced in ChessEcho.
- **No brittle over-assertion**: Never introduce brittle assertions or modify production application code solely to raise mutation scores.

---

## 5. Candidate Selection Criteria & Boundaries

To ensure mutation testing remains fast, actionable, and low-noise, candidate classes must satisfy strict technical prerequisites.

### Prerequisites for Candidate Classes
A backend class is an appropriate candidate for mutation testing if and only if it meets all four criteria:
1. **Pure algorithmic / domain calculation logic**: Core logic consisting of deterministic calculations, ranking, scoring, or scheduling.
2. **High risk of false confidence**: Logic where conventional line coverage can pass with weak, vacuous, or directional assertions.
3. **Complete isolation**: Zero dependencies on Spring application contexts, JPA repositories, file I/O, or external network calls.
4. **Fast, isolated unit test execution**: The unit test suite executes rapidly in memory without external dependencies, container bootstrapping, or heavy framework overhead.

### Non-targets (Explicitly Excluded)
The following are non-targets for PIT mutation testing:
- **Spring Controllers and Web Layer**: Web endpoints, request/response serialization, and security filter chains.
- **JPA Repositories and Entities**: Database interactions, SQL queries, and ORM mapping.
- **Integration Test Suites**: Any tests relying on Spring Boot test contexts or Testcontainers PostgreSQL containers.
- **Unconstrained Whole-Codebase Campaigns**: Running mutation testing across whole packages or the entire project without target filters.

### Future Candidate Evaluation
Future candidates for mutation testing will be evaluated individually on a case-by-case basis under separate, focused issues under parent strategy #547 rather than through blanket sweeps.

---

## 6. Troubleshooting

| Symptom | Cause | Resolution |
|---|---|---|
| `zsh: no matches found: -Ppitest.targetClasses=...*` | macOS zsh expanded the wildcard before passing arguments to Gradle. | Wrap the property value in quotes: `-Ppitest.targetClasses="..."`. |
| `Both 'pitest.targetClasses' and 'pitest.targetTests' must be specified together...` | Exactly one property was supplied, or one property was valid while the other was blank/omitted. | Ensure both properties are supplied together with valid pattern strings, or omit both to use defaults. |
| `'pitest.targetClasses' was provided but contains no usable target patterns.` | The property was supplied with non-blank characters (such as commas only `","`) that yielded no usable patterns after parsing. | Provide valid, non-blank class patterns. |
| `No mutations found` | 1. Typo in `targetClasses` pattern.<br>2. Candidate class contains no mutable bytecode (e.g. interfaces, Kotlin data classes with only auto-generated getters/equals/hashCode). | Verify class path spelling and ensure the target class contains executable calculation logic. |
| Tests run but 0 mutants killed | Test filter `targetTests` did not match any test classes, or tests do not exercise the target class. | Check that `targetTests` pattern matches the fully qualified test class name. |
| Stale package folders in HTML report | Previous runs under different target packages were preserved in `build/reports/pitest/`. | Run `rm -rf build/reports/pitest/` or `./gradlew clean` before running against a new package. |
