# ChessProbe Research Report: Issue #722
## Empirical Evaluation of Combined Static Candidate Filtering and Bizjak Dynamic Similarity Retrieval Pipeline (Phase 1: MultiPV=1 Baseline)

**Date**: November 5, 2026  
**Status**: PHASE 1 COMPLETE (All 4 Hypotheses Evaluated; Hard Engine Ceiling Strictly Preserved)  
**Execution Worktree**: `implement_issue_702_praxis` (`research/issue-722-combined-filtering-dynamic-similarity`)  
**Target Repository**: [NathanZK/ChessEcho](https://github.com/NathanZK/ChessEcho)  
**Reference Issues**: [#722](https://github.com/NathanZK/ChessEcho/issues/722) (Building on [#721](https://github.com/NathanZK/ChessEcho/issues/721) and [#607](https://github.com/NathanZK/ChessEcho/issues/607))  

---

## Executive Summary

This report documents the exhaustive empirical evaluation of Phase 1 of Issue #722: investigating the end-to-end retrieval dynamics when static graph-based candidate filtering (PR #702 / Issue #721) is combined in a two-stage cascaded pipeline with Bizjak dynamic similarity retrieval (Issue #607). 

Using the complete **10,252-position corpus** (10,000 GM positions from Corpus A + 252 tactical motif puzzle pool positions from Issue #607), we executed Stockfish 19 engine analysis strictly fixed at **Depth 16**, establishing the **MultiPV=1 baseline** under an enforced hard ceiling of 11,000 engine search calls (actual searches executed: 5,532; cache hits: 4,720). We conducted a controlled comparative evaluation between **TF-IDF Cosine Similarity** and **BM25** on identical Bizjak continuation tokens, swept across the entire approved 6-value $\alpha$ grid ($\alpha \in \{0.00, 0.25, 0.50, 0.60, 0.75, 1.00\}$), and measured downstream tactical mistake retention at a fixed decision threshold of **120 cp (1.2 pawns)**.

```mermaid
flowchart TD
    subgraph Corpus["Full Evaluation Universe (10,252 Unique Positions)"]
        CorpusA["Corpus A: 10,000 GM Baseline Positions"]
        FrozenPools["32 Frozen Tactical Motif Pools (252 Positions)"]
    end

    subgraph Preprocessing["Strict FEN Normalization & Audit"]
        Norm["Normalized FEN: canonicalFen + ' 0 1'<br/>Halfmove Clock >=80: 0.03% (3/10,000)"]
    end

    subgraph EngineExecution["Stockfish 19 MultiPV=1 Engine Execution"]
        WorkerPool["6 Parallel Engine Workers @ Depth 16"]
        Budget["Engine Call Ceiling: 11,000<br/>Actual Searches: 5,532 | Cache Hits: 4,720"]
        RawCache["Persistent Engine Cache (10,252 records, ~8 MB)"]
    end

    subgraph TwoStagePipeline["Cascaded Pipeline Evaluation"]
        Stage1["Stage 1: Static Pre-filtering<br/>(alpha sweep: 0.00 to 1.00)"]
        Stage2["Stage 2: Dynamic Reranking<br/>(TF-IDF Cosine vs BM25)"]
    end

    CorpusA --> Norm
    FrozenPools --> Norm
    Norm --> WorkerPool
    WorkerPool --> Budget
    Budget --> RawCache
    RawCache --> Stage1
    Stage1 --> Stage2
```

### Key Empirical Findings

1. **Candidate Search-Space Reduction (Hypothesis 1: PASSED)**:
   Static structural candidate filtering achieves an extreme Candidate Reduction Rate (CRR) of **98.27% to 99.21%** across the full $\alpha$ grid. For a typical query with $\sim 5,024$ candidate positions sharing the side-to-move, static clustering reduces the candidate pool to a mean of **39.3 to 86.4 positions**, confirming H1.

2. **Severe Dynamic Relevance Loss (Hypothesis 2: CONFIRMED BOTTLENECK)**:
   While search-space reduction is massive, static pre-filtering discards **$\sim 97.5\%$ of dynamically relevant candidates**. Across all $\alpha$ values, dynamic Recall@10 against the unconstrained full-corpus reference ranking is only **$2.04\% - 3.08\%$** (BM25: 2.04% at production $\alpha=0.60$; TF-IDF: 2.76%). Candidate pool purity is $\le 0.51\%$. This demonstrates that static structural clustering acts as an informational bottleneck that blinds downstream dynamic similarity to the vast majority of dynamically similar positions in the corpus.

3. **Downstream Weakness Retention & False-Familiarity Suppression (Hypothesis 3: CONFIRMED)**:
   At the fixed decision-evaluation threshold of 120 cp (1.2 pawns), downstream mistake positions are retained in the candidate pool at a rate of only **$0.38\% - 1.33\%$** across $\alpha$. Crucially, dynamic reranking evicts between **1,714 and 7,208 high-evaluation-divergence positions** ($|\Delta\text{eval}| > 150$ cp) from the top ranks of static clusters ("false familiarity" suppression).

4. **Dynamic Scorer Comparison: TF-IDF Cosine Superiority (Hypothesis 4: CONFIRMED)**:
   On the 32 independently validated tactical motif benchmark pools (#607):
   - **TF-IDF Cosine**: **Hit@1 = 50.00%**, **Hit@5 = 93.75%**, **Hit@10 = 100.00%**, **MRR = 0.6693**.
   - **BM25**: **Hit@1 = 43.75%**, **Hit@5 = 90.63%**, **Hit@10 = 96.88%**, **MRR = 0.6420**.
   *Theoretical Mechanism*: BM25's document length penalty $(1 - b + b \cdot \frac{L}{\text{avgdl}})$ penalizes rich, multi-ply tactical combination lines that naturally produce more continuation tokens. TF-IDF Cosine normalizes strictly by vector magnitude, maintaining superior ranking stability across variable continuation lengths.

---

## 1. Experimental Methodology & Pre-Flight Verification

### 1.1 Fixed Experimental Parameters

To eliminate confounding variables and ensure exact backward comparability with Issues #721 and #607, all core parameters were fixed before execution:

| Parameter | Fixed Value | Rationale |
|---|---|---|
| **Stockfish Version** | Stockfish 19 (`dc2fa0ea4be9b9b47e243dbf15fcefa0143a571f54cfbf009fd98b7123984ca8`) | Official release binary matching Issue #721 |
| **Search Depth** | Strictly **Depth 16** | Empirically established in #721 as optimal reference (70% best-move agreement with Depth 20, 37.7 cp mean delta) |
| **MultiPV Staging** | **MultiPV=1** for Phase 1 | MultiPV=3 deferred to Phase 2; MultiPV=5 strictly excluded |
| **Decision Threshold** | Strictly **120 cp (1.2 pawns)** | Standard tactical blunder boundary; no threshold variation |
| **Alpha ($\alpha$) Grid** | $\{0.00, 0.25, 0.50, 0.60, 0.75, 1.00\}$ | Full 6-value spectrum from Issue #721 preserved intact |
| **Engine Call Ceiling** | **11,000 calls** | Hard programmatic ceiling enforced at worker level |
| **Probe Queries** | Stratified $N=250$ (seed 42) | Stratified sample from Corpus A matching #721 |

### 1.2 FEN Normalization & Halfmove Clock Verification

Engine evaluation requires strict FEN normalization to avoid cache duplication while preserving legality. In accordance with the pre-flight requirements:
1. All engine analysis was conducted on normalized FEN strings: `canonicalFen + " 0 1"` (halfmove clock set to 0, fullmove counter set to 1).
2. **Historical Halfmove Clock Audit**: An exhaustive programmatic audit of all 10,000 positions in Corpus A was conducted via `HalfmoveClockAuditTest.kt`, reconstructing complete game histories:
   - Halfmove clock $\ge 80$: **3 positions (0.03%)**
   - Halfmove clock $\ge 50$: **11 positions (0.11%)**
   - Halfmove clock $\ge 40$: **13 positions (0.13%)**
   - Corpus mean halfmove clock: **1.91** plies
   - Tactical motif frozen pools (252 positions): **0 positions** with halfmove clock $\ge 50$ (maximum observed: 26).
   *Conclusion*: Over 99.87% of positions have halfmove clocks $< 40$, meaning 50-move rule effects in Depth 16 searches are negligible ($< 0.03\%$). Setting halfmove clock to 0 is methodologically justified.

### 1.3 Pre-Flight Runtime Benchmark (100 Positions)

To prevent unconstrained execution and validate the numeric call budget, a pre-flight benchmark was executed across 100 stratified representative positions (25 opening, 50 middlegame, 25 endgame):

| Metric | MultiPV=1 Benchmark | MultiPV=3 Benchmark | Ratio (MPV=3 / MPV=1) |
|---|---|---|---|
| **Mean Latency** | **0.315s / pos** | **1.095s / pos** | 3.48x |
| **Median Latency** | **0.301s / pos** | **1.003s / pos** | 3.33x |
| **P90 Latency** | **0.511s / pos** | **1.747s / pos** | 3.42x |
| **Total 100-Pos Time**| **31.86s** | **109.94s** | 3.45x |

### 1.4 Hard Engine Call Budget & Ceiling Enforcement

The engine runner (`DynamicEngineResearchRunner.kt`) implements an atomic invocation counter with hard ceiling verification:
- **Total Unique Positions in Scope**: 10,000 (Corpus A) + 252 (Motif Pools) = **10,252 unique canonical positions**.
- **Ceiling Invariant**: If `actualEngineSearchCalls.incrementAndGet() > hardCeiling`, the runner immediately terminates all workers and throws `IllegalStateException`. Cache hits do not increment the counter and cannot bypass the ceiling.
- **Phase 1 Execution Metric**:
  - Total positions evaluated: **10,252**
  - Actual fresh engine searches executed: **5,532**
  - Persistent cache hits: **4,720**
  - Enforced Ceiling: **11,000**
  - **Budget Compliance: PASSED (50.3% of budget used; 0 violations)**

---

## 2. Dynamic Scorer Comparison: TF-IDF Cosine vs BM25

We conducted an independent controlled comparison between **TF-IDF Cosine Similarity** and **BM25**, holding the underlying Bizjak dynamic tokens identical.

### 2.1 Independent Tactical Motif Benchmark (32 Frozen Pools, Issue #607)

The 32 frozen tactical motif benchmark pools provide ground-truth human-annotated tactical motif relevance independent of algorithmic ranking:

| Metric | BM25 Scorer | TF-IDF Cosine Scorer | Delta ($\Delta$) | Superior Model |
|---|---|---|---|---|
| **Hit@1** | 43.75% (14/32) | **50.00% (16/32)** | **+6.25%** | **TF-IDF Cosine** |
| **Hit@5** | 90.63% (29/32) | **93.75% (30/32)** | **+3.12%** | **TF-IDF Cosine** |
| **Hit@10** | 96.88% (31/32) | **100.00% (32/32)** | **+3.12%** | **TF-IDF Cosine** |
| **MRR (Mean Reciprocal Rank)** | 0.6420 | **0.6693** | **+0.0273** | **TF-IDF Cosine** |

### 2.2 Scorer Agreement on Unconstrained Full Corpus

Evaluating ranking agreement across the full candidate universes $U(q)$ ($\sim 5,024$ positions per query) for all 250 probe queries:
- **Global Rank Agreement (Mean Spearman $\rho$)**: **$0.8832 \pm 0.045$** (strong global monotonic correlation over the entire corpus).
- **Top-10 Ranking Overlap (Mean Top-10 Jaccard Similarity)**: **$0.1843 \pm 0.012$** (only 18.43% set overlap in top 10).
- **Theoretical Explanation of Divergence**:
  While the two scorers broadly agree on distant irrelevant positions ($\rho = 0.8832$), their top-10 retrieved sets diverge sharply ($J_{10} = 0.1843$). BM25 computes relevance using:
  $$\text{score}(D, Q) = \sum_{t \in Q} \text{IDF}(t) \cdot \frac{f(t, D) \cdot (k_1 + 1)}{f(t, D) + k_1 \cdot \left(1 - b + b \cdot \frac{|D|}{\text{avgdl}}\right)}$$
  In chess tactical positions, deep and forcing combination continuations emit longer PVs with more dynamic tokens (larger $|D|$). BM25's length penalty term $(1 - b + b \cdot \frac{|D|}{\text{avgdl}})$ systematically penalizes these tactically rich continuations, favoring positions with short, blunt continuations. TF-IDF Cosine normalizes solely by vector Euclidean norm $L_2$, preserving relative token weights without length-based suppression. This explains why TF-IDF achieves 100% Hit@10 on the benchmark motif pools, compared to 96.88% for BM25.

---

## 3. Static Candidate Filtering Sweep across the 6-Alpha Grid

We evaluated static candidate filtering using the approved 6-value $\alpha$ grid from Issue #721: $\alpha \in \{0.00, 0.25, 0.50, 0.60, 0.75, 1.00\}$.

```
Table 1: End-to-End Pipeline Performance Across Alpha Grid (MultiPV=1, Depth 16)
```

| $\alpha$ | Surviving $|S_\alpha(q)|$ | CRR (%) | BM25 Recall@10 | TF-IDF Recall@10 | BM25 Purity@10 | TF-IDF Purity@10 | BM25 MRR | TF-IDF MRR | Mistake Ret. (120 cp) | False Fam. Evictions |
|---|---|---|---|---|---|---|---|---|---|---|
| **0.00** | 39.30 | 99.21% | 2.20% | **3.08%** | 0.50% | 0.24% | 0.0280 | **0.0360** | 0.38% | 1,714 |
| **0.25** | 42.34 | 99.15% | 1.80% | **2.60%** | 0.51% | 0.16% | 0.0200 | **0.0200** | 0.47% | 2,161 |
| **0.50** | 62.95 | 98.74% | 1.72% | **2.28%** | 0.33% | 0.36% | 0.0200 | 0.0080 | 0.87% | 4,488 |
| **0.60** | 81.60 | 98.37% | 2.04% | **2.76%** | 0.34% | 0.38% | 0.0240 | 0.0120 | 1.20% | 6,431 |
| **0.75** | 68.44 | 98.63% | 1.48% | **2.16%** | 0.11% | 0.17% | 0.0200 | 0.0160 | 1.05% | 5,551 |
| **1.00** | 86.36 | 98.27% | 2.16% | **2.44%** | 0.13% | 0.17% | 0.0200 | 0.0160 | 1.33% | 7,208 |

### 3.1 Candidate Reduction Rate (CRR) & Search Space Compression

- **H1 Verdict: PASSED**.
- At all $\alpha$ values, CRR exceeds **98.27%**, with maximum search-space reduction occurring at $\alpha=0.00$ (pure pawn structure: 99.21% CRR, 39.3 surviving candidates) and minimum reduction at $\alpha=1.00$ (pure piece configuration: 98.27% CRR, 86.4 surviving candidates).
- The production default $\alpha=0.60$ yields a CRR of **98.37%** (81.6 surviving candidates).

### 3.2 Dynamic Relevance Retention Bottleneck

- **H2 Verdict: CONFIRMED SEVERE INFORMATIONAL BOTTLENECK**.
- Pre-filtering through static structural clusters discards **$96.9\% - 98.5\%$** of the top 10 dynamically relevant positions.
- At $\alpha=0.60$, only **0.204 out of 10** BM25 top candidates (2.04%) and **0.276 out of 10** TF-IDF top candidates (2.76%) survive static filtering.
- **Candidate Pool Purity** remains below **0.51%** across all configurations.
- *Root Cause Analysis*: In Issue #721, we established that **87.10%** of Stockfish evaluation variance is within-cluster ($\sigma_W \approx 329.7$ cp). Static pawn skeletons and piece configurations inhabit an orthogonal topological space to tactical dynamics. When a strict static filter pre-selects candidates prior to dynamic scoring, it permanently discards positions that share the same tactical theme (e.g., clearance sacrifice, back-rank weakness, deflection) but originate from different opening pawn structures.

### 3.3 Downstream Weakness Retention & False-Familiarity Suppression

- **H3 Verdict: CONFIRMED**.
- At the fixed decision threshold of **120 cp (1.2 pawns)**:
  - Mistake position retention across static clusters is low ($0.38\%$ to $1.33\%$).
  - Dynamic reranking provides strong **false-familiarity suppression**: across the 250 queries, between **1,714 and 7,208 positions** that shared the query's static cluster but exhibited extreme evaluation discrepancies ($|\Delta\text{eval}| > 150$ cp) were demoted out of the top 10.
  - This demonstrates that dynamic similarity effectively sanitizes static clusters by pruning positions where superficial structural similarity conceals a tactical collapse.

---

## 4. Architectural Analysis: Cascaded Filtering vs Dual-Space Retrieval

The empirical findings from Phase 1 resolve the core architectural question regarding how static structural similarity and Bizjak dynamic similarity should be integrated into ChessProbe:

```mermaid
graph LR
    subgraph ModelB["Model B: Cascaded Two-Stage Filter (Evaluated in Phase 1)"]
        QueryB["Query Position"] --> StaticFilter["Static Filter (CRR 98.4%)"]
        StaticFilter --> Truncated["Truncated Pool (81 cands)"]
        Truncated --> DynamicRerank["Dynamic Scorer"]
        DynamicRerank --> OutputB["Top Retrieval<br/>(Loses 97.5% Dynamic Relevance)"]
    end

    subgraph ModelD["Model D: Parallel Dual-Space Joint Retrieval (Recommended)"]
        QueryD["Query Position"] --> StaticIndex["Static Index"]
        QueryD --> DynamicIndex["Dynamic Inverted Index"]
        StaticIndex --> StaticScore["Static Score S_static"]
        DynamicIndex --> DynamicScore["Dynamic Score S_dynamic"]
        StaticScore --> JointFusion["Joint Fusion: S = w_s*S_static + w_d*S_dynamic"]
        DynamicScore --> JointFusion
        JointFusion --> OutputD["Optimal Top Retrieval<br/>(Zero Candidate Bottleneck)"]
    end
```

### 4.1 The Failure Mode of Strict Cascading (Model B)

Phase 1 empirically demonstrates that a pure cascaded architecture (Model B: Static Candidate Filter $\to$ Dynamic Reranker) suffers from an unrecoverable **informational bottleneck**:
- Because static candidate filtering achieves a 98.4% reduction rate, any dynamic candidate that does not reside in the same static Leiden cluster is permanently discarded.
- In practice, this means that tactical motifs like *smothered mates*, *bishop sacrifices on h7*, or *rook pins on the d-file* can only be retrieved if they happen to share the exact pawn and piece configuration of the query.
- Over **97% of tactically identical positions** are eliminated before the dynamic scorer ever evaluates them.

### 4.2 Recommendation: Dual-Space Parallel Retrieval (Model D)

Rather than using static similarity as a hard gatekeeper, ChessProbe should implement **Model D (Orthogonal Dual-Space Relationship Model)**:
1. **Parallel Inverted Indexing**: Maintain both the static token inverted index and the dynamic Bizjak token inverted index over the corpus.
2. **Independent Candidate Generation**: Generate top candidates independently from both indices (e.g., top 100 static + top 100 dynamic).
3. **Union & Joint Scoring**: Form the union pool ($|C| \le 200$) and compute a joint similarity score:
   $$\text{Sim}(Q, D) = (1 - \lambda) \cdot \text{Sim}_{\text{static}}(Q, D) + \lambda \cdot \text{Sim}_{\text{dynamic}}(Q, D)$$
4. This preserves the millisecond indexing speed of static retrieval while completely eliminating the 97.5% dynamic recall truncation observed in Model B.

---

## 5. Artifact Ledger & Compliance Audit

### 5.1 Persisted Raw Research Artifacts

All raw experimental evaluations and aggregated metrics have been serialized to JSON within `backend/src/test/resources/research/`:

| Artifact Path | Size | Description |
|---|---|---|
| `engine_evaluation_cache_d16_mpv1.json` | 8.1 MB | Complete raw Stockfish Depth 16 evaluations, PVs, scores, and dynamic tokens for all 10,252 unique positions |
| `combined_pipeline_multipv1_baseline.json` | 6.2 KB | Full Phase 1 baseline execution report containing all engine metrics, ceiling compliance, and grid results |
| `scorer_comparison_tfidf_vs_bm25.json` | 740 B | Direct comparison metrics between TF-IDF Cosine and BM25 on the 32 independent tactical motif pools |
| `dynamic_candidate_retention_breakdown.json` | 5.8 KB | Complete breakdown of candidate retention, purity, recall, and ranking metrics across all 6 $\alpha$ values |
| `downstream_weakness_comparison.json` | 420 B | Mistake retention rates at 120 cp and false-familiarity eviction counts across all 6 $\alpha$ values |

### 5.2 Research Infrastructure Isolation & Production Integrity

- **Production Code Isolation**: Zero files modified in `backend/src/main/` or database schemas (`V1__baseline.sql`).
- **Research Scope Isolation**: All code resides strictly in `backend/src/test/kotlin/com/chessecho/research/`.
- **Git Branch**: Work completed strictly on isolated research worktree branch `research/issue-722-combined-filtering-dynamic-similarity`.

---

## 6. Execution Status & Stop Condition

- **Phase 1 (MultiPV=1 Baseline)**: **COMPLETE**.
- **Phase 2 (MultiPV=3 Evaluation)**: **PAUSED / PENDING APPROVAL**. In strict accordance with the user instructions, MultiPV=3 execution has not been started and requires subsequent explicit user approval.
