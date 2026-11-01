---
trigger: always_on
description: Consult the graphify knowledge graph at graphify-out/ for codebase and architecture questions.
---

## graphify

This project has a graphify knowledge graph at graphify-out/ to supplement architectural understanding.

Rules:
- **Query-first exploration**: For codebase or architecture questions, when `graphify-out/graph.json` exists, first run `graphify query "<question>"`. Use `graphify path "<A>" "<B>"` for structural relationships between components, and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, significantly smaller than `GRAPH_REPORT.md` or broad filesystem grep.
- **Navigation guidance**: If `graphify-out/wiki/index.md` exists, consult it for high-level architectural navigation before inspecting targeted raw source files.
- **Overview reports**: Read `graphify-out/GRAPH_REPORT.md` only for broad architecture review or when query/path/explain do not surface enough context.
- **AST updates**: After modifying code files in this session, run `graphify update .` to keep the local graph current (AST-only, strictly local, no API cost).
- **Instruction boundaries & ground truth**:
  - Graphify is an exploratory supplement and architectural map; live source code in `backend/` and `frontend/` remains the authoritative ground truth for implementation and verification.
  - Graphify rules do not override Praxis authorization, repository conventions, or required test and linting gates.
  - All operations are strictly local and offline; no remote MCP or hosted cloud endpoints are used.
