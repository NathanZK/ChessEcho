"""Pure reconstruction contract and validation for the workflow runtime.

Defines the bootstrap/baseline/triage/pin/request document formats and the
validation and comparison logic that decides whether previously observed
runtime facts still match a pinned reconstruction. Every function here is a
pure function of its arguments: no Git, GitHub, or other process execution,
no filesystem or network access, and no authority or evidence mutation. The
only external dependency is workflow_inspector's in-memory canonical JSON
and hashing helpers, which perform no I/O themselves.
"""
import base64, binascii, copy, fnmatch, hashlib, json, pathlib, re
try:
    from . import workflow_inspector
except ImportError:  # pragma: no cover - package execution
    import workflow_inspector
FAILURE_FORMAT = 'chess-echo-workflow-runtime-failure-v1'
BOOTSTRAP_FORMAT = 'chess-echo-runtime-bootstrap-v1'
RECONSTRUCTION_PIN_FORMAT = 'chess-echo-runtime-reconstruction-pin-v1'
RECONSTRUCTION_REQUEST_FORMAT = 'chess-echo-runtime-reconstruction-request-v1'
BASELINE_FORMAT = 'chess-echo-work-type-baseline-v1'
TRIAGE_FORMAT = 'chess-echo-work-type-triage-result-v1'
DIFF_OBSERVATION_FORMAT = 'chess-echo-work-type-diff-observation-v1'
(MAX_CONFIG_BYTES, MAX_DOCUMENT_BYTES) = (1024 * 1024, 2 * 1024 * 1024)
VALIDATION_LIMITS = {'timeout_ms': 3600000, 'grace_ms': 2000, 'output_limit_bytes': 512 * 1024}
SHELLS = frozenset({'ash', 'bash', 'csh', 'dash', 'fish', 'ksh', 'powershell', 'pwsh', 'sh', 'tcsh', 'zsh'})
WRAPPERS = frozenset({'busybox', 'command', 'env', 'find', 'nohup', 'xargs'})
(SLUG_RE, REPOSITORY_RE, OID_RE, SHA_RE, RUN_RE, RFC3339_RE) = tuple(re.compile(pattern) for pattern in ('[A-Za-z0-9][A-Za-z0-9._:-]{0,127}', '[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', '(?:[0-9a-f]{40}|[0-9a-f]{64})', '[0-9a-f]{64}', '[0-9a-f]{32}', '\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2})'))
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
def _branch(value, label):
    _text(value, label, maximum=255); parts = value.split('/')
    if value == '@' or value.startswith('-') or value.endswith('.') or '..' in value or '@{' in value or any((not part or part.startswith('.') or part.endswith('.lock') for part in parts)) or any((ord(char) < 32 or ord(char) == 127 or char in ' ~^:?*[\\' for char in value)):
        _fail('denied', 'invalid-%s' % label, '%s is not a safe branch name' % label)
    return value
def _timestamp(value, label):
    if not isinstance(value, str) or RFC3339_RE.fullmatch(value) is None: _fail('corrupt', 'invalid-%s' % label, '%s is not RFC 3339' % label)
    return value
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
def _command(value, label):
    if not isinstance(value, list) or not 1 <= len(value) <= 128: _fail('corrupt', 'invalid-%s' % label, '%s must be a bounded argv' % label)
    for part in value: _text(part, '%s-part' % label, maximum=4096)
    executable = pathlib.PurePosixPath(value[0]).name.lower()
    if executable in SHELLS or executable in WRAPPERS: _fail('denied', '%s-dispatch-prohibited' % label, 'Shells and dispatch wrappers are prohibited')
    return list(value)
def _git_blob_oid(data, length):
    payload = b'blob ' + str(len(data)).encode('ascii') + b'\x00' + data
    return (hashlib.sha1 if length == 40 else hashlib.sha256)(payload).hexdigest()
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
def _verify_document_digest(value, field, label):
    unsigned = copy.deepcopy(value)
    digest = unsigned.pop(field, None)
    _sha(digest, '%s-sha256' % label)
    if workflow_inspector.sha256(_canonical(unsigned)) != digest:
        _fail('corrupt', '%s-digest-mismatch' % label, '%s digest is stale' % label.capitalize())
def _validate_executable_record(value, label):
    _exact(value, {'path', 'sha256'}, label)
    _text(value['path'], '%s-path' % label, maximum=4096)
    if not pathlib.Path(value['path']).is_absolute():
        _fail('denied', '%s-path-not-absolute' % label, '%s path must be absolute' % label.capitalize())
    _sha(value['sha256'], '%s-sha256' % label)
    return copy.deepcopy(value)
def validate_bootstrap_document(value):
    keys = {'format', 'repository', 'initial_head', 'remote_tip', 'target_base', 'config', 'executables', 'validation_executables', 'profiles', 'mode', 'runtime', 'bootstrap_sha256'}
    _exact(value, keys, 'runtime-bootstrap')
    if value['format'] != BOOTSTRAP_FORMAT:
        _fail('unsupported', 'runtime-bootstrap-format', 'Runtime bootstrap format is unsupported')
    if not isinstance(value['repository'], str) or REPOSITORY_RE.fullmatch(value['repository']) is None:
        _fail('corrupt', 'runtime-bootstrap-repository', 'Runtime bootstrap repository is invalid')
    oid_length = len(value['remote_tip']) if isinstance(value['remote_tip'], str) else 0
    if oid_length not in {40, 64}:
        _fail('corrupt', 'runtime-bootstrap-object-format', 'Runtime bootstrap object format is invalid')
    for field in ('initial_head', 'remote_tip'):
        _oid(value[field], 'runtime-bootstrap-%s' % field, oid_length)
    _exact(value['target_base'], {'name', 'ref', 'commit', 'tree'}, 'runtime-bootstrap-target-base')
    branch = _branch(value['target_base']['name'], 'runtime-bootstrap-target-base')
    if value['target_base']['ref'] != 'refs/remotes/origin/%s' % branch:
        _fail('stale', 'runtime-bootstrap-base-ref', 'Runtime bootstrap target-base ref is inconsistent')
    for field in ('commit', 'tree'):
        _oid(value['target_base'][field], 'runtime-bootstrap-base-%s' % field, oid_length)
    if value['initial_head'] != value['remote_tip'] or value['target_base']['commit'] != value['remote_tip']:
        _fail('stale', 'runtime-bootstrap-tip-mismatch', 'Runtime bootstrap does not select one exact base tip')
    _exact(value['config'], {'path', 'blob_oid', 'content_sha256', 'size'}, 'runtime-bootstrap-config')
    if value['config']['path'] != '.github/agent-workflow.json':
        _fail('stale', 'runtime-bootstrap-config-path', 'Runtime bootstrap config path is invalid')
    _oid(value['config']['blob_oid'], 'runtime-bootstrap-config-blob', oid_length)
    _sha(value['config']['content_sha256'], 'runtime-bootstrap-config-content')
    _uint(value['config']['size'], 'runtime-bootstrap-config-size', MAX_CONFIG_BYTES, positive=True)
    _exact(value['executables'], {'git', 'github'}, 'runtime-bootstrap-executables')
    _validate_executable_record(value['executables']['git'], 'runtime-bootstrap-git')
    _validate_executable_record(value['executables']['github'], 'runtime-bootstrap-github')
    if not isinstance(value['validation_executables'], list):
        _fail('corrupt', 'runtime-bootstrap-validation-executables', 'Runtime bootstrap validation executables are invalid')
    validation_keys = []
    for row in value['validation_executables']:
        _exact(row, {'profile', 'entry', 'path', 'sha256'}, 'runtime-bootstrap-validation-executable')
        key = (_slug(row['profile'], 'runtime-bootstrap-profile'), _slug(row['entry'], 'runtime-bootstrap-entry'))
        validation_keys.append(key)
        if (row['path'] is None) != (row['sha256'] is None):
            _fail('corrupt', 'runtime-bootstrap-validation-executable', 'Runtime bootstrap validation executable is incomplete')
        if row['path'] is not None:
            _validate_executable_record({'path': row['path'], 'sha256': row['sha256']}, 'runtime-bootstrap-validation')
    if validation_keys != sorted(set(validation_keys)):
        _fail('ambiguous', 'runtime-bootstrap-validation-executables', 'Runtime bootstrap validation executables are duplicated or unordered')
    if not isinstance(value['profiles'], list):
        _fail('corrupt', 'runtime-bootstrap-profiles', 'Runtime bootstrap profiles are invalid')
    if value['mode'] not in {'inactive', 'active'}:
        _fail('unsupported', 'runtime-bootstrap-mode', 'Runtime bootstrap mode is unsupported')
    _exact(value['runtime'], {'name', 'version', 'source_sha256'}, 'runtime-bootstrap-runtime')
    if value['runtime']['name'] != 'workflow-runtime':
        _fail('stale', 'runtime-bootstrap-runtime-name', 'Runtime bootstrap implementation name is invalid')
    _slug(value['runtime']['version'], 'runtime-bootstrap-runtime-version')
    _sha(value['runtime']['source_sha256'], 'runtime-bootstrap-runtime-source')
    _verify_document_digest(value, 'bootstrap_sha256', 'runtime-bootstrap')
    return copy.deepcopy(value)
def _validate_runtime_baseline(value, pin):
    keys = {'format', 'repository', 'issue', 'family_run_id', 'issue_snapshot_binding', 'target_base', 'config', 'profiles', 'profile_check_limits', 'targeted_templates', 'baseline_sha256'}
    _exact(value, keys, 'runtime-baseline')
    if value['format'] != BASELINE_FORMAT:
        _fail('unsupported', 'runtime-baseline-format', 'Runtime baseline format is unsupported')
    if value['repository'] != pin['repository'] or value['issue'] != pin['issue'] or value['family_run_id'] != pin['family_run_id']:
        _fail('stale', 'runtime-baseline-identity', 'Runtime baseline identity differs from the selected pin')
    _reference(value['issue_snapshot_binding'], 'runtime-baseline-issue-snapshot')
    bootstrap = pin['bootstrap']
    if value['target_base'] != bootstrap['target_base'] or value['profiles'] != bootstrap['profiles']:
        _fail('stale', 'runtime-baseline-bootstrap-mismatch', 'Runtime baseline differs from bootstrap facts')
    _exact(value['config'], {'path', 'blob_oid', 'content_sha256', 'size', 'bytes_base64'}, 'runtime-baseline-config')
    try:
        config_bytes = base64.b64decode(value['config']['bytes_base64'], validate=True)
    except (TypeError, ValueError, binascii.Error):
        _fail('corrupt', 'runtime-baseline-config-base64', 'Runtime baseline config is not strict base64')
    expected_config = {key: value['config'][key] for key in ('path', 'blob_oid', 'content_sha256', 'size')}
    if expected_config != bootstrap['config'] or len(config_bytes) != value['config']['size'] or workflow_inspector.sha256(config_bytes) != value['config']['content_sha256'] or _git_blob_oid(config_bytes, len(bootstrap['remote_tip'])) != value['config']['blob_oid']:
        _fail('corrupt', 'runtime-baseline-config-mismatch', 'Runtime baseline config differs from bootstrap bytes')
    config_root = _parse(config_bytes, 'runtime-baseline-config', MAX_CONFIG_BYTES)
    if config_root.get('target_base') != bootstrap['target_base']['name'] or _profiles(config_root) != bootstrap['profiles']:
        _fail('stale', 'runtime-baseline-config-projection', 'Runtime baseline config projection differs from bootstrap')
    expected_limits = [{'profile': profile['id'], 'check': check['name'], **VALIDATION_LIMITS} for profile in bootstrap['profiles'] for check in profile['checks']]
    expected_limits.sort(key=lambda item: (item['profile'], item['check']))
    if value['profile_check_limits'] != expected_limits or not isinstance(value['targeted_templates'], list):
        _fail('stale', 'runtime-baseline-limits', 'Runtime baseline limits differ from bootstrap configuration')
    _verify_document_digest(value, 'baseline_sha256', 'runtime-baseline')
    return copy.deepcopy(value), config_bytes
def _validate_runtime_triage(value, pin, baseline):
    keys = {'format', 'outcome', 'issue', 'family_run_id', 'issue_snapshot_binding', 'baseline_binding', 'classification', 'route', 'activation', 'request_sha256', 'result_sha256'}
    _exact(value, keys, 'runtime-triage')
    if value['format'] != TRIAGE_FORMAT or value['outcome'] != {'status': 'resolved', 'code': 'classified'}:
        _fail('unsupported', 'runtime-triage-format', 'Runtime triage result is unsupported')
    if value['issue'] != pin['issue'] or value['family_run_id'] != pin['family_run_id'] or value['issue_snapshot_binding'] != baseline['issue_snapshot_binding'] or value['baseline_binding'] != pin['baseline_binding']:
        _fail('stale', 'runtime-triage-identity', 'Runtime triage differs from selected baseline identity')
    if not isinstance(value['classification'], dict) or value['classification'].get('work_type') != 'implementation' or not isinstance(value['route'], dict) or value['route'].get('work_type') != 'implementation':
        _fail('denied', 'runtime-triage-route', 'Runtime reconstruction requires the selected implementation route')
    _sha(value['request_sha256'], 'runtime-triage-request')
    _verify_document_digest(value, 'result_sha256', 'runtime-triage')
    return copy.deepcopy(value)
def _validate_reconstruction_pin(value):
    _exact(value, {'format', 'repository', 'issue', 'family_run_id', 'bootstrap', 'baseline_binding', 'triage_binding', 'pin_sha256'}, 'runtime-reconstruction-pin')
    if value['format'] != RECONSTRUCTION_PIN_FORMAT:
        _fail('unsupported', 'runtime-reconstruction-pin-format', 'Runtime reconstruction pin format is unsupported')
    if not isinstance(value['repository'], str) or REPOSITORY_RE.fullmatch(value['repository']) is None:
        _fail('corrupt', 'runtime-reconstruction-repository', 'Runtime reconstruction repository is invalid')
    _uint(value['issue'], 'runtime-reconstruction-issue', positive=True)
    if not isinstance(value['family_run_id'], str) or RUN_RE.fullmatch(value['family_run_id']) is None:
        _fail('corrupt', 'runtime-reconstruction-family', 'Runtime reconstruction family is invalid')
    bootstrap = validate_bootstrap_document(value['bootstrap'])
    if bootstrap['repository'] != value['repository']:
        _fail('stale', 'runtime-reconstruction-repository', 'Runtime reconstruction repository differs from bootstrap')
    _reference(value['baseline_binding'], 'runtime-reconstruction-baseline')
    _reference(value['triage_binding'], 'runtime-reconstruction-triage')
    _verify_document_digest(value, 'pin_sha256', 'runtime-reconstruction-pin')
    return copy.deepcopy(value)
def build_reconstruction_request(*, pin_binding, pin_document, baseline_binding, baseline_document, triage_binding, triage_document, authority_binding, repository_mode, repository_observation):
    pin = _validate_reconstruction_pin(pin_document)
    pin_binding = _reference(pin_binding, 'runtime-pin-binding')
    baseline_binding = _reference(baseline_binding, 'runtime-baseline-binding')
    triage_binding = _reference(triage_binding, 'runtime-triage-binding')
    authority_binding = _reference(authority_binding, 'runtime-authority-binding')
    if baseline_binding != pin['baseline_binding'] or triage_binding != pin['triage_binding']:
        _fail('stale', 'runtime-reconstruction-binding', 'Runtime reconstruction evidence binding differs from its pin')
    baseline, _config_bytes = _validate_runtime_baseline(baseline_document, pin)
    triage = _validate_runtime_triage(triage_document, pin, baseline)
    if repository_mode not in {'exact', 'clean-base', 'trusted-current'}:
        _fail('corrupt', 'runtime-reconstruction-repository-mode', 'Runtime reconstruction repository mode is invalid')
    if repository_mode == 'exact':
        repository_observation = _repository_document(repository_observation, pin['issue'], pin['family_run_id'])
        if repository_observation is None or repository_observation['repository'] != pin['repository'] or repository_observation['triage_binding'] != triage_binding:
            _fail('stale', 'runtime-reconstruction-repository-evidence', 'Phase-selected repository evidence is stale')
    elif repository_observation is not None:
        _fail('corrupt', 'runtime-reconstruction-repository-evidence', 'Repository evidence is only valid in exact mode')
    document = {'format': RECONSTRUCTION_REQUEST_FORMAT, 'pin_binding': pin_binding, 'pin': pin, 'baseline': {'binding': baseline_binding, 'document': baseline}, 'triage': {'binding': triage_binding, 'document': triage}, 'authority_binding': authority_binding, 'repository_expectation': {'mode': repository_mode, 'observation': copy.deepcopy(repository_observation)}}
    return _with_digest(document, 'request_sha256')
def _clean_runtime_repository(value):
    workspace, trust = value['workspace'], value['git_trust']
    return not any(workspace[field] for field in ('staged', 'unstaged', 'untracked_non_ignored', 'assume_unchanged', 'skip_worktree')) and trust == {'no_replace_objects': True, 'replacement_refs': [], 'git_replace_ref_base': None, 'git_graft_file': None, 'info_grafts_present': False, 'environment_redirections': [], 'alternate_object_directories': []}
def repository_key(observation):
    """Return the subset of a diff observation that identifies its repository fact for equality comparison."""
    fields = ('repository', 'issue', 'family_run_id', 'triage_binding', 'object_format', 'base', 'head', 'ancestry', 'changes', 'workspace', 'git_trust', 'head_config', 'raw_diff_sha256')
    return {field: observation[field] for field in fields}
def same_repository(before, after):
    """Compare two diff observations for exact repository-fact identity."""
    return after is not None and repository_key(before) == repository_key(after)
def test_scope(observation, scope):
    """Return whether every changed path in a diff observation falls within the given glob scope."""
    paths = [change[key] for change in observation['changes'] for key in ('old_path', 'new_path') if change[key] is not None]
    patterns = [item for item in scope if isinstance(item, str)]
    patterns += [item.replace('**/', '') for item in patterns]
    return bool(paths) and all(any(fnmatch.fnmatchcase(path, item) for item in patterns) for path in paths)
def test_changes(observation, patterns):
    """Return the changed-path rows in a diff observation that match any of the given glob patterns."""
    normalized = list(patterns) + [item.replace('**/', '') for item in patterns]
    return [change for change in observation['changes'] if any(path is not None and any(fnmatch.fnmatchcase(path, pattern) for pattern in normalized) for path in (change['old_path'], change['new_path']))]
