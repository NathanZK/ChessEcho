---
name: adversarial-review
description: "EXPERIMENTAL: Supplemental adversarial review layer and evidence falsification protocols for ChessProbe, strengthening existing Praxis review stages."
---

# Experimental Adversarial Review Protocol

> [!IMPORTANT]
> **STATUS: EXPERIMENTAL WORKSPACE-LOCAL SKILL**
> This skill supplements existing Praxis review stages (`review`, `subagents`, `reviewers.md`) and Ponytail review (`ponytail-review`). It introduces active evidence falsification and multimodal verification without modifying the global Praxis distribution, creating new subagent roles, or multiplying review passes.

---

## 1. Charter & Core Philosophy

### Active Evidence Falsification vs. Compliance Checklist
Standard reviews tend toward compliance credulity: if an implementer checked off tasks, linting passed, and test counts matched, reviewers default to approval.

Adversarial review inverts this presumption:
- **Do not ask**: *"Did the implementer complete the tasks listed in the plan?"*
- **Ask**: *"What concrete evidence would disprove the claim that this implementation works correctly in production?"*

### The Four-Tier Evidence Hierarchy
Reviewers must evaluate verification claims against four distinct levels of rigor:

```
[Level 1: Command Success]      <-- Weakest: Script exited 0 (may mask silent crashes or empty runs)
        ↓
[Level 2: Artifact Creation]    <-- File exists on disk (may contain broken or default content)
        ↓
[Level 3: Artifact Inspection]  <-- Reviewer ingested and examined artifact content
        ↓
[Level 4: Criteria Verified]    <-- Strongest: Inspected evidence proves acceptance criteria satisfied
```

1. **Level 1 (Command Success)**: A test runner, build tool, or capture script exited with code `0`. This proves only that the process terminated without an unhandled exit code.
2. **Level 2 (Artifact Creation)**: A file (e.g. screenshot `.png`, generated `.sql`, coverage report) exists on disk. Checking `ls -la` confirms file allocation, **not correctness**.
3. **Level 3 (Artifact Inspection)**: The reviewer actively ingests and inspects the content of the artifact (e.g. reading screenshot image bytes, auditing migration SQL statements, reading generated payload JSON).
4. **Level 4 (Criteria Verified)**: The inspected content directly demonstrates that user acceptance criteria and relevant edge cases are satisfied without unexpected regressions or error banners.

**Rule**: No review finding may be approved at Level 1 or Level 2 when Level 3/4 evidence is required by the task acceptance criteria.

### Evidence Identity Gate
Before accepting reported verification evidence (test outputs, screenshots, build logs):
- Establish whether the evidence corresponds to the exact revision under review by verifying:
  - Git HEAD SHA and working-tree status (clean vs. uncommitted modifications).
  - Test command invocation, exit code, and stdout/stderr matching the current diff.
  - Screenshot provenance (generated during the current revision run).
- **Do not rely on timestamps alone.**
- Treat stale, mismatched, or unverifiable evidence as **insufficient proof**—not as automatic proof of a defect. If evidence cannot be verified, request or run targeted regeneration rather than guessing.

---

## 2. Multimodal Visual Verification Protocol

When a change affects user-visible UI, component styling, or includes generated screenshots:

### 2.1 Direct Image Inspection
- **Inspect actual image content**: The reviewer must not rely on file existence (`ls screenshots/`) or script exit codes. When visual correctness is relevant, the reviewer must ingest the image content using any available image-capable mechanism (such as `read_file` on a `.png` or `.jpg` in Antigravity, or other harness image decoders).
- **Inspect populated viewports**: Verify that data cards, tables, charts, and metrics actually render populated test fixtures.
- **Detect negative UI artifacts**: Actively scan for:
  - Error banners or alert bars (e.g. *"Failed to load..."*, *"Could not load..."*, *"Unexpected response body"*).
  - Frozen loading spinners or unpopulated skeleton loaders.
  - Raw unstyled fallback strings or uncaught exception boundaries.
  - Layout collisions, text truncation, or overflow across responsive breakpoints (Desktop 1440px, Tablet 1024px, Mobile 390px).

### 2.2 Scoped UI State Coverage
- **Evaluate only relevant UI states**: Check states (populated, empty, loading, error, filtering) only when they are directly modified or mandated by the task acceptance criteria. Do not force an exhaustive 5-state audit on a change that only touches a single isolated element (e.g. button color).
- **Canonical Parity Check**: When a task explicitly mirrors an existing canonical component (e.g. aligning `LongDecisionsView.tsx` with `WeaknessesList.tsx`), verify that error and empty presentations match the canonical surface patterns (such as a centered card with retry action rather than a raw unstyled banner).

### 2.3 Environmental Limitation Reporting
- If image inspection tools are unavailable or image ingestion is unsupported in the current execution harness:
  The reviewer must explicitly record this in the review report:
  `LIMITATION: Multimodal image inspection unavailable; visual state verified through JSX inspection only.`
- The reviewer must **never** report visual verification as passed solely based on file presence.
- For pure backend, CLI, migration, or documentation tasks where rendered behavior is not in scope, screenshot capture and image inspection must not be requested.

---

## 3. Targeted Adversarial Verification & Boundary Probing

Reviewers should actively look for defects that survive deterministic unit tests and static typecheckers.

### 3.1 Runtime Contract Boundaries vs. Static Types
- **TypeScript interfaces vanish at runtime**: Static checks (`tsc --noEmit`) verify compile-time compatibility, but do not validate dynamic data.
- **Audit call sites and argument ordering**: In TypeScript/JavaScript, positional arguments with primitive types (e.g. `fn(limit?: number, offset?: number)`) can easily be swapped without compiler errors. Cross-reference call-site invocations with the callee signature in `api.ts`.
- **Untrusted boundary schema validation**: Verify whether API client parsers (e.g. `isUuid`, `isRecord`, Zod schemas) validate payload structure. Check whether test fixtures pass static checks but fail runtime validators.

### 3.2 Test Mock Bypass Analysis
- Determine what layer the test mock replaces.
- If a test mocks the HTTP client, does the mock return data matching the backend's real serialized format, or does it bypass a critical validation routine?
- JSDOM unit tests do not compute CSS layout, grid layouts, or canvas rendering. Passing JSDOM tests do not establish visual layout correctness.

### 3.3 Negative Space & Failure Path Tracing
- Map what happens on error:
  - 401 Unauthenticated (session expiration).
  - 403 Forbidden (insufficient roles).
  - 404 / 422 / 500 responses.
  - Network disconnection or abort.
- Does the UI handle the failure gracefully with a retry mechanism, or does it render an unhandled crash state?

### 3.4 Concurrency & Asynchronous Behavior
- Check for race conditions in React effects or asynchronous thunks:
  - Is there an active request version counter (e.g. `requestVersion.current`) or `AbortController` to prevent out-of-order stale responses from overwriting newer user selections?
  - Does unmounting a component while a fetch is in-flight trigger state updates on unmounted components?

### 3.5 Falsification Standards & Risk Classification
- **Concrete evidence is mandatory**: Every `BLOCK` or `FIX` must include a concrete failure demonstration: `"Input/condition X leads to incorrect result Y"`.
- **Code-Path Proof for Security & Architectural Flaws**:
  - For security vulnerabilities, authorization gaps, or data integrity risks (e.g. missing transaction, unescaped sink, unvalidated deserialization), a verified code-path demonstration of untrusted input reaching an unprotected sink is sufficient to issue a `BLOCK`.
  - Reviewers are **not** required to construct an executable exploit payload or live reproduction when code-path evidence is conclusive.
- **Distinguish confirmed defects from credible risks**:
  - **Confirmed Defect (`BLOCK`)**: Verified broken code path, test failure, runtime crash, rendered visual error, or demonstrated security sink.
  - **Credible Risk (`FIX`)**: Missing defensive check, race window under load, or non-idiomatic pattern that could cause failure under specific edge conditions.
  - **Advisory (`NIT`)**: Non-blocking stylistic, structural, or minor cleanup suggestions.
- **Do not invent defects**: Never fabricate trivial or speculative concerns to appear thorough.

---

## 4. Efficient Verification Guardrails & Reviewer Authority

Adversarial review must not degenerate into slow, repetitive full-suite re-execution or boundary violations.

### 4.1 Whole-Suite Protection
1. **Do not duplicate whole-suite checks**: Deterministic CI and pre-merge scripts (`./gradlew test`, `npm test`, `ktlintCheck`, `npm run lint`) own full regression testing. Reviewers must not routinely re-run full test suites.
2. **Targeted falsification probes**: When an implementation claim seems high-risk, stale, or untrusted, reviewers possessing command execution authority may run targeted single-test probes (e.g. `npm test -- LongDecisionsView.test.tsx`), execute a focused script, or inspect specific files.
3. **Mandatory justification**: Whenever an independent probe or execution is run, the reviewer must state why:
   `Running targeted test on LongDecisionsView.test.tsx because fixture schema validity was altered in api.ts.`
4. **Strict read-only discipline**: Reviewers evaluate and report. They **never** silently edit implementation code, tests, or configuration.

### 4.2 Role Authority Alignment
Adversarial protocols strictly respect Praxis role boundaries:

- **Whole-Diff Reviewer & Coordinator**:
  - Own whole-diff review before merge and ship.
  - Authorized to execute targeted falsification probes and cross-check the implementation against original user intent.
  - May delegate targeted investigation to a subagent ONLY when that subagent's role charter explicitly permits it and all target files are included within its assigned `FILES:` boundary.
- **Task-Level Subagents (`spec-reviewer`, `quality-reviewer`)**:
  - `spec-reviewer` remains strictly confined to verifying that the task's code matches its assigned `SPEC:` contract and acceptance criteria. Does NOT own code quality, re-running full test suites, or redesigning specifications.
  - `quality-reviewer` remains strictly confined to checking code quality, standards, and conventions within its assigned `FILES:` and diff. Does NOT own re-running acceptance tests or full test suites.
  - Subagents may inspect generated task artifacts (e.g. reading screenshot image bytes or build/test logs) when explicitly included in `FILES:`.
  - Subagents are never authorized to violate their existing Praxis role charters.

---

## 5. Specification Independence & User Intent

Reviewers must guard against **Specification Compliance Tunnel Vision**:
- A specification may faithfully capture an implementer's misunderstanding of the original user prompt or issue.
- **Assigned exclusively to Whole-Diff Reviewers and Coordinators**:
  - Cross-check the final implementation against the original issue description and user acceptance criteria, not solely the coordinator's staging spec.
  - If the spec missed an obvious user journey or inverted an acceptance criterion, flag it as a `BLOCK` or `FIX` prior to ship.
- **Task-level subagents** remain focused on their assigned milestone/task spec to avoid scope creep during atomic implementation steps.

---

## 6. Preservation of Existing Reviewer Architecture

This experimental skill integrates directly into existing roles without creating parallel structures:

- **Whole-Diff Review (`skills/review/SKILL.md`)**:
  Applies Section 1 (Evidence Hierarchy & Identity), Section 2 (Visual Inspection), and Section 3 (Boundary Probing) as the final pre-ship quality gate.
- **Task-Level Spec Review (`skills/subagents/SKILL.md:spec-reviewer`)**:
  Inspects task acceptance artifacts (screenshots, logs) within assigned `FILES:` without re-running full test suites.
- **Task-Level Quality Review (`skills/subagents/SKILL.md:quality-reviewer`)**:
  Audits diffs for conventions, security code paths, and runtime errors within assigned `FILES:`.
- **Design Spec Review (`skills/references/reviewers.md`)**:
  Ensures UI specs enumerate relevant visual states (populated, empty, loading, error, filtering) before coding starts.
- **Ponytail Review (`plugins/ponytail/skills/ponytail-review/SKILL.md`)**:
  Reinforces Ponytail's core tenet: every finding must have a concrete case; no vague anxieties.

---

## 7. Compact Risk-Based Decision Guide

Use this matrix to determine the appropriate verification depth:

| Category | Minimum Evidence Required | Independent Review Focus | Targeted Probe Justification | Blocking (`BLOCK`) vs. Advisory (`NIT`) |
|---|---|---|---|---|
| **1. Docs-Only** | Markdown diff, command runnable check. | Diff text, README commands, relative links. | Never run test suites. | Broken command or link $\rightarrow$ **BLOCK**. Missing optional elaboration $\rightarrow$ **NIT**. |
| **2. API & Contracts** | Unit tests, static typecheck logs. | Call-site arguments vs. function signatures; runtime schema validators (`isUuid`, `isRecord`). | Positional primitive arguments or untyped caller mocks. | Runtime validation crash or argument swap $\rightarrow$ **BLOCK**. |
| **3. Database & Migrations** | Migration SQL diff, test logs. | Idempotency, nullable defaults, Flyway naming conventions. | Column drops or default constraint additions. | Syntax error or invalid baseline alteration $\rightarrow$ **BLOCK**. Context-dependent `IF NOT EXISTS` $\rightarrow$ **NIT**. |
| **4. UI & Visual Changes** | Multi-viewport screenshots, component tests. | **Image bytes via image inspection**, canonical reference JSX, responsive breakpoints. | Mock fixture schema mismatch or unexpected visual layout. | Rendered error banner or broken layout $\rightarrow$ **BLOCK**. 1–2px margin difference $\rightarrow$ **NIT**. |
| **5. Accessibility (a11y)** | JSDOM accessibility tests, JSX tags. | `aria-labelledby`, `role` semantics, keyboard interactive elements. | Custom interactive elements (`<div onClick>`). | Missing accessible label or non-navigable control $\rightarrow$ **BLOCK**. |
| **6. Security-Sensitive** | Auth test diff, CSRF headers. | Cookie attributes (`HttpOnly`, `SameSite`), token handling, sanitization. | Code-path trace shows untrusted input reaching unescaped sink. | Unsanitized HTML or missing authz check $\rightarrow$ **BLOCK** (exploit not required). Theoretical threat without attack path $\rightarrow$ **NIT**. |
| **7. Concurrency & Async** | Race condition tests, effect diff. | Stale request counters (`requestVersion`), cleanup in `useEffect`, abort controllers. | Interleaving state dispatches or rapid user inputs. | Stale response overwriting fresh state $\rightarrow$ **BLOCK**. Missing cleanup on unmount $\rightarrow$ **FIX**. |

---

## 8. Retrospective Validation: ChessProbe Issue #667

To validate these instructions, trace how they resolve the ChessProbe Issue #667 failure:

1. **Trigger**: Implementer delivers 6 screenshots for the Decision Timing page and reports 825 tests passing in JSDOM.
2. **Reviewer Action under Section 1 & 2**:
   - Reviewer notes Level 1 (exit code 0) and Level 2 (files exist in `screenshots/issue-667/`).
   - Reviewer establishes Evidence Identity: checks that screenshot git status and commit SHA match current HEAD.
   - Reviewer recognizes that Level 3 (Artifact Inspection) is mandatory for visual acceptance criteria.
   - Reviewer ingests `01-dt-pop-1440.png` using an image-capable tool (`read_file`).
3. **Observation**:
   - The ingested image reveals a red banner: *"Could not load long-decision occurrences. Failed to load long decisions: unexpected response body"*.
4. **Boundary Investigation under Section 3**:
   - Reviewer inspects `scratch/capture_suite.js` and observes fixture ID `occ-1`.
   - Reviewer inspects `api.ts:384` and observes `isUuid(occurrence.id)` rejecting `occ-1`.
5. **Canonical Parity Check under Section 2.2**:
   - Reviewer compares error rendering in `LongDecisionsView.tsx` against `WeaknessesList.tsx`.
   - Observes that `LongDecisionsView.tsx` rendered an inline alert banner rather than the canonical centered surface card with an `AlertCircle` icon and retry button.
6. **Verdict**:
   - Emits an evidence-backed `BLOCK`:
     `BLOCK: Screenshot 01-dt-pop-1440.png renders an active runtime crash banner ('Failed to load long decisions: unexpected response body'). Fixture 'occ-1' in capture_suite.js fails runtime validation in api.ts:384. Furthermore, LongDecisionsView.tsx error state lacks canonical parity with WeaknessesList.tsx.`
7. **Outcome**: The defect is caught and resolved before code is merged or presented to the user.

---

## 9. Observational Pilot Protocol (15 Meaningful Reviews)

To evaluate the operational impact of this experimental layer, conduct an observational pilot over 15 consecutive, non-trivial pull requests in ChessProbe without tuning or modifying the protocol during the pilot.

### 9.1 Data Collection Fields

For each review, record:
1. **Review Context**: PR/Issue number, change category (UI, API, DB, Concurrency, Refactoring).
2. **Tool Invocations**: Additional tool calls executed (image reads, code-path audits, targeted probes).
3. **Token & Time Overhead**: Token delta and elapsed review minutes vs. standard review baseline.
4. **Findings & Precision**:
   - Count of `BLOCK`, `FIX`, and `NIT` findings emitted.
   - Count of findings confirmed and fixed by implementer vs. rejected as false positives.
5. **Independently Established Defect Capture**: Count of latent runtime, visual, or contract defects caught by adversarial checks where an independent reference existed (e.g. failing runtime schema, broken screenshot, mutation test survivor).
6. **Post-Review Escaped Defects**: Any regressions or defects identified by CI, staging, or user QA within a 7-day post-merge window.

### 9.2 Observation Hypotheses (Not Established Standards)

The numerical targets below are explicitly labeled as **hypotheses under observation**:
- **Hypothesis 1 (Finding Precision)**: $\frac{\text{Confirmed Findings}}{\text{Total Findings Emitted}} \ge 85\%$.
- **Hypothesis 2 (Tool Cost)**: Average additional tool calls $\le 4$ per review.
- **Hypothesis 3 (Token Overhead)**: Average token increase $\le 15\%$ (~1,000–1,500 tokens).
- **Hypothesis 4 (Defect Capture)**: Successfully flags Level 3/4 visual or schema defects prior to human acceptance.

*(Note: Finding precision must not be described as a complete defect-detection rate, as total latent defects in untouched code paths remain unmeasured in an observational pilot.)*
