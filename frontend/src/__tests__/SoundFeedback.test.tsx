import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import { vi, describe, it, expect, beforeEach, afterEach } from 'vitest';
import { soundService, playSound, isSoundEnabled, setSoundEnabled, toggleSound } from '../services/soundService';
import { BoardControls } from '../components/BoardControls';
import { ChessBoardArea } from '../components/ChessBoardArea';

// Mock react-chessboard to allow direct onPieceDrop simulation
vi.mock('react-chessboard', () => ({
  Chessboard: ({ options }: { options?: { onPieceDrop?: (data: { sourceSquare: string; targetSquare: string }) => boolean } }) => (
    <div data-testid="chessboard-mock">
      <button
        data-testid="make-move-best"
        onClick={() => options?.onPieceDrop?.({ sourceSquare: 'e2', targetSquare: 'e4' })}
      >
        Make Best Move
      </button>
      <button
        data-testid="make-move-acceptable"
        onClick={() => options?.onPieceDrop?.({ sourceSquare: 'd2', targetSquare: 'd4' })}
      >
        Make Acceptable Move
      </button>
      <button
        data-testid="make-move-mistake"
        onClick={() => options?.onPieceDrop?.({ sourceSquare: 'g1', targetSquare: 'f3' })}
      >
        Make Mistake Move
      </button>
      <button
        data-testid="make-move-continuation"
        onClick={() => options?.onPieceDrop?.({ sourceSquare: 'e7', targetSquare: 'e5' })}
      >
        Make Continuation Move
      </button>
    </div>
  ),
}));

describe('Sound Feedback System', () => {
  let playMock: ReturnType<typeof vi.fn>;
  let createdAudioList: Array<{ src: string; currentTime: number; play: ReturnType<typeof vi.fn> }>;

  beforeEach(() => {
    localStorage.clear();
    vi.clearAllMocks();
    createdAudioList = [];

    playMock = vi.fn().mockImplementation(function (this: { src: string; currentTime: number }) {
      return Promise.resolve();
    });

    class MockAudio {
      src: string;
      currentTime: number = 0;
      preload: string = '';
      play: ReturnType<typeof vi.fn>;

      constructor(src: string) {
        this.src = src;
        this.play = playMock;
        createdAudioList.push(this);
      }
    }

    // @ts-expect-error Mocking window.Audio
    window.Audio = MockAudio;

    // Reset soundService state and cache for each test
    soundService.reset();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  describe('SoundService Unit Tests', () => {
    it('defaults to sound enabled', () => {
      expect(isSoundEnabled()).toBe(true);
      expect(soundService.isSoundEnabled()).toBe(true);
    });

    it('plays correct sound with correct asset path and resets currentTime', () => {
      playSound('correct');
      expect(createdAudioList.length).toBe(1);
      expect(createdAudioList[0].src).toBe('/sounds/correct.wav');
      expect(playMock).toHaveBeenCalledTimes(1);
    });

    it('plays incorrect sound with correct asset path', () => {
      playSound('incorrect');
      expect(createdAudioList.some((a) => a.src === '/sounds/incorrect.wav')).toBe(true);
      expect(playMock).toHaveBeenCalledTimes(1);
    });

    it('plays completion sound with correct asset path', () => {
      playSound('completion');
      expect(createdAudioList.some((a) => a.src === '/sounds/completion.wav')).toBe(true);
      expect(playMock).toHaveBeenCalledTimes(1);
    });

    it('plays piece move sound with correct asset path', () => {
      playSound('move');
      expect(createdAudioList.some((a) => a.src === '/sounds/move.wav')).toBe(true);
      expect(playMock).toHaveBeenCalledTimes(1);
    });

    it('caches Audio objects and reuses existing instance on repeated plays', () => {
      playSound('completion');
      expect(createdAudioList.filter((a) => a.src === '/sounds/completion.wav').length).toBe(1);

      // Play completion again
      playSound('completion');
      expect(createdAudioList.filter((a) => a.src === '/sounds/completion.wav').length).toBe(1);
      expect(playMock).toHaveBeenCalledTimes(2);
    });

    it('does not play audio when sound is muted', () => {
      setSoundEnabled(false);
      expect(isSoundEnabled()).toBe(false);
      expect(localStorage.getItem('chessecho_sound_enabled')).toBe('false');

      playSound('correct');
      playSound('completion');
      playSound('incorrect');
      playSound('move');

      expect(playMock).not.toHaveBeenCalled();
    });

    it('toggles sound on and off and persists preference in localStorage', () => {
      expect(isSoundEnabled()).toBe(true);

      const toggledOff = toggleSound();
      expect(toggledOff).toBe(false);
      expect(isSoundEnabled()).toBe(false);
      expect(localStorage.getItem('chessecho_sound_enabled')).toBe('false');

      const toggledOn = toggleSound();
      expect(toggledOn).toBe(true);
      expect(isSoundEnabled()).toBe(true);
      expect(localStorage.getItem('chessecho_sound_enabled')).toBe('true');
    });

    it('gracefully handles rejected Audio.play() promise without throwing', async () => {
      const rejectMock = vi.fn().mockImplementation(() => Promise.reject(new Error('Autoplay prevented')));
      playMock.mockImplementation(rejectMock);

      expect(() => {
        playSound('correct');
      }).not.toThrow();
    });

    it('notifies listeners subscribed to sound state changes', () => {
      const listener = vi.fn();
      const unsubscribe = soundService.subscribe(listener);

      setSoundEnabled(false);
      expect(listener).toHaveBeenCalledWith(false);

      setSoundEnabled(true);
      expect(listener).toHaveBeenCalledWith(true);

      unsubscribe();
      setSoundEnabled(false);
      expect(listener).toHaveBeenCalledTimes(2);
    });
  });

  describe('BoardControls Sound Toggle UI', () => {
    it('renders mute button when sound is enabled and handles click', () => {
      const handleToggle = vi.fn();
      render(
        <BoardControls
          onUndo={vi.fn()}
          onRedo={vi.fn()}
          onReset={vi.fn()}
          onHint={vi.fn()}
          onNextPuzzle={vi.fn()}
          canUndo={false}
          canRedo={false}
          soundEnabled={true}
          onToggleSound={handleToggle}
        />
      );

      const soundBtn = screen.getByRole('button', { name: /mute sound/i });
      expect(soundBtn).toBeInTheDocument();
      expect(soundBtn).toHaveAttribute('title', 'Mute Sound');

      fireEvent.click(soundBtn);
      expect(handleToggle).toHaveBeenCalledTimes(1);
    });

    it('renders enable sound button when sound is disabled', () => {
      render(
        <BoardControls
          onUndo={vi.fn()}
          onRedo={vi.fn()}
          onReset={vi.fn()}
          onHint={vi.fn()}
          onNextPuzzle={vi.fn()}
          canUndo={false}
          canRedo={false}
          soundEnabled={false}
          onToggleSound={vi.fn()}
        />
      );

      const soundBtn = screen.getByRole('button', { name: /enable sound/i });
      expect(soundBtn).toBeInTheDocument();
      expect(soundBtn).toHaveAttribute('title', 'Enable Sound');
    });
  });

  describe('ChessBoardArea Sound Integration', () => {
    const defaultPuzzleProps = {
      initialFen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
      playerColor: 'WHITE' as const,
      targetMove: 'e4',
      acceptableMoves: [{ move: 'd4', evalLoss: 0.05 }],
      movesPlayed: [{ move: 'Nf3', timesPlayed: 3, averageLoss: 0.8 }],
      onMoveAttempt: vi.fn(),
      onNextPuzzle: vi.fn(),
    };

    it('plays completion sound when the best move (targetMove) is played', () => {
      render(<ChessBoardArea {...defaultPuzzleProps} />);

      const bestMoveBtn = screen.getByTestId('make-move-best');
      fireEvent.click(bestMoveBtn);

      expect(createdAudioList.some((a) => a.src === '/sounds/completion.wav')).toBe(true);
      expect(playMock).toHaveBeenCalledTimes(1);
    });

    it('plays correct sound when an acceptable alternative move is played', () => {
      render(<ChessBoardArea {...defaultPuzzleProps} />);

      const acceptableMoveBtn = screen.getByTestId('make-move-acceptable');
      fireEvent.click(acceptableMoveBtn);

      expect(createdAudioList.some((a) => a.src === '/sounds/correct.wav')).toBe(true);
      expect(playMock).toHaveBeenCalledTimes(1);
    });

    it('plays incorrect sound when a historical mistake or suboptimal move is played', () => {
      render(<ChessBoardArea {...defaultPuzzleProps} />);

      const mistakeMoveBtn = screen.getByTestId('make-move-mistake');
      fireEvent.click(mistakeMoveBtn);

      expect(createdAudioList.some((a) => a.src === '/sounds/incorrect.wav')).toBe(true);
      expect(playMock).toHaveBeenCalledTimes(1);
    });

    it('plays move sound when continuing board exploration after initial move', () => {
      render(<ChessBoardArea {...defaultPuzzleProps} />);

      // First initial decision move
      fireEvent.click(screen.getByTestId('make-move-best'));
      expect(createdAudioList.some((a) => a.src === '/sounds/completion.wav')).toBe(true);

      // Subsequent exploration move
      fireEvent.click(screen.getByTestId('make-move-continuation'));
      expect(createdAudioList.some((a) => a.src === '/sounds/move.wav')).toBe(true);
    });

    it('does not play sound on moves when soundEnabled is false', () => {
      setSoundEnabled(false);
      render(<ChessBoardArea {...defaultPuzzleProps} soundEnabled={false} />);

      fireEvent.click(screen.getByTestId('make-move-best'));
      expect(playMock).not.toHaveBeenCalled();
    });
  });
});
