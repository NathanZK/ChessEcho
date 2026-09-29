# ChessEcho Technical Specification

purpose:      Find recurring chess positions where players repeatedly make weaker moves, then provide personalized practice.

user:         Chess players analyzing imported games and practicing recurring positions.

use-case:     Import games → identify recurring positions → analyze moves with Stockfish → rank weaknesses → practice with interactive puzzles.

architecture:
  - **Backend**: Kotlin/Spring Boot REST API; persistence, authentication, game import and analysis, puzzle/progress APIs, and internal corpus administration.
  - **Frontend**: Next.js single-page application (`frontend/src/app/page.tsx`) with Import Games, Weaknesses Library, and Practice Puzzles tabs; separate `login` and `register` routes.
  - **Frontend layers**: `app` routes, `components` UI, `services/api.ts` backend client, `utils` helpers; tests in `frontend/src/__tests__`.
  - **Persistence**: PostgreSQL stores users, accounts, games, positions, occurrences, analysis, jobs, training events, and human-move corpus data.
  - **Analysis flow**: imported games are replayed; positions use four-field FEN identity; qualifying positions are evaluated by Stockfish; weakness ranking is calculated per account.
  - **Backend modules**: `controller` and `dto` expose HTTP boundaries; `service` implements application behavior; `domain` models persisted concepts; `repository` uses Spring Data JPA; `config` and `web` provide framework and request/security integration.
  - **Human-move subsystem**: `humanmove` and related services build empirical human-move distributions by rating band; see `docs/architecture/human-move-provider.md` and `docs/engineering/human-move-corpus-portability.md`.

stack:
  - **Backend**: Kotlin 2.0, Spring Boot 3.3.2, Spring Data JPA, Flyway, PostgreSQL 16, kchesslib, Stockfish.
  - **Frontend**: Next.js 16, React 19, TypeScript, react-chessboard, chess.js, Tailwind CSS 4.
  - **Build and runtime**: Gradle and Java 21; npm and Node.js 20; Docker Compose for PostgreSQL and the application.
  - **Tests**: JUnit 5, Mockito, H2, Testcontainers, Vitest, and Testing Library.
  - **CI**: GitHub Actions workflow at `.github/workflows/ci.yml`.

entry:
  - Backend: `./gradlew bootRun`; containerized runtime: `docker compose up`. Swagger UI is available at `/swagger-ui.html`.
  - Frontend: `cd frontend && npm ci && npm run dev`; the default API base is `http://localhost:8080/api`.
  - Database: `make db-up`; schema is defined in `src/main/resources/db/migration/V1__baseline.sql`.
  - Backend outbound Chess.com requests use configured `CHESS_PUBAPI_USERNAME` and `CHESS_PUBAPI_CONTACT` for the required User-Agent. See `README.md`.

contract:
  - **Account association**: `POST /api/accounts` associates an account with the authenticated user; `GET /api/accounts` lists that user's accounts. See `AccountController` and `API_CONTRACT.md`; frontend connected-account state is specified in `docs/specs/connected-account-state.md`.
  - **Authentication/session**: `POST /api/register`, `POST /api/login`, `GET /api/me`, and `POST /api/logout`; session cookie `CHESSECHO_SESSION`. See `docs/architecture/identity-and-session.md`.
  - **Development session**: `POST /api/dev/session` exists only under `dev`/`local` profiles with `chessecho.auth.dev-mode.enabled=true`; otherwise it returns 404 (`DevSessionController`).
  - **Import and jobs**: `POST /api/games/import` creates an asynchronous job; `GET /api/jobs/{id}` reports ingestion and analysis progress.
  - **Private selectors**: authenticated private requests select an owned account by `accountId`; guest requests use platform and username. An authenticated guest-shaped import returns `400 ACCOUNT_SELECTION_REQUIRED`.
  - **Analysis and practice**: `/api/positions/weaknesses` and `/api/positions/{positionId}/progress` expose weakness and progress reads; `/api/puzzles`, `/api/puzzles/continuation`, `/api/puzzles/evaluate-move`, `/api/puzzles/attempt`, and `/api/puzzles/events` support puzzles, continuation, evaluation, attempts, and events.
  - **Human-move corpus administration**: `/api/admin/human-move-distribution/*` covers BFS acquisition, corpus runs/checkpoints, artifact export/verify/import/purge, projections, and cross-cohort comparison.
  - **Errors and security**: API errors use structured responses; state-changing requests require the session's double-submit CSRF token. See `API_CONTRACT.md`.

flow:
  - import: `POST /api/games/import` → resolve owned or unclaimed account → async job fetches and replays Chess.com games → store position occurrences.
  - analysis: import ingestion completes → positions reached ≥5 times (`EngineAnalysisOrchestrator`) → Stockfish depth 16 (`EngineAnalysisService`) → stored position and move evaluations.
  - weakness: weakness/puzzle request → on-demand per-account aggregation with recency-weighted priority (`README.md`) → paginated weaknesses or puzzles.
  - practice: puzzle move → evaluate/continue/attempt/event endpoints → feedback and recorded training events.
  - Import and ingestion details: `README.md` and `API_CONTRACT.md`.

invariant:
  - Position identity includes piece placement, side to move, castling rights, and en-passant availability.
  - Occurrence counts are scoped by account and player color.
  - Engine analysis is stored per position and shared across accounts; weakness ranking is computed per account.
  - `userId`, usernames, account UUIDs, and job UUIDs are never bearer credentials (`API_CONTRACT.md`).
  - Authenticated reads and imports select an account by UUID and verify ownership; guest reads and imports select only unclaimed accounts.
  - `AsyncJob` rejects updates that change its persisted import configuration, including account, date range, time controls, and player color.
  - Before deployment, schema evolution updates the V1 baseline rather than adding synthetic Flyway versions. See `docs/engineering/repository-conventions.md`.

constraint:
  - Stockfish runs as a subprocess; analysis can take several minutes for large histories.
  - Exact recurring positions are analyzed; abstract strategic patterns are not detected.
  - Engine evaluation loss can produce false positives and does not necessarily indicate a practical weakness.
  - Practical-evidence ranking is optional and disabled by default; its calibration requirements are documented in `README.md`.
  - Import jobs accept only `CHESS_COM` (`AsyncJob` validation); Lichess is not implemented.
  - Fidenaut runtime and manifest are external to this repository. Fidenaut owns governed workflow mechanics; ChessEcho owns repository identity, target branch, role profiles, application validation, and product behavior. The issue workflow is used only when explicitly started. See `docs/engineering/agent-workflow.md` and `AGENTS.md`.

convention:
  - **Quality checks**: backend `./gradlew ktlintCheck` and `./gradlew test`; frontend `npm run lint`, `npx tsc --noEmit`, `npm run test`, and `npm run build` from `frontend/`.
  - **Error handling**: exceptions map to structured JSON errors with codes in `GlobalExceptionHandler`; codes are listed in `API_CONTRACT.md`.
  - **Security**: server-side sessions, double-submit CSRF on state changes, and owner-scoped account access; see `docs/architecture/identity-and-session.md`.
  - **Tests**: backend tests under `src/test/kotlin/com/chessecho/{controller,service,repository,integration,...}`; integration tests use Testcontainers PostgreSQL.
  - **CI**: `.github/workflows/ci.yml` runs backend ktlint and tests (Java 21) and frontend lint, typecheck, Vitest, and build (Node 20).
  - **Persistence**: follow the pre-deployment baseline-first migration convention in `docs/engineering/repository-conventions.md`.
  - **Workflow**: keep changes issue-focused; target pull requests at `main`; follow `AGENTS.md` and `docs/engineering/agent-workflow.md` when an issue workflow is explicitly started.
