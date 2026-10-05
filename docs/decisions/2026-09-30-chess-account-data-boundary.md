# Chess account data boundary

## Context

Chess.com account identity and imported game data are shared, while puzzle
history and training attempts belong to an individual user. Account connections
can change independently of imported data and in-flight jobs.

## Current choice — issue #503

- Keep shared identity and imported data on `ChessAccount`; do not store a single user pointer there.
- Link users to accounts through `AccountConnection`, with unique `(app_user_id, chess_account_id)` and unique `app_user_id`; many users may connect the same account, but a user may have only one active connection.
- Lock the user row while connecting, disconnecting, and authorizing authenticated import creation. A user must disconnect before connecting a different account.
- Allow guest imports and username-based shared-data reads regardless of connection state. Authenticated imports require an explicit currently connected account ID.
- Authorize authenticated job status by immutable initiating user and guest job status by guest initiation, independent of later account connections.
- Require an explicit current account connection for personal Progress. Scope personal scheduling events, progress, and training attempts by both user and account; disconnect retains this data for reconnection.
- Delete only the connection row on disconnect. Its user/account foreign keys cascade connection-row deletion; existing account, job, and history delete policies remain unchanged.
- Preserve PostgreSQL source-linked replay protection with a partial `NULLS NOT DISTINCT` unique index. Update the pre-deployment V1 baseline in place; no deployed-data backfill is required.

## Superseded choice

The earlier pre-deployment design used a nullable `app_user_id` on
`ChessAccount` as an exclusive owner pointer, allowed guests only while an
account was unclaimed, and rejected another user's connection with
`ACCOUNT_CLAIM_CONFLICT`. Issue #503 replaces that model with shared account
identity and per-user connection rows.

## Ruled out

- Using the mutable current connection to determine personal-history ownership or job-status access.
- Deleting shared data or personal training history when a user disconnects.
- Cascading user deletion into training history or audit jobs without an approved deletion policy.
- Expressing the PostgreSQL partial uniqueness rule as a table-wide JPA unique constraint.
