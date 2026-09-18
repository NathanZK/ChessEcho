# ChessEcho repository conventions

This document is the single source of truth for the small conventions that
repeatedly caused friction across recent product issues. It is intentionally
short: it records defaults, not a full repository policy.

## Default: ChessEcho is pre-deployment

ChessEcho is assumed to be **pre-deployment** unless an issue explicitly
states otherwise. "Pre-deployment" means there is no production install with
data that must be preserved across a schema change, so there is no deployed
migration history that implementations must treat as immutable.

An issue changes this default only by explicitly stating a deployment state,
for example a line such as:

```text
Deployment state: already deployed; treat existing migrations as immutable.
```

Planners and implementers should treat the absence of such a line as
confirmation that the pre-deployment default still applies; they should not
infer a deployed state from unrelated context.

## Migration convention while pre-deployment

While the pre-deployment default applies, schema evolution is folded into the
baseline migration (`src/main/resources/db/migration/V1__baseline.sql`)
instead of being preserved as a synthetic, never-deployed version ladder
(for example, an unreleased `V2`/`V3`). `#80` (timed training) established
this pattern: schema changes needed for that feature were consolidated into
the baseline rather than added as a new versioned migration.

Once an issue's "Deployment state" line establishes that ChessEcho (or the
relevant environment) is deployed, this convention no longer applies for that
work: new schema changes must be added as new, additive, versioned Flyway
migrations, and the existing baseline must not be rewritten.

## Minimal issue contract

New product issues should use the structure in
[`.github/ISSUE_TEMPLATE/product-issue.md`](../../.github/ISSUE_TEMPLATE/product-issue.md):
product intent, acceptance criteria, repository constraints (including
deployment/migration state), and validation. The template records the
information an implementation agent needs without prescribing architecture.
