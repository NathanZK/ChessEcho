# Issue #113 — Test implementation report (TESTS ONLY, refreshed for baseline 09fac2b)

Role: `chess-echo-implementer` in `TEST_IMPLEMENTATION`. Authoritative state confirmed
`TEST_IMPLEMENTATION` (scope `full-stack`, target base `main`). The plan was human
re-approved by NathanZK (`plan_approved`, confirmation `plan_approved`,
2026-09-01T23:42:24Z) after an authorized reconciliation of the non-test baseline onto
`origin/main == 09fac2b65fbe7d0e936cdb385ef6fa68094656c0`, which advanced when upstream
**#111** ("practical weakness evidence") landed immediately before final normalization.
Approved plan SHA
`54493c47d5906dd2517a7866f1fbf45ca355e3f2a15f1718443866cb30fcd373`; final plan review SHA
`045f02d3e8fbf97f17920d05ee274801e077b199e9c1537103d6c57c1288514b`. Approved-plan non-test
fingerprint `106478fb0a2154bd67a7353b366b934f7950507e021456def79d72c523cef88a`.

This refresh re-establishes **test evidence only** after the #111 reconciliation. No test
file was created, modified, removed, weakened, or rewritten in this phase; no production,
docs, config, dependency, or workflow code was touched. Because the approved #113 production
is already present (uncommitted working tree), the previously red #113 test set is now
**green**; the earlier red-phase failures are retained below only as history. The
reconciliation exposed **no** test defect, so no test was changed.

---

## 1. Reconciled baseline and #111 / #85 coexistence

- **Reconciled baseline:** `origin/main == HEAD == 09fac2b65fbe7d0e936cdb385ef6fa68094656c0`
  ("Implement practical weakness evidence"). This baseline already contains upstream **#111**
  (practical weakness evidence, commits `837c0b5` and `09fac2b`) stacked on top of upstream
  **#85** (`b1b8398` "Show live game-import progress …", `d27ff36` "Implement live game
  import progress (#85)"). Both #111 and #85 changes — production and tests — are first-class
  baseline content and are preserved unchanged.
- **#111 tests are baseline (tracked) and coexist with the #113 tests.** #111 introduced /
  extended these backend test classes, all present and tracked at this baseline:
  - `service/PracticalEvidenceServiceTest.kt` (new)
  - `service/WeaknessCalculationServiceTest.kt` (new)
  - `service/WeaknessPriorityPolicyTest.kt` (new)
  - `service/GameOutcomeNormalizerTest.kt` (new)
  - `service/PgnHeaderTagReaderTest.kt` (new)
  - `integration/controller/PracticalWeaknessControllerIntegrationTest.kt` (new)
  - `integration/controller/WeaknessControllerIntegrationTest.kt` (extended)
  - `integration/controller/PuzzleControllerIntegrationTest.kt` (extended)
  - `integration/service/GameImportServiceIntegrationTest.kt` (extended)
- **#85 tests remain baseline (tracked) and coexist too.** Representative upstream #85 tests
  confirmed present and tracked:
  - `frontend/src/__tests__/AccountContextAndImport.test.tsx`
  - `frontend/src/__tests__/ImportJobStore.test.tsx`
  - `src/test/kotlin/com/chessecho/controller/GameImportControllerTest.kt`
  - `src/test/kotlin/com/chessecho/integration/service/GameImportServiceIntegrationTest.kt`
- The workflow-authoritative test fingerprint is computed over **all** files matching the
  `full-stack` `test_paths` globs, so it now necessarily includes the upstream **#85** and
  **#111** baseline tests **plus** the 14 new #113 tests. All three sets compile and pass
  together (see §4).

## 2. #113 test files (unchanged in this phase)

Backend (`src/test/kotlin/com/chessecho/`), untracked working-tree files:
- `repository/AuthIdentityRepositoryTest.kt` — `@DataJpaTest`
- `repository/AuthSessionRepositoryTest.kt` — `@DataJpaTest`
- `service/auth/IdentitySessionServiceTest.kt` — Mockito (`MockitoExtension`)
- `service/auth/IdentitySessionServicePersistenceTest.kt` — `@DataJpaTest` + `@Import(service)`
- `service/auth/DevIdentityProviderTest.kt` — plain unit (provider-neutral claims boundary)
- `web/SessionAuthenticationFilterTest.kt` — servlet filter unit
- `web/AuthenticatedPrincipalArgumentResolverTest.kt` — argument-resolver unit
- `config/DevModeStartupGuardTest.kt` — direct `ApplicationRunner.run()` unit
- `controller/SessionControllerTest.kt` — `@WebMvcTest(SessionController)`
- `integration/controller/DevSessionAbsenceTest.kt` — `@SpringBootTest(RANDOM_PORT)`, default profile
- `integration/controller/SessionCookieAuthIntegrationTest.kt` — `@SpringBootTest(RANDOM_PORT)`, `@ActiveProfiles("test","dev")`

Frontend (`frontend/src/__tests__/`), untracked working-tree files:
- `SessionBootstrap.test.tsx`
- `SessionCredentialsCsrf.test.ts`
- `SessionLogoutExpiry.test.tsx`

**14 files, 1480 lines total.** These are byte-for-byte the approved #113 tests; this refresh
did not touch them. `git status` shows no modified/staged files under any `test_paths` glob —
the only test-path entries are these pre-existing untracked #113 files (see §6).

## 3. Acceptance-criteria and #79 decision (D1, D2, D7) mapping

| AC / Decision | Covering tests |
|---|---|
| AC1 immutable internal identity, email-independent (D1) | `AuthIdentityRepositoryTest` (owner stable across lookups), `IdentitySessionServiceTest` (provisioning does not key on email), `IdentitySessionServicePersistenceTest`; nullable-`email` is exercised through the identity email-snapshot path. |
| AC2 identities resolved by `(issuer, subject)`; equal subjects distinct; email change never merges (D1) | `AuthIdentityRepositoryTest` (repeat-lookup identity/owner, distinct issuer + equal subject, unique constraint, email-snapshot no-merge). |
| AC3 opaque sessions create/expiry/revoke/rotate/logout; secret never stored raw (D2) | `AuthSessionRepositoryTest` (findByTokenHash, unique hash, cleanup), `IdentitySessionServiceTest` (only-hash persisted; slide; reject revoked/idle/absolute; rotate; idempotent revoke), `IdentitySessionServicePersistenceTest` (only-hash observable; one live row after rotate). |
| AC4 cookie `HttpOnly`/`Secure`/`SameSite` (D2) | `SessionCookieAuthIntegrationTest` (session cookie is `HttpOnly`); attributes are property-driven via `SessionCookieProperties`. |
| AC5 CSRF on state-changing requests; credentialed CORS only for configured origins (D2) | `SessionControllerTest` (missing/mismatched CSRF → 403), `SessionAuthenticationFilterTest` (seeds readable CSRF cookie), `SessionCookieAuthIntegrationTest` (credentialed preflight allowed for configured origin, refused for unlisted). |
| AC6 current-user endpoint distinguishes auth vs unauth without reusable credential (D2) | `SessionControllerTest` (200 summary with no secret/session-id field; 401 when absent), `SessionCookieAuthIntegrationTest`. |
| AC7 frontend bootstraps from `/api/me`; no personalized request from stale username while unresolved (D2, D7) | `SessionBootstrap.test.tsx` (no `fetchPuzzles`/`fetchWeaknesses` while loading even with stored username; gate opens on authenticated). |
| AC8 logout/expiry clears private state; late requests can't restore prior user (D2) | `SessionLogoutExpiry.test.tsx` (logout calls `logout`, clears active job, late puzzle resolve dropped via monotonic generation). |
| AC9 explicit dev principal through same boundary; cannot activate in production (D7) | `DevIdentityProviderTest`, `DevModeStartupGuardTest` (default+enabled throws; dev/local+enabled pass; default+disabled pass — via direct `run()`), `DevSessionAbsenceTest` (default profile → 404), `SessionCookieAuthIntegrationTest` (allowlisted `dev` happy path proves real runner lifecycle permitted boot). |
| AC10 missing/invalid/expired/revoked fail closed (D2) | `SessionAuthenticationFilterTest` (no/garbage cookie → no principal), `AuthenticatedPrincipalArgumentResolverTest` (absent principal → `UnauthenticatedException`/401), `SessionControllerTest` (401), `SessionCookieAuthIntegrationTest` (garbage cookie → 401; post-logout → 401). |
| AC11 backend migration/repo/service/request-security/controller/integration tests before production | Full backend matrix above. Migration/JPA constraints exercised via H2 JPA-expressed uniqueness (see §7 limitation). |
| AC12 frontend bootstrap/logout/expiry/stale/credentials-CSRF/no-localStorage-material | `SessionBootstrap.test.tsx`, `SessionLogoutExpiry.test.tsx`, `SessionCredentialsCsrf.test.ts` (credentials include, `X-XSRF-TOKEN`, no session material in localStorage). |
| AC13 existing anonymous/dev behavior explicit or fail-closed; not treated as authenticated (D7) | `SessionBootstrap.test.tsx` ("Chess.com Connected" not derived from stored username when unauthenticated; sign-in CTA), `DevSessionAbsenceTest`, `SessionCookieAuthIntegrationTest`. |
| D1 stable internal identity keyed on `(issuer, subject)`, email-independent | `AuthIdentityRepositoryTest`, `IdentitySessionServiceTest`, `IdentitySessionServicePersistenceTest` (see AC1/AC2). |
| D2 opaque server-side sessions + cookie/CSRF/current-user boundary | `AuthSessionRepositoryTest`, `IdentitySessionServiceTest`, `SessionControllerTest`, `SessionCookieAuthIntegrationTest`, `SessionBootstrap/Logout/CredentialsCsrf` frontend tests (see AC3–AC8, AC10, AC12). |
| D7 explicit dev principal through the same boundary; never implicitly authenticated | `DevIdentityProviderTest`, `DevModeStartupGuardTest`, `DevSessionAbsenceTest`, `SessionCookieAuthIntegrationTest`, `SessionBootstrap.test.tsx` (see AC9, AC13). |

Scope notes: AC14 (docs) is out of scope for the test phase (documentation, not tested).
Decisions D3 (account association), D5 (async import ownership), and D6 (#76 progress) are
explicit non-goals and are intentionally not tested. D4 (owner-scoped endpoint migration) is
only exercised at the request-security primitives (filter/resolver fail-closed); wholesale
endpoint migration is deferred and not tested.

## 4. Commands run and results (current, green)

All commands were run at the reconciled baseline
`09fac2b65fbe7d0e936cdb385ef6fa68094656c0` with the approved #113 production present.

Backend (from repo root):

- `./gradlew ktlintCheck compileTestKotlin` → **BUILD SUCCESSFUL**. Main and test source
  sets are ktlint-clean and the entire test source set (upstream #85 + #111 + #113) compiles
  against the now-present production symbols.
- Targeted #113 suite —
  `./gradlew test --tests com.chessecho.repository.AuthIdentityRepositoryTest --tests com.chessecho.repository.AuthSessionRepositoryTest --tests com.chessecho.service.auth.IdentitySessionServiceTest --tests com.chessecho.service.auth.IdentitySessionServicePersistenceTest --tests com.chessecho.service.auth.DevIdentityProviderTest --tests com.chessecho.web.SessionAuthenticationFilterTest --tests com.chessecho.web.AuthenticatedPrincipalArgumentResolverTest --tests com.chessecho.config.DevModeStartupGuardTest --tests com.chessecho.controller.SessionControllerTest --tests com.chessecho.integration.controller.DevSessionAbsenceTest --tests com.chessecho.integration.controller.SessionCookieAuthIntegrationTest`
  → **BUILD SUCCESSFUL**. Per-class result XML: **11 classes, 45 tests, 0 failures, 0
  errors, 0 skipped**:
  - `AuthIdentityRepositoryTest` 4, `AuthSessionRepositoryTest` 3,
    `IdentitySessionServiceTest` 10, `IdentitySessionServicePersistenceTest` 3,
    `DevIdentityProviderTest` 2, `SessionAuthenticationFilterTest` 4,
    `AuthenticatedPrincipalArgumentResolverTest` 4, `DevModeStartupGuardTest` 4,
    `SessionControllerTest` 6, `DevSessionAbsenceTest` 1,
    `SessionCookieAuthIntegrationTest` 4.
- Full backend suite — `./gradlew test` → **BUILD SUCCESSFUL**. Aggregated result XML:
  **43 test classes, 277 tests, 0 failures, 0 errors, 0 skipped**. This confirms the upstream
  **#85** and **#111** backend tests and the **#113** backend tests all pass together. The
  #111 practical-weakness classes ran green (e.g. `PracticalEvidenceServiceTest`,
  `WeaknessCalculationServiceTest`, `WeaknessPriorityPolicyTest`,
  `GameOutcomeNormalizerTest`, `PgnHeaderTagReaderTest`,
  `PracticalWeaknessControllerIntegrationTest`).

Frontend (from `frontend/`, `node_modules` already installed):

- `npm run test` (`vitest run`, full suite) → **27 files passed, 263 tests passed, 0
  failed**. This includes the upstream #85 frontend tests (e.g.
  `AccountContextAndImport.test.tsx`, `ImportJobStore.test.tsx`) and the 3 new #113 session
  test files. (Benign jsdom `HTMLMediaElement.prototype.play` "Not implemented" console noise
  originates from pre-existing sound tests and does not fail any test.)
- The 3 #113 session files in isolation
  (`npx vitest run SessionBootstrap.test.tsx SessionCredentialsCsrf.test.ts SessionLogoutExpiry.test.tsx`)
  → **3 files passed, 8 tests passed** — exactly the 8 assertions that were red before
  production existed.
- `npm run lint` (`eslint --max-warnings 0`) → **exit 0** (clean, including the new tests).
- `npx tsc --noEmit` → **exit 0** (type-clean).
- `npm run build` (`next build`) → **exit 0** (Compiled successfully; TypeScript check
  passed; static pages generated).

## 5. Fingerprints

- **Approved-plan non-test fingerprint (unchanged):**
  `106478fb0a2154bd67a7353b366b934f7950507e021456def79d72c523cef88a`. The current workspace
  non-test fingerprint recomputes to the identical value, proving no production, config, docs,
  dependency, or workflow file changed since plan approval. The `submit-tests` non-test-only
  guard (`require_test_only_phase_unchanged`) therefore holds.
- **Current combined test fingerprint (workflow-authoritative,
  `files_fingerprint(root, test_paths)`):**
  `11aaa2a68ef766599b835a8a50ecb7ff767e23ab3308ca940c29681a02886232`. This fingerprint spans
  every file matching the `full-stack` `test_paths` globs — i.e. the upstream **#85** and
  **#111** baseline tests **plus** the 14 **#113** tests — so it correctly reflects their
  coexistence at baseline `09fac2b`. The value is stable before and after running the backend
  and frontend suites (build outputs are git-ignored and excluded from the fingerprint).

## 6. Evidence: no production/non-test change after approval; no test change in this phase

- `git status --porcelain=v1` shows the reconciled #113 production as tracked modifications and
  untracked additions (e.g. `M src/main/kotlin/com/chessecho/domain/AppUser.kt`,
  `?? src/main/kotlin/com/chessecho/service/auth/`,
  `?? src/main/resources/db/migration/V2__identity_and_session.sql`), the 14 untracked #113
  test files, and the untracked `.agent-workflow/runs/` infrastructure. No new production edits
  were made in this phase, and the non-test fingerprint match in §5 confirms production is
  byte-identical to the plan-approval state.
- **No test modifications in this refresh:** there are no modified/staged files under
  `src/test/**`, `frontend/src/**/__tests__/**`, `*.test.*`, `*.spec.*`,
  `frontend/vitest.config.mts`, or `frontend/vitest.setup.ts`. The only test-path entries not
  already tracked are the pre-existing untracked #113 test files; the #85 and #111 baseline
  tests remain tracked and unmodified. Reconciliation onto #111 did not expose any test defect;
  nothing in the approved test set was edited, weakened, or removed. Build outputs (`build/`,
  `.next/`) are git-ignored and excluded from all fingerprints.
- **Combined test tree, signatures, and conflict markers independently verified:** the 73
  files matching `test_paths` were enumerated (73 = tracked #85/#111/baseline tests +
  `vitest.config.mts` + `vitest.setup.ts` + `application-test.yml` + the 14 untracked #113
  files); a full-tree scan found **no** git conflict markers (`<<<<<<<`, `=======`,
  `>>>>>>>`); and every referenced production symbol resolves (backend `compileTestKotlin`
  and frontend `tsc --noEmit` are clean).

## 7. Limitations

- **Flyway/DDL test gap (known, plan §12):** `application-test.yml` disables Flyway and uses
  H2 `create-drop`, so the planned `V2__identity_and_session.sql` DDL text is not executed by
  the JVM suite; H2 builds the schema from the JPA entities. The repository tests therefore
  assert the constraints through JPA-expressed uniqueness (`(issuer, subject)`, `token_hash`)
  rather than the raw Postgres migration. A Postgres/Testcontainers migration check remains a
  documented follow-up; these tests do not fabricate migration proof.
- **Dev-mode boot-abort:** the fail-closed guard is proven by a direct `run()` unit test and
  the real-runner happy path in `SessionCookieAuthIntegrationTest`; per plan §9.6 no
  `ApplicationContextRunner` assertion is used (it never invokes `callRunners()`).
- **Frontend contract surface:** the sign-in CTA and logout-control assertions define the
  observable contract using flexible matchers; the now-present production satisfies them.

## 8. History — prior red-phase context (superseded by §4)

Before the #113 production existed, the same test set was intentionally red: backend
`compileTestKotlin` failed with `Unresolved reference` to the planned production symbols
(`AuthIdentity`, `AuthSession`, `AuthIdentityRepository`, `AuthSessionRepository`,
`IdentitySessionService`, `SessionCookieProperties`, `SessionWebConfig`,
`DevModeStartupGuard`, `SessionController`, `SessionAuthenticationFilter`,
`AuthenticatedPrincipalArgumentResolver`, `CsrfEnforcementInterceptor`, `SessionCookieWriter`,
`AuthenticatedPrincipal`, `VerifiedIdentityClaims`, `DevIdentityProvider`, …) plus the
deliberate nullable-`email` argument-type mismatch, and the frontend session suite showed 8
behavioral failures (`fetchCurrentSession is not a function` / `logout is not a function`;
stale-username `fetchPuzzles`; missing logout wiring). Those failures were each tied to a
missing planned production surface. Prior test-phase reports/reviews on the earlier
pre-#111 baseline (`b1b8398`) recorded that red evidence and superseded fingerprints; they
are retained as history only. With the approved production now present at baseline `09fac2b`,
every one of those assertions passes; §4 is the current authoritative evidence.

## 9. Git overview

- Branch tracks reconciled `main`; `HEAD == origin/main == 09fac2b65fbe7d0e936cdb385ef6fa68094656c0`.
- No commits added in this phase; the #113 tests remain working-tree files consumed by
  `submit-tests`.
- Approved-plan non-test fingerprint (unchanged, matches workspace):
  `106478fb0a2154bd67a7353b366b934f7950507e021456def79d72c523cef88a`.
- Combined workflow test fingerprint (sha256, spans #85 + #111 + #113 tests):
  `11aaa2a68ef766599b835a8a50ecb7ff767e23ab3308ca940c29681a02886232`.
