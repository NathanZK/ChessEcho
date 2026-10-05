# Chess account data boundary

## Contract

- `ChessAccount` stores a shared Chess.com identity and owns shared imported games, occurrences, position statistics, and engine analysis.
- `AccountConnection` links an application user to a shared account. A user has at most one active connection; many users may connect the same account.
- Authenticated shared-data reads by account ID require a principal and an existing account, but do not require a current connection.
- Guest imports and username-based reads resolve the shared account regardless of its connection state.
- Authenticated imports require an explicit account ID that is currently connected to the caller. The import job stores the immutable initiating user.
- Guest jobs are pollable only without an authenticated principal and remain guest-pollable regardless of later connection changes. Authenticated jobs are pollable only by their initiator.
- Personal scheduling events, progress, and training attempts remain scoped by both `app_user_id` and `chess_account_id`. Disconnect preserves these rows; reconnect restores access.
- `GET /api/accounts` returns zero or one current connection. Connecting a different account requires disconnecting the current one first.

## Flow

- Connect: authenticate → connect or repeat the shared `(platform, username)` identity → persist the user/account link.
- Import: a guest submits platform and username; an authenticated caller submits the explicitly connected account ID → persist a job and initiator → import shared games.
- Disconnect: delete only the caller's `AccountConnection`; shared data and personal history remain unchanged.
- Progress: authenticate → select a current connection explicitly → read occurrences and personal events for that exact account and principal.

## Invariants

- Account identity is unique case-insensitively by platform and username; account identity is not owned exclusively by one user.
- A unique `app_user_id` constraint enforces at most one active connection per user; `(app_user_id, chess_account_id)` is also unique.
- Connection foreign keys cascade connection-row deletion when the referenced user or account is deleted. Other account, job, and history delete policies remain unchanged.
- Authenticated personal history never derives its owner from the current connection or the import job's shared account.
- A job's initiating user cannot change after persistence and remains authoritative for personal import events and authenticated status visibility.
- Source-linked scheduling events are unique by `(app_user_id, position_occurrence_id, event_type)` with PostgreSQL `NULLS NOT DISTINCT` semantics and a non-null occurrence predicate.
- The baseline schema is authoritative before deployment; no historical user attribution is invented.

## Failures

- A second different connection returns `409 ACCOUNT_CONNECTION_LIMIT_REACHED`; it never replaces the current connection.
- Authenticated imports without an account ID return `400 ACCOUNT_SELECTION_REQUIRED`; unknown IDs return `404 ACCOUNT_NOT_FOUND`; known but unconnected IDs return `403 FORBIDDEN`.
- Progress without an account selection returns `400 ACCOUNT_SELECTION_REQUIRED`; unknown or unconnected IDs return `404 ACCOUNT_NOT_FOUND`.
- Disconnect of a missing caller connection returns `404 ACCOUNT_NOT_FOUND`.
- A guest cannot poll an authenticated job; a signed-in non-initiator cannot poll another user's job.
- Non-404 disconnect failures preserve the confirmed frontend selection. A DELETE 404 alone does not confirm disconnection; after it, the frontend reloads the authenticated account list.
- Failed or malformed account-list reloads leave connection state unconfirmed and recoverable rather than reporting success.

## Convention

- Backend services enforce connection, job-initiator, and personal-state boundaries; PostgreSQL constraints enforce durable connection uniqueness.
- Flyway/PostgreSQL integration tests prove partial `NULLS NOT DISTINCT` uniqueness; Hibernate-generated test schemas do not represent that rule.
