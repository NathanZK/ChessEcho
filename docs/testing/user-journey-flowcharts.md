# User Journey Flowcharts

This document visually models the actual user experience across ChessProbe's canonical journeys using Mermaid flowcharts. It is written to allow product owners, testers, and developers to inspect, challenge, and verify user interactions without needing to read application source code.

---

## 1. Master Shared-Practice Convergence Architecture

Five distinct user entry points converge into the unified shared-practice shell (`PracticePuzzlesView`), each preserving its own entry context and return routing.

```mermaid
flowchart LR
    subgraph EntryRoutes["Entry Points"]
        EP1["Weaknesses<br/> Library"]
        EP2["Decision<br/> Timing"]
        EP3["Similar<br/> Positions"]
        EP4["Explore<br/> Modal"]
        EP5["Practice Puzzles<br/> Tab"]
    end

    subgraph Shell["Shared Practice Shell"]
        PS1["Mount Board<br/> & Panel"]
        PS2{{"Weakness?"}}
        PS3["Show Recurrence<br/> Stats"]
        PS4["Suppress<br/> Badges"]
        PS5["Interactive<br/> Solving Loop"]
        PS6["Line Exploration<br/> Mode"]

        PS1 --> PS2
        PS2 -->|Yes| PS3 --> PS5
        PS2 -->|No| PS4 --> PS5
        PS5 <-->|"Continue / Exit"| PS6
    end

    subgraph ExitRoutes["Return Navigation"]
        EX1["Restore Weaknesses<br/> Tab"]
        EX2["Restore Decisions<br/> Tab"]
        EX3["Restore Similar<br/> Positions Tab"]
        EX4["Restore Prior<br/> Active Tab"]
        EX5["Return to Catalog<br/> View"]
    end

    EP1 -->|"tab: weaknesses"| PS1
    EP2 -->|"tab: decisions"| PS1
    EP3 -->|"tab: similar"| PS1
    EP4 -->|"tab: prior"| PS1
    EP5 -->|"tab: puzzles"| PS1

    PS5 -->|"Exit (J01)"| EX1
    PS5 -->|"Exit (J02)"| EX2
    PS5 -->|"Exit (J03)"| EX3
    PS5 -->|"Exit (J04)"| EX4
    PS5 -->|"Exit (J05)"| EX5
```

*\*Note: Decision Timing (J02) and Similar Positions (J03) positions check database evidence; if the user blundered $\ge 5$ times in that exact FEN, `isWeaknessBacked` resolves true, otherwise recurrence stats remain suppressed. Entry routes pass originating context (`originatingTab: 'weaknesses'`, `'long-decisions'`, `'similar-positions'`, `priorActiveTab`, or `'puzzles'`) to ensure return navigation restores the correct tab.*

---

## 2. Shared-Practice Entry Point Flowcharts

### 2.1 Journey 1: Weaknesses Library Practice (J01)
*User goal: Review recurrent personal blunders identified by engine analysis and practice the correct alternatives.*

```mermaid
flowchart TD
    W1["Open Weaknesses<br/> Library Tab"] --> W2["Browse Weakness<br/> Cards"]
    W2 --> W3{{"Action?"}}

    W3 -->|"Filter"| W4["Adjust<br/> Filters"]
    W4 --> W2

    W3 -->|"Inspect"| W5["View Progress<br/> Modal"]
    W5 -->|"Back"| W2

    W3 -->|"Practice"| W6["Mount Practice<br/> Shell"]

    W6 --> W7["Solve Initial<br/> Move"]
    W7 --> W8{{"Evaluation?"}}

    W8 -->|"Blunder (>0.80)"| W9["Rollback<br/> Move"]
    W9 --> W7

    W8 -->|"Game Mistake"| W10["Coaching<br/> Hint"]
    W10 --> W7

    W8 -->|"Best Move"| W11["Emerald<br/> Banner"]

    W11 --> W12{{"Next Action?"}}
    W12 -->|"Explore"| W13["Line<br/> Exploration"]
    W13 -->|"Exit Line"| W11

    W12 -->|"Back"| W14["Exit Practice<br/> Shell"]
    W14 --> W15["Restore Weaknesses<br/> Library Tab"]
```

**Key Interaction Details (J01):**
- **Catalog Filtering**: Allows filtering by player color (White/Black), time control (Bullet/Blitz/Rapid/Classical), and minimum mistake threshold (0.30 – 2.00 pawns).
- **Evaluation Rules**: Any move incurring $>0.80$ pawn loss is rejected and rolled back. If the user repeats their historical game mistake, an educational hint is surfaced.
- **Return Contract**: Exiting practice or line exploration restores the `#weaknesses` tab, preserving active filters and scroll position.

---

### 2.2 Journey 2: Decision Review / Timing Practice (J02)
*User goal: Identify moves where excessive clock time was burned and practice making the decision under simulated time pressure.*

```mermaid
flowchart TD
    D1["Open Decision<br/> Timing Tab"] --> D2["Browse Decision<br/> Blunders"]
    D2 --> D3{{"Action?"}}

    D3 -->|"Filter"| D4["Adjust Timing<br/> Filters"]
    D4 --> D2

    D3 -->|"Link"| D5["Open Game on<br/> Chess.com"]

    D3 -->|"Explore"| D6["Mount Practice<br/> Shell"]

    D6 --> D7["Display Initial<br/> Position"]
    D7 --> D8["Make Practice<br/> Move"]
    D8 --> D9{{"Move Result?"}}

    D9 -->|"Incorrect"| D10["Show Mistake<br/> Feedback"]
    D10 --> D8

    D9 -->|"Correct"| D11["Green Feedback<br/> Banner"]
    D11 --> D12["Click<br/> Exit"]
    D12 --> D13["Restore Decision<br/> Timing Tab"]
```

**Key Interaction Details (J02):**
- **Decision Filters**: Allows filtering by duration (seconds spent on move), severity (`Critical`, `Significant`, `Minor`), and game speed.
- **Stopwatch / Timer**: Practice shell provides optional countdown or stopwatch simulating original clock conditions.
- **Return Contract**: Restores `#long-decisions` view state, re-applying query parameters, selected occurrence, and scroll position.

---

### 2.3 Journey 3: Similar Positions Search & Practice (J03)
*User goal: Search personal games for positions tactically similar to an arbitrary board layout, then practice candidate matches.*

```mermaid
flowchart TD
    S1["Open Similar<br/> Positions Tab"] --> S2["Input FEN in<br/> Search Bar"]
    S2 --> S3{{"Side in FEN?"}}

    S3 -->|"No"| S4["Prompt Side<br/> to Move"]
    S4 --> S5["Search Similar<br/> Positions"]
    S3 -->|"Yes"| S5

    S5 --> S6["Query Static<br/> BM25 Index"]
    S6 --> S7{{"Status?"}}

    S7 -->|"Success"| S8["Dynamic Score<br/> Ranking"]
    S7 -->|"Timeout"| S9["Static BM25<br/> Fallback"]

    S8 --> S10["Display Candidate<br/> Cards"]
    S9 --> S10

    S10 --> S11["Click Practice<br/> Position"]
    S11 --> S12["Mount Practice<br/> Shell"]
    S12 --> S13["Solve or<br/> Explore Line"]
    S13 --> S14["Restore Similar<br/> Positions Tab"]
```

**Key Interaction Details (J03):**
- **FEN Validation**: Accepts 4-field or incomplete FEN strings. If turn token is omitted, client prompts user to select White or Black before querying.
- **Two-Stage Retrieval**: Queries static BM25 structural index up to 200 candidates, then attempts Stockfish depth 8 dynamic tactical reranking on top 100 candidates.
- **Graceful Fallback**: If engine analysis exceeds the 500ms watchdog or semaphore permits are exhausted, static BM25 ranking is returned with `dynamicSimilarityApplied = false`.

---

### 2.4 Journey 4: Explore Position Modal (J04)
*User goal: Paste an arbitrary FEN or PGN from an external study/game to analyze or practice with engine evaluation.*

```mermaid
flowchart TD
    E1["User on Any<br/> Active Tab"] --> E2["Click Explore<br/> in Header"]
    E2 --> E3["Explore Modal<br/> Opens"]
    E3 --> E4{{"Input Type?"}}

    E4 -->|"FEN"| E5["Paste FEN<br/> String"]
    E4 -->|"PGN"| E6["Paste PGN<br/> Text"]

    E5 --> E7["Click Explore<br/> Position"]
    E6 --> E7

    E7 --> E8["Mount Practice<br/> Shell"]
    E8 --> E9["Board Displays<br/> Position"]

    E9 --> E10{{"User Choice?"}}
    E10 -->|"Play"| E11["Dynamic Move<br/> Evaluation"]
    E10 -->|"Mode"| E12["Switch Interactive<br/> Mode"]

    E11 --> E13["Click<br/> Exit"]
    E12 --> E13
    E13 --> E14["Restore Prior<br/> Active Tab"]
```

**Key Interaction Details (J04):**
- **Input Flexibility**: Modal supports both raw orthodox FEN strings and multi-move PGN text.
- **Supplied Position Policy**: Governed by #662 AC9. Positions are solved as puzzles with move evaluation and blunder rollback. Recurrence badges (mistake counts) are strictly hidden (`isWeaknessBacked: false`).
- **Return Destination**: Exit closes exploration and restores whatever application tab was active immediately prior to opening the header modal.

---

### 2.5 Journey 5: Practice Puzzles Direct Tab (J05)
*User goal: Browse personal puzzle catalog directly and solve puzzles sequentially in a focused training carousel.*

```mermaid
flowchart TD
    P1["Open Practice<br/> Puzzles Tab"] --> P2{{"Active Puzzle?"}}

    P2 -->|"No"| P3["Discovery Catalog<br/> View"]
    P2 -->|"Yes"| P4["Resume Practice<br/> Shell"]

    P3 --> P5["Catalog Header<br/> & Filters"]
    P5 --> P6{{"Launch Choice?"}}

    P6 -->|"Start CTA"| P7["Load First<br/> Filtered Puzzle"]
    P6 -->|"Card Click"| P8["Load Selected<br/> Puzzle"]

    P7 --> P9["Mount Practice<br/> Shell"]
    P8 --> P9

    P9 --> P10["Solve Puzzle<br/> Board"]
    P10 --> P11{{"Evaluation?"}}

    P11 -->|"Incorrect"| P12["Retry<br/> Move"]
    P12 --> P10

    P11 -->|"Correct"| P13["Best Move<br/> Banner"]

    P13 --> P14{{"Next Choice?"}}
    P14 -->|"Next"| P15["Advance<br/> Carousel"]
    P15 --> P9
    P14 -->|"Back"| P16["Return to<br/> Discovery View"]
```

**Key Interaction Details (J05):**
- **Dual Mode**: Toggles between discovery catalog (`mode: 'discovery'`) and interactive puzzle carousel (`mode: 'practice'`).
- **Carousel Navigation**: Solving a puzzle enables "Next Puzzle &rarr;" button to advance through the filtered list.
- **Return Navigation**: Clicking "Back to Puzzles" returns to discovery mode while preserving active filter bar selections.

---

## 3. Data Pipeline & Identity Journey Flowcharts

### 3.1 Journeys 6 & 7: Game Import & Asynchronous Analysis Pipeline
*User goal: Import historical Chess.com games and generate automated Stockfish analysis without freezing the browser.*

```mermaid
flowchart TD
    I1["Open Import<br/> Games Tab"] --> I2{{"Session State?"}}

    I2 -->|"Guest"| I3["Guest Import<br/> Form"]
    I2 -->|"Auth"| I4["Connected Account<br/> Form"]

    I3 --> I5["Select Game Type<br/> & Date Range"]
    I4 --> I5

    I5 --> I6["Click Import<br/> Games CTA"]
    I6 --> I7["Create AsyncJob<br/> in QUEUED"]

    I7 --> I8["Client Starts<br/> Polling Loop"]
    I8 --> I9["Worker Claims<br/> Job"]

    I9 --> I10["Extract Unique<br/> Positions"]
    I10 --> I11["Queue Stockfish<br/> Analysis"]

    I11 --> I12{{"Job Outcome?"}}
    I12 -->|"Success"| I13["Mark Job<br/> COMPLETED"]
    I12 -->|"Failure"| I14["Mark Job<br/> FAILED"]

    I13 --> I15["Display Summary<br/> Card"]
    I14 --> I16["Display Failure<br/> Alert"]

    I15 --> I17["Open Weaknesses<br/> Tab"]
```

**Key Pipeline Details (J06/J07):**
- **Concurrency & Identity Control**: Unauthenticated guest imports identify jobs by username. Authenticated imports require an active `AccountConnection` owned by caller. Enforces at most 1 active job per account.
- **In-Memory Streaming**: PGN files streamed from Chess.com public archives are parsed directly in memory with 0 disk writes.
- **Polling Loop**: Client polls `GET /api/jobs/{id}` at 2-second intervals until terminal status (`COMPLETED` or `FAILED`).

---

### 3.2 Journeys 8, 9 & 10: Session Bootstrap, Registration, and Login Lifecycle
*User goal: Maintain seamless guest access while providing optional secure user registration, session persistence, and clean sign-out.*

```mermaid
flowchart TD
    A1["User Opens<br/> Application"] --> A2["Client Dispatches<br/> GET /api/me"]
    A2 --> A3{{"Status?"}}

    A3 -->|"401"| A4["Initialize Guest<br/> Mode"]
    A3 -->|"200"| A5["Initialize<br/> Authenticated Mode"]
    A3 -->|"Error"| A6["Show Offline<br/> Banner"]

    A4 --> A7{{"User Action?"}}

    A7 -->|"Register"| A8["Navigate to<br/> /register"]
    A8 --> A9["Submit Registration<br/> Form"]
    A9 --> A10{{"Outcome?"}}
    A10 -->|"409"| A11["Show Conflict<br/> Error"]
    A10 -->|"200"| A12["Set Session<br/> Cookie"]

    A7 -->|"Sign In"| A13["Navigate to<br/> /login"]
    A13 --> A14["Submit Login<br/> Form"]
    A14 --> A15{{"Outcome?"}}
    A15 -->|"401"| A16["Show Invalid<br/> Credentials"]
    A15 -->|"200"| A12

    A12 --> A5

    A5 --> A17["Sliding Idle<br/> Window Active"]
    A17 --> A18{{"Session End?"}}

    A18 -->|"Sign out"| A19["POST<br/> /api/logout"]
    A19 --> A20["Revoke Session<br/> & Clear Cookie"]
    A20 --> A4

    A18 -->|"Inactivity"| A21["Server Returns<br/> 401"]
    A21 --> A4
```

**Key Security & Auth Details (J08/J09/J10):**
- **Guest State First**: Initial HTTP 401 on `GET /api/me` is not an error; it establishes guest session state and sets the readable double-submit `XSRF-TOKEN` cookie.
- **Credentials & Registration**: Canonical lowercased email, salted PBKDF2 hash, constant-time validation. Duplicate registrations return `409 REGISTRATION_CONFLICT`.
- **Session Duration**: HttpOnly Secure `CHESSPROBE_SESSION` cookie with sliding 30-minute inactivity timeout and 30-day absolute expiration cap. Explicit logout revokes the server session row immediately.

---

## 4. Source-Specific Divergence & Behavioral Contracts

The following table summarizes observable behavioral differences across the five entry routes into shared practice:

| Dimension | J01 (Weaknesses) | J02 (Decisions) | J03 (Similar Positions) | J04 (Supplied Position) | J05 (Practice Catalog) |
|---|---|---|---|---|---|
| **Originating Route** | `#weaknesses` | `#long-decisions` | `#similar-positions` | Header Explore Modal | Direct `#puzzles` tab |
| **`activePuzzle.source`** | `'weakness'` | `'long-decision'` | `'similar'` | `'supplied'` | `'weakness'` / `'catalog'` |
| **Recurrence Badges Allowed?** | Yes (`isWeaknessBacked: true`) | Only if $\ge 5$ blunders exist | Only if $\ge 5$ blunders exist | No (strictly hidden) | Yes (`isWeaknessBacked: true`) |
| **Initial Target Move Pre-Defined?** | Yes (Engine PV Best Move) | Yes (Calculated Best Move) | Yes (Candidate Engine PV) | No (Arbitrary FEN/PGN) | Yes (Catalog Puzzle Move) |
| **Move Rollback on Blunder?** | Yes (Rolls back if $>0.80$ pawns loss) | Yes (Rolls back if $>0.80$ loss) | Yes (Rolls back if $>0.80$ loss) | Yes (Dynamic eval rollback) | Yes (Rolls back if $>0.80$ loss) |
| **Answer Secrecy Enforcement?** | Strict (Continuation hidden pre-move) | Strict (Continuation hidden pre-move) | Strict (Continuation hidden pre-move) | Exempt (Engine PV visible) | Strict (Continuation hidden pre-move) |
| **Exit Destination** | `#weaknesses` | `#long-decisions` | `#similar-positions` | Tab active before modal | `#puzzles` (Catalog view) |
| **Context Snapshot Restored?** | Scroll position | Filters, severity, timer | Query FEN & candidates | Modal dismissed | Catalog filter selections |

---

## 5. Product Owner Walkthrough: Key Challenge Points

This section highlights real-world user scenarios for product-owner review and decision:

### Scenario A: Disconnecting Account During Active Exploration
- **What happens:** If a user is practicing a weakness in Line Exploration and disconnects their Chess.com account in another tab/window:
  - `handleDisconnect` calls `clearAccountUiState()`.
  - The in-flight puzzle is cleared, and `longDecisionReturnSnapshotRef` is reset to null.
  - The view drops back into guest mode cleanly without rendering corrupted historical blunders.

### Scenario B: Dynamic Similarity Engine Timeout
- **What happens:** When searching similar positions for a complex tactical middlegame, Stockfish candidate reranking might hit the 500ms watchdog ceiling under heavy load:
  - The system **does not crash or show an error modal**.
  - It seamlessly falls back to static relational BM25 scoring and sets `dynamicSimilarityApplied: false`.
  - An informational indicator explains that static ranking was applied.

### Scenario C: Custom FEN Exploration vs. Puzzle Rollback
- **What happens:** In J04 (Explore Position modal), a user pastes a custom position to analyze. If they intentionally play a blunder move to test a defense:
  - The system currently treats it under the standard puzzle policy: it evaluates move loss, flags it as inaccurate, and rolls the board back.
  - *Product Owner Question:* Should supplied position exploration operate as a strict puzzle (rolling back blunders), or should it offer an unconstrained "Sandbox Mode" where any legal move can be played and explored? (Tracked in Traceability Matrix).
