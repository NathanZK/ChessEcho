#!/usr/bin/env bash
set -euo pipefail

# Script: update-graph.sh
# Purpose: Reproducibly regenerate the ChessProbe / ChessProbe codebase knowledge graph
# and update the persistent HTML visualization in docs/architecture/graphify/.

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$REPO_ROOT"

echo "=== Updating ChessProbe Codebase Architecture Graph ==="

# 1. Resolve local Python environment and Graphify executable
resolve_shebang_interpreter() {
    local file="$1"
    local first_line=""
    if [ ! -f "$file" ]; then
        return 1
    fi
    IFS= read -r first_line < "$file" || [ -n "$first_line" ] || return 1
    first_line="${first_line%$'\r'}"
    if [[ "$first_line" =~ ^#![[:space:]]*(.*)$ ]]; then
        local target="${BASH_REMATCH[1]}"
        # Trim leading and trailing whitespace
        target="${target#"${target%%[![:space:]]*}"}"
        target="${target%"${target##*[![:space:]]}"}"
        # Check env-based shebang: /usr/bin/env [-S] <prog> [args...]
        if [[ "$target" =~ ^/usr/bin/env[[:space:]]+(-S[[:space:]]+)?([^[:space:]]+) ]]; then
            local env_prog="${BASH_REMATCH[2]}"
            if command -v "$env_prog" >/dev/null 2>&1; then
                command -v "$env_prog"
                return 0
            fi
        elif [ -x "$target" ]; then
            echo "$target"
            return 0
        fi
    fi
    return 1
}

GRAPHIFY_BIN=""
if command -v graphify >/dev/null 2>&1; then
    GRAPHIFY_BIN="$(command -v graphify)"
elif [ -x "${HOME:-}/.local/bin/graphify" ]; then
    GRAPHIFY_BIN="${HOME:-}/.local/bin/graphify"
fi

PYTHON=""
if [ -n "$GRAPHIFY_BIN" ]; then
    PYTHON="$(resolve_shebang_interpreter "$GRAPHIFY_BIN")" || true
    if [ -z "$PYTHON" ]; then
        echo "Error: Unable to resolve a valid Python interpreter from '$GRAPHIFY_BIN' shebang." >&2
        exit 1
    fi
    if ! "$PYTHON" -c "import graphify" >/dev/null 2>&1; then
        echo "Error: Python interpreter '$PYTHON' from '$GRAPHIFY_BIN' cannot import graphify." >&2
        exit 1
    fi
else
    # Fallback to local python3 only when standalone graphify binary is not found
    if python3 -c "import graphify" >/dev/null 2>&1; then
        PYTHON="python3"
    fi
    if [ -z "$PYTHON" ]; then
        echo "Error: Graphify is not installed locally." >&2
        echo "Prerequisite: Install once via 'uv tool install graphifyy==0.9.100' (or ensure graphify is in PATH)." >&2
        exit 1
    fi
fi

mkdir -p graphify-out
echo "$PYTHON" > graphify-out/.graphify_python

# 2. File detection
echo "[1/5] Detecting codebase files..."
"$PYTHON" -c "
import json
from graphify.detect import detect
from pathlib import Path
from graphify.paths import out_path
from graphify.watch import _read_build_excludes, _read_build_gitignore

result = detect(Path('.'), extra_excludes=_read_build_excludes(out_path()), gitignore=_read_build_gitignore(out_path()))
Path('graphify-out/.graphify_detect.json').write_text(json.dumps(result, ensure_ascii=False), encoding='utf-8')
print(f'Detected {result[\"total_files\"]} files ({result.get(\"total_words\", 0)} words)')
"

# 3. Structural AST extraction
echo "[2/5] Running Tree-sitter AST extraction on code files..."
"$PYTHON" -c "
import json
from graphify.extract import collect_files, extract
from pathlib import Path

detect = json.loads(Path('graphify-out/.graphify_detect.json').read_text(encoding='utf-8'))
code_files = []
for f in detect.get('files', {}).get('code', []):
    code_files.extend(collect_files(Path(f)) if Path(f).is_dir() else [Path(f)])

result = extract(code_files, cache_root=Path('.'), root=Path('.'))
Path('graphify-out/.graphify_ast.json').write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding='utf-8')
print(f'AST extracted: {len(result[\"nodes\"])} nodes, {len(result[\"edges\"])} edges')
"

# 4. Merge results (local offline mode, no external LLM)
echo "[3/5] Merging extraction results..."
"$PYTHON" -c "
import json
from pathlib import Path

Path('graphify-out/.graphify_semantic.json').write_text(json.dumps({'nodes':[],'edges':[],'hyperedges':[],'input_tokens':0,'output_tokens':0}), encoding='utf-8')
ast = json.loads(Path('graphify-out/.graphify_ast.json').read_text(encoding='utf-8'))
sem = json.loads(Path('graphify-out/.graphify_semantic.json').read_text(encoding='utf-8'))

for n in sem['nodes']: n.setdefault('_origin', 'semantic')
for e in sem['edges']: e.setdefault('_origin', 'semantic')

seen = {n['id'] for n in ast['nodes']}
merged_nodes = list(ast['nodes'])
for n in sem['nodes']:
    if n['id'] not in seen:
        merged_nodes.append(n)
        seen.add(n['id'])

merged = {
    'nodes': merged_nodes,
    'edges': ast['edges'] + sem['edges'],
    'hyperedges': sem.get('hyperedges', []),
    'input_tokens': sem.get('input_tokens', 0),
    'output_tokens': sem.get('output_tokens', 0),
}
Path('graphify-out/.graphify_extract.json').write_text(json.dumps(merged, indent=2, ensure_ascii=False), encoding='utf-8')
"

# 5. Build graph, cluster, and label communities
echo "[4/5] Building graph, clustering communities, and generating report..."
"$PYTHON" -c "
import json, re
from graphify.build import build_from_json
from graphify.cluster import cluster, score_all
from graphify.analyze import god_nodes, surprising_connections, suggest_questions
from graphify.report import generate
from graphify.export import to_json
from pathlib import Path

extraction = json.loads(Path('graphify-out/.graphify_extract.json').read_text(encoding='utf-8'))
detection  = json.loads(Path('graphify-out/.graphify_detect.json').read_text(encoding='utf-8'))

G = build_from_json(extraction, root='.', directed=False)
communities = cluster(G)
cohesion = score_all(G, communities)
tokens = {'input': extraction.get('input_tokens', 0), 'output': extraction.get('output_tokens', 0)}
node_map = {n['id']: n for n in extraction['nodes']}

labels = {}
for cid, nids in communities.items():
    c_nodes = [node_map[nid] for nid in nids if nid in node_map]
    sources = set()
    for n in c_nodes:
        if 'source_file' in n and n['source_file']:
            sources.add(Path(n['source_file']).name)
    top_sources = sorted(sources, key=lambda s: sum(1 for n in c_nodes if n.get('source_file','').endswith(s)), reverse=True)
    if top_sources:
        primary = top_sources[0]
        base = primary.replace('.kt', '').replace('.tsx', '').replace('.ts', '').replace('.py', '')
        words = re.sub('([a-z])([A-Z])', r'\1 \2', base)
        labels[cid] = f'{words} Subsystem'
    else:
        labels[cid] = f'Community {cid}'

questions = suggest_questions(G, communities, labels)
report = generate(G, communities, cohesion, labels, god_nodes(G), surprising_connections(G, communities), detection, tokens, '.', suggested_questions=questions)
Path('graphify-out/GRAPH_REPORT.md').write_text(report, encoding='utf-8')
Path('graphify-out/.graphify_labels.json').write_text(json.dumps({str(k): v for k, v in labels.items()}, ensure_ascii=False), encoding='utf-8')

wrote = to_json(G, communities, 'graphify-out/graph.json', community_labels=labels)
print(f'Graph constructed: {G.number_of_nodes()} nodes, {G.number_of_edges()} edges, {len(communities)} communities')
"

# 6. Export HTML and update persistent docs deliverable
echo "[5/5] Exporting HTML visualization to docs/architecture/graphify/..."
if [ -n "$GRAPHIFY_BIN" ] && [ -x "$GRAPHIFY_BIN" ]; then
    "$GRAPHIFY_BIN" export html
else
    "$PYTHON" -c "
import json
from graphify.build import build_from_json
from graphify.cluster import cluster
from graphify.export import to_html
from pathlib import Path

extraction = json.loads(Path('graphify-out/.graphify_extract.json').read_text(encoding='utf-8'))
labels = json.loads(Path('graphify-out/.graphify_labels.json').read_text(encoding='utf-8'))
labels = {int(k): v for k, v in labels.items()}
G = build_from_json(extraction, root='.', directed=False)
communities = cluster(G)
to_html(G, communities, 'graphify-out/graph.html', community_labels=labels, node_limit=5000)
"
fi

# Patch HTML to use local assets with CDN fallback
"$PYTHON" -c "
from pathlib import Path
html = Path('graphify-out/graph.html').read_text(encoding='utf-8')
local_script = r'''<script src="./assets/vis-network.min.js"></script>
<script>
  if (typeof vis === 'undefined') {
    document.write('<script src="https://unpkg.com/vis-network@9.1.6/standalone/umd/vis-network.min.js" integrity="sha384-Ux6phic9PEHJ38YtrijhkzyJ8yQlH8i/+buBR8s3mAZOJrP1gwyvAcIYl3GWtpX1" crossorigin="anonymous"><\/script>');
  }
</script>'''
old_script = r'''<script src="https://unpkg.com/vis-network@9.1.6/standalone/umd/vis-network.min.js"
        integrity="sha384-Ux6phic9PEHJ38YtrijhkzyJ8yQlH8i/+buBR8s3mAZOJrP1gwyvAcIYl3GWtpX1"
        crossorigin="anonymous"></script>'''
if old_script in html:
    html = html.replace(old_script, local_script)

Path('docs/architecture/graphify/graph.html').write_text(html, encoding='utf-8')
Path('docs/architecture/graphify/index.html').write_text(html, encoding='utf-8')
print('Updated docs/architecture/graphify/graph.html and index.html')
"

echo "=== Graph generation complete! ==="
