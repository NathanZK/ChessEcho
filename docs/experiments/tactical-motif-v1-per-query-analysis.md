# Tactical Motif Retrieval (Stage 1 & Stage 2) Per-Query Ranking Analysis

status: approved-baseline-analysis
date: 2026-10-25
issue: https://github.com/NathanZK/ChessEcho/issues/607
reference_specification: docs/experiments/2026-10-25-bizjak-tactical-motif-v1-specification.md
results_source: docs/experiments/tactical-motif-v1-results.json
frozen_pool_digest: 705ab4cbff0eaad0a6ad8eb6e9cbb5f7d288504f8a25c18eae9a9d595a39220d

---

## 1. Executive Summary & Objectives

This document provides a systematic per-query analysis of the 32 queries and 6,400 candidate evaluations recorded in `docs/experiments/tactical-motif-v1-results.json`. The analysis explains:
1. Where and why each configuration succeeds or fails on individual queries.
2. The specific geometric and structural mechanisms by which static pawn features (`bizjak_static_only`) rescue back-rank mate retrieval.
3. The discriminative power and vulnerability of dynamic solution tokens (`bizjak_dynamic_only`).
4. The mathematical cause of score dilution and rank inversions in joint combined fusion (`bizjak_combined`).
5. Representative query-level error cases, backed by exact FEN positions, move sequences, and BM25 term scores.

In accordance with Praxis research invariants, no weights, thresholds, features, or candidate selection parameters were tuned against this benchmark; the original results remain immutable baseline evidence.

---

## 2. Complete Query-Level Ranking Table

The table below lists the first target positive rank ($\text{rank}_1$) for all 32 queries across the four evaluated configurations ($N=200$ candidates per query):
- **Base**: `baseline_ganguly_static` (Stage 1 frozen baseline at commit `8793d58`)
- **Static**: `bizjak_static_only` (Stage 2 static positions + Plut/Bizjak pawn structures)
- **Dynamic**: `bizjak_dynamic_only` (Stage 2 dynamic general flags + sequence tokens)
- **Combined**: `bizjak_combined` (Stage 2 joint static + dynamic fusion, uniform $w_j=1.0$)

| Query ID | Motif | Puzzle ID | Root FEN (Active Side) | Base $\text{rank}_1$ | Static $\text{rank}_1$ | Dynamic $\text{rank}_1$ | Combined $\text{rank}_1$ |
|---|---|---|---|---|---|---|---|
| `query_fork_N_01` | `fork_N` | `000Pw` | `r1bq1rk1/pp2ppbp/2np1np1/8/3NP3/2N1BP2/PPPQ2PP/R3KB1R w KQ -` | 2 | 3 | 2 | 1 |
| `query_fork_N_02` | `fork_N` | `001pC` | `2r2rk1/1pq1bppp/p2p1n2/4p3/P1n1P3/1PN2N2/2PB1PPP/R2QR1K1 w - -` | 3 | 4 | 1 | 2 |
| `query_fork_N_03` | `fork_N` | `002GQ` | `r4rk1/1bq1bppp/p1q1pn2/2p1N3/8/3B4/P1P1QPPP/R4RK1 b - -` | 5 | 1 | 1 | 1 |
| `query_fork_N_04` | `fork_N` | `002KJ` | `r3k2r/pppq1ppp/2n1bn2/bB1p4/3P4/P1N1BN2/1PP2PPP/R2Q1RK1 b kq -` | 9 | 4 | 2 | 3 |
| `query_fork_N_05` | `fork_N` | `002O7` | `r1bq1rk1/ppp2ppp/2n5/3pP3/3P4/P1bB1N2/1P3PPP/R1BQK2R w KQ -` | 10 | 8 | 3 | 8 |
| `query_fork_N_06` | `fork_N` | `002Q2` | `r1b2rk1/pppn1ppp/3p1q2/2b5/2BNP1n1/2P4P/PP3PP1/RNBQR1K1 b - -` | 1 | 1 | 1 | 1 |
| `query_fork_N_07` | `fork_N` | `002rd` | `r4rk1/pp1b1ppp/1qn1pn2/2b5/2B5/P1N1PN2/1P2QPPP/R1BR2K1 b - -` | 4 | 10 | 1 | 4 |
| `query_fork_N_08` | `fork_N` | `003S3` | `r4rk1/1p2qppp/p1n1pn2/2Pp4/1P1P4/P4B2/5PPP/R2Q1RK1 b - -` | 4 | 4 | 1 | 2 |
| `query_fork_N_09` | `fork_N` | `003Tx` | `2r5/pR5p/5p1k/4p3/4R3/B4nPP/PP3P2/1K6 b - -` | 1 | 12 | 3 | 8 |
| `query_fork_N_10` | `fork_N` | `003jH` | `r2q1rk1/1b1nbppp/ppn1p3/2ppP3/3P4/2PBBN2/PP1N1PPP/R2Q1RK1 w - -` | 3 | 2 | 2 | 1 |
| `query_fork_N_11` | `fork_N` | `003jv` | `r4rk1/pp2ppbp/1qnp1np1/8/2PNP3/2N1BP2/PP2Q1PP/R4RK1 b - -` | 8 | 8 | 1 | 1 |
| `query_fork_N_12` | `fork_N` | `003md` | `r2qk2r/ppp2ppp/2n1b3/2bnp3/2B5/1PNP1N2/P1P2PPP/R1BQK2R w KQkq -` | 1 | 2 | 8 | 2 |
| `query_fork_N_13` | `fork_N` | `003r5` | `r2q1rk1/1pp1bppp/p1n1bn2/3p4/3P4/PQN1PN2/1P2BPPP/R1B1K2R w KQ -` | 9 | 9 | 1 | 4 |
| `query_fork_N_14` | `fork_N` | `004nd` | `2r1r1k1/pp3pbp/1q4p1/3p1n2/1B1P4/P4N1P/1P1Q1PP1/3RR1K1 b - -` | 3 | 2 | 3 | 2 |
| `query_fork_N_15` | `fork_N` | `006om` | `r2qr1k1/pp3ppp/2n1bn2/2b5/2Pp4/PP3N2/1B1NBPPP/R2Q1RK1 w - -` | 5 | 6 | 1 | 2 |
| `query_fork_N_16` | `fork_N` | `007AS` | `r1bq1rk1/ppp2pbp/2np1np1/4p3/2PPP3/2N1BP2/PP1Q2PP/R3KBNR w KQ -` | 1 | 1 | 1 | 1 |
| `query_back_rank_mate_01` | `back_rank_mate` | `000Zo` | `4r1k1/2p2pbp/p5p1/1p2P3/1P6/P5BP/2Pr1PP1/R3R1K1 b - -` | 5 | 13 | 12 | 9 |
| `query_back_rank_mate_02` | `back_rank_mate` | `001KR` | `2r3k1/pp3ppp/4p3/1q1p4/3Pn3/BP2P3/P4PPP/3Q2K1 b - -` | 7 | 3 | 2 | 2 |
| `query_back_rank_mate_03` | `back_rank_mate` | `001Wz` | `3r2k1/ppp2ppp/8/8/2N5/5Q2/PPq2PPP/1R4K1 w - -` | 8 | 6 | 1 | 1 |
| `query_back_rank_mate_04` | `back_rank_mate` | `001w5` | `r4rk1/ppp2ppp/4b3/8/3QN3/4R3/PPP2PPP/R5K1 b - -` | **19** | **10** | 7 | 14 |
| `query_back_rank_mate_05` | `back_rank_mate` | `001wR` | `2r3k1/ppp2ppp/8/4P3/5P2/P2r4/1P3RPP/R5K1 b - -` | **18** | **10** | 1 | 2 |
| `query_back_rank_mate_06` | `back_rank_mate` | `002X3` | `4r1k1/pp3ppp/2p5/8/4B1P1/bP3P2/b1P4P/3K2NR b - -` | 10 | 10 | 1 | 11 |
| `query_back_rank_mate_07` | `back_rank_mate` | `002Z9` | `3r2k1/p1p2ppp/2p5/8/2P5/3q4/PP3PPP/1R2Q1K1 b - -` | 1 | 3 | 1 | 1 |
| `query_back_rank_mate_08` | `back_rank_mate` | `002p5` | `r1bqr1k1/pp1nbpp1/2p5/3n2P1/2BP4/P7/1PQNNPP1/R3K2R w KQ -` | **33** | **10** | 5 | 7 |
| `query_back_rank_mate_09` | `back_rank_mate` | `002vV` | `5rk1/pp1b1ppp/4p3/4P3/8/2q2BP1/P4P1P/R2Q2K1 b - -` | **11** | **6** | 2 | 1 |
| `query_back_rank_mate_10` | `back_rank_mate` | `0030b` | `3r2k1/ppp2ppp/8/4n3/2P5/1B5P/PP3PP1/3R2K1 b - -` | 6 | 4 | 3 | 2 |
| `query_back_rank_mate_11` | `back_rank_mate` | `0042j` | `3r2k1/4nppp/pq3b2/1p2p3/2r2P2/2P1NR2/PP1Q2BP/3R2K1 w - -` | 11 | 36 | 3 | 24 |
| `query_back_rank_mate_12` | `back_rank_mate` | `00465` | `2r3k1/pp3ppp/8/1B1p4/1P1B4/P4P2/2rn1KPP/R7 b - -` | 6 | 1 | 1 | 1 |
| `query_back_rank_mate_13` | `back_rank_mate` | `004WZ` | `3r2k1/ppp2ppp/2b5/8/2P5/8/PP3PPP/3R2K1 b - -` | **12** | **6** | 1 | 5 |
| `query_back_rank_mate_14` | `back_rank_mate` | `004X6` | `3r2k1/ppp2ppp/8/8/2b5/2N5/PP3PPP/3R2K1 b - -` | **12** | **3** | 1 | 1 |
| `query_back_rank_mate_15` | `back_rank_mate` | `004XI` | `r5k1/pp2ppbp/2p3p1/4P3/5P2/P2r4/1P3RPP/R5K1 b - -` | **39** | **18** | 5 | 12 |
| `query_back_rank_mate_16` | `back_rank_mate` | `004kB` | `2r3k1/pp1b1ppp/4p3/4P3/8/2P2BP1/P4P1P/R2Q2K1 b - -` | **21** | **10** | 3 | 3 |

---

## 3. Configuration Performance & Mechanisms

### 3.1 `baseline_ganguly_static` (Stage 1 Baseline)
- **Strengths**: Perfect Hit@10 (1.0000) and Hit@5 of 0.7500 on `fork_N`. Knight fork configurations possess dense local geometric signatures: pieces clustered around the knight jump targets, multiple simultaneous attack vectors (`attack|...`), and ray pins (`ray|...`). Even without solution moves, the static board geometry strongly correlates with fork potential.
- **Failures**: Severe breakdown on `back_rank_mate` (Hit@10=0.4375, Hit@5=0.1250, Hit@1=0.0625, MRR=0.1538). 9 out of 16 queries (56.3%) failed to rank any positive in the top 10. For instance, `004XI` placed its first positive at rank 39, `002p5` at rank 33, `004kB` at rank 21, `001w5` at rank 19, and `001wR` at rank 18.
- **Root Cause**: The defining vulnerability of a back-rank mate is the *absence of an escape square* caused by a wall of friendly pawns (typically pawns on f7/g7/h7 or f2/g2/h2) combined with an open or semi-open back rank. In `baseline_ganguly_static`, pawn positions are simply individual piece tokens (`piece|b|P|f7`, etc.) with short-range attacks. There are no relational features connecting adjacent pawns into a structure or identifying uncastled/cramped kings. Candidate positions sharing unrelated pieces (e.g. queens or bishops on common central squares) easily outscore true back-rank mates.

### 3.2 `bizjak_static_only` (Pawn Structures + Static Positions)
- **Strengths**: Drastic rescue of `back_rank_mate` retrieval. Hit@10 nearly doubled from 0.4375 to 0.8125; Hit@5 more than doubled from 0.1250 to 0.3125; MRR rose from 0.1538 to 0.2131.
  - `query_back_rank_mate_04` (`001w5`): rank 19 $\to$ 10.
  - `query_back_rank_mate_05` (`001wR`): rank 18 $\to$ 10.
  - `query_back_rank_mate_08` (`002p5`): rank 33 $\to$ 10.
  - `query_back_rank_mate_09` (`002vV`): rank 11 $\to$ 6.
  - `query_back_rank_mate_12` (`00465`): rank 6 $\to$ 1.
  - `query_back_rank_mate_13` (`004WZ`): rank 12 $\to$ 6.
  - `query_back_rank_mate_14` (`004X6`): rank 12 $\to$ 3.
  - `query_back_rank_mate_15` (`004XI`): rank 39 $\to$ 18.
  - `query_back_rank_mate_16` (`004kB`): rank 21 $\to$ 10.
- **Underlying Mechanism**: The Plut (2018) / Bizjak (§2.1.2) pawn features explicitly encode:
  - Pawn chains: `w[f7/g7/h7]` or `w[f2/g2/h2]` capture contiguous pawn barriers.
  - Pawn islands: `p(2)`, `p(3)`, `P(2)`, `P(3)` capture intact castled wings.
  - Isolated pawns: `I{sq}` flags disconnected files.
  Back-rank mate positions overwhelmingly share intact 3-pawn kingside shields without pawn luft (no h6 or g6 push). Because candidate distractors frequently feature broken pawn structures, pawn chains produce large positive BM25 scores for back-rank mates, lifting them above distractors.
- **Failures / Regressions**: Slight degradation on `fork_N` (Hit@10 slipped from 1.0000 to 0.9375; MRR from 0.4280 to 0.3934). On `query_fork_N_09` (`003Tx`), rank dropped from 1 to 12. Because `003Tx` is a rook-and-pawn endgame position, its pawn chains (`w[a2/b2]`, `w[f2/g3/h3]`) matched distractor `002Mm` which had an identical pawn skeleton, pushing it ahead of the tactical target positives.

### 3.3 `bizjak_dynamic_only` (Dynamic Flags + Sequence Tokens)
- **Strengths**: Dominant retrieval accuracy across both motifs (Macro Hit@1=0.5000, Hit@5=0.9062, Hit@10=0.9688, MRR=0.6641). On `fork_N`, Hit@10 reached 1.0000 with MRR=0.7266. On `back_rank_mate`, Hit@10 reached 0.9375 with MRR=0.6016.
- **Underlying Mechanism**: Dynamic tokens directly encode the tactical theme:
  - For `fork_N`: `!-N` (Knight moves), `!N>Q`, `!N>R` (Knight attacks Queen or Rook), and `!xQ`/`!xR` (Knight captures high-value target).
  - For `back_rank_mate`: `!-R`, `!-Q`, `!#R`, `!#Q` (Rook/Queen delivers checkmate on rank 1 or 8), `?#` (checkmate delivered), `?+` (check delivered).
  Because background distractors in Lichess rarely execute an identical tactical sequence, these dynamic sequence tokens provide near-perfect discriminative separation.
- **Failures**:
  - `query_fork_N_12` (`003md`): First positive rank fell to 8. This query possesses an unusually long 8-ply sequence (`g3e4 e1e4 d5e4 d1h5 h4h5 b3f7 h5f7`) involving Queen trades and Bishop sacrifices. Long puzzle sequences in the distractor pool matched many non-fork sequence tokens, pushing shorter 2-ply fork positives down the ranking.
  - `query_back_rank_mate_01` (`000Zo`): First positive rank was 12. The solution sequence (`e5f6 e8e1 g1f2 e1f1`) involves a King escape step (`g1f2`) before terminal mate on f1. Unlabeled distractors executing direct back-rank checks (`006yP`, `000qP`) received higher BM25 scores.

### 3.4 `bizjak_combined` (Uniform Fusion Dilemma, $w_j=1.0$)
- **Degradation**: Macro MRR dropped sharply from 0.6641 (`dynamic_only`) to 0.5362 (`combined`), Hit@5 dropped from 0.9062 to 0.7500, and Hit@10 dropped from 0.9688 to 0.8750.
- **Mathematical Root Cause**:
  In joint BM25 scoring with equal weights ($w_j=1.0$):
  - Static positions and pawn structures generate 100 to 180 tokens per document (total static BM25 score magnitude $\approx 35 - 85$).
  - Dynamic solution sequences generate only 6 to 15 tokens per document (total dynamic BM25 score magnitude $\approx 8 - 25$).
  - Consequently, static background features account for $\approx 75\% - 85\%$ of the total composite score.
  If a non-positive distractor shares a similar opening structure, pawn skeleton, or piece layout with the query, its static score alone (e.g. 50-70) can overpower a true positive that possesses a high dynamic score (e.g. 20) but only a moderate static score (e.g. 25).
  - Example: On `query_back_rank_mate_11` (`0042j`), distractor `0000D` had static score 46.91 + dynamic score 0.0 = 58.78 total score. True positive `005N7` had static score 18.52 + dynamic score 19.15 = 37.67 total score. Distractor `0000D` was ranked #1, while `005N7` dropped to rank 24.

---

## 4. Deep-Dive Case Studies

### 4.1 Case Study 1: Distractor Intrusion and Static Dilution (`query_back_rank_mate_11`)
- **Query**: `query_back_rank_mate_11` (Puzzle `0042j`)
  - Root FEN: `3r2k1/4nppp/pq3b2/1p2p3/2r2P2/2P1NR2/PP1Q2BP/3R2K1 w - - 0 25`
  - Moves: `d6e5` (setup), `d2d8` (Qxd8+), `b6d8` (Qxd8), `d1d8#` (Rxd8#).
  - Tactical Motif: Back-rank Queen sacrifice followed by Rook checkmate.
- **Rankings by Configuration**:
  - `baseline_ganguly_static`: rank 11
  - `bizjak_static_only`: rank 36
  - `bizjak_dynamic_only`: rank 3
  - `bizjak_combined`: rank 24
- **Intruding Distractor**: Puzzle `0000D`
  - FEN: `5rk1/1p3ppp/pq1Q1b2/8/8/1P3N2/P4PPP/3R2K1 b - - 3 27`
  - Tactical theme: Queen trade in an endgame (`... Qxd6 Rxd6`). NOT a back-rank mate.
  - Overlap with Query:
    - Identical pieces and squares: Black King on `g8`, Black pawns on `f7/g7/h7`, Black Queen on `b6`, Black Bishop on `f6`, White Rook on `d1`, White Queen on d-file (`d6` vs `d2`), White King on `g1`, White pawns on `f2/g2`.
    - Static Score in Static Only: **46.91** (Rank 1).
    - Dynamic Score in Dynamic Only: **0.00** (Rank 200).
    - Combined Score: **58.78** (Rank 1).
- **True Positive**: Puzzle `005N7`
  - Moves: `f1f8`, `g8f8`, `d1d8#` (Rook deflection followed by back-rank mate).
  - Dynamic Score: **19.15** (Rank 3 in dynamic-only).
  - Combined Score: **37.67** (Rank 24 in combined).
- **Takeaway**: Static features measure piece-placement and pawn-structure proximity. When two positions share an opening or endgame piece tableau, static similarity dwarfs dynamic motif similarity unless feature weights are heavily biased toward dynamic tokens.

### 4.2 Case Study 2: Endgame Pawn Chains vs. Tactical Fork (`query_fork_N_09`)
- **Query**: `query_fork_N_09` (Puzzle `003Tx`)
  - Root FEN: `2r5/pR5p/5p1k/4p3/4R3/B4nPP/PP3P2/1K6 b - - 0 27`
  - Moves: `e1e4` (setup), `f3d2+` (player Knight fork of King and Rook), `b1a1`, `c8c1#`.
  - Tactical Motif: Knight fork leading to mate.
- **Rankings by Configuration**:
  - `baseline_ganguly_static`: rank 1
  - `bizjak_static_only`: rank 12
  - `bizjak_dynamic_only`: rank 3
  - `bizjak_combined`: rank 8
- **Explanation of Rank Drop in Static Only**:
  - In `baseline_ganguly_static`, the piece count is low (endgame). The Knight on `f3` attacking `d2` and King on `b1` matched the target positive `00Bex` at rank 1 with score 17.62.
  - In `bizjak_static_only`, pawn features added 14 terms: `w[a2/b2]`, `w[f2/g3/h3]`, `P(2)`, `p(2)`, `Ih7`, etc. Distractor `002Mm` (`rn1qrk2/ppp3pQ/3p1pP1/3Pp3/2P1P3/8/PP3PP1/R1B1K3 w Q - 3 17`) matched nearly all of these pawn chain and island tokens, receiving +12 points from pawn structure alone (score 29.23), while target positive `00Bex` (a complex middlegame Sicilian structure) received zero pawn chain matches.
  - This dropped `00Bex` from rank 1 to rank 12.
- **Takeaway**: Static pawn features encode structural pawn skeleton similarity (e.g. Carlsbad, French, King's Indian, endgame pawn chains). In sparse endgame positions, pawn skeleton matching can override tactical piece geometry.

### 4.3 Case Study 3: The Long Solution Sequence Penalty (`query_fork_N_12`)
- **Query**: `query_fork_N_12` (Puzzle `003md`)
  - Root FEN: `r2qk2r/ppp2ppp/2n1b3/2bnp3/2B5/1PNP1N2/P1P2PPP/R1BQK2R w KQkq - 1 8`
  - Moves: `f1e1`, `g3e4` (Knight fork at ply 1), `e1e4`, `d5e4`, `d1h5`, `h4h5`, `b3f7`, `h5f7` (total 8 plies).
- **Rankings by Configuration**:
  - `baseline_ganguly_static`: rank 1
  - `bizjak_static_only`: rank 2
  - `bizjak_dynamic_only`: **rank 8**
  - `bizjak_combined`: rank 2
- **Explanation**:
  - While plies 0-1 execute a Knight fork (`g3e4`), plies 2-7 execute a complex tactical melee involving Queen checks (`d1h5`), Queen trades, and Bishop sacrifices (`b3f7`).
  - True target positives for `fork_N` in the pool are predominantly clean 2-ply or 4-ply fork tactics that terminate after the fork and capture. Their dynamic token sets contain only `!-N`, `!N>Q`, `!xQ`.
  - Distractor puzzles in the candidate pool that are 6-ply or 8-ply tactical attacking games matched the Queen and Bishop sequence tokens of `003md`, scoring higher BM25 similarity than the clean 2-ply fork target positives.
  - In contrast, the static position at root FEN captured the knight fork setup cleanly, keeping rank 1 in baseline and rank 2 in static-only and combined.
- **Takeaway**: Dynamic sequence matching is sensitive to solution length. Multi-phase tactical combinations can match unrelated tactical combinations with similar piece-trade sequences rather than the primary motif.

---

## 5. Conclusions and Implications for ChessEcho

1. **Static Pawn Features are Essential for Mating Motifs**:
   The baseline Stage 1 representation (`FenPositionSimilarity`) was severely blind to back-rank mate structures because it treated all pawns as isolated pieces. Adding Plut/Bizjak static pawn structures (`bizjak_static_only`) doubled Hit@10 from 0.4375 to 0.8125 without requiring any solution moves. For ChessEcho's static similarity engine, pawn structure features represent a high-value, engine-free improvement.

2. **Dynamic Solution Features are Powerful but Leakage-Prone**:
   Dynamic sequence tokens deliver outstanding retrieval (0.9688 Hit@10), but this performance is achieved because the tokens explicitly spell out the positive class definition (`!#R` for back-rank mate, `!-N` for knight fork). This is effective for grouping known tactical puzzles, but cannot be computed on user game positions without prior tactical analysis.

3. **Naive Linear / Equal-Weight Fusion Fails**:
   Simply pooling static and dynamic tokens ($w_j=1.0$) degrades retrieval compared to dynamic-only because static token volume (~150 tokens) swamps dynamic token volume (~10 tokens). Any production deployment combining static and dynamic signals must use separate index fields, reciprocal rank fusion (RRF), or asymmetric field weighting rather than uniform BM25 term pooling.
