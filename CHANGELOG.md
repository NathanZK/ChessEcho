# Changelog

## Unreleased

### Added

- Sign-in users can reveal or re-mask their password with an accessible visibility toggle.
- Signed-in users can connect or disconnect Chess.com accounts, and personal training history remains scoped to the user/account pair after disconnecting.
- Authenticated import jobs retain their initiating user for personal-event attribution and job-status authorization.

### Changed

- Weaknesses Library actual-game results show W/D/L, score rate, and eligible-game count without internal evidence labels or training comparisons.
- Account-claim conflicts explain when a Chess.com account is connected under another ChessEcho sign-in without changing the selected account or starting an import.

### Fixed

- Disconnect 404 responses now reconcile the selected account against the authenticated account list without treating the error as success.
