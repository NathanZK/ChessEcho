# Planner -> Reviewer -> Implementer Workflow

ChessEcho uses a repository-scoped, resumable workflow for taking a GitHub issue
through planning, tests, implementation, validation, and a draft pull request.
The active replacement path composes immutable evidence, expected-tip authority,
deterministic policy, fresh runtime reconstruction, bounded process supervision,
a reviewed provider adapter, and explicit human gates. It is entered through
`scripts/workflow_local_host.py`; `scripts/workflow_driver.py` may continue
automatic steps but cannot approve, recover, merge, or create its own lifecycle.

The older `scripts/agent_workflow.py` lifecycle remains in the repository for
legacy runs and compatibility. It is not the architecture described as current
below. Its operating reference is retained later in this document and is
explicitly labeled [Legacy lifecycle reference](#legacy-lifecycle-reference).
This is more than import isolation: replacement initialization refuses an issue
already owned by legacy authority, and every replacement status read fails with
`dual-authority-detected` if both authority models exist. Two state machines
must never be simultaneously authoritative for one issue.

The repository-level `AGENTS.md` “Issue workflow” instructions still describe
the explicitly requested legacy `scripts/agent_workflow.py` lifecycle. They are
not the replacement host's runbook and must not be used to infer replacement
commands or capabilities. Updating that agent-control surface is outside this
documentation-only change.

This guide is the canonical architecture orientation and reference.
Focused engineering documents remain the detailed contracts for individual
trusted mechanisms and policies. Current commands and operator preconditions
are in [Workflow orchestration](workflow-orchestration.md#commands),
[Trusted-local workflow provider](workflow-local-provider.md#activation-and-operation),
and [Bounded workflow driver](workflow-driver.md#invocation). No definitive
operator runbook exists yet; it is tracked by open
[issue #197](https://github.com/NathanZK/ChessEcho/issues/197).

### Suggested reading paths

- **New to the architecture:** read [Why the architecture exists](#why-the-architecture-exists),
  [Five questions to keep separate](#five-questions-to-keep-separate),
  [A simple mental model](#a-simple-mental-model), and
  [What runs today](#what-runs-today).
- **Building or integrating a component:** continue through the
  [current responsibility map](#current-responsibility-and-dependency-map),
  [composition model](#current-composition-without-collapsed-ownership), and
  [module boundaries](workflow-boundaries.md).
- **Reviewing security or correctness:** focus on
  [authority and trust](#authority-trust-and-guarantee-boundaries),
  [provider protocols](#provider-and-candidate-protocol-boundaries), and
  [current non-guarantees](#current-guarantees-non-guarantees-and-unfinished-work).
- **Operating the current path:** use the focused command documents linked
  above; the later [legacy reference](#legacy-lifecycle-reference) applies only
  to `scripts/agent_workflow.py`.

## Why the architecture exists

The workflow coordinates processes and state with different failure modes:

- agents are unreliable workers whose prose and artifacts are candidates, not
  authority;
- repository, branch, worktree, base-ref, and GitHub PR state can change between
  observations;
- commands can fail, time out, be interrupted, or leave uncertain external
  outcomes;
- review and human approval apply to exact evidence, not to a mutable filename
  or a similar-looking later result;
- retry and reopen loops can preserve useful work, but can also repeat forever
  or reuse stale evidence unless dependencies and limits are explicit;
- filesystem replacement can be atomic for one name without making a set of
  files a transaction or guaranteeing storage durability on every filesystem;
  and
- a workflow tool that modifies and certifies itself can amplify ambiguity in
  its own implementation.

Ordinary mutable files, process exit zero, implicit agent success, or one large
state-machine script are insufficient because none independently proves that the
observed bytes, repository revision, review, approval, and intended transition
still belong together. ChessEcho therefore separates:

1. **evidence**, which records exact observed bytes and facts;
2. **authority**, which selects the one permitted lifecycle state;
3. **policy**, which evaluates what may follow from selected evidence;
4. **runtime and supervision**, which reconstruct fresh external facts and bound
   process execution;
5. **provider and host adapters**, which translate one external agent protocol
   into ChessEcho's narrow candidate contract; and
6. **human authorization**, which remains separate from technical review and
   automatic policy satisfaction.

The workflow is not primarily a task runner. It is an authorization and
evidence system for autonomous work. Agent output is a proposal until
independent observation, validation, policy, and authority connect it to an
allowed transition.

### Five questions to keep separate

If the terminology is unfamiliar, start with five ordinary questions:

| Plain-English question | Technical term | ChessEcho answer |
|---|---|---|
| What proves what actually happened? | **Evidence** | Exact plan, review, process, repository, validation, and GitHub-observation bytes are preserved and linked. |
| Where did this artifact come from? | **Provenance and lineage** | Separate records name how evidence was captured and whether it is original, inherited, or replaces earlier evidence. |
| Which one of many valid records is official now? | **Authority** | One expected-tip pointer selects one immutable orchestration-state binding. |
| Where do we stop trusting input automatically? | **Trust boundary** | Agent output, provider traffic, external state, and human-source claims are independently checked by their owning adapters or policies. |
| What happens when required proof is missing? | **Fail-closed behavior** | The workflow refuses or pauses instead of guessing, silently defaulting, or manufacturing success. |

A content-addressed store (**CAS**) gives bytes an identity derived from their
contents: change one byte and the identity changes. That makes later
substitution detectable, but it does not say who produced the bytes or whether
they are approved. **Process supervision** means the workflow launches an
external command with explicit time, output, and process-group limits and
records how it ended; exit zero still does not prove the candidate is valid.
These distinctions explain why ChessEcho needs several mechanisms rather than
one “agent succeeded” flag.

## A simple mental model

The current system is easiest to understand as one control plane around an
untrusted worker:

1. The **replacement orchestrator** chooses at most one next action from
   selected authority.
2. **Policy modules** validate route, plan/review, dependency, convergence, and
   gate semantics. They return deterministic results; they do not select
   authority themselves.
3. The **authority pointer** selects one immutable orchestration-state binding
   by expected tip.
4. The **runtime** reconstructs base-pinned Git, GitHub, executable,
   configuration, authority, and repository facts for each public command.
5. The **supervisor** bounds one process group and records complete typed
   process facts.
6. The **trusted-local host and provider** validate Copilot JSONL and extract
   candidate bytes while preserving the complete raw transport as bound
   evidence; the workflow core decides whether those bytes are a valid
   phase-specific candidate.
7. The **driver** repeats only policy-selected automatic steps and stops at
   human, recovery, and terminal boundaries.

```mermaid
flowchart LR
  Human{{"Human<br/>authorization"}} --> Host["Reviewed local host"]
  Driver["Bounded driver"] --> Host
  Host --> Orchestrator["Replacement orchestrator"]
  Orchestrator --> Policy["Deterministic policy"]
  Orchestrator --> Authority["Expected-tip authority"]
  Orchestrator --> Runtime["Fresh runtime reconstruction"]
  Runtime --> Provider["Copilot provider adapter"]
  Provider --> Supervisor["Process supervisor"]
  Supervisor --> Agent["Untrusted agent"]
  Provider --> Evidence[("Candidate + raw transport<br/>immutable evidence")]
  Orchestrator --> Evidence
  Authority --> Evidence
  Runtime --> External["Git / GitHub / validation"]

  classDef human fill:#ede9fe,stroke:#6d28d9,color:#111827,stroke-width:2px;
  classDef control fill:#dbeafe,stroke:#1d4ed8,color:#111827;
  classDef store fill:#dcfce7,stroke:#166534,color:#111827;
  classDef untrusted fill:#fee2e2,stroke:#991b1b,color:#111827,stroke-width:2px;
  class Human human;
  class Host,Driver,Orchestrator,Policy,Authority,Runtime,Supervisor,Provider control;
  class Evidence store;
  class Agent untrusted;
```

## What runs today

For an implementation issue, the active replacement workflow is:

```text
trusted issue intake and clean base
  -> plan -> independent technical review -> plan gate
  -> tests-only change -> independent technical review -> test gate
  -> implementation -> comprehensive validation -> final technical review
  -> final gate -> publication gate -> draft pull request
  -> fresh PR/repository observation -> completed authority
```

The committed supervision configuration uses four supervised gates: `plan`,
`tests`, `final`, and `pr-publication`. Reviewer acceptance is technical
evidence, never authorization. The final and publication gates are distinct so
approval of implementation evidence cannot silently authorize a later GitHub
mutation. Completion records an authority transition; it does not merge, mark
the draft ready, deploy, or close the issue.

Each gate supports `supervised` or `automatic` mode. In supervised mode, only a
fresh exact GitHub authorization observation can satisfy the challenge. In
automatic mode, deterministic `configured-automatic-v1` policy evidence
satisfies it with no actor, reviewer, agent, or discretionary decision; the
bounded driver may dispatch that policy step. Automatic therefore means
pre-authorized deterministic policy, not inferred human consent or agent
self-approval. All four modes committed in `.github/agent-workflow.json` are
`supervised`. Changing the map requires a separate human-authorized supervision
change. The core orchestrator defines `set-supervision`, but the reviewed
Phase 1 local host does not expose it, so runtime mode changes are not currently
an operable host procedure. Automatic mode has not been exercised by the
authenticated E2Es documented here.

One cross-policy limitation matters: the older #116 implementation-route
requirements still use names such as `explicit-human-plan-approval`,
`explicit-human-test-approval`, and `explicit-human-pr-approval`. The
replacement supervision policy can technically satisfy those gates
automatically, but it does not rewrite that vocabulary. The committed
all-supervised configuration is consistent with both contracts. Until #116's
requirements are versioned or made mechanism-neutral, automatic mode is a
component capability—not a demonstrated end-to-end guarantee that every
implementation-route requirement has coherent semantics.

The active route currently accepts only `implementation`. Design, research, and
documentation classifications exist in the work-type policy but their lifecycle
routes are not activated. **Phase 1** is the implemented trusted-local boundary:
the local operator and reviewed host are trusted, the authorized coding agent
may execute locally, and all agent-produced content remains untrusted.
Phase 1 does not claim hostile same-UID process, filesystem, credential,
authority-store, network, or escaped-descendant isolation. **Phase 2** is the
unimplemented hostile-worker model requiring stronger OS/process, credential,
network, and authority-store containment; it remains
[issue #160](https://github.com/NathanZK/ChessEcho/issues/160).

### Current state machine

The replacement state names encode where new authority may be selected, not
whether an agent process happens to be running:

```mermaid
stateDiagram-v2
  [*] --> PLANNING
  PLANNING --> PLAN_REVIEW: accepted plan candidate
  PLAN_REVIEW --> PLANNING: needs revision
  PLAN_REVIEW --> PLAN_REVIEW: full review escalation
  PLAN_REVIEW --> WAITING_FOR_PLAN_APPROVAL: technical acceptance
  WAITING_FOR_PLAN_APPROVAL --> TEST_IMPLEMENTATION: gate satisfaction
  TEST_IMPLEMENTATION --> TEST_REVIEW: scoped tests candidate
  TEST_REVIEW --> WAITING_FOR_TEST_APPROVAL: technical acceptance
  TEST_REVIEW --> PAUSED: needs revision
  WAITING_FOR_TEST_APPROVAL --> IMPLEMENTATION: gate satisfaction
  IMPLEMENTATION --> VALIDATION: accepted implementation
  VALIDATION --> FINAL_REVIEW: all checks accepted
  FINAL_REVIEW --> WAITING_FOR_FINAL_APPROVAL: technical acceptance
  FINAL_REVIEW --> PAUSED: needs revision
  WAITING_FOR_FINAL_APPROVAL --> PR_PREPARATION: gate satisfaction
  PR_PREPARATION --> WAITING_FOR_PR_PUBLICATION_APPROVAL: fresh clean publication observation
  WAITING_FOR_PR_PUBLICATION_APPROVAL --> PR_PREPARATION: gate satisfaction
  PR_PREPARATION --> COMPLETED: draft reconciled and freshly observed
  PLANNING --> PAUSED: unsupported or failed transition
  PLAN_REVIEW --> PAUSED
  TEST_IMPLEMENTATION --> PAUSED
  IMPLEMENTATION --> PAUSED
  VALIDATION --> PAUSED
  PR_PREPARATION --> PAUSED
  PAUSED --> PLANNING: authorized recovery of planner attempt
  PAUSED --> PLAN_REVIEW: authorized recovery of plan review
  PAUSED --> TEST_IMPLEMENTATION: authorized recovery of test author
  PAUSED --> TEST_REVIEW: authorized recovery of test review
  PAUSED --> IMPLEMENTATION: authorized recovery of implementation
  PAUSED --> VALIDATION: authorized recovery of validation
  PAUSED --> FINAL_REVIEW: authorized recovery of final review
  PAUSED --> PR_PREPARATION: authorized recovery of GitHub attempt
```

Each executable operation is first claimed in authority and later finalized
from an exact immutable result. `PAUSED` and a pending request are not generic
retry states. Cancellation retains the request identity, and recovery requires
a separate mandatory-human challenge. The workflow never interprets a missing
result or operator restart as permission to execute again.

Plan review may return directly to planning or remain in plan review because
the plan-revision policy defines those bounded technical outcomes. A rejected
test or final review instead pauses because no active replacement transition
interprets that rejection as permission to alter already selected downstream
evidence. Recovery first opens a mandatory-human challenge and then returns
only to the safe phase derived from the exact recorded operation.

### Why the stages are separate

| Boundary | Failure class it prevents |
|---|---|
| Issue intake and triage | Acting on mutable, wrong-repository, pull-request, frozen, or ambiguously classified input |
| Plan then technical review | Coding from an unexamined design or from invented source/API assumptions |
| Plan gate | Treating reviewer readiness or agent confidence as human authorization |
| Tests before production | Making behavior changes without first fixing the observable contract and scope |
| Test review and test gate | Letting the implementation author silently weaken or self-approve the test contract |
| Implementation scope check | Accepting a clean-looking candidate whose independent repository observation has no required executable change or includes out-of-scope paths |
| Comprehensive validation | Equating process success, candidate validity, or targeted checks with repository correctness |
| Final technical review | Letting the implementation author certify the same evidence it produced |
| Final gate | Carrying technical review forward as authorization for the final repository/config snapshot |
| Publication gate | Treating approval of code as permission for a later external GitHub mutation |
| Fresh PR/repository observation | Completing on a moved head, edited draft, wrong base, stale authorization, or uncertain write |

The ceremony is deliberately proportional to consequential autonomous changes.
It adds latency, code, evidence volume, and operator work and is not a template
for every ordinary local task. But removing a gate without naming the failure
it prevents makes the trust model implicit. A useful maintenance test is:
**if a future engineer cannot explain what failure a gate prevents, either the
gate is unnecessary or its rationale is undocumented.**

### Gate anatomy

All gates bind one immutable challenge to exact subjects and a repository
observation. The selected satisfaction is then revalidated during later history
revalidation; prior-stage prose is never enough.

| Gate | Artifact and bound evidence | Satisfied by | Enables | Failure prevented |
|---|---|---|---|---|
| `plan` | Exact plan snapshot, technical plan review, and plan-review repository observation | Fresh GitHub authorization in `supervised` mode or deterministic automatic decision in `automatic` mode | A `plan-approval` node and `TEST_IMPLEMENTATION` | Coding against an unreviewed, changed, or merely agent-endorsed plan |
| `tests` | Exact test manifest, technical test review, and test-manifest repository observation | Selected supervised or automatic gate satisfaction | A `test-approval` node and `IMPLEMENTATION` | Production work against tests that changed, escaped scope, or were self-approved |
| `final` | Exact final review, comprehensive validation, and validated repository observation | Selected supervised or automatic gate satisfaction, with fresh local re-observation | `PR_PREPARATION` and retained final-gate satisfaction | Treating process success or reviewer acceptance as authorization of a stale final revision |
| `pr-publication` | Final-gate satisfaction, final review, validation, and a fresh clean publication observation | Selected supervised or automatic gate satisfaction, rechecked before the write | One exact draft-PR claim; after reconciliation, completion | Letting code approval silently authorize a later or changed GitHub mutation |

The active replacement CLI has no gate-reject or approval-revocation command.
At a waiting gate, a human who declines approval leaves the challenge waiting;
silence is not converted into a transition. `cancel` applies only to a pending
executable attempt, not to human or automatic gate challenges. There is no
public reopen operation in the active path. A failed supported transition may
enter `PAUSED`, from which two-step human-authorized recovery can resume only
the recorded operation's safe phase. The broader transition vocabulary accepted
by the authority schema does not make every named transition publicly
executable.

Cancellation has a separate edge not expanded in the diagram: an executable
claim may become `cancel-requested`, and authorized recovery may resume its safe
phase without first creating a `PAUSED` successor. The exact pending request
remains selected throughout; cancellation never creates permission to execute
it again.

## Core terms

These definitions are the minimum needed to read the detailed architecture. The
[Glossary](#glossary) later provides a broader operational reference.

| Term | Meaning |
|---|---|
| **Active replacement lifecycle** | The transitions composed by `workflow_orchestrator.py`, selected by `workflow_authority.py`, and activated through the reviewed local host. |
| **Legacy lifecycle** | The older `agent_workflow.py`/`workflow_kernel.py` path retained for legacy compatibility; it is not imported by the replacement orchestrator. |
| **Policy evaluator** | A deterministic module that validates and derives a result. The orchestrator may publish and compose that result, but the policy module cannot select authority itself. |
| **Trusted primitive** | A narrow implementation is trusted only for its documented mechanism. This does not authenticate its caller or make caller-selected input authoritative. |
| **Untrusted candidate** | Agent output, inline documents, process output, or a caller-designated digest before the owning verifier establishes its exact contract. |
| **Trusted/designated digest** | An expected byte identity supplied out of band. The receiving component proves equality, not who selected it, whether it is latest, or whether it has been revoked. |
| **Authority** | Exact state selected by the active authority mechanism. A digest, fingerprint, checkpoint, review, or process result alone is never authority. |
| **Activation boundary** | The authority commit that makes verified evidence or a policy result affect the selected lifecycle. Only the orchestrator composes this; policy and evidence modules cannot cross it alone. |
| **Evidence** | Exact bytes and structured records used to support a workflow decision, such as a plan, review, test report, validation result, or repository observation. |
| **Content-addressed storage (CAS)** | Storage whose object name is derived from a digest of its bytes. Identical bytes share an identity; the digest does not identify an approved producer. |
| **Manifest** | Canonical list of evidence paths, file kinds, modes, sizes, and payload references. It answers “what bytes make up this evidence?” |
| **Provenance** | Separate record of how, when, and from where evidence was captured. |
| **Lineage** | Parent relationship showing whether evidence is original, inherited unchanged, or a replacement. |
| **Evidence binding** | Immutable graph root that connects one decision and subject to a manifest, provenance, lineage, and optional migration metadata. It is not the same as pinning a workflow to a Git revision. |
| **Git revision pinning** | Recording the exact base and `HEAD` commits used for validation, review, and PR creation so later Git movement cannot silently reuse stale evidence. |
| **Projection** | Materialized state/history files or a derived view of canonical records. Whether a projection is authoritative depends on the owning component. |
| **Trust anchor** | An expected current identity obtained independently of the data being evaluated. A policy can verify equality to it but cannot choose it for itself. |
| **Invalidation** | Removing evidence from the active dependency set because something it depends on changed, while retaining the old immutable record for audit. |
| **Convergence** | Bounded progression from identifying a cause through applying and verifying a fix; retry exhaustion escalates instead of looping forever. |
| **Run formats v1-v4** | Successive stored formats of the legacy local workflow. Version 4 is the legacy terminus and adds the committed state/history integrity envelope; these are not replacement-orchestrator versions. |
| **Protocol immutability** | Public CAS writers never overwrite conflicting bytes and readers recheck object identity. It is not tamper-proof storage against an actor with filesystem write access. |
| **Historical evidence** | Public issues, PRs, commits, historical source, and postmortem observations used to explain evolution. They do not govern a current run. |

## Historical pre-activation component map (superseded)

### Historical component map

The following diagram is preserved as the architecture snapshot immediately
before replacement activation. Its `ACTIVE TODAY`, `INACTIVE`, and `FUTURE`
labels describe that historical point, not current status. It explains the
cutover problem the replacement had to solve without pretending the legacy and
replacement paths were one state machine. Use the current diagram in
[A simple mental model](#a-simple-mental-model) for present-day flow.

<details>
<summary>Expand the historical pre-activation component map</summary>

```mermaid
flowchart TB
  subgraph Evolution["Historical pressure and response - context, never authority"]
    E99["PR #99<br/>initial gated lifecycle"] --- E79["#79 work-shape mismatch<br/>#85 terminal correction"]
    E79 --- E120["#117 correction runs<br/>#120 bounded validation + integrity"]
    E120 --- E115["#115 failed monolithic<br/>evidence attempt - FROZEN"]
    E115 --- ECore["#128-#134<br/>trusted-core decomposition"]
    ECore --- EPolicy["#116 + #125<br/>inactive policy layers"]
    EPolicy --- E126["#126<br/>architecture consolidation"]
    E126 --- E144["#144 future<br/>composition + activation"]
  end

  subgraph Roles["People and unreliable workers"]
    Human{{"Human authorization"}}
    Orchestrator["Orchestrator"]
    Planner["Planner"]
    Reviewer["Reviewer"]
    Implementer["Implementer<br/>(Test Author during TEST_IMPLEMENTATION)"]
  end

  subgraph Legacy["ACTIVE TODAY - legacy lifecycle authority"]
    LegacyCLI["agent_workflow.py<br/>lifecycle, gates, corrections,<br/>validation, Git/GitHub"]
    Plan["PLANNING ⇄ PLAN_REVIEW"]
    PlanGate{{"WAITING_FOR_PLAN_<br/>HUMAN_APPROVAL"}}
    Tests["TEST_IMPLEMENTATION ⇄ TEST_REVIEW<br/>Implementer acts as Test Author"]
    TestGate{{"WAITING_FOR_TEST_<br/>HUMAN_APPROVAL"}}
    Impl["IMPLEMENTATION → VALIDATION"]
    Final["FINAL_REVIEW"]
    Draft["DRAFT_PR_CREATED"]
    PRGate{{"WAITING_FOR_PR_<br/>HUMAN_APPROVAL"}}
    Approved["PR_APPROVED"]
    Reopen["explicit reject / reopen<br/>clears dependent evidence"]
    CorrectionFork["start-correction<br/>immutable parent + child"]
    CorrMeta["metadata-only → PR gate<br/>inherits all except PR approval"]
    CorrImpl["implementation-only → IMPLEMENTATION<br/>inherits approved plan + tests"]
    CorrTest["test-contract → TEST_IMPLEMENTATION<br/>inherits approved plan"]
    CorrArch["architecture → PLANNING<br/>inherits nothing"]
    Kernel["workflow_kernel.py<br/>legacy v4 serialization,<br/>integrity, lock, per-file replace"]
    LegacyFiles[("worktree run projections<br/>state / history / integrity<br/>artifacts / validation logs")]
    External["Git + GitHub + configured<br/>local validation subprocesses"]
  end

  subgraph Trusted["IMPLEMENTED - independently callable trusted substrate"]
    Inspector["workflow_inspector.py<br/>read-only verification + checkpoint"]
    Repair["workflow_repair.py<br/>legacy explicit bundle + journaled repair"]
    AuthorityRepair["workflow_authority_repair.py<br/>checkpoint-bound pointer restoration"]
    CAS["workflow_cas.py<br/>protocol-immutable publication"]
    Evidence["workflow_evidence.py<br/>manifest + provenance + lineage + binding"]
    Migration["workflow_migration.py<br/>deterministic conversion + publication"]
    Supervisor["workflow_supervisor.py<br/>bounded process-group execution"]
    Durable[("durable pointer / index / CAS<br/>under Git common directory")]
  end

  subgraph Policies["DIRECTLY CALLABLE BUT INACTIVE - derived results only"]
    P134["Dependency / convergence policy<br/>(issue #134; inactive)"]
    Invalidation["minimal dependency closure<br/>max 2 root-changing cycles<br/>then decomposition-required"]
    Convergence["UNKNOWN → CAUSE → FIX → APPLIED<br/>→ TARGETED_VERIFIED → CLOSED<br/>max 3 retries per stage<br/>then human-recovery-required"]
    P116["Work-type policy<br/>(issue #116; inactive)"]
    Triage["exact work-type-triage evidence binding<br/>external publication/designation required<br/>not active authority"]
    P125["Plan-revision policy<br/>(issue #125; inactive)"]
  end

  Future["#144 THIN ORCHESTRATOR<br/>NOT IMPLEMENTED / NOT ACTIVE"]

  Orchestrator -->|"active command dispatch"| LegacyCLI
  Planner -->|"candidate plan"| Plan
  Reviewer -->|"technical reviews"| Plan
  Reviewer --> Tests
  Reviewer --> Final
  Implementer -->|"tests before production"| Tests
  Implementer -->|"implementation + validation"| Impl
  Human ==>|"approve / reject plan"| PlanGate
  Human ==>|"approve / reject tests"| TestGate
  Human ==>|"approve / reject PR"| PRGate
  Human ==>|"authorize reopen / correction"| Reopen
  Human ==> CorrectionFork

  LegacyCLI ==> Plan
  Plan ==>|"Reviewer ready"| PlanGate
  PlanGate ==>|"approved"| Tests
  Tests ==>|"Reviewer ready"| TestGate
  TestGate ==>|"approved"| Impl
  Impl ==>|"validation passes"| Final
  Final ==>|"Reviewer ready"| Draft
  Draft ==> PRGate
  PRGate ==>|"approved"| Approved
  Reopen ==>|"plan change"| Plan
  Reopen ==>|"test-contract change"| Tests
  Reopen ==>|"implementation change"| Impl

  PRGate ==> CorrectionFork
  Approved ==> CorrectionFork
  CorrectionFork ==> CorrMeta
  CorrectionFork ==> CorrImpl
  CorrectionFork ==> CorrTest
  CorrectionFork ==> CorrArch
  CorrMeta ==> PRGate
  CorrImpl ==> Impl
  CorrTest ==> Tests
  CorrArch ==> Plan

  LegacyCLI --> Kernel
  Kernel -->|"envelope-last projection writes"| LegacyFiles
  LegacyCLI --> External

  Inspector -->|"read only"| Durable
  Inspector -->|"repeated repository observations"| External
  Repair -->|"checkpoint + exact preconditions"| Inspector
  Repair --> CAS
  Repair -->|"pointer replace is repair commit point"| Durable
  AuthorityRepair --> Inspector
  AuthorityRepair --> Authority
  AuthorityRepair --> CAS
  AuthorityRepair -->|"same authority lock + exact pointer bytes"| Durable
  Evidence --> CAS
  Evidence -->|"binding published last"| Durable
  Migration --> Inspector
  Migration --> Kernel
  Migration --> Evidence
  CAS --> Durable
  P134 --> Inspector
  P134 --> Evidence
  P134 --> Migration
  P116 --> Inspector
  P116 --> Evidence
  P116 --> Supervisor
  P125 --> Inspector
  P125 --> Evidence

  P134 -.-> Invalidation
  P134 -.-> Convergence
  P116 -.->|"derived result relationship;<br/>does not publish"| Triage
  Triage -.->|"plan policy checks generic identity<br/>and subject links only"| P125

  P134 --o Future
  P116 --o Future
  P125 --o Future
  Human --o Future
  LegacyCLI --o Future
  Future --o Inspector
  Future --o Repair
  Future --o Evidence
  Future --o Migration
  Future --o Supervisor

  ECore --- Inspector
  EPolicy --- P116

  subgraph Legend["Legend"]
    LH1["history"] --- LH2["history: chronology/context only"]
    LA1["active"] ==>|"thick: active lifecycle transition"| LA2["active"]
    LT1["implemented"] -->|"solid arrow: direct call/read/write"| LT2["implemented"]
    LI1["inactive"] -.->|"dotted: derived, not authority"| LI2["inactive"]
    LF1["future"] --o LF2["open-circle: future obligation only"]
  end

  classDef historical fill:#f3f4f6,stroke:#6b7280,color:#111827;
  classDef active fill:#fee2e2,stroke:#991b1b,color:#111827,stroke-width:2px;
  classDef trusted fill:#dbeafe,stroke:#1d4ed8,color:#111827;
  classDef inactive fill:#fef3c7,stroke:#92400e,color:#111827,stroke-dasharray:5 5;
  classDef future fill:#ffedd5,stroke:#c2410c,color:#111827,stroke-dasharray:8 4;
  classDef human fill:#ede9fe,stroke:#6d28d9,color:#111827,stroke-width:2px;
  classDef store fill:#dcfce7,stroke:#166534,color:#111827;
  class E99,E79,E120,E115,ECore,EPolicy,E126,E144,LH1,LH2 historical;
  class LegacyCLI,Plan,Tests,Impl,Final,Draft,Approved,Reopen,CorrectionFork,CorrMeta,CorrImpl,CorrTest,CorrArch,Kernel,LegacyFiles,External,LA1,LA2 active;
  class Inspector,Repair,CAS,Evidence,Migration,Supervisor,Durable,LT1,LT2 trusted;
  class P134,Invalidation,Convergence,P116,Triage,P125,LI1,LI2 inactive;
  class Future,LF1,LF2 future;
  class Human,PlanGate,TestGate,PRGate human;
  class LegacyFiles,Durable store;
```

### What the historical arrows meant

| Flow | Meaning and limit |
|---|---|
| Historical `---` ribbon | Chronology and motivation only. A line from historical pressure to a landed component is not a runtime dependency or authority edge. |
| Thick active lifecycle arrows | Currently enforced transitions, approval gates, reopen paths, and correction entry routes in `agent_workflow.py`. |
| Roles -> legacy CLI | Agents produce candidate artifacts in the state allowed for their role. Reviewer readiness is technical evidence, not human authorization. The Human arrow is distinct because only explicit human commands cross approval gates. |
| Legacy CLI -> kernel -> projections | The legacy CLI owns lifecycle meaning. The kernel supplies canonical bytes, integrity checks, advisory locks, and atomic replacement of each projection file. State, history, and integrity are not replaced as one filesystem transaction; the integrity envelope is written last as the commit marker and recovery handles supported interruptions. |
| Legacy CLI -> Git/GitHub/validation | The active path observes and mutates external state directly and runs configured validation with `subprocess.run`. It does not use the new supervisor and its captured output is not size-bounded. |
| Inspector -> durable store/Git | The inspector independently verifies supported durable authority and repeats mutable Git observations before emitting a checkpoint. A checkpoint is a deterministic observation and possible precondition, not approval or permanent freshness. |
| Repair -> inspector/CAS/pointer | `prepare` and `dry-run` are read-only; explicit `apply`/`recover` use a canonical bundle, advisory repair lock, journal, immutable object publication, source recheck, and one pointer replacement commit point. Non-cooperating legacy writers must be quiescent. |
| Evidence -> CAS -> binding | Payloads, semantic manifest, and provenance are published before the binding. Binding-last prevents a successful partial graph, but an interruption may leave unreachable objects. |
| Migration -> inspector/kernel/evidence | Migration accepts enumerated exact source bytes or a durable checkpoint selection, rebuilds a deterministic plan, rechecks durable preconditions, and publishes evidence. It does not activate a lifecycle, pointer, or policy. |
| Dotted policy edges | Dependency/convergence evaluation and the work-type-triage/plan-context relationship are derived checks only. The plan policy validates the triage binding's generic identity/subject links and leaves work-type semantics to future composition. |
| Open-circle edges to/from the future orchestrator | Future handoff obligations only: consume policy results, call narrow trusted interfaces, bind human authorization, and cut over without simultaneous authorities. They are not current calls. |

</details>

## Current responsibility and dependency map

| Area | Owner and current status | Owns | Deliberately does not own |
|---|---|---|---|
| Replacement composition | [`workflow_orchestrator.py`](workflow-orchestration.md); active through reviewed host | One-step lifecycle composition, policy/result publication, exact gate transitions, pending-operation claims and finalization | Primitive evidence verification, pointer mechanics, provider transport, candidate-schema ownership, direct process/Git/GitHub access |
| Candidate resume boundary | [`workflow_orchestrator_resume.py`](workflow-orchestration.md#candidate-contract); active through orchestrator and verified by host | Exact candidate decoding and phase-specific schemas, plan/review/implementer field validation, final PR-body contract, pending-result reconstruction helpers | Copilot transport/FSM compatibility, lifecycle selection, authority commit |
| Legacy lifecycle | `scripts/agent_workflow.py`; retained compatibility path | Legacy v1-v4 states/events, approvals, corrections, validation, adoption, and projection recovery | Replacement authority, policy composition, runtime reconstruction, provider transport |
| Legacy kernel | [`workflow_kernel.py`](workflow-boundaries.md); active through legacy CLI | Legacy v4 paths, canonical projection bytes, envelope/transaction checks, per-run advisory lock, per-file replacement, envelope-last publication | Lifecycle semantics, Git/GitHub, process execution, durable-store authority |
| Inspector/checkpoint | [`workflow_inspector.py`](workflow-inspector.md); independently callable, read-only | Pointer/index/run verification, supported object graph, repeated minimal repository observations, canonical checkpoint | Migration, repair, lifecycle-history revalidation, approval, permanent freshness |
| Repair | [`workflow_repair.py`](workflow-repair.md); independently callable; mutation only through explicit operations | Canonical repair bundles, exact operation allowlist, journal, pointer commit, immutable audit receipt, recovery | Lifecycle transition, hidden recovery, lock-free CAS, automatic conflict resolution |
| Process supervisor | [`workflow_supervisor.py`](workflow-supervisor.md); active through runtime/provider | One bounded process group, deadline, independent stream limits, optional sink, observed byte/digest accounting, TERM/KILL sequence | Retry or acceptance policy, escaped descendants, CPU/memory/network quotas, scheduling |
| Content-addressed storage | `workflow_cas.py`; used by repair/evidence/migration | Create-exclusive temporary writes, fsync ordering, hard-link publication, collision verification, concurrent identical idempotence | Binding semantics, pointers, lifecycle, tamper-proof storage, multi-object transactions |
| Evidence | [`workflow_evidence.py`](workflow-evidence.md); independently callable and consumed by other new layers | Semantic manifest, separate provenance, lineage, binding, verified derived projection | Trust-anchor selection, actor authentication, invalidation/revocation, lifecycle acceptance |
| Migration | [`workflow_migration.py`](workflow-migration.md); independently callable | Exact supported-source conversion, canonical plan, durable precondition recheck, immutable publication, binding-last commit | Source guessing, pointer/projection mutation, implicit repair, activation |
| Dependency and convergence policy | [`workflow_policy.py`](workflow-policy.md), issue #134; active through orchestrator | Fixed evidence dependency graph, minimal invalidation, correction roots, bounded convergence and deterministic escalation | Trusted-tip acquisition, publication/application, lifecycle transition |
| Work-type policy | [`workflow_work_type_policy.py`](workflow-work-type-policy.md), issue #116; implementation route active | Four classifications, frozen profiles/limits, supervised targeted checks, structural completion assessment | Authority selection, comprehensive final validation, freshness; non-implementation routes remain unactivated |
| Plan-revision policy | [`workflow_plan_revision_policy.py`](workflow-plan-revisions.md), issue #125; active through orchestrator | Exact plan/diff/review evidence, full versus incremental review, bounded coverage preservation, finding dispositions | Human approval inheritance, latest-tip selection, authority mutation |
| Supervision policy | [`workflow_supervision_policy.py`](workflow-supervision-policy.md), issue #165; active through orchestrator | Four configurable gate modes and exact satisfaction semantics | Human authentication, evidence publication, authority selection, mandatory-human operation configuration |
| Authority selector | [`workflow_authority.py`](workflow-authority.md); active | Expected-tip pointer, complete predecessor verification, one selected immutable orchestration state | Lifecycle semantics, policy evaluation, process execution |
| Runtime and reconstruction | [`workflow_runtime.py`](workflow-runtime.md) and `workflow_runtime_reconstruction.py`; active | Fresh base-pinned external observations, request/result boundary, validation, source/PR operations, reconstruction attestation | Lifecycle selection, evidence publication, retry policy |
| Trusted-local host/provider | [`workflow_local_host.py` and `workflow_local_provider.py`](workflow-local-provider.md); active Phase 1 adapter | Reviewed control checkout, source/executable pins, installation of runtime/provider/pending-result seams, per-execution worktrees/homes, Copilot JSONL decoding, candidate-byte extraction, raw sidecar | Candidate-schema semantics, hostile same-UID isolation, lifecycle policy, human authorization |
| Bounded driver | [`workflow_driver.py`](workflow-driver.md); active optional continuation loop; refuses frozen issues #115 and #174 | Fresh `plan-next`, automatic allowlist, bounds, secret-free journal | Approval, recovery, initialization, merge, independent authority |

The mechanically enforced import direction is documented in
[Workflow Module Boundaries](workflow-boundaries.md). The legacy CLI still
imports only its kernel, while the replacement orchestrator composes the trusted
substrate through public module interfaces. Neither imports the other.

The host is an activation boundary, not a convenience wrapper. Direct
`workflow_orchestrator.py` execution starts with its runtime, sandbox/provider,
and pending-result seams unset and fails closed when an operation needs them.
`workflow_local_host.py` verifies the reviewed control-source set, including
`workflow_orchestrator_resume.py`, then installs those seams. Consequently the
provider cannot become active merely because its module is importable, and a
direct orchestrator invocation cannot silently inherit ambient execution
capabilities.

## Authority, trust, and guarantee boundaries

### What is trusted, and for what

- The **replacement authority pointer** selects the current immutable
  orchestration-state binding. The orchestrator must revalidate selected
  policy and gate history before acting; structural pointer validity alone is
  insufficient.
- The **legacy lifecycle** remains authoritative only for runs that still use
  its separate v1-v4 projection model. Its files are not replacement authority.
- The **durable inspector** is trusted only to verify the supported durable
  object graph and observed repository facts.
- A verified **repair bundle** and exact source/target preconditions define one
  attempted, explicitly selected repair. They are not lifecycle authority. The
  confirmation phrase prevents accidents; it is not authentication.
- A **CAS digest** identifies exact bytes under the SHA-256 assumption. It does
  not identify an approved producer or select the current lifecycle tip.
- An **evidence binding** is a structurally verified immutable record. The caller
  still must establish why that exact binding is trusted and current.
- A **policy result** is derived review evidence. Exit zero means the evaluator
  produced a valid result. Only selection in a valid orchestrator successor can
  make it part of active authority.
- Generic **actor strings** in legacy records or evidence provenance record
  attribution only. Replacement human gates instead re-observe the exact
  GitHub source, numeric account, configured association, and unedited body;
  that still depends on the configured GitHub and local credential boundary.

Plan, snapshot, diff, artifact, workspace, test, and PR fingerprints are
ordinary byte identity. They may detect change and bind evidence, but never
create approval, lifecycle authority, freshness, revocation, or activation.

### One authority and one expected tip

The replacement and legacy stores solve different authority problems and must
never own the same issue at once. On initialization, the replacement
orchestrator rejects legacy-owned state as `legacy-authority-owned`. On every
status read, coexistence of a replacement pointer and legacy state is
`dual-authority-detected`. Import separation prevents hidden code reuse; these
runtime guards prevent two independently writable control planes from both
appearing legitimate.

Within replacement authority, every mutation supplies the SHA-256 digest of the
pointer observed by the caller as an **expected tip**. The authority bundle
binds the exact source pointer bytes; the authority module locks, re-reads those
bytes, verifies their digest against the caller's expectation, and compares the
exact source before replacing the pointer with a successor that names the
previous authority and increments generation exactly once. This prevents a
cooperating stale writer from overwriting intervening progress—the same problem
addressed by version preconditions such as HTTP `If-Match`. A blind
last-writer-wins pointer would be simpler but could erase an approval, pending
claim, or recovery step; an append-only log without a selected tip would
preserve history but leave current authority ambiguous. Expected-tip selection
is not distributed consensus, actor authentication, or protection from a
process that bypasses the authority API and writes its files directly.

Here “expected tip” means the SHA-256 of the compact **authority pointer**, not
whatever commit a Git branch happens to name. Repository commits have a
separate freshness contract: bootstrap pins the selected base and later runtime
reconstruction re-observes `HEAD`, base ancestry, cleanliness, and relevant
GitHub state. Conflating those tips would let a fresh branch hide stale workflow
authority, or fresh workflow authority hide repository drift.

### Trust boundary map

| Boundary | Trusted responsibility | Data treated as untrusted or separately verified |
|---|---|---|
| Human | Make consequential authorization decisions after inspecting the exact artifact | Agent/reviewer claims; mutable current state; inferred consent |
| Replacement orchestrator | Compose one legal successor and revalidate selected policy/gate history | Candidate output, caller-selected tips, runtime results until verified |
| Authority | Verify and atomically select one expected-tip state binding | Lifecycle meaning and external freshness |
| Policy | Deterministically evaluate exact evidence and dependencies | Trust-anchor selection, publication, human identity |
| Evidence/CAS | Preserve and verify exact bytes, manifests, provenance, lineage, and bindings | Producer trust, currentness, approval, retention |
| Runtime | Reconstruct and observe Git, GitHub, config, executable, and repository facts | Caller assertions and stale prior observations |
| Supervisor | Bound one process group and report typed process facts | Application semantics and escaped descendants |
| Local host | Load the reviewed controller and fixed provider seams from a clean base | Mutable controller copies, plugin discovery, ambient credentials |
| Provider adapter | Validate Copilot transport and produce one strict candidate | Provider-specific metadata/events outside the consumed contract; all candidate content |
| Candidate worktree/home | Confine intended repository scope and give each execution fresh state | Hostile same-UID containment; Phase 1 does not provide it |
| Agent | Produce proposed plans, reviews, tests, and implementation | Everything it emits remains untrusted until the owning boundary validates it |

### Failure guarantees and non-guarantees

| Mechanism | Detects, contains, or recovers | Does not guarantee |
|---|---|---|
| Replacement authority | Exact expected-tip selection, complete predecessor chain, immutable state/evidence identity, stale cooperating-writer detection | Lifecycle correctness without orchestrator history revalidation; distributed consensus; protection from a writer bypassing local filesystem controls |
| Legacy v4 projections | Structural state/history equality, committed envelope hashes, supported transaction recovery, stale evidence invalidation | Replacement authority; all projection files changing atomically together; protection from a writer bypassing the CLI |
| Inspector/checkpoint | Missing, unsupported, corrupt, ambiguous, moved HEAD/base/status, and exact object/reference inconsistency | Full lifecycle-history revalidation, producer authentication, remote freshness, a checkpoint remaining current after emission |
| Repair | Stale source, malformed/unsafe journal, conflicting object/pointer, interruption at tested transaction boundaries, postcommit mismatch | Lock-free compare-and-swap against a writer bypassing the advisory lock; arbitrary repair; hidden automatic recovery |
| CAS/evidence | Exact hash/size mismatch, noncanonical graph, missing dependency, immutable-name collision, stale expected identity | Mathematical collision impossibility, tamper prevention by filesystem permissions, revocation, latest-tip selection, one multi-object filesystem transaction |
| Migration | Unsupported source shape, incomplete transaction, changed durable checkpoint, wrong lineage/identity, tampered plan | Semantic guessing, legacy activation, lifecycle conversion, deleting or compacting source data |
| Supervisor | Startup failure, timeout, per-stream output overflow, original process-group survival, cancellation, signal escalation facts | A graceful reaction to SIGTERM, application-level cleanup after SIGKILL, observation of escaped descendants, CPU/memory/I/O/network quotas |
| Dependency/convergence policy | Wrong trusted tip input, malformed chain, stale or reused convergence context, excessive root-changing cycles or stage retries | Acquiring the trusted tip, publishing/applying next authority, lifecycle completion by itself |
| Work-type policy | Ambiguous classification, unsupported scope, targeted-check bounds, static final-observation inconsistencies | Fresh comprehensive validation, latest tip, authenticated acceptance; non-implementation route activation |
| Plan-revision policy | Stale plan/review/revision binding, invalid diff/unit map, missing dispositions, unsafe preservation, escalation to full review | Semantic understanding, inherited human approval, newest revision, authority change by itself |
| Human gates | Exact challenge confirmation and fresh GitHub authorization observation selected by the orchestrator | Cryptographic identity beyond the configured GitHub account/association model, authorization after evidence changes, automatic conflict resolution |

Fail-closed means unsupported, stale, corrupt, missing, denied, or ambiguous
inputs do not become a success-shaped transition. It does not mean the system
can repair every failure or prove that its external observations will remain
fresh.

## Why each mechanism was chosen

The focused documents define exact schemas and operations. This section explains
the design choices without duplicating those contracts.

| Mechanism | Problem and decision | Alternatives and tradeoff | Guarantee boundary and demonstrating tests |
|---|---|---|---|
| Independent inspector/checkpoint | A mutation-capable writer cannot be its own only recovery witness. The independent inspector (issue #128) uses a separate read-only implementation and a compact deterministic checkpoint. | Trusting `agent_workflow.py` would share its failure mode; copying a full workspace would increase authority surface and storage. The compact reader intentionally supports less lifecycle interpretation. | Establishes one verified observation and exact precondition candidates, not continuing freshness. See [`test_workflow_inspector.py`](../../scripts/tests/test_workflow_inspector.py). |
| Expected-tip authority | Concurrent or stale cooperating callers must not overwrite intervening authority. Each commit compares the exact selected pointer, verifies the complete predecessor chain, records the predecessor and pointer digest, and advances generation once. | Last-writer-wins is smaller but can erase selected progress; an unselected append-only log preserves records but leaves current authority ambiguous; distributed consensus is unnecessary for the reviewed local single-store model. | Detects stale API callers and malformed predecessor chains. It does not authenticate callers, coordinate multiple stores, or prevent direct filesystem tampering. See [`test_workflow_authority.py`](../../scripts/tests/test_workflow_authority.py). |
| Checkpoint-before-repair and explicit activation | Repair must not guess current state or silently reinterpret authority. The repair component (issue #129) prepares a complete canonical bundle, requires an explicit operation/confirmation, then rechecks immediately before pointer commit. | Arbitrary setters or automatic recovery are simpler to invoke but can synthesize state. Explicit bundles add operator ceremony and fail when evidence is insufficient. | The pointer replacement is the repair authority commit point; advisory locking still requires cooperating writers. See [`test_workflow_repair.py`](../../scripts/tests/test_workflow_repair.py). |
| Small kernel boundary | The failed #115 attempt showed that storage, integrity, lifecycle, and dispatch in one module made the active implementation ambiguous. The boundary work (issue #130) extracted the legacy low-level primitives and enforces downward imports and unique top-level names. | Keeping one file reduced initial plumbing but allowed silent Python shadowing. Modules add interfaces and compatibility work but make ownership reviewable. | The boundary prevents known definition/import regressions; it does not activate the new architecture. See [`test_workflow_boundaries.py`](../../scripts/tests/test_workflow_boundaries.py). |
| Process groups and TERM-before-KILL | Parent-only termination can abandon descendants. The supervisor (issue #131) starts a new session, signals its process group, allows a bounded grace period, then escalates. | Immediate KILL is bounded but denies cooperative cleanup; parent-only terminate is weaker. Grace increases completion time and still may be ignored. | Verifies cleanup only for the original process group; escaped descendants remain unobservable. See [`test_workflow_supervisor.py`](../../scripts/tests/test_workflow_supervisor.py). |
| Time and output limits | Unbounded commands can monopolize orchestration or memory. The supervisor arms one deadline before spawn and gives stdout/stderr independent byte budgets. | Truncating output and reporting success would hide evidence; unlimited capture preserves bytes but risks exhaustion. ChessEcho terminates on overflow and reports it. | It bounds elapsed supervision and retained stream bytes, not CPU/memory/network consumption. |
| Evidence and accepted evidence immutability | Review and approval must continue to name exact bytes after retries, corrections, or worktree loss. The evidence layer (issue #132) uses immutable content references and verifies complete graphs. | Mutable files permit later replacement; random IDs do not prove content; database blobs still need canonical identity and concurrency rules. Content-addressed storage gives stable identity/deduplication but needs external selection and retention policy. | Publication never overwrites conflicting bytes through the API; filesystem write access remains a trust boundary. See [`test_workflow_cas.py`](../../scripts/tests/test_workflow_cas.py) and [`test_workflow_evidence.py`](../../scripts/tests/test_workflow_evidence.py). |
| SHA-256 content identity | Immutable evidence needs a practical identifier derived from exact bytes so readers can detect alteration without embedding the payload everywhere. SHA-256 is standardized, widely available, and strong enough for this local evidence model. | Filenames/random UUIDs identify locations or allocations, not contents; weaker hashes reduce collision margin; signatures answer producer identity, a different problem. | Collision resistance is an assumption, not a proof of uniqueness or authority. SHA-256 is never an approval or signature. |
| Semantic manifest separated from provenance and lineage | The same path/mode/content can be captured from Git, a workspace, or migration at different times. Mixing those facts would make equivalent content have different semantic identity. | One combined object is simpler but couples identity to timestamp/source and duplicates payloads. Separation adds a graph that must be verified. | Manifest identity describes semantic bytes; provenance describes how they were observed; lineage describes parent relationships. None establishes latest-tip or revocation alone. |
| Dependency-first, binding-last publication | Multiple object writes are not one portable filesystem transaction. Dependencies are published idempotently, durable preconditions are rechecked where required, and the binding is the final success marker. | In-place mutation risks partial graphs; a database transaction would add a new trusted engine and still require external-state preconditions. Binding-last may leave unreachable objects after interruption. | A successful binding has published dependencies; orphan objects are possible and compaction remains separate. |
| Deterministic migration with optimistic preconditions | Compatibility conversion must not depend on whichever mutable path is read during apply. The migration layer (issue #133) plans exact source bytes/selections and rechecks a durable checkpoint before publishing an evidence binding. | Automatic live migration is convenient but couples reads, writes, and activation; semantic conversion guesses unsupported meaning. Exact plans are reproducible but deliberately reject more inputs. | Migration is input-deterministic and idempotent for identical publication; it does not activate or prove current policy authority. See [`test_workflow_migration.py`](../../scripts/tests/test_workflow_migration.py). |
| Dependency-aware invalidation | Full reset discards valid sibling evidence, while preservation by prose similarity is unsafe. The dependency policy (issue #134) uses a fixed graph and byte-identical evidence dependencies. | A caller-supplied graph is flexible but weakenable; semantic inference is unbounded. A fixed version-1 graph is rigid but independently testable. | Computes a minimal next state; only the orchestrator may publish and select it. See [`test_workflow_policy.py`](../../scripts/tests/test_workflow_policy.py). |
| Bounded retries and convergence | The failed #115 attempt demonstrated repeated reopen/review loops that did not necessarily remove the structural cause. The dependency/convergence policy shares two root-changing cycles and allows three retries per convergence stage before deterministic escalation. | Unlimited retries may never converge; immediate abort wastes recoverable work. Fixed limits can escalate a difficult but solvable case to human decomposition. | Exhaustion returns a no-change escalation, never bypasses a gate or synthesizes evidence. |
| Work-type policy | The architecture-only work recorded in issue #79 showed that forcing design, research, or docs through implementation stages creates synthetic evidence. The work-type policy (issue #116) defines four fail-closed routes and verifies final scope. | One universal lifecycle is simpler; automatic semantic classification is convenient but untrustworthy. Explicit intake adds schema/triage overhead. Only the implementation route is currently composed. | A conforming structural assessment is not active completion or fresh validation. See [`test_workflow_work_type_policy.py`](../../scripts/tests/test_workflow_work_type_policy.py). |
| Incremental plan review | The plan-revision work (issue #125) recorded costly repeated certification of unchanged plan content. It preserves only anchored coverage for byte-identical units and expands one hop around changed dependencies. | Always-full review is simple but expensive; wholesale review inheritance is unsafe. Strict unit/diff schemas add planner/reviewer work and escalate many changes to full review. | Preserves review coverage, never human approval. See [`test_workflow_plan_revision_policy.py`](../../scripts/tests/test_workflow_plan_revision_policy.py). |
| Human approval boundaries | Reviewer readiness establishes technical sufficiency, not authorization. Exact GitHub authorization observations bind human decisions to gate challenges. | Agent self-approval or inferred consent is faster but collapses independent authority. Human gates add latency and currently expose hash-bearing confirmations to operators. | The orchestrator re-observes an unedited comment from a configured account; the agent cannot manufacture it. Human-facing artifact exposure remains issue #196. |
| Fresh runtime reconstruction | A long-lived process or mutable checkout can silently outlive the facts that authorized it. Every public command reconstructs the selected runtime from pinned config, executable, authority, baseline, and phase evidence. | Reusing one in-memory adapter is faster but expands the validity domain of stale observations. Global caching would weaken freshness. | Reconstruction proves the requested current boundary, not permanent freshness. Command-scoped reuse avoids selected-history revalidation amplification; broader performance work remains #174. |
| Provider and host source pins | A readable provider version is insufficient to prove which reviewed code runs. The config binds exact provider, host, and executable source hashes, checked before use and in CI. | Ordinary release labels are easier to maintain but can remain unchanged when trusted bytes change. | Versions such as `1.5.4` are audit labels. Source SHA-256 is the integrity identity, not a signature or deployment version. |
| Separate transport and candidate protocols | Copilot emits verbose evolving JSONL; ChessEcho needs one small strict candidate. The adapter incrementally validates observed event paths, preserves complete transport as a sidecar, and extracts only the terminal candidate. | Treating stdout as candidate bytes, heuristic JSON extraction, or globally permissive event handling hides ambiguity. | The adapter understands only observed/documented paths. Unknown semantics fail closed until bounded evidence justifies a narrow compatibility rule. |

## How the architecture evolved

The history matters because each layer answers a failure the simpler workflow
could not contain. It does not make historical state authoritative.

| Design pressure | What changed and why |
|---|---|
| Establish explicit gates | The initial gated workflow ([PR #99](https://github.com/NathanZK/ChessEcho/pull/99)) separated Planner, Reviewer, Implementer, and human authorization, then bound validation and PR creation to repository evidence. It created the safety model that still runs today, but concentrated lifecycle, storage, Git/GitHub, and process execution in one script. |
| Keep unrelated repository failures out of an issue | A repository-wide lint baseline blocked another issue's validation ([issue #98](https://github.com/NathanZK/ChessEcho/issues/98)). Planning therefore gained issue isolation, explicit analyzer inventory, and source-alignment rules. |
| Stop treating every deliverable as code | An architecture-only task was pushed through synthetic test and implementation stages ([issue #79](https://github.com/NathanZK/ChessEcho/issues/79)). The later work-type policy ([issue #116](https://github.com/NathanZK/ChessEcho/issues/116)) defined separate implementation, design, research, and documentation classifications. The replacement currently activates only implementation; other routes still fail closed. |
| Correct approved work without restarting everything | A small post-approval API correction could not safely reopen a terminal run ([issue #85](https://github.com/NathanZK/ChessEcho/issues/85)). Linked correction runs ([issue #117](https://github.com/NathanZK/ChessEcho/issues/117), [PR #123](https://github.com/NathanZK/ChessEcho/pull/123)) preserved an immutable parent while invalidating only the affected downstream evidence. |
| Bound review effort without weakening integrity | Repeated broad validation and direct state-mutation risk motivated bounded validation, v4 integrity envelopes, explicit legacy adoption, and controlled recovery ([issue #120](https://github.com/NathanZK/ChessEcho/issues/120), [PR #124](https://github.com/NathanZK/ChessEcho/pull/124)). These safeguards were useful, but they increased coupling inside the legacy script. |
| Preserve attempts and attribute drift precisely | The evidence-persistence effort ([issue #115](https://github.com/NathanZK/ChessEcho/issues/115)) correctly identified disposable worktree records and coarse aggregate fingerprints. Its implementation expanded the self-hosting monolith instead of creating boundaries: duplicate active/shadowed definitions made fixes ambiguous, broad evidence capture amplified storage, repeated reopen loops did not converge, and final validation still failed across coupled paths. The public [architectural checkpoint](https://github.com/NathanZK/ChessEcho/issues/115#issuecomment-5516943602) froze the run and called for selective decomposition. |
| Make critical mechanisms independently reviewable | The [trusted-core roadmap](https://github.com/NathanZK/ChessEcho/issues/136) split read-only inspection, explicit repair, kernel ownership, process supervision, canonical evidence, deterministic migration, and dependency/convergence policy into narrow downward dependencies. Its linked issues and merged PRs preserve the detailed implementation history. |
| Preserve review effort without inheriting approval | The incremental plan-review policy ([issue #125](https://github.com/NathanZK/ChessEcho/issues/125), [PR #148](https://github.com/NathanZK/ChessEcho/pull/148)) verifies exact diffs, finding dispositions, and narrowly reusable coverage. The orchestrator composes its technical result but never treats preserved coverage as human approval. |
| Compose without collapsing boundaries | The work tracked by issue [#144](https://github.com/NathanZK/ChessEcho/issues/144) added the thin orchestrator only after policy genesis, authority selection, runtime supervision, trusted remote-head observation, configurable gates, trusted issue intake, safe branch publication, pointer repair, and pinned reconstruction existed independently. Activation changed composition status, not ownership: each lower module retains its narrow contract. The tracker remains open with unreconciled acceptance items; this guide reports current source behavior rather than inferring implementation status from unchecked issue metadata. |
| Learn the provider protocol empirically | Controlled #176 runs falsified assumptions about prompts, credentials, stdout, stream suppression, metadata, event order, worker homes, and denied tools. Each accepted change was limited to documented or observed behavior; one unresolved-parent incident deliberately received no fix until independent probes reproduced it. |

The abandoned #115 implementation and its postmortem explain failed approaches;
they are not a specification for current code. #115 must never be initialized,
resumed, migrated, repaired, or used as an activation target.

## Current composition without collapsed ownership

The policy capabilities still do not import one another as a hidden
orchestrator:

- The **work-type policy** verifies intake, scope, routes, targeted checks, and
  structural completion. Only its implementation route is active.
- The **plan-revision policy** verifies plan snapshots, exact diffs, technical
  reviews, findings, and reusable coverage. It never preserves human approval.
- The **dependency/convergence policy** verifies exact dependencies,
  invalidation, correction roots, and bounded convergence.
- The **supervision policy** defines `plan`, `tests`, `final`, and
  `pr-publication` satisfaction. It cannot authenticate a human or write GitHub.
- The **authority module** verifies and commits an expected-tip pointer. It
  cannot decide whether the selected transition is semantically legal.

`workflow_orchestrator.py` is the only composition owner. For one action it
revalidates selected history, calls the relevant public policy/runtime/evidence
interfaces, constructs one successor candidate, and commits at most one
authority pointer. A process request and its result are deliberately two
authority steps: one successor claims exact work, external execution publishes
evidence without moving authority, and a later successor finalizes only that
exact result. A crash cannot become permission to execute the request twice.

### Authority, evidence, and authorization

These answer different questions:

| Record | Question answered | What it cannot answer alone |
|---|---|---|
| Evidence binding | What exact bytes and observations exist? | Whether they are current, permitted, or approved |
| Authority pointer and chain | Which immutable state is selected now? | Whether external facts stayed fresh after observation |
| Policy result | Does this exact input satisfy a deterministic rule? | Whether the result was selected or human-authorized |
| Human authorization observation | Did the configured human publish the exact challenge response? | Whether the reviewed artifact remains unchanged |
| Repository/PR observation | What did Git or GitHub report at this boundary? | Whether a later observation will match |

Hashes establish identity, not permission. A candidate can be canonical,
content-addressed, and internally valid while still being stale, unselected, or
unauthorized. Conversely, an authority pointer cannot make missing or malformed
evidence valid. The orchestrator succeeds only when these independent claims
join on exact references.

### Reconstruction and freshness

Initialization uses strict bootstrap: clean `HEAD`, the local tracking ref, and
the live default-branch tip must agree, and configuration is read from that
reviewed base. Later commands reconstruct from a credential-free pin plus the
selected baseline, triage, authority, and phase repository evidence. The host
rechecks source hashes, executables, config bytes, Git/GitHub identity, the live
default tip, worktree controls, and the expected repository state before
external work.

This repetition is intentional: safety depends on the validity domain of an
observation, not merely on whether recomputation is idempotent. It also has real
cost. During runtime-reconstruction work, full CI increased from about 10.3 to
47 minutes because selected authorization-history revalidation caused N-by-M
reconstruction and more than 10,000 supervised subprocesses. Command-scoped
reuse removed the accidental amplification while retaining fresh reconstruction
at separate public-command and trust boundaries. Residual cost remains
[issue #174](https://github.com/NathanZK/ChessEcho/issues/174); a global cache
or weaker freshness is explicitly not an acceptable shortcut.

## Provider and candidate protocol boundaries

The orchestrator does not speak Copilot JSONL. It requests a role-specific
operation from the runtime and consumes only ChessEcho's candidate schema. The
trusted-local provider owns the external protocol:

```mermaid
flowchart LR
  Request["Immutable execution request"] --> Prompt["Operation-specific prompt"]
  Prompt --> Copilot["Pinned Copilot executable"]
  Copilot --> JSONL["External-provider JSONL"]
  JSONL --> FSM["Strict incremental adapter"]
  FSM --> Bytes["Extracted candidate bytes"]
  JSONL --> Sidecar[("Complete raw transport sidecar")]
  Bytes --> Bundle[("Same evidence manifest")]
  Sidecar --> Bundle
  Bundle --> Candidate["Core phase-specific decoder<br/>workflow_orchestrator_resume.py"]
  Candidate --> Orchestrator["Policy and lifecycle composition"]
```

There are three distinct protocols:

1. The **external-provider protocol** is what the pinned Copilot executable
   actually emits. It is empirically observed and not assumed to be fully
   specified.
2. The **adapter protocol** is the smallest observed/documented subset the
   provider FSM understands and trusts.
3. The **candidate protocol** is ChessEcho's strict, phase-specific artifact
   contract, owned by `workflow_orchestrator_resume.py`. The provider extracts
   terminal candidate bytes but does not decide which plan, review, implementer,
   or PR fields are legal. A successful process and valid provider stream can
   still fail the core candidate contract. Provider 1.5.4 also carries a
   prompt-facing JSON Schema copy for review operations so Copilot is told what
   review candidate to produce; tests keep that communication schema aligned,
   but only the core decoder validates and authorizes candidate meaning.

The current provider (`1.5.4`) and reviewed local host (`1.3.0`) have exact
source identities in `.github/agent-workflow.json` under
`orchestrator.local_host.provider.source_sha256` and
`orchestrator.local_host.source_sha256`. Those versions are human-readable
audit labels. The
authoritative integrity identities are the configured source SHA-256 values for
the provider and local host plus the pinned agent executable SHA-256. Changing
trusted source bytes requires updating the corresponding binding. During the
[merged PR #199](https://github.com/NathanZK/ChessEcho/pull/199) prompt
correction, the host bytes changed while its configured hash did not; CI failed
until the stale hash was corrected. That is intentional integrity enforcement,
not ordinary release-version management.

This separation follows the same dependency direction as
[Ports and Adapters](https://alistair.cockburn.us/hexagonal-architecture/):
provider-specific compatibility remains at an external adapter while core
policy owns the application contract. ChessEcho's exact modules and trust
claims come from its source and tests, not from that architectural analogy.

### Observation before formalization

The provider history establishes this engineering sequence:

```text
authoritative documentation
        ↓
bounded black-box observation
        ↓
captured immutable evidence
        ↓
behavioral model
        ↓
minimal adapter contract
        ↓
synthetic tests
        ↓
implementation
        ↓
independent review
        ↓
controlled E2E
```

The methodological failure was treating an empirically unknown external
protocol as fully specified too early. Synthetic tests then proved that the
implementation matched its own assumptions, not that those assumptions matched
Copilot. `--silent` did not create a candidate-only stdout contract;
`--stream off` did not suppress tool, reasoning, background-task, and partial
events; harmless nested metadata broke an exhaustive decoder; and valid
reasoning-to-message order broke an overfitted FSM.

The denied-tool incident shows the corrected discipline. One complete E2E
contained an unresolved parent ID, but its semantics were unknown, so no code
changed. Only after the pattern recurred and two standalone authenticated probes
reproduced all observed denials did the adapter add one exact transition. The
parent remains an opaque correlation token; the documentation does not invent
its internal meaning.

**Governance rule:** when implementation materially depends on undocumented
external behavior, establish that behavior through authoritative documentation
or a bounded black-box probe before formalizing architecture around it. Stop
when design requires invented semantics, fixtures describe unobserved behavior,
failures accumulate increasingly complex exceptions, or evidence cannot
distinguish an external-system defect from a model defect.

The agent and reviewers should label epistemic status explicitly:

| Status | Meaning | Permitted architectural use |
|---|---|---|
| Documented guarantee | An authoritative provider or platform source promises the behavior | May define the adapter contract, with the source and version recorded |
| Observed fact | Complete bounded evidence shows the behavior under named conditions | May justify the narrowest compatible path covered by that evidence |
| Assumption | A design convenience not yet grounded in documentation or observation | Must not become a trusted protocol rule |
| Hypothesis | A possible explanation for observed facts | May guide the next probe, never be recorded as provider semantics |

Repeated failures should trigger evidence gathering before more architectural
exceptions. Synthetic tests are regression evidence only after the modeled
behavior is grounded. Human approval is required for consequential architecture
changes, not merely for the final code. Autonomous retries remain bounded so
they cannot continually elaborate a wrong model.

## Fail-closed incidents as architectural evidence

A red run is not automatically a workflow defect. The relevant questions are:
which boundary rejected the input, what exact evidence supports that
classification, and whether acceptance would have weakened an invariant.

| Incident | Observed boundary | Architectural lesson |
|---|---|---|
| #115 evidence-persistence attempt | A self-hosting monolith accumulated duplicate/shadowed definitions, broad evidence amplification, and nonconverging reopen loops. | A mutation-capable workflow cannot be its only recovery witness. Inspection, repair, storage, evidence, policy, and execution must have narrow owners. |
| #176 prompt and authentication failures | The provider and runtime disagreed on the same prompt bound; then isolated Copilot correctly lacked credentials. | Revalidate one operation-specific contract consistently. Isolation and credential provisioning are separate decisions. |
| #176 candidate/transport failures | Prose preceded JSON; three finite stdout ceilings were exhausted; `--stream off` still emitted large tool/reasoning/background traffic. | External transport, retained diagnostics, process lifetime, complete raw evidence, and candidate bytes are different quantities. The sidecar architecture separates them. |
| #176 metadata and ordering failures | Complete successful transports contained new opaque metadata and an unmodeled reasoning-to-message transition. | Be strict about consumed semantics, not harmless metadata; extend an FSM with exact observed paths rather than global permissiveness. |
| #176 worker-home failure | Planning succeeded, but reviewer launch reused a populated run-level home and was denied. | Execution isolation has execution lifetime. A fresh worker home is allocated for every agent-producing step. |
| #176 denied-tool parent | One complete run contained an unresolved parent. No fix was made. After recurrence and two independent probes reproduced five denied executions, one exact adapter transition was added. | Unknown behavior remains unknown. Reproduction can justify a bounded compatibility rule without inventing the opaque parent's semantics. |
| #176 no-op test authoring | Copilot exited zero and returned a valid candidate, but the independent repository observation contained no required test change; policy paused with `test-scope-drift`. | Process success, candidate success, operation success, and workflow success are distinct. A correct refusal may be the desired result. |
| #198 reviewer candidate | Transport and process succeeded, but the reviewer returned an unsupported verdict and extra fields because the operation prompt did not communicate the core candidate schema. Merged PR #199 corrected the prompt contract without weakening the decoder. | Keep the strict core candidate decoder. Correct the operation-specific prompt contract rather than accepting plausible-but-unauthorized JSON. |
| #198 source-pin CI failure | Trusted host bytes changed in PR #199 but the configured host SHA remained old; CI failed until the same PR corrected the integrity binding, then merged green. | Readable versions do not establish deployed identity. Source pins must move with every trusted-byte change. |
| Runtime reconstruction regression | Full CI grew from about 10.3 to 47 minutes because selected-history revalidation multiplied fresh reconstruction calls. | Safe-to-repeat does not mean cheap. Optimize within the observation's validity domain; do not trade freshness for a global cache. |

The full provider chronology, evidence sizes, run identifiers, exact observed
event paths, and validation status are preserved in
[Trusted-local workflow provider](workflow-local-provider.md#176-controlled-e2e-protocol-discovery).
That document intentionally distinguishes fixture/protocol re-execution,
standalone probes, and later authenticated E2E observations.

The controlled E2Es prove important boundaries, not an end-to-end completion
claim. #176 exercised authenticated provider work and supervised plan approval,
then correctly paused at `test-scope-drift` when independent observation found
no executable test change. #198 reached plan review and correctly rejected an
invalid reviewer candidate; PR #199 corrected that prompt/schema mismatch and
merged with focused validation, but no post-merge complete E2E is claimed here.
No authenticated controlled run documented by this guide has traversed the
entire replacement lifecycle through `COMPLETED`. Automatic gate satisfaction
is covered by policy and driver tests, not by those controlled E2Es.

## Current guarantees, non-guarantees, and unfinished work

**Current guarantees** are deliberately narrow:

- selected replacement state is an expected-tip pointer to a completely
  verified immutable authority/evidence chain;
- policy, technical review, human authorization, and external observations are
  separate records joined by exact references;
- each external operation is bounded, claimed once, and finalized separately;
- agent output is validated as untrusted candidate data;
- trusted provider, host, and executable identities are pinned and rechecked;
- complete Copilot transport is bound beside the execution result without being
  confused with the candidate;
- stale, unsupported, corrupt, missing, ambiguous, denied, and uncertain
  outcomes do not become success-shaped authority transitions; and
- human gates cannot be inferred from agent or reviewer output.

**Current non-guarantees** are equally important:

- Phase 1 does not contain a hostile same-UID worker or deny it filesystem,
  credential, authority-store, or network access at the OS level;
- process groups cannot prove containment of descendants that escape them;
- local CAS and pointers are protocol-immutable, not physically tamper-proof;
- SHA-256 identifies bytes but does not authenticate a producer or authorize a
  transition;
- GitHub observations and reconstruction establish bounded freshness, not a
  fact that remains true forever;
- completion does not merge, mark ready, deploy, or close the issue;
- the reviewed Phase 1 host cannot currently invoke the core-only
  `set-supervision` operation; and
- the current implementation route does not activate design, research, or
  documentation lifecycles.

**Unfinished architecture** includes hostile-worker containment (#160),
reconstruction-cost work that preserves freshness (#174), improved worker
credential storage (#180), usable immutable-plan approval UX (#196), the
operator runbook and worked traces (#197), and evidence retention/compaction
(#135). These are future boundaries, not guarantees implied by the current
code.

## Distributed-systems mental model

These analogies help reason about failure, but ChessEcho is a local workflow
tool, not a distributed database.

| ChessEcho concept | Useful analogy | Where it breaks down |
|---|---|---|
| Agent process | Unreliable worker | There is no authenticated worker fleet, scheduler, lease, or remote execution consensus. |
| Workflow generation or policy tip | Versioned state | There is no replicated state machine or quorum selecting the current version. |
| CAS object | Content-addressed immutable record | Storage is local and protocol-immutable, not automatically replicated, access-controlled, retained, or physically tamper-proof. |
| SHA-256 digest | Cryptographic content identity and integrity check | It is not a signature, approval, authority selector, freshness proof, or guarantee that collisions are impossible. |
| Evidence provenance and lineage | Provenance/version lineage | They do not independently prove producer identity, current time, revocation absence, or latest tip. |
| Checkpoint and repair/migration precondition | Optimistic concurrency / expected version | Repair uses advisory serialization and exact rechecks; portable pointer replacement is not lock-free compare-and-swap against a non-cooperating writer. |
| Binding-last publication | Append-oriented commit marker | It is not a general multi-object transaction; unreachable dependency objects can remain after interruption. |
| Supervisor | Process/failure isolation | A POSIX process group is not a container or cgroup and cannot observe a descendant that escapes the group. |
| SIGTERM / SIGKILL | Graceful request / bounded forced termination | SIGTERM may be ignored; SIGKILL cannot run application cleanup or prove durable consistency. |
| Repair/recovery | Explicit recovery operation | There is no automatic conflict resolution, global rollback, or arbitrary state repair. |
| Retry/reopen budgets | Bounded work / backpressure | Fixed counters bound one policy lineage; they do not manage load across a service. |
| Human approval | External authorization gate | The replacement path authenticates the configured GitHub source/account/association, but does not provide a separate cryptographic approval identity beyond that trust boundary. |
| Deterministic migration | Schema/data compatibility conversion | It publishes a verified mapping; it does not switch lifecycle authority or provide transactional rollback. |

ChessEcho does not implement distributed consensus, a transactional filesystem,
a distributed lock service, remote attestation, an authenticated approval
service, or a general job scheduler.

## What we deliberately do not do

The current source and focused contracts support these boundaries:

- no hidden automatic recovery, repair, migration, or conflict resolution;
- no arbitrary setter, patch, or mutable global workflow authority;
- no policy decision inside the inspector, CAS publisher, supervisor, or other
  trusted primitive;
- no activation or lifecycle claim from a digest, checkpoint, process success,
  policy result, Reviewer verdict, or human actor string alone;
- no automatic semantic classification of work or plan dependencies;
- no unbounded reopen/retry behavior in the dependency/convergence policy;
- no reuse of comprehensive final validation after the final HEAD/workspace
  changes;
- no claim that process groups contain escaped descendants or enforce all
  resources;
- no deletion, compaction, or reclamation as part of evidence publication or
  migration;
- no revival, migration, repair, completion, or activation of frozen #115;
- no #127 risk tiers or execution modes in the current architecture; and
- no activation of unimplemented non-implementation routes or Phase 2
  containment disguised as documentation.

## Glossary

| Term | Definition |
|---|---|
| Authority | Exact state or binding selected by the currently active authority mechanism. |
| Activation | Authorized step that makes verified evidence or a policy result affect lifecycle authority. |
| Generation | Monotonic version field within a supported run, issue index, or policy lineage; its scope is defined by the owning contract. |
| Projection | Materialized state/history/integrity or a derived human-readable view. A projection is authoritative only where its owner explicitly says so. |
| Checkpoint | Canonical, deterministic inspector document binding verified durable authority and minimal repository observations at one read. |
| Repair bundle | Canonical, hash-bound, operation-specific request plus source/target preconditions and objects for one deliberate repair attempt. |
| Repair journal | Fixed-path durable transaction record used to resume or finalize a supported repair interruption. |
| CAS object | Bytes stored at a path derived from their SHA-256 digest and accepted only when hash/size and expected kind verify. |
| Evidence identity | Exact content identity of evidence bytes/manifest; not approval or producer authentication. |
| Provenance | Separately hashed facts about how, when, and from where semantic evidence was captured. |
| Lineage | Original, inherited, or replacement relationship to a parent binding within one evidence family. |
| Binding | Immutable graph root connecting identity, decision, subject, manifest, provenance, lineage, and migration metadata. |
| Trusted tip | Expected current binding digest obtained outside the evaluator; the evaluator verifies equality but does not acquire it. |
| Designated binding | Caller-selected expected digest for a candidate input; designation is not endorsement. |
| Invalidation | Removal of an active derived dependency while retaining its immutable historical evidence. |
| Reopen | Explicit return to an earlier approved stage with downstream evidence invalidation. |
| Correction | Linked child run that preserves an immutable parent and inherits/invalidates evidence according to its class. |
| Convergence | Bounded sequence of cause, fix, verification, and closure evidence evaluated by the dependency/convergence policy. |
| Stale reuse | Illegitimate reuse of an earlier observation or result outside exact current preconditions. Content identity alone does not prevent stale reuse; selected authority and phase-specific freshness checks do. |
| Selected-history revalidation | Recomputing lifecycle, policy, and gate semantics from the immutable authority chain before a current action. This is not stale reuse. |
| Fixture/protocol re-execution | Feeding preserved protocol bytes through the current decoder to verify compatibility. It is regression evidence, not a new external observation or authenticated E2E. |
| Historical reproduction | Re-running an earlier scenario or workload to investigate behavior. The new observation has its own provenance and freshness boundary. |
| Freshness | Evidence that an observation is current for a required time/tip boundary; content identity alone does not establish it. |
| Revocation | Explicit determination that previously selected authority or approval is no longer acceptable; immutable storage alone does not provide it. |
| Idempotency | Repeating the same valid operation converges on the same result rather than creating a conflicting duplicate. |
| Commit point | Named write after which an operation is considered authoritative: envelope last, repair pointer replace, or evidence binding publication, depending on the mechanism. |
| Fail-closed | Unsupported or inconsistent evidence produces a typed failure or no-change escalation rather than a success-shaped transition. |
| Trusted primitive | Narrow mechanism trusted for its stated contract and kept below policy/orchestration dependencies. |
| Protocol immutability | No-overwrite/collision-check behavior of supported writers; not a claim that OS-level writers cannot alter files. |

## Authoritative conceptual references

These references explain underlying concepts. They do not prove ChessEcho's
implementation; source and tests above establish its actual guarantees.

### ChessEcho history and contracts

- [PR #99](https://github.com/NathanZK/ChessEcho/pull/99) documents the initial
  gated workflow and its original validation.
- [Issues #79](https://github.com/NathanZK/ChessEcho/issues/79),
  [#85](https://github.com/NathanZK/ChessEcho/issues/85),
  [#117](https://github.com/NathanZK/ChessEcho/issues/117), and
  [#120](https://github.com/NathanZK/ChessEcho/issues/120) establish the
  work-shape, correction, validation, integrity, and recovery pressure.
- [#115's public architectural checkpoint](https://github.com/NathanZK/ChessEcho/issues/115#issuecomment-5516943602)
  records the frozen failed attempt and selective-decomposition decision.
- [The trusted-core roadmap, #136](https://github.com/NathanZK/ChessEcho/issues/136)
  records dependency order; [#144](https://github.com/NathanZK/ChessEcho/issues/144)
  records the composition and activation of the current replacement
  orchestrator.

### Systems concepts

- [Git objects](https://git-scm.com/book/en/v2/Git-Internals-Git-Objects)
  explain a practical content-addressed object model and why content identity is
  different from a mutable filename.
- [Git revisions](https://git-scm.com/docs/gitrevisions) explains the commit and
  object naming used when the workflow pins a base and final `HEAD`.
- [NIST FIPS 180-4](https://csrc.nist.gov/pubs/fips/180-4/upd1/final) specifies
  SHA-256. ChessEcho relies on its collision resistance for practical byte
  identity; it does not use a digest as a signature.
- [NIST SP 800-160 Vol. 1](https://csrc.nist.gov/pubs/sp/800/160/v1/r1/final)
  explains trustworthy-system engineering, including explicit trustworthiness
  objectives and protection across system boundaries. It helps frame why
  ChessEcho states assumptions and non-guarantees rather than calling the whole
  workflow “trusted.”
- [SLSA provenance](https://slsa.dev/spec/v1.2/provenance) explains verifiable
  statements about where software artifacts came from. ChessEcho uses its own
  local evidence/provenance schema and does not claim SLSA conformance; the
  useful shared lesson is that artifact identity and origin are separate facts.
- [Hexagonal Architecture / Ports and Adapters](https://alistair.cockburn.us/hexagonal-architecture/)
  is the original description of keeping application rules behind explicit
  interfaces to external systems. It is the broader pattern behind keeping
  Copilot compatibility in the provider adapter and candidate policy in the
  workflow core.
- [OWASP Agentic AI Threats and Mitigations](https://genai.owasp.org/resource/agentic-ai-threats-and-mitigations/)
  provides a broader threat-modeling vocabulary for autonomous agents,
  permissions, tool use, and observability. ChessEcho's observation-before-
  formalization rule and bounded autonomy are local decisions informed by real
  incidents, not requirements asserted by OWASP.
- The POSIX specifications for
  [`kill`](https://pubs.opengroup.org/onlinepubs/9799919799/functions/kill.html)
  and [`setpgid`](https://pubs.opengroup.org/onlinepubs/9799919799/functions/setpgid.html)
  explain signals and process groups behind the supervisor's supported POSIX
  boundary.
- The POSIX specifications for
  [`link`](https://pubs.opengroup.org/onlinepubs/9799919799/functions/link.html),
  [`rename`](https://pubs.opengroup.org/onlinepubs/9799919799/functions/rename.html),
  and [`fsync`](https://pubs.opengroup.org/onlinepubs/9799919799/functions/fsync.html)
  explain the individual filesystem operations. They do not turn several
  ChessEcho objects into one transaction or erase filesystem-specific
  durability limits.
- [RFC 9110 idempotent methods](https://www.rfc-editor.org/rfc/rfc9110.html#name-idempotent-methods)
  and [`If-Match`](https://www.rfc-editor.org/rfc/rfc9110.html#name-if-match)
  provide useful analogies for repeatable operations and expected-version
  preconditions; ChessEcho does not implement HTTP concurrency control.
- [W3C PROV-DM](https://www.w3.org/TR/prov-dm/) supplies standard provenance
  vocabulary. ChessEcho uses a smaller repository-specific schema.
- [SQLite atomic commit](https://www.sqlite.org/atomiccommit.html) explains
  crash-recovery reasoning and commit points in a real transactional engine.
  ChessEcho is not SQLite and does not inherit its transaction guarantees.
- [Linux cgroup v2](https://docs.kernel.org/admin-guide/cgroup-v2.html) and
  POSIX [`getrlimit`/`setrlimit`](https://pubs.opengroup.org/onlinepubs/9799919799/functions/getrlimit.html)
  describe stronger resource-control facilities that the process-group
  supervisor does not implement.

## Legacy lifecycle reference

The remainder of this section documents the retained
`scripts/agent_workflow.py` path. These commands, v4 projections, correction
runs, and state names remain relevant only to legacy runs. They must not be
mixed with replacement authority, evidence, approvals, or recovery.

## Legacy state machine

```text
PLANNING
  -> PLAN_REVIEW
  -> PLANNING                              (reviewer needs revision)
  -> WAITING_FOR_PLAN_HUMAN_APPROVAL
  -> PLANNING                              (human rejects)
  -> TEST_IMPLEMENTATION                   (human approves)
  -> TEST_REVIEW
  -> TEST_IMPLEMENTATION                   (reviewer needs revision)
  -> WAITING_FOR_TEST_HUMAN_APPROVAL
  -> TEST_IMPLEMENTATION                   (human rejects)
  -> IMPLEMENTATION                        (human approves)
  -> VALIDATION
  -> TEST_IMPLEMENTATION                   (human reopens approved tests)
  -> IMPLEMENTATION                        (validation fails)
  -> FINAL_REVIEW                          (validation passes)
  -> TEST_IMPLEMENTATION                   (human reopens approved tests)
  -> IMPLEMENTATION                        (reviewer needs revision; stale final evidence cleared)
  -> DRAFT_PR_CREATED                      (reviewer ready + validation passes)
  -> WAITING_FOR_PR_HUMAN_APPROVAL
  -> WAITING_FOR_PR_HUMAN_APPROVAL         (human-authorized metadata-only revision)
  -> IMPLEMENTATION                        (human rejects and authorizes revision)
  -> PR_APPROVED                           (human approves)
```

The CLI rejects any action that is invalid in the current state. Reviewer readiness never records human approval. Validation and final-review data are invalidated when implementation revision resumes.

## Legacy responsibilities

| Role | Responsibility | Prohibited |
|---|---|---|
| Orchestrator | Initialize the run, invoke the correct role, report state, and stop at gates | Inferring approval or bypassing the CLI |
| Planner | Inspect the issue, architecture, code, and tests; map criteria to a concrete plan | Editing production code or tests |
| Reviewer | Independently challenge plans, tests, and implementation | Editing reviewed work or granting human approval |
| Implementer | Act as Test Author in `TEST_IMPLEMENTATION`, write code only after test approval, and run validation | Self-approval, weakening approved tests, or direct PR creation |
| Human | Explicitly approve or reject the plan, tests, and draft PR | N/A |

## Legacy bounded, risk-aware validation policy

Validation is proportional to risk, but it never weakens a gate.

**Mandatory validation** covers the issue and contract, exact source symbol, signature, and call site evidence needed for source alignment, acceptance mapping, current workflow status and documented precondition, relevant test and helper behavior, and every applicable approval, integrity, migration, recovery, final-validation, Git, and pull request gate.

**Optional/deep validation** is additional investigation beyond that mandatory set. Before doing it, record this five-field declaration:

- **Uncertainty or risk**: the concrete question or material risk.
- **Impact and reversibility**: the consequence and whether the action can be safely undone.
- **Source insufficiency**: why the issue, contract, source, and existing tests cannot settle it.
- **Smallest probe**: the narrowest targeted check capable of answering it.
- **Stopping result**: the result that makes the evidence sufficient.

Prefer an exact source read, one call-site trace, or one targeted existing test. Routine planning, review, test authoring, and workflow execution must not use broad reinspection, a scratch implementation, transcribed harness, copied harness, mutation campaign, or exhaustive experiment. Implementation-level testing during planning or review is prohibited unless source insufficiency leaves a named material uncertainty that requires the smallest probe.

Stop when source alignment, executability, acceptance coverage, relevant risk, and open findings have sufficient evidence. Another validation pass requires changed scope, changed source, new evidence, an open finding, or a newly named risk; repetition merely to increase confidence is not justified.

Deep validation is mandatory for integrity, approval, or security boundaries; migration or recovery; irreversible or destructive changes; an external contract or external dependency; final certification; and material uncertainty. A contradiction, unknown lifecycle, unknown signature, or insufficient high-risk evidence must fail closed until resolved.

Workflow authority remains controlled regardless of validation depth. Never use direct authority mutation of `state.json`, `history.jsonl`, or approvals. Use only documented commands, including explicit `adopt-legacy-run` and `recover-run` where applicable.

## Legacy mandatory source-alignment and executability gate

Before every plan submission, the Planner must align the proposed work with the repository's actual source. This is a required pre-submission gate, not work delegated to the Reviewer.

For every proposed production change, the Planner must:

1. Inspect the exact symbols, functions, hooks, and components to be changed.
2. Trace relevant call sites, consumers, actual APIs and callback signatures, state ownership, lifecycle boundaries, and data flow.
3. When moving logic out of effects or other lifecycle code, trace every current state transition and name the concrete replacement trigger for each one.
4. Consider stale-state, request-ordering, concurrency, cleanup, remount, disconnect, and other lifecycle windows relevant to the change.
5. Inspect existing test helpers, mocks, render wrappers, deferred-promise sequencing, and setup before specifying tests; proposed tests must exercise reachable application lifecycle paths rather than imagined entry points.
6. Read the actual workflow and integrity implementation before relying on state transitions, artifact hashes, fingerprints, validation behavior, or sequencing. Do not infer these guarantees from documentation or conversation alone.

Immediately before submission, perform a final executability check and record concise evidence in the plan:

- every referenced symbol exists;
- every API, callback, and function signature matches the repository;
- every relocated state transition has a concrete replacement trigger;
- relevant call sites, consumers, and lifecycle paths are covered;
- stale-state and concurrency windows are addressed;
- tests exercise real application lifecycle using verified helpers and sequencing; and
- another engineer can implement the plan without rediscovering the architecture.

If any item cannot be proven, continue investigating or state the ambiguity explicitly; do not submit the plan as executable.

During plan review, the Reviewer independently spot-checks this evidence. Review findings should distinguish genuine architectural/planning disagreements from repository-verifiable `SOURCE_ALIGNMENT_DEFECT` findings that should have been caught by this gate. This classification improves the process without discouraging revisions or weakening independent review.

### Analyzer and lint cleanup plans

For analyzer, compiler-diagnostic, typecheck, or lint cleanup issues, first declare the analyzer, check, and scope owned by the issue. Source alignment then requires a complete inventory of findings belonging to that declared scope before plan submission. Include suppressions relevant to the same scope when the analyzer supports suppression or existing source/configuration may hide those findings. Do not expand the inventory to unrelated repository-wide checks or suppressions.

Every finding must map explicitly to:

| Required mapping | Evidence |
|---|---|
| Location | Exact file and line or symbol |
| Cause | Why the analyzer reports it |
| Resolution | Specific production or test change that clears it |
| Verification | Test or analyzer command proving it is resolved |

Broad architectural changes do not count as coverage by themselves. The plan must show how each scoped finding is cleared by the proposed design. Before requesting human approval, answer: **Where does every finding in the declared analyzer/check/scope go?** No scoped finding may remain unowned.

Prefer the smallest behavior-preserving change that genuinely resolves each finding. Do not include optional adjacent refactors. Every refactor must have a concrete tie to a finding, correctness requirement, or necessary architectural consequence. Suppressions, disabled rules, weakened configuration, reduced analyzer scope, or equivalent workarounds are not valid resolutions unless the issue and human approval explicitly choose them as the intended solution.

On every plan revision:

1. Re-run or reconcile the complete scoped analyzer inventory against current source.
2. Reconcile the entire plan, not only the latest review comments.
3. Remove superseded sections and stale claims instead of accumulating revision patches.
4. Check for contradictions between old and new designs.
5. Re-verify affected hooks, components, APIs, callbacks, state cells, and lifecycle behavior.
6. Submit one coherent executable plan that another engineer can follow without reconstructing which revision is authoritative.

The Reviewer must verify complete finding ownership, the absence of stale or contradictory plan sections, source-level correctness of every referenced surface, that each proposed change actually clears its mapped finding, and that optional refactors have not expanded scope. Inventory, source-alignment, or revision-hygiene defects should be classified as `SOURCE_ALIGNMENT_DEFECT` where applicable. Review success is technical readiness, not minimizing the number of legitimate revision loops.

## Legacy Git baseline and final-revision invariants

Git baseline inspection and finalization are separate. Planning needs an understood, issue-isolated baseline; mechanical final evidence begins only when the implementation is normalized for final validation.

### Planning baseline and issue isolation

During planning, the Orchestrator must:

1. Inspect the configured target base, branch ancestry, commits, and worktree.
2. Verify the branch and worktree contain only the current issue's changes plus any explicitly required workflow infrastructure.
3. Stop if commits or changes from another issue or prior work are present. Require them to be separated; never discard, rewrite, or hide unrelated work silently.
4. Prefer fetching the target base before source alignment so the plan uses a current baseline.

The fetch recommendation is agent guidance: the CLI does not contact the remote during planning and does not claim that a local tracking ref is the latest remote revision. Rebase or reconcile only when the baseline actually requires it. Do not repeatedly rewrite history before plan submission, test implementation, and production implementation. The test-first order remains unchanged: human plan approval, tests, human test approval, then production implementation.

If a later baseline change invalidates an approved plan or tests, use `reopen-plan` or `reopen-tests`; do not carry stale approval forward. Before final validation, normalize only once.

### Final normalization before validation

After implementation is complete but before submitting it for the validation that supports final review:

1. Fetch the configured target base. This latest-remote step is agent guidance, not a claim made by the CLI.
2. Reconcile with the fetched target base if needed.
3. Verify no unrelated work is mixed into the branch.
4. Normalize the current issue to exactly one commit relative to the configured local tracking ref (for example, `origin/main`). Inside a correction run the anchor is the source run's validated `HEAD` instead of the tracking ref, so exactly one *correction* commit is required on top of it (see [Legacy corrections](#legacy-corrections)).
5. Stop and require explicit separation if unrelated commits or changes are present. Never silently drop them.
6. Record the resulting final `HEAD` SHA in the implementation report.

Only after normalization may the Implementer submit the implementation and the Orchestrator run final validation. The required order is:

```text
fetch/reconcile/squash once
  -> one clean issue commit relative to the local target-base tracking ref
  -> record final HEAD
  -> final validation
  -> final review of that same HEAD
  -> draft PR from that same HEAD
```

Before running checks, `run-validation` mechanically captures a clean worktree, `HEAD`, the run's base ref and its resolved SHA, workspace fingerprint, approved-test fingerprint, ancestry, and the exactly-one-commit invariant. For a normal run the base ref is the configured local target-base ref; for a correction run it is the synthetic `parent-run-head:<sha>` label naming the source run's validated `HEAD`. After every check finishes, it recaptures the same evidence and requires that base SHA to have remained unchanged for the duration of validation. Any command-induced file/test/HEAD change, base-ref movement, dirty worktree, ancestry change, or commit-count change invalidates the run, clears stale evidence, records `VALIDATION_INVALIDATED`, and returns to `IMPLEMENTATION` without certifying PASS results.

Successful validation freezes that resolved base SHA in structured `validation_evidence`. Final review, draft creation, metadata revision, and approval verify ancestry and the one-commit invariant against the frozen SHA, not the later value of mutable `origin/main`. Therefore unrelated target-base advances after validation do not invalidate unchanged evidence. Final validation, final review, and draft-PR preparation must refer to one identical final `HEAD`; reviewer readiness records the reviewed `HEAD`, and GitHub's remote head must match it. A same-tree history rewrite cannot pass. The submitted implementation report is hash-protected after submission, but its prose is reviewer-readable context; structured workflow state is the mechanical authority for Git evidence.

The Orchestrator may write a human-readable validation summary, but it is guidance rather than an additional machine-validated artifact. The state machine's structured validation record is authoritative. Keep any internal PR-preparation notes separate from `pr-body.md` so the body retains the exact public heading format below.

Do not rewrite history after final validation. If the workspace or `HEAD` changes after validation, the Reviewer records `NEEDS_REVISION`; that auditable event returns to `IMPLEMENTATION` and clears stale validation and final-review evidence. Resubmit, rerun validation, and repeat final review before creating the draft PR. Reviewer `READY_FOR_HUMAN_APPROVAL` remains prohibited for changed evidence.

## Starting a legacy run from a GitHub issue

Select the `chess-echo-orchestrator` custom agent and ask it to run the workflow for an issue. The exact initialization command is:

```bash
python3 scripts/agent_workflow.py init ISSUE
```

The issue's `backend`, `frontend`, or `full-stack` label selects the repository-pinned validation profile. If labels do not identify a scope, specify it; an explicit scope cannot override a conflicting issue label:

```bash
python3 scripts/agent_workflow.py init ISSUE --scope frontend
```

A workflow-tooling-only issue carries none of those labels and is initialized with `--scope workflow-tooling`.

Inspect resumable status at any time:

```bash
python3 scripts/agent_workflow.py status ISSUE
```

## Legacy agent and human events

Agents write artifacts inside `.agent-workflow/runs/issue-<number>/artifacts/`, then record them:

```bash
python3 scripts/agent_workflow.py submit-plan ISSUE \
  --artifact .agent-workflow/runs/issue-ISSUE/artifacts/plan.md \
  --agent chess-echo-planner

python3 scripts/agent_workflow.py review-plan ISSUE \
  --status READY_FOR_HUMAN_APPROVAL \
  --artifact .agent-workflow/runs/issue-ISSUE/artifacts/plan-review.md \
  --reviewer chess-echo-reviewer
```

At a waiting state, the Orchestrator must show the artifact and review, stop, and obtain explicit human authorization. It then records that authorization:

```bash
python3 scripts/agent_workflow.py approve-plan ISSUE --by GITHUB_LOGIN --confirm plan_approved
python3 scripts/agent_workflow.py approve-tests ISSUE --by GITHUB_LOGIN --confirm tests_approved
python3 scripts/agent_workflow.py approve-pr ISSUE --by GITHUB_LOGIN --confirm "I approve this draft PR."
```

Rejections require a durable reason and return to the relevant producer:

```bash
python3 scripts/agent_workflow.py reject-plan ISSUE --by GITHUB_LOGIN --reason "Missing migration rollback strategy"
python3 scripts/agent_workflow.py reject-tests ISSUE --by GITHUB_LOGIN --reason "AC3 is not covered"
python3 scripts/agent_workflow.py reject-pr ISSUE --by GITHUB_LOGIN --reason "Revise the implementation"
```

`reject-pr` authorizes implementation changes, returns to `IMPLEMENTATION`, and clears validation/final-review evidence. For a title/body-only correction to the same open draft, the human instead uses the audited metadata-only path:

```bash
python3 scripts/agent_workflow.py revise-pr-metadata ISSUE \
  --by GITHUB_LOGIN \
  --reason "Clarify the summary" \
  --title "Revised title" \
  --body-file .agent-workflow/runs/issue-ISSUE/artifacts/pr-body.md
```

This command validates the body, requires the same workspace, configured base, validated/reviewed `HEAD`, and open draft, and never performs an unsafe automatic metadata write. The authorized human first supplies the desired title/body to the command; if the live draft still has the recorded metadata, the command instructs them to apply that exact change through GitHub and rerun. The rerun accepts only the exact requested title/body, then atomically records its new fingerprint while remaining at `WAITING_FOR_PR_HUMAN_APPROVAL`. Unrelated external metadata changes fail closed. Repeating an already-applied authorized revision is idempotent. The path cannot authorize code, history, workspace, head, or base changes.

If implementation or validation reveals a material problem in already approved tests, the human can explicitly reopen the test gate from `IMPLEMENTATION`, `VALIDATION`, or `FINAL_REVIEW`; downstream implementation and final evidence are invalidated:

```bash
python3 scripts/agent_workflow.py reopen-tests ISSUE --by GITHUB_LOGIN --reason "The approved expectation is incomplete"
```

If later work reveals a material design problem, the human can reopen the plan gate. This also revokes any test approval:

```bash
python3 scripts/agent_workflow.py reopen-plan ISSUE --by GITHUB_LOGIN --reason "Implementation exposed a material design gap"
```

## Legacy tests before implementation

After plan approval, the Implementer writes tests only and submits a report:

```bash
python3 scripts/agent_workflow.py submit-tests ISSUE \
  --artifact .agent-workflow/runs/issue-ISSUE/artifacts/test-report.md \
  --agent chess-echo-implementer
```

The Reviewer records `NEEDS_REVISION` or `READY_FOR_HUMAN_APPROVAL` with `review-tests`. Production implementation remains impossible until explicit test approval.

## Legacy validation and final review

After implementation submission, run:

```bash
python3 scripts/agent_workflow.py run-validation ISSUE
```

Before executing checks, the command enforces the configured local base ancestry, one-commit final history, clean worktree, and approved-test fingerprint. It then executes and records every required check:

| Scope | Required commands |
|---|---|
| Backend | `./gradlew ktlintCheck`; `./gradlew test` |
| Frontend | `npm run lint`; `npx tsc --noEmit`; `npm run test`; `npm run build` in `frontend/` |
| Full stack | All backend and frontend commands |
| Workflow tooling | `make agent-workflow-test` |

The `workflow-tooling` scope is for issues that change only the workflow tooling under `scripts/`. Scope inference reads only the `backend`, `frontend`, and `full-stack` labels, so such an issue must carry none of them and must pass `--scope workflow-tooling` explicitly. An issue that changes workflow tooling *and* product code stays on its product scope and needs the workflow tooling test path added to that run's frozen contract at `init`.

A failure returns the workflow to `IMPLEMENTATION`. A pass moves it to `FINAL_REVIEW`, where the Reviewer records its verdict with `review-final`.

## Legacy draft pull request gate

Prepare a concise PR body that explains the actual change and reasoning rather than reproducing the plan or implementation report. It must begin with and contain exactly these rendered level-2 headings in this order, with visible non-empty content in every section and no additional level-2 headings. Content before `## What` is prohibited; level-3 subsections and visible content following the final heading belong to their enclosing required section. Headings or content hidden in comments or fenced code do not satisfy the contract.

```markdown
## What

Concise changed behavior.

## Why

Problem and rationale for the chosen approach.

## Testing

Validation performed, including relevant test, typecheck, build, and lint results.
```

Record the final reviewed `HEAD` SHA in the PR preparation artifact and confirm it matches the normalized implementation and final-review artifact. Create the PR only with:

```bash
python3 scripts/agent_workflow.py create-draft-pr ISSUE \
  --title "Handle puzzle and weakness loading failures" \
  --body-file .agent-workflow/runs/issue-ISSUE/artifacts/pr-body.md
```

The command validates the body and refuses to call GitHub unless all configured checks passed, the final Reviewer recorded `READY_FOR_HUMAN_APPROVAL`, frozen-base ancestry and one-commit history still hold, and current/validated/reviewed heads and workspace evidence match. Creation and lookup are explicitly scoped to the run's configured repository and current head branch, and returned PR URLs must identify that repository. Lookup/auth/network/JSON failures and ambiguous matches fail closed. A matching draft created before a local interruption is adopted only when base, head, title, and body exactly match. Persisted `DRAFT_PR_CREATED` recovery re-reads and verifies the live PR. Ordinary creation/recovery never edits mismatched metadata; use the explicit metadata-revision command.

At that state, do not merge, mark ready, deploy, close the issue, or continue implementation. `approve-pr` records the final human decision; it does not merge or mark the PR ready.

## Legacy audit and recovery

Version 4 runs have three projections: canonical `state.json`, canonical append-only `history.jsonl`, and an `integrity.json` committed envelope containing their hashes, sequence, identity, and complete recoverable snapshot. A guarded writer appends one event, replaces the state and history files individually through atomic same-filesystem replacement, and writes the envelope last as the commit marker. The three files do not change as one filesystem transaction. Initial root and correction creation first records an exact-identity bootstrap transaction containing the intended canonical bytes; the exact creation command can reconcile an interruption, while conflicting or malformed partial creation fails closed. Every normal transition, status, correction summary, and correction-source/latest/sibling read verifies object structure, root-or-correction identity, version, contiguous sequence, embedded/JSONL equality, latest state, committed sequence, and both hashes before using authority. Reads never repair, migrate, synchronize, or write.

An internally consistent version 1-3 run without independent integrity evidence is not trusted automatically. The Orchestrator may explicitly run:

```bash
python3 scripts/agent_workflow.py adopt-legacy-run ISSUE \
  --by ASSERTED_IDENTITY --reason "Reviewed legacy records" \
  --confirm legacy_run_trusted [--correction N]
```

Active legacy adoption verifies exact state/history agreement, supported lifecycle names, and structure, then writes an exact pre-adoption transaction before either projection. Ordinary commands fail closed while that record exists; rerunning the exact adoption command validates its bytes, hashes, identity, timestamp, and trust metadata, reconciles only the recorded legacy or derived adopted bytes, commits the v4 envelope, and removes the transaction. Adoption applies compatibility defaults and appends `LEGACY_RUN_ADOPTED` without changing lifecycle, approvals, artifacts, validation, or Git evidence. A settled legacy root or correction at either PR gate is initially adopted sidecar-only with exact lossless bytes and trust metadata; later reads and idempotent adoption fully verify the sidecar against those live bytes and normalize only in memory. Consistency is not proof of provenance, and conflicting adoption fails closed.

`PR_APPROVED` legacy projections remain permanently byte-immutable. An adopted legacy run at `WAITING_FOR_PR_HUMAN_APPROVAL` still supports the existing approve, reject, and metadata-revision commands. Immediately before one of those transitions, the CLI uses the same interruption guard to commit the audited v4 adoption event; conversion is refused if an existing correction names that run as its exact source, so correction parent hashes and every settled ancestor remain unchanged. Status, correction reads, and other ordinary commands never trigger conversion.

If active v4 projections differ after an interruption, the legacy Orchestrator role may run `recover-run ISSUE [--correction N]`. Recovery independently validates the last committed envelope, restores only its snapshot, and appends `RUN_INTEGRITY_RECOVERED` with fixed `chess-echo-orchestrator` attribution and observed hashes. Approve, reject, and metadata-revision transitions from `WAITING_FOR_PR_HUMAN_APPROVAL` first record their source and intended bytes in a transaction. If interrupted before the envelope-last commit, recovery validates that transaction and restores the exact committed waiting bytes without an event, lifecycle change, or approval change; if the intended envelope committed, recovery only removes its completed marker after byte-exact verification. It does not re-execute the rejected action, grant or revoke approval, or infer tool success; the original action must be rerun deliberately. Missing, malformed, stale, wrong-identity, ambiguous, legacy, already-matching, adoption-in-progress, and arbitrary settled recovery attempts fail closed. `PR_APPROVED` remains byte-immutable.

Plan and test approvals verify that the human is seeing the exact artifacts and tests the Reviewer marked ready. Approved plan/review and test/review artifacts are rechecked at every downstream gate. Plan approval freezes a fingerprint of every non-test file through the test-review gate, preventing production implementation before test approval. Test approval records a fingerprint of the test files, and implementation submission refuses changed approved tests. Fingerprints include file type and permissions and cover Git-tracked plus non-ignored untracked files. Successful validation records workspace, `HEAD`, and the frozen base revision; final review records workspace and reviewed `HEAD`. Draft-PR creation requires all evidence to match, frozen-base ancestry and one-commit history to remain valid, all non-run changes to be committed, and no Git `assume-unchanged` or `skip-worktree` flags. If GitHub creates the PR but the local process stops before recording it, rerunning `create-draft-pr` reconciles only an open draft with the expected base, reviewed head, title, and body instead of creating another. After an implementation-level PR rejection completes a new validated/reviewed cycle, changed title/body metadata on the existing draft requires the explicit human-authorized `revise-pr-metadata` path.

Final PR approval verifies that the workspace, Git revision, base branch, draft status, title, body, and remote PR head are unchanged since draft creation.

Do not manually edit state, history, integrity, or approvals. Actor strings and filesystem locks coordinate cooperating processes but do not authenticate callers sharing an OS account; external signing and credentials are outside this local-file authority model.

## Legacy corrections

A run at a PR gate is never reopened or mutated **to perform a bounded correction**; normal `approve-pr`, `reject-pr`, and `revise-pr-metadata` gate behavior remains as described above. A bounded post-approval fix instead forks an immutable, linked correction run:

```bash
python3 scripts/agent_workflow.py start-correction ISSUE \
  --classification implementation-only \
  --by GITHUB_LOGIN --reason "Post-approval API contract fix" \
  [--from-correction N]
```

The correction is a child run beside its source, and every later command addresses it with `--correction N`:

```text
.agent-workflow/runs/issue-ISSUE/                 # source run, never written by a correction
.agent-workflow/runs/issue-ISSUE/corrections/1/   # child state.json, history.jsonl, artifacts/, validation/
```

```bash
python3 scripts/agent_workflow.py submit-implementation ISSUE --correction 1 \
  --artifact .agent-workflow/runs/issue-ISSUE/corrections/1/artifacts/implementation-report.md \
  --agent chess-echo-implementer
python3 scripts/agent_workflow.py run-validation ISSUE --correction 1
python3 scripts/agent_workflow.py status ISSUE --correction 1
```

`--correction` is accepted by every subcommand except `init` and `start-correction`. Artifacts, validation logs, state, and history for a correction live under the child directory; artifacts recorded for a correction must be inside its own `artifacts/` directory.

### Classifications and evidence

| Classification | Entry state | Inherited | Invalidated |
|---|---|---|---|
| `metadata-only` | `WAITING_FOR_PR_HUMAN_APPROVAL` | All six artifacts, plan and test approvals, all validation/final/draft evidence | PR approval |
| `implementation-only` | `IMPLEMENTATION` | Plan, plan review, test report, test review, plan and test approvals | Implementation report, final review, PR approval, all validation/final/draft evidence |
| `test-contract` | `TEST_IMPLEMENTATION` | Plan, plan review, plan approval | Test evidence, implementation evidence, PR approval, all validation/final/draft evidence |
| `architecture` | `PLANNING` | Nothing | Everything |

Each child records `correction` (number, classification, reason, requesting human, timestamp, and the exhaustive, disjoint `inherited`/`invalidated` token lists) and `parent_run` (issue, source correction number, source state, source validated head/base, and the SHA-256 of the source `state.json` and `history.jsonl` at fork time). Inherited artifact records still point at the source run's files, so any later edit to inherited evidence fails the correction closed at every downstream gate. A `test-contract` correction re-derives the inherited plan approval's non-test fingerprint, exactly as `reopen-tests` does, so the child's test phase measures the current tree.

### Fail-closed fork checks

`start-correction` reads its source read-only and writes nothing outside the new child directory. It refuses to fork unless the source is at one of the two PR-gate states with a recorded validated head and base, the current `HEAD` descends from that validated head with at most one commit on top of it, and the worktree is clean. A `metadata-only` fork additionally requires the live workspace fingerprint and `HEAD` to equal the source's validated values and all approved source artifacts to be unchanged; a changed tree or head is refused rather than accepted under a weaker label, and the operator must re-run with a code-changing classification.

Misclassification discovered later escalates through the existing human commands inside the child run: an `implementation-only` correction that must change approved tests uses `reopen-tests ISSUE --correction N`, and a `test-contract` correction that must change non-test files uses `reopen-plan ISSUE --correction N`. There is no de-escalation back to a narrower class.

### Chaining, siblings, and validation anchoring

Numbering is flat per issue. A new correction is refused while any other correction for the issue is in flight, that is, in any state before its own PR gate; multiple settled corrections may coexist. Once a committed or in-progress child records the exact parent hashes, that waiting source cannot be approved, rejected, or metadata-revised. When the latest correction validated a different `HEAD` from the selected source, every later correction must use `--from-correction N` with that latest correction number. This mechanically keeps code-changing history linear and prevents newer settled evidence from being orphaned. Settled metadata-only corrections may remain siblings because they retain the same validated `HEAD`. `status ISSUE` lists every committed correction with its number, classification, state, requesting human, and creation time. An incomplete bootstrap is hidden from summaries and reserves its number until the exact command reconciles it; an unrelated `corrections/<n>/` directory without `state.json` remains ignored by both listing and numbering.

Inside a correction, validation anchors on the source run's validated `HEAD` rather than the configured target base: `run-validation` resolves that SHA, records it as `validated_base` with the synthetic `parent-run-head:<sha>` base ref, and still requires exactly one commit relative to it. Every other safeguard is unchanged — clean worktree, ancestry, validated/reviewed head equality, frozen final review, and the draft-PR fingerprint checks all apply to the correction's own evidence. The latest-correction requirement above prevents selecting an older anchor whose branch history could orphan newer settled evidence.

The draft PR follows existing behaviour. A code-changing correction starts with no recorded draft, so `create-draft-pr ISSUE --correction N` adopts the still-open draft when its base, head, title, and body match, otherwise routes metadata differences through `revise-pr-metadata ISSUE --correction N`, and opens a fresh draft when no open PR matches the branch. A `metadata-only` correction inherits the draft record and goes straight to `revise-pr-metadata` and `approve-pr`; it fails closed if that pull request is no longer an open draft.

The source run's `issue.md` remains the authoritative issue snapshot for its corrections; child runs do not copy it.

## Legacy limitations

- Agent invocation is coordinated by Copilot rather than a continuously running service.
- Human identity and the exact stage confirmation are recorded, but a local process with repository write access remains a trust boundary and can impersonate `--by`.
- Durable run files remain local until committed or otherwise preserved with the branch.
- GitHub CI runs independently after draft PR creation; this workflow gates creation on local validation, not later CI status.
- Final PR approval is recorded but intentionally does not merge or mark the draft ready.
- A correction that is in flight blocks starting another correction for the same issue; drive it back to its PR gate first. Nothing is permanently marked, so completing it unblocks the issue.
- Validation subprocess output is captured in memory before it is written to logs; output is not currently size-bounded.

## Appendix: historical #126 architecture audit record

This appendix preserves the pre-activation audit that originally produced this
guide. Status statements in the main body supersede its historical findings.

### Evidence examined

The audit compared:

- public issue and pull-request history from the initial gated workflow in PR
  #99 through the future composition scope in issue #144;
- historical workflow source/test snapshots at PR #99, the linked-correction
  merge, the integrity/recovery merge, and the abandoned #115 source commit;
- current module source, public CLIs, embedded schema validators, and import
  direction on the post-PR #148 baseline;
- focused engineering documents and representative tests for every owner in the
  responsibility map; and
- CI/path routing, `.github/agent-workflow.json`, agent profiles, and the
  workflow-test Make target.

For reproducibility, source-derived historical measurements were recomputed from
their cited commits: workflow/test source grew from 1,415/844 lines in the
initial gated implementation, to 1,789/1,592 after linked corrections, to
2,938/2,325 after integrity/recovery, and to 8,112/8,063 in the abandoned #115
attempt; that final source defined 27 top-level function names twice. These
measurements document the coupling failure but are not needed to operate the
current workflow.

The audit did not initialize, execute, migrate, repair, or revive #115 and did
not use its runtime state as current authority.

### Findings and disposition

| Finding | Classification | Disposition |
|---|---|---|
| The former canonical introduction described only the legacy CLI/kernel and omitted the landed trusted/inactive layers. | Historical documentation work | Corrected by #126; the current guide now also records later replacement activation. |
| "Test Author" is a phase responsibility, not a fifth custom-agent profile. | Documentation work | The diagrams and role table name the Implementer as Test Author during `TEST_IMPLEMENTATION`. |
| The repair guide said repair imported only the inspector, while source also imports the content-addressed storage leaf. | Documentation work | Corrected in [Workflow Repair](workflow-repair.md). |
| The boundary table ambiguously assigned unqualified migration/recovery policy to the legacy CLI beside trusted migration/repair modules. | Documentation work | Qualified as legacy adoption/projection recovery in [Workflow Module Boundaries](workflow-boundaries.md). |
| Evidence documentation could make content-addressed records sound authenticated, tamper-proof, current lifecycle authority. | Documentation work | Qualified in this guide and [Canonical Workflow Evidence](workflow-evidence.md). |
| Atomicity claims needed named scope: per-file legacy replacement, pointer commit for repair, binding commit for evidence/migration. | Documentation work | Corrected in the diagrams, arrow explanation, and guarantee table. |
| Issue dependency/status prose lagged merged work; the canonical evidence and migration issues remain open although their implementations landed. | Audit finding | This guide describes exact implementation status; no issue metadata is changed here. |
| Historical #115 coupled policy/storage/recovery, shadowed definitions, amplified evidence, and did not converge through repeated lifecycle retries. | Audit finding | Preserved as historical rationale; selected responses are verified against landed source rather than assumed from the postmortem. |
| Legacy validation uses unbounded `subprocess.run` capture and does not use the supervisor. | Historical follow-up | The replacement runtime uses bounded supervision; the legacy limitation remains confined to legacy runs. |
| Repair requires non-cooperating lifecycle writers to be quiescent; its pointer replacement is not lock-free compare-and-swap against them. | Current non-guarantee | Replacement activation did not expand repair's narrow contract. |
| Work-type, plan-revision, dependency/convergence, supervision, authority, and runtime components were not yet composed. | Historical follow-up | Issue #144 later composed them for the active implementation route. |
| Historic evidence amplification has no active compaction/deletion mechanism. | Follow-up implementation work | Deferred, destructive retention work remains [issue #135](https://github.com/NathanZK/ChessEcho/issues/135), not this documentation issue. |
| Risk tiers and execution modes are not implemented. | Follow-up implementation work | Evidence-driven future policy remains [issue #127](https://github.com/NathanZK/ChessEcho/issues/127). |
| Workflow-doc changes run the Python workflow suite, but no Markdown link/anchor/Mermaid checker exists. | Audit finding | Manual rendering/link/reference review is required; automation is optional future tooling, not a defect silently fixed here. |

No production defect is normalized as intentional and no behavioral fix is
included here. For a new finding, record the exact baseline, owner, violated
invariant, reproduction or missing-test evidence, impact, and whether an
existing issue already owns it. File one narrowly scoped follow-up only after
human confirmation, link it from this table, and leave production behavior
unchanged. Speculative gaps and obligations already owned by the risk-tier, retention, or
replacement-orchestrator follow-up tracks do not justify duplicate issues.
