# Workflow scope-drift retrospective

This completed learning record archives the substantive body of GitHub issue
[#238](https://github.com/NathanZK/ChessEcho/issues/238), observed at
`2026-09-13T15:23:44Z` with body SHA-256
`a4cde0f52f55fe338ab1138f2791a50571e66331abaa7bacfe1651cbe9546eee`.
**#238 records why; #237 is the future how.** This retrospective does not
authorize compatibility mode, weaken any gate, or implement #237.

## Status

This issue is the completed architectural retrospective and a living decision record for the ChessEcho agent workflow. It explains why the workflow is being simplified, which guarantees remain non-negotiable, and how later evidence should update the design.

It is intentionally established before implementation of #237. It does not authorize a workflow change.

## Executive conclusion

ChessEcho began with a sound objective: place an adversarial governance layer around an autonomous coding agent so useful agent capabilities could be used without trusting the agent's account of its own work.

That objective remains valid. The workflow's strongest mechanisms independently establish:

- who authorized a transition and what exact challenge was approved;
- which host, provider, executable, and source bytes ran;
- which Git base, tree, commits, paths, and remote state were observed;
- which tests and validation commands actually ran;
- which immutable evidence and provenance bindings support a decision;
- whether a transition was permitted by current authority.

The architecture accumulated communication constraints with workflow-stopping force as Copilot planning, narration, serialization, and handoffs were formalized. These failures were not literally classified the same as integrity violations: implementation-phase `candidate-output-invalid` already produces a recoverable pause, while evidence corruption and unauthorized actions fail with stronger typed outcomes. The architectural concern is that a communication pause still halts forward progress and requires human-authorized recovery. Each added contract was locally defensible; together, some enlarged the system from governance around Copilot toward partial deterministic reimplementation of Copilot's interaction protocol.

The durable lesson is:

> Be tolerant at the interoperability boundary. Be strict at the trust boundary. Prefer independent observation over agent assertion. Formalize only what creates a guarantee the underlying agent does not already provide.

This does **not** mean "strictness is bad," "trust Copilot," or "make failures warnings." It means failure severity must follow the property being protected.

## Evidence base

This retrospective is grounded in the current merged workflow, its issue/PR history, and preserved authenticated E2E evidence.

### Current implementation

The relevant boundaries are implemented in:

- `scripts/workflow_local_provider.py` and `scripts/workflow_local_host.py`;
- `scripts/workflow_orchestrator.py`, `scripts/workflow_orchestrator_resume.py`, and `scripts/workflow_orchestrator_gates.py`;
- `scripts/workflow_plan_revision_policy.py` and `scripts/workflow_work_type_policy.py`;
- `scripts/workflow_policy.py` and `scripts/workflow_supervision_policy.py`;
- `scripts/workflow_runtime.py` and `scripts/workflow_supervisor.py`;
- `scripts/workflow_authority.py`, `scripts/workflow_evidence.py`, `scripts/workflow_cas.py`, and `scripts/workflow_inspector.py`;
- `scripts/workflow_issue_source.py`;
- the legacy/kernel/migration path in `scripts/agent_workflow.py`, `scripts/workflow_kernel.py`, and `scripts/workflow_migration.py`.

The completed audit grouped approximately 1,000 typed failures into 36 gate families. Most protect real authorization, integrity, provenance, topology, scope, or artifact correctness. The compatibility surface is narrow.

### Evolution and failure chronology

| Evidence | What it established | Architectural lesson |
|---|---|---|
| #136 | A trusted-core rebuild was needed after the monolithic workflow became unsafe to extend. | Separate narrow trusted primitives before composing orchestration. |
| #144 | The replacement should be a thin orchestrator over policy, authority, runtime, evidence, repair, and supervision. | Orchestration must not absorb every component's responsibility. |
| #158 | The active architecture, trust transitions, transport discoveries, and controlled failures needed explicit documentation. | Observation must precede formalization, especially for external agent protocols. |
| #183 | Prompting and `--silent` could not create a trustworthy byte-level output protocol; arbitrary JSON extraction was unsafe. | Provider-specific translation belongs at an adapter, while ambiguous candidate selection remains fail-closed. |
| #214 and merged PR #235 | The implementer prompt omitted the validator's trusted-base and one-final-commit contract. | Consumer validation is not automatically a producer contract. |
| Merged PR #236 | The planner prompt omitted the exact composition needed to avoid acceptance metadata self-reference. | A correct validator can still expose an incomplete producer interface. |
| Preserved #198 planning run, family `8727e6a356830125e01e6847b117fa20` | `acceptance-coverage-self-reference` rejected a substantive unit whose range included the metadata block. | Traceability representation had acquired workflow-stopping authority. |
| Preserved #198 test-author run, family `a47a31a1fad5cf103e2d5956475a7868` | Copilot produced a clean tests-only one-commit Git candidate, but prose plus fenced JSON caused `candidate-output-invalid`. | Communication failure and invalid repository artifact are different failure classes. |

The latest failure is particularly useful because the independent evidence was unusually clear:

- trusted base: `ceefe3ed5ff88ea25a08a773a94d657e1f5f325d`;
- candidate commit: `4c3876bccae8edb65f1932633c2280379fd316bb`;
- exactly one commit from base;
- clean worktree;
- only `src/test/kotlin/com/chessecho/config/StringToTimeControlConverterTest.kt` changed;
- complete 558,610-byte JSONL transport with terminal exit code `0`;
- raw candidate preserved with SHA-256 `1728b8c09104e4348b3a7f644bde5e525cdd913e2bd210f5665262b902678aad`;
- strict decoding failed at line 1, column 1 because the final message began with prose.

The workflow was correct to stop under its current strict contract. The current implementation already distinguishes the outcomes: implementation-phase `candidate-output-invalid` becomes a recoverable pause rather than the hard `corrupt` or `denied` outcome used for integrity and authorization failures. The retrospective question is narrower: should this communication failure halt forward progress and require recovery when an independent repository artifact still exists?

## 1. How scope drift happened

The drift followed a repeatable sequence:

```text
legitimate governance requirement
  -> explicit representation
  -> producer contract
  -> strict validator
  -> new producer obligation
  -> E2E failure at the obligation
  -> another prompt, field, state, or validator
```

For example:

1. Plans needed reviewable traceability.
2. Traceability became ordered line-range units and acceptance mappings.
3. The validator correctly rejected self-referential metadata coverage.
4. The producer prompt then needed a precise instruction for a separate unmapped metadata unit.

Similarly:

1. Implementation needed a trusted final Git state.
2. The validator required one commit from the trusted base.
3. Test and production phases naturally produced two commits.
4. The producer prompt then needed explicit history-normalization instructions.

Each correction aligned one producer with one consumer. The accumulated system, however, increasingly governed the agent's representation of work rather than the independently observable result.

Three questions must remain distinct:

1. Are we solving the original governance problem?
2. Are we solving a failure introduced by our solution?
3. Are we solving a failure introduced by the solution to that failure?

Recursive complexity begins when the third category is treated as automatically equivalent to the first.

## 2. Local correctness is not system correctness

The audited validators are generally rigorous:

- canonical schemas reduce ambiguity;
- exhaustive plan units improve local traceability;
- exact metadata supports deterministic consumers;
- typed failures improve diagnosis;
- strict transitions prevent hidden progression.

Those properties do not establish that the whole architecture is proportionate.

Collectively, they can increase:

- coupling between prompts, candidate schemas, validators, state transitions, evidence projections, and tests;
- the number of ways a valid engineering artifact can become unusable;
- recovery and operator burden;
- cognitive load for reviewers;
- pressure to encode more of Copilot's conversational behavior as workflow state;
- distance from the original authorization and integrity objective.

A locally correct validator can therefore participate in a globally mis-scoped architecture. The relevant question is not only "does this validator correctly enforce its contract?" but also "does this contract purchase a system-level guarantee worth its cost?"

## 3. Complexity budget

Every mechanism should justify itself by the guarantee it purchases.

| Mechanism | Guarantee purchased | Cost and new failure surface | Assessment |
|---|---|---|---|
| Cryptographic host/provider/executable/source identity | Reviewed bytes are the bytes that ran | Pin updates, source hashing, trusted bootstrap | High complexity, strong unique guarantee |
| CAS, evidence, and authority lineage | Facts cannot be silently substituted, reordered, or made current without a valid transition | Canonical serialization, bindings, generations, inspection | High complexity, strong unique guarantee |
| Human approval bound to an exact challenge | A permitted actor approved this exact state | Challenge lifecycle, stale detection, artifact lookup | High complexity, strong authorization guarantee |
| Git ancestry, topology, scope, and cleanliness | The candidate derives from the trusted base and contains only authorized changes | Git observation and policy logic | High value; independently establishes repository facts |
| Independent test/build execution | The artifact actually satisfies required checks | Runtime cost and environment handling | High value; replaces agent assertion with observation |
| Exhaustive plan line-range mapping | Every trusted fact is traceable to plan text | Prompt/schema coupling and revision complexity | Useful quality guarantee, not repository integrity |
| Implementer narrative report | Human-readable account of the change | Candidate serialization and parser failures | Low unique value when Git and tests establish the facts |
| Exact Markdown or JSON presentation | Predictable machine consumption | Producer brittleness and E2E stoppage | Valuable only where no independent artifact exists |
| Detailed provider-internal JSONL ordering | Deterministic transcript interpretation | Large compatibility surface tied to an external protocol | Justified only for events needed to identify and bind the terminal result |

Complexity is justified when it creates a guarantee unavailable elsewhere. It is suspect when it duplicates a fact already established more directly.

## 4. Invariant versus implementation

The workflow should specify what must be true, not unnecessarily prescribe how Copilot must prove it.

| Useful invariant | Over-specified producer implementation |
|---|---|
| The workflow knows which files changed. | Copilot must enumerate changed files in a particular JSON field. |
| The candidate descends from the trusted base. | Copilot must narrate the trusted base correctly. |
| The candidate has the required topology. | Copilot must describe or repair history through a particular report shape. |
| Required tests passed. | Copilot must assert that tests passed. |
| The reviewed executable ran. | Copilot must identify its own executable or version. |

Git, the filesystem, independent process execution, and cryptographic hashes establish the left-hand facts more strongly than an agent report.

Explicit producer output remains necessary where the information is inherently authored rather than observed: a proposed plan, rationale, or review judgment. Even then, it is untrusted input to review and authorization, not proof of its own correctness.

The reusable rule is:

> Specify the invariant, not the agent's preferred method of proving the invariant.

## 5. Assertion versus observation

`Copilot says X` and `an independent mechanism establishes X` are different evidence classes.

- Git establishes commits, trees, ancestry, changed paths, and topology.
- The filesystem establishes worktree contents and cleanliness.
- The supervisor establishes process exit, timeout, signals, and captured output.
- The validation runner establishes which checks ran and their results.
- Cryptographic hashes establish object identity.
- CAS and evidence bindings establish immutable provenance.
- GitHub-observed comments and reviews establish an external authorization artifact.

Agent reports can explain these facts but should not replace them. Requiring the agent to restate independently observable facts adds protocol surface without increasing confidence.

This principle applies beyond AI systems: participant assertions are weaker than observations produced by an independent control plane.

## 6. Adversarial governance is the valuable core

The workflow's most important principle is:

> Do not trust what the agent says it did; independently verify what actually happened.

A version label is not an executable identity. A digest of the actual bytes is.

A stored artifact is not current authority. A valid authority chain selecting its binding is.

A review saying "accepted" is not human authorization. An independently observed approval artifact bound to the current challenge is.

A process exit of `0` is not a valid candidate. The expected artifact, repository observation, and validation evidence must all exist and agree.

These controls are qualitatively different from formatting contracts. They remain strict because relaxing them can admit forged, stale, unauthorized, or out-of-scope state.

## 7. The trust boundary

The emerging architecture is:

```text
Copilot capability
  -> conservative interoperability adapter
  -> canonical representation only where necessary
  -> independent adversarial governance
  -> deterministic Git/filesystem/test validation
  -> cryptographic provenance and human authorization
  -> auditable state transition
```

Responsibilities:

- **Copilot:** probabilistic planning, coding, testing, review, and explanation.
- **Adapter:** preserves raw output and translates only unambiguous supported forms. It never creates authority.
- **Governance:** decides whether independently established facts satisfy policy.
- **Trusted evidence:** observations from the trusted host over Git, filesystem state, process results, tests, human authorization artifacts, and cryptographic identity.

Variation can be tolerated before the trusted boundary when raw input remains preserved and every downstream invariant is still established independently. After the boundary, ambiguity must fail closed.

Trust here is conditional rather than absolute:

- Git, filesystem, process, and test observations are only as trustworthy as the host, operating system, Git executable, runner, dependencies, and configuration that produced them.
- A cryptographic digest establishes byte identity and tamper evidence; it does not establish that the identified bytes are correct, safe, or honestly produced.
- A GitHub approval artifact establishes that an authenticated account performed an exact platform action. It does not by itself prove review quality, attention, or human intent beyond the platform evidence.
- The issue source is the accepted requirements input, not an oracle of product correctness. Freshness and digest checks establish which issue text was used, not that the text was complete.
- Remote observations depend on GitHub, its APIs, credentials, and reconciliation logic behaving within the documented trust model.

The preference for observation over assertion therefore means **independent of the agent under the declared trusted-control-plane assumptions**, not universally trustworthy ground truth.

The proposed thin-governance direction is also not automatically simpler. A policy profile, relaxability registry, warning artifact, and typed parse outcome add a new trusted subsystem before they remove any old complexity. That increment must itself satisfy the complexity-budget test and remain no larger than the first evidence-backed compatibility experiment requires.

## 8. Proportional failure semantics

Not every failure should have equal authority to stop the system.

| Failure class | Appropriate semantics |
|---|---|
| Unauthorized approval, credential disclosure, policy bypass | Unconditional hard failure |
| Corrupt evidence, digest mismatch, stale authority, wrong executable | Unconditional hard failure |
| Wrong trusted base, ancestry violation, scope escape, unrelated changes | Unconditional hard failure |
| Invalid repository artifact, failed required tests, incomplete validation | Hard correctness failure |
| Ambiguous plan/review where the candidate is the only artifact | Hard producer failure |
| Malformed implementation narrative with a separately valid repository artifact | Potential policy-controlled warning followed by unchanged artifact validation |
| Missing non-authoritative detail or preferred presentation | Advisory quality finding |

The latest `candidate-output-invalid` is architecturally different from authorization, provenance, topology, scope, and test failures. Its underlying Git artifact remained independently inspectable and structurally promising, but it was not yet semantically accepted: the parse pause occurred before the normal test-manifest submission, review, approval, and later validation stages.

That does not justify arbitrary parsing. Free-form extraction of JSON from prose is ambiguous: there may be zero, one, or several JSON-looking objects, and choosing one introduces a new trust decision. The safer compatibility model for implementation phases is to preserve the raw bytes, record the strict failure, create a visibly machine-authored report, and continue only through every existing repository validator.

Conversely, making all failures warnings would erase the adversarial boundary. Proportionality is not permissiveness.

## 9. State-machine design

A workflow state should represent meaningful external, governance, or artifact state:

- a plan exists and has been reviewed;
- a human approval is pending or valid;
- a candidate repository state has been observed;
- validation is complete;
- publication has been reconciled;
- recovery has been explicitly authorized.

It should not automatically mirror:

- every conversational turn;
- every provider-internal event;
- every narrative handoff;
- implementation bookkeeping with no externally meaningful invariant.

Protocol state can still be necessary when it establishes recovery position, exactly-once behavior, authorization context, provenance, or reproducibility. The test is whether the state preserves a meaningful invariant, not whether it is internal or externally visible.

The current repository contains both the active evidence orchestrator and legacy/kernel/migration machinery. That overlap may be necessary during migration, but it illustrates the cost of two state models plus a bridge. Retirement decisions require proof about authority ownership and preserved compatibility, not a cosmetic cleanup.

## 10. Contract taxonomy

Contracts remain justified, but they need different semantics.

| Contract type | Purpose | Default treatment |
|---|---|---|
| Governance invariant | Authorization, integrity, provenance, scope, topology | Unconditional hard |
| Necessary artifact contract | Represents information unavailable elsewhere, such as a plan or review judgment | Strict schema and review |
| Translation contract | Converts an external provider's output into a supported internal form | Conservative adapter; raw input preserved |
| Convenience contract | Simplifies orchestration or reporting | Warning or derived replacement when independently verifiable |
| Advisory contract | Improves human quality or traceability | Non-authoritative finding |

A contract is suspicious when it requires the agent to describe information that the trusted host can derive.

## 11. Where the workflow may duplicate Copilot

Copilot already provides:

- planning and decomposition;
- file selection;
- test authoring and execution;
- implementation;
- Git operations;
- self-review;
- narrative explanation.

ChessEcho legitimately adds:

- independent authorization;
- trusted identity and provenance;
- repository scope and base enforcement;
- topology checks;
- independently executed validation;
- immutable evidence and auditability;
- fail-closed transitions.

Potential duplication exists in:

- requiring implementer narrative reports as if they were authoritative;
- requiring review detail fields beyond what downstream decisions consume;
- modeling extensive conversational/provider event ordering;
- formalizing plan decomposition more deeply than required for review and scope;
- maintaining overlapping legacy and replacement workflow state machinery.

Redundancy is not automatically waste. An agent report that contradicts an independently observed diff could provide a useful divergence signal. The current implementation does not perform that cross-check, so the report has little authoritative value today; future removal should still ask whether a deliberately designed comparison would purchase a meaningful guarantee.

Decision rule:

- **Keep** components that create an independent guarantee Copilot cannot safely provide for itself.
- **Question or simplify** components primarily reproducing deterministic versions of Copilot's native behavior.
- **Remove later** only after evidence shows removal does not compromise governance or correctness.

## 12. Retain, question, and possibly remove

### Keep

- CAS, evidence, authority, and independent inspection;
- trusted host/provider/executable/source verification;
- exact supervised authorization;
- process containment;
- issue-source identity;
- Git base, ancestry, scope, topology, and cleanliness checks;
- independent tests and validation;
- remote publication and PR reconciliation;
- bounded, authorized recovery.

### Question or make policy-sensitive

- implementer report serialization;
- narrowly defined terminal-message presentation differences;
- acceptance self-reference as a traceability-quality rule;
- exact PR prose formatting;
- provider event ordering unrelated to terminal-result identity;
- review detail fields not consumed by governance.

### Possibly remove later

- redundant implementation reports;
- unnecessary transcript micro-validation;
- completed migration compatibility machinery after explicit cutover;
- some self-review stages, but only if human review and deterministic artifact validation demonstrably preserve the intended guarantees.

Removal must follow evidence rather than intuition:

```text
make the narrow adapter tolerant
  -> retain all governance gates
  -> run controlled acceptance
  -> observe what guarantees remain
  -> remove only what evidence proves redundant
```

## 13. Control classification from the gate audit

### A. Unconditional hard controls

- CAS/digest/canonical-byte integrity;
- pointer and authority lineage;
- evidence identity and provenance;
- trusted host/provider/executable/source identity;
- command-source identity and process containment;
- human authorization and mandatory-human gates;
- challenge and authorization-source binding;
- policy/DAG transitions;
- bounded recovery with no bypass;
- trusted issue-source identity;
- trusted worktree continuity and cleanliness;
- trusted Git base, ancestry, and authorized scope;
- the current one-commit topology rule, recognized as a design-imposed normalization and review policy rather than an intrinsic truth property;
- validation completion and required test/build results;
- remote-head and PR reconciliation;
- credential non-disclosure;
- ambiguous, missing, stale, or conflicting authoritative artifacts.

### B. Correctness-hard controls

- invalid plan/review schema when that candidate is the artifact;
- contradictory review verdicts;
- missing, altered, or unmapped trusted acceptance facts;
- malformed or unstable authoritative repository observations;
- invalid deliverable or route;
- incomplete validation.

### C. Candidate policy-controlled controls

- narrowly scoped implementation-phase producer parsing failures when the independently observed repository is authoritative and all downstream checks remain strict;
- individually proven presentation/order incompatibilities that do not make terminal identity ambiguous;
- acceptance-coverage self-reference when exact trusted facts remain preserved, recognizing that this concedes the machine-checked guarantee that each fact maps to a substantive non-metadata unit and shifts that semantic check to review;
- redundant narrative/report formatting.

This classification is a learning outcome, not permission to weaken anything. #237 owns the future implementation decision.

## 14. External comparisons

The comparisons below use primary documentation accessed on 2026-09-13. Facts from those sources are separated from ChessEcho's architectural inference.

### GitHub Copilot cloud agent

GitHub documents that its cloud agent works in an ephemeral GitHub Actions-powered environment and produces a pull request for normal review. Where approvals are required, the user's approval of a Copilot-authored PR does not count toward the required approvals; another reviewer must approve it. GitHub Actions workflows also do not run automatically by default after Copilot pushes changes and require an explicit human approval.

Source:

- [About Copilot cloud agent](https://docs.github.com/en/copilot/concepts/agents/cloud-agent/about-cloud-agent)
- [Review output from Copilot](https://docs.github.com/en/copilot/how-tos/copilot-on-github/use-copilot-agents/review-copilot-output)

**ChessEcho inference:** meaningful authority is enforced by platform review and Actions state outside the agent's prose. This supports coarse external governance rather than trusting a narrative report.

### OpenAI Codex

OpenAI distinguishes sandbox mode—what the agent can technically do—from approval policy—when it must ask before an action. The documented environment restricts writes and network access, and cloud secrets are removed before the agent phase. Safety monitoring may pause a task but does not replace sandboxing, permissions, or review.

Source:

- [Agent approvals and security](https://learn.chatgpt.com/docs/agent-approvals-security)

**ChessEcho inference:** capability containment and authorization should be based on action semantics and externally enforced permissions, not on how the agent describes an action.

### LangGraph

LangGraph documents interrupts as durable control-flow pauses. A checkpointer stores graph state, execution waits for external input, and a resume command continues the same thread. Interrupt payloads and resume values are JSON-serializable.

Sources:

- [Interrupts](https://docs.langchain.com/oss/python/langgraph/interrupts)
- [Persistence](https://docs.langchain.com/oss/python/langgraph/persistence)

**ChessEcho inference:** durable state and explicit human interruption need only a minimal serialization contract. They do not inherently require exhaustive schemas for every agent conversation.

### SLSA provenance

SLSA provenance distinguishes untrusted `externalParameters`, which must be recorded and verified downstream, from platform-controlled `internalParameters`, which inherit trust from the identified build platform. `builder.id` represents the transitive closure of entities trusted to run the build and record provenance.

Source:

- [SLSA provenance v1.0](https://slsa.dev/spec/v1.0/provenance)

**ChessEcho inference:** strict schemas are especially valuable when they let independent verifiers distinguish trusted platform facts from untrusted producer inputs. That is a stronger reason than parsing convenience.

### in-toto attestations

The in-toto attestation specification recommends monotonic policy: ignoring an attestation or field must never turn DENY into ALLOW. It also requires consumers to ignore unknown fields for compatible schema evolution.

Source:

- [in-toto Attestation Framework v1](https://github.com/in-toto/attestation/blob/main/spec/v1/README.md)

**ChessEcho inference:** fail closed on missing positive evidence, but key the decision to the strongest available evidence source. A malformed narrative should not replace or invalidate independently verified Git evidence unless the narrative itself is the required artifact.

### Open Policy Agent

OPA explicitly separates policy decision-making from policy enforcement. The caller supplies structured input, OPA produces a decision, and the caller enforces it.

Source:

- [Open Policy Agent documentation and philosophy](https://www.openpolicyagent.org/docs)

**ChessEcho inference:** producers should not need to restate policy owned by validators. Feed independently observed facts to a central policy decision and keep enforcement separate.

### Reproducible Builds and NIST AI RMF

Reproducible Builds defines reproducibility as independently recreating bit-for-bit identical artifacts from the same source, environment, and instructions, verified through comparison and cryptographic hashes. NIST AI RMF organizes risk work around Govern, Map, Measure, and Manage and supports profiles tailored to context.

Sources:

- [Reproducible Builds definition](https://reproducible-builds.org/docs/definition/)
- [NIST AI Risk Management Framework](https://airc.nist.gov/airmf-resources/airmf/)

**ChessEcho inference:** independent re-derivation and comparison are stronger than self-reporting; control profiles should be proportionate to risk rather than universally maximal.

External systems are comparisons, not authorities for ChessEcho's design. Their existence does not prove that any mechanism is appropriate here.

The comparisons also cut against an overly simple tolerance thesis. SLSA and in-toto depend on strict, versioned, machine-verifiable attestations, and in-toto's monotonic principle requires that ignored or missing information never turn DENY into ALLOW. They support tolerance for compatible representation only when positive trusted evidence still establishes the decision. They do not support weakening an authoritative structured artifact merely because another observation exists.

## 15. Architecture discovery under uncertainty

Not all workflow growth was scope drift. The chronology supports five different mechanisms:

- **A. Intentional scope expansion:** the system deliberately added a new responsibility.
- **B. Reactive formalization:** an observed failure or ambiguity led to a new rule.
- **C. Architecture discovery:** evidence showed that the prior system-boundary model was incomplete.
- **D. Recursive hardening:** a new mechanism created protocol obligations that required further mechanisms.
- **E. Genuine scope drift:** behavior materially departed from the original governance problem.

These mechanism labels are separate from any confidence rating assigned to evidence.

### Mechanism classification

| Mechanism | Classification | Invariant revealed and implementation chosen |
|---|---|---|
| CAS, evidence and authority lineage, trusted identities, exact authorization, and transition policy | **A — intentional scope expansion from known invariants** | The objective already required preventing substitution, stale authority, and unauthorized transition. Cryptographic bindings and explicit gates directly implement those guarantees. |
| Git base, ancestry, scope, cleanliness, independent validation, remote reconciliation, and credential non-disclosure | **C — architecture discovery of how to establish known invariants** | The required properties were known; implementation moved their proof from agent assertion to host observation. |
| Initial exact candidate-output assumptions | **B — reactive formalization** | The real invariant was unambiguous candidate identity. Requiring one exact producer representation was only one possible implementation. |
| #183's structured JSONL adapter | **C — architecture discovery** | Real output showed that intermediate conversation and the terminal candidate needed separation. The adapter was a justified correction; its terminal identity, completeness, and credential protections remain load-bearing. |
| Later JSONL metadata, reasoning-order, denied-tool, subagent, and capacity adjustments | **C/D — observed compatibility discovery with recursive-hardening risk** | Some changes established real terminal causality or bounded transport; others formalized provider bookkeeping whose architectural value remains uncertain. |
| Acceptance facts and plan mapping | **A/B, with E risk in representation** | Preserving every trusted fact was intentional and valuable. Exhaustive line units and self-reference rules were reactive representations; allowing those representations to stop the workflow moved toward scope drift. |
| One-commit implementation contract and producer-side history normalization | **B/D** | Trusted-base ancestry and authorized scope are real invariants. Exactly one commit is a chosen review/normalization policy that created additional producer obligations. |
| Supervised-gate operator correction | **C — architecture discovery** | The gate validator was correct, but the acceptance coordinator had acted as the human. The failure clarified that artifact validity does not establish independent human intent. |
| Latest implementation-phase `candidate-output-invalid` | **C — architecture discovery** | It exposed the phase-specific distinction between candidate-as-artifact and candidate-as-narrative. The repository was observable, but the workflow stopped before its normal semantic gates. |
| Parallel legacy/kernel/migration and active orchestration machinery | **A — intentional migration cost, with D risk** | Compatibility and preservation motivated the overlap. It becomes drift only if retained after authority ownership and migration no longer require it. |

### Evidence-backed chronology

The transport history is more precise than a generic scope-drift story:

```text
governance objective
  -> incomplete model of Copilot output
  -> real runs show intermediate prose and structured events
  -> #183 separates transport events from the terminal candidate
  -> further runs expose additional provider event shapes
  -> compatibility rules accumulate
  -> latest #198 run produces an unambiguous terminal message whose content
     is prose plus fenced JSON while Git independently records a candidate
  -> the phase-specific artifact boundary becomes visible
```

#183 was not merely premature hardening. It corrected the mistaken assumption that complete human-readable stdout could safely be treated as candidate bytes and preserved the sound prohibition on choosing arbitrary JSON fragments. The remaining question is whether every final-message presentation error must halt an implementation phase whose authoritative artifact is independently observed Git state.

The planning history followed another path:

```text
preserve trusted issue facts
  -> map each fact into a reviewable plan
  -> encode exhaustive line units and acceptance metadata
  -> reject self-reference into the metadata block
  -> clarify the producer prompt after #198 exposes the representation rule
```

Fact preservation was intentional. The exact representation became reactive formalization and then recursive hardening. The failure revealed a traceability invariant; the separate metadata-unit prompt was an implementation choice, not the invariant itself.

The implementation history similarly separated:

```text
trusted base and authorized final scope
  -> exactly-one-commit normalization policy
  -> natural test and production commits violate the chosen topology
  -> producer prompt requires history normalization
```

The trusted base and scope are integrity properties. The one-commit rule is a review and history policy whose cost and benefit should be evaluated separately.

### What was understood, and what was discovered

- **Understood at the beginning:** autonomous changes required explicit authorization, bounded capability, provenance, trusted identity, scope control, independent validation, and auditable transitions.
- **Understood only after building:** the shape and variability of Copilot transport; which provider events matter to terminal identity; the difference between candidate-as-artifact and candidate-as-narrative; the operational distinction between a valid approval artifact and independent human intent; and how producer contracts create their own failure surface.
- **Controls designed from known invariants:** CAS/digests, evidence and authority lineage, exact authorization challenges, trusted executable/source identity, scope and ancestry, independent validation, and remote reconciliation.
- **Controls reacting to symptoms:** exact final-message formatting, some internal JSONL grammar, exhaustive plan representation, metadata self-reference handling, and producer-side one-commit normalization.
- **Mechanisms clarified by #198:** planner traceability representation, supervised operator separation, implementation topology, and the narrative-versus-repository artifact distinction.
- **What should have been observed before formalizing:** actual terminal-message and event-shape distributions; which fields downstream logic consumes; whether phase outputs are authoritative artifacts or redundant narratives; and whether a topology preference protects security, reviewability, or only presentation.
- **What correctly required immediate constraint:** authorization, credential isolation, trusted identity, evidence immutability, scope, ancestry, irreversible publication, and any transition that could make unverified state authoritative.

The preferred sequence under uncertainty is:

```text
observe -> instrument -> model -> identify invariant
  -> independently verify -> constrain -> optimize
```

This is not universal. Security, authorization, integrity, provenance, and irreversible actions need conservative constraints before experimentation. Reversible provider presentation and conversational behavior should usually be observed before being elevated into architectural policy.

The evidence supports a bounded paradox: mechanisms intended to prevent agent scope drift contributed to workflow scope growth when the interoperability boundary was still poorly understood. The same reflex was stabilizing at the trust boundary and recursive at the communication boundary.

The lesson is therefore not simply “avoid scope drift.” It is:

> It is possible to be rigorous about an architecture that is not yet understood. Sequence understanding before invariant selection and control design wherever reversible observation is safe.

## 16. Overengineering as a learning asset

The work was not wasted merely because some mechanisms may later be simplified.

The workflow created a controlled environment for learning:

- where strictness blocks real authorization or integrity failures;
- where strictness creates compatibility-only failures;
- where independent observation is stronger than assertion;
- where contracts become brittle;
- how complexity creates recursive failure modes;
- how preserved failures expose architectural boundaries;
- how governance differs from orchestration;
- how to audit a system against its own stated purpose.

> The parts we eventually remove may teach us as much as the parts we keep.

The issue and evidence history should preserve why a mechanism existed, what it protected, and what evidence later justified changing it.

## 17. The self-audit lesson

The workflow was designed to ask:

> Can we trust what the agent claims?

The audit applies the same discipline inward:

> Can we trust that our mechanisms remain justified by the original objective?

Systems require periodic audits for relevance and proportionality, not only implementation correctness. A passing test proves conformance to the current design; it does not prove that the design still solves the right problem.

## 18. Reusable engineering checklist

Before adding a gate, contract, state, validator, or artifact, answer this smaller set:

1. What invariant and threat are we addressing?
2. Is it a security, authorization, integrity, provenance, scope, correctness, interoperability, or quality concern?
3. Who or what can establish the fact independently, and under which trust assumptions?
4. Is the agent being asked to assert something Git, files, process results, tests, or evidence already establish?
5. What unique guarantee does the complexity purchase, and what new failure/recovery surface does it create?
6. Is failure severity proportional to the consequence?
7. Can communication become tolerant while downstream governance remains strict?
8. Are we duplicating agent capability, or adding an independent guarantee?
9. Does this state represent recoverable/authorizable system state or merely conversation protocol?
10. Does redundancy provide a valuable cross-check, or only duplicate bookkeeping?
11. What guarantee would disappear if the mechanism were removed, and what evidence would justify removal?
12. Are we solving the original problem or compensating for complexity introduced by an earlier solution?

Most importantly:

> Before adding a mechanism to solve a failure, determine whether it solves the original problem or merely compensates for complexity introduced by previous mechanisms.

## Relationship to #237

This issue records **why** the producer/governance boundary matters. #237 records one possible **implementation** of that separation.

The reasoning here should remain valid even if #237's implementation changes substantially:

- Copilot output remains untrusted.
- Raw communication remains preserved.
- Independently observed facts remain authoritative.
- Compatibility cannot weaken governance.
- Strict behavior remains the default until an explicitly reviewed policy says otherwise.

Do not duplicate #237's implementation design here. Use later #237 review and E2E evidence to update the retained/simplified/removed decisions in this record.

## Decision record

As of 2026-09-13:

- **Retain brutally strict:** authorization, cryptographic identity, provenance, authority, scope, topology, validation, and reconciliation.
- **Investigate first:** tolerant producer interoperability where an independent artifact exists.
- **Do not do:** heuristic acceptance of arbitrary output, broad ignore-errors behavior, or trust in agent narrative.
- **Do not run another strict #198 E2E merely to discover the next presentation mismatch.**
- **Implement no simplification from this issue.** #237 remains the separately reviewed implementation track.
- **Remove nothing yet.** Simplification requires evidence from the compatibility experiment.

## Future updates

Append evidence-backed decisions here after #237 and subsequent controlled runs:

- which controls were retained and why;
- which communication boundaries became tolerant;
- whether the next failures concerned real artifact correctness or more protocol surface;
- which components, if any, became demonstrably redundant;
- which external comparisons materially changed a ChessEcho decision.

This record should answer the future question:

> Why did we simplify this workflow without weakening its adversarial guarantees?
