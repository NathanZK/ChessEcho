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
