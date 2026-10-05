import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { BlinfoldBoardWrapper } from '../components/BlinfoldBoardWrapper';
import {
  BOARD_CONTAINER_CLASS,
  BOARD_DARK_SQUARE_STYLE,
  BOARD_FRAME_CLASS,
  BOARD_LIGHT_SQUARE_STYLE,
} from '../components/boardPresentation';

const captured: { options: Record<string, unknown> | null } = { options: null };

vi.mock('react-chessboard', () => ({
  Chessboard: ({ options }: { options: Record<string, unknown> }) => {
    captured.options = options;
    return <div data-testid="mock-chessboard" />;
  },
}));

const FEN = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';

describe('BlinfoldBoardWrapper', () => {
  beforeEach(() => {
    captured.options = null;
  });

  it('renders the hidden placeholder inside the shared board container and frame', () => {
    render(<BlinfoldBoardWrapper fen={FEN} isVisible={false} onReveal={() => {}} boardOrientation="white" />);
    const container = screen.getByTestId('blindfold-board');
    expect(container.className).toBe(BOARD_CONTAINER_CLASS);
    expect((container.firstElementChild as HTMLElement).className).toBe(BOARD_FRAME_CLASS);
    expect(screen.getByText('Board hidden')).toBeTruthy();
    expect(screen.queryByTestId('mock-chessboard')).toBeNull();
  });

  it('renders the revealed board with shared styles, frame, and the given orientation', () => {
    render(<BlinfoldBoardWrapper fen={FEN} isVisible onReveal={() => {}} boardOrientation="black" />);
    const container = screen.getByTestId('blindfold-board');
    expect(container.className).toBe(BOARD_CONTAINER_CLASS);
    expect((container.firstElementChild as HTMLElement).className).toBe(BOARD_FRAME_CLASS);
    expect(captured.options).toMatchObject({
      position: FEN,
      boardOrientation: 'black',
      allowDragging: false,
      darkSquareStyle: BOARD_DARK_SQUARE_STYLE,
      lightSquareStyle: BOARD_LIGHT_SQUARE_STYLE,
    });
  });
});
