# Long Decisions exploration return context

## Context

Launching Line Exploration replaces the selected puzzle in the shared practice workspace and normally clears the supplied-position session on exit. Switching tabs also unmounts Long Decisions and discards its local query and results.

## Choice

Long Decisions launches reuse the supplied-position exploration path and preserve the originating view and selected puzzle state. An active practice timer pauses for exploration and resumes with its saved elapsed or remaining time.

## Ruled out

- Returning to a fresh Long Decisions search loses the originating query and result page.
- A separate exploration board duplicates the existing modes and continuation behavior.
- Letting an active practice timer continue during exploration records unrelated time against the selected puzzle.
