---
trigger: always_on
description: Consult the graphify knowledge graph at graphify-out/ for codebase and architecture questions.
---

## graphify

This project has a graphify knowledge graph at graphify-out/ to supplement architectural understanding.

Rules:
- **Navigation & Inspection Strategy**:
  - **Component Inspection**: Prefer `graphify explain "Symbol"` to inspect a specific class, service, or component and its immediate 1-hop relationships without expanding into large traversal trees.
  - **Dependency Paths**: Prefer `graphify path "Source" "Target"` to trace the shortest structural dependency path between two components (e.g. `graphify path "GameImportController" "PositionOccurrenceRepository"`).
  - **Call Relationships**: Use `graphify query "Symbol" --context call` for targeted constructor and static call relationships.
  - **Dependency-Injection References**: Use `graphify query "Symbol" --context field` when investigating injected fields and parameters (note: 2-hop traversal can be noisy as it includes test references).
  - **Reverse Impact Analysis**: Use `graphify affected "Symbol" --depth 1` to find direct incoming consumers, tests, and controllers affected by a symbol.
- **Query Constraints & Known Anti-Patterns**:
  - **No Conversational Questions**: Never pass conversational natural-language sentences (e.g. `graphify query "How does X work?"`) to `graphify query`. Its retrieval is purely lexical keyword matching with a mandatory per-term seed guarantee, which matches unrelated files and exhausts token budgets before edges can render. Target specific symbols instead.
  - **Unsupported Flags & Commands**: Never use `--context-filter` (the valid CLI flag is `--context`) and never use `graphify get_node` (an internal MCP tool not supported by the CLI; use `graphify explain` instead).
- **Architectural & Semantic Boundaries**:
  - **Missing Kotlin Instance-Method Calls**: Graphify extracts constructor invocations and static calls as `calls`, and Spring dependency injection as `references [context=field]`. It does NOT extract instance-method invocations on injected beans (e.g. `repository.save()`). A `references` edge establishes architectural coupling, but does NOT establish an actual method call.
  - **Navigation Aid, Not Runtime Proof**: Treat Graphify output as structural navigation evidence, never as proof of runtime behavior. Always inspect live source code in `backend/` and `frontend/` to verify execution flow, method calls, branching, and persistence.
  - **Instruction Boundaries & Ground Truth**:
    - Live source code in `backend/` and `frontend/` remains authoritative ground truth for implementation and verification.
    - Graphify rules do not override Praxis authorization, repository conventions, or required test and linting gates.
    - All operations are strictly local and offline; no remote MCP or hosted cloud endpoints are used.
- **AST Updates**:
  - After modifying code files in this session, run `graphify update .` to keep the local graph current (AST-only, strictly local, no API cost).
