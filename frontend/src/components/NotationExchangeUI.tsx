'use client';

import React, { FormEvent, useState } from 'react';
import type { BlindfoldGameState } from '@/utils/blindfoldGameState';

interface NotationExchangeUIProps {
  state: BlindfoldGameState;
  onPlayerMove: (notation: string) => void;
  onReveal: () => void;
  onReset: () => void;
  onExit?: () => void;
}

export const NotationExchangeUI: React.FC<NotationExchangeUIProps> = ({
  state,
  onPlayerMove,
  onReveal,
  onReset,
  onExit,
}) => {
  const [value, setValue] = useState('');
  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (state.currentTurn !== 'PLAYER' || state.isLoading) return;
    onPlayerMove(value);
    setValue('');
  };

  return (
    <section className="rounded-2xl border border-slate-800 bg-slate-900/80 p-4 shadow-xl">
      <div className="mb-3 flex items-center justify-between">
        <div>
          <p className="text-[10px] font-bold uppercase tracking-wider text-emerald-400">Blindfold continuation</p>
          <p className="text-xs text-slate-400">
            {state.currentTurn === 'PLAYER' ? 'Your turn — enter SAN notation' : 'ChessEcho is responding…'}
          </p>
        </div>
        <span className="rounded-full bg-slate-800 px-2 py-1 text-[10px] font-bold text-slate-300">
          {state.moveCount} moves
        </span>
      </div>
      <form onSubmit={submit} className="flex gap-2">
        <input
          value={value}
          onChange={(event) => setValue(event.target.value)}
          disabled={state.currentTurn !== 'PLAYER' || state.isLoading}
          placeholder="e4, Nf3, O-O"
          aria-label="Move notation"
          className="min-w-0 flex-1 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-sm text-white outline-none focus:border-emerald-500"
        />
        <button type="submit" disabled={state.currentTurn !== 'PLAYER' || state.isLoading || !value.trim()} className="rounded-lg bg-emerald-600 px-3 py-2 text-xs font-bold text-white disabled:cursor-not-allowed disabled:opacity-40">
          Send
        </button>
      </form>
      {state.notationError && <p className="mt-2 text-xs text-rose-400">{state.notationError}</p>}
      <div className="mt-3 max-h-32 overflow-y-auto rounded-lg bg-slate-950/70 p-2 text-xs text-slate-300">
        {state.moveHistory.length === 0 ? 'No moves entered yet.' : state.moveHistory.map((move, index) => (
          <span key={`${move.timestamp}-${index}`} className="mr-2">{move.san}</span>
        ))}
      </div>
      <div className="mt-3 flex gap-2">
        <button type="button" onClick={onReveal} className="rounded-lg border border-amber-700/60 px-3 py-2 text-xs font-bold text-amber-300">Reveal board</button>
        <button type="button" onClick={onReset} className="rounded-lg border border-slate-700 px-3 py-2 text-xs font-bold text-slate-300">Reset</button>
        {onExit && <button type="button" onClick={onExit} className="ml-auto rounded-lg border border-slate-700 px-3 py-2 text-xs font-bold text-slate-400">Exit</button>}
      </div>
    </section>
  );
};
