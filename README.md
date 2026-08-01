# ChessEcho ♟️

> Discover the patterns that repeat in your games.

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
Analyze Positions With Stockfish
      |
      v
Identify Recurring Weaknesses
      |
      v
Generate Personalized Puzzles
```

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

ChessEcho identifies unique board positions and groups transpositions together.

A position becomes a candidate for analysis when it has been reached at least a configurable number of times.

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

Bf5  -> 25 times
Nd7  -> 15 times
```

---

## Engine Analysis

Candidate positions are analyzed asynchronously using Stockfish.

Each unique position is analyzed once per engine configuration.

Engine analysis is shared globally between users.

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
                         +----------------------+
                         |  Kotlin Spring Boot  |
                         |       API            |
                         |                      |
                         | REST API             |
                         | User Features       |
                         | Job Management      |
                         +----------+-----------+
                                    |
                                    |
                              PostgreSQL
                                    |
                 +------------------+------------------+
                 |                                     |
                 v                                     v
        User Game Data                         Engine Analysis Cache


                                    |
                                    v


                         +----------------------+
                         | Python Analysis      |
                         | Worker               |
                         |                      |
                         | python-chess         |
                         | Stockfish            |
                         +----------------------+
```

---

# Technology Stack

## Backend API

### Kotlin + Spring Boot

Spring Boot is used as the backend framework.

Responsibilities:

* REST API
* User management
* Chess account integration
* Game import orchestration
* Background job scheduling
* Weakness calculation
* Puzzle delivery

---

## Database

### PostgreSQL

Stores:

* Users
* Chess accounts
* Games
* Positions
* Position occurrences
* Engine analysis results
* Puzzle history

---

## Chess Analysis Worker

### Python + python-chess

A separate Python service handles chess-specific processing.

Responsibilities:

* PGN parsing
* Move replay
* FEN generation
* Legal move validation
* SAN/UCI conversion
* Stockfish communication

python-chess is used because it provides a mature toolkit for chess operations.

---

## Chess Engine

### Stockfish

Used for:

* Position evaluation
* Best move calculation
* Alternative move discovery

---

# Domain Model

```text
User
 |
 |
 v
ChessAccount
 |
 |
 v
Game
 |
 |
 v
PositionOccurrence
 |
 |
 v
Position


Position
 |
 |
 v
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

A position is global and shared across users.

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
GET /api/weaknesses
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

* Engine analysis is shared.
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
