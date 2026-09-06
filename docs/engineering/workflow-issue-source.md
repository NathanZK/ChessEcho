# Trusted pre-genesis issue source

`scripts/workflow_issue_source.py` owns the narrow pre-genesis publication required
before replacement-workflow initialization. It does not create or select authority,
publish evidence bindings, mutate lifecycle state, or accept caller-provided payload
bytes or object references.

## Publication

```text
python3 scripts/workflow_issue_source.py publish ISSUE \
  --root ROOT \
  --repository OWNER/REPOSITORY \
  --git-executable /absolute/path/to/git \
  --gh-executable /absolute/path/to/gh
```

The GitHub token is read from standard input. The operation:

1. denies frozen issue #115 before runtime or CAS access;
2. bootstraps the base-pinned `workflow_runtime` and obtains the issue only through
   `Runtime.observe_issue`;
3. verifies the exact repository, issue number, GitHub API URL, GitHub HTML URL,
   bounded raw size and SHA-256, and the complete canonical snapshot relationship;
4. publishes only those exact raw bytes with `workflow_cas.publish_immutable`; and
5. returns `chess-echo-trusted-issue-source-publication-v1`.

The returned publication contains the exact `issue-snapshot` object reference, the
complete runtime bootstrap document, the runtime source identity, and the intake tool
identity. The canonical snapshot is validated before publication but is not embedded,
so repeated intake of identical bytes returns the same publication despite a new
capture time. The bootstrap document binds the base commit and tree, configuration
blob/content identity, and Git/GitHub executable paths and hashes. The GitHub
credential is never returned or stored.

Identical publication is idempotent, including concurrent publication. A conflicting
object, malformed observation, tool/config/bootstrap change, or identity mismatch
fails closed. Interruption before the immutable link creates no success document;
restart converges only when the observed bytes are identical.

## Initialization handoff

`workflow_orchestrator.initialize()` requires the publication as
`trusted_issue_source`. It performs a fresh runtime bootstrap and issue observation,
requires the receipt's bootstrap/config/tool identities to remain exact, requires the
live raw-byte reference to equal the published reference, and then verifies the CAS
object byte-for-byte before any genesis publication or authority commit.

An issue edit after intake therefore returns `issue-source-edited`. The operator must
run a fresh explicit intake and pass its new publication; initialization never swaps
in the newer bytes implicitly.

The operation writes no authority pointer, evidence binding, legacy projection,
migration or repair state, approval, route, or lifecycle transition. Direct filesystem
writers remain outside this API's trust boundary; publication alone does not
authenticate issue content or grant workflow authority.
