# Orchestration Authority Repair

`scripts/workflow_authority_repair.py` is the independently callable repair
boundary for the inactive replacement orchestration pointer. It does not repair
legacy durable-v4 authority and does not run or advance the orchestrator.

## Commands

```bash
python3 scripts/workflow_authority_repair.py prepare ISSUE --root ROOT \
  --checkpoint CHECKPOINT --request REQUEST > bundle.json
python3 scripts/workflow_authority_repair.py dry-run --root ROOT --bundle bundle.json
python3 scripts/workflow_authority_repair.py apply --root ROOT --bundle bundle.json
python3 scripts/workflow_authority_repair.py recover ISSUE --root ROOT
```

`prepare` and `dry-run` are read-only. `apply` and `recover` may write only:

```text
<git-common-dir>/chess-echo-agent-workflow/
  orchestration/issues/<issue>/pointer.json
  orchestration/issues/<issue>/authority.lock
  orchestration/issues/<issue>/repair-journal.json
  orchestration-repair-receipts/sha256/<two>/<remaining>
```

## Authority and authorization

The only operation is `restore-checkpoint`. Its target is reconstructed from a
complete, canonical `chess-echo-orchestration-checkpoint-v1`; callers cannot
supply a binding, generation, state, patch, path, or arbitrary pointer outside
that checkpoint. `workflow_authority.verify_checkpoint` verifies the complete
state/evidence/predecessor chain through `workflow_evidence` and
`workflow_inspector`. Repair never enumerates CAS and never chooses by
generation, recency, or availability.

The canonical request must bind all of:

- the issue and fixed operation;
- nonempty operator identity and reason;
- `RESTORE ISSUE <issue> EXACT PRE-CORRUPTION ORCHESTRATION POINTER`;
- `exact-independently-captured-pre-corruption-checkpoint`;
- `noncooperating-orchestration-pointer-writers-stopped`;
- the exact missing, malformed, or already-target source observation;
- the exact target pointer byte record; and
- the checkpoint, selected authority, state digest, family, generation, phase,
  policy-state binding, and supervision-policy binding.

These fields are an explicit authorization record, not identity
authentication. The checkpoint must have been captured independently before
corruption. Without that trust anchor, safe repair is impossible.

## Preconditions and denied cases

Repair accepts only an absent pointer, an exact malformed regular pointer, or
the exact target. A different syntactically valid pointer is an unsafe rollback
and is denied even when the supplied checkpoint names an older valid
generation. An unsupported pointer format is not corruption: older repair code
must deny it rather than overwrite a potentially valid newer authority format.
A retained expected-tip authority bundle, not repair, must finish a known
interrupted commit. The presence of an authority-commit temporary fails closed
rather than guessing its source or target.

Issue #115 is denied before authority lookup. Resolved legacy authority is
`legacy-authority-owned`; any unreadable, corrupt, projection-only, or otherwise
unresolved legacy ownership is `legacy-authority-ambiguous`. Dual ownership is
therefore denied even when the orchestration pointer already equals the target.
Migrated-v4 authority remains unsupported. Symlinks, FIFOs, nonregular
ancestors, unsafe locks, changed files, and third-value races fail closed.

The quiescence assertion means every writer that does not honor
`authority.lock` is stopped for the complete `apply` or `recover` call.
Cooperating expected-tip writers and repair serialize on that same per-issue
lock. Portable filesystem replacement is not a lock-free compare-and-swap
against an actor that violates this requirement.

## Durable transaction

For an applicable source, `apply`:

1. publishes and fsyncs a fixed-path journal containing the complete bundle;
2. re-verifies the target checkpoint and all immutable objects;
3. writes and fsyncs a same-directory temporary, performs an immediate
   byte-exact source recheck, atomically replaces the pointer, and fsyncs the
   directory;
4. verifies the live pointer resolves to the exact authorized checkpoint;
5. publishes an immutable content-addressed receipt; and
6. removes the journal and fsyncs its directory.

Recovery accepts only the journal's exact source or exact target. Source
resumes validation and commit; target resumes postcommit verification and
receipt publication. Missing, changed, malformed, or third-value state relative
to that journal conflicts and retains the journal. Interruption seams cover
journal publication, target-object verification, pointer publication,
postcommit verification, receipt publication, and journal removal.

The operation changes no immutable evidence. It cannot synthesize approval,
change phase or candidates, evaluate policy, activate a route, migrate legacy
state, perform rollback, or back-convert replacement authority.
