# Issue #113 — Plan review (chess-echo-reviewer)

Status: READY_FOR_HUMAN_APPROVAL

## 0. Scope of this review

Acting as the independent `chess-echo-reviewer` at authoritative state `PLAN_REVIEW`. I did **not** edit production code, tests, or the submitted plan, and I record **no** human approval — `READY_FOR_HUMAN_APPROVAL` is reviewer technical readiness only and authorizes no implementation, tests, migrations, dependencies, or PR creation.

This is one coherent, full re-review of the plan the human reopened for the **second baseline reconciliation onto `origin/main` `09fac2b` after upstream #111** ("Implement practical weakness evidence"). The human reopen mandate is strictly: reconcile #113 with `origin/main` `09fac2b`, preserve the landed #111 changes, and refresh plan/test evidence **without changing #113 architecture or scope**. I re-checked the whole plan (not only the delta) and confirm the submission stays within that mandate.

## 1. Artifacts and hashes

- **Reviewed plan** `.agent-workflow/runs/issue-113/artifacts/plan.md` — content SHA-256 `54493c47d5906dd2517a7866f1fbf45ca355e3f2a15f1718443866cb30fcd373` (matches the recorded submission SHA).
- **Prior review** `.agent-workflow/runs/issue-113/artifacts/plan-review.md` (now overwritten by this file): pre-overwrite content SHA-256 `6f3012e9973baccb1ef910c5fa502c958e5d05206ce0dba1788f2bd4c3692347`, status `READY_FOR_HUMAN_APPROVAL`, subject the prior (post-#85 `b1b8398`) refreshed plan. Its resolved findings SA-DEFECT-1/2/3 and R1–R4 are the continuity baseline below.
- **#79 architecture/review** recovered from Git commit object `c1fb55c` (`git cat-file -t c1fb55c` → `commit`); its `plan-review.md` (`READY_FOR_HUMAN_APPROVAL`) drives decisions D1–D7 the plan references.
- **Committed baseline** `origin/main` = `HEAD` = `09fac2b65fbe7d0e936cdb385ef6fa68094656c0`.
- **Upstream #111** = commits `837c0b5` + `09fac2b`; prior planning baseline `b1b839828da51ef4ec1898ac359eecfd3e752538`.
- **Verdict:** `READY_FOR_HUMAN_APPROVAL`.

## 2. Overall assessment

The second baseline reconciliation is **correct and complete**, and the plan remains internally coherent and source-accurate. Every claim I could verify against the repository holds at the committed `09fac2b` tree. HEAD/origin ancestry, the #111-as-baseline-only treatment, #111's non-contradiction with D1–D7, every refreshed source anchor, the prior SA-DEFECT-1/2/3 + R1–R4 resolutions, the AC1–AC14 ↔ D1–D7 mapping, the non-goal exclusions, the test strategy, and the mandatory source-alignment/executability evidence all hold on the reconciled tree. Only the baseline SHA/divergence, the `AppConfig.kt`/`application.yml` overlap-resolution narrative, and confirmation that no frontend anchor shifted required refreshing. My independent full re-review surfaces **no new blocker** and **no `SOURCE_ALIGNMENT_DEFECT`**.

The preserved uncommitted #113 implementation in the worktree is correctly used as *evidence* that the plan remains executable against the reconciled tree; the document stays a forward-looking plan, not an implementation report.

## 3. Baseline, divergence & #111 evidence (independently verified)

| Claim | Verification | Result |
|---|---|---|
| `HEAD == origin/main == 09fac2b65…` | `git rev-parse HEAD`/`origin/main` | ✔ both `09fac2b65fbe7d0e936cdb385ef6fa68094656c0` |
| Divergence `0 0` | `git rev-list --left-right --count origin/main...HEAD` | ✔ `0    0` |
| `b1b8398` strict ancestor of `09fac2b` | `git merge-base --is-ancestor` | ✔ ancestor |
| Intervening commits = upstream #111 | `git log --oneline b1b8398..09fac2b` | ✔ `09fac2b` + `837c0b5`, both "Implement practical weakness evidence" |
| #111 diff = 25 files, no migration, no frontend | `git diff --stat b1b8398 09fac2b` | ✔ 25 files, `3926 +/176 −`; no `*.sql`/`db/migration`; no `frontend/` file |
| #111 adds new practical-evidence backend + shares only `AppConfig.kt` + `application.yml` with #113 | diff file list | ✔ new `PracticalEvidenceProperties.kt`, `service/{GameOutcomeNormalizer,PgnHeaderTagReader,PracticalEvidenceService,WeaknessPriorityPolicy}.kt`, edits to `WeaknessCalculationService.kt`/`PuzzleController.kt`/`PositionOccurrenceRepository.kt`/DTOs, plus `AppConfig.kt`, `application.yml`, two docs, and #111's own tests |
| #111 does **not** touch `GlobalExceptionHandler.kt`, `AppUser.kt`, `build.gradle.kts`, `API_CONTRACT.md`, `page.tsx`, `Header.tsx`, `api.ts` | diff file list | ✔ none present in #111 diff |

**Conclusion:** #111 is upstream baseline content only, with **no scope absorption** into #113. The plan treats it exactly this way (§0, §12, §13 "Upstream #111 coexistence").

## 4. Shared-file overlap resolution (independently verified)

- **`AppConfig.kt`** — at `09fac2b`, `@EnableConfigurationProperties(ChessPubApiProperties::class, PracticalEvidenceProperties::class)` (lines 13–16), and `corsConfigurer` still has `.allowedHeaders("*")` at line 35 (`git diff b1b8398 09fac2b` shows #111's **only** change was appending `PracticalEvidenceProperties::class`; the `corsConfigurer` body is byte-identical to `b1b8398`). The preserved #113 working copy **retains both** `ChessPubApiProperties::class` and `PracticalEvidenceProperties::class` and **appends** `SessionCookieProperties::class`; the `corsConfigurer` body gains `.allowedHeaders("Content-Type", "X-XSRF-TOKEN")` + `.allowCredentials(true)`. No `PracticalEvidenceProperties` registration is lost; no conflict markers remain. ✔ matches §6.2/§13.
- **`application.yml`** — #111 added `chess.weakness.practical.*` **nested under the existing `chess:` block** (verified diff). The preserved #113 working copy adds a **separate top-level `chessecho.auth.*` block** above `chess:`, preserving the entire `chess.weakness.practical.*` subtree untouched. The two never touch the same lines. ✔ matches §13.
- **Repository-wide conflict-marker scan** of `src/`, `frontend/src/`, `API_CONTRACT.md`, `application.yml` → none. ✔ no stale/conflict claims.

`PracticalEvidenceProperties`, the practical config block, and the #113 session properties/CORS coexist with **no conflict and no stale claims**.

## 5. #111 non-interference with #113 architecture (independently verified)

- **No migration / no DDL:** `git diff --name-only b1b8398 09fac2b` lists no `*.sql`/`db/migration`; schema is identical to `b1b8398`, so the mixed-timestamp convention and the SA-DEFECT-2 fix stand. ✔
- **No frontend change:** no `frontend/` file in #111 diff; the identity/session frontend architecture, placeholder-`AppUser` path, lifecycle, and anchors are unaffected. ✔
- **Placeholder `AppUser` path intact:** `GameImportService.getOrCreateAccount` at line 207 and the placeholder `AppUser(email = dummyEmail)` at lines 214–215 are exact at `09fac2b`; `GameImportService.kt` is **not** in the #111 diff (it was refactored only by #85). ✔
- **Dependencies unchanged:** `build.gradle.kts` not in #111 diff; Spring Boot pin `3.3.2` intact — the basis of the SA-DEFECT-3 runner-lifecycle proof is preserved. ✔
- **Tests / docs contract:** #111 adds only its own tests and two practical-evidence architecture docs; it does not touch `API_CONTRACT.md` or any #113 test surface. ✔

⇒ #111 introduces **no contradiction and no new genuine design decision** for the #113 identity/session foundation.

## 6. Refreshed source anchors (independently verified at committed `09fac2b`)

| Anchor | Verified |
|---|---|
| `AppUser` `@Table("app_user")`, UUID `id`, `@Column(nullable=false, unique=true) email: String` (`domain/AppUser.kt`, email line 19) | ✔ |
| Only production `.email` refs: `AppUserRepository.findByEmail(String)` + `GameImportService:215 AppUser(email = dummyEmail)`; every #111 `AppUser(email=…)` occurrence is in #111 **test** files with non-null literals (compile against nullable `email`) | ✔ |
| `getOrCreateAccount` @207, `dummyEmail` @214, `AppUser(email=…)` @215 | ✔ |
| No `spring-security` in `build.gradle.kts`; no `@Profile`, no `application-prod.yml` in committed `src/main` @09fac2b | ✔ (only `application.yml` + `application-test.yml`) |
| `@EnableAsync` + `@EnableScheduling` on `ChessEchoApplication` (`@Scheduled` cleanup already enabled) | ✔ |
| V1 mixed timestamps: plain `TIMESTAMP` on `app_user`/`chess_account`/`async_job`/`game`; `TIMESTAMP WITH TIME ZONE` on `position`/`position_occurrence`/`engine_analysis`/`imported_archive`/`user_position_weakness`/`user_position_stats`/`human_move_bfs_seen_game` | ✔ |
| `GlobalExceptionHandler` maps `NoSuchElementException→404`, `IllegalArgumentException→400`, `ActiveImportJobException→409`, `ErrorResponse(error, details)` | ✔ (new `401 UNAUTHENTICATED`/`403 CSRF_FAILED` handlers fit) |
| `application-test.yml`: H2 `MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE`, `ddl-auto=create-drop`, `flyway.enabled=false` | ✔ |
| Frontend `page.tsx` @09fac2b: `activeUsernameStore` `useSyncExternalStore` (~L39–42), `puzzleLoadSeqRef` L80, `invalidatePuzzleRequests` L82, `handleSetUsername` L87, `handleDisconnect` L94, seq guards L621/641/651…; frontend byte-identical `b1b8398..09fac2b` (empty diff) | ✔ (all anchors reflect committed `09fac2b`, not the modified working copy) |

All anchors are accurate against committed `09fac2b`. (Minor: the `useSyncExternalStore(activeUsernameStore…)` call spans L39–43; the plan's `:39` label is the call's first line — not a defect.)

## 7. Acceptance-criteria ↔ #79 decision mapping

All 14 criteria map to a durable #79 decision and a verified source surface; the mapping is unchanged from the approved architecture and remains coherent after reconciliation:

| AC | Decision | Assessment |
|---|---|---|
| AC1 immutable internal identity | D1 | Sound — `AppUser.id` stays owner key; `AuthIdentity` decouples; `email` nullable (V2); id never derived from email/subject/username. |
| AC2 `(issuer, subject)` uniqueness | D1 | Sound — `AuthIdentity` + `findByIssuerAndSubject` + DB `UNIQUE(issuer, subject)`; email is metadata. |
| AC3 opaque session lifecycle; no raw secret stored | D2 | Sound — SHA-256 hex of 256-bit `SecureRandom`; raw secret only in `HttpOnly` cookie; transactional lifecycle + atomic rotation (R1). |
| AC4 cookie `HttpOnly`+env `Secure`/`SameSite` | D2 | Sound — `SessionCookieProperties`; `Lax` default valid in dev (same *site*), prod `Secure=true`. |
| AC5 CSRF + credentialed CORS | D2 | Sound — constant-time double-submit (R3) on `/api/logout` + `/api/dev/session`; `allowCredentials(true)` with explicit origins (R4). |
| AC6 current-user endpoint | D2 | Sound — `GET /api/me` 200 summary / 401 fail-closed, no reusable secret. |
| AC7 bootstrap from `/api/me`; no stale-username fetch | D2/D7 | Sound — new `sessionStore` + bootstrap effect; existing puzzle/weakness gates extended to wait on `sessionStatus !== 'loading'` (reuses verified L581/598 gates). |
| AC8 logout/expiry clears private state | D2 | Sound — reuses `invalidatePuzzleRequests()`/`handleDisconnect()`/`WeaknessesList.loadSeqRef` generation bumps + `activeJobStore` clear before render clear. |
| AC9 dev principal via same boundary; not in prod | D7 | Sound — fail-closed `{dev,local}` allowlist: `@Profile` + `@ConditionalOnProperty` + always-registered `DevModeStartupGuard`; SA-DEFECT-1/3 resolved and non-vacuous. |
| AC10 fail-closed sessions | D2/D4 | Sound — filter → no principal → 401 via `UnauthenticatedException` in `GlobalExceptionHandler`. |
| AC11 backend test coverage | D2 | Sound — §9 matrix uses verified `@DataJpaTest`/Mockito/`@WebMvcTest`/`@SpringBootTest(RANDOM_PORT)` patterns. |
| AC12 frontend test coverage | D2 | Sound — §9 Vitest/Testing-Library + deferred-promise + localStorage-mock helpers verified to exist. |
| AC13 anonymous/dev migrated or fails closed | D7 | Sound — new boundary fails closed; dev principal explicit; frontend stops inferring auth from stored username; existing anonymous endpoints stay out of the boundary (owner-migration deferred). |
| AC14 API + architecture docs | D2 | Sound — `API_CONTRACT.md` additions + new `docs/architecture/identity-and-session.md`. |

D1/D2/D7 (plus the principal primitives of D4) are implemented; D3, D5, D6, and the broader D4 owner-migration are explicitly deferred to #79 §7 items 2–7. Non-goals in §2/§12 are internally consistent with this boundary.

## 8. Prior-fix continuity (SA-DEFECT-1/2/3, R1–R4)

| Item | Class | Status | Verified-still-holds on `09fac2b` |
|---|---|---|---|
| SA-DEFECT-1 — guard premised on nonexistent `prod` profile | `SOURCE_ALIGNMENT_DEFECT` | RESOLVED | No `@Profile`/`application-prod.yml`; `Dockerfile`/compose set no `SPRING_PROFILES_ACTIVE` → default-profile production. Fail-closed `{dev,local}` allowlist + always-registered `DevModeStartupGuard`. #111 untouched these. ✔ |
| SA-DEFECT-2 — V2 `timestamptz` vs "matches entities" contradiction | `SOURCE_ALIGNMENT_DEFECT` | RESOLVED | V1 mixed; V2 uses plain `TIMESTAMP` matching `app_user`. #111 added no `.sql`/DDL/timestamp column, so the convention is unchanged; §5.9/§6.1/§13 reconfirm. ✔ |
| SA-DEFECT-3 — boot-abort mechanism (`ApplicationContextRunner` never runs `ApplicationRunner`) | `SOURCE_ALIGNMENT_DEFECT` | RESOLVED | §9.6 direct `run()` unit test + `@SpringBootTest` runner-lifecycle proof; Spring Boot `3.3.2` pin intact (#111 didn't touch `build.gradle.kts`). ✔ |
| R1 — transactional lifecycle / atomic rotation | recommendation | RESOLVED | `establish/resolve(write)/rotate/revoke/cleanup` `@Transactional`; rotation = revoke-old + insert-new in one tx; `token_hash UNIQUE`. ✔ |
| R2 — logout raw-secret plumbing + clear-cookie | recommendation | RESOLVED | `@CookieValue(name="${chessecho.auth.cookie.name}", required=false)`; clear-cookie replays identical attributes with `Max-Age=0`; idempotent 204. ✔ |
| R3 — constant-time CSRF | recommendation | RESOLVED | `MessageDigest.isEqual` double-submit; `HttpOnly` session cookie never the CSRF token; safe `GET /api/me` seeds `XSRF-TOKEN`. ✔ |
| R4 — credentialed-CORS wording | recommendation | RESOLVED | No wildcard **origin** with credentials (explicit origins); explicit `allowedHeaders` a deliberate clarity choice. ✔ |

No architecture, scope, test strategy, or defect resolution was disturbed by the second reconciliation.

## 9. Security assessment

- **Session secrecy:** raw secret only in the `HttpOnly` cookie; DB stores SHA-256 hex; never in body, log, or `localStorage`. Sound.
- **CSRF:** double-submit with constant-time `MessageDigest.isEqual`, applied to state-changing endpoints only; session cookie is never the CSRF token; safe `GET` exempt but seeds the readable `XSRF-TOKEN`. Sound.
- **CORS:** credentials only with explicit origins (no wildcard origin); explicit allowed headers. The plan correctly frames the hard constraint (wildcard origin) and treats explicit headers as a clarity choice, not a functional necessity. Sound.
- **Dev principal:** fail-closed allowlist enforced two ways (`@Profile` bean gate + always-registered startup guard); default/production profile can neither expose `/api/dev/session` (404) nor boot with `dev-mode.enabled=true`. Matches the repo's real default-profile deployment. Sound.
- **Fail-closed resolution:** any non-live/revoked/expired session → no principal → 401; no partial-authenticated state. Sound.

## 10. Scope, continuity & executability

- **Scope:** stays within the human's reconciliation mandate — baseline/evidence refresh only, no architecture or scope change. Non-goals (owner-scoped migration, Chess.com association, async-ownership redesign, #76, provider selection, account/session UI) remain explicitly deferred. No optional refactor expands scope.
- **Continuity:** the plan is one coherent document; no stale, superseded, or contradictory sections; the historical #85 context is clearly labeled as baseline-only.
- **Executability:** the mandatory source-alignment/executability gate (§13) is satisfied — every referenced symbol exists, signatures match (`@CookieValue` placeholder, `@Transactional`, `WebMvcConfigurer` extension points, `HandlerMethodArgumentResolver`), relocated frontend lifecycle transitions name concrete replacement triggers (seq-ref/generation bumps, in-flight guards), stale-state/concurrency windows are addressed, and tests use reachable existing helpers. Another engineer can implement this without rediscovering the architecture.
- **Known gap (accepted, not a defect):** the V2 Flyway DDL is not exercised under H2 (`flyway.enabled=false`, `create-drop`); constraints are reached via JPA-expressed uniqueness, and a Postgres/Flyway migration check is a documented follow-up per #79 §8. The plan discloses this honestly.

## 11. Findings

- **New blockers:** none.
- **New `SOURCE_ALIGNMENT_DEFECT`:** none.
- **Architectural/planning disagreements:** none material.
- **Required changes:** none.

## 12. Verdict

**READY_FOR_HUMAN_APPROVAL** — reviewer technical readiness only. This is **not** human approval and authorizes no implementation, tests, migrations, dependencies, or PR creation. The refreshed plan for issue #113 is source-accurate against committed `09fac2b`, correctly treats upstream #111 as orthogonal baseline content with no scope absorption, preserves the shared `AppConfig.kt`/`application.yml` overlaps without conflict or loss, and keeps all AC↔D mappings, SA-DEFECT-1/2/3 + R1–R4 resolutions, security decisions, test strategy, and non-goals coherent and executable.

## 13. Reference hashes

- Reviewed plan `plan.md` SHA-256: `54493c47d5906dd2517a7866f1fbf45ca355e3f2a15f1718443866cb30fcd373`.
- Prior `plan-review.md` (overwritten) pre-overwrite SHA-256: `6f3012e9973baccb1ef910c5fa502c958e5d05206ce0dba1788f2bd4c3692347`.
- Committed baseline: `origin/main = HEAD = 09fac2b65fbe7d0e936cdb385ef6fa68094656c0`; upstream #111 = `837c0b5` + `09fac2b`; prior baseline `b1b839828da51ef4ec1898ac359eecfd3e752538`; #79 architecture from commit `c1fb55c`.
