# Governed workflow ownership

Fidenaut is the governed workflow provider; ChessEcho is a consumer. The
provider owns state transitions, approval and evidence semantics, candidate
integrity, process supervision, recovery, and publication mechanics. ChessEcho
owns repository-specific identity, target branch, roles, application
validation, and product behavior.

## External runtime boundary

The consumer invokes the pinned Fidenaut entry point with three explicit
inputs:

- `--consumer-root`: ChessEcho worktree, including consumer configuration,
  Git repository state, issue run state, and application validation commands.
- `--provider-runtime-root`: detached Fidenaut checkout at the exact pinned
  revision.
- `--provider-manifest`: external manifest binding the provider repository,
  full revision, runtime-relative paths, Git modes, and SHA-256 identities.

The provider checkout and manifest remain outside the consumer worktree.
Fidenaut verifies those inputs before governed progress and excludes provider
files from consumer candidate and application-validation calculations. It
fails closed when provider identity or integrity cannot be verified. ChessEcho
must not maintain a second controller, launcher, provider copy, or overlay.

The consumer pin and operational command are documented in
[`agent-workflow.md`](agent-workflow.md). The provider's pinned
[architecture and trust-boundary documentation](https://github.com/NathanZK/Fidenaut/blob/c01e94575aecb3a3edfd26c35c1c6f2095b29000/docs/engineering/agent-workflow-architecture.md)
is authoritative for provider mechanics.

## Ownership map

| Concern | Owner |
| --- | --- |
| Governed lifecycle, state, approvals, evidence, candidate integrity, recovery, publication | Fidenaut |
| External runtime and manifest verification | Fidenaut |
| Repository identity, target/base, topology, and GitHub conventions | ChessEcho |
| Roles, repository guidance, and issue conventions | ChessEcho |
| Backend/frontend checks and product behavior | ChessEcho |
| Provider self-tests | Fidenaut |
| Consumer contract checks and application validation | ChessEcho |

The `fidenaut-consumer` validation profile runs the targeted consumer contract
test through the pinned external provider. ChessEcho CI runs only the existing
application checks; it does not run provider self-tests or retain a separate
embedded-workflow test job.
