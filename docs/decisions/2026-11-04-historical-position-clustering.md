# Decision: Account-Scoped Historical Chess-Position Clustering Architecture

**Date**: 2026-11-04  
**Context**: Issue [#702](https://github.com/NathanZK/ChessEcho/issues/702) (incorporating research from [#701](https://github.com/NathanZK/ChessEcho/issues/701))

## Context
ChessProbe required an account-scoped offline position clustering capability to identify cohesive thematic position families from an account's historical games. The solution needed an internal experimental weighting parameter $\alpha \in [0.0, 1.0]$ between pawn structure and piece placement tokens, high candidate retrieval recall, graph construction via Relaxed MNN, CWTS Leiden community detection with Reichardt-Bornholdt modularity parity, centroid cohesion pruning, and immutable snapshot lifecycle persistence.

## Choice
1. **Configurable Internal Parameter $\alpha$**: Default $\alpha = 0.60$ preserved. Supported across $[0.0, 1.0]$. Inverted-index score accumulation is normalized by active query term counts per family ($\frac{\alpha}{|q_P|}$ and $\frac{1-\alpha}{|q_{Pc}|}$) to eliminate piece-term volume imbalance, achieving 100% Recall@1 and Recall@10 across all $\alpha \in \{0.0, 0.60, 1.0\}$.
2. **Global TF-IDF Block-Normalization Vector**: Vectors are partitioned into subvectors $v(p) = [\sqrt{\alpha}\hat{v}_{\text{pawn}}, \sqrt{1-\alpha}\hat{v}_{\text{piece}}]$, with true cosine similarity $\frac{u \cdot v}{\|u\|_2 \|v\|_2}$ and explicit zero-norm guards for pawnless/pieceless boundary positions.
3. **Relaxed MNN Graph Construction**: Symmetric top-$k$ ($k=30$, $\tau_{\text{floor}} = 0.40$) supplemented by an absolute top-1 asymmetric exception ($\tau_{\text{top1}} = 0.65$), with deterministic tie-breaking on `canonicalFen` ascending.
4. **CWTS Leiden Community Detection**: Using `nl.cwts:networkanalysis:1.3.0`, node weights are set to total edge weights ($n_i = k_i$), and CPM resolution is rescaled as $\gamma_{CWTS} = \frac{\gamma_{RB}}{2m}$ where $\gamma_{RB} = 1.25$. This gives exact Reichardt-Bornholdt modularity equivalence ($\text{calcQuality}_{CWTS} = Q_{RB}$). Zero-edge graphs ($2m = 0.0$) bypass Leiden to classify all positions as unassigned isolates.
5. **Centroid Cohesion Evaluation**: Member vectors are averaged into centroid $C = \frac{1}{|K|}\sum v(u)$. Positions with $\text{sim}(u, C) < \tau_c = 0.38$ are evicted as `OUTLIER_EVICTED` with `cluster_id = null`. Empty clusters are dropped.
6. **Snapshot Lifecycle Persistence**: Schema evolved in `V1__baseline.sql` (`position_cluster_snapshot`, `position_cluster`, `position_cluster_assignment`). Snapshots support active publication (`promoteToActive = true`, superseding existing `ACTIVE` snapshot) and isolated experimental execution (`promoteToActive = false`, status `EXPERIMENTAL`). Concurrency is guarded by an account-level mutual exclusion lock.

## Ruled Out
- **Stockfish / Dynamic Similarity Integration**: Dynamic similarity (Issue #699) and Maia chess models were strictly ruled out; this implementation focuses exclusively on static relational clustering.
- **Frontend UI Controls / Sliders**: Kept as an internal backend experimental parameter without user-facing exposure.
- **Incremental Real-Time Clustering on Game Ingestion**: Clustering is designed as an offline asynchronous batch snapshot operation, not an inline ingestion step.
- **Speculative Interfaces**: Applied Ponytail minimalism to avoid redundant abstractions or single-implementation interfaces.
