# ChessEcho Technical Specification

purpose:      Identify recurring chess weaknesses and individual decisions that consume significant time, then support relevant practice and exploration.

user:         Chess players analyzing imported games and practicing recurring positions.

use-case:     Import games → analyze recurring positions with Stockfish → rank weaknesses → practice with interactive puzzles.
              Search imported games by broad time control and absolute decision-time threshold → inspect qualifying occurrences.
              Search personal games by arbitrary FEN similarity using an inverted index and static relational BM25 → explore matching positions.

architecture:
  - **Backend**: Kotlin/Spring Boot REST API; persistence, authentication, game import and analysis, puzzle/progress APIs, and internal corpus administration.
  - **Frontend**: Next.js single-page application (`frontend/src/app/page.tsx`) with Import Games, Long Decisions, Similar Positions, Weaknesses Library, and Practice Puzzles tabs; separate `login` and `register` routes.
  - **Frontend layers**: `app` routes, `components` UI, `services/api.ts` backend client, `utils` helpers; tests in `frontend/src/__tests__`. Current route, state, and API-to-UI traces: [frontend architecture](architecture/frontend.md).
  - **Persistence**: PostgreSQL stores users, accounts, games, positions, occurrences, analysis, jobs, training events, and human-move corpus data.
  - **Similarity subsystem**: Pure domain indexing and static relational BM25 scoring (`com.chessecho.similarity`); generation-guarded in-memory cache lifecycle invalidated on successful game imports (`PositionSimilarityCache`).
  - **Analysis flow**: imported games are replayed; positions use four-field FEN identity; qualifying positions are evaluated by Stockfish; weakness ranking is calculated per account.
  - **Backend modules**: `controller` and `dto` expose HTTP boundaries; `service` implements application behavior; `domain` models persisted concepts; `repository` uses Spring Data JPA; `config` and `web` provide framework and request/security integration.
  - **Backend architecture and endpoint traces**: See `docs/architecture/backend-architecture.md`.
  - **Human-move subsystem**: `humanmove` and related services build empirical human-move distributions by rating band; see `docs/architecture/human-move-provider.md` and `docs/engineering/human-move-corpus-portability.md`.
  - **Puzzle continuation**: Candidate collection, multi-provider engine/human selection, and fallback orchestration; see [puzzle continuation architecture](architecture/puzzle-continuation.md).
  - **Reference-coverage analysis**: Retained-evidence admission and coverage calculations are documented in `docs/engineering/human-move-corpus-portability.md`.
  - **Chess account data boundary**: Shared imported data, per-user training history, disconnect behavior, and import-job authorization are documented in `docs/architecture/identity-and-session.md` and `docs/architecture/backend-architecture.md`.
  - **Reference-evidence subsystem**: The explicit-input producer creates #430 snapshots; retained admission and coverage are documented in `docs/engineering/human-move-corpus-portability.md`.

stack:
  - **Backend**: Kotlin 2.0, Spring Boot 3.3.2, Spring Data JPA, Flyway, PostgreSQL 16, kchesslib, Stockfish.
  - **Frontend**: Next.js 16, React 19, TypeScript, react-chessboard, chess.js, Tailwind CSS 4.
  - **Build and runtime**: Gradle and Java 21; npm and Node.js 20; Docker Compose for PostgreSQL and the application.
  - **Tests**: JUnit 5, Mockito, H2, Testcontainers, Vitest, and Testing Library.
  - **CI**: GitHub Actions workflow at `.github/workflows/ci.yml`.

entry:
  - Backend: `cd backend && ./gradlew bootRun`; containerized runtime: `docker compose up`. Swagger UI is available at `/swagger-ui.html`.
  - Frontend: `cd frontend && npm ci && npm run dev`; the default API base is `http://localhost:8080/api`.
  - Database: `make db-up`; schema is defined in `backend/src/main/resources/db/migration/V1__baseline.sql`.
  - Backend outbound Chess.com requests use configured `CHESS_PUBAPI_USERNAME` and `CHESS_PUBAPI_CONTACT` for the required User-Agent. See `README.md`.

contract:
  - **Chess accounts**:
    - `ChessAccount` stores shared identity; `AccountConnection` links users.
    - Multiple users may connect one account; each user may have one active connection.
    - See `API_CONTRACT.md` and `docs/architecture/identity-and-session.md`.
  - **Authentication/session**: `POST /api/register`, `POST /api/login`, `GET /api/me`, and `POST /api/logout`; session cookie `CHESSECHO_SESSION`. See `docs/architecture/identity-and-session.md`.
  - **Header account state**: The Header distinguishes guests, signed-in users without a connected Chess.com account, and signed-in users with one.
  - **Development session**: `POST /api/dev/session` exists only under `dev`/`local` profiles with `chessecho.auth.dev-mode.enabled=true`; otherwise it returns 404 (`DevSessionController`).
  - **Import and jobs**:
    - Authenticated imports require `accountId` for the caller's current connection.
    - Guests import by username regardless of connection state.
    - Authenticated job polling is initiator-only; guest jobs remain pollable after connections change.
  - **Account data access**:
    - Authenticated shared-data reads select any existing account by `accountId`.
    - Guest imports and username-based reads ignore connection state.
    - Personal Progress requires the caller's current connection and scopes history to the user/account pair.
  - **Analysis and practice**:
    - `/api/positions/weaknesses` exposes weakness reads (including time-control and phase partitioned stats); `/api/positions/{positionId}/progress` requires `playerColor`, a connected `accountId`, and the selected `minEvalLoss`.
    - `/api/puzzles`, `/api/puzzles/continuation`, `/api/puzzles/evaluate-move`, `/api/puzzles/attempt`, and `/api/puzzles/events` support practice.
    - Candidate tactical continuations: Each candidate move receives independent bounded Stockfish search and forcing-prefix PV replay.
    - Forced-mate evaluations persist root-relative mate distances (`baselineEvalMate`, `bestMoveEvalMate`, `evalMate`); evaluation loss is calculated via an 8-case piecewise function in $[0.0, 20.0]$; losing-mate defense against negative centipawns returns null (horizon effect); occurrences with null loss are skipped from mistake counts and acceptable moves.
  - **Position Progress**: Dated actual-game encounters form a separate historical baseline and one aggregate observation per interval started by a persisted `SOLVED` puzzle event. Intervals use `Game.playedAt`, remain reconstructible after late imports, and reuse `GameOutcomeNormalizer`; see `docs/architecture/backend-architecture.md`.
  - **Long Decisions**: Individual occurrences expose reliable account-player decision durations derived from PGN clocks; the account-scoped API filters by broad time control and an inclusive absolute threshold. Line Exploration preserves query and timer state on return; see `docs/architecture/frontend.md`.
  - **Similar Positions**: `GET /api/positions/similar` retrieves personal positions similar to an arbitrary orthodox FEN input. Filtered to $\le 200$ candidates before static relational BM25 scoring. Guarantees $\ge 90\%$ recall of top-20 exhaustive candidates and warm p95 latency $< 500\text{ms}$. Exact query positions are excluded, and ties are broken deterministically by `canonicalFen` ascending. Exact-ply Chess.com URLs are derived from verified source metadata, returning `null` when unverifiable. Line Exploration transitions preserve Similar Positions query and results state on return.
  - **Puzzle attempt counts**: Authenticated user/account/position/color counts separate submitted SOLVED and FAILED initial answers via a single outcome-grouped aggregate over qualifying scheduling events; absent groups return zero counts.
  - **Actual-game results display**:
    - The Weaknesses Library displays supplied W/D/L, score rate, and eligible-game count without confidence classifications or training comparisons.
    - Null scores or zero eligible games show neutral unavailable-score wording; absent practical evidence omits the block.
  - **Practical weakness priority**: Confidence-gated, account/position/color-scoped distinct-game score rates adjust recommendation priority using the enabled normal policy; objective priority is unchanged. See `README.md` and `docs/architecture/precomputed-weakness-analysis.md`.
  - **Evaluation evidence**: Internal `EvaluationEvidenceProducerService.produce` creates #430 snapshots from explicit input and verifies #450 before persistence; see `docs/engineering/human-move-corpus-portability.md`.
  - **Human-move corpus administration**: `/api/admin/human-move-distribution/*` covers BFS acquisition, corpus runs/checkpoints, artifact export/verify/import/purge, projections, and cross-cohort comparison.
  - **Errors and security**: API errors use structured responses; state-changing requests require the session's double-submit CSRF token. See `API_CONTRACT.md`.

flow:
  - import: guest submits a username or authenticated user selects a current connection → async job fetches and replays shared Chess.com games → store position occurrences.
  - analysis: import ingestion completes → positions reached ≥5 times (`EngineAnalysisOrchestrator`) → Stockfish depth 16 (`EngineAnalysisService`) → stored position and move evaluations.
  - weakness: weakness/puzzle request → account-derived aggregation plus the caller's account-scoped training history → paginated weaknesses or puzzles.
  - practice: puzzle move → evaluate/continue/attempt/event endpoints → feedback and recorded training events.
  - long decisions: authenticated account, broad time control, and absolute seconds threshold → query eligible occurrences → display each position, move, duration, and game context.
  - Import and ingestion details: `README.md` and `API_CONTRACT.md`.

invariant:
  - Position identity includes piece placement, side to move, castling rights, and en-passant availability.
  - Partitioned statistics (time control and phase) are calculated in-memory and affect only their specific filters; they do not alter global candidate discovery thresholds or global totals.
  - Phase classification follows a 4-rule precedence: (1) corrupt FEN fallback (< 8 ranks) defaults to `OPENING`; (2) reduced-piece endgame (`mmCount <= 6`) evaluates to `ENDGAME`; (3) middlegame conditions (`mmCount <= 10 || backrankSparse || moveNumber > 14`) evaluate to `MIDDLEGAME` (< 4 pieces on White rank 1 or Black rank 8; `moveNumber = (plyNumber + 1) / 2`); (4) default to `OPENING`.
  - Occurrence counts are scoped by account and player color.
  - Engine analysis is stored per position and shared across accounts; weakness ranking is computed per account.
  - `userId`, usernames, account UUIDs, and job UUIDs are never bearer credentials (`API_CONTRACT.md`).
  - Authenticated shared reads select an existing account by UUID; personal history is scoped to `(app_user_id, chess_account_id)`.
  - The Header shows “No Chess.com account connected” only after account loading confirms the user has no connections; authenticated users always retain Sign out.
  - Each user has at most one active connection; multiple users may connect a shared Chess.com identity.
  - Authenticated imports require the explicit current connection; authenticated status reads require the immutable initiator.
  - Guest username reads/imports ignore connection state; guest jobs remain pollable after connections change.
  - Position Progress uses the selected weakness threshold and requires an explicit currently connected account.
  - Decision-time results require complete usable account-player clock data for a game; an ineligible game contributes no decision durations, and move quality does not affect qualification.
  - Inverted index candidate generation filters to $\le 200$ positions and guarantees $\ge 90\%$ recall of top-20 exhaustive candidates.
  - Core similarity interfaces in `com.chessecho.similarity` have zero dependencies on Spring Data, JPA, or web annotations.
  - Cache invalidation triggers on successful game-import completion with monotonic generation guards; concurrent rebuilds never commit partial or stale indexes.
  - `AsyncJob` rejects updates that change its persisted import configuration, including account, date range, time controls, and player color.
  - Centipawn and forced-mate evaluation columns are mutually exclusive and reject `mate = 0`.
  - Game and BFS traversal PGN parsing operates in-memory with zero temporary disk file generation.
  - Before deployment, schema evolution updates the V1 baseline rather than adding synthetic Flyway versions. See `docs/engineering/repository-conventions.md`.

constraint:
  - Stockfish runs as a subprocess; analysis can take several minutes for large histories.
  - Exact recurring positions are analyzed; abstract strategic patterns are not detected.
  - Engine evaluation loss can produce false positives and does not necessarily indicate a practical weakness.
  - Practical ranking uses tested initial calibration values that are not empirically optimized; `CHESS_WEAKNESS_PRACTICAL_RANKING_ENABLED=false` disables only its contribution, preserving authenticated adaptive scheduling. See `README.md`.
  - Import jobs accept only `CHESS_COM` (`AsyncJob` validation); Lichess is not implemented.

convention:
  - **Quality checks**: backend `./gradlew ktlintCheck`, `./gradlew detektMain detektTest`, and `./gradlew test` from `backend/`; frontend `npm run lint`, `npx tsc --noEmit`, `npm run test`, and `npm run build` from `frontend/`.
  - **Error handling**: exceptions map to structured JSON errors with codes in `GlobalExceptionHandler`; codes are listed in `API_CONTRACT.md`.
  - **Security**: server-side sessions, double-submit CSRF on state changes, and connection checks for authenticated imports and Progress.
  - **Access control**: authenticated job status is initiator-only; personal state is scoped per user. See `docs/architecture/identity-and-session.md` and `docs/architecture/backend-architecture.md`.
  - **Tests**: backend tests under `src/test/kotlin/com/chessecho/{controller,service,repository,integration,...}`; integration tests use Testcontainers PostgreSQL.
  - **Mutation testing**: opt-in PIT audits per `docs/engineering/mutation-testing.md`; verified audit records indexed in `docs/engineering/audit/index.md`.
  - **CI**: `.github/workflows/ci.yml` runs backend ktlint, Detekt, and tests (Java 21) and frontend lint, typecheck, Vitest, and build (Node 20).
  - **Persistence**: follow the pre-deployment baseline-first migration convention in `docs/engineering/repository-conventions.md`.
  - **Workflow**: keep changes issue-focused and target pull requests at `main`; follow the repository conventions in `AGENTS.md`.
