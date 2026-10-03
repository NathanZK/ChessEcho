# Weakness account identity

issue: #470
convention: Preserve the frontend API contract and account hydration; keep authenticated reads keyed by account UUID and guest reads keyed by Chess.com username.

decision: Add an optional `accountId` selector prop to `WeaknessesList`. Keep `username` as the display label and guest selector. The request selector is `accountId ?? username`; UI identity text uses only the username.

contract: The weaknesses page passes the selected connected account UUID as `accountId` and its Chess.com username as `username`. Once the session gate opens, non-authenticated states retain the existing active-username guest lookup; authenticated sessions pass the connected account UUID and username only after account hydration. Authenticated sessions without a hydrated connected account pass neither identity; they must not use principal IDs, cached usernames, or account IDs as display names.

invariant: Initial, refresh/filter, and paginated weakness requests use the account ID for an authenticated connected account, and the selector—not the display label—remains the request-state identity. Guest requests continue to use their supplied username.

failure: If there is no request selector, do not request weaknesses and show the existing disconnected state. If an account selector exists without a display username, requests may proceed; suppress the analysis banner and render the empty state without a player label. Never expose the selector as a name.

test: Extend `frontend/src/__tests__/WeaknessesList.test.tsx` to verify banner and empty-state copy use a display username distinct from the account UUID and that initial/filter/refresh/paginated requests retain the UUID; verify the API URL still uses `accountId=` for UUID selectors and username lookup for guests. Extend connected-account page coverage to verify the hydrated Chess.com username and account UUID reach the weaknesses view independently.

scope: `frontend/src/app/page.tsx`, `frontend/src/components/WeaknessesList.tsx`, and focused frontend tests. Do not change backend/API contracts, account hydration, filters, paging, refresh behavior, or guest username lookup.

## Working notes

- Alternative: rename `username` to `displayUsername` and introduce a separate selector prop. This makes the distinction explicit at every call site but changes a broader component API than this boundary needs.
- Alternative: format the UUID only at the two visible text sites. This does not correct the prop contract or protect future display uses.
- Recommended approach: retain `username` for its existing guest/display meaning and add `accountId` solely for authenticated selection. This is the smallest explicit boundary change and preserves guest behavior.
