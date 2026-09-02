# Planner -> Reviewer -> Implementer Workflow

ChessEcho uses a repository-scoped, resumable workflow for taking a GitHub issue through planning, tests, implementation, validation, and a draft pull request. It separates technical review from human authorization.

## Architecture

- `.github/agents/` defines the Orchestrator, Planner, Reviewer, and Implementer roles using GitHub Copilot custom-agent profiles.
- `scripts/agent_workflow.py` is the authoritative state machine. It exposes specific events rather than an unrestricted transition command.
- `.agent-workflow/config.json` maps issue scope to ChessEcho's existing validation commands.
- `.agent-workflow/runs/issue-<number>/` contains the issue snapshot, current `state.json`, append-only `history.jsonl`, artifacts, and validation logs.
- `scripts/tests/test_agent_workflow.py` verifies gates, revision loops, failure handling, and draft-PR blocking.

No database or external orchestrator is required. Atomic state writes and a per-run file lock make local updates interruption-safe. Commit run artifacts at approval gates when the audit trail must survive beyond the current worktree.

## State machine

```text
PLANNING
  -> PLAN_REVIEW
  -> PLANNING                              (reviewer needs revision)
  -> WAITING_FOR_PLAN_HUMAN_APPROVAL
  -> PLANNING                              (human rejects)
  -> TEST_IMPLEMENTATION                   (human approves)
  -> TEST_REVIEW
  -> TEST_IMPLEMENTATION                   (reviewer needs revision)
  -> WAITING_FOR_TEST_HUMAN_APPROVAL
  -> TEST_IMPLEMENTATION                   (human rejects)
  -> IMPLEMENTATION                        (human approves)
  -> VALIDATION
  -> TEST_IMPLEMENTATION                   (human reopens approved tests)
  -> IMPLEMENTATION                        (validation fails)
  -> FINAL_REVIEW                          (validation passes)
  -> TEST_IMPLEMENTATION                   (human reopens approved tests)
  -> IMPLEMENTATION                        (reviewer needs revision; stale final evidence cleared)
  -> DRAFT_PR_CREATED                      (reviewer ready + validation passes)
  -> WAITING_FOR_PR_HUMAN_APPROVAL
  -> WAITING_FOR_PR_HUMAN_APPROVAL         (human-authorized metadata-only revision)
  -> IMPLEMENTATION                        (human rejects and authorizes revision)
  -> PR_APPROVED                           (human approves)
```

The CLI rejects any action that is invalid in the current state. Reviewer readiness never records human approval. Validation and final-review data are invalidated when implementation revision resumes.

## Responsibilities

| Role | Responsibility | Prohibited |
|---|---|---|
| Orchestrator | Initialize the run, invoke the correct role, report state, and stop at gates | Inferring approval or bypassing the CLI |
| Planner | Inspect the issue, architecture, code, and tests; map criteria to a concrete plan | Editing production code or tests |
| Reviewer | Independently challenge plans, tests, and implementation | Editing reviewed work or granting human approval |
| Implementer | Write tests after plan approval, code after test approval, and run validation | Self-approval, weakening approved tests, or direct PR creation |
| Human | Explicitly approve or reject the plan, tests, and draft PR | N/A |

## Mandatory source-alignment and executability gate

Before every plan submission, the Planner must align the proposed work with the repository's actual source. This is a required pre-submission gate, not work delegated to the Reviewer.

For every proposed production change, the Planner must:

1. Inspect the exact symbols, functions, hooks, and components to be changed.
2. Trace relevant call sites, consumers, actual APIs and callback signatures, state ownership, lifecycle boundaries, and data flow.
3. When moving logic out of effects or other lifecycle code, trace every current state transition and name the concrete replacement trigger for each one.
4. Consider stale-state, request-ordering, concurrency, cleanup, remount, disconnect, and other lifecycle windows relevant to the change.
5. Inspect existing test helpers, mocks, render wrappers, deferred-promise sequencing, and setup before specifying tests; proposed tests must exercise reachable application lifecycle paths rather than imagined entry points.
6. Read the actual workflow and integrity implementation before relying on state transitions, artifact hashes, fingerprints, validation behavior, or sequencing. Do not infer these guarantees from documentation or conversation alone.

Immediately before submission, perform a final executability check and record concise evidence in the plan:

- every referenced symbol exists;
- every API, callback, and function signature matches the repository;
- every relocated state transition has a concrete replacement trigger;
- relevant call sites, consumers, and lifecycle paths are covered;
- stale-state and concurrency windows are addressed;
- tests exercise real application lifecycle using verified helpers and sequencing; and
- another engineer can implement the plan without rediscovering the architecture.

If any item cannot be proven, continue investigating or state the ambiguity explicitly; do not submit the plan as executable.

During plan review, the Reviewer independently spot-checks this evidence. Review findings should distinguish genuine architectural/planning disagreements from repository-verifiable `SOURCE_ALIGNMENT_DEFECT` findings that should have been caught by this gate. This classification improves the process without discouraging revisions or weakening independent review.

### Analyzer and lint cleanup plans

For analyzer, compiler-diagnostic, typecheck, or lint cleanup issues, first declare the analyzer, check, and scope owned by the issue. Source alignment then requires a complete inventory of findings belonging to that declared scope before plan submission. Include suppressions relevant to the same scope when the analyzer supports suppression or existing source/configuration may hide those findings. Do not expand the inventory to unrelated repository-wide checks or suppressions.

Every finding must map explicitly to:

| Required mapping | Evidence |
|---|---|
| Location | Exact file and line or symbol |
| Cause | Why the analyzer reports it |
| Resolution | Specific production or test change that clears it |
| Verification | Test or analyzer command proving it is resolved |

Broad architectural changes do not count as coverage by themselves. The plan must show how each scoped finding is cleared by the proposed design. Before requesting human approval, answer: **Where does every finding in the declared analyzer/check/scope go?** No scoped finding may remain unowned.

Prefer the smallest behavior-preserving change that genuinely resolves each finding. Do not include optional adjacent refactors. Every refactor must have a concrete tie to a finding, correctness requirement, or necessary architectural consequence. Suppressions, disabled rules, weakened configuration, reduced analyzer scope, or equivalent workarounds are not valid resolutions unless the issue and human approval explicitly choose them as the intended solution.

On every plan revision:

1. Re-run or reconcile the complete scoped analyzer inventory against current source.
2. Reconcile the entire plan, not only the latest review comments.
3. Remove superseded sections and stale claims instead of accumulating revision patches.
4. Check for contradictions between old and new designs.
5. Re-verify affected hooks, components, APIs, callbacks, state cells, and lifecycle behavior.
6. Submit one coherent executable plan that another engineer can follow without reconstructing which revision is authoritative.

The Reviewer must verify complete finding ownership, the absence of stale or contradictory plan sections, source-level correctness of every referenced surface, that each proposed change actually clears its mapped finding, and that optional refactors have not expanded scope. Inventory, source-alignment, or revision-hygiene defects should be classified as `SOURCE_ALIGNMENT_DEFECT` where applicable. Review success is technical readiness, not minimizing the number of legitimate revision loops.

## Git baseline and final-revision invariants

Git baseline inspection and finalization are separate. Planning needs an understood, issue-isolated baseline; mechanical final evidence begins only when the implementation is normalized for final validation.

### Planning baseline and issue isolation

During planning, the Orchestrator must:

1. Inspect the configured target base, branch ancestry, commits, and worktree.
2. Verify the branch and worktree contain only the current issue's changes plus any explicitly required workflow infrastructure.
3. Stop if commits or changes from another issue or prior work are present. Require them to be separated; never discard, rewrite, or hide unrelated work silently.
4. Prefer fetching the target base before source alignment so the plan uses a current baseline.

The fetch recommendation is agent guidance: the CLI does not contact the remote during planning and does not claim that a local tracking ref is the latest remote revision. Rebase or reconcile only when the baseline actually requires it. Do not repeatedly rewrite history before plan submission, test implementation, and production implementation. The test-first order remains unchanged: human plan approval, tests, human test approval, then production implementation.

If a later baseline change invalidates an approved plan or tests, use `reopen-plan` or `reopen-tests`; do not carry stale approval forward. Before final validation, normalize only once.

### Final normalization before validation

After implementation is complete but before submitting it for the validation that supports final review:

1. Fetch the configured target base. This latest-remote step is agent guidance, not a claim made by the CLI.
2. Reconcile with the fetched target base if needed.
3. Verify no unrelated work is mixed into the branch.
4. Normalize the current issue to exactly one commit relative to the configured local tracking ref (for example, `origin/main`). Inside a correction run the anchor is the source run's validated `HEAD` instead of the tracking ref, so exactly one *correction* commit is required on top of it (see [Corrections](#corrections)).
5. Stop and require explicit separation if unrelated commits or changes are present. Never silently drop them.
6. Record the resulting final `HEAD` SHA in the implementation report.

Only after normalization may the Implementer submit the implementation and the Orchestrator run final validation. The required order is:

```text
fetch/reconcile/squash once
  -> one clean issue commit relative to the local target-base tracking ref
  -> record final HEAD
  -> final validation
  -> final review of that same HEAD
  -> draft PR from that same HEAD
```

Before running checks, `run-validation` mechanically captures a clean worktree, `HEAD`, the run's base ref and its resolved SHA, workspace fingerprint, approved-test fingerprint, ancestry, and the exactly-one-commit invariant. For a normal run the base ref is the configured local target-base ref; for a correction run it is the synthetic `parent-run-head:<sha>` label naming the source run's validated `HEAD`. After every check finishes, it recaptures the same evidence and requires that base SHA to have remained unchanged for the duration of validation. Any command-induced file/test/HEAD change, base-ref movement, dirty worktree, ancestry change, or commit-count change invalidates the run, clears stale evidence, records `VALIDATION_INVALIDATED`, and returns to `IMPLEMENTATION` without certifying PASS results.

Successful validation freezes that resolved base SHA in structured `validation_evidence`. Final review, draft creation, metadata revision, and approval verify ancestry and the one-commit invariant against the frozen SHA, not the later value of mutable `origin/main`. Therefore unrelated target-base advances after validation do not invalidate unchanged evidence. Final validation, final review, and draft-PR preparation must refer to one identical final `HEAD`; reviewer readiness records the reviewed `HEAD`, and GitHub's remote head must match it. A same-tree history rewrite cannot pass. The submitted implementation report is hash-protected after submission, but its prose is reviewer-readable context; structured workflow state is the mechanical authority for Git evidence.

The Orchestrator may write a human-readable validation summary, but it is guidance rather than an additional machine-validated artifact. The state machine's structured validation record is authoritative. Keep any internal PR-preparation notes separate from `pr-body.md` so the body retains the exact public heading format below.

Do not rewrite history after final validation. If the workspace or `HEAD` changes after validation, the Reviewer records `NEEDS_REVISION`; that auditable event returns to `IMPLEMENTATION` and clears stale validation and final-review evidence. Resubmit, rerun validation, and repeat final review before creating the draft PR. Reviewer `READY_FOR_HUMAN_APPROVAL` remains prohibited for changed evidence.

## Starting from a GitHub issue

Select the `chess-echo-orchestrator` custom agent and ask it to run the workflow for an issue. The exact initialization command is:

```bash
python3 scripts/agent_workflow.py init ISSUE
```

The issue's `backend`, `frontend`, or `full-stack` label selects the repository-pinned validation profile. If labels do not identify a scope, specify it; an explicit scope cannot override a conflicting issue label:

```bash
python3 scripts/agent_workflow.py init ISSUE --scope frontend
```

A workflow-tooling-only issue carries none of those labels and is initialized with `--scope workflow-tooling`.

Inspect resumable status at any time:

```bash
python3 scripts/agent_workflow.py status ISSUE
```

## Agent and human events

Agents write artifacts inside `.agent-workflow/runs/issue-<number>/artifacts/`, then record them:

```bash
python3 scripts/agent_workflow.py submit-plan ISSUE \
  --artifact .agent-workflow/runs/issue-ISSUE/artifacts/plan.md \
  --agent chess-echo-planner

python3 scripts/agent_workflow.py review-plan ISSUE \
  --status READY_FOR_HUMAN_APPROVAL \
  --artifact .agent-workflow/runs/issue-ISSUE/artifacts/plan-review.md \
  --reviewer chess-echo-reviewer
```

At a waiting state, the Orchestrator must show the artifact and review, stop, and obtain explicit human authorization. It then records that authorization:

```bash
python3 scripts/agent_workflow.py approve-plan ISSUE --by GITHUB_LOGIN --confirm plan_approved
python3 scripts/agent_workflow.py approve-tests ISSUE --by GITHUB_LOGIN --confirm tests_approved
python3 scripts/agent_workflow.py approve-pr ISSUE --by GITHUB_LOGIN --confirm "I approve this draft PR."
```

Rejections require a durable reason and return to the relevant producer:

```bash
python3 scripts/agent_workflow.py reject-plan ISSUE --by GITHUB_LOGIN --reason "Missing migration rollback strategy"
python3 scripts/agent_workflow.py reject-tests ISSUE --by GITHUB_LOGIN --reason "AC3 is not covered"
python3 scripts/agent_workflow.py reject-pr ISSUE --by GITHUB_LOGIN --reason "Revise the implementation"
```

`reject-pr` authorizes implementation changes, returns to `IMPLEMENTATION`, and clears validation/final-review evidence. For a title/body-only correction to the same open draft, the human instead uses the audited metadata-only path:

```bash
python3 scripts/agent_workflow.py revise-pr-metadata ISSUE \
  --by GITHUB_LOGIN \
  --reason "Clarify the summary" \
  --title "Revised title" \
  --body-file .agent-workflow/runs/issue-ISSUE/artifacts/pr-body.md
```

This command validates the body, requires the same workspace, configured base, validated/reviewed `HEAD`, and open draft, and never performs an unsafe automatic metadata write. The authorized human first supplies the desired title/body to the command; if the live draft still has the recorded metadata, the command instructs them to apply that exact change through GitHub and rerun. The rerun accepts only the exact requested title/body, then atomically records its new fingerprint while remaining at `WAITING_FOR_PR_HUMAN_APPROVAL`. Unrelated external metadata changes fail closed. Repeating an already-applied authorized revision is idempotent. The path cannot authorize code, history, workspace, head, or base changes.

If implementation or validation reveals a material problem in already approved tests, the human can explicitly reopen the test gate from `IMPLEMENTATION`, `VALIDATION`, or `FINAL_REVIEW`; downstream implementation and final evidence are invalidated:

```bash
python3 scripts/agent_workflow.py reopen-tests ISSUE --by GITHUB_LOGIN --reason "The approved expectation is incomplete"
```

If later work reveals a material design problem, the human can reopen the plan gate. This also revokes any test approval:

```bash
python3 scripts/agent_workflow.py reopen-plan ISSUE --by GITHUB_LOGIN --reason "Implementation exposed a material design gap"
```

## Tests before implementation

After plan approval, the Implementer writes tests only and submits a report:

```bash
python3 scripts/agent_workflow.py submit-tests ISSUE \
  --artifact .agent-workflow/runs/issue-ISSUE/artifacts/test-report.md \
  --agent chess-echo-implementer
```

The Reviewer records `NEEDS_REVISION` or `READY_FOR_HUMAN_APPROVAL` with `review-tests`. Production implementation remains impossible until explicit test approval.

## Validation and final review

After implementation submission, run:

```bash
python3 scripts/agent_workflow.py run-validation ISSUE
```

Before executing checks, the command enforces the configured local base ancestry, one-commit final history, clean worktree, and approved-test fingerprint. It then executes and records every required check:

| Scope | Required commands |
|---|---|
| Backend | `./gradlew ktlintCheck`; `./gradlew test` |
| Frontend | `npm run lint`; `npx tsc --noEmit`; `npm run test`; `npm run build` in `frontend/` |
| Full stack | All backend and frontend commands |
| Workflow tooling | `make agent-workflow-test` |

The `workflow-tooling` scope is for issues that change only the workflow tooling under `scripts/`. Scope inference reads only the `backend`, `frontend`, and `full-stack` labels, so such an issue must carry none of them and must pass `--scope workflow-tooling` explicitly. An issue that changes workflow tooling *and* product code stays on its product scope and needs the workflow tooling test path added to that run's frozen contract at `init`.

A failure returns the workflow to `IMPLEMENTATION`. A pass moves it to `FINAL_REVIEW`, where the Reviewer records its verdict with `review-final`.

## Draft pull request gate

Prepare a concise PR body that explains the actual change and reasoning rather than reproducing the plan or implementation report. It must begin with and contain exactly these rendered level-2 headings in this order, with visible non-empty content in every section and no additional level-2 headings. Content before `## What` is prohibited; level-3 subsections and visible content following the final heading belong to their enclosing required section. Headings or content hidden in comments or fenced code do not satisfy the contract.

```markdown
## What

Concise changed behavior.

## Why

Problem and rationale for the chosen approach.

## Testing

Validation performed, including relevant test, typecheck, build, and lint results.
```

Record the final reviewed `HEAD` SHA in the PR preparation artifact and confirm it matches the normalized implementation and final-review artifact. Create the PR only with:

```bash
python3 scripts/agent_workflow.py create-draft-pr ISSUE \
  --title "Handle puzzle and weakness loading failures" \
  --body-file .agent-workflow/runs/issue-ISSUE/artifacts/pr-body.md
```

The command validates the body and refuses to call GitHub unless all configured checks passed, the final Reviewer recorded `READY_FOR_HUMAN_APPROVAL`, frozen-base ancestry and one-commit history still hold, and current/validated/reviewed heads and workspace evidence match. Creation and lookup are explicitly scoped to the run's configured repository and current head branch, and returned PR URLs must identify that repository. Lookup/auth/network/JSON failures and ambiguous matches fail closed. A matching draft created before a local interruption is adopted only when base, head, title, and body exactly match. Persisted `DRAFT_PR_CREATED` recovery re-reads and verifies the live PR. Ordinary creation/recovery never edits mismatched metadata; use the explicit metadata-revision command.

At that state, do not merge, mark ready, deploy, close the issue, or continue implementation. `approve-pr` records the final human decision; it does not merge or mark the PR ready.

## Audit and recovery

`state.json` is the resumable snapshot and contains the complete sequenced event history. `history.jsonl` is an atomically regenerated, append-only audit view of those events, so a process interruption cannot leave it authoritative over conflicting state. Submitted artifacts include SHA-256 hashes, so later edits are detectable. Validation configuration accepts only safe filename-slug check names, non-empty string argument lists, and working directories that resolve inside the repository; execution revalidates those constraints. Validation logs record command, working directory, output, exit code, and timestamps.

Plan and test approvals verify that the human is seeing the exact artifacts and tests the Reviewer marked ready. Approved plan/review and test/review artifacts are rechecked at every downstream gate. Plan approval freezes a fingerprint of every non-test file through the test-review gate, preventing production implementation before test approval. Test approval records a fingerprint of the test files, and implementation submission refuses changed approved tests. Fingerprints include file type and permissions and cover Git-tracked plus non-ignored untracked files. Successful validation records workspace, `HEAD`, and the frozen base revision; final review records workspace and reviewed `HEAD`. Draft-PR creation requires all evidence to match, frozen-base ancestry and one-commit history to remain valid, all non-run changes to be committed, and no Git `assume-unchanged` or `skip-worktree` flags. If GitHub creates the PR but the local process stops before recording it, rerunning `create-draft-pr` reconciles only an open draft with the expected base, reviewed head, title, and body instead of creating another. After an implementation-level PR rejection completes a new validated/reviewed cycle, changed title/body metadata on the existing draft requires the explicit human-authorized `revise-pr-metadata` path.

Final PR approval verifies that the workspace, Git revision, base branch, draft status, title, body, and remote PR head are unchanged since draft creation.

Do not manually edit state or history. If an agent or command fails before recording an event, inspect `status` and rerun the action appropriate to that unchanged state.

## Corrections

A run that has reached `WAITING_FOR_PR_HUMAN_APPROVAL` or `PR_APPROVED` is never reopened or mutated. A bounded post-approval fix instead forks an immutable, linked correction run:

```bash
python3 scripts/agent_workflow.py start-correction ISSUE \
  --classification implementation-only \
  --by GITHUB_LOGIN --reason "Post-approval API contract fix" \
  [--from-correction N]
```

The correction is a child run beside its source, and every later command addresses it with `--correction N`:

```text
.agent-workflow/runs/issue-ISSUE/                 # source run, never written by a correction
.agent-workflow/runs/issue-ISSUE/corrections/1/   # child state.json, history.jsonl, artifacts/, validation/
```

```bash
python3 scripts/agent_workflow.py submit-implementation ISSUE --correction 1 \
  --artifact .agent-workflow/runs/issue-ISSUE/corrections/1/artifacts/implementation-report.md \
  --agent chess-echo-implementer
python3 scripts/agent_workflow.py run-validation ISSUE --correction 1
python3 scripts/agent_workflow.py status ISSUE --correction 1
```

`--correction` is accepted by every subcommand except `init` and `start-correction`. Artifacts, validation logs, state, and history for a correction live under the child directory; artifacts recorded for a correction must be inside its own `artifacts/` directory.

### Classifications and evidence

| Classification | Entry state | Inherited | Invalidated |
|---|---|---|---|
| `metadata-only` | `WAITING_FOR_PR_HUMAN_APPROVAL` | All six artifacts, plan and test approvals, all validation/final/draft evidence | PR approval |
| `implementation-only` | `IMPLEMENTATION` | Plan, plan review, test report, test review, plan and test approvals | Implementation report, final review, PR approval, all validation/final/draft evidence |
| `test-contract` | `TEST_IMPLEMENTATION` | Plan, plan review, plan approval | Test evidence, implementation evidence, PR approval, all validation/final/draft evidence |
| `architecture` | `PLANNING` | Nothing | Everything |

Each child records `correction` (number, classification, reason, requesting human, timestamp, and the exhaustive, disjoint `inherited`/`invalidated` token lists) and `parent_run` (issue, source correction number, source state, source validated head/base, and the SHA-256 of the source `state.json` and `history.jsonl` at fork time). Inherited artifact records still point at the source run's files, so any later edit to inherited evidence fails the correction closed at every downstream gate. A `test-contract` correction re-derives the inherited plan approval's non-test fingerprint, exactly as `reopen-tests` does, so the child's test phase measures the current tree.

### Fail-closed fork checks

`start-correction` reads its source read-only and writes nothing outside the new child directory. It refuses to fork unless the source is at one of the two PR-gate states with a recorded validated head and base, the current `HEAD` descends from that validated head with at most one commit on top of it, and the worktree is clean. A `metadata-only` fork additionally requires the live workspace fingerprint and `HEAD` to equal the source's validated values and all approved source artifacts to be unchanged; a changed tree or head is refused rather than accepted under a weaker label, and the operator must re-run with a code-changing classification.

Misclassification discovered later escalates through the existing human commands inside the child run: an `implementation-only` correction that must change approved tests uses `reopen-tests ISSUE --correction N`, and a `test-contract` correction that must change non-test files uses `reopen-plan ISSUE --correction N`. There is no de-escalation back to a narrower class.

### Chaining, siblings, and validation anchoring

Numbering is flat per issue. A new correction is refused while any other correction for the issue is in flight, that is, in any state before its own PR gate; multiple settled corrections may coexist. When the latest correction validated a different `HEAD` from the selected source, every later correction must use `--from-correction N` with that latest correction number. This mechanically keeps code-changing history linear and prevents newer settled evidence from being orphaned. Settled metadata-only corrections may remain siblings because they retain the same validated `HEAD`. `status ISSUE` lists every correction with its number, classification, state, requesting human, and creation time. A `corrections/<n>/` directory without `state.json`, such as one created by a mistyped `--correction`, is ignored by both the listing and numbering.

Inside a correction, validation anchors on the source run's validated `HEAD` rather than the configured target base: `run-validation` resolves that SHA, records it as `validated_base` with the synthetic `parent-run-head:<sha>` base ref, and still requires exactly one commit relative to it. Every other safeguard is unchanged — clean worktree, ancestry, validated/reviewed head equality, frozen final review, and the draft-PR fingerprint checks all apply to the correction's own evidence. The latest-correction requirement above prevents selecting an older anchor whose branch history could orphan newer settled evidence.

The draft PR follows existing behaviour. A code-changing correction starts with no recorded draft, so `create-draft-pr ISSUE --correction N` adopts the still-open draft when its base, head, title, and body match, otherwise routes metadata differences through `revise-pr-metadata ISSUE --correction N`, and opens a fresh draft when no open PR matches the branch. A `metadata-only` correction inherits the draft record and goes straight to `revise-pr-metadata` and `approve-pr`; it fails closed if that pull request is no longer an open draft.

The source run's `issue.md` remains the authoritative issue snapshot for its corrections; child runs do not copy it.

## Limitations

- Agent invocation is coordinated by Copilot rather than a continuously running service.
- Human identity and the exact stage confirmation are recorded, but a local process with repository write access remains a trust boundary and can impersonate `--by`.
- Durable run files remain local until committed or otherwise preserved with the branch.
- GitHub CI runs independently after draft PR creation; this workflow gates creation on local validation, not later CI status.
- Final PR approval is recorded but intentionally does not merge or mark the draft ready.
- A correction that is in flight blocks starting another correction for the same issue; drive it back to its PR gate first. Nothing is permanently marked, so completing it unblocks the issue.
- Validation subprocess output is captured in memory before it is written to logs; output is not currently size-bounded.
