# ChessProbe Frontend ♟️

Next.js 16.4.1 single-page application and interactive chessboard interface for ChessProbe.

---

## 1. Architecture & Tech Stack

- **Framework**: Next.js 16.4.1 (App Router), React 19.2.8, TypeScript 5
- **Styling**: Tailwind CSS 4
- **Chess Engine & Board UI**: `react-chessboard`, `chess.js`
- **Icons**: `lucide-react`
- **Testing**:
  - Unit & Component: Vitest 4, `@testing-library/react`
  - End-to-End User Journeys: Playwright
  - Exploratory Monkey Testing: Custom multi-seed transition campaign runner
  - Mutation Testing: Stryker Mutator

---

## 2. Views & Features

The main application route (`/`) serves 6 primary analytical views:
1. **Practice Puzzles (`puzzles`)**: Interactive board drilling personalized recurring mistake positions, validating player moves against Stockfish evaluations, and supporting continuation lines and tactical hints.
2. **Tactical Weaknesses (`weaknesses`)**: Categorized library of recurring opening mistakes, win/loss stats, historical game links, and on-demand Leiden community clustering (`ClusterType.STRUCTURAL` and `ClusterType.DYNAMIC`).
3. **Long Decisions (`long-decisions`)**: Identifies positions where the player spent significant time thinking, with time-control filtering and interactive practice transitions.
4. **Similar Positions (`similar-positions`)**: Dual-mode tactical search (`mode=STRUCTURAL` for pawn/piece placement vectors, `mode=DYNAMIC` for Stockfish continuation token retrieval via `DynamicInvertedIndex`).
5. **Import Games (`import`)**: Asynchronous game ingestion from Chess.com PubAPI with live status polling and resilient checkpointing.
6. **Game Analysis (`game-analysis`)**: Move-by-move engine review of individual games with mistake categorization and immediate retry drilldown.

Additionally, **`ExplorePositionModal`** provides freeform position exploration from arbitrary FENs with live Stockfish move evaluation.

---

## 3. Getting Started

### Prerequisites
- Node.js 20+
- Running ChessProbe backend (defaults to `http://localhost:8080/api`)

### Installation & Development
```bash
npm ci
npm run dev
```
The app starts on **http://localhost:3000**.

### Environment Variables
Configure in `.env.local`:
```bash
NEXT_PUBLIC_API_URL=http://localhost:8080/api
```

---

## 4. Verification & Testing Commands

```bash
# Unit and component tests (Vitest)
npm run test

# Type checking
npx tsc --noEmit

# Linter (zero warnings policy)
npm run lint

# Unused dependencies and export analysis
npm run knip

# End-to-End user journeys (Playwright)
npm run test:journeys

# Exploratory monkey testing
npm run test:monkey

# Multi-seed sustained exploration campaign runner
npm run test:monkey:campaign -- --seeds=3 --budget=25

# Frontend mutation testing (Stryker)
npm run test:mutation
```
