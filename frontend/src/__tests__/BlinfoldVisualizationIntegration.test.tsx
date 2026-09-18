import { describe, it, expect, beforeEach } from 'vitest';
import { Chess } from 'chess.js';
import {
  BlinfoldGameState,
  createBlinfoldGameState,
  applyPlayerMove,
  applyChessEchoMove,
  resetBlinfoldGame,
  getAccumulatedPosition,
  rebuildPositionFromHistory,
} from '../utils/blindfoldGameState';

describe('BlinfoldVisualizationIntegration', () => {
  let initialFen: string;

  beforeEach(() => {
    initialFen = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';
  });

  describe('Full blindfold flow', () => {
    it('should execute 3-iteration flow correctly', () => {
      let state = createBlinfoldGameState(initialFen);

      // Iteration 1
      let result = applyPlayerMove(state, 'e4', state.currentFen) as BlinfoldGameState;
      expect(result).not.toHaveProperty('error');
      expect((result as BlinfoldGameState).currentTurn).toBe('CHESSECHO');
      state = result as BlinfoldGameState;

      result = applyChessEchoMove(state, 'c5', state.currentFen) as BlinfoldGameState;
      expect(result).not.toHaveProperty('error');
      expect((result as BlinfoldGameState).currentTurn).toBe('PLAYER');
      state = result as BlinfoldGameState;

      // Iteration 2
      result = applyPlayerMove(state, 'd4', state.currentFen) as BlinfoldGameState;
      expect(result).not.toHaveProperty('error');
      state = result as BlinfoldGameState;

      result = applyChessEchoMove(state, 'cxd4', state.currentFen) as BlinfoldGameState;
      expect(result).not.toHaveProperty('error');
      state = result as BlinfoldGameState;

      // Iteration 3
      result = applyPlayerMove(state, 'Nf3', state.currentFen) as BlinfoldGameState;
      expect(result).not.toHaveProperty('error');
      state = result as BlinfoldGameState;

      result = applyChessEchoMove(state, 'Nf6', state.currentFen) as BlinfoldGameState;
      expect(result).not.toHaveProperty('error');
      state = result as BlinfoldGameState;

      expect(state.moveCount).toBe(6);
      expect(state.moveHistory).toHaveLength(6);
      expect(state.currentTurn).toBe('PLAYER');
    });
  });

  describe('Notation exchange display', () => {
    it('should display player and ChessEcho moves correctly', () => {
      let state = createBlinfoldGameState(initialFen);

      let result = applyPlayerMove(state, 'e4', state.currentFen) as BlinfoldGameState;
      state = result as BlinfoldGameState;
      expect(state.lastPlayerMove?.san).toBe('e4');
      expect(state.moveHistory[0].san).toBe('e4');

      result = applyChessEchoMove(state, 'c5', state.currentFen) as BlinfoldGameState;
      state = result as BlinfoldGameState;
      expect(state.lastChessEchoMove?.san).toBe('c5');
      expect(state.moveHistory.map((m) => m.san)).toEqual(['e4', 'c5']);
    });

    it('should maintain separate tracking of moves', () => {
      let state = createBlinfoldGameState(initialFen);

      let result = applyPlayerMove(state, 'e4', state.currentFen) as BlinfoldGameState;
      state = result as BlinfoldGameState;

      result = applyChessEchoMove(state, 'c5', state.currentFen) as BlinfoldGameState;
      state = result as BlinfoldGameState;

      result = applyPlayerMove(state, 'd4', state.currentFen) as BlinfoldGameState;
      state = result as BlinfoldGameState;

      expect(state.lastPlayerMove?.san).toBe('d4');
      expect(state.lastChessEchoMove?.san).toBe('c5');
    });

    it('should accumulate notation history', () => {
      let state = createBlinfoldGameState(initialFen);

      const moves = [
        { player: 'e4', chessecho: 'c5' },
        { player: 'd4', chessecho: 'cxd4' },
        { player: 'Nf3', chessecho: 'Nf6' },
      ];

      for (const { player, chessecho } of moves) {
        let result = applyPlayerMove(state, player, state.currentFen) as BlinfoldGameState;
        state = result as BlinfoldGameState;

        result = applyChessEchoMove(state, chessecho, state.currentFen) as BlinfoldGameState;
        state = result as BlinfoldGameState;
      }

      const expected = ['e4', 'c5', 'd4', 'cxd4', 'Nf3', 'Nf6'];
      expect(state.moveHistory.map((m) => m.san)).toEqual(expected);
    });
  });

  describe('Board reveal functionality', () => {
    it('should show accumulated position when revealed', () => {
      let state = createBlinfoldGameState(initialFen);
      const moves = ['e4', 'c5', 'd4', 'cxd4', 'Nf3', 'Nf6'];

      for (const move of moves) {
        let result: BlinfoldGameState | { error: string };
        if (state.currentTurn === 'PLAYER') {
          result = applyPlayerMove(state, move, state.currentFen) as BlinfoldGameState;
        } else {
          result = applyChessEchoMove(state, move, state.currentFen) as BlinfoldGameState;
        }
        state = result as BlinfoldGameState;
      }

      const revealed = { ...state, isVisible: true };
      const position = getAccumulatedPosition(revealed);
      expect(position.moveCount).toBe(6);
    });

    it('should rebuild exact position from history on reveal', () => {
      let state = createBlinfoldGameState(initialFen);
      const moves = ['e4', 'c5', 'd4', 'cxd4', 'Nf3', 'Nf6', 'Nc3', 'Nc6'];

      for (const move of moves) {
        let result: BlinfoldGameState | { error: string };
        if (state.currentTurn === 'PLAYER') {
          result = applyPlayerMove(state, move, state.currentFen) as BlinfoldGameState;
        } else {
          result = applyChessEchoMove(state, move, state.currentFen) as BlinfoldGameState;
        }
        state = result as BlinfoldGameState;
      }

      const reconstructed = rebuildPositionFromHistory(initialFen, state.moveHistory);
      expect(reconstructed).toBe(state.currentFen);
    });

    it('should display correct move count on reveal', () => {
      let state = createBlinfoldGameState(initialFen);
      const moves = ['e4', 'c5', 'd4', 'cxd4'];

      for (const move of moves) {
        let result: BlinfoldGameState | { error: string };
        if (state.currentTurn === 'PLAYER') {
          result = applyPlayerMove(state, move, state.currentFen) as BlinfoldGameState;
        } else {
          result = applyChessEchoMove(state, move, state.currentFen) as BlinfoldGameState;
        }
        state = result as BlinfoldGameState;
      }

      const position = getAccumulatedPosition(state);
      expect(position.moveCount).toBe(4);
    });
  });

  describe('Reset functionality', () => {
    it('should clear all state after N moves', () => {
      let state = createBlinfoldGameState(initialFen);
      const moves = ['e4', 'c5', 'd4', 'cxd4', 'Nf3', 'Nf6'];

      for (const move of moves) {
        let result: BlinfoldGameState | { error: string };
        if (state.currentTurn === 'PLAYER') {
          result = applyPlayerMove(state, move, state.currentFen) as BlinfoldGameState;
        } else {
          result = applyChessEchoMove(state, move, state.currentFen) as BlinfoldGameState;
        }
        state = result as BlinfoldGameState;
      }

      expect(state.moveCount).toBe(6);
      const reset = resetBlinfoldGame(state, initialFen);
      expect(reset.currentFen).toBe(initialFen);
      expect(reset.moveHistory).toHaveLength(0);
      expect(reset.moveCount).toBe(0);
      expect(reset.currentTurn).toBe('PLAYER');
    });

    it('should reset after odd number of moves', () => {
      let state = createBlinfoldGameState(initialFen);
      const moves = ['e4', 'c5', 'd4'];

      for (const move of moves) {
        let result: BlinfoldGameState | { error: string };
        if (state.currentTurn === 'PLAYER') {
          result = applyPlayerMove(state, move, state.currentFen) as BlinfoldGameState;
        } else {
          result = applyChessEchoMove(state, move, state.currentFen) as BlinfoldGameState;
        }
        state = result as BlinfoldGameState;
      }

      const reset = resetBlinfoldGame(state, initialFen);
      expect(reset.moveCount).toBe(0);
      expect(reset.currentTurn).toBe('PLAYER');
    });
  });

  describe('Provider error handling', () => {
    it('should handle null ChessEcho response', () => {
      let state = createBlinfoldGameState(initialFen);

      const result: BlinfoldGameState | { error: string } = applyPlayerMove(state, 'e4', state.currentFen);
      state = result as BlinfoldGameState;

      state = { ...state, isLoading: false, isFailed: true, lastChessEchoMove: null };
      expect(state.isFailed).toBe(true);

      const reset = resetBlinfoldGame(state, initialFen);
      expect(reset.isFailed).toBe(false);
      expect(reset.moveCount).toBe(0);
    });

    it('should preserve history before failure', () => {
      let state = createBlinfoldGameState(initialFen);

      let result = applyPlayerMove(state, 'e4', state.currentFen) as BlinfoldGameState;
      state = result as BlinfoldGameState;

      result = applyChessEchoMove(state, 'c5', state.currentFen) as BlinfoldGameState;
      state = result as BlinfoldGameState;

      result = applyPlayerMove(state, 'd4', state.currentFen) as BlinfoldGameState;
      state = result as BlinfoldGameState;

      state = { ...state, isFailed: true };
      expect(state.moveHistory).toHaveLength(3);
      expect(state.moveHistory.map((m) => m.san)).toEqual(['e4', 'c5', 'd4']);
    });
  });

  describe('Exit/cancel consistency', () => {
    it('should maintain state during exit', () => {
      let state = createBlinfoldGameState(initialFen);
      const moves = ['e4', 'c5', 'd4', 'cxd4'];

      for (const move of moves) {
        let result: BlinfoldGameState | { error: string };
        if (state.currentTurn === 'PLAYER') {
          result = applyPlayerMove(state, move, state.currentFen) as BlinfoldGameState;
        } else {
          result = applyChessEchoMove(state, move, state.currentFen) as BlinfoldGameState;
        }
        state = result as BlinfoldGameState;
      }

      const exitState = { ...state };
      expect(exitState.moveHistory).toHaveLength(4);
      expect(exitState.moveCount).toBe(4);
      expect(exitState.currentTurn).toBe('PLAYER');
    });

    it('should allow clean reset after cancel', () => {
      let state = createBlinfoldGameState(initialFen);

      const result: BlinfoldGameState | { error: string } = applyPlayerMove(state, 'e4', state.currentFen);
      state = result as BlinfoldGameState;

      const reset = resetBlinfoldGame(state, initialFen);
      expect(reset.currentFen).toBe(initialFen);
      expect(reset.moveCount).toBe(0);
    });
  });

  describe('State consistency', () => {
    it('should maintain FEN consistency across moves', () => {
      let state = createBlinfoldGameState(initialFen);
      const moves = ['e4', 'c5', 'd4', 'cxd4', 'Nf3', 'Nf6', 'Nc3', 'Nc6', 'Nxd4', 'a6'];

      for (const move of moves) {
        let result: BlinfoldGameState | { error: string };
        if (state.currentTurn === 'PLAYER') {
          result = applyPlayerMove(state, move, state.currentFen) as BlinfoldGameState;
        } else {
          result = applyChessEchoMove(state, move, state.currentFen) as BlinfoldGameState;
        }
        state = result as BlinfoldGameState;
      }

      // Verify FENs match at each step
      for (let i = 0; i < state.moveHistory.length; i++) {
        const fen = rebuildPositionFromHistory(initialFen, state.moveHistory.slice(0, i + 1));
        expect(fen).toBe(state.moveHistory[i].fen);
      }
    });

    it('should maintain correct move order', () => {
      let state = createBlinfoldGameState(initialFen);
      const expectedMoves = ['e4', 'c5', 'd4', 'cxd4', 'Nf3'];

      for (const move of expectedMoves) {
        let result: BlinfoldGameState | { error: string };
        if (state.currentTurn === 'PLAYER') {
          result = applyPlayerMove(state, move, state.currentFen) as BlinfoldGameState;
        } else {
          result = applyChessEchoMove(state, move, state.currentFen) as BlinfoldGameState;
        }
        state = result as BlinfoldGameState;
      }

      expect(state.moveHistory.map((m) => m.san)).toEqual(expectedMoves);
      expect(state.moveCount).toBe(expectedMoves.length);
    });
  });

  describe('Arbitrary depth', () => {
    it('should support 10+ moves without limits', () => {
      let state = createBlinfoldGameState(initialFen);
      let moveCount = 0;

      while (moveCount < 20) {
        const chess = new Chess(state.currentFen);
        if (chess.isGameOver()) break;

        const legalMoves = chess.moves();
        const move = legalMoves[0];

        let result: BlinfoldGameState | { error: string };
        if (state.currentTurn === 'PLAYER') {
          result = applyPlayerMove(state, move, state.currentFen) as BlinfoldGameState;
        } else {
          result = applyChessEchoMove(state, move, state.currentFen) as BlinfoldGameState;
        }

        if (result && !('error' in result)) {
          state = result as BlinfoldGameState;
          moveCount++;
        }
      }

      expect(state.moveCount).toBeGreaterThanOrEqual(10);
      expect(state.moveHistory.length).toBeGreaterThanOrEqual(10);
    });
  });
});
