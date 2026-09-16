# Identity and Session Foundation

This document describes the provider-neutral identity and server-side session
foundation introduced by [issue #113](https://github.com/NathanZK/ChessEcho/issues/113),
implementing the first slice of the architecture approved in issue #79 (decisions
D1, D2, and D7), together with the owner boundary from issue #252. It still does
not select or integrate a production identity provider.

## Goals

- One immutable internal identity (`AppUser.id`) that is independent of email,
  display name, or Chess.com username.
- Provider-neutral external identities, persisted and uniquely resolved by
  `(issuer, subject)`.
- Opaque, server-side sessions with a full lifecycle (create, expiry, rotation,
  revocation, logout, cleanup) where the raw secret is never persisted or exposed.
- A secure cookie/CSRF/credentialed-CORS request boundary that fails closed.
- An explicit development-only principal that cannot activate in production.

## Persistent identity

`AuthIdentity` decouples the external identity from the internal owner:

| Column | Notes |
|---|---|
| `id` | Surrogate key. |
| `app_user_id` | FK to the immutable `AppUser` (`ON DELETE CASCADE`). |
| `issuer`, `subject` | `UNIQUE(issuer, subject)`; the only identity key. |
| `email_snapshot`, `email_verified` | Optional metadata only. Never keys the identity and never triggers a merge. |
| `created_at`, `last_seen_at` | Timestamps. |

`app_user.email` is nullable (the clean Flyway `V1__baseline.sql`) so a provider-neutral principal can be
provisioned from `(issuer, subject)` claims that carry no email. The `UNIQUE(email)`
constraint is retained; PostgreSQL permits multiple `NULL`s.

Provisioning keys strictly on `(issuer, subject)`: the same pair always resolves to
the same `AppUser`; equal subjects under different issuers stay distinct; and an
`email_snapshot` change never creates or merges an identity.

## Opaque sessions

`AuthSession` stores only the SHA-256 hex of a 256-bit opaque secret. The raw
secret lives solely in the `HttpOnly` cookie — never in the database, a response
body, a log line, or browser storage.

Lifecycle (all mutations are `@Transactional`):

- **establish** — resolve/provision the `AuthIdentity` (and `AppUser` if absent),
  create an `AuthSession`, and return the raw secret only for the cookie writer.
- **resolve** — hash the cookie secret, load by `token_hash`, reject if revoked or
  past idle/absolute expiry, otherwise slide the idle window (capped by the
  absolute expiry) and return the principal.
- **rotate** — within one transaction, revoke the old row and insert a new one for
  the same user (atomic swap: never two live or zero live sessions on commit).
- **revoke** — set `revoked_at` (idempotent).
- **cleanup** — a `@Scheduled` sweep deletes revoked/absolutely-expired rows.

Idle and absolute lifetimes come from `chessecho.auth.session.*` (defaults: idle
30 minutes sliding, absolute 30 days).

## Provider-adapter boundary

`IdentityProviderAdapter.claims(request)` returns already-validated,
provider-neutral `VerifiedIdentityClaims(issuer, subject, emailSnapshot?, emailVerified?)`.
No production provider is wired in this slice. `DevIdentityProvider` is the only
concrete adapter and it exists only inside the development allowlist.

## Request boundary

- **`SessionAuthenticationFilter`** reads the session cookie, resolves it into a
  request-scoped `AuthenticatedPrincipal`, and seeds the readable `XSRF-TOKEN`
  cookie when absent. It never rejects; it fails closed by attaching no principal.
- **`AuthenticatedPrincipalArgumentResolver`** injects the principal and throws
  `UnauthenticatedException` (→ `401`) when a required principal is absent. It
  never exposes the raw secret.
- **`CsrfEnforcementInterceptor`** enforces double-submit CSRF on the
  state-changing endpoints (`POST /api/logout`, `POST /api/dev/session`,
  `POST /api/accounts`, and `POST /api/games/import`), comparing the
  `X-XSRF-TOKEN` header to the `XSRF-TOKEN` cookie in constant time
  (`MessageDigest.isEqual`). `GET`, `HEAD`, and `OPTIONS` (including CORS
  preflight) are safe and exempt.
- **`SessionCookieWriter`** writes the `HttpOnly` session cookie and the deletion
  cookie (identical attributes, empty value, `Max-Age=0`).
- CORS gains `allowCredentials(true)` bound to the existing explicit origins.

Any cookie that does not hash to a live, unrevoked, unexpired session yields no
principal, so `GET /api/me` returns `401` — never a partial authenticated state.

## Development-only principal (fail-closed allowlist)

Production runs on the **default** Spring profile (no `prod` profile exists), so
the dev path is gated as an allowlist rather than a `prod` denylist:

1. `DevSessionController` and `DevIdentityProvider` are annotated
   `@Profile("dev", "local")` **and** `@ConditionalOnProperty("chessecho.auth.dev-mode.enabled")`
   (default off). Under the default/production profile the bean is absent, so
   `POST /api/dev/session` returns `404` regardless of the property.
2. `DevModeStartupGuard` (an always-registered `ApplicationRunner`) aborts boot if
   `chessecho.auth.dev-mode.enabled=true` while none of `{dev, local}` is active —
   including the default/production profile.

When it does run (explicit `dev`/`local` + property on), the dev principal calls
the same `IdentitySessionService.establishSession` path as any provider.

## Frontend

The SPA bootstraps its session from `GET /api/me` into non-persistent state
(`loading` → `authenticated` / `unauthenticated` / `error`). No session material is
written to `localStorage`. Guest-eligible username-based reads wait for bootstrap
to resolve, but they are not suppressed when the result is `unauthenticated`:
the selected Chess.com username is a guest capability input, not proof of
identity. The UI labels that state as guest mode rather than an authenticated
connection. Session calls send `credentials: 'include'`, and logout sends the
`X-XSRF-TOKEN` header read from the readable cookie. Logout and expiry clear
private state and bump monotonic generation guards so a late in-flight response
can never restore a prior user's data.

### Guest/authenticated capability boundary

| Capability | Guest | Authenticated |
|---|---|---|
| Import Chess.com games and poll an unclaimed job | Allowed with CSRF | Allowed for owned account IDs |
| Read unclaimed practice puzzles and weakness analysis | Allowed after session bootstrap resolves | Allowed for owned account IDs |
| Continue puzzles, evaluate moves, and use analysis UI | Allowed | Allowed |
| Persist private history across devices or associate data with an internal owner | Not allowed | `POST /api/accounts` claims an account |
| Access private games, weaknesses, puzzles, or jobs after claim | Not allowed | Required owner principal and account UUID |

### Issue #252 ownership rules

`ChessAccount.user_id` is nullable only for an explicitly unclaimed guest
account. Every private and derived row is reached through its
`chess_account_id`; callers cannot authorize with a username, email, local
storage value, or job ID. Authenticated reads and imports select an account by
UUID and verify `user_id`; guest reads and imports can select only an unclaimed
canonical `(platform, username)` identity. Claiming is atomic under a row lock
and the case-insensitive unique index, so one owner wins a race and another
receives `409 ACCOUNT_CLAIM_CONFLICT`.

`POST /api/games/import` persists the complete immutable command snapshot:
account, normalized provider identity, date bounds, sorted time controls, and
player color. Workers reload that snapshot and fail closed with
`INVALID_READY_JOB_CONFIGURATION` rather than trusting a caller request.
`READY`/`UNRESOLVED` state and the supported status, platform, color, date, and
time-control checks are enforced by the baseline database and the domain.
The active-job partial unique index permits only one queued or processing job
per account. `GET /api/jobs/{id}` rechecks the persisted relationship on every
poll and never treats the returned `accountId` as a credential.

`GET /api/me` returning `401` is a valid guest state, not an application error.
Authentication gates must be applied only to capabilities that require durable
identity, privacy, ownership, or cross-device persistence. Provider integration,
session expiry, and logout must return guest-eligible flows to the guest state
without turning authentication into a global prerequisite.

## Out of scope (follow-ups)

Production provider selection/integration, issue #76 progress, and broader
profiles/account lifecycle management remain out of scope. The pre-production
Flyway history is a single complete `V1__baseline.sql`; no V2/V3 upgrade,
quarantine, reparenting, or placeholder-user backfill exists.
