# ChessEcho API Contract

This document outlines the API contract for all REST endpoints available in ChessEcho.

---

## 1. Connected Chess.com Accounts

Chess.com identity and imported data are shared. Multiple users may connect the
same account, while each user may have at most one active connection. A user
must disconnect before connecting a different account. `userId`, a username,
an account UUID stored in the browser, and a job UUID are never bearer
credentials.

### `GET /api/accounts`

Requires a live `CHESSECHO_SESSION` and returns the caller's zero-or-one active
connections:

```json
[
  {"id": "account-uuid", "platform": "CHESS_COM", "username": "Hikaru"}
]
```

### `POST /api/accounts`

Requires the session and a matching `X-XSRF-TOKEN`/`XSRF-TOKEN` pair. Platform
and username are trimmed before validation; account identity is unique by
case-insensitive `(platform, username)`.

```json
{"platform": "chess_com", "username": " Hikaru "}
```

`201 Created` creates the shared account and first connection; `200 OK` links
an existing account or repeats the caller's existing connection. A second,
different connection returns `409 ACCOUNT_CONNECTION_LIMIT_REACHED`; disconnect
the current account before connecting another. `401 UNAUTHENTICATED` means no
live session; `403 CSRF_FAILED` means the double-submit pair is missing or
mismatched.

### `DELETE /api/accounts/{accountId}/connection`

Requires a live session and a matching CSRF token. Returns `204 No Content` and
removes only the caller's connection; shared imported data and the user's
training history remain stored and become available again after reconnection.
If the caller has no matching connection, the endpoint returns `404
ACCOUNT_NOT_FOUND`.

---

## 2. Start Import Job
Initiates an asynchronous game import job from Chess.com.

- **Endpoint:** `POST /api/games/import`
- **Content-Type:** `application/json`

### Request Body
```json
{
  "accountId": "UUID (authenticated form)",
  "platform": "CHESS_COM (optional snapshot in authenticated form)",
  "username": "string (optional snapshot in authenticated form)",
  "timeControls": ["RAPID", "BLITZ", "BULLET", "CLASSICAL"],
  "playerColor": "WHITE | BLACK | BOTH (required)",
  "fromDate": "YYYY-MM (optional)",
  "toDate": "YYYY-MM (optional)"
}
```

There are two mutually exclusive selector forms:

* **Authenticated:** `accountId` is the sole selector. Optional `platform` and
  `username` values are metadata snapshots and must match the selected account
  when supplied.
* **Guest:** omit `accountId` (or send JSON `null`) and supply both `platform`
  and `username`. The server creates or reuses the shared account, regardless
  of whether any user has connected it.

A valid session plus a guest-shaped body never falls back to the guest
username selector: it returns `400 ACCOUNT_SELECTION_REQUIRED`. A selected account with
an inconsistent snapshot returns `400 ACCOUNT_SELECTION_MISMATCH`. Both forms
require CSRF, including a guest request with no session cookie.

Authenticated import creation requires that the caller currently connects the
selected account. It requires an explicit `accountId`, even when the caller has
only one connection. Missing selection returns `400 ACCOUNT_SELECTION_REQUIRED`;
an unknown ID returns `404 ACCOUNT_NOT_FOUND`; a known but unconnected ID
returns `403 FORBIDDEN`. The created job records its initiating user
independently of later disconnect or reconnect operations.

### Curl Example
```bash
curl -X POST http://localhost:8080/api/games/import \
  -H "Content-Type: application/json" \
  -d '{
    "username": "magnuscarlsen",
    "platform": "CHESS_COM",
    "timeControls": ["RAPID"],
    "playerColor": "WHITE"
  }'
```

### Responses
#### `202 Accepted`
Job successfully queued.
```json
{
  "jobId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "status": "QUEUED"
}
```

#### `400 Bad Request`
Validation failed (e.g. invalid time controls, missing fields).
```json
{
  "error": "VALIDATION_ERROR",
  "details": [
    "username: username must not be blank",
    "playerColor: playerColor must be one of: white, black, both"
  ]
}
```

#### `409 Conflict`
An active import job is already running for this account.
```json
{
  "error": "CONFLICT",
  "details": [
    "An active import job already exists for account 'account-uuid'."
  ]
}
```

#### Account-selection and authorization errors

Authenticated requests without `accountId` return `400 ACCOUNT_SELECTION_REQUIRED`.
Unknown account IDs return `404 ACCOUNT_NOT_FOUND`; known accounts not currently
connected to the caller return `403 FORBIDDEN`.

---

## 3. Poll Job Status
Fetches the status and metrics of a running or completed import job.

- **Endpoint:** `GET /api/jobs/{id}`

### Path Parameters
- `id` (UUID, required): The job ID returned when creating the import job.

### Curl Example
```bash
curl http://localhost:8080/api/jobs/3fa85f64-5717-4562-b3fc-2c963f66afa6
```

### Responses
#### `200 OK`
```json
{
  "jobId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "status": "QUEUED | PROCESSING | COMPLETED | FAILED",
  "gamesImported": 120,
  "gamesSkipped": 5,
  "gamesProcessed": 150,
  "errorMessage": null,
  "analysisStatus": "NOT_STARTED | ANALYZING | COMPLETED | FAILED",
  "accountId": "account-uuid",
  "configurationState": "READY"
}
```

The response also includes the persisted platform, username, date bounds,
canonical sorted time controls, and player color when the job is resolved.
`READY` jobs are immutable commands. `UNRESOLVED` or malformed jobs fail closed.
A guest can poll only guest-started jobs (`app_user_id` is null), regardless of
whether a user connects the account later. An authenticated caller can poll only
jobs initiated by that user, even if the account is disconnected or connected
by another user.
There is at most one `QUEUED`/`PROCESSING` job per account.

`status` tracks game ingestion and becomes `COMPLETED` before Stockfish analysis starts.
Progress counters are updated after each archive. `gamesProcessed` counts every game examined,
including imported, skipped, and games excluded by import criteria. `analysisStatus` tracks the subsequent,
independent analysis lifecycle; an analysis failure does not change a completed import status.

#### `404 Not Found`
```json
{
  "error": "NOT_FOUND",
  "details": [
    "Job not found: 3fa85f64-5717-4562-b3fc-2c963f66afa6"
  ]
}
```

---

## 4. Get Imported Games
Retrieves a paginated list of imported games for a specified player and platform.

- **Endpoint:** `GET /api/games`

### Query Parameters
- `accountId` (UUID, authenticated form): Selects an existing account for shared
  imported-game reads; the caller need not currently own the connection.
- `username` and `platform` (guest form): Select a shared account together;
  connection state does not restrict guest username reads.
- `page` (int, optional, default: 0): Zero-indexed page number.
- `size` (int, optional, default: 20): Page size limit.
- `sort` (string, optional): Sorting specification.

### Curl Example
```bash
curl "http://localhost:8080/api/games?username=magnuscarlsen&platform=CHESS_COM&page=0&size=20"
```

### Responses
#### `200 OK`
```json
{
  "content": [
    {
      "id": "game-uuid-1",
      "platformGameId": "12345678",
      "timeControl": "600",
      "playedAt": "2026-08-01T12:00:00Z",
      "result": "1-0",
      "whiteUsername": "player1",
      "blackUsername": "player2",
      "pgn": "1. e4 e5 2. Nf3 Nc3..."
    }
  ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 20
  },
  "totalPages": 1,
  "totalElements": 1
}
```

---

## 5. Get Position Weaknesses
Retrieves calculated chess weaknesses based on position evaluations and recurring player mistakes.

- **Endpoint:** `GET /api/positions/weaknesses`

### Query Parameters
- `accountId` (UUID, authenticated form), or normalized `platform` + `username`
  in guest mode.
- `playerColor` (PlayerColor enum, required): `WHITE`, `BLACK`, or `BOTH`.
- `minEvalLoss` (double, optional, default: `0.8`): Minimum engine evaluation loss, in pawns, required for a move to be classified as a mistake. A lower value means a stricter definition of a mistake.
- `minMistakeCount` (int, optional, default: `3`): Minimum number of mistakes required to qualify as a weakness.
- `page` (int, optional, default: `0`): Zero-indexed page number.
- `size` (int, optional, default: `20`): Page size limit.

### Curl Example
```bash
curl "http://localhost:8080/api/positions/weaknesses?platform=CHESS_COM&username=magnuscarlsen&playerColor=WHITE&minEvalLoss=0.8&page=0&size=20"
```

### Responses
#### `200 OK`
```json
[
  {
    "positionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "fen": "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3",
    "timesReached": 10,
    "mistakeCount": 4,
    "mistakeRate": 0.4,
    "averageLoss": 1.2,
    "timeControlStats": {
      "BLITZ": {
        "timesReached": 6,
        "mistakeCount": 2,
        "mistakeRate": 0.333,
        "averageLoss": 0.9
      },
      "RAPID": {
        "timesReached": 4,
        "mistakeCount": 2,
        "mistakeRate": 0.5,
        "averageLoss": 1.5
      }
    },
    "priority": 4.8,
    "bestMove": "Bb5",
    "acceptableMoves": [
      {
        "move": "Bc4",
        "evalLoss": 0.1
      }
    ],
    "movesPlayed": [
      {
        "move": "d3",
        "timesPlayed": 4,
        "averageLoss": 1.2
      }
    ],
    "gameUrls": [
      "https://chess.com/game/live/12345"
    ],
    "evalCp": 35
  }
]
```

### Long Decisions

Returns individual imported position occurrences whose account player's
derived decision duration meets the requested absolute threshold. Decision
duration is independent of move quality.

- **Endpoint:** `GET /api/positions/long-decisions`
- **Authentication:** Requires the authenticated session. As with other shared
  imported-data reads, `accountId` identifies an existing account; a current
  connection is not required for the server-side read.
- **Query parameters:** `accountId` (UUID), `timeControl` (`BULLET`,
  `BLITZ`, `RAPID`, or `CLASSICAL`), `thresholdSeconds` (positive integer),
  `page` (zero-indexed, default `0`), and `size` (default `20`, bounded to
  `1`–`100`).
- **Filtering:** Selects games by the broad `Game.timeControl` value and
  occurrences with `decisionTimeMs >= thresholdSeconds * 1000`. Only games
  with reliable clock data have decision durations; unusable games retain
  ordinary occurrences but contribute no values to this query.
- **Response:** A page object with `content`, `page`, `size`, `totalElements`,
  `totalPages`, and `hasNext`. Each occurrence includes its ID and position ID,
  FEN, player color, ply, played move, decision duration in milliseconds,
  time control, platform game ID, game date, and opponent username. Occurrences
  are not grouped by position.
- **Errors:** Missing/invalid parameters and unsupported time controls return
  `400`; unauthenticated requests return `401`; unknown accounts return `404`.

### Position Progress

Returns the authenticated user's actual-game performance for an exact position and player color, divided into a historical baseline and intervals that start at successful puzzle decisions.

- **Endpoint:** `GET /api/positions/{positionId}/progress?playerColor={WHITE|BLACK}&accountId={UUID}&minEvalLoss={value}`
- **Query parameters:** `accountId` must identify the caller's current connection. `minEvalLoss` is required, finite, and positive; Progress uses the threshold from the successful Weaknesses request that produced the selected row. The frontend snapshots it with the selection; later global filter changes do not alter the request. Missing, malformed, zero, negative, NaN, or infinite values return HTTP 400 with the existing structured `VALIDATION_ERROR` body. Invalid requests never fall back to a fixed threshold.
- **Authentication:** Requires the existing authenticated session. The service scopes occurrences and training events to that user, selected account, exact position, and color; it never falls back to another account.
- **Selection errors:** Missing `accountId` returns `400 ACCOUNT_SELECTION_REQUIRED`. Unknown or unconnected account IDs return `404 ACCOUNT_NOT_FOUND`.
- **Response fields:** `positionId` is a UUID; `playerColor` is `WHITE` or `BLACK`; `baseline` is null or an object with ISO-8601 `occurredAt`, 0–100 rates, and positive `sourceEncounterCount`.
- Each point has a UUID `checkpointId`, ISO-8601 `occurredAt`, 0–100 rates, positive `attempts`, and boolean `open`. Changes are finite numbers or null; `assessment` is a string.
- `currentIntervalState` is `NO_CHECKPOINT`, `OPEN_AWAITING_EVIDENCE`, or `MEASURED_OPEN`; `excludedUndatedEncounters` is a non-negative integer.
- Only persisted `SOLVED` puzzle scheduling events are checkpoints. `FAILED`, game-scheduling events, and timed-attempt telemetry are not checkpoints.
- The baseline includes dated encounters before the first checkpoint. Each point represents one non-empty interval beginning at a checkpoint and ending immediately before the next checkpoint; the final interval is open. A game on a checkpoint boundary belongs to the new interval.
- Rates are independently aggregated as normalized wins or mistakes divided by dated source encounters, multiplied by 100. Draws and unknown results remain attempts but are not wins. Mistake loss calculation and fallback evaluation follow Weaknesses; an encounter is a mistake when its loss is greater than or equal to the request's `minEvalLoss`. A dated encounter with no usable direct or fallback evaluation remains an attempt but is not a mistake.
- `occurredAt` is the latest included game's `playedAt` for both the baseline and each measured point. It is not the checkpoint time. `playedAt` is the available game-completion-time proxy; undated encounters are excluded rather than assigned import or persistence timestamps.
- Empty intervals have no point. A later game updates its historically matching interval and does not create a checkpoint. Point `checkpointId` remains stable when late imports alter its rates or timestamp.
- `currentIntervalState` describes the latest interval: `NO_CHECKPOINT`, `OPEN_AWAITING_EVIDENCE`, or `MEASURED_OPEN`. Older measured points may be closed while the latest interval awaits evidence.
- Rate changes compare the baseline with the latest non-empty interval in checkpoint order: `(intervalRate - baselineRate) / baselineRate * 100`. Missing baseline/measurement or a zero baseline rate produces `null`.
- The backend assessment describes both rate directions relative to baseline when both comparisons exist. It compares rate values even when a relative change is null because the baseline rate is zero.
- Unchanged rates are named explicitly. Use “and” for opposing movement or any unchanged rate; use “but” for same-direction movement. When a comparison is unavailable, the assessment requests more played encounters.
- `excludedUndatedEncounters` counts only otherwise scoped source occurrences with no `playedAt`.

#### `200 OK`

```json
{
  "positionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "playerColor": "WHITE",
  "baseline": {
    "occurredAt": "2025-12-20T18:00:00Z",
    "mistakeRate": 35.0,
    "winRate": 45.0,
    "sourceEncounterCount": 20
  },
  "points": [
    {
      "checkpointId": "d2719a55-2e1e-4ea2-b551-b6d6bce7c2a4",
      "occurredAt": "2026-01-05T18:00:00Z",
      "mistakeRate": 20.0,
      "winRate": 70.0,
      "attempts": 10,
      "open": true
    }
  ],
  "currentIntervalState": "MEASURED_OPEN",
  "excludedUndatedEncounters": 0,
  "mistakeRateChange": -42.857142857142854,
  "winRateChange": 55.55555555555556,
  "assessment": "You are making fewer mistakes at this position, and your win rate has increased."
}
```

---

## 6. Get Puzzles
Retrieves position puzzles created from detected player weaknesses for interactive training.

- **Endpoint:** `GET /api/puzzles`

### Query Parameters
- `accountId` (UUID, authenticated form), or normalized `platform` + `username`
  in guest mode. Guest username reads are independent of connection state.
- `playerColor` (PlayerColor enum, required): `WHITE` or `BLACK`.
- `minEvalLoss` (double, optional, default: `0.8`): Minimum engine evaluation loss, in pawns, required for a move to be classified as a mistake. A lower value means a stricter definition of a mistake.
- `minMistakeCount` (int, optional, default: `3`): Minimum mistake count threshold.
- `limit` (int, optional, default: `5`): Max number of puzzles to return per page.
- `page` (int, optional, default: `0`): Page index.

### Curl Example
```bash
curl "http://localhost:8080/api/puzzles?platform=CHESS_COM&username=magnuscarlsen&playerColor=WHITE&minEvalLoss=0.8&limit=5&page=0"
```

### Responses
#### `200 OK`
```json
[
  {
    "puzzleId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "fen": "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3",
    "playerColor": "WHITE",
    "targetMove": "Bb5",
    "acceptableMoves": [
      {
        "move": "Bc4",
        "evalLoss": 0.1
      }
    ],
    "movesPlayed": [
      {
        "move": "d3",
        "timesPlayed": 4,
        "averageLoss": 1.2
      }
    ],
    "priority": 4.8,
    "timesReached": 10,
    "mistakeCount": 4,
    "mistakeRate": 0.4,
    "evalCp": 35
  }
]
```

---

## 7. Record Puzzle Scheduling Events
Records a retained puzzle decision for an authenticated user and an existing
Chess.com account.

- **Endpoint:** `POST /api/puzzles/events`
- **Content-Type:** `application/json`
- **Authentication:** Requires a live `CHESSECHO_SESSION`.

### Request Body
```json
{
  "positionId": "position-uuid",
  "playerColor": "WHITE",
  "eventType": "SOLVED",
  "accountId": "account-uuid"
}
```

- `positionId` (UUID, required): Position to which the event applies.
- `playerColor` (string, required): `WHITE` or `BLACK`; the position occurrence
  must match this color.
- `eventType` (required): `SOLVED`, `FAILED`, `GAME_REENCOUNTERED`,
  `GAME_MISTAKE`, or `GAME_HANDLED_SUCCESSFULLY`. The frontend emits only
  `SOLVED` and `FAILED` for puzzle outcomes; game event values retain their
  existing backend behavior.
- `accountId` (UUID, required by this route): An existing account used to find
  the matching position occurrence. The account lookup requires a session but
  does not require that the caller owns the account; the saved event is
  attributed to the authenticated user.

`PRESENTED`, `STARTED`, and `SKIPPED` are no longer accepted event types.

### Responses
#### `202 Accepted`
The event was saved. Legacy requests have an empty response body.

For a replay-safe initial answer, supply both `submissionId` (UUID) and
`submittedMove` (nonblank canonical SAN without surrounding whitespace).
Identified answers must use `SOLVED` or `FAILED` and `WHITE` or `BLACK`.
The response is `{ "submissionId": "submitted-uuid" }`.
A fresh answer has a fresh identity, independent of timer IDs; retries reuse
the exact identity and payload. Concurrent or repeated matching deliveries
persist one event. Reusing the identity for another account, position, color,
outcome, or move returns `409 PUZZLE_SUBMISSION_CONFLICT`.
Omitted and null optional fields are equivalent; supplying only one field
returns `400 VALIDATION_ERROR`.

### Submitted-answer outcome counts

`GET /api/puzzles/{positionId}/attempt-count?accountId=UUID&playerColor=WHITE|BLACK`
requires an authenticated session and an explicit account/color. It returns
`{ "solvedCount": 2, "failedCount": 3 }`, two nonnegative integers counting the caller's
`SOLVED` and `FAILED` training events for that account/position/color, excluding
source-linked events. Each row contributes only to its persisted outcome.
One grouped aggregate supplies both values; absent outcome groups return zero.
The combined `attemptCount` field is not returned. It does not count
timing telemetry, real-game encounters, or Position Progress observations.

Guest reads return `401 UNAUTHENTICATED` before account lookup. Invalid or
missing parameters return `400 VALIDATION_ERROR`; an unknown account returns
`404 ACCOUNT_NOT_FOUND`; missing position/color occurrence returns
`404 NOT_FOUND`. Identified writes also use structured `404 NOT_FOUND` for a
missing occurrence; legacy writes retain their empty 404 response.

Both fields use signed 64-bit backend counts. The frontend accepts each through
`Number.MAX_SAFE_INTEGER`; larger or malformed values show unavailable instead of rounded counts.
Existing attributable legacy training events count once per row by outcome. Without
historical submission identities, missing writes or duplicate historical
answers cannot be reconstructed or deduplicated reliably.

#### `400 Bad Request`
Missing `accountId` returns `ACCOUNT_SELECTION_REQUIRED`. A removed or unknown
`eventType` returns `VALIDATION_ERROR` before the controller writes an event.

#### `401 Unauthorized`
No live authenticated session is present.

#### `404 Not Found`
An unknown account returns `ACCOUNT_NOT_FOUND`. If the account exists but has
no occurrence for the supplied position and player color, the route returns
404 with an empty body and does not save an event.

---

## 8. Get Puzzle Continuation
Retrieves continuation candidate moves and resulting board states for a given position.

- **Endpoint:** `GET /api/puzzles/continuation`

### Query Parameters
- `fen` (string, required): Baseline position in FEN notation.
- `mode` (ContinuationMode enum, optional, default: `ENGINE`): Continuation mode (`ENGINE` or `HUMAN`).

### Provider & Fallback Behavior
- `requestedMode`: Refers to the continuation mode requested by the caller (`ENGINE` or `HUMAN`).
- `effectiveProvider`: Identifies the provider implementation that produced the returned candidates (`"ENGINE"` or `"HUMAN"`).
- `mode=ENGINE`: Uses `EngineMoveProvider` (Stockfish MultiPV filtered by `max-eval-loss: 0.50`) to return top engine continuation candidates. Response contains `requestedMode: "ENGINE"`, `effectiveProvider: "ENGINE"`.
- `mode=HUMAN`: Invokes `HumanMoveProvider` first. If historical moves exist for the position, returns human candidate moves with play counts (`timesPlayed`). Response contains `requestedMode: "HUMAN"`, `effectiveProvider: "HUMAN"`. If no historical moves exist, `ContinuationService` automatically falls back to `EngineMoveProvider`. Response contains `requestedMode: "HUMAN"`, `effectiveProvider: "ENGINE"`.
- **Candidates Collection**: The endpoint returns a collection of candidate moves (`candidates`) rather than forcing rank 1.

### Curl Example
```bash
curl "http://localhost:8080/api/puzzles/continuation?fen=r1bqkbnr%2Fpppp1ppp%2F2n5%2F4p3%2F4P3%2F5N2%2FPPPP1PPP%2FRNBQKB1R%20w%20KQkq%20-%202%203&mode=HUMAN"
```

### Responses
#### `200 OK` (HUMAN mode fallback to ENGINE example)
```json
{
  "fen": "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3",
  "requestedMode": "HUMAN",
  "effectiveProvider": "ENGINE",
  "candidates": [
    {
      "move": "Bb5",
      "resultingFen": "r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3",
      "providerType": "ENGINE",
      "evalCp": 40,
      "evalLoss": 0.0,
      "timesPlayed": null
    },
    {
      "move": "Bc4",
      "resultingFen": "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3",
      "providerType": "ENGINE",
      "evalCp": 35,
      "evalLoss": 0.05,
      "timesPlayed": null
    }
  ]
}
```

#### `404 Not Found`
No continuation moves available for the given position.
```json
{
  "error": "NOT_FOUND",
  "details": [
    "No continuation move available"
  ]
}
```

---

## 9. Evaluate User Exploration Move
Evaluates an arbitrary legal move played from an arbitrary position (FEN) during interactive line exploration against the engine baseline and determines whether its evaluation loss is within the configured user exploration threshold (`0.80` pawns).

- **Endpoint:** `GET /api/puzzles/evaluate-move`

### Query Parameters
- `fen` (string, required): Baseline position in FEN notation.
- `move` (string, required): Exact move attempted by the user in SAN notation (e.g. `Nf3`, `Ba4`, `Bxc6`).

### Behavior & Separation of Concerns
- **Distinct from Continuation Discovery**: Continuation candidates (`/api/puzzles/continuation`) answer *"Which moves may ChessEcho play?"* (`max-eval-loss: 0.50`). Move evaluation (`/api/puzzles/evaluate-move`) answers *"How much evaluation did the user's move lose, and is that loss acceptable?"* (`max-eval-loss: 0.80`).
- **Server Configured Threshold**: The threshold is configured server-side (`engine.exploration.max-eval-loss: 0.80`) and intentionally **not** accepted as a query parameter. The response explicitly returns `maxEvalLoss` for transparency.
- **Does NOT Require MultiPV=5**: If the user's move is rank 6+, Stockfish evaluates that specific move independently.
- **Threshold Rule**: Evaluation loss is computed relative to the position's best move: `evalLoss = max(0.0, (bestEvalCp - userEvalCp) / 100.0)`. The move is `acceptable = true` if `evalLoss <= maxEvalLoss`.

### Curl Example
```bash
curl "http://localhost:8080/api/puzzles/evaluate-move?fen=r1bqkbnr%2F1ppp1ppp%2Fp1n5%2F1B2p3%2F4P3%2F5N2%2FPPPP1PPP%2FRNBQK2R%20w%20KQkq%20-%200%204&move=Ba4"
```

### Responses
#### `200 OK`
```json
{
  "fen": "r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4",
  "move": "Ba4",
  "bestMove": "Ba4",
  "bestEvalCp": 80,
  "evalCp": 80,
  "evalLoss": 0.0,
  "maxEvalLoss": 0.80,
  "threshold": 0.80,
  "acceptable": true
}
```

#### `400 Bad Request`
Returned when the specified position FEN is invalid or the attempted move is illegal.
```json
{
  "error": "VALIDATION_ERROR",
  "details": [
    "Illegal or unparseable move 'e8' for FEN 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3'"
  ]
}
```
---

## Identity & Session (Issue #113)

The identity/session foundation adds a provider-neutral authenticated boundary
(#79 D1/D2/D7). Sessions are opaque, server-side, and cookie-based; only the
SHA-256 hash of the secret is persisted. See
[`docs/architecture/identity-and-session.md`](docs/architecture/identity-and-session.md)
for the full contract.

### Cookies

- **`CHESSECHO_SESSION`** — the opaque session secret. `HttpOnly`, `Path=/`,
  `SameSite` and `Secure` are configurable (`chessecho.auth.cookie.*`). Never
  readable by JavaScript and never returned in a response body.
- **`XSRF-TOKEN`** — the readable double-submit CSRF token, seeded on safe
  requests. Not `HttpOnly`. The SPA echoes it in the `X-XSRF-TOKEN` header on
  state-changing requests.

### CORS

Credentialed CORS (`Access-Control-Allow-Credentials: true`) is restricted to the
configured explicit origins (`http://localhost:3000`, `http://127.0.0.1:3000`).
Allowed request headers include `Content-Type` and `X-XSRF-TOKEN`.

## Local email/password authentication (Issue #276)

`AppUser` is the sole application identity. A `LocalCredential` is a one-to-one
password factor attached to that user and stores only an adaptive PBKDF2
password verifier; it has no email or provider identity field. Successful
registration and login issue the same opaque `CHESSECHO_SESSION` cookie used by
the rest of the application. Passwords, hashes, session identifiers, and raw
session secrets are never returned.

Email canonicalization is exactly: trim leading/trailing Unicode whitespace,
then lowercase with `Locale.ROOT`. Provider-specific rewrites (including Gmail
dot or plus-address handling) are not performed. The canonical value is stored
on `AppUser` and is used for uniqueness, duplicate registration, and login.

### `POST /api/register`

Requires a matching `XSRF-TOKEN` cookie and `X-XSRF-TOKEN` header.

```json
{"email":" Alice@Example.COM ","password":"correct horse battery staple"}
```

`201 Created` returns `{ "userId": "...", "email": "alice@example.com" }` and
sets an HttpOnly session cookie. `409 REGISTRATION_CONFLICT` is returned when
the canonical email is already registered; validation failures are `400
VALIDATION_ERROR`; missing or mismatched CSRF is `403 CSRF_FAILED`.

### `POST /api/login`

Uses the same CSRF contract and request shape. `200 OK` returns the safe user
summary and sets an opaque session cookie. Unknown email and wrong password
both return the identical `401` response:

```json
{"error":"INVALID_CREDENTIALS","details":["Invalid email or password"]}
```

Neither failure creates a session or reveals whether the email exists.

## Current Session

Returns the current authenticated principal summary, or `401` when the session is
missing, expired, or revoked. Exposes no reusable credential.

- **Endpoint:** `GET /api/me`
- **Auth:** session cookie (optional)
- **CSRF:** not required (safe method)

### Responses
#### `200 OK`
```json
{
  "userId": "b1e5f2c0-0000-0000-0000-000000000000",
  "devPrincipal": false,
  "email": null
}
```

#### `401 Unauthorized`
```json
{
  "error": "UNAUTHENTICATED",
  "details": ["Authentication required"]
}
```

## Logout

Revokes the current session (idempotent) and clears the session cookie by
replaying its attributes with `Max-Age=0`.

- **Endpoint:** `POST /api/logout`
- **Auth:** session cookie (the raw secret is read from the cookie, never from the
  principal)
- **CSRF:** required (`X-XSRF-TOKEN` header must equal the `XSRF-TOKEN` cookie)

### Responses
- **`204 No Content`** — session revoked (or no session was present; still `204`).
- **`403 Forbidden`** — `{"error": "CSRF_FAILED", ...}` when the CSRF token is
  missing or does not match.

## Development Session (dev/local only)

Establishes a session through the shared identity/session path using a fixed
development principal. It is a fail-closed allowlist endpoint: present only under
the `{dev, local}` profile allowlist AND when `chessecho.auth.dev-mode.enabled=true`
(default off). Under any other profile the endpoint is absent (`404`). If dev mode
is enabled outside the allowlist, application boot is aborted by a startup guard.

- **Endpoint:** `POST /api/dev/session`
- **CSRF:** required

### Responses
- **`200 OK`** — `CurrentUserResponse` plus a `Set-Cookie: CHESSECHO_SESSION` (`HttpOnly`).
- **`404 Not Found`** — outside the `{dev, local}` allowlist or when dev mode is disabled.
- **`403 Forbidden`** — `{"error": "CSRF_FAILED", ...}`.
