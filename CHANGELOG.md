# Changelog

## Unreleased

### Added

- Sign-in users can reveal or re-mask their password with an accessible visibility toggle.
- Signed-in users can connect or disconnect Chess.com accounts, and personal training history remains scoped to the user/account pair after disconnecting.
- Authenticated import jobs retain their initiating user for personal-event attribution and job-status authorization.

### Changed

- Puzzle training events now record only solved and failed outcomes; puzzle activation, timed-training start, and skips no longer submit scheduling events.
- Practical game-outcome evidence adjusts weakness and puzzle recommendation priority by default through a confidence-gated policy; authenticated adaptive scheduling remains active.
- Weaknesses Library actual-game results show W/D/L, score rate, and eligible-game count without internal evidence labels or training comparisons.
- Position Progress shows a historical baseline and a latest measured interval summary while retaining every measured observation in its chart; the summary shows open/closed status, while chronological chart labels and tooltips omit it. The assessment compares mistake and win rates with the baseline; empty-interval and undated-game states remain explicit.
- Position Progress now classifies mistakes using the threshold from the selected Weaknesses result.
- Account-claim conflicts explain when a Chess.com account is connected under another ChessEcho sign-in without changing the selected account or starting an import.

### Fixed

- Authenticated users without a connected Chess.com account now see their signed-in state and can sign out from the Header.
- Disconnect 404 responses now reconcile the selected account against the authenticated account list without treating the error as success.
- Position Progress win rates correctly count imported Chess.com wins for White and Black, including the first encounter, with PGN result fallback.
