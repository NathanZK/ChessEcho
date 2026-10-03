# Position Progress Interval Construction

context:
- Position Progress must assign imported encounters to historical training intervals, including when games arrive after a solve.
- Existing scoped occurrence and personal scheduling-event reads provide the source facts.

choice:
- Reconstruct baseline and SOLVED-gated intervals deterministically in memory on each read.
- Use `Game.playedAt` for membership and latest-included-game timestamps; leave undated games unassigned.
- Keep checkpoint UUIDs as stable observation identities and aggregate dated source occurrences by count.

ruled-out:
- SQL interval aggregation, because the existing service already loads exact-position occurrences and the interval rules are clearer in one deterministic fold.
- Persisted progress summaries, because they duplicate derivable data and require summary invalidation or idempotency handling for late imports.
