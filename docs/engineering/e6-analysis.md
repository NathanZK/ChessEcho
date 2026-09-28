# E6 analysis evidence and results

E6 analysis consumes one finalized, verified #426 projection and its retained
artifact, #431's immutable source-located occurrence corpus, and the verified
retained #430 evaluation snapshot. It does not read imported PGNs or operational
evaluation rows. The #429 read/admission boundary compares the selected
population with the snapshot, verifies the ordered projection distribution,
and resolves every retained evaluation row through #431. The occurrence UUID
is a lookup key; its semantic location is `(sourceRunId, qualifyingOrdinal,
preMovePly)`. The declared evaluation-player roster is retained independently
of rows so a player with no evidence is still reported.

The retained #430 digest covers the exact reference-population tuple and
canonical evaluation rows, including occurrence UUID. It does **not** cover
the snapshot ID, declared roster, configuration, or source/engine/parser
metadata. The read path checks that digest against all rows in a consistent
view; snapshot immutability protects the separately retained metadata and
roster. Snapshots without a valid roster fail closed rather than inferring one
from surviving rows. The existing #430 aggregate reconstruction remains
available, but is not the #429 analysis input.

For player `i` and threshold `T` (0.30, 0.50, or 0.80), qualifying occurrences
are retained rows with `loss >= T`. `W_i(T)` is their set of distinct position
hashes; `S_i(T) = W_i(T) ∩ R(P)`, where `R(P)` is the distinct position set
of the selected #426 projection. Coverage is `|S_i(T)| / |W_i(T)|`, or N/A
when `W_i(T)` is empty. Pooled coverage sums player numerators and denominators
before division, without unioning players or averaging their ratios.
Mean, median, minimum, and maximum use defined player coverages only.

Source-location reporting includes **all** threshold-qualified evaluation
occurrences, even when their position is outside `R(P)`. Repeated positions
remain separate canonical occurrence observations. Location groups count
occurrences with an occurrence denominator; distinct-position and unique
source-game counts are descriptive, not substitutes for Metric A. The
evaluation row's game ID is not assumed to identify a #431 source game.
No cohort analysis, new weakness detector, or causal inference is performed.
