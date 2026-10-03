# Disconnect Not Found Reconciliation

context:
- A disconnect request can return 404 while the UI still has a server-hydrated account selected.
- Network, authorization, and server errors do not establish that the account is absent.

choice:
- Preserve disconnect HTTP status and reload the authenticated account list only after a 404.
- Gate account-dependent work until the list confirms the current selection.
- Keep the failed operation visible after reconciliation.

ruled-out:
- Clearing selection after every DELETE failure, because non-404 failures do not establish absence.
- Reloading after every failure, because it adds unnecessary work and risks discarding a valid selection.
