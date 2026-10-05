# Puzzle outcome-count aggregate

## Context

The puzzle UI needs solved and failed submission counts for one authenticated user/account/position/color context.
Stored scheduling events already contain the outcome and replay-safe submission identity.

## Choice

Use one outcome-grouped aggregate over qualifying scheduling events.
Return both counts from the existing narrow read endpoint; absent groups map to zero.
Preserve submission storage, replay transactions, and all existing exclusions.

## Ruled out

- Independent outcome queries can observe different snapshots during concurrent writes.
- Returning event history shifts aggregation into the frontend and exposes unnecessary data.
- A new analytics table duplicates existing outcome history without supporting this narrow requirement.
