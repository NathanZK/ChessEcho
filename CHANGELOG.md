# Changelog

## Unreleased

### Added

- Sign-in users can reveal or re-mask their password with an accessible visibility toggle.
- Signed-in users can connect or disconnect Chess.com accounts, and personal training history remains scoped to the user/account pair after disconnecting.
- Authenticated import jobs retain their initiating user for personal-event attribution and job-status authorization.

### Changed

- Practical game-outcome evidence adjusts weakness and puzzle recommendation priority by default through a confidence-gated policy; authenticated adaptive scheduling remains active.
- Weaknesses Library actual-game results show W/D/L, score rate, and eligible-game count without internal evidence labels or training comparisons.
- Position Progress shows a separate historical baseline and one measured observation per SOLVED training interval, with explicit empty-interval and undated-game states.
- Account-claim conflicts explain when a Chess.com account is connected under another ChessEcho sign-in without changing the selected account or starting an import.

### Fixed

- Authenticated users without a connected Chess.com account now see their signed-in state and can sign out from the Header.
- Disconnect 404 responses now reconcile the selected account against the authenticated account list without treating the error as success.
- Position Progress win rates correctly count imported Chess.com wins for White and Black, including the first encounter, with PGN result fallback.
