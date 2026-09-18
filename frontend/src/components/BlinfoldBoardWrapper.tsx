'use client';

import React from 'react';
import { Chessboard } from 'react-chessboard';

interface BlinfoldBoardWrapperProps {
  fen: string;
  isVisible: boolean;
  onReveal: () => void;
}

export const BlinfoldBoardWrapper: React.FC<BlinfoldBoardWrapperProps> = ({ fen, isVisible, onReveal }) => {
  if (!isVisible) {
    return (
      <div className="flex aspect-square w-full max-w-[640px] items-center justify-center rounded-2xl border border-emerald-900/60 bg-slate-900 p-8 text-center shadow-2xl">
        <div>
          <div className="mb-3 text-5xl">♟</div>
          <p className="font-bold text-white">Board hidden</p>
          <p className="mt-1 text-xs text-slate-400">Use notation to visualize the position.</p>
          <button type="button" onClick={onReveal} className="mt-4 rounded-lg border border-amber-700/60 px-4 py-2 text-xs font-bold text-amber-300">Reveal position</button>
        </div>
      </div>
    );
  }
  return (
    <div className="w-full max-w-[640px] overflow-hidden rounded-2xl">
      <Chessboard options={{ position: fen, allowDragging: false }} />
    </div>
  );
};
