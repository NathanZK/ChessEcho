# Human Move Provider Architecture

## 1. Problem Statement
Within ChessEcho's Line Exploration, users can play against the engine or a simulated human opponent. The goal of the Human Move Provider is to answer: "If I play this move against players around a selected rating, what would they realistically tend to play?" This behavior must come from empirical data observed in real historical games. It must not mean taking Stockfish's second/third-best move and pretending it is human. The user explicitly selects an opponent rating band (e.g., 800–1000, 1000–1200) representing the population they want to practice against, independent of their own rating.

## 2. Architectural Decisions

### Data Acquisition via Graph Traversal
The Human Move Provider does not scan a pre-existing complete historical corpus. Instead, data acquisition is a rating-band-specific graph traversal:
1. Select an initial set of random Chess.com players in a target band (e.g., 800–1000).
2. Fetch their games and discover their opponents.
3. Filter for opponents who also belong to the target band.
4. Traverse those players and discover more games.
5. Continue until coverage criteria are met.

"Seeding a rating band" means actively constructing this corpus.

### Do Not Persist Seed Games
Games encountered during the graph traversal are NOT persisted as normal ChessEcho user `Game` records. The **existing user `Game` and `PositionOccurrence` data** serve a distinct purpose (user-specific weakness analysis) and must remain unchanged. 

The human-data seeding pipeline follows this flow:
`Discovered players -> temporary processing of discovered games -> extract position/move -> aggregate -> persist HumanMoveDistribution -> discard raw game data`

The goal is to persist the compact behavioral model (the **permanent `HumanMoveDistribution` dataset**), not the external corpus.

### Game Deduplication Metadata
Because graph traversal can discover the same game multiple times (e.g., through Player A and later Player B), games must be processed only once per seeding run. We must maintain persistent or resumable **deduplication/seeding metadata** keyed by game ID. This ensures we do not repeatedly process the same game. This is strictly metadata for the traversal; game IDs must not be stored in the permanent `HumanMoveDistribution` rows.

### Aggregation and Rating Handling
During a seeding run for a specific population (e.g., 800–1000), aggregation happens at the move level. While exact player ratings are available during raw processing where useful, the aggregation does NOT group by exact numeric ratings. 
The in-memory aggregation looks like: `Position X | Nf6 | count`
When promoted to the permanent dataset, it becomes: `Position X | 800–1000 | Nf6 | count`
The permanent human distribution is strictly band-level.

### No Temporary `PositionOccurrence` Table
Do not introduce a temporary database table to store raw position occurrences (e.g., one row per `position + move + game_id`) during seeding. The seeding algorithm should process games and aggregate observations in-memory, directly persisting only the resulting aggregate counts. A staging database table should only be introduced later if actual scale requires it.

### Permanent Human Move Distribution
The permanent dataset is an aggregated count conceptually modeled as:
`Position + Rating Band + Move + Number of Observations`
Counts are the source of truth; probabilities are derived dynamically at runtime.

### Weighted Sampling
The provider must not deterministically select the most common move. It should use weighted sampling from the empirical distribution (e.g., 42% for Nf6, 28% for d6). Randomness must be injectable to support deterministic unit testing.

### Sparse Data Fallback
When data is insufficient for a specific position and rating band, a strict fallback hierarchy applies:
1. Exact requested rating band (if sufficient data exists).
2. Adjacent rating bands.
3. Wider human population.
4. `ENGINE` fallback (only when there is insufficient human data overall).

The `ENGINE` provider is strictly a data-availability fallback, not the source of HUMAN behavior.

### Continuation Architecture Integration
The architecture preserves the existing continuation contract where `ContinuationService` acts as orchestrator. The selected rating band is passed into the provider layer. The Human Move Provider handles the empirical distribution lookup without being coupled specifically to Challenge Mode.

## 3. Implementation Phases

**PHASE 1 — Schema + manufactured data + end-to-end behavior**
* Define the minimum `HumanMoveDistribution` schema/entity and repository.
* Create manufactured/fake records to test against (do NOT build the real seeding algorithm yet).
* Implement provider logic, including selected rating band support, weighted empirical move selection, and sparse-data fallback.
* Integrate with the existing `ContinuationService`.
* Update the frontend just enough to pass the selected rating band from the existing Line Exploration → "Play against ChessEcho" flow (no Challenge Mode redesign).
* Thoroughly test and verify the architecture end-to-end.

**PHASE 2 — Historical seeding algorithm**
* Design and implement the rating-band-specific graph traversal based around the existing game acquisition architecture.
* Fetch random players, traverse their opponents within the band, and process their games.
* Maintain resumable deduplication/seeding metadata so games are processed only once.
* Perform in-memory temporary processing of discovered games to aggregate positions/moves.
* Apply statistical thresholds and persist meaningful counts into the permanent `HumanMoveDistribution` dataset (discarding raw games).

**PHASE 3 — Production seeding / refinement**
* Run the proven seeding algorithm for desired rating bands.
* Monitor dataset size and distribution quality.
* Refine thresholds and fallback behaviors based on real sparse regions.
* Optimize traversal or introduce staging storage only if empirical scale necessitates it.

> Phase 1 proves the model and end-to-end behavior with manufactured data. Phase 2 builds the real historical-data seeding algorithm.
