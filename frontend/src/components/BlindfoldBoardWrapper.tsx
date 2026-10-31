'use client';

import React from 'react';
import { ChessBoardView } from './ChessBoardView';
import {
  BOARD_CONTAINER_CLASS,
  BOARD_FRAME_CLASS,
} from './boardPresentation';

interface BlindfoldBoardWrapperProps {
  fen: string;
  isVisible: boolean;
  onReveal: () => void;
  onHide?: () => void;
  onToggleVisibility?: () => void;
  boardOrientation: 'white' | 'black';
}

export const BlindfoldBoardWrapper: React.FC<BlindfoldBoardWrapperProps> = ({
  fen,
  isVisible,
  onReveal,
  onHide,
  onToggleVisibility,
  boardOrientation,
}) => (
  <div className={BOARD_CONTAINER_CLASS} data-testid="blindfold-board">
    <div className={BOARD_FRAME_CLASS}>
      {isVisible ? (
        <div className="relative">
          <ChessBoardView
            fen={fen}
            boardOrientation={boardOrientation}
            allowDragging={false}
            containerClassName="w-full"
            frameClassName=""
          />
          <div className="mt-3 flex justify-center pb-2">
            <button
              type="button"
              onClick={onHide ?? onToggleVisibility}
              aria-label="Hide board"
              className="rounded-lg border border-slate-700 bg-slate-900/90 px-4 py-2 text-xs font-bold text-slate-300 hover:border-slate-500 hover:text-white transition cursor-pointer"
            >
              Hide board
            </button>
          </div>
        </div>
      ) : (
        <div className="flex aspect-square w-full items-center justify-center p-8 text-center">
          <div>
            <div className="mb-3 text-5xl">♟</div>
            <p className="font-bold text-white">Board hidden</p>
            <p className="mt-1 text-xs text-slate-400">Use notation to visualize the position.</p>
            <button
              type="button"
              onClick={onReveal ?? onToggleVisibility}
              className="mt-4 rounded-lg border border-amber-700/60 px-4 py-2 text-xs font-bold text-amber-300 cursor-pointer"
            >
              Reveal position
            </button>
          </div>
        </div>
      )}
    </div>
  </div>
);
