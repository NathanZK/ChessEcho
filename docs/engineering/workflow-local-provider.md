# Trusted-local workflow provider

`scripts/workflow_local_host.py` is the only Phase 1 production entry point for
the replacement orchestrator. It installs the runtime, local execution, and
pending-result providers explicitly; `workflow_orchestrator.py` remains
fail-closed when imported or invoked without those providers.

## Security boundary

Phase 1 trusts the local operator and treats every coding-agent response and
workspace change as untrusted candidate material. The host preserves the
existing authority pointer, expected-tip, immutable evidence, runtime
reconstruction, independent validation, and human-gate contracts.

The local provider is **not an OS sandbox**. It does not isolate the network,
the host filesystem, credentials available to the same operating-system user,
or the authority store from a hostile same-UID process. Its process guarantee
is limited to the bounded POSIX process-group behavior reported by
`workflow_supervisor.py`. Containers, VMs, namespaces, cgroups, Seatbelt,
hostile filesystem isolation, hardened credential isolation, and production
cloud execution remain Phase 2 work in issue #160.

## Reviewed control plane

Run the host only from a clean control checkout whose `HEAD` equals
`refs/remotes/origin/main`. The host must be started with isolated Python mode:

```bash
/usr/bin/python3 -I /path/to/reviewed/ChessEcho/scripts/workflow_local_host.py ...
```

Before importing workflow modules, the host verifies:

- its own source identity and the provider identity against the base-pinned
  `.github/agent-workflow.json`;
- the exact Python and coding-agent executable identities;
- every controller module against the selected control commit;
- a clean control checkout with no source replacement; and
- active Phase 1 configuration.

It then removes the caller cwd from `sys.path` and imports only from the
reviewed control checkout. Candidate `scripts/**`, `.github/**`, `PYTHONPATH`,
and executable-path substitutions therefore cannot become the current or a
fresh process's controller. Missing, changed, malformed, or mismatched inputs
fail closed.

## Dedicated worktree

Choose explicit host paths:

```bash
CONTROL=/path/to/clean/ChessEcho
WORKSPACES="$HOME/.local/state/chess-echo/workspaces"
RESULTS="$HOME/.local/state/chess-echo/results/issue-176"
GIT=/absolute/path/to/git
GH=/absolute/path/to/gh
AGENT=/absolute/path/to/the/pinned/copilot
AGENT_HOME="$HOME/.local/state/chess-echo/worker-home"
```

Create `AGENT_HOME` as an empty operator-owned directory with mode `0700` before
each command that may launch an agent. The provider rejects a populated or
less-restricted directory. Do not point it at the operator's normal home or copy
`.copilot`, `.config/gh`, SSH, signing, workflow, or publication credentials
into it. This prevents accidental ambient CLI fallback; it is not same-UID
hostile-process isolation.

## Trusted local worker authentication

The base-pinned host configuration declares
`trusted-local-development-v1`. In this mode the operator intentionally trusts
the local coding agent with its authentication credential. Agent output and
candidate repository changes remain independently validated, but the worker is
not treated as hostile to the credential and no OS-level credential isolation
is claimed.

Authentication is absent by default. For a `step` that may launch an agent,
pass `--trusted-worker-auth-stdin` and provide the coding-agent credential as
the second standard-input line, after the trusted runtime's GitHub token. The
host reads that line lazily only when the orchestrator requests the agent
provider, then injects it as `COPILOT_GITHUB_TOKEN` into the agent environment.
It never inherits `GH_TOKEN`, `GITHUB_TOKEN`, ambient credentials, or generic
environment values.

The credential value is not placed in argv, prompts, requests, provider facts,
workflow evidence/state, pending-result records, logs, or repository files.
Persistent facts record only the configured mode, trust assumption, and secret
environment key name. Missing mode opt-in or a missing/malformed credential
fails before agent launch. The agent command disables built-in MCPs, marks
`COPILOT_GITHUB_TOKEN` secret, and disables logging.

This is a local-development trust decision, not hostile-process containment.
Phase 2 issue #160 remains responsible for credential-backed execution of an
untrusted worker.

## #176 controlled E2E protocol discovery

The following incidents are chronological results from fresh, controlled
issue-176 E2E runs against the real Copilot CLI. The first runs established the
argument and authentication boundaries; later runs used the resulting explicit
trusted-worker authentication path. These are not hypothetical protocol
assumptions derived only from fixtures or SDK types. Where an early run stopped
before immutable transport publication, the durable identifier is the merged
correction or observed commit rather than an invented run ID. No credential
values or machine-local evidence paths are recorded here.

### 1. Generic argument limit versus the operation-specific prompt bound

**Problem:** The first planner claim after PR #177 launched with a generated
prompt larger than the generic 4 KiB command-part limit. **Evidence:** The fresh
run against merged `main` at `615b1f6917be9205f27124c4dae5ad928fa8d72d`
failed during runtime result validation after provider execution; the provider
allowed its prompt while the runtime rejected the same argv element.
**Root cause:** Two validators applied different limits to the provider-owned
prompt. **Why the existing design failed:** It treated a generated operation
payload like an arbitrary configured command argument even though the provider
had already defined a separate bounded prompt contract. **Decision:** Keep the
generic 4 KiB limit for every other command part and add a shape- and
position-specific 64 KiB allowance only for the trusted-local `--prompt`
argument. **Actual fix:** PR #179 aligned provider production and runtime
revalidation around that operation-specific bound without weakening executable,
argv-shape, or source-identity checks. **Validation:** Focused tests exercised
an exact 22,090-byte planner prompt, the 64 KiB ceiling, and continued rejection
of oversized generic arguments; the next fresh run passed this boundary.
**Lesson:** A generated operation payload needs one explicit contract that is
revalidated consistently at every boundary.

### 2. Trusted-local worker authentication boundary

**Problem:** The next fresh planner run reached the pinned Copilot process, which
exited 1 because its deliberately controlled environment contained no
authentication credential. **Evidence:** The post-PR-#179 run produced no
candidate and was correctly recorded as `attempt-not-successful`; it did not
fall back to ambient `gh`, shell, or user-home credentials. **Root cause:** The
provider's environment isolation was working, but Phase 1 had no explicit,
reviewed path for granting the trusted local worker its Copilot credential.
**Why the existing design failed:** It conflated denying ambient credentials
with supplying the one credential required by an explicitly trusted worker.
**Decision:** Make authentication an operator opt-in trust boundary, separate
from GitHub runtime credentials and candidate trust. **Actual fix:** The host
accepts `--trusted-worker-auth-stdin`, reads the credential lazily, injects only
`COPILOT_GITHUB_TOKEN` into the worker, and persists only redacted mode and key
metadata. **Validation:** Later controlled runs authenticated successfully while
missing, malformed, or disclosed credentials remained fail-closed. **Lesson:**
Process isolation and credential provisioning are distinct contracts; neither
implies the other.

### 3. `--silent` was not a candidate-output contract

**Problem:** An authenticated run emitted two prose progress messages before an
otherwise valid JSON plan, although the CLI was invoked with `--silent`.
**Evidence:** The strict candidate decoder rejected the whole stdout stream;
strengthening the prompt to prohibit progress output did not prevent the same
shape in a fresh authenticated run. **Root cause:** `--silent` changes
presentation behavior but does not guarantee that process stdout is exactly one
candidate document. **Why the existing design failed:** It treated a CLI
presentation option and prompt instruction as an enforceable byte-level output
contract. **Decision:** Preserve strict candidate decoding and stop using
human-readable stdout as the candidate protocol. **Actual fix:** The temporary
prompt hardening made the desired behavior explicit, but the durable correction
was to move the provider boundary to structured JSONL rather than strip prose or
select the last JSON object. **Validation:** Focused tests retained rejection of
prose-prefixed candidates, and later real-CLI runs exercised the structured
path. **Lesson:** Candidate validity must come from a validated protocol
boundary, not a quiet-mode flag or model compliance.

### 4. Strict JSONL transport

**Problem:** The provider needed to distinguish intermediate messages, tools,
and lifecycle records from the authoritative final candidate without heuristic
recovery. **Evidence:** An authenticated disposable probe of the pinned CLI
showed LF-terminated JSONL with separate message/tool events, a root final
`assistant.message`, matching `assistant.turn_end`, ephemeral
`assistant.idle`, and terminal `result` with `exitCode: 0`; `session.idle` was
not present or required. **Root cause:** Raw process output carried a protocol,
while the adapter treated it as an undifferentiated candidate byte string.
**Why the existing design failed:** “Last JSON” extraction or prose filtering
would have detached candidate selection from authenticated turn and terminal
boundaries. **Decision:** Adopt an explicit, bounded, fail-closed JSONL decoder
for the empirically observed pinned-CLI protocol and keep the downstream
candidate decoder unchanged. **Actual fix:** The adapter validates framing,
event schemas, identities, tool lifecycles, final boundaries, idle, and result,
then passes only the exact final `data.content` bytes downstream. **Validation:**
Malformed, unknown, truncated, causally invalid, unresolved-tool, and nonzero
result fixtures remain rejected; subsequent authenticated runs progressed to
more specific protocol mismatches instead of bypassing them. **Lesson:**
Transport decoding and candidate schema validation are separate strict stages.

### 5. Stdout retention, output limits, and the sidecar architecture

**Problem:** Complete successful work could exceed the supervisor's retained
stdout budget, causing the reader to close the pipe and convert volume into
process termination and truncated evidence. **Evidence:** Controlled planning
runs on 2026-09-08 reached the exact 384 KiB and then 448 KiB caps and ended with
truncated JSONL. A later run reached 851,968 bytes; Copilot's durable lifecycle
showed 22 turn starts but only 21 turn ends after the pipe was closed. **Root
cause:** Process lifetime, parser input, retained transport, and the 2 MiB
execution-result document budget were represented by one buffer. **Why the
existing design failed:** Raising a single limit merely moved the failure and
Base64-inlining complete raw stdout could exhaust the result document.
**Decision:** Stream supervision output through independent secret scanning,
bounded raw retention, and incremental decoding, then publish complete raw
transport as a same-manifest sidecar. **Actual fix:** Provider 1.5.0 introduced
the transport sink, independent event/transport/candidate bounds, typed parser
failure evidence, and
`workflow-orchestration/copilot-transport.jsonl` without inlining raw bytes in
the result document. **Validation:** Run
`issue-176-20260909T174914Z-460f8d22-ed07-46bc-8b00-85f0f77e05f5`
completed with exit 0 and preserved a complete 584,784-byte, 1,366-record
sidecar with SHA-256
`9b2230c90889bb9b58e74e2c090b6bd0fad87cd54018cb850f69e56cae84988e`.
**Lesson:** Observation, retention, decoding, process supervision, and evidence
publication need independent limits and identities.

### 6. `--stream off` did not suppress every delta or partial event

**Problem:** Disabling streaming did not make JSONL small or remove all
incremental event classes. **Evidence:** The bounded real-CLI run retained zero
`assistant.message_start` and `assistant.message_delta` records but still
contained 1,076 `assistant.tool_call_delta` records (304,883 bytes), 103
`assistant.reasoning_delta` records, 204
`session.background_tasks_changed` records, and 33
`tool.execution_partial_result` records; transient records accounted for
510,590 bytes. **Root cause:** For the pinned CLI, `--stream off` controls
message streaming rather than the complete structured event stream.
**Why the existing design failed:** It assumed an argv flag could enforce the
adapter's transport-volume and event-class contract. **Decision:** Keep the
single reviewed `--stream off` argv position, but accept and strictly validate
the observed bounded event classes instead of relying on the flag for
suppression. **Actual fix:** The JSONL FSM models those classes and the sidecar
architecture carries their complete bytes without making retention a process
kill switch. **Validation:** Focused large-stream tests and the complete
584,784-byte authenticated transport show that transport correctness no longer
depends on all deltas disappearing. **Lesson:** CLI flags must be validated by
observed traffic; their names are not protocol guarantees.

### 7. Forward-compatible `assistant.message.data` metadata

**Problem:** Provider 1.5.0 rejected an otherwise valid
`assistant.message.data` object because the real CLI added metadata fields the
adapter did not consume. **Evidence:** Run
`issue-176-20260909T174914Z-460f8d22-ed07-46bc-8b00-85f0f77e05f5`
failed at JSONL record 153 with
`local-agent-jsonl-invalid`; observed additions included `apiCallId`,
`clientRequestId`, `model`, `outputTokens`, `reasoningOpaque`,
`reasoningText`, `requestId`, `rte`, and `serviceRequestId`. **Root cause:** The
decoder modeled the nested message data as an exhaustive schema rather than a
strict schema for consumed fields. **Why the existing design failed:** It made
uninterpreted provider metadata part of the adapter's compatibility surface.
**Decision:** Require and type-check every consumed message field and identity,
while tolerating additional unconsumed metadata in that nested object.
**Actual fix:** Provider 1.5.1 retained strict `content`, `messageId`, `turnId`,
`interactionId`, and `toolRequests` validation and the unchanged final-candidate
contract while allowing extra metadata. **Validation:** PR #192 replayed the
immutable capture and focused negative fixtures; a fresh merged-main run passed
the former record-153 failure. **Lesson:** Strictness belongs on interpreted
semantics and trust boundaries, not on harmless evolution of opaque metadata.

### 8. Valid `assistant.reasoning_delta` to `assistant.message_start`

**Problem:** The next merged-main run rejected
`assistant.reasoning_delta` followed by `assistant.message_start`, insisting
that reasoning deltas end at a tool call. **Evidence:** Run
`issue-176-20260909T200342Z-a5f06116-47c9-4235-9b3e-01e31b384aee`
failed at record 5,117 with `local-agent-jsonl-sequence`; the complete
1,764,601-byte, 5,254-record transport had exit 0, a terminal result, and all
22 turn starts matched by turn ends. The observed path was
`assistant.reasoning_delta` -> `assistant.message_start` ->
`assistant.message_delta` -> `assistant.message` ->
`assistant.reasoning` -> `assistant.turn_end`. **Root cause:** The FSM encoded
one observed reasoning outcome--a tool-call path--as the only valid outcome.
**Why the existing design failed:** It confused a previously observed event
transition with an exhaustive protocol rule. **Decision:** Add only the
reasoning-linked streamed-message path, with explicit message identity, parent,
delta, summary, and turn-end constraints; do not make ordering generally
permissive. **Actual fix:** Provider 1.5.2 added that strict
substate while preserving the reasoning-to-tool path and every final-boundary
check. **Validation:** Focused fixtures accept both valid reasoning outcomes,
reject missing, mismatched, or intervening message events, and replay the
immutable transport to extract the exact candidate bytes. This implementation
is **not yet proven end-to-end**; a fresh authenticated E2E after merge must
establish that. **Lesson:** Empirical FSMs should evolve by adding narrowly
bound observed paths, never by weakening ordering globally.

### 9. Worker home is per execution, not per controlled run

**Problem:** The first authenticated run after PR #193 completed planning but
the plan reviewer could not launch because the driver passed the planner's
populated worker home to a second agent operation. **Evidence:** Run
`issue-176-20260909T213027Z-33e62687-488c-4654-8cef-7bac8fba0207`
advanced from `PLANNING` to `PLAN_REVIEW`; the planner exited 0 with complete
transport and an accepted candidate, then reviewer launch failed as
`denied / agent-home-not-dedicated`. **Root cause:** The bounded driver baked
one `--agent-home` value into its shared host command prefix for the entire
run. **Why the existing design failed:** The run-level directory lifetime was
mistaken for the provider's execution-level isolation boundary, even though
Copilot legitimately populates its home during a successful operation.
**Decision:** Preserve the provider's strict guard and allocate a new worker
home for every automatic agent-producing step. **Actual fix:** The driver's
`--agent-home` now identifies a private allocation root; planner, reviewer,
test-author, implementer, and final-review steps each receive an atomically
unique empty `0700` child outside both worktrees. Prior homes remain preserved
but are never reused. **Validation:** Focused driver tests exercise consecutive
planner and reviewer operations, repeated allocation after a prior home is
populated, uniqueness, emptiness, ownership, and permissions; provider tests
continue to reject populated, nested, or non-private homes. This correction is
not yet proven end-to-end. **Lesson:** Provider isolation requirements apply to
each automatic execution independently; a controlled E2E run is an
orchestration scope, not a reusable worker-home scope.

### 10. Unresolved parent on a denied tool execution

**Problem:** Copilot CLI 1.0.83-5 emitted a background-task event whose
`parentId` did not identify any event in the complete structured transport.
**Evidence:** In run
`issue-176-20260909T222717Z-791e7076-c09e-4167-a31f-7f4a8665183d`,
JSONL line 1,414 is `session.background_tasks_changed` with parent
`50fdf231-32df-4041-8b17-287f7918534e`. That ID never appears as an emitted
event. Line 1,415 uses the same unresolved parent for
`tool.execution_complete`, whose payload reports `success: false` and a denied
tool execution. The transport is complete: 2,442 records, 1,169,097 bytes,
SHA-256
`89fca8937350f6cbdcc3a542201818ffe23fd7d23fa8dafe18bcf5afc282db73`,
20 balanced turns, `assistant.idle`, and a terminal `result` with exit code 0.
**Root cause:** The CLI emitted an event relationship whose parent is absent
from its own complete JSONL output. The immutable stream does not establish
what the missing node represents; interpreting it as a permission-decision
node would be speculation. **Why the existing design failed:** The external
event stream did not satisfy the adapter's reviewed causal-closure contract.
This was not transport truncation, supervisor output loss, or an adapter
reconstruction failure. **Decision:** Classify the stream as a genuine protocol
violation at the current adapter boundary and as unsupported Copilot event
behavior. Preserve the unresolved-parent invariant; do not exempt, normalize,
synthesize, rewrite, or infer the missing parent. Even if line 1,414 were
exempted, the denied completion at line 1,415 independently fails the accepted
successful tool-execution contract. **Actual fix:** None. The evidence is
insufficient to justify an adapter compatibility change. **Validation:** The
immutable sidecar was searched across all event IDs and parent references,
replayed through the strict decoder, and checked for terminal LF, balanced
turns, idle, and terminal result. The rejection remained
`corrupt / local-agent-jsonl-parent` at line 1,414, and the sidecar retained its
record count, byte count, and digest. **Lesson:** An adapter must not
manufacture causal history merely to accommodate an undocumented external
protocol pattern.

### 11. Opaque denied-tool transition established by black-box probes

**Problem:** A second fresh E2E reproduced the unresolved-parent sequence twice,
but the two workflow runs alone could not establish whether it was a stable
Copilot denial protocol or coincidental malformed output. **Evidence:** Run
`issue-176-20260909T224355Z-beabbd89-3670-4b3a-8b10-5be6c18ffd87`
contained the same sequence at records 1,253-1,254 and 1,306-1,307. Two
independent authenticated black-box probes,
`copilot-denied-tool-probe-20260909T230006Z-d14da919-d9c4-41e9-bc61-03b27c6d20f6`
and
`copilot-denied-tool-probe-20260909T230034Z-df0052a0-9635-425e-aebf-17741fe512a4`,
then asked the pinned Copilot CLI 1.0.83-5 to execute the same bounded Bash
command against filesystem root under the production `--no-ask-user` JSONL
flags. Both probes emitted a known `tool.execution_start`, zero or more
normally parented background events, one
`session.background_tasks_changed` with a fresh un-emitted parent, and an
immediately following `tool.execution_complete` with that same parent,
`success: false`, `error.code: denied`, the message `Permission denied and
could not request permission from user`, and
`shell_error_category: permission_denied`. Both complete transports continued
through balanced turns, `assistant.idle`, and terminal `result` with exit 0.
Across both probes and both E2Es, the behavior reproduced in all five observed
denials. **Root cause:** Copilot 1.0.83-5 exposes a stable denied-tool boundary
whose correlation parent is not part of the emitted JSONL event set. The
parent's internal meaning remains unknown. **Why the existing design failed:**
The adapter correctly rejected unresolved causality before independent traffic
established that this exact two-event denial boundary was systematic, so it
could not distinguish that bounded provider behavior from an arbitrary missing
parent or unsuccessful tool. **Decision:** Recognize only the observed
opaque-parent background event followed immediately by its matching denied
tool completion while one known tool execution is active. Keep the UUID as an
opaque correlation token: never add it to emitted IDs, synthesize an event, or
name its semantics. Continue rejecting every other unresolved parent and
unsuccessful completion. **Actual fix:** Provider 1.5.3 adds a bounded pending
denial state that binds the opaque parent, active tool call, turn, and
interaction. The next event must be the matching completion with the exact
denial and permission classification; normal causal validation resumes from
that completion's real emitted ID. **Validation:** Exact event subsequences
from both immutable probes pass the decoder, strict mutations of the parent,
tool call, ordering, success value, error, telemetry, active-tool context, and
reuse remain rejected, and both immutable E2E transports replay through this
specific boundary without weakening candidate or terminal validation. This is
not proof that the complete #176 workflow succeeds; a fresh authenticated E2E
after review and merge must establish that separately. **Lesson:** Repeated
black-box observations can justify an explicit compatibility state without
manufacturing the provider's omitted causal event.

The durable architectural lesson is that the provider adapter is an explicit
protocol boundary whose assumptions must be validated against real provider
traffic. Transport framing, event sequencing, candidate extraction, process
supervision, authentication, and evidence publication are separate contracts
and must not be conflated.

### 12. A named schema is not a communicated schema

**Problem:** The first controlled #198 implementation workload reached
`PLAN_REVIEW`, completed the Copilot process and JSONL transport normally, and
extracted and bound the final candidate, but the orchestrator rejected that
candidate as `candidate-output-invalid`.

**Evidence:** The immutable candidate used the correct format and review kind
but supplied `verdict: "approve"`, omitted the required `pr` object, and added
workflow metadata that the exact decoder does not permit. The provider prompt
required a candidate matching `chess-echo-orchestrator-agent-candidate-v1` but
did not state its fields, verdict vocabulary, finding shape, or operation-level
PR requirements. A prior reviewer happened to emit the accepted five-key shape
under the same incomplete prompt; that successful guess did not establish a
communicated contract.

**Root cause:** The strict schema existed only at the downstream authority
boundary and in deterministic fixtures. The live agent received the schema
name, not the schema.

**Why the existing design failed:** Transport authentication and candidate
extraction correctly preserved exactly what Copilot emitted, and the strict
decoder correctly rejected it. The missing upstream contract made valid output
dependent on the model independently guessing private decoder requirements.

**Decision:** Keep exact-key, verdict, finding, PR, duplicate-key, byte/digest,
and fail-closed validation unchanged. Provider 1.5.4 adds the exact review
candidate JSON Schema to prompts for `review-plan`, `review-tests`, and
`review-final`. All three require exactly `format`, `kind`, `verdict`,
`findings`, and `pr`, forbid additional outer keys, and name the three allowed
verdicts and exact finding shape. Plan and test review identify `pr` as a
required object that may be empty because those operations do not consume its
metadata. Final review requires exactly nonempty `head_ref`, `title`, and
`body`, with the body limited to nonempty `What`, `Why`, and `Testing`
sections.

**Actual fix:** Merged
[PR #199](https://github.com/NathanZK/ChessEcho/pull/199) makes the provider
construct the operation-specific contract and embed its canonical JSON
representation in the live prompt. Focused tests inspect each review
operation's contract and prove that a prompted candidate shape is accepted by
the unchanged decoder.

**Validation:** The focused provider prompt and candidate-contract tests, the
orchestrator review-candidate tests, and the workflow tooling checks cover the
boundary.

**Lesson:** A named schema is not a communicated schema. A strict decoder
cannot safely expect an agent to satisfy constraints that the agent was never
actually given. Strict gates remain necessary because model output is
untrusted; the correction makes the upstream contract explicit rather than
weakening the downstream boundary.

## Copilot JSONL transport decision

Issue #176 exposed two independent Phase 1 transport failures. The initial
prompt could exceed the generic 4 KiB command-part limit, so the reviewed
provider argv received a dedicated 64 KiB prompt bound. After that correction,
the first authenticated canary showed that treating all human-readable stdout
as candidate bytes included intermediate prose before a valid candidate. The
candidate decoder remained strict, and the prompt was hardened to prohibit
intermediate output. A fresh authenticated canary then reproduced the same
shape, proving prompt-only enforcement insufficient.
Issue #183 records the resulting provider-boundary correction.

A credential-free disposable probe could confirm command availability but not
the authenticated event lifecycle, so its result was inconclusive. The
subsequent authenticated disposable probe used Copilot CLI 1.0.80 at SHA-256
`fe779da7dd2342c1d23f0744873fa27d0251eaaee4dc6637fa53093639c0f3c9`.
With `--output-format json`, the pinned executable emitted LF-terminated JSONL.
`--silent` retained the relevant structured records; it changed only the
number of streaming delta records in the observed runs.

The trusted-local provider version 1.4.1 fixed the supervised argv to include
exactly one ordered `--stream off` immediately before `--prompt`. The immutable
issue-176 run then proved that flag insufficient in JSON mode. Its preserved
851,968-byte transport contains zero `assistant.message_start` and zero
`assistant.message_delta` records, but still 1,076 `assistant.tool_call_delta`
(304,883 bytes), 103 `assistant.reasoning_delta`, 204
`session.background_tasks_changed`, and 33 `tool.execution_partial_result`
records. Transient `ephemeral: true` records account for 1,455 events and
510,590 bytes, 59.9 percent of the stream. `copilot --help` for the pinned
1.0.80 executable documents no flag that suppresses those records, so transport
volume cannot be controlled at the argv boundary. The argv is unchanged.

The provider distinguishes documented framing from observed schema. JSONL is
the documented transport format. The accepted event allowlist, fields,
relationships, and terminal sequence are empirical behavior of the pinned
1.0.80 executable and must be re-reviewed before changing that pin. The
observed lifecycle contains optional ordered startup and progress records, one
user message, assistant turns, correlated tool requests and executions, and a
root final `assistant.message`. That final message must have nonempty
`data.content` and
`data.toolRequests: []`, and must be completed by a matching
`assistant.turn_end`, ephemeral `assistant.idle`, and terminal `result` with
`exitCode: 0`. `assistant.idle` is distinct from `session.idle`; the latter was
not emitted and is not required. Startup parent records may be absent from
stdout, and ephemeral siblings may share a persisted parent, so validation
checks the observed causal graph rather than imposing a physical-line chain.
The decoder treats the consumed `assistant.message.data` fields (`content`,
`messageId`, `turnId`, `interactionId`, and `toolRequests`) as required and
continues to validate their types, identities, and relationships. Additional
provider metadata fields in that object are tolerated because they do not
affect candidate selection or protocol state. The same rule applies to
unconsumed metadata on individual tool-request objects, while `toolCallId`,
`name`, `type`, and `arguments` remain required and retain their existing
validation. The unconsumed `tool.execution_start.data.shellToolInfo` metadata
is optional but must remain an object when present. Event envelopes and other
event types remain closed; validation of all other event payloads is unchanged.

The bounded issue-176 run exposed additional exact pinned-CLI shapes that had
previously been masked by transport truncation. The three ordered startup
records are ephemeral and carry exactly `servers`, `skills`, and `model`
payloads; their nonempty parent IDs are distinct and remain unresolved in the
emitted stream. Each observed ephemeral `model.call_start` immediately follows
its active turn start and binds that parent and turn ID. Ephemeral
`assistant.reasoning_delta` records form one contiguous, turn-bound reasoning
group after a model call. They may lead either to a tool-call delta or to the
normal streamed final-message sequence (`assistant.message_start`, one or more
`assistant.message_delta` records, and `assistant.message`). An ephemeral
`assistant.reasoning` summary has exactly `content`, `reasoningId`, and
`rte: true`, immediately follows the corresponding assistant message and binds
that message as parent and a prior delta group. A tool-bearing message's
summary must immediately precede tool execution; a final candidate message's
summary must immediately precede its matching turn end. These records are
validated as exact reviewed schemas and causal relationships; unknown
reasoning records are not ignored.

Copilot 1.0.83-5 also has one explicitly modeled denied-tool transition. While
one known tool execution is active, an empty ephemeral
`session.background_tasks_changed` may introduce a fresh opaque parent that has
not been emitted, but only when the immediately following event is the matching
`tool.execution_complete` with `success: false`, `error.code: denied`, and the
reviewed permission-denied classification. The adapter binds that pair to the
active tool, turn, and interaction, never inserts the opaque parent into the
emitted identity set, and resumes normal causality from the completion's actual
event ID. Every other unresolved parent, failed completion, mismatch, reorder,
or reuse remains invalid.

## Incremental consumption and the raw transport sidecar

Provider version 1.5.0 corrects how that stream is consumed. Previously the
supervisor accumulated the whole transport into one bounded buffer and the
provider decoded it after exit, which made retention, consumption, and process
lifetime the same quantity: exceeding the retained-byte budget terminated a
working agent. The immutable issue-176 run shows exactly that. Copilot exited
zero and its own durable session log ends with `abort {"reason":
"user_initiated"}` and `session.shutdown {"shutdownType": "routine"}` after 22
turn starts and only 21 turn ends, because the supervisor stopped reading and
closed the pipe. The retained prefix ended in a partial
`assistant.tool_call_delta`, so no terminal record and no candidate existed.

That budget was not free to raise: the whole raw transport was Base64-inlined
into the 2 MiB execution result, leaving roughly 45 KiB of slack. The
correction removes raw transport from the result document entirely.

The provider now passes a transport sink to the supervisor. Per chunk, in this
exact order, the sink secret-scans, retains the exact unfiltered bytes, and
feeds the strict incremental decoder. Scanning first is what makes complete
cross-chunk credential detection happen before any byte can become publishable;
the scan carries over `max(len(token), len(base64(token))) - 1` bytes so a
credential split across chunk boundaries is still caught, and it now covers the
whole stream rather than a retained prefix. Retention is memory-resident and
bounded, so no spool file, spool lifecycle, or new filesystem permission
surface is introduced, and nothing is written anywhere until the disclosure
verdict is final. Retention and decoding are independent: a decode failure
still preserves the raw transport, and retention overflow still lets decoding
run to the end so the recorded diagnostic stays accurate. The sink never
terminates the process.

Three bounds are now named separately instead of being derived from one
document-size figure. `JSONL_MAX_EVENT_BYTES` is 832 KiB and bounds one
physical line and therefore the pending-line buffer. `JSONL_MAX_EVENTS` is
32,768 and bounds the decoder identity sets, at worst about two entries per
event and roughly 8 MiB. `TRANSPORT_MAX_BYTES` is 8 MiB, equal to the reviewed
`MAX_OUTPUT_BYTES` ceiling any supervised limit may declare and eight times
below the 64 MiB evidence payload limit; at the observed average of 545 bytes
per event it carries about 15,000 events. Provider peak memory is therefore
bounded near 17 MiB and disk cost at one object of at most 8 MiB per attempt.
Exceeding `TRANSPORT_MAX_BYTES` fails the attempt closed with
`local-agent-transport-too-large`; a truncated sidecar is never published, and
the process is still not terminated for volume.

The strict decoder is unchanged in substance. It became an incremental
`feed`/`finish` state machine carrying the same state, checks, and check order,
and `_extract_candidate_from_jsonl` remains as a whole-buffer wrapper over it so
the original decoder tests still apply verbatim. Only three constructs are
restated: the previous event type is tracked instead of indexed, any event fed
after the terminal `result` fails instead of an end-of-list index check, and an
unterminated residue at `finish` reports the same truncation failure that the
missing terminal LF reported.

The provider reads the transport once through that sink, secret-scans those
unchanged bytes, and strictly validates bounded UTF-8 JSONL. It independently
secret-scans the extracted candidate before returning it. It rejects missing
terminal LF, blank or oversized lines, excess records or bytes, duplicate JSON
keys, malformed JSON, non-object lines, unknown/error/abort/truncation events,
duplicate identifiers, broken parent/turn/interaction relationships,
unresolved tool calls, unsuccessful tools, malformed or nonzero results,
candidate-like nonfinal messages, missing final boundaries, multiple final
candidates, and records after the terminal result. It does not trim, normalize,
or recover output heuristically. In particular, selecting the last parseable
JSON object is prohibited because it can detach candidate selection from the
authenticated event and turn boundaries.

Only the exact UTF-8 encoding of the final message's `data.content` is passed to
the existing strict candidate decoder and schema validation. Final prose is
therefore still rejected downstream. Execution evidence keeps independent
identities for the complete raw transport and extracted candidate.
`sandbox.transport_size` and `sandbox.transport_sha256` are the supervisor's
own whole-stream `observed_bytes` and `observed_sha256`, so the provider cannot
assert a transport identity the core did not observe, while candidate byte
count and SHA-256 attest the decoder input.

The complete unfiltered raw JSONL is published as a sidecar payload at
`workflow-orchestration/copilot-transport.jsonl` inside the same atomic
execution-result evidence binding and manifest as
`workflow-orchestration/execution-result.json`. `sandbox.transport_reference`
names that path with the identical size and digest, and
`runtime.verify_result_attachments` proves the reference resolves to exactly one
manifest entry of that same binding, with matching path, size, content digest,
and payload reference. There is no second binding, no second authority, and no
separate state machine. Because the raw transport is no longer Base64-inlined,
`sandbox.transport_output` no longer exists; nothing in the result document is a
prefix presented as complete evidence. The persisted `process_result.stdout`
still contains the extracted candidate so the unchanged candidate decoder
remains byte-exact, and its `observed_*` fields describe that same candidate
record rather than the raw stream. `sandbox.process_result_sha256` identifies
the supervised process result before that candidate adaptation, whose stdout
retains zero bytes and carries the raw stream's observed identity.
Authentication remains unchanged and lazy: only `COPILOT_GITHUB_TOKEN` is
injected when the provider executes.

Failed supervised executions use the same immutable execution-result binding.
So do successful processes whose transport fails strict decoding: a malformed,
unknown, truncated, or relationship-violating stream now produces an
authoritative failed execution result carrying the typed parser failure, no
candidate, and the exact same-manifest raw sidecar, instead of losing the raw
evidence through an exception-only host failure. In that case
`provider_failure` is the parser failure itself rather than the fixed
process-failure classification. `sandbox.process_diagnostic` records the
supervisor outcome and reason, exit code or terminating signal, byte count and
SHA-256 identity of each stream, the provider failure classification, and any
typed JSONL parser failure. The diagnostic contains no raw stream bytes or
Base64 payloads. Raw and candidate credential scans still run before this record
can be constructed; a disclosure is scrubbed and rejected instead of persisted,
and no sidecar is published. Transport overflow likewise publishes nothing.

Persisting the raw transport added a third independently bounded Base64 output
to each trusted-local execution result: adapted candidate stdout, stderr, and
`sandbox.transport_output`. The original preflight budget covered only stdout
and stderr, so the former 512 KiB per-stream limit could exceed the unchanged
2 MiB document limit before metadata. The first correction reduced the raw
limit to 384 KiB and corrected the preflight to account separately for the
complete generated prompt persisted in
`sandbox.command.argv`. Its 64 KiB raw UTF-8 limit can occupy up to 131,074
bytes as a canonical JSON string because quotes, backslashes, and newlines are
escaped and the surrounding JSON quotes add two bytes.

The preflight charges four terms independently: the actual canonical
repository-before observation, 131,074 bytes for the maximum serialized
prompt, exact Base64 expansions for raw JSONL, candidate, and stderr, and 64 KiB
for the remaining fixed result structure.

A controlled planning run on 2026-09-08 then reached the 384 KiB stdout limit
exactly and failed as `output-limit` / `per-stream-output-limit`, with strict
JSONL parsing independently reporting a truncated final record. A later
immutable run reached the 448 KiB stdout cap exactly with no stderr or candidate
bytes and again ended in a truncated parser record. The correction therefore
redistributes the identical persistence budget asymmetrically: raw JSONL/stdout
is 832 KiB, extracted candidate output remains 448 KiB, and stderr is 64 KiB.
Their exact Base64 charges are respectively 1,135,960, 611,672, and 87,384
bytes, totaling 1,835,016 bytes, exactly the former three times 611,672. After
the unchanged 131,074-byte serialized-prompt reservation and 64 KiB fixed
headroom, the preflight still admits a 65,526-byte canonical repository
observation. That preflight arithmetic is deliberately unchanged by the sidecar
correction even though the result document no longer carries raw transport: it
is now a conservative over-reservation rather than a binding constraint, and no
limit semantics move in this correction. `output_limit_bytes` remains 851,968
and is now a retention bound the sink makes inert for stdout, not a kill switch.
Focused tests accept per-event, event-count, and candidate bytes exactly at
their respective bounds, reject each next unit, serialize the maximum blobs with
a maximum-length quote/backslash/newline-heavy prompt and the maximal admitted
observation, and reject either the next Base64 expansion or an additional
repository-observation byte. Further focused tests prove that intermediate delta
traffic far beyond `output_limit_bytes` still yields the exact candidate and a
byte-for-byte sidecar, that incremental decoding matches whole-buffer decoding
for arbitrary chunk widths, and that no publishable raw artifact survives a
credential disclosure. No admitted stream or candidate is filtered, dropped, or
truncated.

Repository-after is observed only after the agent runs and can legitimately be
larger than repository-before, so preflight does not claim to bound that future
growth. Final canonicalization remains authoritative: if repository-after
growth or any other component makes the completed result exceed 2 MiB, the
runtime rejects the entire result with `document-too-large` before persistence.
It does not truncate either output, discard repository-after, or persist a
smaller success-shaped substitute. The lesson is that request-time bounds can
prove only the bounded inputs they charge; post-execution evidence must still
pass the complete document limit as one indivisible record.

Create the deterministic linked worktree:

```bash
/usr/bin/python3 -I "$CONTROL/scripts/workflow_local_host.py" \
  --control-root "$CONTROL" \
  --repository NathanZK/ChessEcho \
  --git-executable "$GIT" \
  --gh-executable "$GH" \
  --agent-executable "$AGENT" \
  --agent-home "$AGENT_HOME" \
  --result-store "$RESULTS" \
  --github-token-stdin \
  prepare-workspace 176 --workspace-parent "$WORKSPACES"
```

The path is
`$WORKSPACES/<repository-sha256-prefix>/issue-176`, and its branch is exactly
`chess-echo-agent/issue-176`. Reuse is accepted only when the provider can
reconstruct that same linked-worktree identity.

## Activation and operation

Activation is the reviewed `mode: active` configuration plus the exact host,
provider, Python, and agent hashes in `.github/agent-workflow.json`. There is no
dynamic provider discovery or environment-selected provider class. The
operator supplies executable paths, but their resolved hashes must equal the
base-pinned identities.

All commands after workspace preparation revalidate the linked-worktree root,
common Git directory, deterministic branch, and separation from the reviewed
checkout. They read the GitHub token as one line from standard input. First
verify bootstrap:

```bash
printf '%s\n' "$GH_TOKEN" | /usr/bin/python3 -I \
  "$CONTROL/scripts/workflow_local_host.py" \
  --control-root "$CONTROL" \
  --repository NathanZK/ChessEcho \
  --git-executable "$GIT" \
  --gh-executable "$GH" \
  --agent-executable "$AGENT" \
  --agent-home "$AGENT_HOME" \
  --result-store "$RESULTS" \
  --github-token-stdin \
  bootstrap 176 --workspace "$WORKSPACE"
```

Use the same prefix for `publish-issue-source`, `init`, `status`, `plan-next`,
`step`, `approve`, `cancel`, and `recover`. `init` and an exact execution
handoff use `--request FILE`; every post-genesis orchestration mutation
(`step`, `approve`, `cancel`, and `recover`) uses the `pointer_sha256` returned
by `status` as `--expected-tip`. `bootstrap`, `publish-issue-source`, and
genesis `init` do not have an existing replacement pointer to supply.

For a `step` that may launch an agent, add the explicit trusted-worker option
and second input line:

```bash
printf '%s\n%s\n' "$GH_TOKEN" "$COPILOT_GITHUB_TOKEN" | /usr/bin/python3 -I \
  "$CONTROL/scripts/workflow_local_host.py" \
  --control-root "$CONTROL" \
  --repository NathanZK/ChessEcho \
  --git-executable "$GIT" \
  --gh-executable "$GH" \
  --agent-executable "$AGENT" \
  --agent-home "$AGENT_HOME" \
  --result-store "$RESULTS" \
  --github-token-stdin \
  --trusted-worker-auth-stdin \
  step 176 --workspace "$WORKSPACE" --expected-tip "$EXPECTED_TIP"
```

The host calls `workflow_runtime.bootstrap()` only for trusted intake and
`workflow_runtime.reconstruct()` for every selected later command. Agent
commands run in the dedicated worktree through `workflow_supervisor`. The host
projects only the exact request-selected immutable evidence inputs into the
prompt and binds that projection's digest. The fully encoded prompt is capped
at 64 KiB so it remains below the supported host argument budget. Runtime
validation applies that larger bound only to the prompt position in the exact
trusted-local provider argv shape; configured commands and every other command
part retain the generic 4 KiB bound. The execution result also records the exact
executable, argv, cwd, selected commit, authority binding, controlled environment
identity, bounded process result, raw JSONL transport identity, and separately
extracted candidate output identity.
Write phases require the agent to commit all intended changes and leave its
worktree clean; read-only phases explicitly prohibit candidate changes.

Before the immutable execution-result binding is published, the orchestrator
durably records the exact pending-result query, candidate binding, and binding
bytes. A later `step` with no `--request` may complete an interrupted binding
publication and answer that exact query from the bounded index. It never scans
CAS or reruns the operation. Supplying the exact live handoff remains the normal
path and is distinct from restart rediscovery.

## Canary

Use a disposable issue, never #115, #174, or production work. The bounded
sequence is:

```text
prepare-workspace
  -> bootstrap
  -> publish-issue-source
  -> construct the canonical init request with that publication
  -> init
  -> status / plan-next
  -> step with the exact expected tip
  -> persist the returned handoff
  -> step with that exact handoff (or, in a fresh host process, no handoff)
  -> stop at the next normal review or human gate
```

Stop immediately on any identity, reconstruction, authority, provider,
workspace, process, candidate, or gate failure. Do not substitute a fixture,
change the selected base, infer approval, or bypass the failed guard.
