# Reference-coverage analysis evidence and results

`RetainedEvaluationEvidenceService` reconstructs and validates retained
evidence, selects the artifact population, checks terminal eligibility, and
resolves occurrences. `ReferenceCoverageAnalysisService` calculates
reference-coverage results. Value types and validation live in
`RetainedEvaluationEvidence.kt`; coverage models and calculations live in
`ReferenceCoverageAnalysisService.kt`.

The v1 artifact's serialized `e6Eligible` field is a fixed contract.
`analysisVersion` is opaque metadata; `e6-v1` remains an accepted value.

Reference-coverage analysis consumes one finalized, verified projection and
its retained artifact, the immutable source-located occurrence corpus, and the
verified retained evaluation snapshot. It does not read imported PGNs or
operational evaluation rows. The evidence read/admission boundary
compares the selected population with the snapshot, verifies the ordered
projection distribution, and resolves every retained evaluation row through
the occurrence corpus. The occurrence UUID is a lookup key; its semantic
location is `(sourceRunId, qualifyingOrdinal, preMovePly)`. The declared
evaluation-player roster is retained independently of rows so a player with no
evidence is still reported.

The retained evaluation digest covers the exact reference-population tuple and
canonical evaluation rows, including occurrence UUID. It does **not** cover
the snapshot ID, declared roster, configuration, or source/engine/parser
metadata. The read path checks that digest against all rows in a consistent
view; snapshot immutability protects the separately retained metadata and
roster. Snapshots without a valid roster fail closed rather than inferring one
from surviving rows. The aggregate reconstruction remains
available, but is not the reference-coverage analysis input.

For player `i` and threshold `T` (0.30, 0.50, or 0.80), qualifying occurrences
are retained rows with `loss >= T`. `W_i(T)` is their set of distinct position
hashes; `S_i(T) = W_i(T) ∩ R(P)`, where `R(P)` is the distinct position set
of the selected projection. Coverage is `|S_i(T)| / |W_i(T)|`, or N/A
when `W_i(T)` is empty. Pooled coverage sums player numerators and denominators
before division, without unioning players or averaging their ratios.
Mean, median, minimum, and maximum use defined player coverages only.

Source-location reporting includes **all** threshold-qualified evaluation
occurrences, even when their position is outside `R(P)`. Repeated positions
remain separate canonical occurrence observations. Location groups count
occurrences with an occurrence denominator; distinct-position and unique
source-game counts are descriptive, not substitutes for coverage. The
evaluation row's game ID is not assumed to identify a source-corpus game.
No cohort analysis, new weakness detector, or causal inference is performed.
