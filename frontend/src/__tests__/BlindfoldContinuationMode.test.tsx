import { describe, it, expect, beforeEach } from 'vitest';
import {
  BlindfoldGameState,
  createBlinfoldGameState,
  applyPlayerMove,
  applyChessEchoMove,
  resetBlinfoldGame,
  getAccumulatedPosition,
  toggleBlinfoldVisibility,
  setBlinfoldVisibility,
  setPlayerMoveEvaluation,
} from '../utils/blindfoldGameState';

describe('BlinfoldContinuationMode', () => {
  let initialFen: string;
  let state: BlindfoldGameState;

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
      expect((result as BlindfoldGameState).moveCount).toBe(1);
      expect((result as BlindfoldGameState).currentTurn).toBe('CHESSECHO');
    });

    it('should accept valid SAN move (e.g., Nf3)', () => {
      const result = applyPlayerMove(state, 'Nf3', state.currentFen);
      expect(result).not.toHaveProperty('error');
      expect((result as BlindfoldGameState).moveCount).toBe(1);
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
      expect((result as BlindfoldGameState).moveHistory).toHaveLength(1);
      expect((result as BlindfoldGameState).moveHistory[0].san).toBe('e4');
    });

    it('should clear notation input after valid move', () => {
      state.notationInput = 'e4';
      const result = applyPlayerMove(state, 'e4', state.currentFen);
      expect((result as BlindfoldGameState).notationInput).toBe('');
    });

    it('should clear notation error on valid move', () => {
      state.notationError = 'Previous error';
      const result = applyPlayerMove(state, 'e4', state.currentFen);
      expect((result as BlindfoldGameState).notationError).toBeNull();
    });
  });

  describe('Alternating turn logic', () => {
    it('should switch to CHESSECHO turn after player move', () => {
      const result = applyPlayerMove(state, 'e4', state.currentFen);
      expect((result as BlindfoldGameState).currentTurn).toBe('CHESSECHO');
    });

    it('should switch to PLAYER turn after ChessEcho move', () => {
      let result = applyPlayerMove(state, 'e4', state.currentFen) as BlindfoldGameState;
      const fen1 = (result as BlindfoldGameState).currentFen;
      
      result = applyChessEchoMove(result, 'c5', fen1) as BlindfoldGameState;
      expect((result as BlindfoldGameState).currentTurn).toBe('PLAYER');
    });

    it('should maintain correct turn sequence over multiple moves', () => {
      let currentState = state;

      const moves = [
        { player: 'e4', chessecho: 'c5' },
        { player: 'd4', chessecho: 'cxd4' },
        { player: 'Nf3', chessecho: 'Nf6' },
      ];

      for (const { player, chessecho } of moves) {
        let result = applyPlayerMove(currentState, player, currentState.currentFen) as BlindfoldGameState;
        expect((result as BlindfoldGameState).currentTurn).toBe('CHESSECHO');
        currentState = result;

        result = applyChessEchoMove(currentState, chessecho, currentState.currentFen) as BlindfoldGameState;
        expect((result as BlindfoldGameState).currentTurn).toBe('PLAYER');
        currentState = result;
      }
    });
  });

  describe('State synchronization', () => {
    it('should update FEN after each move', () => {
      const result = applyPlayerMove(state, 'e4', state.currentFen);
      expect((result as BlindfoldGameState).currentFen).not.toBe(initialFen);
    });

    it('should increment move count correctly', () => {
      let result = applyPlayerMove(state, 'e4', state.currentFen) as BlindfoldGameState;
      expect((result as BlindfoldGameState).moveCount).toBe(1);

      result = applyChessEchoMove(result, 'c5', (result as BlindfoldGameState).currentFen) as BlindfoldGameState;
      expect((result as BlindfoldGameState).moveCount).toBe(2);
    });

    it('should maintain complete move history', () => {
      let result = applyPlayerMove(state, 'e4', state.currentFen) as BlindfoldGameState;
      result = applyChessEchoMove(result, 'c5', (result as BlindfoldGameState).currentFen) as BlindfoldGameState;
      result = applyPlayerMove(result, 'd4', (result as BlindfoldGameState).currentFen) as BlindfoldGameState;

      expect((result as BlindfoldGameState).moveHistory).toHaveLength(3);
      expect((result as BlindfoldGameState).moveHistory.map((m) => m.san)).toEqual(['e4', 'c5', 'd4']);
    });

    it('should record FEN for each move in history', () => {
      let result = applyPlayerMove(state, 'e4', state.currentFen) as BlindfoldGameState;
      const fen1 = (result as BlindfoldGameState).currentFen;
      expect((result as BlindfoldGameState).moveHistory[0].fen).toBe(fen1);

      result = applyChessEchoMove(result, 'c5', fen1) as BlindfoldGameState;
      const fen2 = (result as BlindfoldGameState).currentFen;
      expect((result as BlindfoldGameState).moveHistory[1].fen).toBe(fen2);
    });
  });

  describe('Board reveal functionality', () => {
    it('should track reveal state', () => {
      expect(state.isVisible).toBe(false);
      const revealed = { ...state, isVisible: true };
      expect(revealed.isVisible).toBe(true);
    });

    it('should return accumulated position with correct FEN', () => {
      let result = applyPlayerMove(state, 'e4', state.currentFen) as BlindfoldGameState;
      result = applyChessEchoMove(result, 'c5', (result as BlindfoldGameState).currentFen) as BlindfoldGameState;

      const position = getAccumulatedPosition(result as BlindfoldGameState);
      expect(position.moveCount).toBe(2);
    });

    it('should return correct move count on reveal', () => {
      let result = state;

      const movesToPlay = ['e4', 'c5', 'd4', 'cxd4', 'Nf3'];
      for (let i = 0; i < movesToPlay.length; i++) {
        const move = movesToPlay[i];
        if (result.currentTurn === 'PLAYER') {
          result = applyPlayerMove(result, move, result.currentFen) as BlindfoldGameState;
        } else {
          result = applyChessEchoMove(result, move, result.currentFen) as BlindfoldGameState;
        }
      }

      const position = getAccumulatedPosition(result as BlindfoldGameState);
      expect(position.moveCount).toBe(5);
    });
  });

  describe('Reset functionality', () => {
    it('should clear state and return to initial FEN', () => {
      let result = applyPlayerMove(state, 'e4', state.currentFen) as BlindfoldGameState;
      result = applyChessEchoMove(result, 'c5', (result as BlindfoldGameState).currentFen) as BlindfoldGameState;

      const reset = resetBlinfoldGame(result, initialFen);
      expect(reset.currentFen).toBe(initialFen);
      expect(reset.moveHistory).toHaveLength(0);
      expect(reset.moveCount).toBe(0);
    });

    it('should reset turn to PLAYER', () => {
      const result = applyPlayerMove(state, 'e4', state.currentFen) as BlindfoldGameState;
      const reset = resetBlinfoldGame(result, initialFen);
      expect(reset.currentTurn).toBe('PLAYER');
    });

    it('should clear last moves and errors', () => {
      const result = applyPlayerMove(state, 'e4', state.currentFen) as BlindfoldGameState;
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
      const newState = result as BlindfoldGameState;
      expect(newState.lastPlayerMove).not.toBeNull();
      expect(newState.lastPlayerMove?.san).toBe('e4');
    });

    it('should allow eval loss to be populated from feedback', () => {
      const result = applyPlayerMove(state, 'e4', state.currentFen);
      const newState = result as BlindfoldGameState;
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
        result = applyPlayerMove(result, playerMove, result.currentFen) as BlindfoldGameState;
        expect((result as BlindfoldGameState).moveCount).toBe(++moveCount);

        result = applyChessEchoMove(result, chessechoMove, result.currentFen) as BlindfoldGameState;
        expect((result as BlindfoldGameState).moveCount).toBe(++moveCount);
      }

      expect((result as BlindfoldGameState).moveCount).toBe(12);
      expect((result as BlindfoldGameState).moveHistory).toHaveLength(12);
    });
  });

  describe('Provider failure handling', () => {
    it('should handle null ChessEcho response', () => {
      let result = applyPlayerMove(state, 'e4', state.currentFen) as BlindfoldGameState;

      result = {
        ...result,
        isLoading: false,
        isFailed: true,
        lastChessEchoMove: null,
      };

      expect((result as BlindfoldGameState).isFailed).toBe(true);
      expect((result as BlindfoldGameState).currentTurn).toBe('CHESSECHO');
    });

    it('should allow reset after provider failure', () => {
      const result = applyPlayerMove(state, 'e4', state.currentFen) as BlindfoldGameState;
      result.isFailed = true;

      const reset = resetBlinfoldGame(result, initialFen);
      expect(reset.isFailed).toBe(false);
      expect(reset.moveCount).toBe(0);
    });
  });

  describe('Exit/cancel consistency', () => {
    it('should preserve state during exit', () => {
      let result = applyPlayerMove(state, 'e4', state.currentFen) as BlindfoldGameState;
      result = applyChessEchoMove(result, 'c5', (result as BlindfoldGameState).currentFen) as BlindfoldGameState;

      const exitState = { ...result };
      expect(exitState.moveHistory).toHaveLength(2);
      expect(exitState.moveCount).toBe(2);
    });

    it('should allow clean exit via reset', () => {
      const result = applyPlayerMove(state, 'e4', state.currentFen) as BlindfoldGameState;
      const reset = resetBlinfoldGame(result, initialFen);

      expect(reset.currentFen).toBe(initialFen);
      expect(reset.moveCount).toBe(0);
    });
  });
  describe('Opening turn from entry position', () => {
    const blackToMove = 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1';

    it('gives the player the first turn when the player is to move', () => {
      expect(createBlinfoldGameState(initialFen, 'WHITE').currentTurn).toBe('PLAYER');
      expect(createBlinfoldGameState(blackToMove, 'BLACK').currentTurn).toBe('PLAYER');
    });

    it('gives ChessEcho the first turn when the other side is to move', () => {
      expect(createBlinfoldGameState(initialFen, 'BLACK').currentTurn).toBe('CHESSECHO');
      expect(createBlinfoldGameState(blackToMove, 'WHITE').currentTurn).toBe('CHESSECHO');
    });

    it('keeps the player first when no player color is given', () => {
      expect(createBlinfoldGameState(blackToMove).currentTurn).toBe('PLAYER');
    });

    it('applies the same rule on reset', () => {
      const played = createBlinfoldGameState(initialFen, 'BLACK');
      expect(resetBlinfoldGame(played, initialFen, 'BLACK').currentTurn).toBe('CHESSECHO');
      expect(resetBlinfoldGame(played, initialFen, 'WHITE').currentTurn).toBe('PLAYER');
    });
  });

  describe('Reversible visibility control', () => {
    it('toggles visibility back and forth while preserving game state', () => {
      const initial = createBlinfoldGameState(initialFen);
      expect(initial.isVisible).toBe(false);

      const revealed = toggleBlinfoldVisibility(initial);
      expect(revealed.isVisible).toBe(true);
      expect(revealed.currentFen).toBe(initial.currentFen);
      expect(revealed.moveCount).toBe(0);

      const hiddenAgain = toggleBlinfoldVisibility(revealed);
      expect(hiddenAgain.isVisible).toBe(false);
      expect(hiddenAgain.currentFen).toBe(initial.currentFen);

      const explicitlyShown = setBlinfoldVisibility(hiddenAgain, true);
      expect(explicitlyShown.isVisible).toBe(true);
      const explicitlyHidden = setBlinfoldVisibility(explicitlyShown, false);
      expect(explicitlyHidden.isVisible).toBe(false);
    });
  });

  describe('Terminal state detection (checkmate, stalemate, draw)', () => {
    it('detects checkmate when the player delivers checkmate', () => {
      // Fool's mate setup: 1. f3 e5 2. g4 Qh4#
      const foolsMateFen = 'rnbqkbnr/pppp1ppp/8/4p3/6P1/5P2/PPPPP2P/RNBQKBNR b KQkq - 0 2';
      const state = createBlinfoldGameState(foolsMateFen, 'BLACK');
      const result = applyPlayerMove(state, 'Qh4#', foolsMateFen) as BlindfoldGameState;

      expect(result.isTerminal).toBe(true);
      expect(result.terminalReason).toBe('CHECKMATE');
      expect(result.winner).toBe('PLAYER');
    });

    it('detects checkmate when ChessEcho delivers checkmate', () => {
      const foolsMateFen = 'rnbqkbnr/pppp1ppp/8/4p3/6P1/5P2/PPPPP2P/RNBQKBNR b KQkq - 0 2';
      const state = createBlinfoldGameState(foolsMateFen, 'WHITE');
      const result = applyChessEchoMove(state, 'Qh4#', foolsMateFen) as BlindfoldGameState;

      expect(result.isTerminal).toBe(true);
      expect(result.terminalReason).toBe('CHECKMATE');
      expect(result.winner).toBe('CHESSECHO');
    });

    it('detects stalemate upon move application', () => {
      // White to move: Ka8, Black: Kc8, Qc7. White is not in check, but has Ka8 trapped.
      // Move Ka7 -> wait, let's create a stalemate position reached by a move:
      // White: Kh1, Pg2. Black: Kf2. Black plays g3 -> wait.
      // Standard: 7k/5Q2/6K1/8/8/8/8/8 b - - 0 1 -> stalemate already.
      // Move that causes stalemate:
      // FEN: 7k/8/5QK1/8/8/8/8/8 w - - 0 1. White plays Qf7 -> wait, Qf7 is not legal or check?
      // FEN: 8/8/8/8/8/5k2/4q3/7K b - - 0 1 -> Black to move. If Black plays Qg2# that's mate. If Black plays Qf2:
      // 8/8/8/8/8/5k2/5q2/7K w - - 1 2 -> White king on h1 has no legal moves, not in check -> Stalemate!
      const beforeStalemate = '8/8/8/8/8/5k2/4q3/7K b - - 0 1';
      const state = createBlinfoldGameState(beforeStalemate, 'BLACK');
      const result = applyPlayerMove(state, 'Qf2', beforeStalemate) as BlindfoldGameState;

      expect(result.isTerminal).toBe(true);
      expect(result.terminalReason).toBe('STALEMATE');
      expect(result.winner).toBe('DRAW');
    });

    it('prevents moves once terminal', () => {
      const foolsMateFen = 'rnbqkbnr/pppp1ppp/8/4p3/6P1/5P2/PPPPP2P/RNBQKBNR b KQkq - 0 2';
      const state = createBlinfoldGameState(foolsMateFen, 'BLACK');
      const checkmated = applyPlayerMove(state, 'Qh4#', foolsMateFen) as BlindfoldGameState;

      const subsequent = applyPlayerMove(checkmated, 'e4', checkmated.currentFen);
      expect(subsequent).toHaveProperty('error');
    });
  });

  describe('Move evaluation helper', () => {
    it('sets move evaluation loss, acceptability, and feedbackText on lastPlayerMove', () => {
      const initial = createBlinfoldGameState(initialFen, 'WHITE');
      const moved = applyPlayerMove(initial, 'e4', initialFen) as BlindfoldGameState;

      const evaluated = setPlayerMoveEvaluation(moved, 0.05, true, 'Good move! (+0.05 pawns)');
      expect(evaluated.lastPlayerMove?.evalLoss).toBe(0.05);
      expect(evaluated.lastPlayerMove?.acceptable).toBe(true);
      expect(evaluated.lastPlayerMove?.feedbackText).toBe('Good move! (+0.05 pawns)');
    });
  });
});
