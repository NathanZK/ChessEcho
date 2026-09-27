# Human-move corpus portability (Issue #426)

This document describes the export/import/verify/materialize/finalize/purge and
cross-cohort comparison surface built on top of the Issue #423 human-move
corpus acquisition pipeline. It does not change #423's acquisition, traversal,
or legacy checkpoint/finalization behavior; the legacy
`human_move_distribution` band-wide finalizer remains untouched and continues
to operate exactly as before.

## Artifact format (v1)

A corpus artifact is a single ZIP file with exactly three `STORED`
(uncompressed) entries, in this fixed order:

1. `games.ndjson`
2. `observations.ndjson`
3. `manifest.json`

Every record (each NDJSON line, and the manifest object) is canonical JSON:
object keys are emitted in sorted (lexicographic) order, with no extra
whitespace. This makes the byte-level content of an artifact a pure function
of its logical contents, independent of database row order or map iteration
order.

### Content digest

The artifact's `contentDigest` is a SHA-256 hash computed over the
length-delimited concatenation of the three entry byte streams, in the fixed
order above:

```
digest = SHA256(
    u64be(len(games.ndjson))      || games.ndjson
    || u64be(len(observations.ndjson)) || observations.ndjson
    || u64be(len(manifest.json))       || manifest.json
)
```

`u64be` is an 8-byte big-endian unsigned length prefix. This is the same
canonical digest computed independently by both the export path and the
verifier, so any bit-level corruption or tampering (including entry
reordering, tampering with declared lengths, or byte flips caught by the
ZIP's own CRC32) is detected before any row is trusted for import.

### Manifest

`manifest.json` declares `formatVersion` (currently `1`) and `snapshotKind`
(`human-move-corpus-v1`), the per-entry SHA-256 hash and byte length of
`games.ndjson` and `observations.ndjson` (so the verifier can catch a
manifest that disagrees with the entries it describes), the source run's
identity and metadata (`sourceRunId`, `ratingBand`, `algorithmVersion`,
`sourceRevision`, the original acquisition `requestJson` and its
`requestSha256`), the `coveredPrefix` (the highest qualifying-game ordinal
included in this snapshot), the `sourceFrontier` at export time, game/
observation counts, and whether the source run had reached its bounded
frontier (`e6Eligible`) at export time.

### Position identity

`positionFen` in `observations.ndjson` is the first four space-separated FEN
fields only (board, side to move, castling rights, en passant target — no
halfmove/fullmove counters), matching the position-identity convention
already used by #423. The importer independently re-derives `positionHash`
from `positionFen` and rejects any artifact where the declared hash does not
match, or where an existing position row with the same hash has a different
canonical FEN.

## Verification (bounded, always performed before trust)

Every export, verify, and import request is checked against configurable
bounds (`chessecho.corpus.*`, see below) before any bytes are fully expanded
or any row is written: overall archive size, per-entry expanded size, total
game/observation counts, and per-NDJSON-record byte size. A trusted
`expectedDigest` must be supplied and independently recomputed; a mismatch is
a conflict (HTTP 409), never a silently-accepted artifact. Beyond digest and
structural checks, the verifier revalidates: per-game PGN hash, per-game
declared `observationTotal`/`distinctMoveCount` against the observations that
follow it, ordinal contiguity and sort order for both games and
observations, and position-hash re-derivation as above.

Export reads database cursors in fetch-size batches and writes canonical
records to temporary entry files before constructing the STORED ZIP. Archive
verification spools the bounded upload to disk, expands one entry at a time
to temporary files, and parses one bounded NDJSON record at a time; the
read-only verify endpoint does not retain decoded records. Its only
corpus-sized verifier state is fixed-size-per-game totals/count arrays,
bounded by `max-games`; individual JSON trees and serialized records are
bounded by `max-record-bytes`. The combined size of all expanded entries, not
just each entry separately, is bounded by `max-expanded-bytes`. Import
streams the HTTP request into a bounded staging file, fully verifies and
archives it before reading records for overlap checks and insertion. Export
uses a 16-row game cursor (each PGN is rejected above `max-record-bytes`)
and a 512-row observation cursor (FEN is capped at 512 bytes before
retrieval). Archive downloads are verified from disk and returned as a
stream. Temporary files are removed after each operation.

The artifact service exposes the verified, content-addressed archive as a
streaming handle rather than retaining decoded corpus lists:

```kotlin
val verified: VerifiedCorpusArtifact =
    artifactService.verifyAndArchive(stagedArchivePath, expectedDigest)

artifactService.forEachGame(verified) { game -> /* consume one game */ }
artifactService.forEachObservation(verified) { observation -> /* consume one observation */ }
```

`VerifiedCorpusArtifact` carries `digest`, `manifest`, `manifestJson`, and an
`archivePath`. The path is non-null for `verifyAndArchive(Path, ...)` results
and points to the durable content-addressed archive; stream-only `verify`
results do not promise a retained path. The handle overload confirms that it
names the expected archive and re-verifies it before invoking the consumer.
The Path overloads
`forEachGame(archivePath, consume)` and
`forEachObservation(archivePath, consume)` stream the corresponding ZIP entry
and decode one bounded NDJSON record at a time; lifecycle callers should pass
the durable `archivePath` only after `verifyAndArchive` or
`verifiedArchivePath` has verified it.

The HTTP import controller passes `MultipartFile.inputStream` directly to
`ImportService`; it does not call `.bytes`, stage the request, or create a
corpus-sized `ByteArray`. `ImportService` owns upload staging and invokes
`verifyAndArchive(stagedPath, expectedDigest)` before consuming streamed
records. A database transaction begins only after staging, complete artifact
verification, and durable archival have succeeded. A `ByteArray` overload remains only as a legacy/test-caller
convenience; the HTTP path uses the `InputStream` overload. Verification uses
one bounded record at a time plus count/total arrays indexed by game ordinal
(bounded by `max-games`).

For an import request, disk use includes the staged upload (at most
`max-archive-bytes`), expanded verifier entry files (combined at most
`max-expanded-bytes`), and the temporary canonical-ZIP comparison file (at
most `max-archive-bytes`). Publishing the durable archive uses a same-directory
hard link to the staged file, so it does not duplicate the file's data blocks
on filesystems supporting hard links. These are disk-space peaks, not
corpus-sized heap allocations. Export retains only one database row/record
at a time plus fixed-size I/O buffers. The servlet multipart resolver runs
before controller invocation: configure
`spring.servlet.multipart.max-file-size` and `max-request-size` to accommodate
the configured archive bound and multipart overhead, and a zero or small
`file-size-threshold` so the resolver spools uploads to disk. Controller-side
streaming cannot control multipart resolver buffering or its separate
request-size limits.

Disk capacity must account for temporary files as well as the durable
archive. One export's entry files occupy at most `max-expanded-bytes`, with
the temporary ZIP bounded by `max-archive-bytes`. The configured multipart
file limit defaults to 2 GiB, its request limit to 3 GiB, and its file
threshold to zero (disk spooling); if the archive bound is raised, raise the
multipart limits accordingly. For verify/import, the
multipart resolver upload file may coexist with those service files. At the
documented defaults, plan for up to approximately 10 GiB per active import
request, plus durable archives and filesystem overhead. Standalone verify
spools its input to a bounded archive file before verification, with a
similar worst-case temporary-disk budget. Concurrent requests multiply these
per-request disk and heap peaks; this code does not impose a global
concurrency semaphore.

Import overlap comparison advances the artifact record stream and an ordered
JDBC cursor together, comparing one game or observation at a time; the
game cursor fetch size is 16, and the observation cursor fetch size is 500.
Suffix insertion accumulates at most 16 games (including PGNs) or 500
observations per batch before writing and clearing the batch. This bounds
record count per batch; the configured per-record byte limit and object
overhead still determine the per-request heap ceiling.

Materialization streams the grouped `(position_hash, move_played,
observation_count)` query with a 500-row JDBC fetch size and flushes projection
inserts in batches of 500. Finalization streams sorted projection rows with a
500-row cursor while updating the distribution digest incrementally; it does
not build a full projection list in memory. The finalizer's delete of rows
below the summed-position threshold remains a database-side operation.

## Import (transactional, separate from #423 acquisition)

Import writes exclusively into the #426 tables
(`human_move_corpus_import`, `human_move_corpus_artifact_snapshot`,
`human_move_corpus_imported_game`, `human_move_corpus_imported_observation`)
and the shared `position` table (find-or-create by hash); it never mutates
#423's `human_move_corpus_run`/`human_move_corpus_game`/
`human_move_corpus_observation` tables.

Import is idempotent and overlap-safe: for a given `sourceRunId`, the
existing maximum imported ordinal is compared against the artifact's
`coveredPrefix`. Any overlapping ordinal range is checked field-for-field
against already-imported rows (a divergence is a 409 conflict, rolling back
the whole import including any new snapshot/import identity rows); only
ordinals beyond the existing maximum are inserted. After a purge, previously
archived snapshots are also compared against the overlapping prefix before
reconstruction, so a divergent artifact cannot replace historical evidence.
This handles
first import, suffix-completing a larger snapshot, exact re-import
(no-op), and reconstruction after a purge (which resets the existing
maximum to zero) without special-casing any of them.

A verified artifact is durably archived (content-addressed by digest, never
overwritten) so it can be re-fetched (`GET /corpus-artifacts/{contentDigest}`)
or reused during purge/reimport without needing the archive uploaded again.

## Projections, finalization, and checkpoint parity

A materialized projection (`human_move_corpus_projection` /
`..._projection_row`) is scoped by `(contentDigest, sourceRunId, prefixN,
ratingBand, minObservations, calculationVersion)` and is idempotent: a repeat
materialize call with the same scope is a read-only no-op and never restores
rows a prior finalize already trimmed.

Materialization computes and returns the expected retained distribution
digest from imported raw evidence using the #423 checkpoint serialization,
but leaves the projection unverified until finalization. Finalization
re-verifies the archived artifact against imported rows, recomputes the
expected digest from the raw prefix, compares the retained projection rows
against an independent raw aggregation, and then marks the projection
verified. On a target without the original #423 acquisition tables, this
archive-bound calculation is the checkpoint-equivalent proof; the source
checkpoint can also be compared externally to the returned digest. Purge
rechecks the stored projection digest against current rows and raw evidence
before deleting the latter.

Finalizing a projection applies the same `minObservations` retention rule as
the legacy per-position finalizer, scoped to exactly that one projection, and
computes `distributionSha256` using the identical serialization the #423
checkpoint service uses (`"$positionHash\t$movePlayed\t$observationCount\n"`
per row, sorted by `(positionHash, movePlayed)`, concatenated and hashed).
This guarantees that a projection materialized/finalized from an imported
artifact produces the exact same `distributionSha256` as the source run's
#423 checkpoint at an equivalent prefix, which is the basis for asserting
portability didn't change the underlying distribution.

## Purge

Purging an import is gated: it verifies the archived artifact against its
digest and imported evidence, requires that artifact to cover the entire
imported prefix, and requires a `finalized = true`, `verified = true`
projection for that same run, digest, and full prefix. Purging before any
projection has been finalized is rejected (409); once satisfied, purge
deletes the imported game/observation rows (cascading) and marks the import
row's `rawAvailable = false`. A subsequent reimport from the same (or a newer)
artifact re-derives all rows from ordinal 1, since the existing-maximum-
ordinal check sees the reset.

## Cross-cohort comparison

`POST /corpus-projections/compare` is a read-only, directional (A→B and B→A)
overlap between two independently identified *finalized* projections and two
already-computed, provenanced per-player weakness datasets (each a cohort
label, a threshold, a map of player → distinct evaluated position hashes, and
a `sourceDigest` binding those hashes to their canonical serialized bytes).
The endpoint never re-runs engine analysis and never merges the two cohorts'
underlying reference corpora; it only reports, per direction and per player,
how many of that player's declared weaknesses are also present in the other
projection's finalized position set, plus a pooled coverage figure. A
dataset's declared `cohort` must match its own projection's `ratingBand`, and
its `sourceDigest` must match a recomputed digest of the canonical
`{cohort, players, threshold}` payload; either mismatch is a 409. This
checksum is not authentication of the externally supplied evaluation.
Operators must obtain that source digest and the evaluation dataset from a
trusted channel; a caller who controls both can supply a self-consistent
forgery. The request identifies the source artifact through its projection
ID; when present, a dataset `contentDigest` must match that projection's
verified artifact. Supplied hash-algorithm identities must match the
four-field FEN SHA-256 convention.

## Configuration

```yaml
chessecho:
  corpus:
    archive-root: /var/lib/chessecho/corpus-archive
    max-archive-bytes: 2147483648    # 2 GiB
    max-expanded-bytes: 4294967296   # 4 GiB
    max-games: 100000
    max-observations: 10000000
    max-record-bytes: 4194304        # 4 MiB
```

All bounds are enforced before an artifact's bytes are trusted; there is no
unbounded default. `archive-root` is where verified, content-addressed
archives are durably stored on import/export.

## Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/admin/human-move-distribution/corpus-artifacts/export` | Export a snapshot artifact for a source run/prefix. |
| `GET` | `/api/admin/human-move-distribution/corpus-artifacts/{contentDigest}` | Fetch a previously archived artifact by digest. |
| `POST` | `/api/admin/human-move-distribution/corpus-artifacts/verify` | Verify an uploaded artifact against an expected digest without importing it. |
| `POST` | `/api/admin/human-move-distribution/corpus-artifacts/import` | Verify, archive, and transactionally import an artifact. |
| `POST` | `/api/admin/human-move-distribution/corpus-artifacts/{runId}/purge` | Purge a run's imported rows, gated on a finalized projection. |
| `POST` | `/api/admin/human-move-distribution/corpus-projections/materialize` | Materialize an isolated projection from imported rows. |
| `POST` | `/api/admin/human-move-distribution/corpus-projections/{projectionId}/finalize` | Apply retention and freeze a projection's distribution. |
| `POST` | `/api/admin/human-move-distribution/corpus-projections/compare` | Directional cross-cohort weakness overlap. |
