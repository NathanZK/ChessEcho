# Connected Chess.com account state

contract:
  - `AccountSummary` contains `id`, `platform`, and `username`. Authenticated `GET /api/accounts` returns zero or one current connection; a successful `POST /api/accounts` confirms the returned connection.
  - A Chess.com identity is shared and may be connected by multiple users. Each user may have only one active connection and must disconnect before connecting a different account.
  - Local storage is a cache, not proof of ownership. A cached username is not connected until server session and account hydration succeed.
  - Session hydration distinguishes loading, authenticated, unauthenticated, and error. Only an unauthenticated session enables guest imports; guest imports and username-based reads work regardless of whether the account is connected.
  - Authenticated account hydration distinguishes loading, connected, unconnected, and error. Failed or malformed account responses remain errors and can be retried.
  - Users explicitly connect a Chess.com username from Import Games after account hydration. The frontend calls `associateAccount("CHESS_COM", username)` and selects the returned account.
  - Account association does not start an import. A `409 ACCOUNT_CONNECTION_LIMIT_REACHED` explains that the current account must be disconnected first; other failures preserve the confirmed selection and remain retryable.
  - Users can disconnect a confirmed account. The frontend calls `DELETE /api/accounts/{accountId}/connection`; only a successful response clears the selected account and its account-scoped UI state.
  - Disconnect removes only the user's connection row but retains shared imported data and the user's account-scoped training history for a later reconnect.
  - Hydration retains a cached selection only when its account ID appears in the server response; otherwise it selects the first returned account.
  - Authenticated imports require the confirmed selected account and call `startAccountImportJob` with its account ID.
  - Guest imports use the existing username-based `startImportJob` request.
  - Authenticated Progress requests include the explicitly selected connected account ID; guests are routed to sign-in without a Progress request.
  - Authenticated personal training writes include the selected account ID; guest timed attempts omit it.

flow:
  - Session bootstrap: query the current session → hydrate the caller's connections → expose connected or unconnected state; allow guest behavior only when unauthenticated.
  - Account connection: submit the entered Chess.com username → link the shared identity to the authenticated user → select and display the server-returned account.
  - Account disconnection: request server-side disconnection → on success clear selected-account and scoped UI state; on failure retain state and display the error.
  - Authenticated import: validate the connected username → submit the selected account ID with import filters → display the existing job status and errors.
  - Progress: choose the current connection explicitly → request history for that account ID → show the existing sign-in prompt for guests.
  - Account-scoped personal state: select an account → load and write history scoped to the signed-in user/account pair.

invariant:
  - The connected label and authenticated import selector refer to the same server-confirmed account.
  - Shared-data reads by authenticated account ID do not imply a connection; imports and personal Progress require the caller's current connection.
  - An authenticated user's missing, loading, or failed account state never falls back to a guest import.
  - A username mismatch cannot submit an import for a different selected account.
  - Logout, session-owner changes, and stale requests cannot restore the prior account context.

failure:
  - Account-list HTTP, network, and malformed-response failures are surfaced as retryable errors, not empty account lists.
  - Association failures do not change the selected account or start an import. A `409 ACCOUNT_CONNECTION_LIMIT_REACHED` displays disconnect-first guidance; other failures display the API error. Retries are user-initiated.
  - Disconnect failures preserve the selected account and display the API error.
  - Import failures use the existing import error state; imports are not automatically retried.

convention:
  - Use the typed API helpers, browser stores, React state patterns, and Testing Library/Vitest conventions.
  - The server session and connection checks remain authoritative; client identifiers are selectors only.
