import React from 'react';
import { Chessboard } from 'react-chessboard';
import {
  BOARD_CONTAINER_CLASS,
  BOARD_FRAME_CLASS,
  BOARD_DARK_SQUARE_STYLE,
  BOARD_LIGHT_SQUARE_STYLE,
} from './boardPresentation';

export type BoardOrientationInput = 'white' | 'black' | 'WHITE' | 'BLACK' | string | null | undefined;

export function normalizeBoardOrientation(orientation?: BoardOrientationInput): 'white' | 'black' {
  if (!orientation) return 'white';
  const lower = orientation.toLowerCase();
  return lower === 'black' ? 'black' : 'white';
}

export interface ChessBoardViewProps {
  fen: string;
  boardOrientation?: BoardOrientationInput;
  allowDragging?: boolean;
  containerClassName?: string;
  frameClassName?: string;
  customSquareStyles?: Record<string, React.CSSProperties>;
  animationDurationInMs?: number;
  onPieceDrop?: (args: { piece?: unknown; sourceSquare: string; targetSquare: string | null }) => boolean;
  onSquareClick?: (args: { piece?: unknown; square: string }) => void;
  options?: Record<string, unknown>;
}

export const ChessBoardView: React.FC<ChessBoardViewProps> = ({
  fen,
  boardOrientation = 'white',
  allowDragging = false,
  containerClassName,
  frameClassName,
  customSquareStyles,
  animationDurationInMs,
  onPieceDrop,
  onSquareClick,
  options: additionalOptions,
}) => {
  const normalizedOrientation = normalizeBoardOrientation(boardOrientation);

  const containerClasses = containerClassName ?? BOARD_CONTAINER_CLASS;
  const frameClasses = frameClassName ?? BOARD_FRAME_CLASS;

  return (
    <div className={containerClasses}>
      <div className={frameClasses}>
        <Chessboard
          options={{
            position: fen,
            boardOrientation: normalizedOrientation,
            allowDragging,
            darkSquareStyle: BOARD_DARK_SQUARE_STYLE,
            lightSquareStyle: BOARD_LIGHT_SQUARE_STYLE,
            ...(customSquareStyles ? { squareStyles: customSquareStyles } : {}),
            ...(animationDurationInMs !== undefined ? { animationDurationInMs } : {}),
            ...(onPieceDrop ? { onPieceDrop } : {}),
            ...(onSquareClick ? { onSquareClick } : {}),
            ...additionalOptions,
          }}
        />
      </div>
    </div>
  );
};
