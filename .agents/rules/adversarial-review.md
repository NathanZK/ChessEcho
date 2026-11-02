# ChessProbe Adversarial Review Rules

These rules govern review stages within ChessProbe to prevent verification credulity, uninspected visual failures, and silent runtime boundary mismatches.

## 1. Automatic Activation & Scope

When performing review stages in ChessProbe (including Whole-Diff Review via `review`, pre-ship verification via `ship`, or coordinator-level quality review):

1. **Conditional Activation**:
   - Automatically load and apply the protocols in `.agents/skills/adversarial-review/SKILL.md` whenever changes touch:
     - User-facing UI, layout, CSS/Tailwind styling, or rendered components.
     - API contracts, endpoint signatures, client libraries, or request/response validation.
     - Database migrations, schema constraints, or persistence transactions.
     - Authentication, authorization, session management, or CSRF protection.
     - Concurrency, asynchronous state flows, React effect lifetimes, or abort controls.
     - Critical data integrity boundaries or untrusted external inputs.
   - **Refactoring & Performance**: Do not categorically exclude refactoring. If refactored code touches external contracts, shared services, persistence logic, or runtime boundaries, apply the relevant adversarial checks for those boundaries.
   - **Exemptions**: Pure documentation changes, typo fixes, or internal unit-test updates that do not alter application behavior or public contracts do not require deep adversarial boundary audits.

2. **Visual Verification Trigger**:
   - Mandate image-content inspection ONLY when visual correctness, component layout, or visual regression testing is relevant to the change or acceptance criteria.
   - Do not demand screenshot captures or image reads for changes with zero visual surface.

## 2. Reviewer Role Alignment & Authority Boundaries

Adversarial protocols strengthen existing Praxis review stages without violating established role charters:

- **Whole-Diff Reviewer & Coordinator**:
  - Hold primary authority to execute targeted falsification probes and cross-check the implementation against the original user prompt or issue acceptance criteria (guarding against specification-tunnel vision).
  - May delegate targeted investigation to a subagent ONLY if the receiving role's charter permits it and all target files are explicitly included within its assigned `FILES:` boundary.
- **Task-Level Subagents (`spec-reviewer`, `quality-reviewer`)**:
  - `spec-reviewer` remains strictly confined to verifying that the task's code matches its assigned `SPEC:` contract and acceptance criteria. It does not redesign the spec, judge code quality, or re-run full test suites.
  - `quality-reviewer` remains strictly confined to checking code quality, standards, and convention compliance within its assigned `FILES:` and diff.
  - Both roles may inspect generated task artifacts (e.g. reading screenshot image bytes or build/test logs) when explicitly assigned in `FILES:`, but neither role re-runs full test suites.

## 3. Evidence Rigor & Falsification Standards

1. **Evidence Identity Gate**:
   - Establish that reported verification artifacts (test logs, screenshots, build outputs) correspond to the exact revision under review (matching Git HEAD SHA, clean working tree status, and relevant diff).
   - Do not rely on timestamps alone. Treat stale, mismatched, or unverifiable evidence as insufficient proof—not as automatic proof of a defect.
2. **Code-Path Proof for Security & Architectural Flaws**:
   - A verified code-path demonstration showing untrusted input reaching an unprotected sink, or a missing transaction around multi-step writes, is sufficient to issue a `BLOCK`. Reviewers do not need to construct an active exploit or live reproduction.
3. **No Fabricated Findings**:
   - Every finding requires a concrete case: `"Input/condition X leads to incorrect result Y"`. Distinguish confirmed defects (`BLOCK`) from plausible edge risks (`FIX` or `NIT`).
