# Historical Position Clustering & Recurring Weakness Discovery Architecture

## 1. Overview

ChessProbe provides account-scoped offline position clustering to identify cohesive thematic position families from an account's historical games. It discovers recurring patterns across openings and middle-game structures where players repeatedly face tactical and positional challenges.

The subsystem operates through two primary components:
1. **`PositionClusteringService`**: Decoupled graph construction, CWTS Leiden community detection, and centroid cohesion pruning supporting both `ClusterType.STRUCTURAL` and `ClusterType.DYNAMIC` clustering pipelines.
2. **`RecurringWeaknessDiscoveryService`**: An asynchronous job coordinator managing background pipeline execution, cooperative cancellation, snapshot persistence, and analytical community profiling.

---

## 2. Clustering Pipeline Architecture

```mermaid
%%{init: {"htmlLabels": false, "flowchart": {"htmlLabels": false}}}%%
flowchart TD
    Account["ChessAccount<br/>& Parameters"] --> CandidateRetrieval["Candidate Corpus<br/>Retrieval"]
    CandidateRetrieval --> PipelineChoice{ClusterType}
    
    PipelineChoice -->|STRUCTURAL| Subvectors["Relational Subvector<br/>Block Normalization"]
    Subvectors --> Cosine["Cosine Similarity<br/>Matrix"]
    
    PipelineChoice -->|DYNAMIC| DynamicIndex["DynamicInvertedIndex<br/>& Stockfish PV Unigrams"]
    DynamicIndex --> DynamicScoring["Dynamic BM25<br/>Similarity Matrix"]
    
    Cosine --> MNN["Relaxed MNN<br/>Graph Construction"]
    DynamicScoring --> MNN
    
    MNN --> Leiden["CWTS Leiden<br/>Community Detection"]
    Leiden --> Modularity["Reichardt-Bornholdt<br/>Modularity Equivalence<br/>(gamma=1.25)"]
    Modularity --> Centroid["Centroid Vector<br/>Evaluation & Pruning"]
    Centroid --> Eviction{"sim(u, C) >= 0.38"}
    
    Eviction -->|Yes| ClusterAssignment[Assigned to Cluster]
    Eviction -->|No| OutlierEviction["OUTLIER_EVICTED<br/>cluster_id=null"]
    
    ClusterAssignment --> Snapshot["PositionClusterSnapshot<br/>Persistence"]
    OutlierEviction --> Snapshot
```

### 2.1 Decoupled Clustering Modes (`ClusterType`)
- **`ClusterType.STRUCTURAL`**: Uses static relational BM25 token representation partitioned into pawn-structure and piece-placement subvectors. A configurable internal weighting parameter $\alpha \in [0.0, 1.0]$ (default $\alpha = 0.60$) balances structural pawn tokens ($v_P$) and piece placement tokens ($v_{Pc}$):
  $$v(p) = [\sqrt{\alpha}\hat{v}_{\text{pawn}}, \sqrt{1-\alpha}\hat{v}_{\text{piece}}]$$
  Vectors use true cosine similarity with explicit zero-norm guards for boundary states.
- **`ClusterType.DYNAMIC`**: Uses depth-10 MultiPV-1 dynamic continuation unigrams extracted via `DynamicInvertedIndex` from engine principal variations.

### 2.2 Relaxed Mutual Nearest Neighbors (MNN) Graph Construction
To construct robust thematic similarity graphs while avoiding disconnected components:
1. **Symmetric $k$-NN**: Positions are connected if both nodes consider each other in their top-$k$ neighbors ($k = 30$, $\tau_{\text{floor}} = 0.40$).
2. **Asymmetric Top-1 Exception**: If position $u$ has an exceptionally strong top-1 match with $v$ ($\tau_{\text{top1}} = 0.65$), an edge is established even if reciprocity is not satisfied.
3. Deterministic tie-breaking is enforced by sorting candidate positions by `canonicalFen` ascending.
4. If the graph contains zero qualifying edges ($2m = 0.0$), community detection is bypassed and all positions are classified as `ISOLATE`.

### 2.3 CWTS Leiden Community Detection & Modularity Equivalence
The clustering engine integrates `nl.cwts:networkanalysis:1.3.0`:
- Node weights are set to total edge weights ($n_i = k_i$).
- The constant Potts model (CPM) resolution parameter is rescaled against total graph edge weight:
  $$\gamma_{\text{CWTS}} = \frac{\gamma_{\text{RB}}}{2m} \quad (\gamma_{\text{RB}} = 1.25)$$
- This rescaling produces mathematical equivalence with Reichardt-Bornholdt modularity:
  $$\text{calcQuality}_{\text{CWTS}} = Q_{\text{RB}}$$
- The Leiden algorithm is run with quality optimization to guarantee connected, cohesive community clusters.

### 2.4 Centroid Cohesion Pruning
To prevent loose cluster sprawl:
1. Member position vectors are averaged into a cluster centroid:
   $$C = \frac{1}{|K|} \sum_{u \in K} v(u)$$
2. Positions with cosine cohesion below threshold ($\tau_c = 0.38$) are evicted as `OUTLIER_EVICTED` with `cluster_id = null`.
3. Clusters where all members are evicted or that fall below minimum size thresholds are dropped.

---

## 3. Asynchronous Job Lifecycle & Discovery Service

```mermaid
%%{init: {"htmlLabels": false, "stateDiagram": {"htmlLabels": false}}}%%
stateDiagram-v2
    [*] --> QUEUED: POST /api/weaknesses/recurring/discover
    QUEUED --> RUNNING: Executor thread starts
    RUNNING --> EVALUATING: Check qualifying positions
    EVALUATING --> CLUSTERING: Invoke PositionClusteringService
    CLUSTERING --> COMPLETED: Leiden & Profiling finish
    
    RUNNING --> CANCELLED: POST .../cancel (cooperative)
    EVALUATING --> CANCELLED: POST .../cancel (cooperative)
    CLUSTERING --> CANCELLED: POST .../cancel (cooperative)
    
    RUNNING --> FAILED: Unexpected exception
    EVALUATING --> FAILED: Analysis exception
    CLUSTERING --> FAILED: Clustering exception
    
    COMPLETED --> [*]
    CANCELLED --> [*]
    FAILED --> [*]
```

### 3.1 Job Concurrency & Cancellation
- `RecurringWeaknessDiscoveryService` maintains state in memory via `ConcurrentHashMap<UUID, DiscoveryJobState>`.
- Only one active job is permitted per `ChessAccount` at a time. A concurrent duplicate request returns `409 ACTIVE_JOB_CONFLICT`.
- Jobs support cooperative cancellation (`cancellationRequested.set(true)`). Each pipeline phase checks the cancellation token between iterations to safely abort without database corruption.

### 3.2 Community Weakness Profiling (`CommunityWeaknessService`)
Once community clusters are identified, `CommunityWeaknessService` aggregates:
1. **Decision Timing Profile**: Uses `DecisionTimingPolicy` to compute median decision time and percentage of moves classified as `NORMAL`, `EXTENDED`, and `SEVERE`.
2. **Loss Metrics**: Aggregates mean evaluation loss and mistake frequencies.
3. **Format Breakdown**: Partitions performance across Bullet, Blitz, Rapid, and Classical time controls.

---

## 4. Persistence Model

The clustering subsystem persists entities to PostgreSQL across three tables in `V1__baseline.sql`:

```mermaid
%%{init: {"htmlLabels": false, "erDiagram": {"htmlLabels": false}}}%%
erDiagram
    CHESS_ACCOUNT ||--o{ POSITION_CLUSTER_SNAPSHOT : owns
    POSITION_CLUSTER_SNAPSHOT ||--o{ POSITION_CLUSTER : contains
    POSITION_CLUSTER ||--o{ POSITION_CLUSTER_ASSIGNMENT : assigns
    POSITION ||--o{ POSITION_CLUSTER_ASSIGNMENT : references

    POSITION_CLUSTER_SNAPSHOT {
        uuid id PK
        uuid account_id FK
        int snapshot_version
        string cluster_type
        string status
        double alpha
        timestamp created_at
    }

    POSITION_CLUSTER {
        uuid id PK
        uuid snapshot_id FK
        int cluster_index
        string representative_fen
        int member_count
        double cohesion_score
    }

    POSITION_CLUSTER_ASSIGNMENT {
        uuid id PK
        uuid snapshot_id FK
        uuid cluster_id FK
        uuid position_id FK
        string status
        double distance_to_centroid
    }
```

- **`position_cluster_snapshot`**: Holds the versioned snapshot metadata, status (`GENERATING`, `ACTIVE`, `SUPERSEDED`, `EXPERIMENTAL`, `FAILED`), and configuration parameters.
- **`position_cluster`**: Represents an individual cohesive cluster with its representative FEN and member metrics.
- **`position_cluster_assignment`**: Maps individual `Position` records to clusters, recording centroid distance and assignment status (`ASSIGNED`, `OUTLIER_EVICTED`, `ISOLATE`).
