# Bizjak Tactical-Motif Evaluation (Stage 1 & Stage 2) Algorithm Specification

status: approved-before-scoring
date: 2026-10-25
issue: https://github.com/NathanZK/ChessEcho/issues/607
reference: Bizjak, M. (2020), Avtomatsko prepoznavanje podobnih šahovskih motivov (Master's thesis, University of Ljubljana)
baseline_commit: 8793d583e9ef04fd91b86be63c1baf558bb14759

## 1. Executive Summary & Purpose

This specification defines the exact feature vocabulary, token syntax, frequency accumulation rules, scoring variants, and evaluation harness for Stage 1 (static baseline) and Stage 2 (Bizjak static & dynamic extensions) of the ChessEcho tactical-motif retrieval experiment (#607).

In accordance with Acceptance Criterion 1 of #607, this document is committed to branch `exp/tactical-motif-v1` before any corpus scoring run begins and remains immutable throughout evaluation.

---

## 2. BM25 Fidelity & Primary-Source Reachability Analysis

### 2.1 Primary Source vs. Lucene Payload Scoring
Bizjak (§2.1.1), citing Ganguly et al. (SIGIR 2014, §3.2), utilizes Apache Lucene to index positions. In Lucene:
- Reachability terms are stored with arbitrary byte payloads encoding the Chebyshev distance weight $w = 1 - \frac{7d}{64}$.
- During search, Lucene applies payload weights via a `PayloadFunction` (e.g., `AveragePayloadFunction`) within a `PayloadScoreQuery` or `PayloadTermQuery`. This factor multiplies the term's query score:
  $$\text{Score}(q, d) = \sum_{t \in q \cap d} \text{BM25}(t, d) \cdot \text{payloadFactor}(t, d)$$
- In Lucene, term frequency ($tf$) is an integer count ($tf \in \{1, 2, \ldots\}$). Payloads act as post-retrieval term score multipliers, not continuous term frequencies, and a term only scores if it is present in both query and document ($t \in q \cap d$).

### 2.2 Frozen `FenPositionSimilarity` Baseline Representation (Commit `8793d58`)
ChessEcho's `FenPositionSimilarity` baseline is a clean-room, in-memory Kotlin implementation that does not use Lucene:
1. **Fractional Term Frequency**: It represents $w = 1 - \frac{7d}{64}$ as a fractional `Double` in `terms[term] = w` and feeds it into the standard BM25 equation:
   $$\frac{tf \cdot (k_1 + 1)}{tf + k_1 \cdot \left(1 - b + b \cdot \frac{L_d}{\text{avgdl}}\right)}$$
2. **Query Term Omission**: In `FenPositionSimilarity.rank`, query terms are extracted with `includeReachability = false`:
   ```kotlin
   val queryTerms = extractTerms(query.board, includeReachability = false).keys
   ```
   Because `queryTerms` contains no reachability terms (`reach|...`), reachability terms in candidate documents **never match** any query term.
3. **Implication**: Reachability terms in `FenPositionSimilarity` contribute solely to document length:
   $$L_d = \sum_{t \in d} tf(t, d)$$
   Their sole effect is penalizing candidates with many reachable empty squares in the BM25 denominator. They never contribute positive similarity score.

### 2.3 Explicit Approximation Declaration
Bizjak's original source code is unpublished, and CT-ART 6.0 is proprietary. Primary-source text does not disclose the exact Lucene payload query configuration or whether query reachability terms were searched.
Therefore:
- ChessEcho **does not claim faithful reproduction** of Lucene payload scoring.
- The frozen `FenPositionSimilarity` baseline (commit `8793d58`) is evaluated as-is to measure this frozen Ganguly-style baseline.
- `BizjakFeatureScorer` adheres to the same query reachability omission convention to isolate the contribution of new static pawn and dynamic solution features without introducing divergent reachability semantics.

---

## 3. Scoring Contract for All Four Configurations

### 3.1 Common Mathematical Definitions
- **Corpus & Pool Boundaries**: For query $q$, candidate pool $C$ consists of distinct positions by `canonicalFen`, strictly excluding $q$ (`candidate.canonicalFen != query.canonicalFen`), sorted by `canonicalFen` ascending. $N = |C|$.
- **Document Length**: $L_d = \sum_{t \in d} tf(t, d)$ (sum of all term frequencies in document $d$).
- **Average Document Length**: $\text{avgdl} = \frac{1}{N} \sum_{d \in C} L_d$.
- **Document Frequency**: $df(t) = \sum_{d \in C} \mathbf{1}[tf(t, d) > 0.0]$.
- **BM25 Formula**:
  $$\text{BM25}(q, d) = \sum_{t \in q} \mathbf{1}[t \in d] \cdot \ln\left(1 + \frac{N - df(t) + 0.5}{df(t) + 0.5}\right) \cdot \frac{tf(t, d) \cdot (k_1 + 1)}{tf(t, d) + k_1 \cdot \left(1 - b + b \cdot \frac{L_d}{\text{avgdl}}\right)}$$
  with $k_1 = 1.2$, $b = 0.75$.
- **Tie-Breaking**: Sorted by BM25 score descending, then by `canonicalFen` ascending (lexicographical comparison of the first 4 FEN fields).

### 3.2 Configuration 1: `baseline_ganguly_static` (Stage 1 Frozen Baseline)
- **Component**: `com.chessecho.similarity.FenPositionSimilarity` (commit `8793d58`).
- **Query Terms**: `extractTerms(query.board, includeReachability = false).keys` -> `Set<String>` (binary set: `piece|...`, `attack|...`, `defense|...`, `ray|...`). No reachability, no pawns, no dynamic tokens.
- **Document Terms**: `extractTerms(candidate.board, includeReachability = true)` -> `Map<String, Double>` (piece, attack, defense, ray have $tf=1.0$; reachability has fractional $tf = 1 - 7d/64$).
- **Status**: Evaluated unchanged from `8793d58`.

### 3.3 Configuration 2: `bizjak_static_only` (Stage 2 Static Ablation)
- **Component**: `BizjakFeatureScorer(ScoringVariant.STATIC_ONLY)`.
- **Query Terms**: Static positions (without reachability) + static pawns (`I{sq}`, `F{sq}`, `'F{sq}`, `l{sq}`, `S{file}`, `w[...]`, `P(n)`, `p(n)`). Query term set has $tf=1.0$ per distinct term.
- **Document Terms**: Static positions (with reachability fractional $tf$) + static pawns ($tf=1.0$ per term).
- **Document Length**: $L_d = L_{\text{static\_pos}} + L_{\text{static\_pawns}}$.

### 3.4 Configuration 3: `bizjak_dynamic_only` (Stage 2 Dynamic Ablation)
- **Component**: `BizjakFeatureScorer(ScoringVariant.DYNAMIC_ONLY)`.
- **Query Terms**: Dynamic general flags (`?px`, `?ox`, `?+`, `?=`, `?S`, `?#`, `?1/2`) + dynamic sequence tokens (`!-Piece`, `!xPiece`, `!-P1P2`, `!xP1P2`, `!Attacker>Attacked`, `!SPiece`, `!#Piece`, `!#P1P2`) extracted from query solution plies ($M_1, \ldots, M_{K-1}$).
- **Document Terms**: Dynamic tokens extracted from candidate solution plies ($M_1, \ldots, M_{K-1}$). If candidate is an unlabeled distractor without solution moves, dynamic terms map is empty ($L_d = 0$).
- **Document Length**: $L_d = \sum_{t \in \text{dyn}} tf(t, d)$.

### 3.5 Configuration 4: `bizjak_combined` (Stage 2 Combined)
- **Component**: `BizjakFeatureScorer(ScoringVariant.COMBINED)`.
- **Query Terms**: Union of query static terms (without reachability) + query dynamic terms.
- **Document Terms**: Union of document static terms (with reachability) + document dynamic terms.
- **Document Length**: $L_d = L_{\text{static}} + L_{\text{dynamic}}$.
- **Feature Group Weights**: Equal weighting ($w_j = 1.0$) in a single joint BM25 pass, matching thesis Table 3.1.

---

## 4. Evaluation Claims, Leakage, and Prohibited Conclusions

### 4.1 Retrieval Against Designated Positives in Partially Labeled Pools
- Candidate pools consist of designated target positives (confirmed by external oracle detectors) and $\approx 170$ background distractor puzzles from Lichess.
- **Distractors are NOT confirmed negatives**: They are unlabeled tactical puzzles. They may contain unannotated motifs or overlapping tactical ideas.
- **Metric Scope**: Hit@k and MRR measure retrieval of designated target positives within this frozen candidate mixture. They **do not measure** true semantic precision, false positive rate, or open-world generalization.

### 4.2 Solution-Path Leakage Declaration
- Dynamic features (`dynamic_general`, `dynamic_solution`) are derived directly from puzzle solution lines ($M_1, \ldots, M_{K-1}$).
- Ground-truth evaluation labels (`fork_N` at ply 1, `back_rank_mate` at terminal ply) are derived from those identical solution lines.
- Consequently, dynamic tokens (`!-N`, `!N>Q`, `!#R`, `!#Q`) encode the positive class definition.
- **Interpretation**: Superior retrieval by dynamic or combined variants demonstrates that tokenized solution lines enable BM25 to match similar solution mechanisms across diverse board positions. It **does not demonstrate** unguided chess understanding, tactical motif perception from static FEN alone, or strategic reasoning.

### 4.3 Prohibited Claims
The experimental report and any downstream documentation are strictly prohibited from claiming:
1. That this benchmark establishes general chess position similarity or strategic equivalence.
2. That dynamic retrieval results solve or satisfy arbitrary-FEN position retrieval (#91), which requires features computable without known solution lines.
3. That the model autonomously learned or discovered chess motifs without explicit solution supervision.
4. That candidate pool distractors are confirmed negative examples.

---

## 5. Feature Vocabularies & Serialization

### 5.1 Static Feature Group 1: Board Geometry & Reachability (`static_positions`)
- `piece|{color}|{type}|{square}` ($tf=1.0$)
- `reach|{color}|{type}|{target_square}` ($tf = 1 - \frac{7d}{64}$, candidate documents only)
- `attack|{sourceColor}|{sourceType}|{targetColor}|{targetType}|{targetSquare}` ($tf=1.0$)
- `defense|{sourceColor}|{sourceType}|{targetColor}|{targetType}|{targetSquare}` ($tf=1.0$)
- `ray|{sourceColor}|{sourceType}|{targetColor}|{targetType}|{targetSquare}` ($tf=1.0$)

### 5.2 Static Feature Group 2: Pawn Structures (`static_pawns`, Plut 2018 / Bizjak §2.1.2)
1. **Isolated Pawns**: `I{square}` (no friendly pawns on adjacent files $f-1, f+1$).
2. **Passed Pawns**: `F{square}` (no opposing pawns ahead on same or adjacent files).
3. **Protected Passed Pawns**: `'F{square}` (passed pawn protected by friendly pawn).
4. **Backward Pawns**: `l{square}` (behind friendly pawns on adjacent files, advance square attacked by opposing pawn and unsupported by friendly pawn).
5. **Doubled Pawns**: `S{file}` ($\ge 2$ friendly pawns on file; emitted once per doubled file).
6. **Pawn Chains**: `w[{sq1}/{sq2}/...]` (chains of $\ge 2$ diagonally supporting friendly pawns, ordered lowest to highest rank).
7. **Pawn Islands**: `P(n)` for White, `p(n)` for Black (count of contiguous file groups with friendly pawns; $n=0$ if no pawns).

### 5.3 Dynamic Feature Group 1: General Solution Flags (`dynamic_general`, Bizjak §2.2.1)
- `?px`: Player captures at any player ply in solution ($M_1, M_3, \ldots$).
- `?ox`: Opponent captures at any opponent ply in solution ($M_2, M_4, \ldots$).
- `?+`: Check delivered at any ply in solution.
- `?=`: Pawn promotion occurs at any ply in solution.
- `?S`: Piece sacrificed (player moves piece subsequently captured by opponent where sacrificed piece value > any captured piece value: Q=9, R=5, B=3, N=3, P=1).
- `?#`: Checkmate delivered at final ply.
- `?1/2`: Draw reached at final ply.

### 5.4 Dynamic Feature Group 2: Detailed Sequence Tokens (`dynamic_solution`, Bizjak §2.2.2)
- `!-Piece`: Piece type moved at ply $i$ (`K`, `Q`, `R`, `B`, `N`, `P`).
- `!xPiece`: Piece type captured at ply $i$.
- `!-P1P2`: Consecutive moved pieces at plies $i, i+1$ (emitted for solution plies $\ge 2$).
- `!xP1P2`: Consecutive captures at plies $i, i+1$ (emitted when both plies involve a capture).
- `!Attacker>Attacked`: New direct attack created by move against opponent piece. If multiple, sorted alphabetically.
- `!SPiece`: Specific piece type sacrificed (`!SQ`, `!SR`).
- `!#Piece`: Piece type delivering checkmate.
- `!#P1P2`: Last two plies delivering checkmate.

---

## 6. Evaluation Oracle & Detector Validity

### 6.1 Architectural Separation
`fork_N` and `back_rank_mate` are external evaluation labels produced by independent Python oracle scripts (`scripts/tactical_motif_detectors.py`). They are strictly never encoded as document features, query tokens, or index terms.

### 6.2 Detector `fork_N` Definition & Replay Protocol
- Evaluated **after the first solution move** ($M_1$):
  1. The sequence must have $\ge 2$ plies ($M_0$ setup + $M_1$ player move).
  2. $M_0$ and $M_1$ must be legally replayable from the puzzle FEN.
  3. The piece moved in $M_1$ must be a Knight (`chess.KNIGHT`).
  4. From its destination square in $P_1$, the Knight simultaneously attacks $\ge 2$ opponent pieces, each valued at least as much as a pawn (P, N, B, R, Q, K).
  5. Any malformed FEN, illegal move, or unparseable sequence is handled explicitly and returns `False`.

### 6.3 Detector `back_rank_mate` Definition & Replay Protocol
- Evaluated **at the terminal solution ply**:
  1. All moves in the solution must be legally replayable.
  2. Final position must be checkmate (`board.is_checkmate() == True`).
  3. Mating move delivered by Rook (`chess.ROOK`) or Queen (`chess.QUEEN`).
  4. Mating piece lands on the mated king's home back rank (rank 8 / index 7 for Black king; rank 1 / index 0 for White king).
  5. Any malformed FEN, illegal move, or unparseable sequence returns `False`.

### 6.4 Pre-Validation Requirement
Before candidate pool labeling, both detectors must pass verification against $\ge 5$ verified positive and $\ge 5$ verified negative test cases stored in `src/test/resources/tactical-motif-detector-test-cases.json` and tested in `scripts/tests/test_tactical_motif_detectors.py` and `scripts/verify_tactical_motif_detectors.py`.
