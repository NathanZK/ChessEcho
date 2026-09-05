import base64
import copy
import hashlib
import importlib.util
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

from scripts import workflow_evidence as evidence
from scripts import workflow_inspector as inspector
from scripts import workflow_migration as migration
from scripts import workflow_policy as policy


SCRIPTS = pathlib.Path(__file__).parents[1]
REPOSITORY = SCRIPTS.parent
INSPECTOR_TEST = SCRIPTS / "tests" / "test_workflow_inspector.py"
SPEC = importlib.util.spec_from_file_location("policy_inspector_fixture", INSPECTOR_TEST)
FIXTURE_MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FIXTURE_MODULE)
InspectorFixture = FIXTURE_MODULE.InspectorFixture

BIND_ORDER = tuple(node for node in policy.NODE_ORDER if node != "implementation-a")


class PolicyFixture:
    def __init__(self):
        self.temporary = tempfile.TemporaryDirectory(dir=REPOSITORY)
        self.root = pathlib.Path(self.temporary.name)
        self.authority = InspectorFixture(self.root)
        self.bindings = {}
        self.inputs = {}
        for index, node in enumerate(policy.NODE_ORDER):
            self.add_binding(node, node, index)
        self.state = self.make_state()

    def close(self):
        self.temporary.cleanup()

    def add_binding(
        self,
        name,
        decision_type,
        index=100,
        *,
        decision_id=None,
        identity=None,
        lineage=None,
        payload=None,
        path=None,
    ):
        payload = ("%s\n" % name).encode() if payload is None else payload
        digest = hashlib.sha256(payload).hexdigest()
        entry = {
            "path": path or "policy/%s.txt" % name,
            "kind": "regular",
            "mode": "100644",
            "content_sha256": digest,
            "size": len(payload),
            "payload": {"kind": "evidence-payload", "sha256": digest, "size": len(payload)},
        }
        publication = {
            "format": evidence.PUBLICATION_FORMAT,
            "identity": identity or {
                "issue": self.authority.issue,
                "run_id": self.authority.run_id,
                "family_run_id": self.authority.run_id,
                "correction": None,
                "run_generation": 0,
                "sequence": index + 1,
                "event_tip": ("%064x" % (index + 1)),
            },
            "decision": {
                "type": decision_type,
                "id": decision_id or "decision-%s" % name,
            },
            "subject": self.authority.refs["run-state"],
            "lineage": lineage or {"status": "original", "parent_binding": None},
            "migration": None,
            "entries": [entry],
            "captures": [
                {
                    "entry_sha256": evidence._entry_digest(entry),
                    "capture_method": "fixture",
                    "captured_at": "2026-09-03T00:00:00Z",
                    "source": {"type": "workspace", "path": entry["path"]},
                    "tool": {"name": "test", "version": "1"},
                }
            ],
            "payloads": [
                {
                    "sha256": digest,
                    "size": len(payload),
                    "bytes_base64": base64.b64encode(payload).decode("ascii"),
                }
            ],
        }
        reference = evidence.publish(self.root, publication)["binding"]
        self.bindings[name] = reference
        self.inputs[name] = {"binding": reference, "migration_plan": None}
        return reference

    def bind_state(self, state, parent=None):
        state_bytes = inspector.canonical_bytes(state)
        generation = state["generation"]
        run_id = hashlib.sha256(b"policy-state\0" + state_bytes).hexdigest()[:32]
        identity = {
            "issue": state["issue"],
            "run_id": run_id,
            "family_run_id": state["family_run_id"],
            "correction": generation or None,
            "run_generation": generation,
            "sequence": generation + 1,
            "event_tip": hashlib.sha256(b"policy-tip\0" + state_bytes).hexdigest(),
        }
        name = "policy-state-%d-%s" % (generation, state["state_sha256"][:12])
        return self.add_binding(
            name,
            "policy-state",
            generation + 500,
            decision_id="generation-%d" % generation,
            identity=identity,
            lineage={
                "status": "original" if parent is None else "replacement",
                "parent_binding": parent,
            },
            payload=state_bytes,
            path="workflow-policy/state.json",
        )

    def make_state(self):
        state = policy.initialize(
            self.root,
            self.authority.issue,
            self.authority.run_id,
            self.inputs["implementation-a"],
        )
        state_binding = self.bind_state(state)
        self.authority_chain = [{"binding": state_binding, "state": copy.deepcopy(state)}]
        self.state = state
        self.genesis_state = copy.deepcopy(state)
        self.genesis_authority_chain = copy.deepcopy(self.authority_chain)
        for node in BIND_ORDER:
            result = policy.evaluate(
                self.root,
                self.request(self.bind_operation(node), [node]),
                self.authority_chain[-1]["binding"]["sha256"],
            )
            self.adopt(result)
        return self.state

    def use_genesis(self):
        self.state = copy.deepcopy(self.genesis_state)
        self.authority_chain = copy.deepcopy(self.genesis_authority_chain)

    def bind_operation(self, node, binding=None, dependencies=None):
        if binding is None:
            binding = self.bindings[node]
        if dependencies is None:
            dependencies = [
                {"node": parent, "binding": self.bindings[parent]}
                for parent in policy.DEPENDENCIES[node]
            ]
        return {
            "type": "bind",
            "node": node,
            "binding": binding,
            "dependencies": dependencies,
            "reason": "Activate %s" % node,
        }

    def request(self, operation, extra_inputs=()):
        required = {
            item["binding"]["sha256"] for item in self.state["active"]
        } | {item["binding"]["sha256"] for item in self.state["history"]}
        required.update(
            item["binding"]["sha256"]
            for item in self.state["convergence"]["evidence"]
        )
        required.update(
            item["binding"]["sha256"] for item in self.authority_chain
        )
        for item in self.authority_chain:
            transition = item["state"]["transition"]
            required.update(policy._operation_binding_digests(transition))
        inputs_by_digest = {
            item["binding"]["sha256"]: item for item in self.inputs.values()
        }
        selected = [copy.deepcopy(inputs_by_digest[digest]) for digest in sorted(required)]
        selected.extend(copy.deepcopy(self.inputs[name]) for name in extra_inputs)
        return {
            "format": policy.REQUEST_FORMAT,
            "issue": self.authority.issue,
            "family_run_id": self.authority.run_id,
            "state": copy.deepcopy(self.state),
            "expected_state_sha256": self.state["state_sha256"],
            "bindings": selected,
            "authority_chain": copy.deepcopy(self.authority_chain),
            "operation": operation,
        }

    def replacement(self, node, suffix):
        name = "%s-%s" % (node, suffix)
        self.add_binding(name, node, len(self.bindings) + 20)
        return name, self.bindings[name]

    def adopt(self, result):
        next_state = copy.deepcopy(result["next_state"])
        parent = self.authority_chain[-1]["binding"]
        binding = self.bind_state(next_state, parent)
        self.authority_chain.append(
            {"binding": binding, "state": copy.deepcopy(next_state)}
        )
        self.state = next_state

    def convergence_binding(self, name, decision_type):
        decision_id = policy._convergence_decision_id(
            self.state["convergence"]["episode"],
            self.state["transition_tip"],
            policy._active_sha256(
                {
                    item["node"]: {
                        "node": item["node"],
                        "binding": item["binding"],
                        "dependencies": {
                            dependency["node"]: dependency["binding"]
                            for dependency in item["dependencies"]
                        },
                    }
                    for item in self.state["active"]
                }
            ),
        )
        return self.add_binding(
            name,
            decision_type,
            len(self.bindings) + 300,
            decision_id=decision_id,
        )

    def correction_binding(self, node, name, parent, child_run_id=None):
        child_run_id = child_run_id or hashlib.sha256(name.encode()).hexdigest()[:32]
        return self.add_binding(
            name,
            node,
            len(self.bindings) + 400,
            identity={
                "issue": self.authority.issue,
                "run_id": child_run_id,
                "family_run_id": self.authority.run_id,
                "correction": 1,
                "run_generation": 0,
                "sequence": 1,
                "event_tip": hashlib.sha256((name + "-tip").encode()).hexdigest(),
            },
            lineage={"status": "replacement", "parent_binding": parent},
        )

    def identity_of(self, binding):
        return evidence.project(self.root, binding)["identity"]

    def correction_authorization(
        self, classification, parent, child_identity, name
    ):
        document = {
            "format": policy.CORRECTION_AUTHORIZATION_FORMAT,
            "issue": self.authority.issue,
            "family_run_id": self.authority.run_id,
            "parent_binding": parent,
            "child_identity": child_identity,
            "classification": classification,
            "roots": list(policy.CORRECTION_ROOTS[classification]),
        }
        binding = self.add_binding(
            name,
            "correction-authorization",
            len(self.bindings) + 450,
            decision_id="child-%s" % child_identity["run_id"],
            identity=child_identity,
            lineage={"status": "replacement", "parent_binding": parent},
            payload=inspector.canonical_bytes(document),
            path="workflow-policy/correction-authorization.json",
        )
        return name, {"binding": binding, "document": document}


class WorkflowPolicyTest(unittest.TestCase):
    def setUp(self):
        self.fixture = PolicyFixture()

    def tearDown(self):
        self.fixture.close()

    def assert_failure(self, status, code, request):
        with self.assertRaises(policy.PolicyFailure) as raised:
            self.evaluate(request)
        self.assertEqual(status, raised.exception.status)
        self.assertEqual(code, raised.exception.code)

    def evaluate(
        self, request, trusted_state_binding=None, trusted_correction_binding=None
    ):
        trusted_state_binding = trusted_state_binding or self.fixture.authority_chain[
            -1
        ]["binding"]["sha256"]
        if (
            trusted_correction_binding is None
            and request.get("operation", {}).get("type") == "correction"
            and isinstance(request["operation"].get("authorization"), dict)
        ):
            trusted_correction_binding = request["operation"]["authorization"][
                "binding"
            ]["sha256"]
        return policy.evaluate(
            self.fixture.root,
            request,
            trusted_state_binding,
            trusted_correction_binding,
        )

    def initialize_request(self, binding_record=None):
        request = {
            "format": policy.INITIALIZE_REQUEST_FORMAT,
            "issue": self.fixture.authority.issue,
            "family_run_id": self.fixture.authority.run_id,
            "implementation_a": copy.deepcopy(
                binding_record or self.fixture.inputs["implementation-a"]
            ),
        }
        request["request_sha256"] = policy._digest(request)
        return request

    def reopen(self, target, suffix):
        root = policy.REOPEN_ROOTS[target][0]
        name, binding = self.fixture.replacement(root, suffix)
        request = self.fixture.request(
            {
                "type": "reopen",
                "target": target,
                "reason": "Contract changed",
                "replacements": [{"node": root, "binding": binding}],
            },
            [name],
        )
        return request, self.evaluate(request)

    def correction_request(self, classification, suffix):
        names = []
        replacements = []
        parent = self.fixture.bindings["pr-approval"]
        child_run_id = hashlib.sha256(
            ("correction-%s-%s" % (classification, suffix)).encode()
        ).hexdigest()[:32]
        child_identity = {
            "issue": self.fixture.authority.issue,
            "run_id": child_run_id,
            "family_run_id": self.fixture.authority.run_id,
            "correction": 1,
            "run_generation": 0,
            "sequence": 1,
            "event_tip": hashlib.sha256(child_run_id.encode()).hexdigest(),
        }
        for root in policy.CORRECTION_ROOTS[classification]:
            name = "%s-%s" % (root, suffix)
            binding = self.fixture.add_binding(
                name,
                root,
                len(self.fixture.bindings) + 20,
                identity=child_identity,
                lineage={"status": "replacement", "parent_binding": parent},
            )
            names.append(name)
            replacements.append({"node": root, "binding": binding})
        authorization_name, authorization = self.fixture.correction_authorization(
            classification,
            parent,
            child_identity,
            "authorization-%s-%s" % (classification, suffix),
        )
        names.append(authorization_name)
        return self.fixture.request(
            {
                "type": "correction",
                "classification": classification,
                "reason": "Bounded correction",
                "parent_binding": parent,
                "child_identity": child_identity,
                "authorization": authorization,
                "replacements": replacements,
            },
            names,
        )
    def correction(self, classification, suffix):
        return self.evaluate(self.correction_request(classification, suffix))

    def test_initialize_creates_only_the_safe_canonical_genesis(self):
        first = policy.initialize(
            self.fixture.root,
            self.fixture.authority.issue,
            self.fixture.authority.run_id,
            self.fixture.inputs["implementation-a"],
        )
        second = policy.initialize(
            self.fixture.root,
            self.fixture.authority.issue,
            self.fixture.authority.run_id,
            copy.deepcopy(self.fixture.inputs["implementation-a"]),
        )
        self.assertEqual(first, second)
        self.assertEqual(policy.STATE_FORMAT, first["format"])
        self.assertEqual(0, first["generation"])
        self.assertIsNone(first["transition_tip"])
        self.assertIsNone(first["transition"])
        self.assertEqual(
            [
                {
                    "node": "implementation-a",
                    "binding": self.fixture.bindings["implementation-a"],
                    "dependencies": [],
                }
            ],
            first["active"],
        )
        self.assertEqual(
            [
                {
                    "node": "implementation-a",
                    "binding": self.fixture.bindings["implementation-a"],
                    "status": "active",
                    "transition_id": None,
                }
            ],
            first["history"],
        )
        self.assertEqual(
            {
                "reopens": 0,
                "retries": {stage: 0 for stage in policy.CONVERGENCE_STATES},
            },
            first["budgets"],
        )
        self.assertEqual(
            {"episode": 0, "state": "UNKNOWN", "evidence": []},
            first["convergence"],
        )
        self.assertEqual(policy._state_digest(first), first["state_sha256"])

    def test_initialize_rejects_wrong_decision_identity_and_schema(self):
        with self.assertRaises(policy.PolicyFailure) as raised:
            policy.initialize(
                self.fixture.root,
                self.fixture.authority.issue,
                self.fixture.authority.run_id,
                self.fixture.inputs["plan-approval"],
            )
        self.assertEqual("stale", raised.exception.status)
        self.assertEqual("node-decision-mismatch", raised.exception.code)

        wrong_identity_name = "implementation-a-wrong-family"
        wrong_identity = {
            "issue": self.fixture.authority.issue,
            "run_id": "a" * 32,
            "family_run_id": "b" * 32,
            "correction": None,
            "run_generation": 0,
            "sequence": 1,
            "event_tip": "c" * 64,
        }
        self.fixture.add_binding(
            wrong_identity_name,
            "implementation-a",
            identity=wrong_identity,
        )
        with self.assertRaises(policy.PolicyFailure) as raised:
            policy.initialize(
                self.fixture.root,
                self.fixture.authority.issue,
                self.fixture.authority.run_id,
                self.fixture.inputs[wrong_identity_name],
            )
        self.assertEqual("stale", raised.exception.status)
        self.assertEqual("binding-lineage-mismatch", raised.exception.code)

        for issue, family_run_id, code in (
            (True, self.fixture.authority.run_id, "invalid-initialize-identity"),
            (self.fixture.authority.issue, "not-a-run-id", "invalid-initialize-identity"),
        ):
            with self.subTest(issue=issue, family_run_id=family_run_id):
                with self.assertRaises(policy.PolicyFailure) as raised:
                    policy.initialize(
                        self.fixture.root,
                        issue,
                        family_run_id,
                        self.fixture.inputs["implementation-a"],
                    )
                self.assertEqual("corrupt", raised.exception.status)
                self.assertEqual(code, raised.exception.code)

    def test_authority_chain_rejects_every_non_safe_genesis(self):
        variants = {}

        empty = copy.deepcopy(self.fixture.genesis_state)
        empty["active"] = []
        empty["history"] = []
        variants["empty"] = (empty, "invalid-policy-genesis")

        plan_only = copy.deepcopy(self.fixture.genesis_state)
        plan_only["active"] = [
            {
                "node": "plan-approval",
                "binding": self.fixture.bindings["plan-approval"],
                "dependencies": [],
            }
        ]
        plan_only["history"] = [
            {
                "node": "plan-approval",
                "binding": self.fixture.bindings["plan-approval"],
                "status": "active",
                "transition_id": None,
            }
        ]
        variants["plan-approval"] = (plan_only, "invalid-policy-genesis")

        extra = copy.deepcopy(self.fixture.genesis_state)
        extra["active"].insert(
            0,
            {
                "node": "plan-approval",
                "binding": self.fixture.bindings["plan-approval"],
                "dependencies": [],
            },
        )
        extra["history"].insert(
            0,
            {
                "node": "plan-approval",
                "binding": self.fixture.bindings["plan-approval"],
                "status": "active",
                "transition_id": None,
            },
        )
        variants["extra"] = (extra, "invalid-policy-genesis")

        duplicate = copy.deepcopy(self.fixture.genesis_state)
        duplicate["active"].append(copy.deepcopy(duplicate["active"][0]))
        variants["duplicate"] = (duplicate, "duplicate-active-node")

        for name, (genesis, code) in variants.items():
            with self.subTest(name=name):
                genesis["state_sha256"] = policy._state_digest(genesis)
                binding = self.fixture.bind_state(genesis)
                request = self.fixture.request(
                    self.fixture.bind_operation("implementation-a")
                )
                request["authority_chain"][0] = {
                    "binding": binding,
                    "state": genesis,
                }
                request["bindings"].append(
                    copy.deepcopy(
                        self.fixture.inputs[
                            "policy-state-0-%s" % genesis["state_sha256"][:12]
                        ]
                    )
                )
                self.assert_failure("stale" if name != "duplicate" else "ambiguous", code, request)

    def test_bind_activates_the_fixed_dag_in_order_and_replays(self):
        self.fixture.use_genesis()
        for generation, node in enumerate(BIND_ORDER, start=1):
            previous = copy.deepcopy(self.fixture.state)
            request = self.fixture.request(
                self.fixture.bind_operation(node),
                [node],
            )
            first = self.evaluate(request)
            second = self.evaluate(copy.deepcopy(request))
            self.assertEqual(first, second)
            self.assertEqual("evaluated", first["outcome"]["code"])
            self.assertEqual([node], first["changed_roots"])
            self.assertEqual([], first["invalidated"])
            self.assertEqual(previous["active"], first["preserved"])
            self.assertEqual(previous["budgets"], first["next_state"]["budgets"])
            self.assertEqual(
                previous["convergence"], first["next_state"]["convergence"]
            )
            for historical in previous["history"]:
                self.assertIn(historical, first["next_state"]["history"])
            self.assertEqual(generation, first["next_state"]["generation"])
            bound_history = next(
                item
                for item in first["next_state"]["history"]
                if item["node"] == node
            )
            self.assertEqual(
                first["transition_id"],
                bound_history["transition_id"],
            )
            self.fixture.adopt(first)
        self.assertEqual(list(policy.NODE_ORDER), [item["node"] for item in self.fixture.state["active"]])
        self.assertEqual(9, len(self.fixture.authority_chain))

        request, replayed = self.reopen("tests", "after-bind-chain")
        self.assertEqual("evaluated", replayed["outcome"]["code"])
        self.assertEqual(
            request["authority_chain"][-1]["state_sha256"]
            if "state_sha256" in request["authority_chain"][-1]
            else request["state"]["state_sha256"],
            replayed["input_state_sha256"],
        )

    def test_bind_rejects_forbidden_active_and_historical_targets(self):
        self.fixture.use_genesis()
        implementation = self.fixture.request(
            self.fixture.bind_operation("implementation-a")
        )
        self.assert_failure(
            "unsupported", "bind-implementation-a-forbidden", implementation
        )

        plan = self.evaluate(
            self.fixture.request(
                self.fixture.bind_operation("plan-approval"),
                ["plan-approval"],
            )
        )
        self.fixture.adopt(plan)
        already_active = self.fixture.request(
            self.fixture.bind_operation("plan-approval")
        )
        self.assert_failure(
            "stale", "bind-node-already-active", already_active
        )

        self.fixture.make_state()
        _request, reopened = self.reopen("tests", "bind-history")
        self.fixture.adopt(reopened)
        historical = self.fixture.request(
            self.fixture.bind_operation(
                "test-approval",
                self.fixture.bindings["test-approval"],
                [
                    {
                        "node": "test-manifest",
                        "binding": self.fixture.state["active"][2]["binding"],
                    }
                ],
            )
        )
        self.assert_failure("stale", "bind-already-historical", historical)

    def test_bind_rejects_decision_dependency_and_order_mismatches(self):
        self.fixture.use_genesis()
        wrong_decision = self.fixture.request(
            self.fixture.bind_operation(
                "plan-approval",
                self.fixture.bindings["test-manifest"],
                [],
            ),
            ["test-manifest"],
        )
        self.assert_failure("stale", "node-decision-mismatch", wrong_decision)

        inactive_dependency = self.fixture.request(
            self.fixture.bind_operation("test-manifest"),
            ["plan-approval", "test-manifest"],
        )
        self.assert_failure(
            "stale", "bind-dependency-inactive", inactive_dependency
        )

        plan = self.evaluate(
            self.fixture.request(
                self.fixture.bind_operation("plan-approval"),
                ["plan-approval"],
            )
        )
        self.fixture.adopt(plan)
        wrong_order = self.fixture.request(
            self.fixture.bind_operation(
                "test-manifest",
                dependencies=[
                    {
                        "node": "implementation-a",
                        "binding": self.fixture.bindings["implementation-a"],
                    },
                    {
                        "node": "plan-approval",
                        "binding": self.fixture.bindings["plan-approval"],
                    },
                ],
            ),
            ["test-manifest"],
        )
        self.assert_failure("corrupt", "invalid-node-dependencies", wrong_order)

        stale_name = "stale-plan-dependency"
        stale_binding = self.fixture.add_binding(
            stale_name,
            "plan-approval",
            len(self.fixture.bindings) + 20,
        )
        stale_dependency = self.fixture.request(
            self.fixture.bind_operation(
                "test-manifest",
                dependencies=[
                    {"node": "plan-approval", "binding": stale_binding},
                    {
                        "node": "implementation-a",
                        "binding": self.fixture.bindings["implementation-a"],
                    },
                ],
            ),
            ["test-manifest", stale_name],
        )
        self.assert_failure(
            "stale", "bind-dependency-mismatch", stale_dependency
        )

    def test_bind_invalidates_prepared_convergence_context(self):
        self.fixture.use_genesis()
        cause_name = "cause-before-bind"
        cause_binding = self.fixture.convergence_binding(
            cause_name, "cause-establishment"
        )
        plan = self.evaluate(
            self.fixture.request(
                self.fixture.bind_operation("plan-approval"),
                ["plan-approval"],
            )
        )
        self.fixture.adopt(plan)
        request = self.fixture.request(
            {
                "type": "convergence",
                "from": "UNKNOWN",
                "to": "CAUSE_ESTABLISHED",
                "evidence": cause_binding,
                "retry": False,
            },
            [cause_name],
        )
        self.assert_failure(
            "stale", "convergence-evidence-context-mismatch", request
        )

    def test_initialize_cli_is_canonical_typed_and_read_only(self):
        request = self.initialize_request()
        request_path = self.fixture.root / "initialize.json"
        request_path.write_text(json.dumps(request))
        marker = self.fixture.root / ".agent-workflow" / "runs" / "issue-150" / "state.json"
        marker.parent.mkdir(parents=True)
        marker.write_bytes(b"sentinel\n")
        prefixes = (
            [sys.executable, str(SCRIPTS / "workflow_policy.py")],
            [sys.executable, "-m", "scripts.workflow_policy"],
        )
        expected = policy.initialize(
            self.fixture.root,
            request["issue"],
            request["family_run_id"],
            request["implementation_a"],
        )
        for prefix in prefixes:
            with self.subTest(prefix=prefix):
                result = subprocess.run(
                    prefix
                    + [
                        "initialize",
                        "--root",
                        str(self.fixture.root),
                        "--request",
                        str(request_path),
                        "--implementation-a-binding",
                        request["implementation_a"]["binding"]["sha256"],
                    ],
                    cwd=REPOSITORY,
                    capture_output=True,
                )
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(inspector.canonical_document(expected), result.stdout)
                self.assertEqual(b"sentinel\n", marker.read_bytes())

        stale = copy.deepcopy(request)
        stale["request_sha256"] = "0" * 64
        request_path.write_text(json.dumps(stale))
        result = subprocess.run(
            prefixes[0]
            + [
                "initialize",
                "--root",
                str(self.fixture.root),
                "--request",
                str(request_path),
                "--implementation-a-binding",
                request["implementation_a"]["binding"]["sha256"],
            ],
            cwd=REPOSITORY,
            capture_output=True,
        )
        self.assertEqual(7, result.returncode, result.stderr)
        self.assertNotIn(b"Traceback", result.stderr)
        self.assertEqual(
            "policy-initialize-request-stale",
            json.loads(result.stdout)["outcome"]["code"],
        )

        request_path.write_text(json.dumps(request))
        result = subprocess.run(
            prefixes[1]
            + [
                "initialize",
                "--root",
                str(self.fixture.root),
                "--request",
                str(request_path),
                "--implementation-a-binding",
                "0" * 64,
            ],
            cwd=REPOSITORY,
            capture_output=True,
        )
        self.assertEqual(7, result.returncode, result.stderr)
        self.assertNotIn(b"Traceback", result.stderr)
        self.assertEqual(
            "trusted-implementation-a-binding-mismatch",
            json.loads(result.stdout)["outcome"]["code"],
        )

    def test_reopen_plan_has_minimal_deterministic_closure(self):
        request, first = self.reopen("plan", "v2")
        second = self.evaluate(copy.deepcopy(request))
        self.assertEqual(first, second)
        self.assertEqual(
            [
                "plan-approval",
                "test-manifest",
                "test-approval",
                "implementation-submission",
                "validation",
                "final-review",
                "pr-metadata",
                "pr-approval",
            ],
            [item["node"] for item in first["invalidated"]],
        )
        self.assertEqual(["implementation-a"], [item["node"] for item in first["preserved"]])
        self.assertEqual(
            ["plan-approval", "implementation-a"],
            [item["node"] for item in first["next_state"]["active"]],
        )
        self.assertEqual(policy._result_digest(first), first["result_sha256"])

    def test_reopen_tests_preserves_ancestors_and_invalidates_descendants(self):
        _request, result = self.reopen("tests", "v2")
        self.assertEqual(
            ["plan-approval", "implementation-a"],
            [item["node"] for item in result["preserved"]],
        )
        self.assertEqual(
            [
                "test-manifest",
                "test-approval",
                "implementation-submission",
                "validation",
                "final-review",
                "pr-metadata",
                "pr-approval",
            ],
            [item["node"] for item in result["invalidated"]],
        )
        invalidated = [
            item for item in result["next_state"]["history"] if item["status"] == "invalidated"
        ]
        self.assertEqual(7, len(invalidated))
        self.assertTrue(all(item["transition_id"] == result["transition_id"] for item in invalidated))

    def test_all_correction_classes_match_fixed_roots(self):
        expected = {
            "metadata-only": ["pr-metadata", "pr-approval"],
            "implementation-only": [
                "implementation-submission",
                "validation",
                "final-review",
                "pr-metadata",
                "pr-approval",
            ],
            "test-contract": [
                "test-manifest",
                "test-approval",
                "implementation-submission",
                "validation",
                "final-review",
                "pr-metadata",
                "pr-approval",
            ],
            "architecture": list(policy.NODE_ORDER),
        }
        for classification, nodes in expected.items():
            with self.subTest(classification=classification):
                result = self.correction(classification, classification)
                self.assertEqual(nodes, [item["node"] for item in result["invalidated"]])

    def test_correction_rejects_original_sibling_and_wrong_parent_evidence(self):
        parent = self.fixture.bindings["pr-approval"]
        original_name, original = self.fixture.replacement(
            "pr-metadata", "original-correction"
        )
        original_child = {
            "issue": self.fixture.authority.issue,
            "run_id": "3" * 32,
            "family_run_id": self.fixture.authority.run_id,
            "correction": 1,
            "run_generation": 0,
            "sequence": 1,
            "event_tip": "3" * 64,
        }
        original_auth_name, original_authorization = (
            self.fixture.correction_authorization(
                "metadata-only",
                parent,
                original_child,
                "original-correction-authorization",
            )
        )
        original_request = self.fixture.request(
            {
                "type": "correction",
                "classification": "metadata-only",
                "reason": "Original evidence is not a child",
                "parent_binding": parent,
                "child_identity": original_child,
                "authorization": original_authorization,
                "replacements": [{"node": "pr-metadata", "binding": original}],
            },
            [original_name, original_auth_name],
        )
        self.assert_failure(
            "stale", "correction-replacement-not-child", original_request
        )

        sibling_name = "wrong-lineage-parent"
        sibling = self.fixture.correction_binding(
            "pr-metadata",
            sibling_name,
            self.fixture.bindings["final-review"],
        )
        sibling_identity = self.fixture.identity_of(sibling)
        sibling_auth_name, sibling_authorization = (
            self.fixture.correction_authorization(
                "metadata-only",
                parent,
                sibling_identity,
                "wrong-lineage-parent-authorization",
            )
        )
        sibling_request = self.fixture.request(
            {
                "type": "correction",
                "classification": "metadata-only",
                "reason": "Sibling evidence has another parent",
                "parent_binding": parent,
                "child_identity": sibling_identity,
                "authorization": sibling_authorization,
                "replacements": [{"node": "pr-metadata", "binding": sibling}],
            },
            [sibling_name, sibling_auth_name],
        )
        self.assert_failure("stale", "correction-parent-mismatch", sibling_request)

        actual_sibling_name = "sibling-child"
        actual_sibling = self.fixture.correction_binding(
            "pr-metadata", actual_sibling_name, parent, "6" * 32
        )
        sibling_identity = self.fixture.identity_of(actual_sibling)
        declared_child = dict(sibling_identity)
        declared_child["run_id"] = "7" * 32
        sibling_child_auth_name, sibling_child_authorization = (
            self.fixture.correction_authorization(
                "metadata-only",
                parent,
                declared_child,
                "sibling-child-authorization",
            )
        )
        sibling_child_request = self.fixture.request(
            {
                "type": "correction",
                "classification": "metadata-only",
                "reason": "Sibling child is not the declared correction",
                "parent_binding": parent,
                "child_identity": declared_child,
                "authorization": sibling_child_authorization,
                "replacements": [
                    {"node": "pr-metadata", "binding": actual_sibling}
                ],
            },
            [actual_sibling_name, sibling_child_auth_name],
        )
        self.assert_failure(
            "stale", "correction-child-identity-mismatch", sibling_child_request
        )

        valid_name = "valid-correction-wrong-operation-parent"
        valid = self.fixture.correction_binding(
            "pr-metadata", valid_name, parent
        )
        valid_identity = self.fixture.identity_of(valid)
        wrong_parent_auth_name, wrong_parent_authorization = (
            self.fixture.correction_authorization(
                "metadata-only",
                self.fixture.bindings["final-review"],
                valid_identity,
                "wrong-operation-parent-authorization",
            )
        )
        wrong_parent_request = self.fixture.request(
            {
                "type": "correction",
                "classification": "metadata-only",
                "reason": "Operation names another parent",
                "parent_binding": self.fixture.bindings["final-review"],
                "child_identity": valid_identity,
                "authorization": wrong_parent_authorization,
                "replacements": [{"node": "pr-metadata", "binding": valid}],
            },
            [valid_name, wrong_parent_auth_name],
        )
        self.assert_failure(
            "stale", "correction-parent-mismatch", wrong_parent_request
        )

    def test_architecture_correction_requires_one_child_identity(self):
        parent = self.fixture.bindings["pr-approval"]
        plan_name = "architecture-plan-child"
        implementation_name = "architecture-implementation-child"
        plan_binding = self.fixture.correction_binding(
            "plan-approval", plan_name, parent, "1" * 32
        )
        implementation_binding = self.fixture.correction_binding(
            "implementation-a", implementation_name, parent, "2" * 32
        )
        plan_identity = self.fixture.identity_of(plan_binding)
        architecture_auth_name, architecture_authorization = (
            self.fixture.correction_authorization(
                "architecture",
                parent,
                plan_identity,
                "architecture-authorization",
            )
        )
        request = self.fixture.request(
            {
                "type": "correction",
                "classification": "architecture",
                "reason": "Mixed child identities",
                "parent_binding": parent,
                "child_identity": plan_identity,
                "authorization": architecture_authorization,
                "replacements": [
                    {"node": "plan-approval", "binding": plan_binding},
                    {"node": "implementation-a", "binding": implementation_binding},
                ],
            },
            [plan_name, implementation_name, architecture_auth_name],
        )
        self.assert_failure(
            "stale", "correction-child-identity-mismatch", request
        )

    def test_trusted_correction_authority_rejects_a_sibling_child(self):
        parent = self.fixture.bindings["pr-approval"]
        authorized = self.correction_request("metadata-only", "authorized-child")
        sibling_name = "substituted-sibling"
        sibling = self.fixture.correction_binding(
            "pr-metadata", sibling_name, parent, "8" * 32
        )
        sibling_identity = self.fixture.identity_of(sibling)
        sibling_auth_name, sibling_authorization = (
            self.fixture.correction_authorization(
                "metadata-only",
                parent,
                sibling_identity,
                "substituted-sibling-authorization",
            )
        )
        request = self.fixture.request(
            {
                "type": "correction",
                "classification": "metadata-only",
                "reason": "Attempt sibling substitution",
                "parent_binding": parent,
                "child_identity": sibling_identity,
                "authorization": sibling_authorization,
                "replacements": [
                    {"node": "pr-metadata", "binding": sibling}
                ],
            },
            [sibling_name, sibling_auth_name],
        )
        trusted = authorized["operation"]["authorization"]["binding"]["sha256"]
        with self.assertRaises(policy.PolicyFailure) as raised:
            self.evaluate(request, trusted_correction_binding=trusted)
        self.assertEqual("stale", raised.exception.status)
        self.assertEqual("trusted-correction-binding-mismatch", raised.exception.code)

    def test_historical_correction_authority_is_required_for_replay(self):
        correction = self.correction_request("metadata-only", "historical-auth")
        authorization = correction["operation"]["authorization"]["binding"]
        result = self.evaluate(correction)
        self.fixture.adopt(result)
        request, _result = self.reopen("tests", "after-correction")
        request["bindings"] = [
            item
            for item in request["bindings"]
            if item["binding"]["sha256"] != authorization["sha256"]
        ]
        self.assert_failure("missing", "binding-input-missing", request)

    def test_stale_dependency_and_unknown_parent_fail_closed(self):
        request, _result = self.reopen("tests", "v2")
        request["state"]["active"][2]["dependencies"][0]["binding"] = self.fixture.bindings[
            "implementation-a"
        ]
        request["state"]["state_sha256"] = policy._state_digest(request["state"])
        request["expected_state_sha256"] = request["state"]["state_sha256"]
        self.assert_failure("stale", "active-dependency-stale", request)

        unknown_child = {
            "issue": self.fixture.authority.issue,
            "run_id": "4" * 32,
            "family_run_id": self.fixture.authority.run_id,
            "correction": 1,
            "run_generation": 0,
            "sequence": 1,
            "event_tip": "4" * 64,
        }
        unknown_auth_name, unknown_authorization = (
            self.fixture.correction_authorization(
                "metadata-only",
                self.fixture.bindings["plan-approval"],
                unknown_child,
                "unknown-parent-authorization",
            )
        )
        correction = self.fixture.request(
            {
                "type": "correction",
                "classification": "metadata-only",
                "reason": "Correction",
                "parent_binding": self.fixture.bindings["plan-approval"],
                "child_identity": unknown_child,
                "authorization": unknown_authorization,
                "replacements": [
                    {
                        "node": "pr-metadata",
                        "binding": self.fixture.bindings["pr-metadata"],
                    },
                ],
            },
            [unknown_auth_name],
        )
        correction["operation"]["parent_binding"] = {
            "kind": "evidence-binding",
            "sha256": "f" * 64,
            "size": 1,
        }
        self.assert_failure("missing", "binding-input-missing", correction)

    def test_exact_binding_registry_rejects_missing_duplicate_and_unreferenced(self):
        request, _result = self.reopen("tests", "v2")
        missing = copy.deepcopy(request)
        missing["bindings"].pop()
        self.assert_failure("missing", "binding-input-missing", missing)
        duplicate = copy.deepcopy(request)
        duplicate["bindings"].append(copy.deepcopy(duplicate["bindings"][0]))
        self.assert_failure("ambiguous", "duplicate-binding-input", duplicate)
        unreferenced_name, _binding = self.fixture.replacement("validation", "unused")
        unreferenced = copy.deepcopy(request)
        unreferenced["bindings"].append(copy.deepcopy(self.fixture.inputs[unreferenced_name]))
        self.assert_failure("ambiguous", "unreferenced-binding-input", unreferenced)

    def test_node_decision_mismatch_cannot_synthesize_approval(self):
        name = "fake-plan-approval"
        binding = self.fixture.add_binding(name, "test-manifest", 250)
        request = self.fixture.request(
            {
                "type": "reopen",
                "target": "plan",
                "reason": "Attempt to substitute another decision",
                "replacements": [{"node": "plan-approval", "binding": binding}],
            },
            [name],
        )
        self.assert_failure("stale", "node-decision-mismatch", request)

    def test_state_digest_and_schema_are_exact(self):
        request, _result = self.reopen("tests", "v2")
        request["expected_state_sha256"] = "0" * 64
        self.assert_failure("stale", "policy-state-stale", request)
        extra = self.fixture.request({"type": "unknown"})
        extra["extra"] = True
        self.assert_failure("corrupt", "invalid-policy-request-schema", extra)
        overflow = self.fixture.request({"type": "unknown"})
        overflow["state"]["budgets"]["reopens"] = policy.MAX_REOPENS + 1
        overflow["state"]["state_sha256"] = policy._state_digest(overflow["state"])
        overflow["expected_state_sha256"] = overflow["state"]["state_sha256"]
        self.assert_failure("corrupt", "reopen-budget-overflow", overflow)
        reordered = self.fixture.request({"type": "unknown"})
        reordered["state"]["active"].reverse()
        reordered["state"]["state_sha256"] = policy._state_digest(reordered["state"])
        reordered["expected_state_sha256"] = reordered["state"]["state_sha256"]
        self.assert_failure(
            "corrupt", "noncanonical-policy-state-order", reordered
        )

    def test_current_state_must_be_exact_canonical_authority(self):
        canonical = self.fixture.request({"type": "unknown"})
        self.assert_failure("unsupported", "unsupported-policy-operation", canonical)

        raw_digest = self.fixture.request({"type": "unknown"})
        raw_digest["state"]["active"].reverse()
        raw_digest["state"]["state_sha256"] = policy._state_digest(
            raw_digest["state"]
        )
        raw_digest["expected_state_sha256"] = raw_digest["state"]["state_sha256"]
        self.assert_failure(
            "corrupt", "noncanonical-policy-state-order", raw_digest
        )

        canonical_digest = self.fixture.request({"type": "unknown"})
        canonical_digest["state"]["active"].reverse()
        canonical_digest["state"]["state_sha256"] = self.fixture.state[
            "state_sha256"
        ]
        canonical_digest["expected_state_sha256"] = self.fixture.state[
            "state_sha256"
        ]
        self.assert_failure(
            "corrupt", "noncanonical-policy-state-order", canonical_digest
        )

    def test_successful_transition_replays_as_exact_canonical_state(self):
        request, result = self.reopen("tests", "canonical-replay")
        next_state = result["next_state"]
        validated = policy._validate_state(
            next_state,
            next_state["issue"],
            next_state["family_run_id"],
            next_state["state_sha256"],
        )
        self.assertEqual(
            inspector.canonical_bytes(next_state),
            inspector.canonical_bytes(policy._state_document(validated)),
        )
        self.assertEqual(policy._state_digest(next_state), next_state["state_sha256"])
        self.fixture.adopt(result)
        _request, replayed = self.reopen("tests", "after-canonical-replay")
        self.assertEqual("evaluated", replayed["outcome"]["code"])

    def test_malformed_history_statuses_fail_closed(self):
        for status in ([], {}, 1, None):
            with self.subTest(status=status):
                request = self.fixture.request({"type": "unknown"})
                request["state"]["history"][0]["status"] = status
                request["state"]["state_sha256"] = policy._state_digest(
                    request["state"]
                )
                request["expected_state_sha256"] = request["state"][
                    "state_sha256"
                ]
                self.assert_failure("corrupt", "invalid-history-status", request)

    def test_floating_reference_in_recorded_state_is_typed(self):
        _request, result = self.reopen("tests", "floating-recorded-reference")
        self.fixture.adopt(result)
        malformed = self.fixture.request({"type": "unknown"})
        malformed["state"]["transition"]["replacements"]["test-manifest"][
            "malformed"
        ] = 1.5
        self.assert_failure("unsupported", "floating-point-json", malformed)

        path = self.fixture.root / "floating-recorded-reference.json"
        path.write_text(json.dumps(malformed))
        for prefix in (
            [sys.executable, str(SCRIPTS / "workflow_policy.py")],
            [sys.executable, "-m", "scripts.workflow_policy"],
        ):
            with self.subTest(prefix=prefix):
                cli = subprocess.run(
                    prefix
                    + [
                        "evaluate",
                        "--root",
                        str(self.fixture.root),
                        "--request",
                        str(path),
                        "--trusted-state-binding",
                        malformed["authority_chain"][-1]["binding"]["sha256"],
                    ],
                    cwd=REPOSITORY,
                    capture_output=True,
                )
                self.assertEqual(4, cli.returncode, cli.stderr)
                self.assertNotIn(b"Traceback", cli.stderr)
                document = json.loads(cli.stdout)
                self.assertEqual("unsupported", document["outcome"]["status"])
                self.assertEqual("floating-point-json", document["outcome"]["code"])
        _request, valid = self.reopen("tests", "valid-after-floating-reference")
        self.assertEqual("evaluated", valid["outcome"]["code"])

    def test_malformed_policy_root_is_typed(self):
        request, valid = self.reopen("tests", "valid-root")
        self.assertEqual("evaluated", valid["outcome"]["code"])
        trusted = request["authority_chain"][-1]["binding"]["sha256"]
        malformed_root = self.fixture.root / ("x" * 300)

        with self.assertRaises(policy.PolicyFailure) as raised:
            policy.evaluate(malformed_root, request, trusted)
        self.assertEqual("missing", raised.exception.status)
        self.assertEqual("policy-root-unreadable", raised.exception.code)

        with self.assertRaises(policy.PolicyFailure) as raised:
            policy.evaluate(object(), request, trusted)
        self.assertEqual("corrupt", raised.exception.status)
        self.assertEqual("invalid-policy-root", raised.exception.code)

        symlink_loop = self.fixture.root / "policy-root-loop"
        symlink_loop.symlink_to(symlink_loop.name)
        with self.assertRaises(policy.PolicyFailure) as raised:
            policy.evaluate(symlink_loop, request, trusted)
        self.assertEqual("missing", raised.exception.status)
        self.assertEqual("policy-root-unreadable", raised.exception.code)

        path = self.fixture.root / "malformed-root-request.json"
        path.write_text(json.dumps(request))
        for prefix in (
            [sys.executable, str(SCRIPTS / "workflow_policy.py")],
            [sys.executable, "-m", "scripts.workflow_policy"],
        ):
            with self.subTest(prefix=prefix):
                cli = subprocess.run(
                    prefix
                    + [
                        "evaluate",
                        "--root",
                        str(malformed_root),
                        "--request",
                        str(path),
                        "--trusted-state-binding",
                        trusted,
                    ],
                    cwd=REPOSITORY,
                    capture_output=True,
                )
                self.assertEqual(3, cli.returncode, cli.stderr)
                self.assertNotIn(b"Traceback", cli.stderr)
                document = json.loads(cli.stdout)
                self.assertEqual("missing", document["outcome"]["status"])
                self.assertEqual(
                    "policy-root-unreadable", document["outcome"]["code"]
                )

    def test_complete_binding_references_must_match_verified_inputs(self):
        request, _result = self.reopen("tests", "size-mismatch")
        request["operation"]["replacements"][0]["binding"]["size"] += 1
        self.assert_failure("stale", "binding-reference-mismatch", request)

    def test_third_invalidation_cycle_escalates_without_state_change(self):
        for index in range(2):
            _request, result = self.reopen("tests", "cycle-%d" % index)
            self.fixture.adopt(result)
        request, result = self.reopen("tests", "cycle-2")
        self.assertEqual("escalated", result["outcome"]["code"])
        self.assertEqual("decomposition-required", result["escalation"]["type"])
        self.assertEqual(request["state"], result["next_state"])
        self.assertEqual([], result["invalidated"])

        unchanged = copy.deepcopy(request)
        active = {
            item["node"]: item["binding"] for item in unchanged["state"]["active"]
        }
        unchanged["operation"]["replacements"][0]["binding"] = active["test-manifest"]
        unchanged["bindings"] = [
            item
            for item in unchanged["bindings"]
            if item["binding"] != request["operation"]["replacements"][0]["binding"]
        ]
        self.assert_failure("stale", "replacement-unchanged", unchanged)

    def test_invalidated_binding_cannot_be_reintroduced_as_duplicate_history(self):
        original = self.fixture.bindings["test-manifest"]
        _request, first = self.reopen("tests", "replacement")
        self.fixture.adopt(first)
        request = self.fixture.request(
            {
                "type": "reopen",
                "target": "tests",
                "reason": "Attempt to reuse invalidated evidence",
                "replacements": [{"node": "test-manifest", "binding": original}],
            }
        )
        self.assert_failure("stale", "replacement-already-historical", request)

    def test_convergence_advances_only_with_matching_decision(self):
        name = "cause"
        binding = self.fixture.convergence_binding(name, "cause-establishment")
        request = self.fixture.request(
            {
                "type": "convergence",
                "from": "UNKNOWN",
                "to": "CAUSE_ESTABLISHED",
                "evidence": binding,
                "retry": False,
            },
            [name],
        )
        result = self.evaluate(request)
        self.assertEqual("CAUSE_ESTABLISHED", result["next_state"]["convergence"]["state"])
        self.assertEqual([], result["invalidated"])
        wrong = copy.deepcopy(request)
        wrong["operation"]["to"] = "FIX_IDENTIFIED"
        self.assert_failure("corrupt", "invalid-convergence-transition", wrong)

    def test_convergence_rejects_wrong_evidence_and_stale_source(self):
        name = "wrong-cause"
        binding = self.fixture.convergence_binding(name, "test-manifest")
        wrong = self.fixture.request(
            {
                "type": "convergence",
                "from": "UNKNOWN",
                "to": "CAUSE_ESTABLISHED",
                "evidence": binding,
                "retry": False,
            },
            [name],
        )
        self.assert_failure("stale", "convergence-evidence-mismatch", wrong)
        stale = copy.deepcopy(wrong)
        stale["operation"]["from"] = "FIX_APPLIED"
        stale["operation"]["to"] = "TARGETED_VERIFIED"
        self.assert_failure("stale", "convergence-state-stale", stale)

    def test_fourth_retry_escalates_without_mutation(self):
        for index in range(3):
            name = "retry-%d" % index
            evidence_ref = self.fixture.convergence_binding(name, "retry-observation")
            request = self.fixture.request(
                {
                    "type": "convergence",
                    "from": "UNKNOWN",
                    "to": "UNKNOWN",
                    "evidence": evidence_ref,
                    "retry": True,
                },
                [name],
            )
            result = self.evaluate(request)
            self.fixture.adopt(result)
        name = "retry-exhausted"
        evidence_ref = self.fixture.convergence_binding(name, "retry-observation")
        request = self.fixture.request(
            {
                "type": "convergence",
                "from": "UNKNOWN",
                "to": "UNKNOWN",
                "evidence": evidence_ref,
                "retry": True,
            },
            [name],
        )
        result = self.evaluate(request)
        self.assertEqual("escalated", result["outcome"]["code"])
        self.assertEqual("human-recovery-required", result["escalation"]["type"])
        self.assertEqual(request["state"], result["next_state"])

    def test_authoritative_chain_rejects_reopen_counter_reset(self):
        _request, result = self.reopen("tests", "authority-reopen")
        self.fixture.adopt(result)
        tampered = copy.deepcopy(self.fixture.state)
        tampered["budgets"]["reopens"] = 0
        tampered["state_sha256"] = policy._state_digest(tampered)
        parent = self.fixture.authority_chain[-2]["binding"]
        tampered_binding = self.fixture.bind_state(tampered, parent)
        self.fixture.state = tampered
        self.fixture.authority_chain[-1] = {
            "binding": tampered_binding,
            "state": copy.deepcopy(tampered),
        }
        name, replacement = self.fixture.replacement(
            "test-manifest", "after-reset"
        )
        request = self.fixture.request(
            {
                "type": "reopen",
                "target": "tests",
                "reason": "Attempt after resetting reopen count",
                "replacements": [
                    {"node": "test-manifest", "binding": replacement}
                ],
            },
            [name],
        )
        self.assert_failure(
            "stale", "policy-authority-transition-mismatch", request
        )

    def test_trusted_tip_rejects_forged_genesis_budget_reset(self):
        for index in range(policy.MAX_REOPENS):
            _request, result = self.reopen("tests", "trusted-tip-%d" % index)
            self.fixture.adopt(result)
        trusted_tip = self.fixture.authority_chain[-1]["binding"]["sha256"]
        forged = copy.deepcopy(self.fixture.state)
        forged["generation"] = 0
        forged["transition_tip"] = None
        forged["transition"] = None
        forged["budgets"] = {
            "reopens": 0,
            "retries": {stage: 0 for stage in policy.CONVERGENCE_STATES},
        }
        forged["convergence"] = {"episode": 0, "state": "UNKNOWN", "evidence": []}
        forged["state_sha256"] = policy._state_digest(forged)
        forged_binding = self.fixture.bind_state(forged)
        self.fixture.state = forged
        self.fixture.authority_chain = [
            {"binding": forged_binding, "state": copy.deepcopy(forged)}
        ]
        name, replacement = self.fixture.replacement(
            "test-manifest", "forged-genesis"
        )
        request = self.fixture.request(
            {
                "type": "reopen",
                "target": "tests",
                "reason": "Attempt with a forged genesis",
                "replacements": [
                    {"node": "test-manifest", "binding": replacement}
                ],
            },
            [name],
        )
        with self.assertRaises(policy.PolicyFailure) as raised:
            self.evaluate(request, trusted_state_binding=trusted_tip)
        self.assertEqual("stale", raised.exception.status)
        self.assertEqual("invalid-policy-genesis", raised.exception.code)

    def test_authoritative_chain_rejects_retry_counter_reset(self):
        retry_name = "authority-retry"
        retry_binding = self.fixture.convergence_binding(
            retry_name, "retry-observation"
        )
        operation = {
            "type": "convergence",
            "from": "UNKNOWN",
            "to": "UNKNOWN",
            "evidence": retry_binding,
            "retry": True,
        }
        result = self.evaluate(self.fixture.request(operation, [retry_name]))
        self.fixture.adopt(result)
        tampered = copy.deepcopy(self.fixture.state)
        tampered["budgets"]["retries"]["UNKNOWN"] = 0
        tampered["state_sha256"] = policy._state_digest(tampered)
        parent = self.fixture.authority_chain[-2]["binding"]
        tampered_binding = self.fixture.bind_state(tampered, parent)
        self.fixture.state = tampered
        self.fixture.authority_chain[-1] = {
            "binding": tampered_binding,
            "state": copy.deepcopy(tampered),
        }
        next_name = "retry-after-reset"
        next_binding = self.fixture.convergence_binding(
            next_name, "retry-observation"
        )
        request = self.fixture.request(
            {
                "type": "convergence",
                "from": "UNKNOWN",
                "to": "UNKNOWN",
                "evidence": next_binding,
                "retry": True,
            },
            [next_name],
        )
        self.assert_failure(
            "stale", "policy-authority-transition-mismatch", request
        )

    def test_convergence_evidence_cannot_replay_after_reopen(self):
        cause_name = "cause-before-reopen"
        cause_binding = self.fixture.convergence_binding(
            cause_name, "cause-establishment"
        )
        cause = self.evaluate(
            self.fixture.request(
                {
                    "type": "convergence",
                    "from": "UNKNOWN",
                    "to": "CAUSE_ESTABLISHED",
                    "evidence": cause_binding,
                    "retry": False,
                },
                [cause_name],
            ),
        )
        self.fixture.adopt(cause)
        _request, reopened = self.reopen("tests", "after-cause")
        self.fixture.adopt(reopened)
        replay = self.fixture.request(
            {
                "type": "convergence",
                "from": "UNKNOWN",
                "to": "CAUSE_ESTABLISHED",
                "evidence": cause_binding,
                "retry": False,
            }
        )
        self.assert_failure(
            "stale", "convergence-evidence-context-mismatch", replay
        )

    def test_malformed_identifiers_are_typed_failures(self):
        cases = []
        target, replacement = self.fixture.replacement(
            "test-manifest", "malformed-target"
        )
        cases.append(
            (
                "invalid-reopen-target-identifier",
                self.fixture.request(
                    {
                        "type": "reopen",
                        "target": [],
                        "reason": "Malformed target",
                        "replacements": [
                            {"node": "test-manifest", "binding": replacement}
                        ],
                    },
                    [target],
                ),
            )
        )
        cases.append(
            (
                "invalid-correction-classification-identifier",
                self.fixture.request(
                    {
                        "type": "correction",
                        "classification": [],
                        "reason": "Malformed classification",
                        "parent_binding": self.fixture.bindings["pr-approval"],
                        "child_identity": {
                            "issue": self.fixture.authority.issue,
                            "run_id": "5" * 32,
                            "family_run_id": self.fixture.authority.run_id,
                            "correction": 1,
                            "run_generation": 0,
                            "sequence": 1,
                            "event_tip": "5" * 64,
                        },
                        "authorization": None,
                        "replacements": [],
                    }
                ),
            )
        )
        cases.append(
            (
                "invalid-convergence-state-identifier",
                self.fixture.request(
                    {
                        "type": "convergence",
                        "from": [],
                        "to": "CAUSE_ESTABLISHED",
                        "evidence": self.fixture.bindings["plan-approval"],
                        "retry": False,
                    }
                ),
            )
        )
        malformed_node = self.fixture.request({"type": "unknown"})
        malformed_node["state"]["active"][0]["node"] = []
        malformed_node["state"]["state_sha256"] = policy._state_digest(
            malformed_node["state"]
        )
        malformed_node["expected_state_sha256"] = malformed_node["state"][
            "state_sha256"
        ]
        cases.append(("invalid-policy-node-identifier", malformed_node))
        malformed_dependency = self.fixture.request({"type": "unknown"})
        malformed_dependency["state"]["active"][2]["dependencies"][0]["node"] = []
        malformed_dependency["state"]["state_sha256"] = policy._state_digest(
            malformed_dependency["state"]
        )
        malformed_dependency["expected_state_sha256"] = malformed_dependency[
            "state"
        ]["state_sha256"]
        cases.append(
            ("invalid-node-dependency-identifier", malformed_dependency)
        )
        replacement_name, replacement = self.fixture.replacement(
            "test-manifest", "malformed-node"
        )
        malformed_replacement = self.fixture.request(
            {
                "type": "reopen",
                "target": "tests",
                "reason": "Malformed replacement node",
                "replacements": [{"node": [], "binding": replacement}],
            },
            [replacement_name],
        )
        cases.append(("invalid-policy-node-identifier", malformed_replacement))
        for code, request in cases:
            with self.subTest(code=code):
                self.assert_failure("corrupt", code, request)

    def test_public_cli_is_canonical_and_read_only(self):
        request, expected = self.reopen("plan", "cli")
        request_path = self.fixture.root / "request.json"
        request_path.write_text(json.dumps(request))
        marker = self.fixture.root / ".agent-workflow" / "runs" / "issue-134" / "state.json"
        marker.parent.mkdir(parents=True)
        marker.write_bytes(b"sentinel\n")
        commands = (
            [sys.executable, str(SCRIPTS / "workflow_policy.py")],
            [sys.executable, "-m", "scripts.workflow_policy"],
        )
        for prefix in commands:
            with self.subTest(prefix=prefix):
                result = subprocess.run(
                    prefix
                    + [
                        "evaluate",
                        "--root",
                        str(self.fixture.root),
                        "--request",
                        str(request_path),
                        "--trusted-state-binding",
                        request["authority_chain"][-1]["binding"]["sha256"],
                    ],
                    cwd=REPOSITORY,
                    capture_output=True,
                )
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(inspector.canonical_document(expected), result.stdout)
                self.assertEqual(b"sentinel\n", marker.read_bytes())

    def test_public_cli_dispatches_correction_and_escalation(self):
        correction_request = self.correction_request("metadata-only", "cli-correction")
        for index in range(policy.MAX_REOPENS):
            _request, result = self.reopen("tests", "cli-cycle-%d" % index)
            self.fixture.adopt(result)
        escalation_request, expected_escalation = self.reopen(
            "tests", "cli-escalation"
        )
        self.assertEqual("escalated", expected_escalation["outcome"]["code"])
        cases = (
            ("correction", correction_request, "evaluated"),
            ("escalation", escalation_request, "escalated"),
        )
        prefixes = (
            [sys.executable, str(SCRIPTS / "workflow_policy.py")],
            [sys.executable, "-m", "scripts.workflow_policy"],
        )
        for name, request, code in cases:
            path = self.fixture.root / ("%s.json" % name)
            path.write_text(json.dumps(request))
            for prefix in prefixes:
                with self.subTest(name=name, prefix=prefix):
                    command = prefix + [
                        "evaluate",
                        "--root",
                        str(self.fixture.root),
                        "--request",
                        str(path),
                        "--trusted-state-binding",
                        request["authority_chain"][-1]["binding"]["sha256"],
                    ]
                    if name == "correction":
                        command.extend(
                            [
                                "--trusted-correction-binding",
                                request["operation"]["authorization"]["binding"][
                                    "sha256"
                                ],
                            ]
                        )
                    result = subprocess.run(
                        command,
                        cwd=REPOSITORY,
                        capture_output=True,
                    )
                    self.assertEqual(0, result.returncode, result.stderr)
                    document = json.loads(result.stdout)
                    self.assertEqual(code, document["outcome"]["code"])
                    if name == "correction":
                        self.assertEqual(
                            list(policy.NODE_ORDER[:-2]),
                            [item["node"] for item in document["preserved"]],
                        )

    def test_public_cli_reports_stale_bindings_and_malformed_identifiers(self):
        replacement_name, replacement = self.fixture.replacement(
            "test-manifest", "cli-stale"
        )
        stale = self.fixture.request(
            {
                "type": "reopen",
                "target": "tests",
                "reason": "Stale binding",
                "replacements": [
                    {"node": "test-manifest", "binding": replacement}
                ],
            },
            [replacement_name],
        )
        stale["operation"]["replacements"][0]["binding"]["size"] += 1
        malformed = copy.deepcopy(stale)
        malformed["operation"]["target"] = []
        malformed_history_cases = []
        for label, status in (
            ("list", []),
            ("object", {}),
            ("number", 1),
            ("null", None),
        ):
            malformed_history = self.fixture.request({"type": "unknown"})
            malformed_history["state"]["history"][0]["status"] = status
            malformed_history["state"]["state_sha256"] = policy._state_digest(
                malformed_history["state"]
            )
            malformed_history["expected_state_sha256"] = malformed_history["state"][
                "state_sha256"
            ]
            malformed_history_cases.append(
                (
                    "malformed-history-%s" % label,
                    malformed_history,
                    5,
                    "invalid-history-status",
                )
            )
        cases = (
            ("stale", stale, 7, "binding-reference-mismatch"),
            (
                "malformed",
                malformed,
                5,
                "invalid-reopen-target-identifier",
            ),
        ) + tuple(malformed_history_cases)
        prefixes = (
            [sys.executable, str(SCRIPTS / "workflow_policy.py")],
            [sys.executable, "-m", "scripts.workflow_policy"],
        )
        for name, request, exit_code, code in cases:
            path = self.fixture.root / ("cli-%s.json" % name)
            path.write_text(json.dumps(request))
            for prefix in prefixes:
                with self.subTest(name=name, prefix=prefix):
                    result = subprocess.run(
                        prefix
                        + [
                            "evaluate",
                            "--root",
                            str(self.fixture.root),
                            "--request",
                            str(path),
                            "--trusted-state-binding",
                            request["authority_chain"][-1]["binding"]["sha256"],
                        ],
                        cwd=REPOSITORY,
                        capture_output=True,
                    )
                    self.assertEqual(exit_code, result.returncode, result.stderr)
                    self.assertEqual(code, json.loads(result.stdout)["outcome"]["code"])

    def test_public_cli_failures_are_typed(self):
        result = subprocess.run(
            [sys.executable, str(SCRIPTS / "workflow_policy.py"), "evaluate"],
            cwd=REPOSITORY,
            capture_output=True,
        )
        self.assertEqual(4, result.returncode)
        document = json.loads(result.stdout)
        self.assertEqual("unsupported", document["outcome"]["status"])
        self.assertEqual("invalid-cli", document["outcome"]["code"])

    def test_migrated_binding_requires_its_exact_plan(self):
        migration_request = json.loads(
            (
                SCRIPTS
                / "tests"
                / "fixtures"
                / "workflow-migration"
                / "v1.json"
            ).read_text()
        )
        migration_request["decision"] = {
            "type": "plan-approval",
            "id": "policy-plan-approval",
        }
        plan = migration.plan(self.fixture.root, migration_request)
        binding = migration.apply(self.fixture.root, plan)["binding"]
        identity = evidence.project(self.fixture.root, binding)["identity"]
        implementation_name = "migration-implementation-a"
        self.fixture.add_binding(
            implementation_name,
            "implementation-a",
            700,
            identity=identity,
        )
        migrated_name = "migrated-plan-approval"
        self.fixture.bindings[migrated_name] = binding
        self.fixture.inputs[migrated_name] = {
            "binding": binding,
            "migration_plan": plan,
        }
        state = policy.initialize(
            self.fixture.root,
            identity["issue"],
            identity["family_run_id"],
            self.fixture.inputs[implementation_name],
        )
        state_authority = self.fixture.bind_state(state)
        self.fixture.state = state
        self.fixture.authority_chain = [
            {"binding": state_authority, "state": copy.deepcopy(state)}
        ]
        request = self.fixture.request(
            self.fixture.bind_operation(
                "plan-approval",
                binding,
                [],
            ),
            [migrated_name],
        )
        request["issue"] = identity["issue"]
        request["family_run_id"] = identity["family_run_id"]
        self.assertEqual(
            "evaluated",
            policy.evaluate(
                self.fixture.root,
                request,
                state_authority["sha256"],
            )["outcome"]["code"],
        )
        migrated_input = next(
            item
            for item in request["bindings"]
            if item["binding"]["sha256"] == binding["sha256"]
        )
        migrated_input["migration_plan"] = None
        self.assert_failure("missing", "migration-plan-required", request)

        no_op = migration.plan(
            self.fixture.root,
            {
                "format": migration.REQUEST_FORMAT,
                "source": {
                    "variant": migration.CANONICAL_VARIANT,
                    "binding": binding,
                },
                "decision": None,
                "lineage": None,
            },
        )
        migrated_input["migration_plan"] = no_op
        self.assert_failure("stale", "migration-plan-not-authoritative", request)


if __name__ == "__main__":
    unittest.main()
