'use client';

import React from 'react';
import { ChessBoardView } from './ChessBoardView';
import {
  BOARD_CONTAINER_CLASS,
  BOARD_FRAME_CLASS,
} from './boardPresentation';

interface BlinfoldBoardWrapperProps {
  fen: string;
  isVisible: boolean;
  onReveal: () => void;
  boardOrientation: 'white' | 'black';
}

export const BlinfoldBoardWrapper: React.FC<BlinfoldBoardWrapperProps> = ({ fen, isVisible, onReveal, boardOrientation }) => (
  <div className={BOARD_CONTAINER_CLASS} data-testid="blindfold-board">
    <div className={BOARD_FRAME_CLASS}>
      {isVisible ? (
        <ChessBoardView
          fen={fen}
          boardOrientation={boardOrientation}
          allowDragging={false}
          containerClassName="w-full"
          frameClassName=""
        />
      ) : (
        <div className="flex aspect-square w-full items-center justify-center p-8 text-center">
          <div>
            <div className="mb-3 text-5xl">♟</div>
            <p className="font-bold text-white">Board hidden</p>
            <p className="mt-1 text-xs text-slate-400">Use notation to visualize the position.</p>
            <button type="button" onClick={onReveal} className="mt-4 rounded-lg border border-amber-700/60 px-4 py-2 text-xs font-bold text-amber-300">Reveal position</button>
          </div>
        </div>
      )}
    </div>
  </div>
);
