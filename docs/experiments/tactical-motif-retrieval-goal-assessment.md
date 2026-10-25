# Relevance of Tactical Motif Retrieval Signals to ChessEcho's Player-Weakness Goal

status: approved-assessment
date: 2026-10-25
issue: https://github.com/NathanZK/ChessEcho/issues/607
cross_references:
  - Issue #91: Arbitrary-FEN position retrieval
  - Issue #523: Player weakness analysis across game groupings
  - docs/experiments/2026-10-25-bizjak-tactical-motif-v1-specification.md
  - docs/experiments/tactical-motif-v1-per-query-analysis.md

---

## 1. Executive Summary

This assessment evaluates whether the retrieval signals tested in Stage 1 and Stage 2 of Issue #607 (Ganguly piece-attack baselines and Bizjak static/dynamic extensions) can transfer from puzzle retrieval to ChessEcho's actual product objective: **identifying recurring tactical weaknesses across a player's own games**.

We explicitly distinguish:
1. **Verified experimental facts** established by the frozen benchmark on Lichess puzzle candidate pools.
2. **Product alignment and architectural constraints** governing ChessEcho's backend and ingestion pipeline.
3. **Open research hypotheses** that cannot be verified from puzzle benchmarks and require an empirical own-game dataset.

---

## 2. Product Goal vs. Evaluated Benchmark

| Dimension | Evaluated Benchmark (Issue #607) | ChessEcho's Actual Goal (Issues #91 & #523) |
|---|---|---|
| **Data Domain** | Curated tactical puzzles (Lichess CC0). | Unannotated player games (PGNs from Chess.com / Lichess). |
| **Position Nature** | Forced winning tactics with short, unambiguous solutions. | Full game trajectories (opening, middlegame, endgame) dominated by quiet positions, positional errors, and blunders. |
| **Solution Availability** | Pre-computed, verified winning solution moves ($M_1, \ldots, M_{K-1}$). | No solution moves available without online engine analysis. In blunder positions, the user often played the losing move. |
| **Retrieval Objective** | Retrieve puzzles sharing the same tactical theme among 180 distractor puzzles. | Retrieve historical game positions where the player committed the same recurring tactical oversight (e.g. back-rank weakness, knight fork vulnerability). |
| **Supervision Mode** | Solution-path supervision (`dynamic_general`, `dynamic_solution`). | Unsupervised or engine-assisted indexing on raw FEN positions. |

---

## 3. Transferability of Evaluated Feature Groups

### 3.1 Static Positions (`static_positions`, Ganguly 2014)
- **Signal**: Piece placements, direct attacks, defenses, and ray attacks/pins.
- **Transferability to Own Games**: **High (Feasible without engine)**.
  - Can be computed directly from any FEN in $\approx 0.1\text{ ms}$ using pure in-memory bitboards (`FenPositionSimilarity`).
  - Benchmarked finding: Achieved perfect Hit@10 (1.0000) on `fork_N` in puzzle retrieval.
  - Relevance to Weakness Discovery: Knight fork vulnerabilities have clear geometric precursors before the fork occurs (e.g. King and Queen or King and Rook on same-color squares within knight jump distance, undefended central squares). Static attack/defense geometry can match positions with similar tactical tension without knowing the solution.
- **Limitation**: Completely blind to back-rank vulnerability (Hit@10 = 0.4375, MRR = 0.1538) when evaluated alone.

### 3.2 Static Pawn Structures (`static_pawns`, Plut 2018 / Bizjak §2.1.2)
- **Signal**: Isolated pawns (`I{sq}`), passed pawns (`F{sq}`), backward pawns (`l{sq}`), doubled files (`S{file}`), pawn chains (`w[...]`), and pawn islands (`P(n)`, `p(n)`).
- **Transferability to Own Games**: **High (Feasible without engine)**.
  - Computable from FEN without move trees or engine search.
  - Benchmarked finding: Nearly doubled `back_rank_mate` Hit@10 from 0.4375 to 0.8125 and more than doubled Hit@5 from 0.1250 to 0.3125.
  - Relevance to Weakness Discovery: Player back-rank blunders almost always stem from a specific structural defect: an intact pawn shield (f7/g7/h7 or f2/g2/h2) with no "luft" (escape square) combined with an open back rank. Pawn chains and pawn island counts effectively detect this structural pattern across arbitrary game positions.
- **Caution / Trade-off**: As demonstrated in `query_fork_N_09`, static pawn structures can dominate sparse endgame positions, matching pawn skeleton similarity rather than piece-tactical tension. Pawn features must be balanced or restricted when querying pure piece-tactics.

### 3.3 Dynamic Solution Features (`dynamic_general`, `dynamic_solution`, Bizjak §2.2)
- **Signal**: Move piece types (`!-Piece`), captures (`!xPiece`), checks (`?+`), sacrifices (`?S`), and checkmate (`!#Piece`).
- **Transferability to Own Games**: **Low to Infeasible for Real-Time Raw-FEN Retrieval (#91)**.
  - Dynamic tokens achieved outstanding retrieval on puzzles (0.9688 Hit@10, 0.6641 MRR), but this was driven entirely by **solution-path leakage**: the tokens explicitly encode the moves of the tactical solution.
  - In a player's raw game position, there are no pre-computed solution moves. Computing these tokens would require running an engine (e.g. Stockfish) to depth $\ge 12$ to compute principal variations for every move in the user's game library.
  - Offline Ingestion Exception (#523): During asynchronous background game import, if ChessEcho runs an engine blunder detector to tag mistakes, the engine's refutation line *could* be tokenized using Bizjak dynamic tokens and stored in the database. In that specific offline setting, dynamic tokens could cluster blunder positions by refutation motif.

---

## 4. Architectural & Product Constraints

1. **Engine Independence Invariant**:
   ChessEcho's core similarity service (`FenPositionSimilarity`) operates in-memory on pure board representations without invoking external Stockfish processes. Introducing mandatory engine evaluation for similarity search would introduce high latency and resource contention. Static pawn and geometric extensions preserve engine independence.

2. **Index Dilution in Joint Retrieval**:
   Our per-query ranking analysis demonstrated that naive uniform BM25 fusion ($w_j=1.0$) causes static features (120-180 tokens) to swamp dynamic tokens (6-15 tokens), decreasing MRR from 0.6641 to 0.5362. If ChessEcho adopts multi-modal features in the future, it must use multi-field index weights or reciprocal rank fusion (RRF) rather than single-pass term concatenation.

3. **Open-World Precision vs. Candidate-Pool Hit@k**:
   In a real user game library of 500 games ($\approx 40,000$ positions), the ratio of quiet positions to tactical blunders is $\approx 100:1$. A method that achieves 80% Hit@10 in a curated 200-candidate pool with 10% positive density may suffer high false-positive rates when scanning 40,000 arbitrary game positions.

---

## 5. Verified Facts vs. Open Hypotheses

### 5.1 Verified Facts (Supported by Issue #607 Benchmark)
1. Static pawn structures (`bizjak_static_only`) provide significant, measurable lift for back-rank mate retrieval (Hit@10 0.4375 $\to$ 0.8125) over Ganguly static representations without using solution moves.
2. Ganguly static representations (`baseline_ganguly_static`) provide strong baseline retrieval for knight forks (Hit@10 = 1.0000) due to localized attack and ray geometry.
3. Dynamic sequence tokens (`bizjak_dynamic_only`) deliver superior motif clustering when solution lines are known, but this performance reflects solution-path leakage.
4. Equal-weight fusion of static and dynamic tokens dilutes dynamic discriminability.

### 5.2 Open Hypotheses (Requiring Future Own-Game Evaluation)
1. **Hypothesis 1 (Static Blunder Clustering)**: Combining static position geometry with static pawn structures can retrieve similar tactical blunder positions from a user's game archive without engine evaluation at precision sufficient for player weakness identification.
2. **Hypothesis 2 (Opponent Refutation Tokenization)**: Tokenizing the opponent's immediate punishing move in user-lost games (without full engine search) provides a lightweight dynamic signal that improves weakness grouping over static FEN alone.
3. **Hypothesis 3 (Filtering Pre-Filter)**: Static BM25 similarity can serve as an efficient Stage 1 filter to narrow 40,000 game positions to top 50 candidates, after which a targeted engine evaluation can confirm true recurring tactical weaknesses.

---

## 6. Recommendations for ChessEcho Roadmap

1. **Immediate Next Step for Static Similarity (#91)**:
   Integrate Plut/Bizjak static pawn structure features (`static_pawns`: pawn chains, isolated pawns, and pawn islands) into ChessEcho's static similarity pipeline. This directly addresses the known back-rank blindness of the Stage 1 baseline without violating the engine-free invariant.
2. **Offline Ingestion Pipeline (#523)**:
   Investigate offline engine refutation tokenization during PGN game import, where asynchronous compute is acceptable, enabling dynamic motif clustering for user weakness reports.
3. **Establish an Own-Game Benchmark Dataset**:
   Do not extrapolate puzzle retrieval accuracy directly to full-game weakness diagnosis. Construct a dedicated benchmark of player-annotated game blunders to measure open-world precision and recall.
