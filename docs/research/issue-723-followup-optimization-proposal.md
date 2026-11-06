# Research Proposal: ChessProbe V1 Dynamic Retrieval Optimizations (Follow-Up to Issue #723)

**Document Type:** Formal Research & Experimentation Proposal  
**Status:** PROPOSED / PRE-IMPLEMENTATION (Not Completed Experiments)  
**Parent / Baseline Issue:** GitHub Issue #723 (`EXP-ISSUE-723-DEPTH8-DYNAMIC-SIMILARITY`, commit `a10bc51`)  
**Target Repository:** `NathanZK/ChessEcho`  
**Date:** 2026-11-06  

---

## 1. Context & Objective

In GitHub Issue #723, we established through extensive empirical benchmarking across 10,000 positions (Corpus A) and 250 probe queries ($Q_{250}$) that shallower Stockfish dynamic tokens (Depth 8) dramatically outperform all static representations from Issue #722 (Recall@10 at $N=200$: **18.48% for C6** and **18.20% for C3** vs **8.40% for Combined Closure** and **5.76% for Attack Closure**), with **0.0% candidate pool exhaustion**. Furthermore, MultiPV 3 was demonstrated to be the cost-effective sweet spot on the quality-cost frontier (37.9 ms/pos vs 62.0 ms/pos for MPV 5).

**Objective:** Investigate targeted follow-up optimizations for ChessProbe V1's dynamic similarity retrieval system.

> [!IMPORTANT]
> **System Boundary Invariant:** Structural similarity and dynamic similarity are strictly separate systems. This investigation addresses only the dynamic retrieval pipeline and does not revisit or propose merging them.

---

## 2. Agreed Provisional V1 Direction

Based on the completed empirical findings of Issue #723:
1. **Dynamic Corpus Generation & Clustering:** The agreed provisional architecture for ChessProbe V1 dynamic similarity is **Depth 8 MultiPV 3** evaluated symmetrically across the corpus.
2. **Clustering Pipeline:** Precomputed Depth-8 MultiPV-3 dynamic tokens will feed directly into candidate generation, followed by relaxed Mutual Nearest Neighbors (relaxed MNN) graph construction and Leiden community detection.
3. **Role of Asymmetric Retrieval:** The observed improvement from Depth-16 queries evaluated against Depth-8 candidate indices (Recall@10 reaching 32.68% at $N=200$ and 63.36% at $N=1000$) remains a **diagnostic finding and a potential user-facing query mode**. It is **explicitly not part of the agreed V1 offline clustering architecture**, which operates symmetrically over the precomputed corpus.

---

## 3. Follow-Up Optimization Areas

### Area 1: MultiPV Score-Based Post-Hoc Filtering (Tier 1: 0 ms Engine Compute)

- **Audit Findings:** The existing `engine_evaluation_cache_d8_mpv3.json` and `engine_evaluation_cache_d8_mpv5.json` caches already store per-rank evaluation scores (`scoreCp`), termination reasons (`terminationReason`), and isolated per-rank tokens (`tokensByRank: Map<String, Map<String, Double>>`).
- **Score Properties:**
  - Missing scores: **0 out of 10,000 positions** (100% complete).
  - Score monotonicity: In 100% of positions, $\text{score}(R_1) \ge \text{score}(R_2) \ge \dots \ge \text{score}(R_K)$.
  - Orientation: Scores are consistently normalized to the **root player's perspective** per the UCI protocol.
- **Evaluation Loss Definition:**
  $$\Delta(r) = \text{score}(R_1) - \text{score}(R_r) \ge 0$$
- **Unconditional Rank 1 Preservation:** Rank 1 is preserved unconditionally ($\Delta(1) = 0 \le \theta$).
- **Thresholds to Investigate:** $\theta \in \{30, 50, 70, 100\text{ cp}\}$ ($0.3, 0.5, 0.7, 1.0$ pawns), evaluated alongside the unfiltered baseline ($\theta = \infty$).
- **Mate Score Policy:** Mate scores are represented as $\pm(30,000 - \text{moves})$. Secondary mating lines within threshold are preserved; blunders into mate are discarded.
- **Core Investigation Question:** Does filtering MPV5 ranks 2–5 at $\theta = 0.5$ pawns (yielding an average of **3.20 surviving ranks**) achieve a superior quality-cost trade-off compared to unfiltered MPV3 (mean **2.99 ranks**, 18.20% R@10)?

### Area 2: Depth 10 MultiPV 3 (Tier 2: Separately Gated Future Experiment)

- **Design:** Compare Depth 10 MultiPV 3 directly against the established Depth 8 MPV3 baseline using the identical 250 probe queries ($Q_{250}$) and fixed depth-16 ground truth references (`engine_evaluation_cache_d16_mpv1.json`, strictly immutable).
- **Evaluation Modes:**
  - Symmetric D10-query to D10-candidate retrieval.
  - Asymmetric D16-query to D10-candidate retrieval.
- **Timing Pilot & Feasibility Gate:**
  A 50-position stratified timing pilot (15 early-game, 25 middle-game, 10 late-game) must precede any batch generation.
- **Corrected Timing-Gate Arithmetic:**
  $$\text{Batch Runtime (minutes)} = \frac{10,000 \times T_{\text{pos}} (\text{ms})}{W \times 60,000 \text{ ms/min}} \le 60.0 \text{ minutes}$$
  For $W = 6$ worker cores, the maximum allowable mean per-position latency across the pool is:
  $$T_{\text{pos}} \le \frac{60.0 \times 6 \times 60,000}{10,000} = \mathbf{2,160\text{ ms / position}}$$
  Across the 50 pilot positions, the sum of latencies must satisfy:
  $$\sum_{p \in \text{Pilot}} T_{\text{pos}}(p) \le 50 \times 2,160\text{ ms} = \mathbf{108,000\text{ ms}} \quad (108.0\text{ seconds})$$
  *(Note: Conservative expected D10 latency is 80–110 ms/pos, yielding a projected batch time of ~2.5–3.5 minutes on 6 cores, well within the 60-minute ceiling).*

### Area 3: Rank-Aware Token Weighting (Tier 1: Post-Hoc)

- **Rationale:** Sub-optimal lines (Ranks 2–5) currently receive equal term-frequency weighting to Rank 1 under Model A, which may allow noisy alternative lines to drown out critical Rank 1 tactical motifs.
- **Minimal Weighting Alternatives to Benchmark:**
  1. **Harmonic Decay:** $w(r) = 1 / r$ (Weights: $1.0, 0.50, 0.33, 0.25, 0.20$).
  2. **Linear Decay:** $w(r) = (K - r + 1) / K$.
  3. **Continuous Score-Softmax:** $w(r) = \exp(-\Delta(r) / \tau)$ with $\tau = 50\text{ cp}$ ($0.5$ pawn).
- **Execution:** Evaluated post-hoc on existing cached PV lines without engine execution.

### Area 4: Query-Side Optimization & Descriptive Framing (Tier 1: Post-Hoc)

- **Descriptive Measurement:** In Issue #723, evaluating an authoritative Depth-16 query against a Depth-8 MPV5 candidate index increased recovered Top-10 targets from 462 / 2,500 (18.48%) to 899 / 2,500 (32.68%) at budget $N=200$.
- **Calculation Formulation:**
  $$\frac{899 - 462}{899} = \frac{437}{899} = \mathbf{48.61\%}$$
  This calculation descriptively measures the proportion of dynamic targets recoverable by the D8 candidate index that were missed in symmetric search when the query was analyzed at Depth 8 rather than Depth 16.
- **Epistemic Qualification:** This measurement is **descriptive of target overlap under tested conditions**, not a proven causal attribution of all retrieval error. It demonstrates that the shallow candidate index has higher latent retrieval capacity than symmetric querying exposes.

---

## 4. Minimal Proposed Experiment Matrix

| Condition ID | Area | Engine Config | Aggregation / Filtering Scheme | Compute Mode |
|---|---|---|---|---|
| **E1** | Baseline | D8 MPV3 | Model A Unfiltered (Baseline) | Post-hoc (0 ms) |
| **E2** | Baseline | D8 MPV5 | Model A Unfiltered (Baseline) | Post-hoc (0 ms) |
| **E3a–d** | Area 1 | D8 MPV3 | Score Filtered ($\theta \in \{0.3, 0.5, 0.7, 1.0\}$ pawns) | Post-hoc (0 ms) |
| **E4a–d** | Area 1 | D8 MPV5 | Score Filtered ($\theta \in \{0.3, 0.5, 0.7, 1.0\}$ pawns) | Post-hoc (0 ms) |
| **E5** | Area 3 | D8 MPV3 | Harmonic Rank Weighting ($w(r) = 1/r$) | Post-hoc (0 ms) |
| **E6** | Area 3 | D8 MPV5 | Score-Softmax Weighting ($w(r) = \exp(-\Delta/50)$) | Post-hoc (0 ms) |
| **E7** | Area 2 | D10 MPV3 | Symmetric D10 $\to$ D10 (Model A) | New Batch (Timing Gated) |
| **E8** | Area 2 | D10 MPV3 | Asymmetric D16 $\to$ D10 (Model A) | Evaluated on D10 Cache |
| **E9** | Area 4 | D8 MPV3 | Asymmetric D10 Query $\to$ D8 MPV3 Index | Evaluated on D8 Cache |
| **E10** | Area 4 | Filtered D8 | Asymmetric D16 Query $\to$ Best Filtered D8 Index | Post-hoc (0 ms) |

---

## 5. Metrics & Decision Criteria

1. **Recall@10 at $N=200$:** Primary quality metric (Threshold: $\ge 18.0\%$ symmetric, $\ge 32.0\%$ asymmetric).
2. **Candidate Pool Exhaustion Rate:** Must remain $\le 0.5\%$ across all budgets.
3. **Index Footprint:** Target $\ge 20\%$ reduction in non-zero token postings under score filtering.
4. **Decision Boundary (Filtered MPV5 vs Unfiltered MPV3):** Adopt Filtered MPV5 if and only if Recall@10 exceeds Unfiltered MPV3 by $\ge +0.5$ pp at comparable rank count (~3.0 ranks).
5. **Decision Boundary (Depth 10):** Adopt Depth 10 candidate generation if and only if it passes the 60-minute feasibility gate and improves Recall@10 by $\ge +2.0$ pp over Depth 8 MPV3.
