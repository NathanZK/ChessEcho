# ChessEcho Agent Instructions

## Repository conventions

- Preserve the Kotlin/Spring Boot backend and Next.js frontend architecture described in `README.md`.
- Keep changes focused on the issue being addressed. Do not perform unrelated refactors.
- Read `docs/engineering/repository-conventions.md` for the default pre-deployment
  assumption and the baseline-first migration convention before touching
  `src/main/resources/db/migration`. Do not rediscover this convention per issue.
- Backend validation: `./gradlew ktlintCheck` and `./gradlew test`.
- Frontend validation: `npm run lint`, `npx tsc --noEmit`, `npm run test`, and `npm run build` from `frontend/`.
- Follow the additional generated instructions in `frontend/AGENTS.md` for frontend work.
- Target pull requests at `main`, use a focused branch, reference the issue, and summarize changes and validation.

## Issue workflow

When a human asks to run the issue workflow:

1. Read `docs/engineering/agent-workflow.md`.
2. Use `scripts/agent_workflow.py` as the only workflow state authority.
3. Store artifacts in `.agent-workflow/runs/issue-<number>/artifacts/`.
4. Use the four role profiles in `.github/agents/`:
   - `chess-echo-planner`
   - `chess-echo-reviewer`
   - `chess-echo-test-implementer`
   - `chess-echo-implementer`
5. Never infer human approval. Only explicit `approve-plan`, `approve-tests`, and `approve-implementation` commands advance human gates.
6. Keep validation bounded by using `python3 scripts/agent_workflow.py run-validation ISSUE`.
7. Never create a draft pull request directly; use `create-draft-pr`.
8. Use exactly `## What`, `## Why`, and `## Testing` in governed draft PR bodies. The reusable human-facing scaffold for ordinary pull requests is `.github/PULL_REQUEST_TEMPLATE.md`; it does not replace workflow-owned evidence or controls.

The workflow applies only when explicitly started for an issue; normal repository tasks do not need a workflow run.
