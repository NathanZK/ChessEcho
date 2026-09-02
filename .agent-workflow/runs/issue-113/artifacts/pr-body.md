## What

Add the provider-neutral persistent identity and opaque server-side session
foundation for issue #113 (first slice of the #79 architecture, decisions
D1/D2/D7). Backend: a nullable `app_user.email`, a new `AuthIdentity` keyed
uniquely on `(issuer, subject)`, and an opaque `AuthSession` that stores only the
SHA-256 hash of a 256-bit secret with idle + absolute expiry, sliding idle capped
by absolute, revocation, atomic rotation, and a scheduled cleanup — all
transactional. A provider-adapter boundary (`IdentityProviderAdapter`) consumes
already-validated claims with no production provider wired. A servlet filter
resolves the `HttpOnly` session cookie into a request-scoped principal (failing
closed), an argument resolver injects it (401 when absent), a double-submit CSRF
interceptor guards state-changing endpoints with a constant-time comparison, and
credentialed CORS is limited to the configured origins. New endpoints: `GET
/api/me`, `POST /api/logout`, and a dev-only `POST /api/dev/session` gated by a
`{dev, local}` profile allowlist plus an opt-in property, with an always-registered
startup guard that aborts an enabled dev mode outside the allowlist. Additive
Flyway `V2`. Frontend: non-persistent session bootstrap/state with loading/
authenticated/unauthenticated/error, credentialed session calls with a CSRF helper,
session-gated personalized fetches, logout/expiry clearing with monotonic
stale-response guards, and an auth indicator derived from session state rather than
a stored Chess.com username. Docs updated (`API_CONTRACT.md`, new
`docs/architecture/identity-and-session.md`).

## Why

Today a browser-selected Chess.com username is treated as identity, with no
authenticated principal, session boundary, or CSRF/credentialed-CORS contract.
This slice builds the durable identity/session substrate that later slices
(provider integration, account association, owner-scoped authorization) depend on,
exactly as #79 §7 item 1 defines, without selecting a production provider,
associating Chess.com accounts, or migrating existing endpoints to owner scope.

## Testing

- `./gradlew ktlintCheck` — pass
- `./gradlew test` — pass (277 tests, 0 failures)
- `cd frontend && npm run lint` — pass
- `cd frontend && npx tsc --noEmit` — pass
- `cd frontend && npm run test` — pass (263 tests)
- `cd frontend && npm run build` — pass
