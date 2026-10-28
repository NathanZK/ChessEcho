import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { ChessBoardView, normalizeBoardOrientation } from '../components/ChessBoardView';
import { BOARD_DARK_SQUARE_STYLE, BOARD_LIGHT_SQUARE_STYLE } from '../components/boardPresentation';

// Mock react-chessboard to verify the options passed to it
vi.mock('react-chessboard', () => ({
  Chessboard: (props: { options?: Record<string, unknown> }) => (
    <div data-testid="mock-react-chessboard" data-options={JSON.stringify(props.options)}>
      Board Mock
    </div>
  ),
}));

describe('ChessBoardView', () => {
  const sampleFen = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';

  describe('normalizeBoardOrientation', () => {
    it('normalizes uppercase and lowercase black to black', () => {
      expect(normalizeBoardOrientation('black')).toBe('black');
      expect(normalizeBoardOrientation('BLACK')).toBe('black');
    });

    it('normalizes uppercase and lowercase white to white', () => {
      expect(normalizeBoardOrientation('white')).toBe('white');
      expect(normalizeBoardOrientation('WHITE')).toBe('white');
    });

    it('defaults to white when undefined or null', () => {
      expect(normalizeBoardOrientation()).toBe('white');
      expect(normalizeBoardOrientation(null)).toBe('white');
    });
  });

  describe('rendering and props forwarding', () => {
    it('renders with standard green-and-cream palette by default', () => {
      render(<ChessBoardView fen={sampleFen} />);
      const boardElement = screen.getByTestId('mock-react-chessboard');
      const options = JSON.parse(boardElement.getAttribute('data-options') || '{}');

      expect(options.position).toBe(sampleFen);
      expect(options.boardOrientation).toBe('white');
      expect(options.allowDragging).toBe(false);
      expect(options.darkSquareStyle).toEqual(BOARD_DARK_SQUARE_STYLE);
      expect(options.lightSquareStyle).toEqual(BOARD_LIGHT_SQUARE_STYLE);
    });

    it('forwards black orientation correctly', () => {
      render(<ChessBoardView fen={sampleFen} boardOrientation="BLACK" />);
      const boardElement = screen.getByTestId('mock-react-chessboard');
      const options = JSON.parse(boardElement.getAttribute('data-options') || '{}');

      expect(options.boardOrientation).toBe('black');
    });

    it('allows dragging when allowDragging is true', () => {
      render(<ChessBoardView fen={sampleFen} allowDragging={true} />);
      const boardElement = screen.getByTestId('mock-react-chessboard');
      const options = JSON.parse(boardElement.getAttribute('data-options') || '{}');

      expect(options.allowDragging).toBe(true);
    });

    it('applies custom square styles and animation duration', () => {
      const customStyles = { e4: { backgroundColor: 'yellow' } };
      render(
        <ChessBoardView
          fen={sampleFen}
          customSquareStyles={customStyles}
          animationDurationInMs={150}
        />
      );
      const boardElement = screen.getByTestId('mock-react-chessboard');
      const options = JSON.parse(boardElement.getAttribute('data-options') || '{}');

      expect(options.squareStyles).toEqual(customStyles);
      expect(options.animationDurationInMs).toBe(150);
    });

    it('renders with custom container class if provided', () => {
      const { container } = render(
        <ChessBoardView fen={sampleFen} containerClassName="custom-test-container" />
      );
      expect(container.querySelector('.custom-test-container')).toBeInTheDocument();
    });
  });
});
