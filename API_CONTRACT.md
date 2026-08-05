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
  "platform": "string (required, e.g., 'chessdotcom' | 'lichess')",
  "timeControls": ["rapid", "blitz", "bullet", "classical"],
  "playerColor": "white | black | both (required)",
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
    "platform": "chessdotcom",
    "timeControls": ["rapid"],
    "playerColor": "white"
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
  "errorMessage": null
}
```

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
- `platform` (string, required): Platform identifier (e.g., `chessdotcom`, `lichess`).
- `page` (int, optional, default: 0): Zero-indexed page number.
- `size` (int, optional, default: 20): Page size limit.
- `sort` (string, optional): Sorting specification.

### Curl Example
```bash
curl "http://localhost:8080/api/games?username=magnuscarlsen&platform=chessdotcom&page=0&size=20"
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
- `platform` (string, required): Platform name.
- `username` (string, required): Player username.
- `playerColor` (string, required): `white` or `black`.
- `minEvalLoss` (double, optional, default: `0.8`): Minimum evaluation loss threshold.
- `acceptableThreshold` (double, optional, default: `0.3`): Threshold for acceptable alternative moves.
- `minMistakeCount` (int, optional, default: `3`): Minimum number of mistakes required to qualify as a weakness.

### Curl Example
```bash
curl "http://localhost:8080/api/positions/weaknesses?platform=chessdotcom&username=magnuscarlsen&playerColor=white"
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
- `platform` (string, required): Platform name.
- `username` (string, required): Player username.
- `playerColor` (string, required): `WHITE` or `BLACK`.
- `minEvalLoss` (double, optional, default: `0.8`): Minimum evaluation loss threshold.
- `acceptableThreshold` (double, optional, default: `0.3`): Threshold for acceptable alternative moves.
- `minMistakeCount` (int, optional, default: `3`): Minimum mistake count threshold.
- `limit` (int, optional, default: `5`): Max number of puzzles to return per page.
- `page` (int, optional, default: `0`): Page index.

### Curl Example
```bash
curl "http://localhost:8080/api/puzzles?platform=chessdotcom&username=magnuscarlsen&playerColor=white&limit=5&page=0"
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
