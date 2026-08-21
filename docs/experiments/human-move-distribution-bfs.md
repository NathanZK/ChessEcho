# Human Move Distribution BFS Experiment

## 1. PURPOSE
The Human Move Distribution initiative aims to empirically determine which moves human players in a specific rating band actually choose when encountering recurring positions, rather than relying strictly on engine evaluations or optimal theory. 

By collecting this data per rating band, we can model typical human behavior, identify common blunders, and surface realistic branching patterns. This experiment was designed to answer whether traversing Chess.com rapid games from a specific rating population via a Breadth-First Search (BFS) graph can provide useful empirical distributions, and to understand the shape, density, and persistence requirements of the resulting dataset.

## 2. ALGORITHM
The data collection is driven by a bounded Breadth-First Search over the Chess.com player graph:
- **Seed Players:** The search starts from an initial set of 10 seed usernames.
- **Rating-Band Qualification:** A game only qualifies if at least one of its players falls into the target rating band (e.g., 1000–1200).
- **Rapid-Only Filtering:** Only games with `time_class` equal to `rapid` are processed.
- **Opponent Discovery/Traversal:** When processing a player's games, any encountered opponents are added to the next BFS frontier. 
- **BFS Traversal Rules:** Crucially, a player/game can be used for *traversal* (and their opponents discovered) even if that opponent is outside the target rating band. However, **move attribution** strictly mandates that moves are only contributed to the dataset when the specific player making that move is currently rated in the target band.
- **Limits:** The traversal respects limits on BFS depth, maximum games inspected per player, and maximum total qualifying games.
- **Deduplication:** Players and game URLs are tracked in memory to prevent processing the same player or game twice.
- **Aggregation:** Move observations are aggregated in memory by `position` (FEN hash) and `move`, keeping track of total frequency before batch-saving.

## 3. WHY GAMES INSPECTED > QUALIFYING GAMES
During traversal, the number of *games inspected* (7,527) was significantly higher than the number of *qualifying games* (2,000). This is expected behavior, not a bug, because of the following subset logic:
- **Games inspected:** Every game encountered while traversing a player's monthly archive.
- **Rapid games:** The subset of inspected games where the time control is rapid.
- **Qualifying games:** The subset of rapid games where at least one player's rating falls strictly within the target 1000–1200 band.

Because we traverse opponents even if they are out-of-band, many of their subsequent games will involve players who are also out-of-band. These games are inspected but rejected, preventing them from contributing to the `qualifying games` count.

## 4. DATA METRICS TERMINOLOGY
To interpret the dataset sizes clearly, the following distinctions apply:
- **Total observations:** The raw count of every individual move made by a qualifying player.
- **Unique positions:** The count of distinct board states (identified by structural FEN hashes) encountered during the run.
- **Positions meeting an observation threshold:** The strict subset of unique positions where the sum of *all* observations for that specific position meets or exceeds a required minimum.
- **Move-distribution rows:** The distinct `(position, movePlayed)` records persisted to the database. A single position typically generates multiple move-distribution rows if players chose different candidate moves.

## 5. FIRST 2,000-GAME EXPERIMENT
An initial 2,000 qualifying-game dry run was executed with no persistence threshold.

**Results:**
- Players visited: 77
- Distinct players discovered: 5,775
- BFS depth reached: 1
- Total games inspected: 7,527
- Rapid games: 6,029
- Qualifying games: 2,000
- Unique games processed: 5,971
- Total observations: 104,296
- Unique positions generated: 94,147
- Move-distribution rows initially persisted: 96,124

**Important Finding:** Out of the 94,147 unique positions, **92,991 positions had only one total observation.** This demonstrated a very large long tail of unique positions where games completely diverge.

## 6. LESSON FROM FIRST EXPERIMENT
Persisting every discovered position proved highly undesirable. The dataset was dominated by tens of thousands of one-off positions lacking sufficient evidence to form a useful probability distribution. The useful statistical signal was heavily concentrated in a tiny fraction of recurring positions.

**Position Recurrence in the first run:**
- >= 5 observations: 309 positions
- >= 10 observations: 126 positions
- >= 20 observations: 59 positions
- >= 50 observations: 17 positions
- >= 100 observations: 9 positions

## 7. minObservations CHANGE
To address the persistence of one-off positions, a `minObservations` configuration threshold was added to the request (`HumanMoveBfsRequest.minObservations`), defaulting to 5.
- The threshold is applied to the **total observations for a position** across all candidate moves.
- Positions whose total observation count falls strictly below this threshold are not persisted to the database.
- Aggregation still happens in memory before filtering.

This does **not** mean we stop observing rare positions during the crawl. We still collect them in memory so their frequency can be determined; we simply drop the positions that fail the evidence threshold when flushing to the database.

## 8. SECOND 2,000-GAME RUN
The experiment was re-run with `minObservations = 5` and the database cleared.

**Results:**
- In-memory total observations: 104,238
- Unique positions encountered in memory: 94,092
- Positions meeting the observation threshold (persisted): 310
- Move-distribution rows persisted: 1,275
- Total observations represented by persisted distributions: 8,701

The addition of the threshold created a dramatic difference: while ~94k unique positions were discovered in memory, only 310 high-signal positions actually generated database rows, vastly reducing database write-amplification and noise.

## 9. LIMITATIONS
- This was strictly an exploratory 2,000-qualifying-game experiment.
- The BFS seed logic and player traversal approach can introduce sampling bias based on the specific subgraph of the starting seeds.
- The `minObservations = 5` filter is currently a heuristic rather than a statistically validated threshold.
- Substantially larger samples are required before treating this as production-quality data.

## 10. INTERPRETATION / CURRENT CONCLUSION
- The BFS collection mechanism successfully worked under the experiment's constraints against real Chess.com data.
- The data naturally exhibits a massive long tail due to chess's branching factor.
- Recurring positions do contain meaningful branching distributions, providing evidence for the core concept.
- A persistence threshold is absolutely necessary to prevent database bloat.

## 11. NEXT EXPERIMENT
The next logical step is to increase the number of qualifying games (e.g., to 25k or 50k) and evaluate:
- The number of positions meeting the observation threshold.
- The number of positions with 2+ candidate moves (which provide actual branching distributions).
- Observation depth and opening coverage.
- Distribution stability across runs.
- Whether `minObservations = 5` remains appropriate at a larger scale.
