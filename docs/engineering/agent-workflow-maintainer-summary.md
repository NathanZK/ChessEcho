# Governed agent workflow: maintainer summary

## Why this workflow exists

Agents produce useful plans, tests, code, and reports. They do not establish
that consequential repository work happened correctly. The workflow is the
independent authority for that claim.

The trust rule is:

> Be tolerant about producer representation; be intolerant about unproven
> repository state, provenance, authorization, and external identity.

## Core invariants

1. **Target is an OID, not a branch name.** Resolve the configured
   authoritative remote, verify repository identity, record the target HEAD,
   and require strict descendant movement for replay.
2. **Approval is evidence-bound.** A plan/test/implementation acknowledgment
   applies only to the exact target, scope, candidate, test boundary,
   validation evidence, and journal transition it approved.
3. **Candidate identity is content, paths, and modes.** Reports and path lists
   are claims. Recompute the candidate from Git; require clean state, approved
   scope, exact tree/content equivalence, and one-commit topology.
4. **Test evidence is a boundary.** `test_commit`, approved test paths/content,
   and explicit applicability are checked again downstream. Production code
   cannot silently change the approved test boundary.
5. **State and journals are not Git truth.** State says where the run is;
   journals prove transition intent and crash boundaries; Git proves commits,
   content, and ancestry. All are rechecked together.
6. **Validation includes its environment.** The authoritative profile, setup
   commands, target, candidate, and check results are part of the evidence.
7. **Target movement invalidates or requires replay.** Use the narrow legal
   path: `reanchor-target` before implementation artifacts,
   `reconcile-candidate` for a safe uncommitted candidate,
   `reconcile-implementation-target` for a committed candidate,
   `reconcile-completed-run` for a completed run, or a recovery command when
   the proof says stale evidence must be discarded.
8. **Recovery is not manual override.** Operators authorize a governed
   transition. The workflow, not the operator, decides whether Git replay is
   clean, what evidence is stale, and which gates reopen.
9. **External publication is reverified.** Push with a lease, reread the live
   PR, and recover pending journals only when the exact PR identity and head
   match.
10. **Fail closed on ambiguity.** Do not infer authorization, ancestry,
    candidate equivalence, or successful publication from a plausible state
    field or agent claim.

## Recovery map

| Situation | Legal mechanism | What it preserves | What it invalidates |
| --- | --- | --- | --- |
| Target advances before implementation artifacts | `reanchor-target` | Plan, scope, valid test evidence | Target-bound downstream references as required |
| Uncommitted candidate survives a descendant target without overlap | `reconcile-candidate` | Candidate and approved boundary after real replay | Validation/review/approval that became stale |
| Committed candidate is interrupted or target advances | `reconcile-implementation-target` | Exact candidate identity and transition journal | Old target binding; requires reconciliation proof |
| Uncommitted candidate genuinely overlaps target | `recover-implementation-target` | Audit history and unaffected upstream evidence | Candidate, validation, implementation review/approval; possibly tests |
| Completed run meets exact replay criteria | `reconcile-completed-run` | Approved delta, original PR identity | Old target binding; creates a reconciled commit |
| Completed replay failed only because tools were unavailable and an auto-created child has not progressed | `recover-completed-run-reconciliation` | Immutable original journal and historical evidence | The false auto-classification, via a separate recovery journal |
| Revision PR push is interrupted or remote propagation races | `recover-pr-revision` | Journal and live PR if exact identity matches | Nothing by inference; otherwise remains pending/fails closed |

Never use a broader recovery command because it is more convenient. Genuine
test, implementation, or plan changes require their own governed revision.

## Authority table

| Fact | Authority |
| --- | --- |
| Executable/source identity | Cryptographic/pinned integrity evidence |
| Provider version | Audit label only |
| Target, ancestry, candidate bytes, modes, paths, topology | Git plus verified authoritative remote |
| Test boundary | Approved run evidence and Git rechecks |
| Validation | Authoritative profile, setup evidence, and executed checks |
| Approval | Human acknowledgment bound to the transition evidence; not caller authentication |
| PR identity/head/draft/base/branch | Live GitHub state before and after publication |

When these authorities disagree, stop. Preserve the evidence, diagnose the
invariant that failed, and use the narrow governed recovery if one exists.

## Maintainer review checklist

Before changing workflow code, ask:

* Which trust property does this change establish?
* Which component is authoritative for that fact?
* Can the property be independently observed from Git, configuration,
  validation evidence, or live GitHub state?
* Does target movement make existing approval stale?
* Does the change add a producer-compatibility concern that should remain
  outside governance?
* Does recovery preserve valid evidence and explicitly invalidate stale
  evidence?
* Is there a regression test for the observed failure, including crash and
  external-race cases where applicable?

Do not remove a gate because it is inconvenient. First identify the invariant
it owns and demonstrate that another mechanism owns the same invariant.
