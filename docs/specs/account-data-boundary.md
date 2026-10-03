# Chess account data and personal training state

contract:
  - `ChessAccount` owns shared imported games, occurrences, position statistics, and engine analysis.
  - The mutable account connection owner controls account claims and authenticated import initiation; it does not own shared imported data.
  - Any authenticated user may read shared data for an existing account by `accountId`. Guests may read only unclaimed accounts using platform and username.
  - Authenticated scheduling events and training attempts carry `app_user_id` and are scoped to the selected `chess_account_id`; guest writes remain unattributed.
  - Disconnect clears only the connection pointer. Shared data and the disconnecting user's personal history remain stored for reconnection.
  - Authenticated import jobs retain their initiating user immutably. Only that user may read job status; guest jobs are visible only while the account is unclaimed.
  - Authenticated puzzle events and timed-training attempts include the selected account ID. Guest imports and timed-training attempts remain unattributed; guest timed-training attempts omit the account ID.

flow:
  - Import: current owner submits the confirmed account ID → job stores the initiating user → worker imports shared games and attributes personal events to the initiator.
  - Disconnect: current owner calls `DELETE /api/accounts/{accountId}/connection` → server clears the connection pointer → personal history remains intact.
  - Personal reads: authenticated request selects an account → service filters personal history by both principal and account.

invariant:
  - Authenticated personal history never derives its owner from the account's mutable connection pointer.
  - A job's initiating user cannot be changed after persistence and remains authoritative for its emitted personal events and status visibility.
  - Source-linked scheduling events are unique by `(app_user_id, position_occurrence_id, event_type)` with PostgreSQL `NULLS NOT DISTINCT` semantics and a non-null occurrence predicate.
  - The baseline schema is authoritative before deployment; no historical user attribution is invented.

failure:
  - A foreign user cannot initiate an import for a currently connected account or read another user's authenticated job status.
  - Unauthenticated reads and imports are rejected for claimed accounts.
  - Non-404 disconnect failures preserve the confirmed frontend selection and dependent context.
  - A DELETE 404 alone does not confirm a disconnect.
  - After a DELETE 404, the frontend reloads the authenticated account list.
  - The frontend removes or replaces a selection only when a valid list omits it.
  - An empty valid list leaves the user unconnected; a non-empty list selects only a listed account.
  - A failed or malformed reload leaves connection state unconfirmed and recoverable; it does not report disconnect success.

convention:
  - Backend ownership, shared-read resolution, job status, and personal-state scoping are enforced server-side.
  - Flyway/PostgreSQL integration tests prove the partial `NULLS NOT DISTINCT` uniqueness rule; Hibernate-generated test schemas do not represent it.
