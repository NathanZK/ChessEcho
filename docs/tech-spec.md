# ChessEcho Technical Specification

purpose:      Find recurring chess positions where players repeatedly make weaker moves, then provide personalized practice.

user:         Chess players analyzing imported games and practicing recurring positions.

use-case:     Import games → identify recurring positions → analyze moves with Stockfish → rank weaknesses → practice with interactive puzzles.

architecture:
  - **Backend**: Kotlin/Spring Boot REST API; persistence, authentication, game import and analysis, puzzle/progress APIs, and internal corpus administration.
  - **Frontend**: Next.js single-page application (`frontend/src/app/page.tsx`) with Import Games, Weaknesses Library, and Practice Puzzles tabs; separate `login` and `register` routes.
  - **Frontend layers**: `app` routes, `components` UI, `services/api.ts` backend client, `utils` helpers; tests in `frontend/src/__tests__`. Current route, state, and API-to-UI traces: [frontend architecture](architecture/frontend.md).
  - **Persistence**: PostgreSQL stores users, accounts, games, positions, occurrences, analysis, jobs, training events, and human-move corpus data.
  - **Analysis flow**: imported games are replayed; positions use four-field FEN identity; qualifying positions are evaluated by Stockfish; weakness ranking is calculated per account.
  - **Backend modules**: `controller` and `dto` expose HTTP boundaries; `service` implements application behavior; `domain` models persisted concepts; `repository` uses Spring Data JPA; `config` and `web` provide framework and request/security integration.
  - **Human-move subsystem**: `humanmove` and related services build empirical human-move distributions by rating band; see `docs/architecture/human-move-provider.md` and `docs/engineering/human-move-corpus-portability.md`.
  - **Reference-coverage analysis**: Retained-evidence admission and coverage calculations are documented in `docs/specs/reference-coverage-analysis.md`.
  - **Chess account data boundary**: Shared imported data, per-user training history, disconnect behavior, and import-job authorization are documented in `docs/specs/account-data-boundary.md`.
  - **Reference-evidence subsystem**: The explicit-input producer creates #430 snapshots; retained admission and coverage are documented in `docs/specs/evaluation-evidence-producer.md` and `docs/specs/reference-coverage-analysis.md`.

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
  - **Chess accounts**: `POST /api/accounts` connects an account; `GET /api/accounts` lists the caller's connections; `DELETE /api/accounts/{accountId}/connection` disconnects it. See `API_CONTRACT.md` and `docs/specs/connected-account-state.md`.
  - **Authentication/session**: `POST /api/register`, `POST /api/login`, `GET /api/me`, and `POST /api/logout`; session cookie `CHESSECHO_SESSION`. See `docs/architecture/identity-and-session.md`.
  - **Development session**: `POST /api/dev/session` exists only under `dev`/`local` profiles with `chessecho.auth.dev-mode.enabled=true`; otherwise it returns 404 (`DevSessionController`).
  - **Import and jobs**: `POST /api/games/import` requires the authenticated user's connected account; `GET /api/jobs/{id}` is available to the initiating user, or to guests only while the account is unclaimed.
  - **Account data access**: authenticated shared-data reads select any existing account by `accountId`; guest reads use platform and username for an unclaimed account. Personal training reads are scoped to the authenticated user and selected account.
  - **Analysis and practice**: `/api/positions/weaknesses` and `/api/positions/{positionId}/progress` expose weakness and progress reads; `/api/puzzles`, `/api/puzzles/continuation`, `/api/puzzles/evaluate-move`, `/api/puzzles/attempt`, and `/api/puzzles/events` support puzzles, continuation, evaluation, attempts, and events.
  - **Position Progress**: Dated actual-game encounters form a separate historical baseline and one aggregate observation per interval started by a persisted `SOLVED` puzzle event. Intervals use `Game.playedAt`, remain reconstructible after late imports, and reuse `GameOutcomeNormalizer`; see `docs/specs/position-progress.md`.
  - **Actual-game results display**:
    - The Weaknesses Library displays supplied W/D/L, score rate, and eligible-game count without confidence classifications or training comparisons.
    - Null scores or zero eligible games show neutral unavailable-score wording; absent practical evidence omits the block.
  - **Practical weakness priority**: Confidence-gated, account/position/color-scoped distinct-game score rates adjust recommendation priority using the enabled normal policy; objective priority is unchanged. See `README.md` and `docs/architecture/precomputed-weakness-analysis.md`.
  - **Evaluation evidence**: Internal `EvaluationEvidenceProducerService.produce` creates #430 snapshots from explicit input and verifies #450 before persistence; see `docs/specs/evaluation-evidence-producer.md`.
  - **Human-move corpus administration**: `/api/admin/human-move-distribution/*` covers BFS acquisition, corpus runs/checkpoints, artifact export/verify/import/purge, projections, and cross-cohort comparison.
  - **Errors and security**: API errors use structured responses; state-changing requests require the session's double-submit CSRF token. See `API_CONTRACT.md`.

flow:
  - import: `POST /api/games/import` → resolve owned or unclaimed account → async job fetches and replays Chess.com games → store position occurrences.
  - analysis: import ingestion completes → positions reached ≥5 times (`EngineAnalysisOrchestrator`) → Stockfish depth 16 (`EngineAnalysisService`) → stored position and move evaluations.
  - weakness: weakness/puzzle request → account-derived aggregation plus the caller's account-scoped training history → paginated weaknesses or puzzles.
  - practice: puzzle move → evaluate/continue/attempt/event endpoints → feedback and recorded training events.
  - Import and ingestion details: `README.md` and `API_CONTRACT.md`.

invariant:
  - Position identity includes piece placement, side to move, castling rights, and en-passant availability.
  - Occurrence counts are scoped by account and player color.
  - Engine analysis is stored per position and shared across accounts; weakness ranking is computed per account.
  - `userId`, usernames, account UUIDs, and job UUIDs are never bearer credentials (`API_CONTRACT.md`).
  - Authenticated shared reads select an existing account by UUID; personal history is scoped to `(app_user_id, chess_account_id)`.
  - Authenticated import creation requires the current account owner; status reads require the immutable initiating user. Guests can read/import only unclaimed accounts.
  - `AsyncJob` rejects updates that change its persisted import configuration, including account, date range, time controls, and player color.
  - Before deployment, schema evolution updates the V1 baseline rather than adding synthetic Flyway versions. See `docs/engineering/repository-conventions.md`.

constraint:
  - Stockfish runs as a subprocess; analysis can take several minutes for large histories.
  - Exact recurring positions are analyzed; abstract strategic patterns are not detected.
  - Engine evaluation loss can produce false positives and does not necessarily indicate a practical weakness.
  - Practical ranking uses tested initial calibration values that are not empirically optimized; `CHESS_WEAKNESS_PRACTICAL_RANKING_ENABLED=false` disables only its contribution, preserving authenticated adaptive scheduling. See `README.md`.
  - Import jobs accept only `CHESS_COM` (`AsyncJob` validation); Lichess is not implemented.

convention:
  - **Quality checks**: backend `./gradlew ktlintCheck` and `./gradlew test`; frontend `npm run lint`, `npx tsc --noEmit`, `npm run test`, and `npm run build` from `frontend/`.
  - **Error handling**: exceptions map to structured JSON errors with codes in `GlobalExceptionHandler`; codes are listed in `API_CONTRACT.md`.
  - **Security**: server-side sessions, double-submit CSRF on state changes, connection-owner checks for import initiation, initiator-only authenticated job status, and per-user personal-state scoping; see `docs/architecture/identity-and-session.md` and `docs/specs/account-data-boundary.md`.
  - **Tests**: backend tests under `src/test/kotlin/com/chessecho/{controller,service,repository,integration,...}`; integration tests use Testcontainers PostgreSQL.
  - **CI**: `.github/workflows/ci.yml` runs backend ktlint and tests (Java 21) and frontend lint, typecheck, Vitest, and build (Node 20).
  - **Persistence**: follow the pre-deployment baseline-first migration convention in `docs/engineering/repository-conventions.md`.
  - **Workflow**: keep changes issue-focused and target pull requests at `main`; follow the repository conventions in `AGENTS.md`.
