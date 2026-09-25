# Fidenaut consumer maintenance

ChessEcho consumes the pinned Fidenaut provider runtime directly. Do not
reintroduce a ChessEcho-owned workflow controller, process supervisor,
provider launcher, provider source copy, or runtime overlay.

## Consumer-owned configuration

`.github/agent-workflow.json` remains authoritative for ChessEcho's remote,
target branch, repository-specific roles, bounded command settings, and
application validation profiles. Its `provider_runtime` entry records the
provider repository and exact revision; it does not replace Fidenaut's
external manifest verification.

The provider runtime checkout and `fidenaut-provider-runtime-manifest-v1`
manifest must remain outside the ChessEcho worktree. Every workflow command
must invoke the pinned Fidenaut entry point with separate `--consumer-root`,
`--provider-runtime-root`, and `--provider-manifest` arguments. See
[`agent-workflow.md`](agent-workflow.md) for the pinned file identities and
command example.

## Validation ownership

- The `fidenaut-consumer` profile checks the ChessEcho-owned pin and invocation
  documentation through Fidenaut's external `run-validation` command.
- `.github/agent-workflow.json` retains ChessEcho backend, frontend, and
  full-stack application validation profiles.
- Fidenaut's own regression suite validates the provider and is not a
  ChessEcho application check.
- External provider files and manifest must not enter the ChessEcho candidate
  or consumer validation inputs.
- No dedicated Make target or permanent CI job is provided for the consumer
  contract profile.

For provider implementation invariants and recovery procedures, use the
[pinned Fidenaut maintainer documentation](https://github.com/NathanZK/Fidenaut/blob/c01e94575aecb3a3edfd26c35c1c6f2095b29000/docs/engineering/agent-workflow-maintainer-summary.md).
