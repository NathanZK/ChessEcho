# Reviewed plan revisions

Issue #125 provides an **inactive, read-only** policy evaluator over native
#132 evidence for plan snapshots, technical reviews, and revisions.
`scripts/workflow_plan_revision_policy.py` verifies canonical plan units, a
deterministic unified diff, finding/disposition schemas, and evidence
binding/subject links, then deterministically selects the minimum permitted
review mode (`full` or `incremental`) and emits one derived result document. It
never publishes evidence, selects a trust anchor, authenticates an actor,
preserves human approval, mutates #134 state, transitions a lifecycle, or
activates any behavior. #125 imports only `workflow_evidence` and
`workflow_inspector`; future #144 is the only planned composition/activation
owner.

## Commands and API

```python
evaluate_baseline(root, request, designated_plan_binding, designated_review_binding=None)
evaluate_revision(
    root, request, trusted_prior_plan_binding, trusted_prior_review_binding,
    designated_current_plan_binding, designated_revision_binding,
    designated_current_review_binding=None,
)
```

```text
python3 scripts/workflow_plan_revision_policy.py evaluate-baseline \
  --root ROOT --request REQUEST.json \
  --designated-plan-binding SHA256 [--designated-review-binding SHA256]

python3 scripts/workflow_plan_revision_policy.py evaluate-revision \
  --root ROOT --request REQUEST.json \
  --trusted-prior-plan-binding SHA256 --trusted-prior-review-binding SHA256 \
  --designated-current-plan-binding SHA256 --designated-revision-binding SHA256 \
  [--designated-current-review-binding SHA256]
```

`python3 -m scripts.workflow_plan_revision_policy` supports the same two
commands through an explicit `COMMAND_HANDLERS` registry, and both entry
points emit byte-identical canonical output. Every `trusted_*`/`designated_*`
parameter is a 64-lowercase-hex digest compared to its request envelope's
`binding.sha256` before any projection or policy computation; a mismatch is
`stale/designated-binding-mismatch`. "Trusted" and "designated" mean only that
the caller supplied an expected digest — #125 proves exact equality to it and
makes no claim about who selected it, whether it is the latest tip, or whether
it was revoked. Exit code `0` means one canonical result was produced, never
that the plan was approved; callers must branch on `outcome.code` and
`technical_verdict`. Malformed CLI input, JSON, or policy input reaches
`main()` as a typed failure with the correct `OUTCOME_EXIT_CODES[status]`
(`missing`=3, `unsupported`=4, `corrupt`=5, `ambiguous`=6, `stale`=7,
`denied`=8) rather than a traceback.

## Canonical limits

Structured documents use #128 `utf8-json-sort-keys-compact-ascii-v1`, an exact
key set per schema, and reject unknown/missing keys, duplicate JSON keys,
booleans-as-integers, and floats. Limits are checked cheapest-first:

- request bytes ≤ 8 MiB (checked while reading, before JSON parsing);
- each snapshot/review/revision/result document ≤ 2 MiB;
- plan payload ≤ 1 MiB and ≤ 5,000 lines, UTF-8, LF-only, ending in exactly one
  LF (5,000 lines is accepted when every other limit passes; 5,001 is
  `unsupported/plan-too-many-lines`, checked before any diff work);
- diff payload ≤ 4 MiB;
- before constructing `difflib.SequenceMatcher`, `diff_cost =
  sum(old_count[line] * new_count[line])` over shared lines is computed in
  linear time; exceeding 5,000,000 is `unsupported/diff-cost-exceeded` and the
  matcher is never built;
- ≤ 1,000 plan units/findings/dispositions/coverage rows, ≤ 32 distinct
  preserved-source reviews, ≤ 10 direct dependencies per unit.

The dependency-cycle check is an iterative topological sort (a bounded queue,
not recursion), so a 1,000-unit chain completes without recursion depth risk;
a genuine cycle is `ambiguous/dependency-cycle`.

## Evidence documents and binding roles

All V1 evidence is native #132: identical issue/family across every bound
document in one evaluation, `correction: null`, `lineage: {"status":
"original","parent_binding":null}`, `migration: null`, and exactly the allowed
manifest paths as regular `100644` entries. Structured #125 documents are
stored as `workflow_inspector.canonical_bytes(document)` with no trailing
newline; only CLI stdout adds one trailing newline.

| Document | Decision type / ID | Subject | Manifest entries |
|---|---|---|---|
| Plan snapshot | `plan-snapshot` / `snapshot-<snapshot_sha256>` | exact `context.triage_binding` | `plan.md`, `snapshot.json` |
| Plan review | `plan-review` / `review-<review_sha256>` | reviewed plan binding (baseline) or exact revision binding (revision review) | `review.json` |
| Plan revision | `plan-revision` / `revision-<revision_sha256>` | exact prior review binding | `revision.json`, `plan.diff` |

A snapshot's `context` names the #116 issue-snapshot → baseline → triage
chain; #125 verifies the generic #132 identity/decision/subject links between
those three bindings (and that the snapshot's own subject equals the exact
triage binding) without importing #116 or interpreting its classification,
scope, or route. A changed context binding forces full review; #144 owns
verifying the #116 document semantics behind it.

Revision 1 has `predecessor: null`; revision N>1 requires the exact prior
plan/review pair as `predecessor`, and the prior snapshot's own revision must
be N-1. A snapshot loaded only in the prior or a preservation-source position
is checked for schema and its own internal predecessor shape, not re-compared
to the current evaluation's designated pair. Revision numbers are chain-local
ordering only, never a latest-tip or authority claim.

Units are unique, nonempty, and exactly and contiguously tile every plan line
in array order; each unit hash covers its exact line bytes; dependencies are
sorted, unique, existing unit IDs forming an acyclic graph. `review_class` is
`ordinary`, `scope`, `acceptance-criteria`, `architecture`, or
`source-baseline`.

## Diff semantics

The stored diff must be byte-identical to the module's own regeneration —
`difflib.SequenceMatcher(autojunk=False)`, exactly three lines of context, the
fixed labels `a/plan.md`/`b/plan.md`, `@@ -start,count +start,count @@` hunk
headers (always emitting `,count` even for a zero-count range), and
space/`-`/`+`-prefixed lines from `get_grouped_opcodes(3)`. A committed golden
corpus (`_DIFF_GOLDEN_VECTORS`) is self-checked once per process, lazily inside
the public evaluator boundary, so an incompatible runtime returns
`unsupported/diff-algorithm-runtime` instead of failing at import. An
unchanged plan (identical bytes) always produces empty diff bytes and is
rejected as `stale/revision-without-change`, independent of unit-map changes.

`structurally_changed_units` is added ∪ removed ∪ retained-with-changed-hash
unit IDs; `change_row_units` additionally includes every diff-touched unit
(computed by mapping changed/inserted old/new lines through each snapshot's
unit tiling). The revision's `changes` array must contain exactly one sorted
row per `change_row_units` member and no other row; a missing, extra, or
duplicate row is `corrupt/invalid-revision-schema`. A valid mismatch between
the hash-changed and diff-touched sets (ordinary edits can shift later
byte-identical unit boundaries) is the `diff-unit-mapping-mismatch` escalation
reason, not a schema failure.

## Findings, dispositions, and coverage

A finding ID is `finding-<SHA256(canonical {introduced_plan_binding, severity,
category, unit_ids, detail})>` — content identity only, not Reviewer
authentication. `prior_finding_outcomes` on the current review contains
exactly one sorted row per finding in the immediate prior review:

- `remains` requires the finding carried verbatim (ID, plan binding, severity,
  category, unit IDs, detail unchanged) in current findings;
- `resolved` requires the finding ID absent from current findings;
- `superseded` requires the old ID absent, a `replacement_finding_id` present
  in current findings introduced against the current plan, with severity equal
  to or greater than the prior finding's (`info < warning < blocking`).

Any other combination (recurrence after `resolved`, a mutated `remains`, or a
lower-severity `superseded` replacement) is `corrupt/invalid-review-schema` or
`corrupt/carried-finding-missing`. This check runs even for an explicitly
escalated (`full-review-required`) incremental review — escalation waives
coverage completeness, never prior-finding adjudication.

The revision's `dispositions` array must contain exactly one sorted row per
finding in the immediate prior review (status `addressed`, `disputed`, or
`deferred`); missing or extra rows are `corrupt/invalid-revision-schema`. A
disposition is a Planner claim only — the current Reviewer decides whether a
finding is actually resolved, remains, or is superseded.

Every coverage row's `(unit_id, content_sha256)` must equal an exact unit ID
and content hash from the review's own plan snapshot; unknown units and
stale/foreign hashes are `corrupt/invalid-review-schema`, checked before
coverage completeness or verdict evaluation, for `full`, `incremental`,
`preserved`, and escalated coverage alike. A full review requires
method-`full` coverage of every unit; a completed incremental review requires
every policy-required unit at method `incremental` and every other unit at
`incremental` or `preserved`; an escalated incremental review's
coverage contains only the units actually inspected, all at method
`incremental` with a null source.

## Incremental versus full review

Incremental review requires, together: the immediate prior verdict is exactly
`needs-revision`; issue/family/#116 context bindings are byte-identical;
revision is exactly prior + 1 and names the exact designated predecessor;
unit IDs/order/titles/classes/dependencies are unchanged; every changed unit
is `ordinary`; every declared change impact is `local`; the diff regenerates
and its hash-changed/diff-touched unit sets are identical; every prior finding
has exactly one disposition; and the prior review has complete, anchored
coverage. Its minimum scope is changed units, plus their direct dependencies
and dependents, plus every unit cited by a prior finding or its disposition —
a one-hop bidirectional neighborhood, not an unreviewed semantic transitive
closure.

Full review is mandatory (in addition to any schema/evidence failure) for: an
accepted or escalated immediate prior review; a changed #116 context binding;
a changed `scope`/`acceptance-criteria`/`architecture`/`source-baseline` unit;
`full-review-required` impact on any change row; any unit
addition/deletion/reorder/retitle/reclassification/dependency-graph change; a
valid structural/diff mapping mismatch; or more than 32 distinct
preserved-source reviews. `escalation_reasons` is a sorted subset of a closed
11-value set (see `ESCALATION_REASONS`); `sensitive-unit-changed` in
particular uses the **prior** snapshot's review class for a removed unit and
the **current** snapshot's class for an added or retained unit.

Escalation triggers are computed first; only if none fire does #125 attempt
preservation derivation, so full mode never derives or projects a
preservation-source review. If unresolved after that, exceeding the 32-source
budget adds the sole reason `preservation-fanout-exceeded` without deriving
further sources.

## Preservation anchoring

For each byte-identical unit outside the required scope, the policy-eligible
source is derived without guessing: the designated prior review binding
itself when its own row for that unit is `full`/`incremental`, or that row's
already-validated `source_review_binding` when it is `preserved` (a bounded,
one-level lookup — a `preserved`-to-`preserved` chain is never followed).
`_source_review` caches by binding digest, so citing the same source from
multiple preserved rows costs one projection, not one per row. A supplied
current review's preserved row must equal the policy-derived
`(unit_id, content_sha256, source_review_binding)` triple exactly; a
different (sibling) source, wrong hash, or an escalated/unbounded source
review is `denied/unanchored-preserved-coverage`. `eligible_preserved_units`
is therefore always ⊇ `verified_preserved_units`; both are empty for baseline
results and for any full-mode result.

## Results and outcome codes

`chess-echo-plan-revision-policy-result-v1` reports `review_mode`,
`escalation_reasons`, `changed_units`, `required_review_units`,
`eligible_preserved_units`, `verified_preserved_units`, `disposition_status`
(`not-applicable` for baseline, `complete` for revision), and
`technical_verdict` (`accepted`/`needs-revision`/`full-review-required`/null).
Resolved outcome codes are disjoint:

- `review-required` — valid input, no current review supplied;
- `technical-review-accepted` / `technical-review-needs-revision` — a
  supplied review meeting the required mode;
- `technical-review-escalated` — a supplied incremental review's verdict is
  `full-review-required` when the policy minimum was already incremental (if
  policy already requires full mode, a supplied incremental review is instead
  `denied/insufficient-review-mode`).

`required_review_units` and `dependency_assessment.reviewed_units` follow
plan-unit order; `changed_units`, finding/disposition `unit_ids`, and both
preserved arrays are UTF-8-sorted by unit ID; `activation.unverified` is a
fixed constant tuple. `authority` is always the literal
`"inactive-derived-policy"`.

## Content identity versus authority

| Property | Established by #125? |
|---|---:|
| Plan/snapshot/diff content identity | Yes |
| Review coverage preservation (not approval inheritance) | Yes, narrowly |
| Actor authentication | No |
| Human approval | No |
| Latest-revision authority | No |
| Freshness / revocation / replay prevention | No |
| Lifecycle state | No |

Reviewer/Planner actor strings are attribution only. No #125 document or
result is a `plan-approval` binding, and #125 never changes #134's fixed DAG.

## #116 and #134 composition, and the #144 boundary

#125 verifies only the generic #132 subject chain behind a snapshot's
`context`; it never re-validates #116's classification, scope, or route, and
#116 targeted checks are never accepted as #125 review evidence. #125 never
imports `workflow_policy` (#134) and never emits or requires a
`plan-approval` binding. Future #144 must, in order: acquire trusted #116/#134
tips; ensure any existing approval is explicitly revoked before revision
work; designate the exact prior/current plan-review-revision inputs; invoke
#125 and arrange the required review; obtain explicit human approval bound to
the technically accepted plan; publish a new `plan-approval` binding through
#132; invoke #134 with that replacement root; and publish/apply the resulting
authority. Until #144 exists, every #125 result is inactive and cannot affect
#134 state.

## Non-goals

No lifecycle states, transitions, approval gates, or orchestration; no actor
authentication, latest-tip selection, revocation, freshness, or replay
prevention; no alternate CAS/canonicalizer/hashing scheme; no changes to #116
classification or #134 DAG/invalidation/budgets; no automatic semantic
understanding of plan text; no incremental review for
added/deleted/reordered/reclassified units in V1; no inherited plan approval;
no migrated-evidence acceptance (a migrated plan requires a fresh native
full-reviewed baseline); no UI, diff viewer, or final-validation caching. #125
does not inspect, migrate, repair, resume, or depend on frozen #115.
