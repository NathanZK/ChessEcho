# ChessEcho external Fidenaut consumer

Fidenaut owns governed workflow mechanics. ChessEcho owns repository identity,
target branch, role profiles, application validation, and product behavior.
The pinned provider is recorded in `.github/agent-workflow.json`:
`github.com/NathanZK/Fidenaut@c01e94575aecb3a3edfd26c35c1c6f2095b29000`.

The provider runtime checkout and manifest must remain outside the ChessEcho worktree.
Fidenaut currently has no bundle installer or fetch mechanism. Check out the
exact provider revision to an external location:

```bash
git clone https://github.com/NathanZK/Fidenaut.git "$FIDENAUT_PROVIDER_RUNTIME_ROOT"
git -C "$FIDENAUT_PROVIDER_RUNTIME_ROOT" checkout --detach c01e94575aecb3a3edfd26c35c1c6f2095b29000
```

Create the external `fidenaut-provider-runtime-manifest-v1` manifest at
`$FIDENAUT_PROVIDER_MANIFEST` using the verified file identities below:

```json
{
  "format": "fidenaut-provider-runtime-manifest-v1",
  "provider": {
    "repository": "github.com/NathanZK/Fidenaut",
    "revision": "c01e94575aecb3a3edfd26c35c1c6f2095b29000"
  },
  "files": [
    {
      "path": "scripts/agent_workflow.py",
      "mode": "100644",
      "sha256": "ffd3104015e3a82e570d5694310ba0f7f17758855f31cafca8ad53c78ed4be20"
    },
    {
      "path": "scripts/workflow_supervisor.py",
      "mode": "100644",
      "sha256": "4fb6d6a90d4c6baba9df6725f8566b5ae06899ffaa131a9389be7d9e52a7fd01"
    }
  ]
}
```

Invoke Fidenaut's provider entry point directly. Every command must provide the
consumer root, provider runtime root, and external manifest; do not add a
ChessEcho launcher or copy/overlay provider files:

```bash
python3 "$FIDENAUT_PROVIDER_RUNTIME_ROOT/scripts/agent_workflow.py" status ISSUE \
  --consumer-root "$CHESSECHO_ROOT" \
  --provider-runtime-root "$FIDENAUT_PROVIDER_RUNTIME_ROOT" \
  --provider-manifest "$FIDENAUT_PROVIDER_MANIFEST"
```

Fidenaut resolves configuration, state, Git operations, target topology, and
application validation from `--consumer-root`, while executing provider code
and resolving manifest paths from `--provider-runtime-root`. It verifies the
exact provider revision, manifest location, paths, Git modes, and SHA-256
values before progress and revalidates provider identity on state reads.
Missing, malformed, relocated, changed, or tampered inputs fail closed.
Provider runtime files and the external manifest are excluded from consumer
candidate and application-validation calculations. Provider self-tests are
not ChessEcho application validation.

The focused ChessEcho consumer contract check is the `fidenaut-consumer`
validation profile in `.github/agent-workflow.json`. Run it through the pinned
provider's `run-validation` command with the three external-boundary arguments
above. It checks consumer-owned pinning and documentation; it does not run
Fidenaut's provider test suite. ChessEcho does not add a Make target or
permanent CI job for this governed consumer check.

The authoritative provider documentation for the invocation and verification
contract is the pinned
[Fidenaut README](https://github.com/NathanZK/Fidenaut/blob/c01e94575aecb3a3edfd26c35c1c6f2095b29000/README.md#external-consumer-quickstart)
and its
[workflow specification](https://github.com/NathanZK/Fidenaut/blob/c01e94575aecb3a3edfd26c35c1c6f2095b29000/docs/engineering/agent-workflow.md).

New product issues should follow
[`.github/ISSUE_TEMPLATE/product-issue.md`](../../.github/ISSUE_TEMPLATE/product-issue.md).
See [`repository-conventions.md`](repository-conventions.md) for the default
pre-deployment assumption and baseline-first migration convention.
