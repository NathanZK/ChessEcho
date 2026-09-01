# Chess.com Opening Metadata Investigation

## Status and decision

This investigation records the behavior observed from Chess.com's PubAPI and
defines how opening metadata may fit into ChessEcho without changing position
identity or implementing opening-aware analysis.

**Decision:** treat Chess.com opening metadata as optional, game-scoped source
context. Do not attach it to `Position`, include it in the position hash, or use
it as analytical truth. No schema or import change is warranted yet because the
current `Game.pgn` already preserves both the ECO code and the Chess.com opening
URL. A future presentation feature can derive those values from the stored PGN
and aggregate them across the games supporting a weakness.

## What the payload contains

Chess.com's documentation defines the monthly archive `eco` property as a "URL
pointing to ECO opening (if available)," not as the standard ECO code. A
standard game observed on 2026-09-01 contained:

```json
{
  "eco": "https://www.chess.com/openings/Closed-Sicilian-Defense-Grand-Prix-Attack-3...g6-4.Bc4-Bg7-5.Nf3"
}
```

The same game's PGN contained two distinct tags:

```pgn
[ECO "B23"]
[ECOUrl "https://www.chess.com/openings/Closed-Sicilian-Defense-Grand-Prix-Attack-3...g6-4.Bc4-Bg7-5.Nf3"]
```

The terms must therefore remain distinct:

| Value | Meaning | Suitable use |
|---|---|---|
| JSON `eco` / PGN `ECOUrl` | Chess.com opening page URL for the game | Opaque source URL and optional game context |
| PGN `ECO` | Broad Encyclopaedia of Chess Openings code | Optional game context or coarse grouping |
| Four-field FEN hash | Exact legal position identity in ChessEcho | Canonical position deduplication and analysis |

The API does not provide a separate human-readable opening-name property.

## Live API observations

The investigation queried complete monthly archives from the documented PubAPI
endpoint and considered only games with `rules == "chess"`. This matches the
normal standard-game path through `GameImportService`, whose compatibility is
discussed below.

| Archive | Standard games | JSON `eco` present | Equal to PGN `ECOUrl` | Valid PGN `ECO` tag | URLs containing move tokens |
|---|---:|---:|---:|---:|---:|
| `hikaru/2024/01` | 1,045 | 1,045 | 1,045 | 1,045 | 942 |
| `magnuscarlsen/2023/12` | 142 | 142 | 142 | 142 | 128 |
| `gothamchess/2024/01` | 112 | 112 | 112 | 112 | 97 |
| **Total** | **1,299** | **1,299** | **1,299** | **1,299** | **1,167** |

This is strong evidence that the field is consistently populated for these
ordinary standard-chess archives, but it is not an availability guarantee. The
official contract explicitly says "if available," and ChessEcho should accept a
missing or malformed value.

Other observed properties matter to the design:

- The URL is much more specific than the ECO code. In the Hikaru sample, `B06`
  covered 143 games and 62 distinct opening URLs; `B23` covered 51 games and 32
  distinct URLs. The ECO code is not a unique opening-line identifier.
- 1,167 of 1,299 URLs included move-number/move tokens. Examples include
  `...-3...g6-4.Bc4-Bg7-5.Nf3`; the URL often represents a detected sequence,
  not merely a family name.
- Labels can explicitly describe move order, for example
  `Queens-Pawn-Opening-Keres-Transpositional-Variation-3...c5`. Other API
  values include terms such as `Accelerated-Move-Order`.
- A populated field does not guarantee useful specificity. `.../Undefined` was
  present in the sample.

These observations are a point-in-time sample, not a claim that Chess.com's
taxonomy or slugs are stable.

## Parsing reliability

### Reliable enough to extract

The following can be extracted defensively as nullable game metadata:

- `[ECO]` from the PGN when it matches `[A-E][0-9]{2}`;
- `[ECOUrl]`, or the equivalent JSON `eco` value during a new import, when it is
  an HTTPS URL on `www.chess.com` under `/openings/`.

The URL should be retained as an opaque source value. Its path is not a
documented stable identifier.

### Not reliable to extract

An exact display name cannot be reconstructed reliably from the URL slug.
Replacing hyphens with spaces loses punctuation and structure. For example,
Chess.com's HTML title for:

```text
Queens-Pawn-Opening-Keres-Transpositional-Variation-3...c5
```

is:

```text
Queen's Pawn Opening: Keres, Transpositional Variation
```

The apostrophe, colon, comma, and the boundary between the name and move
qualification are absent from the slug. Similar ambiguity exists between words
that naturally contain hyphens and hyphens used as separators.

Fetching the opening page and scraping its HTML title would recover a current
display name, but that page is not a PubAPI contract. It adds one request per
distinct opening URL and can change independently. ChessEcho should not make
imports depend on it.

A future UI may use a best-effort, non-persisted slug label if its limitations
are acceptable, but polished names require a provider-supported name field or a
separately versioned opening catalog. Neither is required for the current
context-only goal.

## Canonical boundaries

### Game

The classification describes the imported game's recognized opening line. It
belongs to the game as source provenance. It may reflect moves later than a
particular weakness occurrence, so it must not assert that every position in
the game "is" that opening.

The current model already preserves the source snapshot:

1. `ChessComClient.fetchMonthlyGames()` returns each raw game map.
2. `GameImportService` selects the PGN and stores it in `Game.pgn`.
3. That PGN contains both `[ECO]` and `[ECOUrl]` in all sampled games.

Therefore the narrowest design is to derive optional game opening context from
`Game.pgn` when a future use case needs it. This requires no migration and also
works for games already imported.

If repeated query-time parsing later becomes measurable overhead, materialize a
one-to-one, provider-aware game enrichment containing the raw ECO code and
source URL plus a parser/catalog version. Backfill it from stored PGN. Do not
depend on archive refetching: completed past archives are intentionally skipped,
and existing game URLs are deduplicated by the import pipeline.

### Position

`Position` remains the global four-field FEN identity: piece placement, side to
move, castling rights, and en passant state. Engine analysis remains attached
to that identity.

No opening code, URL, family, or display name belongs on `Position`. The same
position can be reached through different move orders, while the same opening
label can cover many different positions. Storing a single label on `Position`
would discard that many-to-many provenance and make provider taxonomy look
canonical.

### PositionOccurrence

`PositionOccurrence` already links a canonical position to a game and ply.
Opening context is reachable through that game, so copying it onto every
occurrence would duplicate data. The ply remains useful because it tells a
future presenter whether the occurrence preceded or followed the moves encoded
in a game-level opening URL.

## Transpositions and move order

ChessEcho should continue merging legally identical four-field FENs regardless
of move order. Opening context must not affect that merge.

When occurrences of one weakness come from games carrying different Chess.com
labels:

- preserve all game-level labels as evidence;
- group only for presentation, never for identity or engine analysis;
- count distinct games rather than raw occurrences so repetition within one
  game does not overweight a label;
- show a dominant label only as "Most common imported opening" or equivalent;
- fall back to "Multiple opening contexts" (or no opening label) when evidence
  is mixed, missing, `Undefined`, or tied.

The exact consensus threshold is a future UX decision and should be tested with
real weakness distributions. It is deliberately not part of the domain model.

## Future weakness naming and context

Opening metadata is useful primarily as descriptive context for a weakness that
ChessEcho has already identified by recurring position and engine loss. It
should not create, merge, rank, or suppress weaknesses.

A future weakness response could expose an optional context object derived from
the weakness's supporting games, for example:

```json
{
  "label": "Queen's Gambit Declined: Semi-Slav Defense",
  "ecoCode": "D43",
  "sourceUrl": "https://www.chess.com/openings/...",
  "supportingGameCount": 7,
  "totalContextGameCount": 9,
  "source": "CHESS_COM"
}
```

The UI should keep the concrete position as the subject and qualify the label,
for example **"Weakness in Queen's Gambit Declined..."** or **"Most often
reached from..."**. It should retain the existing game links so users can inspect
the evidence. Search may later index the derived context, but search results
must still resolve to the canonical position-based weakness.

## Recommendation summary

1. Make no persistence or import-pipeline change for this investigation.
2. Treat `[ECO]` and `[ECOUrl]` in stored `Game.pgn` as optional, provider-owned
   game metadata.
3. Parse them defensively only when a concrete context UX is implemented.
4. Never use the URL slug, ECO code, or display label as position identity.
5. Aggregate distinct-game context at weakness read time and present uncertainty
   instead of forcing one label across transpositions.
6. Introduce typed game enrichment only if query/search needs justify
   materialization; backfill it from PGN and version any name-normalization
   scheme.

## Sources

- [Chess.com Published Data API documentation](https://www.chess.com/news/view/published-data-api#complete-monthly-archives)
- Live monthly archive endpoints sampled on 2026-09-01:
  [Hikaru, January 2024](https://api.chess.com/pub/player/hikaru/games/2024/01),
  [Magnus Carlsen, December 2023](https://api.chess.com/pub/player/magnuscarlsen/games/2023/12),
  and [GothamChess, January 2024](https://api.chess.com/pub/player/gothamchess/games/2024/01)
- Current ChessEcho import and domain implementation:
  `ChessComClient`, `GameImportService`, `GameParserService`, `Game`,
  `Position`, and `PositionOccurrence`
