# Human Move Distribution Batch-Threshold Analysis

## Status

This document records the completed result of the **batch-threshold analysis experiment**.

It is an **experiment report**, not an architectural change. The current batch-local `minObservations` behavior in `HumanMoveBfsService` remains unchanged.

## Why this experiment was run

ChessEcho currently persists human-move observations in batches inside `src/main/kotlin/com/chessecho/service/HumanMoveBfsService.kt`, applying `HumanMoveBfsRequest.minObservations` at batch flush time.

The question investigated here was whether the current **per-batch** `minObservations=5` threshold discards positions or move observations that would qualify if observations were aggregated **globally across the full run** before filtering.

The goal was to preserve evidence before making any architectural decision about batching, aggregation, or persistence behavior.

## Exact run configuration

Completed run timestamp:

- `2026-08-26T09-27-59.503868638Z`

Configuration used:

- `ratingBand=1000-1200`
- `seedPlayers=duo-owl,baking_chess,aiden-lemuel2017`
- `maxQualifyingGames=10000`
- `maxGamesPerPlayer=50`
- `maxPlayers=500`
- `maxDepth=2`
- `minObservations=5`
- `batchSize=5000`

The run completed successfully with:

- `qualifyingGames=10000`
- `stopReason=MAX_QUALIFYING_GAMES`
- `totalBatches=2`

## Relevant implementation files

This experiment used the current BFS pipeline implementation and temporary instrumentation in:

- `src/main/kotlin/com/chessecho/service/HumanMoveBfsService.kt`
- `src/main/kotlin/com/chessecho/dto/HumanMoveBfsDto.kt`
- `src/main/kotlin/com/chessecho/controller/HumanMoveBfsController.kt`
- `src/test/kotlin/com/chessecho/service/HumanMoveBfsServiceTest.kt`

No `HumanMoveBfsExperimentService` or `HumanMoveBfsExperimentController` is part of the current implementation under test.

## Problem being investigated

The specific question was:

> Does the current per-batch `minObservations=5` threshold lose positions or move observations that would survive if raw observations were preserved until global aggregation?

This includes three distinct failure modes:

1. **Position loss** — a position reaches the threshold globally but is never persisted.
2. **Observation loss** — a position survives, but some raw observations are missing from persisted output.
3. **Move-distribution distortion** — a position survives, but some moves are lost or undercounted because they were individually sub-threshold within batches.

## Instrumentation methodology

Temporary instrumentation was added to `HumanMoveBfsService.runBfs()` and `processGamePgn()` to capture the raw stream of qualifying move observations **before threshold filtering and before persistence**.

For each qualifying move, the instrumentation records:

- position hash
- move SAN
- color (`WHITE` or `BLACK`)
- logical batch number
- raw occurrence count

The instrumentation writes raw maps at the end of the run via `writeInstrumentationArtifacts()`.

## Instrumentation artifacts

The completed run produced the following artifact set under local `instrumentation/` output:

- `global_positions_TIMESTAMP.txt`
  - raw `positionHash,count`
  - complete global position-frequency map before thresholding
- `global_colors_TIMESTAMP.txt`
  - raw `positionHash,moveSAN,color,count`
  - complete global move/color map before thresholding
- `batch_positions_TIMESTAMP.txt`
  - raw `batchNumber,positionHash,count`
  - per-batch position counts
- `batch_colors_TIMESTAMP.txt`
  - raw `batchNumber,positionHash,moveSAN,color,count`
  - per-batch move/color counts
- `metadata_TIMESTAMP.txt`
  - run metadata including batch size, threshold, total batches, total qualifying games, total observations, and global unique positions

These files represent **raw instrumentation data**, distinct from the persisted database contents in `human_move_distribution`.

## Integrity checks performed

`writeInstrumentationArtifacts()` validated four invariants before writing results:

1. Sum of batch-local position counts equals total global observations.
2. For each position, global position count equals the sum of its global `(position, move, color)` counts.
3. For each batch and position, batch position count equals the sum of that batch's `(position, move, color)` counts.
4. For each `(position, move, color)` key, global count equals the sum across all batches.

The completed run passed all four checks, and the same relationships were independently recomputed from the artifacts during analysis.

## Final numerical results

### Raw instrumentation totals

- Raw observations: **620,630**
- Global unique positions: **549,224**
- Positions with global frequency `>=5`: **1,939**

### Persisted database totals

- Positions retained by current per-batch threshold: **1,276**
- Persisted observations: **57,752**
- Persisted rows: **6,187**

### Measured loss

- Globally qualified positions lost by current batch-local threshold: **663**
- Share of globally qualified positions lost: **34.19%**
- Sub-threshold batch observations belonging to globally `>=5` positions: **5,207**
- Observations missing from persisted output among globally `>=5` positions: **4,499**
- Missing observations even among positions that survived: **749**

### Raw global frequency distribution (explicit low-count region)

- `1 -> 538,903`
- `2 -> 6,335`
- `3 -> 1,382`
- `4 -> 665`
- `5 -> 420`
- `6 -> 287`
- `7 -> 194`
- `8 -> 125`
- `9 -> 113`
- `10 -> 101`

Higher tail summary:

- Positions with frequency `>10`: **699**
- Maximum global frequency: **9,277**

### Threshold sensitivity from raw global distribution

- `>=1 -> 549,224`
- `>=2 -> 10,321`
- `>=3 -> 3,986`
- `>=4 -> 2,604`
- `>=5 -> 1,939`
- `>=6 -> 1,519`
- `>=7 -> 1,232`
- `>=8 -> 1,038`
- `>=10 -> 800`

## Important cross-batch patterns

The most important result is that many globally recurring positions were assembled from **cross-batch contributions** rather than qualifying inside a single batch.

Examples from the completed run:

- Global frequency `5`:
  - `2+3 -> 111`
  - `3+2 -> 111`
  - `4+1 -> 74`
  - `1+4 -> 59`
  - `5+0 -> 36`
  - `0+5 -> 29`
- Global frequency `6`:
  - `3+3 -> 77`
  - `4+2 -> 67`
  - `2+4 -> 56`
  - `5+1 -> 38`
  - `1+5 -> 29`
- Global frequency `7`:
  - `3+4 -> 47`
  - `4+3 -> 42`
  - `2+5 -> 32`
  - `6+1 -> 28`
  - `5+2 -> 26`
- Global frequency `8`:
  - `5+3 -> 29`
  - `3+5 -> 24`
  - `4+4 -> 19`

Lost globally qualified positions were concentrated in these mixed patterns, especially:

- `2+3 -> 111`
- `3+2 -> 111`
- `3+3 -> 77`
- `4+1 -> 74`
- `4+2 -> 67`
- `1+4 -> 59`
- `2+4 -> 56`
- `3+4 -> 47`
- `4+3 -> 42`
- `4+4 -> 19`

This shows the loss is not primarily explained by one-off noise.

## White / Black totals

Because the position hash includes side-to-move, each hashed position belongs to a single move color.

- WHITE observations: **314,276** (`50.64%`)
- BLACK observations: **306,354** (`49.36%`)

Loss was similarly balanced:

- Lost globally qualified WHITE positions: **337**
- Lost globally qualified BLACK positions: **326**

## Raw instrumentation data vs persisted database data

These figures must be kept distinct:

- **Raw instrumentation data** describes every qualifying move observation before threshold filtering.
- **Persisted database data** describes the output of the existing batch-local persistence algorithm after `minObservations=5` filtering.

This experiment compared those two views of the same completed run.

## Interpretation

The completed run provides evidence that the current batch-local threshold is **materially lossy**.

The strongest evidence is:

- **663** positions reached `>=5` globally but were not retained.
- That is **34.19%** of all globally qualified positions in this run.
- **5,207** sub-threshold batch observations still belonged to positions that were globally recurring.
- **4,499** observations on globally recurring positions were missing from persisted output.
- **749** of those missing observations occurred even on positions that did survive.

This indicates loss at both the **position level** and the **move-distribution level**.

## Conclusion

**Experiment conclusion:** the completed batch-threshold analysis run provides evidence that the current per-batch `minObservations=5` threshold is materially lossy with respect to both recurring positions and move observations.

This document does **not** change the architecture and does **not** claim that a replacement design is already decided. It records the evidence needed to review the current behavior before making a separate architectural decision.
