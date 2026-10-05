# Position Progress chart and viewport

## Context

The progress API provides a historical baseline separately from measured
intervals, while the chart previously rendered only intervals. The fixed-height
app shell could also expose the body background during Position Progress
scrolling in Chrome.

## Choice

- Keep the API, interval model, and server-provided rate changes unchanged.
- Project an available baseline as the chart's first measurement; when absent,
  use the first interval as the displayed baseline without duplicating its
  chart point.
- Apply dynamic viewport sizing and a shrinkable scroll area only while
  Position Progress is selected.

## Ruled out

- Combining the baseline and intervals in a new backend response model.
- Changing interval construction, rate calculations, or global shell sizing.
