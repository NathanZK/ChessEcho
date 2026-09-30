# Connected Chess.com account state

contract:
  - `AccountSummary` contains `id`, `platform`, and `username`. Only an account returned by authenticated `GET /api/accounts` or successful `POST /api/accounts` is confirmed connected.
  - Local storage is a cache, not proof of ownership. A cached username is not connected until server session and account hydration succeed.
  - Session hydration distinguishes loading, authenticated, unauthenticated, and error. Only an unauthenticated session enables guest imports.
  - Authenticated account hydration distinguishes loading, connected, unconnected, and error. Failed or malformed account responses remain errors and can be retried.
  - Users explicitly connect a Chess.com username from Import Games after account hydration. The frontend calls `associateAccount("CHESS_COM", username)` and selects the returned account.
  - Account association does not start an import. Association errors preserve the confirmed selection and remain retryable.
  - Users can disconnect a confirmed account. The frontend calls `DELETE /api/accounts/{accountId}/connection`; only a successful response clears the selected account and its account-scoped UI state.
  - Disconnect clears the server-side connection pointer but retains shared imported data and the user's account-scoped training history for a later reconnect.
  - Hydration retains a cached selection only when its account ID appears in the server response; otherwise it selects the first returned account.
  - Authenticated imports require the confirmed selected account and call `startAccountImportJob` with its account ID.
  - Guest imports use the existing username-based `startImportJob` request.
  - Authenticated personal training writes include the selected account ID; guest timed attempts omit it.

flow:
  - Session bootstrap: query the current session → hydrate owned accounts for authenticated users → expose connected or unconnected state; allow guest behavior only when unauthenticated.
  - Account connection: submit the entered Chess.com username → associate it with the authenticated owner → select and display the server-returned account.
  - Account disconnection: request server-side disconnection → on success clear selected-account and scoped UI state; on failure retain state and display the error.
  - Authenticated import: validate the connected username → submit the selected account ID with import filters → display the existing job status and errors.
  - Account-scoped personal state: select an account → load and write history scoped to the signed-in user/account pair.

invariant:
  - The connected label and authenticated import selector refer to the same server-confirmed account.
  - An authenticated user's missing, loading, or failed account state never falls back to a guest import.
  - A username mismatch cannot submit an import for a different selected account.
  - Logout, session-owner changes, and stale requests cannot restore the prior account context.

failure:
  - Account-list HTTP, network, and malformed-response failures are surfaced as retryable errors, not empty account lists.
  - Association failures do not change the selected account and display the API error.
  - Disconnect failures preserve the selected account and display the API error.
  - Import failures use the existing import error state; imports are not automatically retried.

convention:
  - Use the typed API helpers, browser stores, React state patterns, and Testing Library/Vitest conventions.
  - The server session and account ownership checks remain authoritative; client identifiers are selectors only.
