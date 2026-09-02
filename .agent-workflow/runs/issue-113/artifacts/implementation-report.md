# Issue #113 — Implementation Report

## Status: IMPLEMENTATION COMPLETE — normalized to one issue commit atop the frozen base, ready for final validation

The approved #113 first-foundation slice (persistent identity + opaque
server-side session foundation) is fully implemented against the current
`origin/main` baseline and normalized to exactly one issue commit. The approved
test set is byte-identical to the frozen approval, and the whole change set is
scoped strictly to #113 — the upstream #85/#111 work is already part of the base
and is not re-introduced or reverted by this branch.

## Git evidence

- Configured target base: `main`; local tracking ref `origin/main`.
- Frozen base SHA (`origin/main`): `09fac2b65fbe7d0e936cdb385ef6fa68094656c0`.
  This base already contains the upstream #85/#111 work; #113 builds on top of it.
- Divergence at normalization (`origin/main...HEAD`) before committing: `0 0`
  (identical), i.e. the branch had no residual commits and all #113 changes were
  an intact working tree on the frozen base.
- Normalization result: exactly **one** issue commit atop the frozen base.
  `origin/main` is a first-parent ancestor of `HEAD`, and
  `git rev-list --count origin/main..HEAD` = `1`.
- Final `HEAD`: the single squashed issue commit produced by this submission
  (parent `09fac2b65fbe7d0e936cdb385ef6fa68094656c0`). The concrete SHA is emitted
  by `git rev-parse HEAD` after the commit and is recorded authoritatively in the
  workflow's structured state at `submit-implementation` and again, together with
  the frozen base, by `run-validation`; that structured record — not this prose —
  is the mechanical authority for the Git evidence.
- Worktree is clean after the commit (no staged/unstaged/untracked residue; no
  `assume-unchanged`/`skip-worktree` flags); no conflict markers anywhere in the
  change set.

## Frozen fingerprints (unchanged from approval)

- Approved-test fingerprint (over the configured `test_paths`):
  `11aaa2a68ef766599b835a8a50ecb7ff767e23ab3308ca940c29681a02886232` — the current
  worktree matches exactly, so `submit-implementation` accepts the approved tests.
- Approved non-test fingerprint (frozen at plan approval):
  `106478fb0a2154bd67a7353b366b934f7950507e021456def79d72c523cef88a`.

## Upstream approval chain (hash-verified)

- Plan `plan.md`: `54493c47d5906dd2517a7866f1fbf45ca355e3f2a15f1718443866cb30fcd373`.
- Plan review `plan-review.md`: `045f02d3e8fbf97f17920d05ee274801e077b199e9c1537103d6c57c1288514b`.
- Test report `test-report.md`: `e4d58793ba0f5245538229985615c6ac326963d458d1e3c8de15aafcad2ef51a`.
- Test review `test-review.md`: `4d78fccf597638727155ea37740cc3a17a4f8bb78a6897bcca9bc8d1d830eef6`.
- Plan approved by NathanZK (`plan_approved`); tests approved by NathanZK
  (`tests_approved`, 2026-09-01T23:55:31Z).

## Production changes (files)

Modified:
- `src/main/kotlin/com/chessecho/domain/AppUser.kt` — `email` made nullable.
- `src/main/kotlin/com/chessecho/config/AppConfig.kt` — credentialed CORS (`allowCredentials(true)`, explicit `Content-Type`/`X-XSRF-TOKEN` headers); register `SessionCookieProperties`.
- `src/main/kotlin/com/chessecho/controller/GlobalExceptionHandler.kt` — `UnauthenticatedException`→401 `UNAUTHENTICATED`, `CsrfException`→403 `CSRF_FAILED`.
- `src/main/resources/application.yml` — `chessecho.auth.*` defaults.
- `build.gradle.kts` — `testImplementation` Apache HttpClient5 so `TestRestTemplate` sends the credentialed-CORS preflight headers the JDK client drops.
- `frontend/src/services/api.ts` — `fetchCurrentSession`, `logout`, `devLogin`, `csrfToken` helper, `SessionState`.
- `frontend/src/app/page.tsx` — session bootstrap, session-gated personalized fetches, logout/expiry clearing, session-derived indicator.
- `frontend/src/components/Header.tsx` — connected/sign-in derived from session state.
- `API_CONTRACT.md` — session endpoints, cookies, CSRF, CORS.

Added (backend):
- `domain/AuthIdentity.kt`, `domain/AuthSession.kt`
- `repository/AuthIdentityRepository.kt`, `repository/AuthSessionRepository.kt`
- `config/SessionCookieProperties.kt`, `config/SessionWebConfig.kt`, `config/SessionSecurityConfig.kt`, `config/DevModeStartupGuard.kt`
- `service/auth/{VerifiedIdentityClaims,IdentityProviderAdapter,DevIdentityProvider,SessionModels,SessionSecrets,IdentitySessionService}.kt`
- `web/{AuthExceptions,SessionAuthenticationFilter,AuthenticatedPrincipalArgumentResolver,CsrfEnforcementInterceptor,SessionCookieWriter}.kt`
- `controller/SessionController.kt`, `controller/DevSessionController.kt`, `dto/CurrentUserResponse.kt`
- `resources/db/migration/V2__identity_and_session.sql`

Added (docs): `docs/architecture/identity-and-session.md`.

## Acceptance-criteria → implementation mapping

- AC1/AC2 (immutable internal identity; `(issuer, subject)` uniqueness; no email merge): `AuthIdentity` + `AuthIdentityRepository.findByIssuerAndSubject`, `UNIQUE(issuer, subject)`, nullable `app_user.email`, provisioning keyed only on `(issuer, subject)`.
- AC3 (opaque sessions; hash-only; lifecycle): `AuthSession.tokenHash` (SHA-256 hex), transactional establish/resolve/rotate/revoke/cleanup; atomic rotation.
- AC4 (cookie attributes): `SessionCookieWriter` (`HttpOnly` always; `Secure`/`SameSite`/`Path` config-driven; deletion replays attributes with `Max-Age=0`).
- AC5 (CSRF + credentialed CORS): `CsrfEnforcementInterceptor` (constant-time `MessageDigest.isEqual`), CORS `allowCredentials(true)` on explicit origins.
- AC6/AC10 (current-session endpoint; fail closed): `GET /api/me` 200/401; filter + argument resolver fail closed.
- AC7/AC8 (frontend bootstrap gating; logout/expiry clearing): session-gated fetches; monotonic generation guards; active-job clearing.
- AC9/AC13 (dev-only principal; cannot activate in prod): `@Profile("dev","local")` + `@ConditionalOnProperty` + always-registered `DevModeStartupGuard`.
- AC11/AC12 (backend/frontend tests): all approved tests green (see below).
- AC14 (docs): `API_CONTRACT.md` + `docs/architecture/identity-and-session.md`.

## Security semantics

- Raw secret exists only in the `HttpOnly` cookie; DB stores only the SHA-256 hex; never in a response body, log, or `localStorage`.
- Missing/invalid/expired/revoked cookie → no principal → 401 (never a partial authenticated state).
- Double-submit CSRF required on `POST /api/logout` and `POST /api/dev/session`, constant-time compared; preflight exempt.
- Credentialed CORS bound to explicit origins only.
- Dev principal is a fail-closed allowlist (`404` outside `{dev, local}`; boot aborts if enabled outside the allowlist).
- `auth_identity`/`auth_session` FKs to `app_user` use `ON DELETE CASCADE` (referential-integrity hygiene; users are effectively immutable in production).

## Validation evidence

These are the configured `run-validation` checks. The authoritative results are
produced and recorded by the Orchestrator's `run-validation` on the normalized
`HEAD`; the latest independent Reviewer run of the same commands on this baseline
was green:

- `./gradlew ktlintCheck` — pass
- `./gradlew test` — pass (277 tests, 0 failures)
- `cd frontend && npm run lint` — pass (`eslint --max-warnings 0`, no output)
- `cd frontend && npx tsc --noEmit` — pass (no errors)
- `cd frontend && npm run test` — pass (263 tests)
- `cd frontend && npm run build` — pass (`next build` compiled successfully)

Approved-test fingerprint unchanged: `11aaa2a68ef766599b835a8a50ecb7ff767e23ab3308ca940c29681a02886232`.

## Known limitations / notes

- Migration-proof gap (documented, carried from #79 §8): `application-test.yml` disables Flyway and uses H2 `create-drop`, so the V2 DDL text is not exercised by the JVM suite; constraints are covered via JPA-expressed uniqueness. A Postgres/Flyway check remains a follow-up.
- Non-goals intentionally excluded: production provider integration, Chess.com association, owner-scoped endpoint migration, async ownership redesign, legacy adoption, #76, profiles/account management.
- The `build.gradle.kts` HttpClient5 test dependency is required for the approved `SessionCookieAuthIntegrationTest` credentialed-CORS preflight assertions to pass (the JDK `HttpURLConnection` silently drops `Origin`/`Access-Control-Request-Method`).

## Draft PR proposal

See `pr-body.md` (headings `## What`, `## Why`, `## Testing`). Title:
"Add persistent identity and server-side session foundation (#113)".
