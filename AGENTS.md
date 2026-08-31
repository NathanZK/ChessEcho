# ChessEcho Agent Instructions

## Repository conventions

- Preserve the Kotlin/Spring Boot backend and Next.js frontend architecture described in `README.md`.
- Keep changes focused on the issue being addressed. Do not perform unrelated refactors.
- Backend validation: `./gradlew ktlintCheck` and `./gradlew test`.
- Frontend validation: `npm run lint`, `npx tsc --noEmit`, `npm run test`, and `npm run build` from `frontend/`.
- Follow the additional generated instructions in `frontend/AGENTS.md` for frontend work.
- Target pull requests at `main`, use a focused branch, reference the issue, and summarize changes and validation.

## Issue workflow

When a human asks to run the Planner -> Reviewer -> Implementer workflow for a GitHub issue:

1. Read `docs/engineering/agent-workflow.md`.
2. Use `.github/agents/chess-echo-orchestrator.md` as the coordinating role.
3. Treat `scripts/agent_workflow.py` as the authority for workflow state. Never bypass it by editing state files.
4. Store plans, reviews, and reports in `.agent-workflow/runs/issue-<number>/artifacts/`.
5. Before plan submission, complete the mandatory source-alignment and executability gate in `docs/engineering/agent-workflow.md`; a plan based on assumed symbols, signatures, lifecycle, tests, or workflow behavior is not review-ready.
6. Analyzer and lint cleanup plans must inventory every finding, including relevant suppressions, within the issue's declared analyzer/check/scope; every scoped finding needs an owned root-cause resolution and verification before human approval.
7. Inspect the planning baseline and stop if unrelated work is present. Immediately before final validation, fetch the target base and normalize the issue to one commit relative to its local tracking ref. The CLI mechanically requires that ancestry and commit count and binds validation, final review, and draft-PR preparation to one unchanged final `HEAD`.
8. Use exactly `## What`, `## Why`, and `## Testing` in the concise draft-PR body.
9. Never infer human approval. Only explicit `approve-plan`, `approve-tests`, and `approve-pr` events authorize their transitions.
10. Never create a draft pull request directly. Use `create-draft-pr`, which enforces validation and final-review prerequisites.

The Planner, Reviewer, and Implementer profiles are in `.github/agents/`. The workflow applies only when explicitly started for an issue; normal repository tasks do not need a workflow run.
