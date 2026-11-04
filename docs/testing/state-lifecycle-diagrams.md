# State-Lifecycle Diagrams

This document models the core user-facing state lifecycles in ChessProbe using Mermaid diagrams.

---

### 1. Practice Puzzle Lifecycle

This diagram captures catalog view mode transitions (`mode: 'discovery' | 'practice'`) and board solving states (`PuzzleFeedbackState`):

```mermaid
stateDiagram-v2
    [*] --> Discovery
    Discovery --> Practice: Select puzzle or start
    [*] --> Practice

    state Practice {
        [*] --> IDLE
        IDLE --> EVALUATING: Move submitted
        
        EVALUATING --> CORRECT: Optimal move
        EVALUATING --> INCORRECT: Loss over 0.80 pawns
        EVALUATING --> HIST_MISTAKE: Matches blunder
        EVALUATING --> EVAL_ERROR: Timeout or error

        INCORRECT --> IDLE: Retry or reset
        HIST_MISTAKE --> IDLE: View hint
        EVAL_ERROR --> IDLE: Retry submission

        CORRECT --> EXPLORING: Continue exploration
        EXPLORING --> IDLE: Exit line
    }

    Practice --> Discovery: Back to puzzles
    Practice --> [*]
```

**Solving State Transitions & Evaluation Policies:**
- **`IDLE` &rarr; `EVALUATING`**: User drags or clicks a move on `ChessBoardArea`. Input listeners freeze and `isEvaluatingMove` turns true.
- **`CORRECT`**: Engine validates candidate move within acceptable threshold ($\le 0.80$ pawn loss or engine optimal PV). Unlocks emerald banner and `Continue Exploration &rarr;` CTA.
- **`INCORRECT`**: Engine evaluation loss exceeds $0.80$ pawns. Board displays inaccurate banner and automatically rolls the piece back to the puzzle baseline for retry.
- **`HIST_MISTAKE` (HISTORICAL_MISTAKE)**: User plays the exact suboptimal move played in their original game. Displays a coaching hint banner suggesting the target move and rolls back.
- **`EVAL_ERROR` (EVALUATION_ERROR)**: Subprocess engine crash, 500ms timeout ceiling, or network API failure. Leaves board editable for resubmission.

---

### 2. Import Job Lifecycle

This diagram models the backend asynchronous import job lifecycle (`AsyncJob.ALLOWED_STATUSES`) and analysis pipeline (`AsyncJob.analysisStatus`):

```mermaid
stateDiagram-v2
    [*] --> QUEUED
    QUEUED --> PROCESSING: Worker claims job

    state PROCESSING {
        [*] --> ParsingPGN
        ParsingPGN --> IngestingGames: Store occurrences
        IngestingGames --> AnalysisPending: Ingestion complete
    }

    PROCESSING --> COMPLETED: Ingestion complete
    PROCESSING --> FAILED: Network or parse error

    state AnalysisLifecycle {
        [*] --> NOT_STARTED
        NOT_STARTED --> ANALYZING: 5+ encounters
        ANALYZING --> AnalysisDone: Depth 16 evals stored
        ANALYZING --> AnalysisError: Engine crash or timeout
    }

    COMPLETED --> [*]
    FAILED --> [*]
```

**Asynchronous Import & Analysis Guarantees:**
- **Job Status (`AsyncJob.status`)**: Follows strict unidirectional transitions: `QUEUED` &rarr; `PROCESSING` &rarr; `COMPLETED` / `FAILED`. Only one active (`QUEUED` or `PROCESSING`) job is permitted per Chess.com account connection (enforced by a partial unique index).
- **Processing Sub-states**: Monthly PGN archives downloaded from Chess.com are parsed in-memory with zero temporary disk writes. Positions encountered are persisted as 4-field FEN occurrences.
- **Engine Analysis (`AsyncJob.analysisStatus`)**: Operates as an independent background pipeline. Positions meeting the $\ge 5$ encounter threshold are queued for Stockfish depth 16 evaluation. Successful completion marks terminal `COMPLETED` and advances the monotonic generation counter to invalidate `PositionSimilarityCache`.

---

### 3. Session & Authentication Lifecycle

This diagram captures client bootstrap state (`useAccountSession.ts`), account connection state (`AccountConnectionStatus`), and backend session management (`AuthSession` entity):

```mermaid
stateDiagram-v2
    [*] --> Loading
    Loading --> Unauthenticated: Guest session
    Loading --> Authenticated: Valid session
    Loading --> OfflineError: Network error

    Unauthenticated --> Authenticated: Login or register
    Authenticated --> Unauthenticated: Logout or revoked

    state Authenticated {
        [*] --> Unconnected
        Unconnected --> Connected: Connect Chess.com
        Connected --> Unconnected: Delete connection
    }

    state ServerAuthSession {
        [*] --> Active
        Active --> Active: Slide idle window
        Active --> Revoked: Explicit logout
        Active --> Expired: Idle over 30 min
        Revoked --> [*]
        Expired --> [*]
    }
```

**Session Management & Auth Boundary Invariants:**
- **Bootstrap Protocol**: On application launch, client invokes `GET /api/me` with `credentials: 'include'`. An HTTP 401 is treated as a normal guest state (`status = 'unauthenticated'`), serving a readable CSRF double-submit cookie.
- **Sliding Idle Window**: Authenticated requests refresh `idle_expires_at` (30-minute idle window) up to a 30-day absolute ceiling (`expires_at`).
- **Session Revocation**: `POST /api/logout` sets `revoked_at` timestamp on `AuthSession` and transmits a zero-age deletion cookie (`Max-Age=0`). Revoked and expired sessions are deleted by a scheduled database cleanup worker.
