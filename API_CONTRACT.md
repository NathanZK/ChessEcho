# ChessEcho API Contract

This document outlines the API contract for all REST endpoints available in ChessEcho.

---

## 1. Start Import Job
Initiates an asynchronous game import job from Chess.com or Lichess.

- **Endpoint:** `POST /api/games/import`
- **Content-Type:** `application/json`

### Request Body
```json
{
  "username": "string (required)",
  "platform": "CHESS_COM",
  "timeControls": ["RAPID", "BLITZ", "BULLET", "CLASSICAL"],
  "playerColor": "WHITE | BLACK | BOTH (required)",
  "fromDate": "YYYY-MM (optional)",
  "toDate": "YYYY-MM (optional)"
}
```

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
An active import job is already running for this user/platform combination.
```json
{
  "error": "CONFLICT",
  "details": [
    "An active import job is already running."
  ]
}
```

---

## 2. Poll Job Status
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
  "gamesFilteredOut": 25,
  "errorMessage": null,
  "analysisStatus": "NOT_STARTED | ANALYZING | COMPLETED | FAILED"
}
```

`status` tracks game ingestion and becomes `COMPLETED` before Stockfish analysis starts.
Progress counters are updated after each archive. `analysisStatus` tracks the subsequent,
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

## 3. Get Imported Games
Retrieves a paginated list of imported games for a specified player and platform.

- **Endpoint:** `GET /api/games`

### Query Parameters
- `username` (string, required): Player's username.
- `platform` (Platform enum, required): Platform identifier (`CHESS_COM`).
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

## 4. Get Position Weaknesses
Retrieves calculated chess weaknesses based on position evaluations and recurring player mistakes.

- **Endpoint:** `GET /api/positions/weaknesses`

### Query Parameters
- `platform` (Platform enum, required): Platform identifier (`CHESS_COM`).
- `username` (string, required): Player username.
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

---

## 5. Get Puzzles
Retrieves position puzzles created from detected player weaknesses for interactive training.

- **Endpoint:** `GET /api/puzzles`

### Query Parameters
- `platform` (Platform enum, required): Platform identifier (`CHESS_COM`).
- `username` (string, required): Player username.
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

## 6. Get Puzzle Continuation
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

## 7. Evaluate User Exploration Move
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
