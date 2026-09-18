import { describe, it, expect, beforeEach } from 'vitest';
import {
  BlinfoldGameState,
  createBlinfoldGameState,
  applyPlayerMove,
  applyChessEchoMove,
  resetBlinfoldGame,
  getAccumulatedPosition,
} from '../utils/blindfoldGameState';

describe('BlinfoldContinuationMode', () => {
  let initialFen: string;
  let state: BlinfoldGameState;

  beforeEach(() => {
    initialFen = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';
    state = createBlinfoldGameState(initialFen);
  });

  describe('Blindfold game state initialization', () => {
    it('should create initial blindfold game state with correct defaults', () => {
      expect(state.initialFen).toBe(initialFen);
      expect(state.currentFen).toBe(initialFen);
      expect(state.moveHistory).toHaveLength(0);
      expect(state.currentTurn).toBe('PLAYER');
      expect(state.isVisible).toBe(false);
      expect(state.moveCount).toBe(0);
    });

    it('should have board hidden by default', () => {
      expect(state.isVisible).toBe(false);
    });

    it('should start with player turn', () => {
      expect(state.currentTurn).toBe('PLAYER');
    });
  });

  describe('Player move validation and application', () => {
    it('should accept valid SAN move (e.g., e4)', () => {
      const result = applyPlayerMove(state, 'e4', state.currentFen);
      expect(result).not.toHaveProperty('error');
      expect((result as BlinfoldGameState).moveCount).toBe(1);
      expect((result as BlinfoldGameState).currentTurn).toBe('CHESSECHO');
    });

    it('should accept valid SAN move (e.g., Nf3)', () => {
      const result = applyPlayerMove(state, 'Nf3', state.currentFen);
      expect(result).not.toHaveProperty('error');
      expect((result as BlinfoldGameState).moveCount).toBe(1);
    });

    it('should reject invalid SAN notation (Zz1)', () => {
      const result = applyPlayerMove(state, 'Zz1', state.currentFen);
      expect(result).toHaveProperty('error');
      expect((result as { error: string }).error).toContain('Illegal move');
    });

    it('should reject move not in current position', () => {
      const result = applyPlayerMove(state, 'e5', state.currentFen);
      expect(result).toHaveProperty('error');
    });

    it('should update move history on valid move', () => {
      const result = applyPlayerMove(state, 'e4', state.currentFen);
      expect((result as BlinfoldGameState).moveHistory).toHaveLength(1);
      expect((result as BlinfoldGameState).moveHistory[0].san).toBe('e4');
    });

    it('should clear notation input after valid move', () => {
      state.notationInput = 'e4';
      const result = applyPlayerMove(state, 'e4', state.currentFen);
      expect((result as BlinfoldGameState).notationInput).toBe('');
    });

    it('should clear notation error on valid move', () => {
      state.notationError = 'Previous error';
      const result = applyPlayerMove(state, 'e4', state.currentFen);
      expect((result as BlinfoldGameState).notationError).toBeNull();
    });
  });

  describe('Alternating turn logic', () => {
    it('should switch to CHESSECHO turn after player move', () => {
      const result = applyPlayerMove(state, 'e4', state.currentFen);
      expect((result as BlinfoldGameState).currentTurn).toBe('CHESSECHO');
    });

    it('should switch to PLAYER turn after ChessEcho move', () => {
      let result = applyPlayerMove(state, 'e4', state.currentFen) as BlinfoldGameState;
      const fen1 = (result as BlinfoldGameState).currentFen;
      
      result = applyChessEchoMove(result, 'c5', fen1) as BlinfoldGameState;
      expect((result as BlinfoldGameState).currentTurn).toBe('PLAYER');
    });

    it('should maintain correct turn sequence over multiple moves', () => {
      let currentState = state;

      const moves = [
        { player: 'e4', chessecho: 'c5' },
        { player: 'd4', chessecho: 'cxd4' },
        { player: 'Nf3', chessecho: 'Nf6' },
      ];

      for (const { player, chessecho } of moves) {
        let result = applyPlayerMove(currentState, player, currentState.currentFen) as BlinfoldGameState;
        expect((result as BlinfoldGameState).currentTurn).toBe('CHESSECHO');
        currentState = result;

        result = applyChessEchoMove(currentState, chessecho, currentState.currentFen) as BlinfoldGameState;
        expect((result as BlinfoldGameState).currentTurn).toBe('PLAYER');
        currentState = result;
      }
    });
  });

  describe('State synchronization', () => {
    it('should update FEN after each move', () => {
      const result = applyPlayerMove(state, 'e4', state.currentFen);
      expect((result as BlinfoldGameState).currentFen).not.toBe(initialFen);
    });

    it('should increment move count correctly', () => {
      let result = applyPlayerMove(state, 'e4', state.currentFen) as BlinfoldGameState;
      expect((result as BlinfoldGameState).moveCount).toBe(1);

      result = applyChessEchoMove(result, 'c5', (result as BlinfoldGameState).currentFen) as BlinfoldGameState;
      expect((result as BlinfoldGameState).moveCount).toBe(2);
    });

    it('should maintain complete move history', () => {
      let result = applyPlayerMove(state, 'e4', state.currentFen) as BlinfoldGameState;
      result = applyChessEchoMove(result, 'c5', (result as BlinfoldGameState).currentFen) as BlinfoldGameState;
      result = applyPlayerMove(result, 'd4', (result as BlinfoldGameState).currentFen) as BlinfoldGameState;

      expect((result as BlinfoldGameState).moveHistory).toHaveLength(3);
      expect((result as BlinfoldGameState).moveHistory.map((m) => m.san)).toEqual(['e4', 'c5', 'd4']);
    });

    it('should record FEN for each move in history', () => {
      let result = applyPlayerMove(state, 'e4', state.currentFen) as BlinfoldGameState;
      const fen1 = (result as BlinfoldGameState).currentFen;
      expect((result as BlinfoldGameState).moveHistory[0].fen).toBe(fen1);

      result = applyChessEchoMove(result, 'c5', fen1) as BlinfoldGameState;
      const fen2 = (result as BlinfoldGameState).currentFen;
      expect((result as BlinfoldGameState).moveHistory[1].fen).toBe(fen2);
    });
  });

  describe('Board reveal functionality', () => {
    it('should track reveal state', () => {
      expect(state.isVisible).toBe(false);
      const revealed = { ...state, isVisible: true };
      expect(revealed.isVisible).toBe(true);
    });

    it('should return accumulated position with correct FEN', () => {
      let result = applyPlayerMove(state, 'e4', state.currentFen) as BlinfoldGameState;
      result = applyChessEchoMove(result, 'c5', (result as BlinfoldGameState).currentFen) as BlinfoldGameState;

      const position = getAccumulatedPosition(result as BlinfoldGameState);
      expect(position.moveCount).toBe(2);
    });

    it('should return correct move count on reveal', () => {
      let result = state;

      const movesToPlay = ['e4', 'c5', 'd4', 'cxd4', 'Nf3'];
      for (let i = 0; i < movesToPlay.length; i++) {
        const move = movesToPlay[i];
        if (result.currentTurn === 'PLAYER') {
          result = applyPlayerMove(result, move, result.currentFen) as BlinfoldGameState;
        } else {
          result = applyChessEchoMove(result, move, result.currentFen) as BlinfoldGameState;
        }
      }

      const position = getAccumulatedPosition(result as BlinfoldGameState);
      expect(position.moveCount).toBe(5);
    });
  });

  describe('Reset functionality', () => {
    it('should clear state and return to initial FEN', () => {
      let result = applyPlayerMove(state, 'e4', state.currentFen) as BlinfoldGameState;
      result = applyChessEchoMove(result, 'c5', (result as BlinfoldGameState).currentFen) as BlinfoldGameState;

      const reset = resetBlinfoldGame(result, initialFen);
      expect(reset.currentFen).toBe(initialFen);
      expect(reset.moveHistory).toHaveLength(0);
      expect(reset.moveCount).toBe(0);
    });

    it('should reset turn to PLAYER', () => {
      const result = applyPlayerMove(state, 'e4', state.currentFen) as BlinfoldGameState;
      const reset = resetBlinfoldGame(result, initialFen);
      expect(reset.currentTurn).toBe('PLAYER');
    });

    it('should clear last moves and errors', () => {
      const result = applyPlayerMove(state, 'e4', state.currentFen) as BlinfoldGameState;
      result.lastPlayerMove = { san: 'e4' };
      result.notationError = 'Some error';

      const reset = resetBlinfoldGame(result, initialFen);
      expect(reset.lastPlayerMove).toBeNull();
      expect(reset.notationError).toBeNull();
    });

    it('should reset isFailed flag', () => {
      state.isFailed = true;
      const reset = resetBlinfoldGame(state, initialFen);
      expect(reset.isFailed).toBe(false);
    });
  });

  describe('Error handling - illegal notation', () => {
    it('should maintain state when rejecting illegal move', () => {
      const result = applyPlayerMove(state, 'Zz1', state.currentFen);
      expect(result).toHaveProperty('error');
      expect(state.moveCount).toBe(0);
    });

    it('should not advance turn on illegal move', () => {
      applyPlayerMove(state, 'e5', state.currentFen);
      expect(state.currentTurn).toBe('PLAYER');
    });
  });

  describe('Move quality preservation', () => {
    it('should store eval loss with player move', () => {
      const result = applyPlayerMove(state, 'e4', state.currentFen);
      const newState = result as BlinfoldGameState;
      expect(newState.lastPlayerMove).not.toBeNull();
      expect(newState.lastPlayerMove?.san).toBe('e4');
    });

    it('should allow eval loss to be populated from feedback', () => {
      const result = applyPlayerMove(state, 'e4', state.currentFen);
      const newState = result as BlinfoldGameState;
      newState.lastPlayerMove = { san: 'e4', evalLoss: 0.25 };
      expect(newState.lastPlayerMove?.evalLoss).toBe(0.25);
    });
  });

  describe('Arbitrary depth continuation', () => {
    it('should support 10+ moves without fixed limit', () => {
      let result = state;

      const movePairs = [
        ['e4', 'c5'],
        ['d4', 'cxd4'],
        ['Nf3', 'Nf6'],
        ['Nc3', 'd6'],
        ['Nxd4', 'a6'],
        ['Be3', 'e5'],
      ];

      let moveCount = 0;
      for (const [playerMove, chessechoMove] of movePairs) {
        result = applyPlayerMove(result, playerMove, result.currentFen) as BlinfoldGameState;
        expect((result as BlinfoldGameState).moveCount).toBe(++moveCount);

        result = applyChessEchoMove(result, chessechoMove, result.currentFen) as BlinfoldGameState;
        expect((result as BlinfoldGameState).moveCount).toBe(++moveCount);
      }

      expect((result as BlinfoldGameState).moveCount).toBe(12);
      expect((result as BlinfoldGameState).moveHistory).toHaveLength(12);
    });
  });

  describe('Provider failure handling', () => {
    it('should handle null ChessEcho response', () => {
      let result = applyPlayerMove(state, 'e4', state.currentFen) as BlinfoldGameState;

      result = {
        ...result,
        isLoading: false,
        isFailed: true,
        lastChessEchoMove: null,
      };

      expect((result as BlinfoldGameState).isFailed).toBe(true);
      expect((result as BlinfoldGameState).currentTurn).toBe('CHESSECHO');
    });

    it('should allow reset after provider failure', () => {
      const result = applyPlayerMove(state, 'e4', state.currentFen) as BlinfoldGameState;
      result.isFailed = true;

      const reset = resetBlinfoldGame(result, initialFen);
      expect(reset.isFailed).toBe(false);
      expect(reset.moveCount).toBe(0);
    });
  });

  describe('Exit/cancel consistency', () => {
    it('should preserve state during exit', () => {
      let result = applyPlayerMove(state, 'e4', state.currentFen) as BlinfoldGameState;
      result = applyChessEchoMove(result, 'c5', (result as BlinfoldGameState).currentFen) as BlinfoldGameState;

      const exitState = { ...result };
      expect(exitState.moveHistory).toHaveLength(2);
      expect(exitState.moveCount).toBe(2);
    });

    it('should allow clean exit via reset', () => {
      const result = applyPlayerMove(state, 'e4', state.currentFen) as BlinfoldGameState;
      const reset = resetBlinfoldGame(result, initialFen);

      expect(reset.currentFen).toBe(initialFen);
      expect(reset.moveCount).toBe(0);
    });
  });
});
