# ChessEcho ♟️

> Discover the patterns that repeat in your games.

Traditional chess analysis tells you **what went wrong in one game**.

**ChessEcho tells you what keeps going wrong across hundreds of games.**

ChessEcho is a personalized chess improvement platform that analyzes a player's game history to discover recurring positions, repeated mistakes, and decision-making patterns.

Traditional chess analysis tools answer:

> "Where did I make a mistake in this game?"

ChessEcho answers:

> "Which situations do I repeatedly reach, and where do I repeatedly make weaker decisions?"

The goal is not to memorize engine moves. The goal is to understand and improve recurring decisions.

---
# Core Idea

A single mistake may be accidental.

A mistake repeated across dozens of games is a pattern.

ChessEcho analyzes a player's complete game history to find those patterns:


```text
Import Games
      |
      v
Replay Games
      |
      v
Find Frequently Reached Positions
      |
      v
Analyze Candidate Positions
      |
      v
Identify Recurring Weaknesses
      |
      v
Generate Personalized Puzzles
```

---

## Why This Is Different

Suppose you reach the following middlegame position 27 times over the course of a year.

Perhaps you choose:

- Bf5 18 times
- Nd7 9 times

Stockfish may determine that Bf5 consistently loses 1.2 pawns compared to Nd7.

Most chess tools would show this mistake 18 separate times.

ChessEcho recognizes that it is the **same recurring decision** and teaches the position once.

The goal is to fix habits, not games.

---

# Features (MVP)

## Game Import

ChessEcho imports games from Chess.com.

Supported filters:

* Time control

  * Rapid
  * Classical
  * Blitz
  * Bullet
* Color

  * White
  * Black
  * Both

The system avoids duplicate imports.

---

## Position Detection

Every imported game is replayed move by move.

ChessEcho identifies unique board positions using their complete chess state.

Two positions are considered identical only if all of the following are the same:

* Piece placement
* Side to move
* Castling rights
* En passant availability

This means positions reached through different move orders (transpositions) are grouped together **only when they are legally identical**.

For example, two positions with identical piece placement but different castling rights are treated as different positions and therefore produce different hashes.

A position becomes a candidate for engine analysis once it has been reached at least a configurable number of times.

Default:

```text
minimum occurrence threshold = 5
```

Example:

```text
Position A

Reached:
40 times

Moves played:

Bf5 -> 25 times
Nd7 -> 15 times
```


## Engine Analysis

Candidate positions are analyzed asynchronously in the background using Stockfish.

Each unique position is analyzed at most once per engine configuration.

Subsequent requests reuse the cached engine analysis.
Example:

```text
Position Hash: ABC123

Already analyzed?

Yes:
    Reuse existing analysis

No:
    Analyze with Stockfish
```

The system stores raw engine facts:

* Best move
* Evaluation
* Alternative acceptable moves
* Evaluation of historical moves

Derived statistics are calculated from these facts.

---

## Recurring Weakness Detection

ChessEcho compares the moves a player historically made against engine analysis.

Example:

```text
Position:
Reached 30 times

Stockfish:

Nd7
Evaluation: +0.4


Player history:

Nd7
Played: 20 times
Loss: 0.0

Bf5
Played: 10 times
Loss: 1.3
```

This position becomes a potential weakness because the player repeatedly chooses a significantly weaker move.

### Priority Weighting

To ensure that recent, recurring habits surface before stale or one-off mistakes, ChessEcho computes priority using two factors:

1. **Recency time-decay weight** — each mistake is weighted by how recently it occurred:
   * `weight = max(0.1, 1.0 - (daysSinceGame / 365))`
   * A mistake made today scores 1.0; one made a year ago scores 0.1.

2. **Mistake rate** — the final priority is multiplied by how often the mistake happens relative to how often the position is reached:
   * `mistakeRate = mistakeCount / timesReached`
   * A position reached 75 times with only 1 mistake (1.3%) is far less urgent than one reached 10 times with 8 mistakes (80%).

Full formula:

```text
priority = sum(evalLoss × weight) × (mistakeCount / timesReached)
```

This ensures that positions where you **consistently** make mistakes rank higher than positions where you had a single bad day.

### Game URLs

When returning weaknesses, ChessEcho limits the payload to the **10 most recent distinct game URLs** where the mistake occurred. The platform handles proper link generation (for Chess.com and Lichess) so users can immediately review their historical games in the browser.

---

## Configurable Evaluation Threshold

Different players care about different levels of mistakes.

ChessEcho allows the evaluation-loss threshold to be configured.

Examples:

Beginner:

```text
Ignore losses below 0.8 pawns
```

Advanced player:

```text
Ignore losses below 0.2 pawns
```

Changing this setting does not require new Stockfish analysis.

---

## Personalized Puzzles

ChessEcho creates puzzles from positions where the player historically struggled.

The goal is not:

> "Find the one exact Stockfish move."

Instead:

> "Find a strong move that avoids your recurring mistake."

Multiple moves can be accepted when they are within the acceptable evaluation range.

Example:

```text
Strong moves:

Nd7   +0.40
e6    +0.35
Bc4   +0.30
```

All may be considered correct.

---

## Progress Tracking

As new games are imported, ChessEcho tracks whether recurring weaknesses improve.

Example:

Before:

```text
Position:

Reached: 25 times

Mistake rate:
72%
```

Later:

```text
New games:

Reached: 15 additional times

Mistake rate:
20%
```

The user can see improvement over time.

---

# Architecture

```text
                      +--------------------------------------+
                      |      Kotlin + Spring Boot            |
                      |--------------------------------------|
                      | REST API                             |
                      | Chess.com Integration                |
                      | Game Import                          |
                      | Background Analysis Jobs             |
                      | Position Detection                   |
                      | Weakness Calculation                 |
                      | Puzzle Generation                    |
                      | Stockfish Integration                |
                      | kchesslib                            |
                      +----------------+---------------------+
                                       |
                    +------------------+------------------+
                    |                                     |
                    v                                     v
               PostgreSQL                           Stockfish
```

---

# Technology Stack

## Backend

### Kotlin + Spring Boot

Spring Boot is the primary application framework.

Responsibilities:

* REST API
* User management
* Chess.com integration
* Game import
* Position detection
* Background analysis jobs
* Weakness calculation
* Puzzle generation
* Stockfish integration

---

## Database

### PostgreSQL

Stores:

* Users
* Chess accounts
* Games
* Positions
* Position occurrences
* Engine analysis
* Puzzle history

---

## Chess Library

### kchesslib

Used for:

* PGN parsing
* Move replay
* Board state reconstruction
* FEN generation
* Position hashing
* Legal move validation

---

## Chess Engine

### Stockfish

Used for:

* Position evaluation
* Best move calculation
* Alternative move discovery

Engine analysis is cached globally so that a position is analyzed only once and reused across all users.

---

## Infrastructure

### Docker

ChessEcho uses Docker to provide consistent local development and deployment environments.

The MVP deployment consists of:

* Spring Boot application
* PostgreSQL database

Stockfish is bundled with the application and invoked directly during analysis.

No separate analysis worker or message broker is required for the MVP.


# Domain Model



```text
                 User
                   │
                   │
                   ▼
             ChessAccount
                   │
                   │
                   ▼
                 Game
                   │
          replay move-by-move
                   │
                   ▼
         PositionOccurrence
                   │
                   ▼
              Position
                   │
                   ▼
           EngineAnalysis
```

---

# Entities

## User

Represents a ChessEcho user.

Contains:

* User account data
* Connected chess accounts

---

## ChessAccount

Represents an external chess platform account.

Contains:

* Platform
* Username
* Synchronization status

---

## Game

Represents an imported chess game.

Contains:

* Players
* Result
* Time control
* PGN
* Date

---

## Position

Represents a unique board state.

Contains:

* Position hash
* FEN representation

The position hash is derived from the complete chess state, including:

- Piece placement
- Side to move
- Castling rights
- En passant availability

Engine analysis is cached globally and reused across users.

---

## PositionOccurrence

Represents a specific time a user reached a position.

Contains:

* User
* Game
* Position
* Ply number
* Move played
* Player color

---

## EngineAnalysis

Represents Stockfish analysis of a position.

Shared globally.

Contains:

* Best move
* Evaluation
* Acceptable moves
* Evaluation loss of alternatives

---

# API Overview

## Import Games

```http
POST /api/games/import
```

Starts an asynchronous import job.

---

## Job Status

```http
GET /api/jobs/{id}
```

Returns:

* QUEUED
* PROCESSING
* COMPLETED
* FAILED

---

## Get Weaknesses

```http
GET /api/positions/weaknesses
```

Returns ranked recurring weaknesses.

Example:

```json
[
  {
    "position": "...",
    "timesReached": 25,
    "mistakeCount": 12,
    "averageLoss": 1.1
  }
]
```

---

## Generate Puzzle

```http
GET /api/puzzles/{positionId}
```

Returns a personalized puzzle from a recurring weakness.

---

# Design Principles

## Analyze Patterns, Not Individual Games

A single mistake is noise.

Repeated mistakes reveal habits.

---

## Separate Chess Knowledge From Player Behavior

Engine analysis is universal.

Player weaknesses are personal.

Therefore:

* Engine analysis is cached and shared.
* Player history remains user-specific.

---

## Store Facts, Compute Insights

The system stores raw information:

* Positions
* Moves
* Evaluations

Higher-level concepts such as:

* Priority
* Weakness ranking
* Difficulty

are computed from stored facts.

---

## Improve Understanding, Not Engine Imitation

ChessEcho is not designed to make players memorize engine moves.

It is designed to help players recognize recurring situations and make stronger decisions.
