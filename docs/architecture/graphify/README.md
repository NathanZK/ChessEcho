# ChessProbe / ChessProbe Architecture Knowledge Graph

This directory contains the persistent, standalone HTML knowledge graph visualization of the ChessProbe codebase, generated via [Graphify](https://github.com/Graphify-Labs/graphify).

## 1. What the Graph Represents

The architecture graph provides an interactive structural map of the codebase extracted directly via Tree-sitter AST parsers:
- **Nodes**: Source files, Kotlin classes, Spring Boot services, controllers, entities, repositories, TypeScript/React components, Next.js routes, utility functions, and test suites.
- **Edges**: Directed and undirected relationships including class containment, imports, dependency injection references, method invocations, interface implementations, and caller linkages.
- **Communities**: High-level functional subsystems clustered via Louvain community detection (e.g., `Game Import Service Subsystem`, `Position Occurrence Repository Subsystem`, `Engine Analysis Repository Subsystem`, `Chess Account Domain Subsystem`).
- **Aggregated View**: For repositories exceeding 5,000 nodes (ChessProbe initial baseline snapshot: 6,178 nodes, 18,410 edges, 298 community clusters; regenerated: 6,331 nodes, 18,839 edges, 291 communities), Graphify automatically renders an aggregated community-level meta-graph for smooth browser canvas rendering.

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
- Local asset: `docs/architecture/graphify/assets/vis-network.min.js` (v9.1.6, SHA-384 verified: `sha384-Ux6phic9PEHJ38YtrijhkzyJ8yQlH8i/+buBR8s3mAZOJrP1gwyvAcIYl3GWtpX1`)
The HTML loads the local script first and falls back to CDN if moved, ensuring full offline functionality with zero external network dependencies.

## 3. Querying the Knowledge Graph (CLI Guide & Best Practices)

When `graphify-out/graph.json` exists locally, query architectural relationships using the Graphify CLI.

### Verified CLI Commands

```bash
# 1. Inspect a specific component and its immediate relationships (Recommended)
graphify explain "GameImportService"

# 2. Trace shortest structural path between two components (Recommended)
graphify path "GameImportController" "PositionOccurrenceRepository"

# 3. Targeted constructor & static call relationships
graphify query "GameImportService" --context call

# 4. Dependency-injection parameter & field references
graphify query "GameImportService" --context field

# 5. Reverse impact analysis (direct incoming consumers, tests, controllers)
graphify affected "GameImportService" --depth 1
```

### Retrieval Dynamics & Known CLI Behaviors

#### Lexical Matching vs. Conversational Queries
- `graphify query` uses **lexical keyword matching, not semantic embeddings**. It tokenizes input, strips punctuation and common stopwords, and guarantees seating the highest-scoring node for *every individual token* as a BFS root.
- **Anti-Pattern**: Conversational natural-language questions (e.g. `graphify query "How does game import flow through the backend?"`) seat unrelated nodes matching single words (e.g. `backend` in `.github/workflows/ci.yml`, `Import` in frontend, `Game` in domain entity, test methods matching `flow`). Additionally, question words like "import" can trigger heuristic context filters (`context=import`), traversing 490+ raw file imports instead of execution flows.
- **Rule**: Target specific symbol names (`"GameImportService"`) rather than conversational English questions.

#### Undirected BFS Depth-Two Expansion & Test Noise
- `graphify query` traverses the graph **undirected at a hardcoded BFS depth of 2**.
- Because Spring Boot services are heavily imported and referenced by multiple integration tests (e.g. `GameImportServiceTest`, `GameImportServiceBaseIntegrationTest`, `DerivedBatchBoundaryIntegrationTest`), hop 1 reaches all test classes, and hop 2 expands across the entire dependency graph of those tests.
- An unconstrained query on `GameImportService` discovers **516 nodes**, the majority of which are test fixtures.

#### Token Budget & Edge Truncation
- Output is rendered by printing all `NODE` declarations before any `EDGE` relationships.
- The default budget is ~2,000 tokens (~6,000 characters).
- For subgraphs exceeding ~35 nodes, the character budget is 100% consumed by node definitions alone, **truncating output before any edge lines are printed**.
- To see relationships on dense nodes, either narrow the query using `--context call` (reducing 516 nodes to 5 nodes) or raise the budget (e.g. `--budget 15000`).

#### Command & Flag Syntax Discrepancies
- **Context Filtering**: Use `--context <type>` (e.g. `--context call` or `--context field`). Do **not** use `--context-filter`; the CLI parser silently ignores unrecognized flags and falls back to an unfiltered 516-node traversal.
- **Node Inspection**: Do **not** use `graphify get_node`. Although truncation notices in Graphify mention `get_node`, `get_node` is an internal MCP endpoint not exposed in the CLI (`error: unknown command 'get_node'`). The CLI equivalent is `graphify explain "<Symbol>"`.

## 4. Relationship Semantics & Extraction Coverage

Graphify extracts static Tree-sitter AST relationships. In a Kotlin / Spring Boot codebase, understanding edge semantics is essential:

| Relationship & Context | Extraction Mechanism | Meaning in ChessProbe / Kotlin |
|---|---|---|
| `calls [context=call]` | Tree-sitter AST call resolution | **Constructor invocations (`ClassName(...)`) and static/companion member calls (`Policy.eval(...)`)**. For example, `GameImportService --calls--> MonthImportCoordinator` represents constructor instantiation at line 99. |
| `references [context=field]` / `[context=parameter_type]` | AST type references | **Spring Dependency Injection**. For example, `GameImportService --references--> PositionOccurrenceRepository` represents constructor-injected repository dependencies. |
| `contains [context=None]` | AST hierarchy | Package containment, class declarations, and nested types. |
| `imports [context=import]` | File import statements | File-level import dependencies. |

### Kotlin Instance-Method Limitation
- Tree-sitter AST extraction does not perform whole-program type inference or Spring Bean resolution.
- Calls on lowercase instance variables (e.g. `gameRepository.save(game)` or `positionOccurrenceRepository.findByAccount(...)`) are intentionally skipped across files by Graphify's resolver to avoid global name collisions and false-positive "god-node" explosion on generic method names like `save` or `find`.
- **Critical Takeaway**: A `references` edge establishes architectural coupling and dependency injection, but does **not** prove that a specific method is called. An agent or developer must inspect live Kotlin source code to verify method execution, conditional branching, and transactional boundaries.

## 5. How to Regenerate or Update the Graph

Do **not** regenerate the graph automatically on every build or test run. Rebuilding should only occur intentionally following major architectural restructuring or release milestones.

### Prerequisites
- Python 3.10+
- Standalone Graphify CLI: `graphifyy==0.9.100` (installed via `uv tool install graphifyy==0.9.100`)

### Using the Included Update Script
Run the reproducible update script from the repository root:
```bash
./docs/architecture/graphify/update-graph.sh
```
The script operates 100% offline:
- Resolves the local Python interpreter from the installed `graphify` shebang (supporting direct paths, paths with spaces, and env-based launchers).
- Validates that the resolved interpreter imports `graphify` without falling back to external package downloads.
- Re-extracts AST data, clusters communities, exports aggregated HTML, and patches offline vendor scripts.

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

## 6. What the Graph Does and Does Not Capture

### What is Captured
- Static structural syntax trees: imports, class declarations, Spring bean wiring, and Next.js page dependencies.
- Component-level clustering and high-level architectural subsystems.
- God nodes (central architectural hubs with high degrees, e.g., `GameImportService`, `EngineAnalysisService`, `PositionOccurrenceRepository`).
- Bridge connections and cross-subsystem dependencies.

### What is NOT Captured
- Kotlin instance-method invocations on injected dependencies.
- Dynamic runtime values, runtime polymorphism, and reflection-based dynamic dispatch.
- Runtime database query execution plans, PostgreSQL transaction lifecycles, and lock contention.
- Asynchronous task timing, Docker compose network topologies, and external network interactions (e.g., Chess.com PubAPI rate limits).
- Non-syntax semantic intent or business logic nuances not expressed in class/function signatures.

## 7. Architectural Staleness Notice

> [!WARNING]
> Architecture graphs are static snapshots reflecting the codebase at the time of generation. As code evolves, refactors occur, or components are added/removed, this graph can become stale.
> Always consult the live source code in `backend/` and `frontend/` before making architectural correctness claims or designing new features.
