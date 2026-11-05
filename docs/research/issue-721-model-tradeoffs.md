# Comparative Trade-Off Analysis: Position Similarity Models A–D

## Executive Summary
Based on empirical findings from Research Issue #721, **Model D (Orthogonal Dual-Space) with Model B as Phase 2 bridge** is selected as the architectural roadmap.

## Model Scorecard Comparison

| Model ID | Model Name | Latency (1-10) | Memory (1-10) | Dynamic Coherence (1-10) | Flexibility (1-10) | Determinism (1-10) | Simplicity (1-10) | Weighted Score | Verdict |
|---|---|---|---|---|---|---|---|---|---|
| `Model_A` | Structural-Only Baseline (Status Quo) | 10 | 9 | 3 | 5 | 10 | 10 | **7.15** | `RETAIN_AS_STRUCTURAL_SUBSTRATE` |
| `Model_B` | Structurally Supplemented Dynamic Filtering | 8 | 8 | 7 | 8 | 8 | 8 | **7.85** | `NEAR_TERM_RECOMMENDED_EXTENSION` |
| `Model_C` | Fused Static-Dynamic Similarity | 3 | 6 | 8 | 6 | 6 | 5 | **5.65** | `NOT_RECOMMENDED` |
| `Model_D` | Orthogonal Dual-Space Relationship Model | 7 | 8 | 9 | 10 | 9 | 7 | **8.45** | `TARGET_LONG_TERM_ARCHITECTURE` |

## Qualitative Rationale
### Structural-Only Baseline (Status Quo) (`Model_A`)
- **Verdict**: `RETAIN_AS_STRUCTURAL_SUBSTRATE`
- **Rationale**: Exceptional throughput and determinism make it ideal as a static candidate-generation foundation, but unsuited as a sole dynamic similarity proxy due to false-familiarity blind spots.

### Structurally Supplemented Dynamic Filtering (`Model_B`)
- **Verdict**: `NEAR_TERM_RECOMMENDED_EXTENSION`
- **Rationale**: Retains static graph construction speed while applying cheap dynamic screening (depth 8-12) at query/retrieval time to eliminate severe evaluation divergence.

### Fused Static-Dynamic Similarity (`Model_C`)
- **Verdict**: `NOT_RECOMMENDED`
- **Rationale**: Requires engine evaluation for every historical position before graph construction; couples structural clusters to engine version and search depth volatility.

### Orthogonal Dual-Space Relationship Model (`Model_D`)
- **Verdict**: `TARGET_LONG_TERM_ARCHITECTURE`
- **Rationale**: Decouples static pawn/piece structure from dynamic game state/criticality into orthogonal facets. Users can query by pawn structure and filter by dynamic state without topological interference.

## Architectural Implementation Roadmap
- Phase 1 (Complete): Production static structural clustering (Issue #702) establishes robust pawn/piece topology.
- Phase 2: Introduce on-demand dynamic filtering (Model B) using screening-depth Stockfish evaluation to flag false familiarity.
- Phase 3: Formalize orthogonal dual-space indexing (Model D), storing static cluster IDs and dynamic criticality/evaluation facets independently.
