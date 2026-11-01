# ChessEcho / ChessProbe Architecture Knowledge Graph

This directory contains the persistent, standalone HTML knowledge graph visualization of the ChessEcho codebase, generated via [Graphify](https://github.com/Graphify-Labs/graphify).

## 1. What the Graph Represents

The architecture graph provides an interactive structural map of the codebase extracted directly via Tree-sitter parsers:
- **Nodes**: Source files, Kotlin classes, Spring Boot services, controllers, entities, repositories, TypeScript/React components, Next.js routes, utility functions, and test suites.
- **Edges**: Directed and undirected relationships including class containment, imports, dependency injection references, method invocations, interface implementations, and caller linkages.
- **Communities**: High-level functional subsystems clustered via community detection (e.g., `GameImportService Subsystem`, `PositionProgressView Subsystem`, `EngineAnalysisService Subsystem`, `ChessAccount Domain Subsystem`).
- **Aggregated View**: For repositories exceeding 5,000 nodes (ChessEcho initial baseline snapshot: 6,178 nodes, 18,410 edges, 298 community clusters), Graphify automatically renders an aggregated community-level view for smooth browser rendering.

## 2. How to Open the Visualization

The visualization is completely standalone and requires no AI agent, local web server, or background process.

### Direct File Open
Open `graph.html` (or `index.html`) directly in any modern browser:
```bash
# macOS
open docs/architecture/graphify/graph.html

# Linux
xdg-open docs/architecture/graphify/graph.html

# Windows
start docs/architecture/graphify/graph.html
```

### Offline Operation & Assets
The visualization includes a bundled local copy of the visualization library:
- Entry point: `docs/architecture/graphify/graph.html` (and `docs/architecture/graphify/index.html`)
- Local asset: `docs/architecture/graphify/assets/vis-network.min.js`
The HTML loads the local script first and falls back to CDN if moved, ensuring full offline functionality with zero external network dependencies.

## 3. Querying the Knowledge Graph

When `graphify-out/graph.json` exists locally, query architectural relationships using the Graphify CLI:
```bash
# Query natural-language architecture questions
graphify query "How does GameImportService interact with Stockfish analysis?"

# Find the shortest structural path between two components
graphify path "GameImportService" "PositionOccurrenceRepository"

# Explain a focused concept or component
graphify explain "PositionSimilarityCache"
```

## 4. How to Regenerate or Update the Graph

Do **not** regenerate the graph automatically on every build or test run. Rebuilding should only occur intentionally following major architectural restructuring or release milestones.

### Prerequisites
- Python 3.10+
- Standalone Graphify CLI: `graphifyy==0.9.100` (e.g., installed via `uv tool install graphifyy==0.9.100`)

### Using the Included Update Script
Run the reproducible update script from the repository root:
```bash
./docs/architecture/graphify/update-graph.sh
```

### Manual Command Line Steps
If updating manually using the Graphify CLI:
```bash
# 1. Ensure graphifyy is installed
uv tool install graphifyy==0.9.100

# 2. Build or update graph data
graphify .

# 3. Export HTML visualization
graphify export html

# 4. Copy generated HTML to this directory
cp graphify-out/graph.html docs/architecture/graphify/graph.html
cp graphify-out/graph.html docs/architecture/graphify/index.html
```

## 5. What the Graph Does and Does Not Capture

### What is Captured
- Static structural syntax trees: imports, class declarations, method calls, Spring bean wiring, and Next.js page dependencies.
- Component-level clustering and inter-subsystem call flows.
- God nodes (central architectural hubs with high degrees, e.g., `GameImportService`, `EngineAnalysisService`, `PositionOccurrenceRepository`).
- Bridge connections and cross-subsystem dependencies.

### What is NOT Captured
- Dynamic runtime values, runtime polymorphism, and reflection-based dynamic dispatch.
- Runtime database query execution plans, PostgreSQL transaction lifecycles, and lock contention.
- Asynchronous task timing, Docker compose network topologies, and external network interactions (e.g., Chess.com PubAPI rate limits).
- Non-syntax semantic intent or business logic nuances not expressed in class/function signatures.

## 6. Architectural Staleness Notice

> [!WARNING]
> Architecture graphs are static snapshots reflecting the codebase at the time of generation. As code evolves, refactors occur, or components are added/removed, this graph can become stale.
> Always consult the live source code in `backend/` and `frontend/` before making architectural correctness claims or designing new features.
