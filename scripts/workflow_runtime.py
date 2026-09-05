"""Inactive supervised boundary for fixed workflow Git, GitHub, and agent I/O."""
import argparse, base64, binascii, copy, datetime, hashlib, json, os, pathlib, re, stat, sys, tempfile, threading
try:
    from . import workflow_inspector
    from . import workflow_supervisor
except ImportError:
    import workflow_inspector
    import workflow_supervisor
RUNTIME_VERSION = '1.1.0'
(BOOTSTRAP_FORMAT, REQUEST_FORMAT, RESULT_FORMAT, PR_OBSERVATION_FORMAT, REMOTE_HEAD_OBSERVATION_FORMAT, AUTHORIZATION_OBSERVATION_FORMAT, SANDBOX_RESULT_FORMAT, ISSUE_SNAPSHOT_FORMAT, BASELINE_FORMAT, DIFF_OBSERVATION_FORMAT, FAILURE_FORMAT) = ('chess-echo-runtime-bootstrap-v1', 'chess-echo-execution-request-v1', 'chess-echo-execution-result-v1', 'chess-echo-github-pr-observation-v1', 'chess-echo-github-remote-head-observation-v1', 'chess-echo-github-authorization-observation-v1', 'chess-echo-external-sandbox-result-v1', 'chess-echo-work-type-issue-snapshot-v1', 'chess-echo-work-type-baseline-v1', 'chess-echo-work-type-diff-observation-v1', 'chess-echo-workflow-runtime-failure-v1')
(MAX_CONFIG_BYTES, MAX_DOCUMENT_BYTES, MAX_OUTPUT_BYTES, MAX_TIMEOUT_MS, MAX_GRACE_MS) = (1024 * 1024, 2 * 1024 * 1024, 8 * 1024 * 1024, 3600000, 60000)
VALIDATION_LIMITS = {'timeout_ms': 3600000, 'grace_ms': 2000, 'output_limit_bytes': 512 * 1024}
BOOTSTRAP_LIMITS = {'timeout_ms': 30000, 'grace_ms': 1000, 'output_limit_bytes': MAX_OUTPUT_BYTES}
OUTCOME_EXIT_CODES = {'missing': 20, 'unsupported': 21, 'corrupt': 22, 'ambiguous': 23, 'stale': 24, 'denied': 25, 'conflict': 26, 'uncertain': 27}
PROCESS_REASONS = {'success': {'process-exited'}, 'nonzero-exit': {'process-exited'}, 'signal': {'process-signaled'}, 'timeout': {'execution-timeout', 'execution-timeout-before-start'}, 'output-limit': {'per-stream-output-limit'}, 'terminated': {'process-group-remained', 'cancelled', 'external-signal', 'external-signal-before-start', 'cancelled-before-start'}, 'startup-failure': {'process-not-started'}, 'supervisor-failure': {'supervision-setup-error', 'supervision-error'}, 'unsupported': {'process-session-isolation-unavailable', 'process-wide-signal-guard-unavailable'}}
ROLES, ASSOCIATIONS = ('implementer', 'planner', 'reviewer'), ('COLLABORATOR', 'MEMBER', 'OWNER')
SHELLS = frozenset({'ash', 'bash', 'csh', 'dash', 'fish', 'ksh', 'powershell', 'pwsh', 'sh', 'tcsh', 'zsh'})
WRAPPERS = frozenset({'busybox', 'command', 'env', 'find', 'nohup', 'xargs'})
(SLUG_RE, REPOSITORY_RE, TARGET_RE, OID_RE, SHA_RE, RUN_RE, RFC3339_RE) = tuple(re.compile(pattern) for pattern in ('[A-Za-z0-9][A-Za-z0-9._:-]{0,127}', '[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', '[A-Za-z0-9][A-Za-z0-9._/-]{0,254}', '(?:[0-9a-f]{40}|[0-9a-f]{64})', '[0-9a-f]{64}', '[0-9a-f]{32}', '\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2})'))
class RuntimeFailure(Exception):
    def __init__(self, status, code, message, subject=None):
        super().__init__(message); self.status, self.code, self.message, self.subject = status, code, message, subject
    def document(self): return {'format': FAILURE_FORMAT, 'outcome': {'status': self.status, 'code': self.code, 'message': self.message, **({'subject': self.subject} if self.subject is not None else {})}}
def _fail(status, code, message, subject=None): raise RuntimeFailure(status, code, message, subject)
def _exact(value, keys, label):
    if not isinstance(value, dict) or set(value) != set(keys): _fail('corrupt', 'invalid-%s-schema' % label, '%s schema is invalid' % label)
def _uint(value, label, maximum=2 ** 63 - 1, positive=False):
    if type(value) is not int or value < (1 if positive else 0) or value > maximum: _fail('corrupt', 'invalid-%s' % label, '%s is outside its limits' % label)
    return value
def _text(value, label, maximum=1024 * 1024, empty=False):
    if not isinstance(value, str) or '\x00' in value: _fail('corrupt', 'invalid-%s' % label, '%s must be UTF-8 text' % label)
    try: size = len(value.encode('utf-8'))
    except UnicodeError: _fail('corrupt', 'invalid-%s' % label, '%s must be UTF-8 text' % label)
    if size > maximum or (not empty and (not value)): _fail('corrupt', 'invalid-%s' % label, '%s is outside its limits' % label)
    return value
def _slug(value, label):
    if not isinstance(value, str) or SLUG_RE.fullmatch(value) is None: _fail('corrupt', 'invalid-%s' % label, '%s is not a safe slug' % label)
    return value
def _sha(value, label):
    if not isinstance(value, str) or SHA_RE.fullmatch(value) is None: _fail('corrupt', 'invalid-%s' % label, '%s is not 64 lowercase hex' % label)
    return value
def _oid(value, label, length=None):
    if not isinstance(value, str) or OID_RE.fullmatch(value) is None or (length is not None and len(value) != length): _fail('corrupt', 'invalid-%s' % label, '%s is not a Git object ID' % label)
    return value
def _reference(value, label='binding', kind='evidence-binding'):
    _exact(value, {'kind', 'sha256', 'size'}, label)
    if value['kind'] != kind: _fail('corrupt', 'invalid-%s-kind' % label, '%s kind is invalid' % label)
    _sha(value['sha256'], '%s-sha256' % label)
    _uint(value['size'], '%s-size' % label, positive=True)
    return copy.deepcopy(value)
def _repository_document(value, issue, family):
    if value is None: return None
    _exact(value, {'format', 'repository', 'issue', 'family_run_id', 'triage_binding', 'observer', 'observed_at', 'object_format', 'base', 'head', 'ancestry', 'changes', 'workspace', 'git_trust', 'head_config', 'raw_diff_sha256', 'observation_sha256'}, 'repository-observation')
    if value['format'] != DIFF_OBSERVATION_FORMAT or value['issue'] != issue or value['family_run_id'] != family: _fail('stale', 'repository-observation-identity', 'Repository observation identity differs from request')
    _reference(value['triage_binding'], 'triage-binding')
    _exact(value['observer'], {'name', 'version', 'source_sha256'}, 'repository-observer'); _slug(value['observer']['name'], 'observer-name'); _slug(value['observer']['version'], 'observer-version'); _sha(value['observer']['source_sha256'], 'observer-source')
    _timestamp(value['observed_at'], 'observed-at')
    if value['object_format'] not in {'sha1', 'sha256'}: _fail('unsupported', 'repository-object-format', 'Repository object format is unsupported')
    oid_length = 40 if value['object_format'] == 'sha1' else 64
    _exact(value['base'], {'ref', 'commit', 'tree'}, 'repository-base'); _exact(value['head'], {'commit', 'tree'}, 'repository-head')
    for field in ('commit', 'tree'): _oid(value['base'][field], 'base-%s' % field, oid_length); _oid(value['head'][field], 'head-%s' % field, oid_length)
    _exact(value['ancestry'], {'base_is_ancestor', 'commit_count'}, 'repository-ancestry')
    if type(value['ancestry']['base_is_ancestor']) is not bool: _fail('corrupt', 'repository-ancestry-flag', 'Repository ancestry flag is invalid')
    _uint(value['ancestry']['commit_count'], 'repository-commit-count')
    if not isinstance(value['changes'], list): _fail('corrupt', 'repository-changes', 'Repository changes must be a list')
    _exact(value['workspace'], {'staged', 'unstaged', 'untracked_non_ignored', 'assume_unchanged', 'skip_worktree', 'status_sha256'}, 'repository-workspace')
    if any(not isinstance(value['workspace'][field], list) for field in ('staged', 'unstaged', 'untracked_non_ignored', 'assume_unchanged', 'skip_worktree')): _fail('corrupt', 'repository-workspace-lists', 'Repository workspace lists are invalid')
    _sha(value['workspace']['status_sha256'], 'workspace-status')
    _exact(value['git_trust'], {'no_replace_objects', 'replacement_refs', 'git_replace_ref_base', 'git_graft_file', 'info_grafts_present', 'environment_redirections', 'alternate_object_directories'}, 'repository-git-trust')
    if type(value['git_trust']['no_replace_objects']) is not bool or type(value['git_trust']['info_grafts_present']) is not bool: _fail('corrupt', 'repository-git-trust-flags', 'Repository Git trust flags are invalid')
    _exact(value['head_config'], {'path', 'blob_oid', 'content_sha256', 'size'}, 'repository-head-config'); _oid(value['head_config']['blob_oid'], 'head-config-blob', oid_length); _sha(value['head_config']['content_sha256'], 'head-config-content'); _uint(value['head_config']['size'], 'head-config-size', MAX_CONFIG_BYTES)
    _sha(value['raw_diff_sha256'], 'raw-diff-sha256')
    _verify = dict(value); digest = _verify.pop('observation_sha256', None); _sha(digest, 'observation-sha256')
    if workflow_inspector.sha256(_canonical(_verify)) != digest: _fail('corrupt', 'observation-digest-mismatch', 'Repository observation digest is stale')
    return copy.deepcopy(value)
def _timestamp(value, label):
    if not isinstance(value, str) or RFC3339_RE.fullmatch(value) is None: _fail('corrupt', 'invalid-%s' % label, '%s is not RFC 3339' % label)
    return value
def _now():
    return datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace('+00:00', 'Z')
def _canonical(value):
    try: data = workflow_inspector.canonical_bytes(value)
    except (TypeError, ValueError, workflow_inspector.InspectionFailure) as error: _fail('corrupt', 'invalid-canonical-json', str(error))
    if len(data) > MAX_DOCUMENT_BYTES: _fail('unsupported', 'document-too-large', 'Document exceeds 2 MiB')
    return data
def _with_digest(value, field):
    result = copy.deepcopy(value); result[field] = workflow_inspector.sha256(_canonical(result)); _canonical(result); return result
def _duplicate_rejector(pairs):
    result = {}
    for (key, value) in pairs:
        if key in result: _fail('ambiguous', 'duplicate-json-key', 'JSON contains duplicate keys', key)
        result[key] = value
    return result
def _parse(data, label, maximum=MAX_DOCUMENT_BYTES, expected=dict):
    if not isinstance(data, bytes) or len(data) > maximum: _fail('unsupported', '%s-too-large' % label, '%s exceeds its byte limit' % label)
    try: value = json.loads(data.decode('utf-8'), object_pairs_hook=_duplicate_rejector)
    except RuntimeFailure: raise
    except (UnicodeError, json.JSONDecodeError, RecursionError, ValueError) as error: _fail('corrupt', 'invalid-%s-json' % label, '%s is invalid JSON: %s' % (label, error))
    if not isinstance(value, expected): _fail('corrupt', 'invalid-%s-type' % label, '%s has the wrong JSON type' % label)
    return value
def _path(value, root, label, allow_dot=True):
    if allow_dot and value == '.': return root
    _text(value, label, maximum=4096)
    if value.startswith('/') or '\\' in value or any((part in {'', '.', '..'} for part in value.split('/'))): _fail('denied', 'invalid-%s' % label, '%s must be repository-relative' % label)
    resolved = (root / value).resolve()
    try: resolved.relative_to(root)
    except ValueError: _fail('denied', '%s-escape' % label, '%s escapes the repository' % label)
    return resolved
def _limits(value, label):
    _exact(value, {'timeout_ms', 'grace_ms', 'output_limit_bytes'}, label)
    _uint(value['timeout_ms'], '%s-timeout' % label, MAX_TIMEOUT_MS, positive=True)
    _uint(value['grace_ms'], '%s-grace' % label, MAX_GRACE_MS)
    _uint(value['output_limit_bytes'], '%s-output-limit' % label, MAX_OUTPUT_BYTES, positive=True)
    return copy.deepcopy(value)
def _command(value, label):
    if not isinstance(value, list) or not 1 <= len(value) <= 128: _fail('corrupt', 'invalid-%s' % label, '%s must be a bounded argv' % label)
    for part in value: _text(part, '%s-part' % label, maximum=4096)
    executable = pathlib.PurePosixPath(value[0]).name.lower()
    if executable in SHELLS or executable in WRAPPERS: _fail('denied', '%s-dispatch-prohibited' % label, 'Shells and dispatch wrappers are prohibited')
    return list(value)
def _executable(path, label):
    try:
        candidate = pathlib.Path(path)
        if not candidate.is_absolute(): _fail('denied', '%s-not-absolute' % label, '%s must be absolute' % label)
        resolved = candidate.resolve(strict=True); metadata = resolved.stat()
        if not stat.S_ISREG(metadata.st_mode) or not os.access(resolved, os.X_OK): _fail('denied', '%s-not-executable' % label, '%s is not a regular executable' % label)
        digest = workflow_inspector.sha256(resolved.read_bytes())
    except RuntimeFailure: raise
    except (OSError, RuntimeError, ValueError) as error: _fail('missing', '%s-unavailable' % label, '%s is unavailable: %s' % (label, error))
    return {'path': str(resolved), 'sha256': digest}
def _git_blob_oid(data, length):
    payload = b'blob ' + str(len(data)).encode('ascii') + b'\x00' + data
    return (hashlib.sha1 if length == 40 else hashlib.sha256)(payload).hexdigest()
def _stdout(result, label, stream='stdout'):
    if not isinstance(result, dict) or result.get('format') != 'chess-echo-process-result-v1': _fail('corrupt', 'invalid-process-result', '%s returned an invalid process result' % label)
    record = result.get(stream)
    if not isinstance(record, dict) or set(record) != {'bytes', 'base64'}: _fail('corrupt', 'invalid-process-output', '%s output is malformed' % label)
    try: data = base64.b64decode(record['base64'], validate=True)
    except (TypeError, ValueError, binascii.Error): _fail('corrupt', 'invalid-process-output', '%s output is not strict base64' % label)
    if type(record['bytes']) is not int or record['bytes'] != len(data): _fail('corrupt', 'invalid-process-output-size', '%s output size is inconsistent' % label)
    return data
def _process_document(value, command, limits, label):
    keys = {'format', 'command_sha256', 'limits', 'containment', 'outcome', 'reason', 'exit_code', 'terminating_signal', 'forced_termination', 'cleanup_verified', 'stdout', 'stderr', 'supervisor_error'}
    _exact(value, keys, '%s-process-result' % label)
    expected_limits = {'timeout_ms': limits['timeout_ms'], 'grace_ms': limits['grace_ms'], 'output_bytes_per_stream': limits['output_limit_bytes']}
    if value['format'] != 'chess-echo-process-result-v1' or value['command_sha256'] != workflow_inspector.sha256(_canonical(command)) or value['limits'] != expected_limits: _fail('corrupt', 'invalid-process-result', '%s process identity is malformed' % label)
    outcome, reason = value['outcome'], value['reason']
    if not isinstance(outcome, str) or not isinstance(reason, str) or outcome not in PROCESS_REASONS or reason not in PROCESS_REASONS[outcome] or type(value['forced_termination']) is not bool or type(value['cleanup_verified']) is not bool or value['exit_code'] is not None and (type(value['exit_code']) is not int or value['exit_code'] < 0) or value['terminating_signal'] is not None and (type(value['terminating_signal']) is not int or value['terminating_signal'] < 1): _fail('corrupt', 'invalid-process-result', '%s process outcome is malformed' % label)
    stdout, stderr = _stdout(value, label), _stdout(value, label, 'stderr')
    if len(stdout) > limits['output_limit_bytes'] or len(stderr) > limits['output_limit_bytes']: _fail('corrupt', 'invalid-process-result', '%s process output exceeds its limit' % label)
    before_start = reason in {'execution-timeout-before-start', 'external-signal-before-start', 'cancelled-before-start', 'process-not-started', 'process-session-isolation-unavailable', 'process-wide-signal-guard-unavailable'}
    containment = {'kind': 'none', 'cleanup_scope': 'none', 'escaped_descendants': 'not-applicable', 'descendant_cleanup_verified': False} if before_start else {'kind': 'posix-process-group', 'cleanup_scope': 'original-process-group', 'escaped_descendants': 'not-observable', 'descendant_cleanup_verified': False}
    if value['containment'] != containment or outcome == 'success' and (value['exit_code'] != 0 or value['terminating_signal'] is not None or value['forced_termination'] or not value['cleanup_verified']) or outcome == 'nonzero-exit' and (not value['exit_code'] or value['terminating_signal'] is not None or not value['cleanup_verified']) or outcome == 'signal' and (value['exit_code'] is not None or value['terminating_signal'] is None or not value['cleanup_verified']) or before_start and (stdout or stderr or value['exit_code'] is not None or value['terminating_signal'] is not None or value['forced_termination'] or not value['cleanup_verified']): _fail('corrupt', 'invalid-process-result', '%s process state is inconsistent' % label)
    if outcome in {'startup-failure', 'supervisor-failure'} and (not isinstance(value['supervisor_error'], str) or not value['supervisor_error']): _fail('corrupt', 'invalid-process-result', '%s supervisor error is inconsistent' % label)
    if outcome not in {'startup-failure', 'supervisor-failure'} and value['supervisor_error'] is not None: _fail('corrupt', 'invalid-process-result', '%s supervisor error is inconsistent' % label)
    return value
def _require_success(result, label):
    data = _stdout(result, label)
    if result.get('outcome') != 'success' or result.get('exit_code') != 0 or result.get('cleanup_verified') is not True:
        outcome = result.get('outcome')
        status = 'uncertain' if outcome in {'terminated', 'supervisor-failure'} else 'missing'
        _fail(status, '%s-failed' % label, '%s did not complete successfully' % label)
    return data
def _common_env(paths):
    return {'PATH': os.pathsep.join(paths), 'HOME': '', 'LC_ALL': 'C.UTF-8', 'LANG': 'C.UTF-8', 'TZ': 'UTC'}
def _run(command, *, limits, cwd, environment, cancel_event=None):
    with tempfile.TemporaryDirectory(prefix='chess-echo-runtime-') as home:
        env = dict(environment)
        env['HOME'] = home
        return workflow_supervisor.supervise(command, timeout_ms=limits['timeout_ms'], grace_ms=limits['grace_ms'], output_limit_bytes=limits['output_limit_bytes'], cwd=str(cwd), env=env, cancel_event=cancel_event)
def _git_env(paths):
    env = _common_env(paths)
    env.update({'GIT_OPTIONAL_LOCKS': '0', 'GIT_NO_LAZY_FETCH': '1', 'GIT_NO_REPLACE_OBJECTS': '1', 'GIT_CONFIG_NOSYSTEM': '1', 'GIT_TERMINAL_PROMPT': '0', 'GIT_ASKPASS': ''})
    return env
def _github_env(paths, token):
    env = _common_env(paths)
    env.update({'GH_HOST': 'github.com', 'GH_PROMPT_DISABLED': '1', 'GH_TOKEN': token})
    return env
def _urlencode(value):
    return ''.join((chr(byte) if byte < 128 and (chr(byte).isalnum() or chr(byte) in '-._~') else '%%%02X' % byte) for byte in value.encode('utf-8'))
def _branch(value, label):
    _text(value, label, maximum=255); parts = value.split('/')
    if value == '@' or value.startswith('-') or value.endswith('.') or '..' in value or '@{' in value or any((not part or part.startswith('.') or part.endswith('.lock') for part in parts)) or any((ord(char) < 32 or ord(char) == 127 or char in ' ~^:?*[\\' for char in value)):
        _fail('denied', 'invalid-%s' % label, '%s is not a safe branch name' % label)
    return value
def _ref_path(value): return '/'.join(_urlencode(part) for part in value.split('/'))
def _git_admin_trust(root):
    try: common = workflow_inspector.resolve_store(root).common_dir
    except workflow_inspector.InspectionFailure as error: _fail(error.status, error.code, error.message, error.subject)
    alternate_file = common / 'objects' / 'info' / 'alternates'
    try: data = alternate_file.read_bytes() if alternate_file.is_file() else b''
    except OSError as error: _fail('missing', 'alternates-unreadable', 'Git alternates cannot be read: %s' % error)
    if len(data) > MAX_CONFIG_BYTES: _fail('unsupported', 'alternates-too-large', 'Git alternates exceed the read limit')
    try: alternates = sorted(set(line for line in data.decode('utf-8').splitlines() if line))
    except UnicodeError: _fail('corrupt', 'alternates-invalid', 'Git alternates are not UTF-8')
    return common, (common / 'info' / 'grafts').is_file(), tuple(alternates)
def _index_flags(data):
    assume, skip = [], []
    for item in data.split(b'\x00'):
        if not item: continue
        try: marker, path = item[:1].decode('ascii'), item[2:].decode('utf-8')
        except UnicodeError: _fail('corrupt', 'invalid-index-flags', 'Git index flags are invalid')
        (skip if marker == 'S' else assume if marker.islower() else []).append(path)
    return sorted(assume), sorted(skip)
def _validate_config(config, root):
    if not isinstance(config, dict) or 'orchestrator' not in config: _fail('missing', 'orchestrator-config-missing', 'Base config has no orchestrator block')
    orchestrator = config['orchestrator']
    _exact(orchestrator, {'format', 'mode', 'frozen_issues', 'agent_roles', 'git', 'github', 'validation_path', 'human_approval'}, 'orchestrator-config')
    if orchestrator['format'] != 'chess-echo-orchestrator-config-v1': _fail('unsupported', 'orchestrator-config-format', 'Orchestrator config format is unsupported')
    if orchestrator['mode'] not in {'inactive', 'active'}: _fail('unsupported', 'orchestrator-mode', 'Orchestrator mode is unsupported')
    frozen = orchestrator['frozen_issues']
    if not isinstance(frozen, list) or any((type(issue) is not int or issue < 1 for issue in frozen)) or frozen != sorted(set(frozen)): _fail('corrupt', 'invalid-frozen-issues', 'Frozen issues must be sorted and unique')
    roles = orchestrator['agent_roles']
    if not isinstance(roles, list) or [row.get('role') for row in roles if isinstance(row, dict)] != list(ROLES): _fail('corrupt', 'invalid-agent-roles', 'Exactly three canonically ordered roles are required')
    for row in roles:
        _exact(row, {'role', 'command_prefix', 'cwd', 'timeout_ms', 'grace_ms', 'output_limit_bytes', 'containment', 'provider_name', 'provider_source_sha256'}, 'agent-role')
        _command(row['command_prefix'], 'agent-command')
        _path(row['cwd'], root, 'agent-cwd')
        _limits({key: row[key] for key in ('timeout_ms', 'grace_ms', 'output_limit_bytes')}, 'agent')
        if row['containment'] != 'external-sandbox-v1': _fail('denied', 'sandbox-containment-required', 'Agent roles require external-sandbox-v1')
        _slug(row['provider_name'], 'provider-name')
        _sha(row['provider_source_sha256'], 'provider-source')
    for (name, expected) in (('git', 'git'), ('github', 'gh')):
        entry = orchestrator[name]
        _exact(entry, {'command', 'timeout_ms', 'grace_ms', 'output_limit_bytes'}, name)
        command = _command(entry['command'], '%s-command' % name)
        if command != [expected]: _fail('denied', '%s-command-mismatch' % name, '%s command must be [%r]' % (name, expected))
        _limits({key: entry[key] for key in ('timeout_ms', 'grace_ms', 'output_limit_bytes')}, name)
        if name == 'github' and entry['output_limit_bytes'] > 512 * 1024: _fail('denied', 'github-output-limit', 'GitHub output cannot fit the execution-result limit')
    paths = orchestrator['validation_path']
    if not isinstance(paths, list) or not paths or len(paths) > 32 or (len(paths) != len(set(paths))): _fail('corrupt', 'invalid-validation-path', 'Validation PATH must be a bounded unique list')
    for value in paths:
        if not isinstance(value, str) or os.pathsep in value or not pathlib.Path(value).is_absolute(): _fail('denied', 'invalid-validation-path', 'Validation PATH entries must be absolute')
    approval = orchestrator['human_approval']
    _exact(approval, {'allowed_accounts', 'allowed_associations'}, 'human-approval')
    accounts = approval['allowed_accounts']
    if not isinstance(accounts, list): _fail('corrupt', 'invalid-allowed-accounts', 'Allowed accounts must be a list')
    ids = []
    for account in accounts:
        _exact(account, {'account_id', 'login'}, 'allowed-account')
        ids.append(_uint(account['account_id'], 'account-id', positive=True))
        _slug(account['login'], 'account-login')
    if ids != sorted(set(ids)): _fail('ambiguous', 'invalid-allowed-account-order', 'Allowed accounts must be sorted by unique ID')
    associations = approval['allowed_associations']
    if not isinstance(associations, list) or associations != sorted(set(associations)) or any((item not in ASSOCIATIONS for item in associations)): _fail('corrupt', 'invalid-allowed-associations', 'Allowed associations are invalid')
    return copy.deepcopy(orchestrator)
def execution_attempt_id(authority_binding, operation, command_source, input_bindings, repository_before, limits, reconciliation_expectation=None):
    return workflow_inspector.sha256(_canonical({'authority_binding': authority_binding, 'operation': operation, 'command_source': command_source, 'input_bindings': input_bindings, 'repository_before': repository_before, 'limits': limits, 'reconciliation_expectation': reconciliation_expectation}))
class Runtime:
    __slots__ = ('root', 'repository', '_git_executable', '_gh_executable', '_github_token', '_config', '_config_bytes', '_bootstrap')
    def __init__(self, root, repository, git_executable, gh_executable, token, config, config_bytes, document):
        self.root, self.repository = root, repository
        self._git_executable, self._gh_executable, self._github_token = git_executable, gh_executable, token
        self._config, self._config_bytes, self._bootstrap = config, config_bytes, document
    def __repr__(self): return 'Runtime(root=%r, repository=%r, mode=%r)' % (str(self.root), self.repository, self._config['mode'])
    def bootstrap_document(self): return copy.deepcopy(self._bootstrap)
    def _base_config(self):
        parsed = _parse(self._config_bytes, 'bootstrap-config', MAX_CONFIG_BYTES)
        expected = _validate_config(parsed, self.root)
        if self._config != expected:
            _fail('stale', 'runtime-config-mutated', 'In-memory config differs from bootstrap bytes')
        return expected
    def _verify_tool(self, record, label):
        current = _executable(record['path'], label)
        if current != record:
            _fail('stale', '%s-replaced' % label, '%s executable changed after bootstrap' % label)
    def _paths(self):
        return list(self._config['validation_path'])
    def _git(self, arguments, limits=None, cancel_event=None):
        self._verify_tool(self._git_executable, 'git'); configured = {key: self._config['git'][key] for key in ('timeout_ms', 'grace_ms', 'output_limit_bytes')}
        effective = limits or _limits(configured, 'git'); command = [self._git_executable['path'], '-c', 'core.fsmonitor=false'] + list(arguments)
        result = _process_document(_run(command, limits=effective, cwd=self.root, environment=_git_env(self._paths()), cancel_event=cancel_event), command, effective, 'git')
        return _require_success(result, 'git')
    def _github(self, arguments, cancel_event=None, allow_failure=False):
        self._verify_tool(self._gh_executable, 'github')
        configured = {key: self._config['github'][key] for key in ('timeout_ms', 'grace_ms', 'output_limit_bytes')}
        result = _run([self._gh_executable['path']] + list(arguments), limits=_limits(configured, 'github'), cwd=self.root, environment=_github_env(self._paths(), self._github_token), cancel_event=cancel_event)
        self._token_safe(result)
        if allow_failure:
            return result
        return _require_success(result, 'github')
    def _token_safe(self, result):
        if self._github_token.encode() in _stdout(result, 'github') or self._github_token.encode() in _stdout(result, 'github', 'stderr'): _fail('denied', 'github-token-disclosed', 'GitHub process disclosed its credential')
    def _deny_frozen(self, issue):
        _uint(issue, 'issue', positive=True)
        if issue in self._config['frozen_issues']:
            _fail('denied', 'issue-frozen', 'Issue is frozen by base configuration', str(issue))
    def build_request(self, *, issue, family_run_id, attempt_id, authority_binding, operation, command_source, input_bindings, repository_before, limits, reconciliation_expectation=None):
        self._base_config()
        self._deny_frozen(issue)
        if not isinstance(family_run_id, str) or RUN_RE.fullmatch(family_run_id) is None:
            _fail('corrupt', 'invalid-family-run-id', 'Family run ID is invalid')
        _sha(attempt_id, 'attempt-id')
        authority_binding = _reference(authority_binding, 'authority-binding')
        operation = self._validate_operation(operation)
        repository_before = _repository_document(repository_before, issue, family_run_id)
        if repository_before is not None and repository_before['repository'] != self.repository: _fail('denied', 'repository-observation-target', 'Repository observation differs from bootstrap repository')
        if operation['kind'] in {'agent', 'validation', 'github-write'} and repository_before is None: _fail('missing', 'repository-before-required', 'Agent, validation, and GitHub write execution require a repository observation')
        source = self._validate_command_source(command_source)
        if operation['kind'] == 'validation' and (source['profile'] is None or source['entry'] != operation['name']): _fail('stale', 'validation-command-source-mismatch', 'Validation command source differs from operation')
        if operation['kind'] == 'agent' and (source['profile'] is not None or source['entry'] != operation['role']): _fail('stale', 'agent-command-source-mismatch', 'Agent command source differs from role')
        if operation['kind'] in {'git-read', 'github-read', 'github-write'} and (source['profile'] is not None or source['entry'] != operation['name']): _fail('stale', 'fixed-command-source-mismatch', 'Fixed command source differs from operation')
        inputs = self._validate_inputs(input_bindings)
        limits = _limits(limits, 'request-limits')
        if limits != self._expected_limits(operation, source):
            _fail('stale', 'request-limits-mismatch', 'Request limits differ from base configuration')
        if repository_before is not None and len(_canonical(repository_before)) + 8 * ((limits['output_limit_bytes'] + 2) // 3) + 65536 > MAX_DOCUMENT_BYTES: _fail('denied', 'execution-result-budget', 'Request cannot fit its worst-case execution result')
        expected = execution_attempt_id(authority_binding, operation, source, inputs, repository_before, limits, reconciliation_expectation)
        if attempt_id != expected:
            _fail('corrupt', 'attempt-id-mismatch', 'Attempt ID does not bind the exact request')
        document = {'format': REQUEST_FORMAT, 'issue': issue, 'family_run_id': family_run_id, 'attempt_id': attempt_id, 'authority_binding': authority_binding, 'operation': operation, 'command_source': source, 'input_bindings': inputs, 'repository_before': copy.deepcopy(repository_before), 'limits': limits}
        return _with_digest(document, 'request_sha256')
    def _validate_operation(self, value):
        _exact(value, {'kind', 'name', 'role'}, 'operation')
        if value['kind'] not in {'agent', 'validation', 'git-read', 'github-read', 'github-write'}:
            _fail('unsupported', 'operation-kind', 'Operation kind is unsupported')
        _slug(value['name'], 'operation-name')
        if value['kind'] == 'agent':
            if value['role'] not in ROLES:
                _fail('corrupt', 'invalid-operation-role', 'Agent operation requires one role')
        elif value['role'] is not None:
            _fail('corrupt', 'unexpected-operation-role', 'Non-agent operation cannot name a role')
        return copy.deepcopy(value)
    def _validate_command_source(self, value):
        _exact(value, {'config_binding', 'config_content_sha256', 'config_blob_oid', 'profile', 'entry'}, 'command-source')
        result = copy.deepcopy(value)
        result['config_binding'] = _reference(value['config_binding'], 'config-binding')
        if value['config_content_sha256'] != self._bootstrap['config']['content_sha256'] or value['config_blob_oid'] != self._bootstrap['config']['blob_oid']:
            _fail('stale', 'request-config-mismatch', 'Request does not bind bootstrap config')
        _slug(value['entry'], 'command-entry')
        if value['profile'] is not None:
            _slug(value['profile'], 'command-profile')
        return result
    def _validate_inputs(self, values):
        if not isinstance(values, list) or len(values) > 256:
            _fail('corrupt', 'invalid-input-bindings', 'Input bindings must be bounded')
        result = []
        keys = []
        for value in values:
            _exact(value, {'role', 'binding'}, 'input-binding')
            row = {'role': _slug(value['role'], 'input-role'), 'binding': _reference(value['binding'])}
            result.append(row)
            keys.append((row['role'], row['binding']['sha256']))
        if len(keys) != len(set(keys)):
            _fail('ambiguous', 'duplicate-input-binding', 'Input binding is duplicated')
        if keys != sorted(keys):
            _fail('corrupt', 'noncanonical-input-binding-order', 'Input bindings are not ordered')
        return result
    def _expected_limits(self, operation, source):
        if operation['kind'] == 'validation':
            return dict(VALIDATION_LIMITS)
        if operation['kind'] == 'agent':
            row = next((item for item in self._config['agent_roles'] if item['role'] == operation['role']))
            return {key: row[key] for key in ('timeout_ms', 'grace_ms', 'output_limit_bytes')}
        if operation['kind'] == 'git-read':
            entry = self._config['git']
        else:
            entry = self._config['github']
        return _limits({key: entry[key] for key in ('timeout_ms', 'grace_ms', 'output_limit_bytes')}, operation['kind'])
    def _validate_request(self, value, reconciliation_expectation):
        _exact(value, {'format', 'issue', 'family_run_id', 'attempt_id', 'authority_binding', 'operation', 'command_source', 'input_bindings', 'repository_before', 'limits', 'request_sha256'}, 'execution-request')
        if value['format'] != REQUEST_FORMAT:
            _fail('unsupported', 'request-format', 'Execution request format is unsupported')
        rebuilt = self.build_request(issue=value['issue'], family_run_id=value['family_run_id'], attempt_id=value['attempt_id'], authority_binding=value['authority_binding'], operation=value['operation'], command_source=value['command_source'], input_bindings=value['input_bindings'], repository_before=value['repository_before'], limits=value['limits'], reconciliation_expectation=reconciliation_expectation)
        if value != rebuilt:
            _fail('corrupt', 'request-digest-mismatch', 'Execution request is not canonical')
        return rebuilt
    def _resolve_name(self, name, label):
        if label == 'validation' and name not in {'make', 'npm', 'npx'}: _fail('denied', 'validation-executable-prohibited', 'Validation executable is not allowlisted')
        if '/' in name or '\\' in name or pathlib.PurePosixPath(name).name.lower() in SHELLS | WRAPPERS:
            _fail('denied', '%s-prohibited' % label, '%s executable name is prohibited' % label)
        matches = []
        for directory in self._paths():
            candidate = pathlib.Path(directory) / name
            if candidate.is_file() and os.access(candidate, os.X_OK):
                matches.append(candidate.resolve())
        if not matches:
            _fail('missing', '%s-not-found' % label, '%s executable is absent from controlled PATH' % label)
        resolved = matches[0]
        if name in {'git', 'gh'} and str(resolved) not in {self._git_executable['path'], self._gh_executable['path']}:
            _fail('denied', '%s-tool-substitution' % label, 'Git/GitHub executable substitution is prohibited')
        return str(resolved)
    def _resolve_command(self, request, request_binding, write_payload):
        operation = request['operation']
        source = request['command_source']
        if operation['kind'] == 'validation':
            profiles = self._bootstrap['profiles']
            profile = next((item for item in profiles if item['id'] == source['profile']), None)
            check = next((item for item in (profile or {}).get('checks', []) if item['name'] == source['entry']), None)
            if check is None or source['entry'] != operation['name']:
                _fail('missing', 'validation-entry-missing', 'Validation entry is not in base config')
            command = _command(check['command'], 'validation-command')
            record = next(row for row in self._bootstrap['validation_executables'] if row['profile'] == source['profile'] and row['entry'] == source['entry'])
            if record['path'] is None: _fail('missing', 'validation-executable-not-found', 'Validation executable was unavailable at bootstrap')
            current = _executable(record['path'], 'validation-executable')
            if current != {'path': record['path'], 'sha256': record['sha256']}: _fail('stale', 'validation-executable-replaced', 'Validation executable changed after bootstrap')
            command[0], cwd = record['path'], _path(check['cwd'], self.root, 'validation-cwd')
            return (command, cwd, _common_env(self._paths()))
        if operation['kind'] == 'agent':
            row = next((item for item in self._config['agent_roles'] if item['role'] == operation['role']))
            if source['profile'] is not None or source['entry'] != operation['role']:
                _fail('stale', 'agent-command-source-mismatch', 'Agent command source differs from role')
            command = _command(row['command_prefix'], 'agent-command')
            executable = _executable(self._resolve_name(command[0], 'agent'), 'agent')
            if executable['sha256'] != row['provider_source_sha256']: _fail('denied', 'agent-executable-mismatch', 'Agent executable differs from configured provider source')
            command[0] = executable['path']
            command += ['--request-binding', _canonical(request_binding).decode('ascii')]
            return (command, _path(row['cwd'], self.root, 'agent-cwd'), _common_env(self._paths()))
        if operation['kind'] == 'github-write':
            return self._github_write_command(operation, write_payload)
        _fail('unsupported', 'execute-read-operation', 'Read operations use the fixed observation API')
    def _github_write_command(self, operation, payload):
        if operation['name'] != 'create-draft-pr':
            _fail('unsupported', 'github-write-operation', 'GitHub write operation is unsupported')
        command = [self._gh_executable['path'], 'pr', 'create', '--repo', self.repository, '--draft', '--base', payload['base_ref'], '--head', payload['head_ref'], '--title', payload['title'], '--body', payload['body']]
        return (command, self.root, _github_env(self._paths(), self._github_token))
    def _validate_expectation(self, value, repository_before, payload=None):
        _exact(value, {'repository', 'base_ref', 'base_sha', 'head_ref', 'head_sha', 'title_sha256', 'body_sha256'}, 'reconciliation-expectation')
        if value['repository'] != self.repository:
            _fail('denied', 'reconciliation-repository', 'Reconciliation repository differs from bootstrap')
        _text(value['base_ref'], 'base-ref', maximum=255)
        _branch(value['head_ref'], 'head-ref')
        for field in ('base_sha', 'head_sha'):
            _oid(value[field], field)
        for field in ('title_sha256', 'body_sha256'):
            _sha(value[field], field)
        if payload is not None:
            _exact(payload, {'repository', 'base_ref', 'head_ref', 'title', 'body'}, 'github-write-payload'); _fail('denied', 'github-write-repository', 'Write repository differs from bootstrap') if payload['repository'] != self.repository else None
            for field in ('base_ref', 'head_ref', 'title', 'body'):
                _text(payload[field], 'write-%s' % field, empty=field == 'body')
                if self._github_token in payload[field]: _fail('denied', 'github-token-in-write-payload', 'GitHub write payload contains the designated credential')
        if payload is not None and (workflow_inspector.sha256(payload['title'].encode()) != value['title_sha256'] or workflow_inspector.sha256(payload['body'].encode()) != value['body_sha256'] or payload['base_ref'] != value['base_ref'] or (payload['head_ref'] != value['head_ref'])):
            _fail('corrupt', 'write-expectation-mismatch', 'Write payload differs from reconciliation expectation')
        if repository_before is None or value['head_sha'] != repository_before['head']['commit']:
            _fail('stale', 'github-write-validated-head-mismatch', 'GitHub write head SHA differs from the validated local HEAD')
        return copy.deepcopy(value)
    def execute(self, request_document, request_binding, reconciliation_expectation=None, cancel_event=None, sandbox_provider=None, write_payload=None):
        reconciliation_expectation, write_payload = copy.deepcopy((reconciliation_expectation, write_payload)); config = self._base_config()
        request = self._validate_request(request_document, reconciliation_expectation)
        request_binding = _reference(request_binding, 'request-binding')
        if config['mode'] != 'active': _fail('unsupported', 'runtime-inactive', 'Execution is disabled by base configuration')
        operation, expectation = request['operation'], None
        if operation['kind'] == 'github-write':
            expectation = self._validate_expectation(reconciliation_expectation, request['repository_before'], write_payload); self._verify_tool(self._gh_executable, 'github')
        elif reconciliation_expectation is not None: _fail('corrupt', 'unexpected-reconciliation', 'Only GitHub writes may reconcile')
        provider_row = None
        if operation['kind'] == 'agent':
            provider_row = next((item for item in self._config['agent_roles'] if item['role'] == operation['role']))
            self._validate_provider(sandbox_provider, provider_row)
        preflight_cancelled = cancel_event is not None and cancel_event.is_set(); before, remote_head = request['repository_before'], None
        if not preflight_cancelled:
            try:
                self._ensure_config_current(cancel_event)
                if operation['kind'] == 'github-write':
                    remote_head = self.observe_remote_head(request['issue'], request['family_run_id'], before, expectation['head_ref'], cancel_event=cancel_event)
                elif before is not None and self.observe_diff(request['issue'], request['family_run_id'], before['triage_binding'], before['observed_at'], cancel_event) != before: _fail('stale', 'repository-before-mismatch', 'Current repository differs from request observation')
            except RuntimeFailure:
                if cancel_event is None or not cancel_event.is_set(): raise
                preflight_cancelled = True
        process_cancel = cancel_event if not preflight_cancelled else threading.Event(); process_cancel.set() if preflight_cancelled else None
        (command, cwd, environment) = self._resolve_command(request, request_binding, write_payload)
        process_error = None
        try: process = _process_document(_run(command, limits=request['limits'], cwd=cwd, environment=environment, cancel_event=process_cancel), command, request['limits'], 'execution')
        except BaseException as error:
            if operation['kind'] != 'github-write': raise
            process, process_error = None, error
        write_failure = None
        if operation['kind'] == 'github-write' and process_error is None:
            try: self._token_safe(process); candidate = _stdout(process, 'execution')
            except RuntimeFailure as failure: write_failure, candidate = failure, b''
        else: candidate = b'' if process_error is not None else _stdout(process, 'execution')
        process_ok = process is not None and process['outcome'] == 'success'; write_may_have_started = operation['kind'] == 'github-write' and (process is None or process['reason'] != 'cancelled-before-start')
        reconciliation = {'status': 'not-required', 'external_identity': None}
        outcome = 'succeeded' if process_ok else 'failed'
        if isinstance(process, dict) and process.get('reason') in {'cancelled', 'cancelled-before-start'}: outcome = 'cancelled'
        if write_may_have_started:
            try: (reconciliation, outcome) = self._reconcile_pr(expectation, None)
            except RuntimeFailure as failure:
                if write_failure is None: write_failure = failure
                reconciliation, outcome = {'status': 'ambiguous' if failure.status == 'ambiguous' else 'unknown', 'external_identity': None}, 'failed' if failure.status == 'ambiguous' else 'uncertain'
            except BaseException as error:
                if process_error is None: process_error = error
                reconciliation, outcome = {'status': 'unknown', 'external_identity': None}, 'uncertain'
        if operation['kind'] == 'github-write': reconciliation['remote_head'] = copy.deepcopy(remote_head)
        candidate_record, sandbox = None, None
        if operation['kind'] == 'agent':
            candidate_record = {'sha256': workflow_inspector.sha256(candidate), 'size': len(candidate)}
            sandbox = sandbox_provider.verify(copy.deepcopy(request), copy.deepcopy(process), candidate)
            self._validate_sandbox(sandbox, request, process, candidate_record, provider_row, sandbox_provider)
        repository_after, postflight_cancel = None, None if write_may_have_started else process_cancel
        if request['repository_before'] is not None:
            if postflight_cancel is not None and postflight_cancel.is_set(): outcome = 'cancelled'
            else:
                try: repository_after = self.observe_diff(request['issue'], request['family_run_id'], request['repository_before']['triage_binding'], cancel_event=postflight_cancel)
                except RuntimeFailure as failure:
                    if postflight_cancel is not None and postflight_cancel.is_set(): outcome = 'cancelled'
                    elif failure.code == 'document-too-large': outcome = 'failed'
                    elif write_may_have_started:
                        if write_failure is None: write_failure = failure
                        outcome = 'failed'
                    else: raise
                except BaseException as error:
                    if process_error is None: process_error = error
                    outcome = 'uncertain'
            if repository_after is None and outcome == 'succeeded': outcome = 'cancelled' if postflight_cancel is not None and postflight_cancel.is_set() else 'failed'
        if postflight_cancel is not None and postflight_cancel.is_set() and outcome == 'succeeded': outcome = 'cancelled'
        if process_error is not None: raise process_error
        if write_failure is not None: _fail(write_failure.status, write_failure.code, write_failure.message, write_failure.subject or 'reconciliation:%s' % reconciliation['status'])
        result = {'format': RESULT_FORMAT, 'request_binding': request_binding, 'attempt_id': request['attempt_id'], 'process_result': copy.deepcopy(process), 'candidate_output': candidate_record, 'repository_after': repository_after, 'reconciliation': reconciliation, 'sandbox': copy.deepcopy(sandbox), 'outcome': outcome}
        try: return _with_digest(result, 'result_sha256')
        except RuntimeFailure as failure:
            if failure.code != 'document-too-large': raise
            result['repository_after'], result['outcome'] = None, 'failed'
            return _with_digest(result, 'result_sha256')
    def _ensure_config_current(self, cancel_event=None):
        blob = self._git(['rev-parse', 'HEAD:.github/agent-workflow.json'], cancel_event=cancel_event).decode('ascii').strip()
        data = self._git(['show', 'HEAD:.github/agent-workflow.json'], limits={'timeout_ms': 30000, 'grace_ms': 1000, 'output_limit_bytes': MAX_CONFIG_BYTES}, cancel_event=cancel_event)
        if blob != self._bootstrap['config']['blob_oid'] or data != self._config_bytes:
            _fail('stale', 'head-config-drift', 'HEAD config differs from bootstrap config')
    def _validate_provider(self, provider, row):
        if provider is None:
            _fail('unsupported', 'sandbox-provider-required', 'Agent execution requires an injected provider')
        if getattr(provider, 'name', None) != row['provider_name'] or getattr(provider, 'source_sha256', None) != row['provider_source_sha256'] or (not isinstance(getattr(provider, 'version', None), str)) or SLUG_RE.fullmatch(provider.version) is None or (not callable(getattr(provider, 'verify', None))):
            _fail('denied', 'sandbox-provider-mismatch', 'Injected provider identity differs from base config')
    def _validate_sandbox(self, value, request, process, candidate, provider, injected):
        _exact(value, {'format', 'provider', 'request_sha256', 'command_sha256', 'repository_scope', 'credential_access', 'authority_store_access', 'containment', 'candidate_sha256', 'candidate_size', 'result_sha256'}, 'sandbox-result')
        if value['format'] != SANDBOX_RESULT_FORMAT:
            _fail('unsupported', 'sandbox-result-format', 'Sandbox result format is unsupported')
        _exact(value['provider'], {'name', 'version', 'source_sha256'}, 'sandbox-provider')
        unsigned = dict(value)
        unsigned.pop('result_sha256')
        expected = {'name': provider['provider_name'], 'version': injected.version, 'source_sha256': provider['provider_source_sha256']}
        if value['provider'] != expected or value['request_sha256'] != request['request_sha256'] or value['command_sha256'] != process.get('command_sha256') or (value['repository_scope'] != str(self.root)) or (value['credential_access'] != 'denied') or (value['authority_store_access'] != 'denied') or (value['containment'] != 'verified') or (value['candidate_sha256'] != candidate['sha256']) or (value['candidate_size'] != candidate['size']) or (value['result_sha256'] != workflow_inspector.sha256(_canonical(unsigned))):
            _fail('denied', 'sandbox-verification-failed', 'Sandbox result does not prove the required boundary')
    def _reconcile_pr(self, expectation, cancel_event):
        owner = self.repository.split('/', 1)[0]
        arguments = ['api', '--paginate', '--slurp', 'repos/%s/pulls?state=open&per_page=100&base=%s&head=%s%%3A%s' % (self.repository, _urlencode(expectation['base_ref']), _urlencode(owner), _urlencode(expectation['head_ref']))]; result = self._github(arguments, cancel_event=cancel_event, allow_failure=True)
        try:
            _process_document(result, [self._gh_executable['path']] + arguments, self._config['github'], 'github-reconciliation')
            values = _parse(_stdout(result, 'github-reconciliation'), 'github-reconciliation', expected=list)
            if any(not isinstance(page, list) for page in values): _fail('corrupt', 'invalid-github-reconciliation', 'GitHub reconciliation response is malformed')
            matches = [item for page in values for item in page if self._pr_matches(item, expectation)]
        except (RuntimeFailure, UnicodeError):
            return ({'status': 'unknown', 'external_identity': None}, 'uncertain')
        if result.get('outcome') != 'success':
            return ({'status': 'unknown', 'external_identity': None}, 'uncertain')
        if len(matches) > 1:
            _fail('ambiguous', 'github-write-reconciliation-ambiguous', 'Multiple pull requests match the write')
        if not matches:
            return ({'status': 'unknown', 'external_identity': None}, 'uncertain')
        return ({'status': 'confirmed', 'external_identity': matches[0]['html_url']}, 'succeeded')
    def _pr_matches(self, value, expected):
        if not isinstance(value, dict): _fail('corrupt', 'invalid-github-reconciliation', 'GitHub reconciliation row is malformed')
        base, head, body, url = value.get('base'), value.get('head'), value.get('body'), value.get('html_url')
        if 'body' not in value or not isinstance(base, dict) or not isinstance(head, dict) or not isinstance(value.get('state'), str) or type(value.get('draft')) is not bool or not isinstance(value.get('title'), str) or body is not None and not isinstance(body, str) or not isinstance(url, str) or any((not isinstance(row.get(field), str) for row in (base, head) for field in ('ref', 'sha'))): _fail('corrupt', 'invalid-github-reconciliation', 'GitHub reconciliation row is malformed')
        prefix, number = 'https://github.com/%s/pull/' % self.repository, value.get('number')
        return value['state'] == 'open' and value['draft'] is True and type(number) is int and number > 0 and base['ref'] == expected['base_ref'] and base['sha'] == expected['base_sha'] and head['ref'] == expected['head_ref'] and head['sha'] == expected['head_sha'] and workflow_inspector.sha256(value['title'].encode()) == expected['title_sha256'] and workflow_inspector.sha256((body or '').encode()) == expected['body_sha256'] and url == prefix + str(number)
    def _read_remote_head(self, head_ref, oid_length, cancel_event):
        expected_ref = 'refs/heads/%s' % head_ref; arguments = ['api', 'repos/%s/git/matching-refs/heads/%s' % (self.repository, _ref_path(head_ref))]
        result = self._github(arguments, cancel_event=cancel_event, allow_failure=True)
        try: _process_document(result, [self._gh_executable['path']] + arguments, self._config['github'], 'github-remote-head')
        except RuntimeFailure: _fail('uncertain', 'remote-head-query-failed', 'Remote head could not be observed')
        if result['outcome'] != 'success': _fail('uncertain', 'remote-head-query-failed', 'Remote head could not be observed')
        values = _parse(_stdout(result, 'github-remote-head'), 'github-remote-head', expected=list); matches = []
        for value in values:
            target = value.get('object') if isinstance(value, dict) else None
            if not isinstance(value, dict) or not isinstance(value.get('ref'), str) or not isinstance(target, dict) or target.get('type') != 'commit' or not isinstance(target.get('sha'), str) or OID_RE.fullmatch(target['sha']) is None or len(target['sha']) != oid_length: _fail('corrupt', 'invalid-remote-head', 'GitHub remote-head response is malformed')
            if value['ref'] == expected_ref: matches.append(target['sha'])
        if len(matches) > 1: _fail('ambiguous', 'remote-head-ambiguous', 'GitHub returned duplicate exact remote heads')
        return matches[0] if matches else None
    def observe_remote_head(self, workflow_issue, family_run_id, repository_before, head_ref, observed_at=None, cancel_event=None):
        self._deny_frozen(workflow_issue); repository_before = _repository_document(repository_before, workflow_issue, family_run_id)
        if repository_before is None or repository_before['repository'] != self.repository: _fail('denied', 'repository-observation-target', 'Remote-head observation requires this repository')
        head_ref = _branch(head_ref, 'head-ref'); expected_sha, oid_length = repository_before['head']['commit'], 40 if repository_before['object_format'] == 'sha1' else 64
        for attempt in range(2):
            local_before = self.observe_diff(workflow_issue, family_run_id, repository_before['triage_binding'], repository_before['observed_at'], cancel_event)
            first = self._read_remote_head(head_ref, oid_length, cancel_event)
            local_after = self.observe_diff(workflow_issue, family_run_id, repository_before['triage_binding'], repository_before['observed_at'], cancel_event)
            second = self._read_remote_head(head_ref, oid_length, cancel_event)
            if local_before == repository_before and local_after == repository_before and first == second:
                if first is None: _fail('missing', 'remote-head-unpublished', 'The source branch is not published')
                if first != expected_sha: _fail('stale', 'remote-head-mismatch', 'Remote head differs from the validated local HEAD')
                return _with_digest({'format': REMOTE_HEAD_OBSERVATION_FORMAT, 'repository': self.repository, 'ref': 'refs/heads/%s' % head_ref, 'sha': expected_sha, 'repository_observation_sha256': repository_before['observation_sha256'], 'observed_at': _timestamp(observed_at or _now(), 'observed-at')}, 'observation_sha256')
            if attempt: _fail('stale', 'remote-head-moved' if first != second else 'repository-observation-moved', 'Remote head or validated repository moved during both observations')
    def observe_issue(self, issue, observed_at=None, _retrying=False):
        observed_at = observed_at or _now()
        self._deny_frozen(issue)
        before = self._repository_guard()
        raw = self._github(['api', 'repos/%s/issues/%d' % (self.repository, issue)])
        value = _parse(raw, 'github-issue')
        if value.get('number') != issue or value.get('html_url') != 'https://github.com/%s/issues/%d' % (self.repository, issue):
            _fail('stale', 'issue-identity-mismatch', 'GitHub issue identity differs from request')
        labels = sorted({item.get('name') for item in value.get('labels', []) if isinstance(item, dict)})
        if any((not isinstance(item, str) or not item for item in labels)):
            _fail('corrupt', 'invalid-issue-label', 'GitHub issue labels are invalid')
        source = {'kind': 'issue-snapshot', 'sha256': workflow_inspector.sha256(raw), 'size': len(raw)}
        document = {'format': ISSUE_SNAPSHOT_FORMAT, 'repository': self.repository, 'issue': issue, 'title': _text(value.get('title'), 'issue-title', maximum=1024), 'url': value['html_url'], 'body': _text(value.get('body') or '', 'issue-body', empty=True), 'labels': labels, 'source': source, 'captured_at': _timestamp(observed_at, 'captured-at')}
        document = _with_digest(document, 'snapshot_sha256')
        if before != self._repository_guard():
            if _retrying: _fail('stale', 'repository-observation-moved', 'Repository moved during both GitHub observations')
            return self.observe_issue(issue, observed_at=observed_at, _retrying=True)
        return document, raw
    def build_baseline(self, issue, family_run_id, issue_snapshot_binding):
        self._deny_frozen(issue)
        if not isinstance(family_run_id, str) or RUN_RE.fullmatch(family_run_id) is None:
            _fail('corrupt', 'invalid-family-run-id', 'Family run ID is invalid')
        profiles = self._bootstrap['profiles']
        limits = [{'profile': profile['id'], 'check': check['name'], **VALIDATION_LIMITS} for profile in profiles for check in profile['checks']]
        limits.sort(key=lambda item: (item['profile'], item['check']))
        document = {'format': BASELINE_FORMAT, 'repository': self.repository, 'issue': issue, 'family_run_id': family_run_id, 'issue_snapshot_binding': _reference(issue_snapshot_binding, 'issue-snapshot-binding'), 'target_base': copy.deepcopy(self._bootstrap['target_base']), 'config': {**copy.deepcopy(self._bootstrap['config']), 'bytes_base64': base64.b64encode(self._config_bytes).decode('ascii')}, 'profiles': copy.deepcopy(profiles), 'profile_check_limits': limits, 'targeted_templates': []}
        return _with_digest(document, 'baseline_sha256')
    def _repository_guard(self, cancel_event=None):
        head = self._git(['rev-parse', '--verify', 'HEAD^{commit}'], cancel_event=cancel_event).decode('ascii').strip()
        base = self._git(['rev-parse', '--verify', '%s^{commit}' % self._bootstrap['target_base']['ref']], cancel_event=cancel_event).decode('ascii').strip()
        status_data = self._git(['status', '--porcelain=v1', '-z', '--untracked-files=all'], cancel_event=cancel_event)
        replacements = self._git(['for-each-ref', '--format=%(refname)%00%(objectname)', 'refs/replace'], cancel_event=cancel_event)
        config_blob = self._git(['rev-parse', 'HEAD:.github/agent-workflow.json'], cancel_event=cancel_event).decode('ascii').strip()
        assume, skip = _index_flags(self._git(['ls-files', '-v', '-z'], cancel_event=cancel_event))
        common, grafts, alternates = _git_admin_trust(self.root)
        if base != self._bootstrap['target_base']['commit'] or config_blob != self._bootstrap['config']['blob_oid']: _fail('stale', 'repository-guard-drift', 'Repository base or config moved after bootstrap')
        return (head, base, status_data, replacements, config_blob, grafts, alternates, assume, skip)
    def observe_diff(self, issue, family_run_id, triage_binding, observed_at=None, cancel_event=None):
        self._deny_frozen(issue)
        if not isinstance(family_run_id, str) or RUN_RE.fullmatch(family_run_id) is None:
            _fail('corrupt', 'invalid-family-run-id', 'Family run ID is invalid')
        triage_binding = _reference(triage_binding, 'triage-binding')
        for attempt in range(2):
            before = self._repository_guard(cancel_event)
            document = self._observe_diff_once(issue, family_run_id, triage_binding, observed_at or _now(), before, cancel_event)
            after = self._repository_guard(cancel_event)
            if before == after:
                return document
            if attempt:
                _fail('stale', 'repository-observation-moved', 'Repository moved during both observations')
    def _observe_diff_once(self, issue, family_run_id, triage_binding, observed_at, guard, cancel_event=None):
        (head, base, status_data, replacement_data, config_blob, grafts, alternates, assume, skip) = guard
        expected = self._bootstrap['target_base']
        if base != expected['commit']:
            _fail('stale', 'target-base-moved', 'Local target-base ref moved after bootstrap')
        object_format = self._git(['rev-parse', '--show-object-format'], cancel_event=cancel_event).decode('ascii').strip()
        if object_format not in {'sha1', 'sha256'}:
            _fail('unsupported', 'git-object-format', 'Git object format is unsupported')
        oid_length = 40 if object_format == 'sha1' else 64
        _oid(head, 'head-commit', oid_length)
        head_tree = self._git(['rev-parse', '--verify', '%s^{tree}' % head], cancel_event=cancel_event).decode('ascii').strip()
        merge_base = self._git(['merge-base', base, head], cancel_event=cancel_event).decode('ascii').strip()
        count_text = self._git(['rev-list', '--count', '%s..%s' % (base, head)], cancel_event=cancel_event).decode('ascii').strip()
        try:
            count = int(count_text)
        except ValueError:
            _fail('corrupt', 'invalid-commit-count', 'Git returned an invalid commit count')
        raw_diff = self._git(['diff-tree', '-r', '--no-commit-id', '--raw', '-z', '--no-renames', base, head], cancel_event=cancel_event)
        changes = _parse_raw_diff(raw_diff, oid_length)
        workspace = _parse_status(status_data)
        workspace['assume_unchanged'], workspace['skip_worktree'] = assume, skip
        workspace['status_sha256'] = workflow_inspector.sha256(_canonical(workspace))
        replacements = []
        for raw in replacement_data.splitlines():
            if not raw:
                continue
            try:
                (name, object_id) = raw.decode('utf-8').split('\x00', 1)
            except (UnicodeError, ValueError):
                _fail('corrupt', 'invalid-replacement-ref', 'Git replacement refs are malformed')
            replacements.append({'name': name, 'object_id': object_id})
        replacements.sort(key=lambda item: (item['name'], item['object_id']))
        head_config = self._git(['show', '%s:.github/agent-workflow.json' % head], limits={'timeout_ms': 30000, 'grace_ms': 1000, 'output_limit_bytes': MAX_CONFIG_BYTES}, cancel_event=cancel_event)
        if config_blob != self._bootstrap['config']['blob_oid'] or workflow_inspector.sha256(head_config) != self._bootstrap['config']['content_sha256'] or head_config != self._config_bytes:
            _fail('stale', 'head-config-drift', 'HEAD config differs from bootstrap config')
        document = {'format': DIFF_OBSERVATION_FORMAT, 'repository': self.repository, 'issue': issue, 'family_run_id': family_run_id, 'triage_binding': triage_binding, 'observer': {'name': 'workflow-runtime', 'version': RUNTIME_VERSION, 'source_sha256': workflow_inspector.sha256(pathlib.Path(__file__).read_bytes())}, 'observed_at': _timestamp(observed_at, 'observed-at'), 'object_format': object_format, 'base': {'ref': expected['ref'], 'commit': base, 'tree': expected['tree']}, 'head': {'commit': head, 'tree': head_tree}, 'ancestry': {'base_is_ancestor': merge_base == base, 'commit_count': count}, 'changes': changes, 'workspace': workspace, 'git_trust': {'no_replace_objects': True, 'replacement_refs': replacements, 'git_replace_ref_base': None, 'git_graft_file': None, 'info_grafts_present': grafts, 'environment_redirections': [], 'alternate_object_directories': list(alternates)}, 'head_config': {'path': '.github/agent-workflow.json', 'blob_oid': config_blob, 'content_sha256': workflow_inspector.sha256(head_config), 'size': len(head_config)}, 'raw_diff_sha256': workflow_inspector.sha256(_canonical(changes))}
        return _with_digest(document, 'observation_sha256')
    def observe_pull_request(self, workflow_issue, number, source_request_binding, observed_at=None, _retrying=False):
        observed_at = observed_at or _now()
        self._deny_frozen(workflow_issue)
        _uint(number, 'pull-request-number', positive=True)
        before = self._repository_guard()
        raw = self._github(['api', 'repos/%s/pulls/%d' % (self.repository, number)])
        value = _parse(raw, 'github-pull-request')
        if value.get('number') != number:
            _fail('stale', 'pull-request-identity-mismatch', 'Pull request identity differs')
        state = 'MERGED' if value.get('merged_at') is not None else str(value.get('state', '')).upper()
        if state not in {'OPEN', 'CLOSED', 'MERGED'}:
            _fail('corrupt', 'pull-request-state', 'Pull request state is invalid')
        document = {'format': PR_OBSERVATION_FORMAT, 'repository': self.repository, 'number': number, 'url': value.get('html_url'), 'state': state, 'draft': value.get('draft'), 'base_ref': value.get('base', {}).get('ref'), 'base_sha': value.get('base', {}).get('sha'), 'head_ref': value.get('head', {}).get('ref'), 'head_sha': value.get('head', {}).get('sha'), 'title_sha256': workflow_inspector.sha256(_text(value.get('title'), 'pr-title').encode()), 'body_sha256': workflow_inspector.sha256(_text(value.get('body') or '', 'pr-body', empty=True).encode()), 'source_request_binding': _reference(source_request_binding, 'source-request-binding'), 'observed_at': _timestamp(observed_at, 'observed-at')}
        if document['url'] != 'https://github.com/%s/pull/%d' % (self.repository, number) or type(document['draft']) is not bool:
            _fail('stale', 'pull-request-metadata-mismatch', 'Pull request metadata is inconsistent')
        _oid(document['base_sha'], 'base-sha')
        _oid(document['head_sha'], 'head-sha')
        document = _with_digest(document, 'observation_sha256')
        if before != self._repository_guard():
            if _retrying: _fail('stale', 'repository-observation-moved', 'Repository moved during both GitHub observations')
            return self.observe_pull_request(workflow_issue, number, source_request_binding, observed_at=observed_at, _retrying=True)
        return document
    def observe_authorization(self, *, workflow_issue, target_kind, target_number, source_kind, source_id, challenge_binding, confirmation, source_request_binding, observed_at=None):
        self._deny_frozen(workflow_issue)
        _uint(target_number, 'authorization-target-number', positive=True)
        before = self._repository_guard()
        if target_kind not in {'issue', 'pull-request'} or source_kind not in {'issue-comment', 'pull-request-review'}:
            _fail('unsupported', 'authorization-source', 'Authorization target/source is unsupported')
        _uint(source_id, 'authorization-source-id', positive=True)
        if source_kind == 'issue-comment':
            endpoint = 'repos/%s/issues/comments/%d' % (self.repository, source_id)
        else:
            if target_kind != 'pull-request':
                _fail('corrupt', 'authorization-target', 'Reviews require a pull-request target')
            endpoint = 'repos/%s/pulls/%d/reviews/%d' % (self.repository, target_number, source_id)
        raw = self._github(['api', endpoint])
        value = _parse(raw, 'github-authorization')
        review_edited = False
        if source_kind == 'pull-request-review':
            if value.get('id') != source_id: _fail('stale', 'authorization-source-mismatch', 'REST review identity differs from request')
            node_id = _text(value.get('node_id'), 'review-node-id')
            query = 'query($id:ID!){node(id:$id){... on PullRequestReview{databaseId id url body createdAt submittedAt lastEditedAt author{login ... on User{databaseId}} authorAssociation}}}'; graph_response = _parse(self._github(['api', 'graphql', '-f', 'query=%s' % query, '-f', 'id=%s' % node_id]), 'github-review-graphql')
            if set(graph_response) != {'data'} or not isinstance(graph_response['data'], dict): _fail('corrupt', 'authorization-review-graphql-errors', 'GraphQL review response contains errors')
            graph = graph_response['data'].get('node'); _exact(graph, {'databaseId', 'id', 'url', 'body', 'createdAt', 'submittedAt', 'lastEditedAt', 'author', 'authorAssociation'}, 'authorization-review'); _exact(graph['author'], {'login', 'databaseId'}, 'authorization-review-author')
            if not isinstance(graph, dict) or graph.get('databaseId') != source_id or graph.get('id') != node_id or graph.get('url') != value.get('html_url') or graph.get('body') != value.get('body') or graph.get('submittedAt') != value.get('submitted_at') or graph.get('authorAssociation') != value.get('author_association') or graph.get('author', {}).get('login') != value.get('user', {}).get('login') or graph.get('author', {}).get('databaseId') != value.get('user', {}).get('id'): _fail('stale', 'authorization-review-mismatch', 'REST and GraphQL review observations differ')
            review_edited = graph['lastEditedAt'] is not None; value = {'id': source_id, 'node_id': node_id, 'html_url': graph.get('url'), 'created_at': graph.get('createdAt'), 'updated_at': graph.get('submittedAt'), 'body': graph.get('body'), 'user': {'id': graph.get('author', {}).get('databaseId'), 'login': graph.get('author', {}).get('login')}, 'author_association': graph.get('authorAssociation')}
        actor = value.get('user', {})
        accounts = {item['account_id']: item['login'] for item in self._config['human_approval']['allowed_accounts']}
        (account_id, login) = (actor.get('id'), actor.get('login'))
        association = value.get('author_association')
        if accounts.get(account_id) != login or association not in self._config['human_approval']['allowed_associations']:
            _fail('denied', 'authorization-actor-untrusted', 'Authorization actor is not trusted')
        body = value.get('body')
        if not isinstance(body, str) or body != confirmation:
            _fail('denied', 'authorization-confirmation-mismatch', 'Authorization body is not byte-exact')
        if review_edited or source_kind == 'issue-comment' and value.get('created_at') != value.get('updated_at'): _fail('denied', 'authorization-source-edited', 'Edited authorization sources are not accepted')
        if value.get('id') != source_id:
            _fail('stale', 'authorization-source-mismatch', 'Authorization source identity differs')
        expected_url = 'https://github.com/%s/%s/%d#%s-%d' % (self.repository, 'issues' if target_kind == 'issue' else 'pull', target_number, 'issuecomment' if source_kind == 'issue-comment' else 'pullrequestreview', source_id)
        if value.get('html_url') != expected_url or source_kind == 'issue-comment' and value.get('issue_url') != 'https://api.github.com/repos/%s/issues/%d' % (self.repository, target_number): _fail('stale', 'authorization-target-mismatch', 'Authorization source belongs to a different target')
        document = {'format': AUTHORIZATION_OBSERVATION_FORMAT, 'repository': self.repository, 'target': {'kind': target_kind, 'number': target_number}, 'source': {'kind': source_kind, 'id': source_id, 'node_id': _text(value.get('node_id'), 'source-node-id'), 'url': _text(value.get('html_url'), 'source-url', maximum=4096), 'created_at': _timestamp(value.get('created_at'), 'created-at'), 'updated_at': _timestamp(value.get('updated_at'), 'updated-at'), 'body_sha256': workflow_inspector.sha256(body.encode())}, 'actor': {'account_id': account_id, 'login': login, 'author_association': association}, 'challenge_binding': _reference(challenge_binding, 'challenge-binding'), 'confirmation': confirmation, 'source_request_binding': _reference(source_request_binding, 'source-request-binding'), 'observed_at': _timestamp(observed_at or _now(), 'observed-at')}
        document = _with_digest(document, 'observation_sha256')
        if before != self._repository_guard(): _fail('stale', 'authorization-repository-moved', 'Repository moved during authorization observation')
        return document
def _decode_path(value, label):
    try:
        path = value.decode('utf-8')
    except UnicodeError:
        _fail('corrupt', 'invalid-%s' % label, '%s is not UTF-8' % label)
    if not path or path.startswith('/') or '\\' in path or any((part in {'', '.', '..'} for part in path.split('/'))):
        _fail('denied', 'invalid-%s' % label, '%s is not repository-relative' % label)
    return path
def _parse_raw_diff(data, oid_length):
    parts, changes, index = data.split(b'\x00'), [], 0
    while index < len(parts) and parts[index]:
        try: metadata = parts[index].decode('ascii').split()
        except UnicodeError: _fail('corrupt', 'invalid-raw-diff', 'Git raw diff metadata is invalid')
        if len(metadata) != 5 or not metadata[0].startswith(':'): _fail('corrupt', 'invalid-raw-diff', 'Git raw diff shape is invalid')
        (old_mode, new_mode) = (metadata[0][1:], metadata[1])
        (old_oid, new_oid, change_status) = (metadata[2], metadata[3], metadata[4])
        if change_status not in {'A', 'D', 'M', 'T'} or index + 1 >= len(parts): _fail('unsupported', 'unsupported-raw-diff', 'Git diff status is unsupported')
        path = _decode_path(parts[index + 1], 'diff-path')
        _oid(old_oid, 'old-object', oid_length); _oid(new_oid, 'new-object', oid_length)
        changes.append({'status': change_status, 'old_mode': old_mode, 'new_mode': new_mode, 'old_oid': old_oid, 'new_oid': new_oid, 'old_path': None if change_status == 'A' else path, 'new_path': None if change_status == 'D' else path})
        index += 2
    changes.sort(key=lambda item: (item['old_path'] or '', item['new_path'] or '', item['status'], item['old_oid'], item['new_oid']))
    return changes
def _parse_status(data):
    workspace = {'staged': [], 'unstaged': [], 'untracked_non_ignored': [], 'assume_unchanged': [], 'skip_worktree': []}
    parts, index = data.split(b'\x00'), 0
    while index < len(parts) and parts[index]:
        row = parts[index]
        if len(row) < 4: _fail('corrupt', 'invalid-workspace-status', 'Git status output is malformed')
        status, path = row[:2].decode('ascii'), _decode_path(row[3:], 'workspace-path')
        if status == '??': workspace['untracked_non_ignored'].append(path)
        elif status == '!!': pass
        else:
            original = None
            if status[0] in {'R', 'C'} or status[1] in {'R', 'C'}:
                index += 1
                if index >= len(parts): _fail('corrupt', 'invalid-workspace-rename', 'Git rename record is incomplete')
                original = _decode_path(parts[index], 'workspace-original-path')
            if status[0] not in {' ', '?', '!'}: workspace['staged'].append({'code': status[0], 'path': path, 'original_path': original})
            if status[1] not in {' ', '?', '!'}: workspace['unstaged'].append({'code': status[1], 'path': path, 'original_path': original})
        index += 1
    for field in ('staged', 'unstaged'):
        workspace[field].sort(key=lambda item: (item['path'], item['original_path'] or '', item['code']))
    workspace['untracked_non_ignored'].sort()
    return workspace
def _profiles(config):
    profiles = config.get('validation_profiles')
    expected = ('backend', 'frontend', 'full-stack', 'workflow-tooling')
    if not isinstance(profiles, dict) or set(profiles) != set(expected): _fail('corrupt', 'invalid-validation-profiles', 'Validation profiles are incomplete')
    result = []
    for profile_id in sorted(expected):
        profile = profiles[profile_id]
        if not isinstance(profile, dict) or set(profile) != {'checks', 'test_paths'}: _fail('corrupt', 'invalid-validation-profile', 'Validation profile schema is invalid')
        checks = []
        names = set()
        for raw in profile['checks']:
            if not isinstance(raw, dict) or set(raw) not in ({'name', 'command'}, {'name', 'command', 'cwd'}): _fail('corrupt', 'invalid-validation-check', 'Validation check schema is invalid')
            name = _slug(raw['name'], 'validation-check-name')
            if name in names: _fail('ambiguous', 'duplicate-validation-check', 'Validation check is duplicated')
            names.add(name)
            checks.append({'name': name, 'command': _command(raw['command'], 'validation-command'), 'cwd': raw.get('cwd', '.')})
        paths = profile['test_paths']
        if not isinstance(paths, list) or not paths or any((not isinstance(item, str) or not item for item in paths)): _fail('corrupt', 'invalid-validation-test-paths', 'Validation test paths are invalid')
        result.append({'id': profile_id, 'checks': checks, 'test_paths': list(paths)})
    return result
def _validation_executables(root, config, profiles):
    rows = []
    for profile in profiles:
        for check in profile['checks']:
            name, cwd = check['command'][0], _path(check['cwd'], root, 'validation-cwd')
            if name.startswith('./'): candidate = _path(name[2:], cwd, 'validation-executable', allow_dot=False)
            else:
                if name not in {'make', 'npm', 'npx'}: _fail('denied', 'validation-executable-prohibited', 'Validation executable is not allowlisted')
                candidate = next((pathlib.Path(directory, name).resolve() for directory in config['validation_path'] if pathlib.Path(directory, name).is_file() and os.access(pathlib.Path(directory, name), os.X_OK)), None)
            record = _executable(candidate, 'validation-executable') if candidate is not None and pathlib.Path(candidate).is_file() else {'path': None, 'sha256': None}
            rows.append({'profile': profile['id'], 'entry': check['name'], **record})
    return sorted(rows, key=lambda row: (row['profile'], row['entry']))
def bootstrap(root, repository, git_executable, gh_executable, github_token):
    try: root = pathlib.Path(root).resolve(strict=True)
    except (OSError, RuntimeError, ValueError) as error: _fail('missing', 'repository-root-unavailable', 'Repository root is unavailable: %s' % error)
    if not root.is_dir() or not isinstance(repository, str) or REPOSITORY_RE.fullmatch(repository) is None: _fail('corrupt', 'invalid-bootstrap-target', 'Bootstrap root or repository is invalid')
    _text(github_token, 'github-token', maximum=16 * 1024)
    git = _executable(git_executable, 'git')
    gh = _executable(gh_executable, 'github')
    paths = list(dict.fromkeys([str(pathlib.Path(git['path']).parent), str(pathlib.Path(gh['path']).parent)]))
    def call(executable, arguments, environment, label):
        return _require_success(_run([executable['path']] + arguments, limits=BOOTSTRAP_LIMITS, cwd=root, environment=environment), label)
    git_environment = _git_env(paths)
    def git_call(arguments, label='git'):
        return call(git, ['-c', 'core.fsmonitor=false'] + arguments, git_environment, label)
    def snapshot():
        repository_data = _parse(call(gh, ['api', 'repos/%s' % repository], _github_env(paths, github_token), 'github-repository'), 'github-repository')
        branch = repository_data.get('default_branch')
        if not isinstance(branch, str) or TARGET_RE.fullmatch(branch) is None or ('..' in branch.split('/')): _fail('corrupt', 'invalid-default-branch', 'GitHub default branch is invalid')
        remote_tip = _oid(_parse(call(gh, ['api', 'repos/%s/commits/%s' % (repository, branch)], _github_env(paths, github_token), 'github-tip'), 'github-tip').get('sha'), 'remote-tip')
        ref = 'refs/remotes/origin/%s' % branch
        common, grafts, alternates = _git_admin_trust(root)
        facts = {'branch': branch, 'remote_tip': remote_tip, 'head': git_call(['rev-parse', '--verify', 'HEAD^{commit}']).decode('ascii').strip(), 'ref': ref, 'tracking': git_call(['rev-parse', '--verify', '%s^{commit}' % ref]).decode('ascii').strip(), 'tree': git_call(['rev-parse', '--verify', '%s^{tree}' % ref]).decode('ascii').strip(), 'status': git_call(['status', '--porcelain=v1', '-z', '--untracked-files=all']), 'flags': _index_flags(git_call(['ls-files', '-v', '-z'])), 'replacements': git_call(['for-each-ref', '--format=%(refname)%00%(objectname)', 'refs/replace']), 'grafts': grafts, 'alternates': alternates}
        facts['blob_oid'] = git_call(['rev-parse', '%s:.github/agent-workflow.json' % remote_tip]).decode('ascii').strip()
        facts['config_bytes'] = git_call(['show', '%s:.github/agent-workflow.json' % remote_tip], 'git-config')
        facts['git'] = _executable(git['path'], 'git'); facts['github'] = _executable(gh['path'], 'github')
        facts['config_root'] = _parse(facts['config_bytes'], 'bootstrap-config', MAX_CONFIG_BYTES); facts['config'] = _validate_config(facts['config_root'], root); facts['profiles'] = _profiles(facts['config_root']); facts['validation_executables'] = _validation_executables(root, facts['config'], facts['profiles'])
        return facts
    selected = None
    for attempt in range(2):
        before, after = snapshot(), snapshot()
        if before == after: selected = after; break
        if attempt: _fail('stale', 'bootstrap-observation-moved', 'Bootstrap facts moved during both observations')
    branch, remote_tip, head, ref, tracking, tree = (selected[key] for key in ('branch', 'remote_tip', 'head', 'ref', 'tracking', 'tree'))
    remote_tip = _oid(remote_tip, 'remote-tip')
    for value, label in ((head, 'initial-head'), (tracking, 'tracking-tip'), (tree, 'target-tree')): _oid(value, label, len(remote_tip))
    if selected['grafts'] or selected['alternates']: _fail('denied', 'bootstrap-git-redirection', 'Bootstrap rejects grafts and alternate object directories')
    if selected['status'] or any(selected['flags']) or selected['replacements'].strip(): _fail('stale', 'bootstrap-worktree-dirty', 'Bootstrap requires a clean initial worktree')
    if head != remote_tip or tracking != remote_tip: _fail('stale', 'bootstrap-head-tip-mismatch', 'Initial HEAD and local remote-tracking tip must both equal the observed remote tip')
    blob_oid, config_bytes = selected['blob_oid'], selected['config_bytes']
    if not config_bytes or len(config_bytes) > MAX_CONFIG_BYTES:
        _fail('unsupported', 'bootstrap-config-size', 'Base config is empty or exceeds 1 MiB')
    _oid(blob_oid, 'config-blob', len(remote_tip))
    if _git_blob_oid(config_bytes, len(remote_tip)) != blob_oid:
        _fail('corrupt', 'bootstrap-config-blob-mismatch', 'Config bytes differ from Git blob identity')
    config_root = selected['config_root']
    if config_root.get('target_base') != branch:
        _fail('stale', 'bootstrap-target-base-mismatch', 'Base config target differs from GitHub default branch')
    config, profiles, git, gh = selected['config'], selected['profiles'], selected['git'], selected['github']
    document = {'format': BOOTSTRAP_FORMAT, 'repository': repository, 'initial_head': head, 'remote_tip': remote_tip, 'target_base': {'name': branch, 'ref': ref, 'commit': tracking, 'tree': tree}, 'config': {'path': '.github/agent-workflow.json', 'blob_oid': blob_oid, 'content_sha256': workflow_inspector.sha256(config_bytes), 'size': len(config_bytes)}, 'executables': {'git': git, 'github': gh}, 'validation_executables': selected['validation_executables'], 'profiles': profiles, 'mode': config['mode']}
    document = _with_digest(document, 'bootstrap_sha256')
    return Runtime(root, repository, git, gh, github_token, config, config_bytes, document)
class RuntimeArgumentParser(argparse.ArgumentParser):
    def error(self, message): raise RuntimeFailure('corrupt', 'invalid-arguments', message)
def build_parser():
    parser = RuntimeArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest='command', required=True, parser_class=RuntimeArgumentParser)
    for name in ('bootstrap', 'execute'):
        command = subparsers.add_parser(name)
        command.add_argument('--root', required=True)
        command.add_argument('--repository', required=True)
        command.add_argument('--git-executable', required=True)
        command.add_argument('--gh-executable', required=True)
        command.add_argument('--github-token-stdin', action='store_true', required=True)
        if name == 'execute':
            command.add_argument('--request', required=True)
            command.add_argument('--request-binding', required=True)
    return parser
def _load_file(path, label):
    try:
        data = pathlib.Path(path).read_bytes()
    except OSError as error:
        _fail('missing', '%s-unreadable' % label, '%s cannot be read: %s' % (label, error))
    return _parse(data, label)
def main(argv=None):
    try:
        args = build_parser().parse_args(argv)
        token = sys.stdin.readline().rstrip('\n')
        adapter = bootstrap(args.root, args.repository, args.git_executable, args.gh_executable, token)
        if args.command == 'bootstrap':
            document = adapter.bootstrap_document()
        else:
            document = adapter.execute(_load_file(args.request, 'request'), _load_file(args.request_binding, 'request-binding'))
        sys.stdout.buffer.write(workflow_inspector.canonical_document(document))
        return 0
    except RuntimeFailure as failure:
        sys.stdout.buffer.write(workflow_inspector.canonical_document(failure.document()))
        return OUTCOME_EXIT_CODES[failure.status]
if __name__ == '__main__':
    raise SystemExit(main())
