# Authenticated Progress History

## Contract

- From each exact weakness position in the Weaknesses Library, an action opens a focused progress detail view with a way back to the library.
- The view requests `GET /api/positions/{positionId}/progress?playerColor={WHITE|BLACK}` using the existing session cookie (`credentials: include`). The position ID comes from the weakness item and the player color is derived from its FEN side to move, matching the existing practice adapter.
- The frontend consumes the existing response shape: `positionId: string` (non-empty), `playerColor: 'WHITE' | 'BLACK'`, `points: ProgressPoint[]`, `mistakeRateChange: number | null`, `winRateChange: number | null`, and `assessment: string`. Each point has `occurredAt: string` (a parseable ISO-8601 timestamp), `mistakeRate: number` and `winRate: number` (finite values from 0 through 100), and `attempts: number` (positive integer). Change numbers must be finite. All listed properties are required; unknown extra properties are ignored for forward compatibility. Reject malformed JSON or a response that violates this shape, including a mismatch between returned and requested position ID or player color.
- Plot the ordered mistake-rate and win-rate points over time as percentage series. The backend is authoritative for all progress calculations and assessment text.
- Render `assessment` verbatim. For zero points show a no-history state. For one point, show the point but show `Not enough history yet` for both change metrics rather than presenting a zero change as a trend. With at least two points, show each supplied change value without recalculation; for a `null` value show `Not available yet`.
- Show a loading state before the progress response, a request/invalid-response error state with retry, and a no-history state for a valid empty `points` array. An authenticated response with one point remains a limited-history view, not an error.
- Gate the request on the existing session status. While status is `loading`, show `Checking sign-in…` and do not fetch. Unauthenticated users see a sign-in CTA linking to `/login` and no progress request is made. For session status `error`, show `Unable to verify sign-in. Refresh the page and try again.`; do not fetch or show the unauthenticated CTA.
- Out of scope: backend or authentication changes, progress recalculation, similar-position or cross-position aggregation, training-accuracy analytics, and predictive interpretations.

## Acceptance

- API-client tests assert encoded position/color path and query, included credentials, successful typed response, and rejection for non-success, network, malformed JSON, invalid required fields/ranges, or mismatched returned position/color; extra response fields are accepted.
- UI tests cover authenticated fetch/render, FEN-derived player color, both graph series, supplied change values, exact `Not enough history yet` / `Not available yet` rendering, verbatim assessment, loading, retryable request error, zero-point and one-point states, unauthenticated sign-in behavior with no API call, and the distinct session-checking and session-error states with no API call.
- The Weaknesses Library integration test proves a weakness action opens the matching progress view and its back action returns to the library.
- Frontend validation follows `frontend/AGENTS.md`: lint, TypeScript, Vitest, and production build.

## Convention

- Keep the change in the existing Next.js frontend and API client; use the existing React, TypeScript, Tailwind, and testing conventions.
- Prefer a dependency-free inline SVG chart over adding a charting package. Label the chart and its series accessibly.
- Keep loading, empty, and failure states distinct. Do not use a successful-looking default for malformed or failed API responses.
- Follow the repository’s issue-focused scope and keep the existing backend endpoint and authenticated session boundary unchanged.

## Working notes

- UI options considered: a modal (compact but constrains the graph), a separate route (adds route/deep-link behavior beyond the issue), or a focused detail view within the Weaknesses Library (recommended; context is retained and the graph has space).
- The API response and authorization behavior are already implemented by #325. `ProgressService` reports rates in 0–100 and provides percentage changes, which can be `null` when a baseline rate is zero; the frontend must display those values rather than derive replacements.
