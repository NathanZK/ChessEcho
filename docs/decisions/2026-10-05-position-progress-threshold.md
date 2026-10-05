# Position Progress Threshold Provenance

context:
- Position Progress opens from a weakness identified using the Weaknesses evaluation-loss filter.
- Issue #76 established exact-position actual-game Progress without defining a numerical mistake threshold. The initial implementation's inline `0.8` was later documented by #484, but it was not the intended product rule.

choice:
- Classify Progress encounters using the threshold from the successful Weaknesses request that produced the selected row.
- Capture that threshold with the selection and keep it stable for the report, while reusing Weaknesses' evaluation-loss calculation and fallback behavior.
- Treat the threshold as selection context, not as an intrinsic or persisted property of a position.

ruled-out:
- A fixed Progress-wide threshold, including the historical `0.8` and any hardcoded `0.3`.
- Reading a mutable global Weaknesses threshold after the user has selected a weakness.
- Persisting threshold values on positions or changing Progress encounter, baseline, or interval membership based on weakness eligibility.
