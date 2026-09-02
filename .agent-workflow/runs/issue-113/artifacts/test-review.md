# Issue #113 — Test review (TEST_REVIEW gate, refreshed after #111 reconciliation)

**Reviewer:** `chess-echo-reviewer` (independent). Did not edit any production code or test.
**Authoritative state at review:** `TEST_REVIEW` (scope `full-stack`, target base `main`).
**Reconciled baseline:** `origin/main == HEAD == 09fac2b65fbe7d0e936cdb385ef6fa68094656c0`
("Implement practical weakness evidence"), containing upstream **#85** (live import progress)
and **#111** (practical weakness evidence) as first-class baseline content.

## Verdict

`READY_FOR_HUMAN_APPROVAL` — technical reviewer readiness only. This is **not** human
approval; no approval command was or will be run by the reviewer.

## 1. Integrity: hashes and fingerprints (independently recomputed)

All values below were recomputed by the reviewer with the workflow's own
`agent_workflow.files_fingerprint` / `non_test_fingerprint` over the live tree.

| Artifact / fingerprint | Expected | Recomputed | Match |
|---|---|---|---|
| Test report SHA-256 (`test-report.md`) | `e4d58793ba0f5245538229985615c6ac326963d458d1e3c8de15aafcad2ef51a` | identical | ✅ |
| Approved plan SHA-256 (`plan.md`) | `54493c47d5906dd2517a7866f1fbf45ca355e3f2a15f1718443866cb30fcd373` | identical | ✅ |
| Plan review SHA-256 (`plan-review.md`) | `045f02d3e8fbf97f17920d05ee274801e077b199e9c1537103d6c57c1288514b` | identical | ✅ |
| Approved-plan non-test fingerprint | `106478fb0a2154bd67a7353b366b934f7950507e021456def79d72c523cef88a` | identical | ✅ |
| Combined test fingerprint (`test_paths`) | `11aaa2a68ef766599b835a8a50ecb7ff767e23ab3308ca940c29681a02886232` | identical | ✅ |

- The current **non-test fingerprint recomputes to the approved-plan value**, so
  `require_test_only_phase_unchanged` holds: no production, config, docs, dependency, or
  workflow file changed since plan approval. The reconciled non-test surface (including the
  AppConfig change) is byte-identical to what the human approved.
- The **combined test fingerprint** matches the reported value and is **stable before and
  after** running the full backend and frontend suites (build outputs are git-ignored and
  excluded from the fingerprint).
- CLI-recorded artifact SHAs in `state.json` match the on-disk files (`plan`, `plan_review`,
  `test_report`).

## 2. Baseline coexistence (#85 + #111 + #113), no test drift/weakening

- The `full-stack` `test_paths` globs enumerate **73** files: the tracked upstream #85/#111
  and pre-existing baseline tests + `vitest.config.mts` + `vitest.setup.ts` +
  `application-test.yml`, **plus exactly the 14 untracked #113 test files**.
- `git status` shows **zero modified or staged files** under any `test_paths` glob — the only
  non-tracked test entries are the 14 untracked #113 files. No tracked #85/#111/baseline test
  was edited, weakened, deleted, or re-scoped. This is confirmed after running the suites.
- A full scan found **no git conflict markers** in any test file. The 14 #113 tests are the
  same approved surface; the #111 reconciliation exposed no test defect and required no test
  change, consistent with the test report.
- **#111 coexistence directly proven green:** the full backend suite (43 classes / 277 tests)
  runs all #111 practical-weakness classes together with the #113 classes with zero failures,
  so the reconciliation introduces **no hidden mock/context/profile conflict**.

## 3. AppConfig reconciliation and context/profile hygiene

- `AppConfig.kt` (only non-test reconciliation surface of note) preserves #111's
  `PracticalEvidenceProperties` in `@EnableConfigurationProperties` and additively registers
  `SessionCookieProperties`; CORS moves to explicit `Content-Type`/`X-XSRF-TOKEN` headers with
  `allowCredentials(true)`. This is coherent with #111 and required for AC4/AC5.
- No `@SpringBootTest`/mock/profile collision: #111 `PracticalWeaknessControllerIntegrationTest`
  uses `@ActiveProfiles("test")` with its own `@MockBean` set; #113 `DevSessionAbsenceTest`
  uses `@ActiveProfiles("test")` (dev bean absent → 404); #113 `SessionCookieAuthIntegrationTest`
  uses `@ActiveProfiles("test","dev")` + `dev-mode.enabled=true`. Distinct context-cache keys;
  full-suite green confirms no cross-context contamination.
- **No `ApplicationContextRunner` misuse.** The only `ApplicationContextRunner` usage in the
  tree is in the #111 baseline `WeaknessPriorityPolicyTest` (appropriate). #113
  `DevModeStartupGuardTest` deliberately avoids it and drives the real `ApplicationRunner.run()`
  directly, so the fail-closed guard is asserted non-vacuously (the runner-never-fires trap is
  avoided); the real-runner boot path is additionally exercised by the `dev`-profile
  integration test.

## 4. Coherence / non-vacuousness and per-AC / per-Decision coverage

All 14 tests are non-vacuous (argument captors, `never()`/`times(2)` verifications, deferred
promises, fail-closed negatives, real servlet/JPA/HTTP surfaces). An incorrect implementation
would fail them.

| AC / Decision | Covering tests | Assessment |
|---|---|---|
| AC1 immutable internal identity, email-independent (D1) | `AuthIdentityRepositoryTest`, `IdentitySessionServiceTest`, `IdentitySessionServicePersistenceTest` | Strong |
| AC2 `(issuer, subject)` resolution; equal subjects distinct; email change never merges (D1) | `AuthIdentityRepositoryTest` (repeat lookup, distinct-issuer, unique constraint via `saveAndFlush`, email-snapshot no-merge with `count()==1`) | Strong |
| AC3 opaque sessions create/expiry/revoke/rotate/logout; secret never stored raw (D2) | `AuthSessionRepositoryTest`, `IdentitySessionServiceTest` (hash-only, slide, reject revoked/idle/absolute, rotate revoke-old+issue-new, idempotent revoke), `IdentitySessionServicePersistenceTest` (exactly one live row after rotate) | Strong |
| AC4 cookie HttpOnly/Secure/SameSite (D2) | `SessionCookieAuthIntegrationTest` (HttpOnly asserted on real Set-Cookie); attributes property-driven | Adequate (Secure/SameSite property-driven; see §5) |
| AC5 CSRF on state-changing requests; credentialed CORS only for configured origins (D2) | `SessionControllerTest` (missing + mismatched CSRF → 403), `SessionAuthenticationFilterTest` (seeds readable CSRF), `SessionCookieAuthIntegrationTest` (preflight allowed for configured origin, refused for unlisted) | Strong |
| AC6 current-user endpoint distinguishes auth vs unauth without reusable credential (D2) | `SessionControllerTest` (200 summary asserts no `rawSecret`/`tokenHash`/`sessionId`; 401), `AuthenticatedPrincipalArgumentResolverTest`, `SessionCookieAuthIntegrationTest` | Strong |
| AC7 frontend bootstraps from `/api/me`; no personalized request while unresolved (D2, D7) | `SessionBootstrap.test.tsx` (deferred session; no `fetchPuzzles`/`fetchWeaknesses` while loading even with stored username; gate opens on authenticated) | Strong |
| AC8 logout/expiry clears private state; late requests can't restore prior user (D2) | `SessionLogoutExpiry.test.tsx` (calls `logout`, clears active job, late puzzle resolve dropped) | Strong |
| AC9 explicit dev principal via same boundary; cannot activate in production (D7) | `DevIdentityProviderTest`, `DevModeStartupGuardTest` (default+enabled aborts; dev/local+enabled boot; default+disabled boot), `DevSessionAbsenceTest` (404), `SessionCookieAuthIntegrationTest` | Strong |
| AC10 missing/invalid/expired/revoked fail closed (D2) | `SessionAuthenticationFilterTest`, `AuthenticatedPrincipalArgumentResolverTest`, `SessionControllerTest`, `SessionCookieAuthIntegrationTest` (garbage → 401; post-logout replay → 401) | Strong |
| AC11 backend migration/repo/service/request-security/controller/integration tests before production | Full backend matrix above | Strong (DDL gap disclosed, §5) |
| AC12 frontend bootstrap/logout/expiry/stale/credentials-CSRF/no-localStorage material | `SessionBootstrap`, `SessionLogoutExpiry`, `SessionCredentialsCsrf` (`credentials:'include'`, `X-XSRF-TOKEN` from cookie, no session material in `localStorage`) | Strong |
| AC13 existing anonymous/dev behavior explicit or fail-closed; not treated as authenticated (D7) | `SessionBootstrap.test.tsx` ("Chess.com Connected" not derived from stored username; sign-in CTA), `DevSessionAbsenceTest`, `SessionCookieAuthIntegrationTest` | Strong |
| D1 stable internal identity keyed on `(issuer, subject)`, email-independent | AC1/AC2 tests | Strong |
| D2 opaque server-side sessions + cookie/CSRF/current-user boundary | AC3–AC8/AC10/AC12 tests | Strong |
| D7 explicit dev principal via same boundary; never implicitly authenticated | AC9/AC13 tests | Strong |

Exclusions appropriate: AC14 (docs) is not test-phase work; D3/D5/D6 are declared non-goals
and correctly untested; D4 wholesale endpoint migration is deferred and only its request-security
primitives (filter/resolver fail-closed) are exercised — consistent with the approved plan.

## 5. Disclosed limitations (accepted, not weakening)

- **Flyway/DDL gap:** `application-test.yml` disables Flyway and uses H2 `create-drop`, so
  `V2__identity_and_session.sql` DDL text is not executed by the JVM suite; repository tests
  assert `(issuer, subject)` and `token_hash` uniqueness through JPA-expressed constraints
  (`DataIntegrityViolationException` via `saveAndFlush`). Honestly disclosed; no fabricated
  migration proof. Postgres/Testcontainers migration check remains a documented follow-up.
- **Cookie Secure/SameSite** are property-driven (`SessionCookieProperties`); the integration
  test asserts HttpOnly on the live cookie and defers Secure/SameSite to configuration. Matches
  plan scope.
- **Frontend contract matchers** use flexible role/text matchers for the sign-in CTA and logout
  control; the present production satisfies them.

None of these weaken the approved test set; they bound proof honestly.

## 6. Command and reproducibility evidence (reviewer-run at HEAD 09fac2b)

- Targeted backend #113 suite (`./gradlew test --tests …` 11 classes) →
  **BUILD SUCCESSFUL; 45 tests, 0 failures, 0 errors, 0 skipped** (per-class XML aggregated:
  AuthIdentityRepositoryTest 4, AuthSessionRepositoryTest 3, IdentitySessionServiceTest 10,
  IdentitySessionServicePersistenceTest 3, DevIdentityProviderTest 2,
  SessionAuthenticationFilterTest 4, AuthenticatedPrincipalArgumentResolverTest 4,
  DevModeStartupGuardTest 4, SessionControllerTest 6, DevSessionAbsenceTest 1,
  SessionCookieAuthIntegrationTest 4). Matches report.
- Full backend `./gradlew ktlintCheck test` → **BUILD SUCCESSFUL; 43 classes, 277 tests,
  0 failures/errors/skips** (ktlint clean). #85 + #111 + #113 coexist green. Matches report.
- Targeted frontend session suite (`npx vitest run SessionBootstrap SessionCredentialsCsrf
  SessionLogoutExpiry`) → **3 files, 8 tests passed**. Matches report.
- Full frontend `npm run test` → **27 files, 263 tests passed** (benign jsdom
  `HTMLMediaElement.prototype.play` "Not implemented" console noise from pre-existing sound
  tests; fails nothing). Matches report.
- `npm run lint` → exit 0; `npx tsc --noEmit` → exit 0. Matches report. (`npm run build` is a
  validation-phase check re-run at VALIDATION; test compilation and typecheck already clean.)
- Combined test fingerprint and non-test fingerprint recomputed **identical** after all runs;
  `git status` shows no modified/staged test-path files.

## 7. Findings

No blocking findings. No test drift, weakening, vacuous assertion, `ApplicationContextRunner`
misuse, or hidden #111/#113 context/mock/profile conflict was found. Security, session, and
frontend-lifecycle assertions remain strong and fail-closed. Integrity hashes and fingerprints
all match; reported green counts are reproducible.

**Verdict: `READY_FOR_HUMAN_APPROVAL`** (technical reviewer readiness only).
