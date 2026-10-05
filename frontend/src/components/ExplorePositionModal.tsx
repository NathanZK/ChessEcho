'use client';

import React, { useEffect, useState } from 'react';
import { parseFenInput, parsePgnMainLine, type PgnPly } from '../utils/positionImport';

export interface PositionExplorationStart {
  fen: string;
  sanHistory: string[];
  historyStartFen?: string;
}

interface ExplorePositionModalProps {
  onSubmit: (position: PositionExplorationStart) => void;
  onClose: () => void;
}

type InputMode = 'fen' | 'pgn';

export const ExplorePositionModal: React.FC<ExplorePositionModalProps> = ({
  onSubmit,
  onClose,
}) => {
  const [inputMode, setInputMode] = useState<InputMode>('fen');
  const [fenInput, setFenInput] = useState('');
  const [pgnInput, setPgnInput] = useState('');
  const [pgnStartFen, setPgnStartFen] = useState<string | null>(null);
  const [plies, setPlies] = useState<PgnPly[]>([]);
  const [selectedPly, setSelectedPly] = useState('0');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  const handleModeChange = (mode: InputMode) => {
    setInputMode(mode);
    setError(null);
  };

  const handleFenSubmit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const result = parseFenInput(fenInput);
    if (!result.ok) {
      setError(result.error);
      return;
    }
    onSubmit({ fen: result.fen, sanHistory: [] });
  };

  const handlePgnLoad = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const result = parsePgnMainLine(pgnInput);
    if (!result.ok) {
      setError(result.error);
      setPlies([]);
      setPgnStartFen(null);
      return;
    }
    setError(null);
    setPgnStartFen(result.startFen);
    setPlies(result.plies);
    setSelectedPly('0');
  };

  const handlePgnSubmit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const ply = plies.find((candidate) => String(candidate.ply) === selectedPly);
    if (!ply || !pgnStartFen) {
      setError('Load a valid PGN and choose a position first.');
      return;
    }
    onSubmit({
      fen: ply.fen,
      sanHistory: ply.sanHistory,
      historyStartFen: pgnStartFen,
    });
  };

  const handlePgnFormSubmit = (event: React.FormEvent<HTMLFormElement>) => {
    if (plies.length === 0) {
      handlePgnLoad(event);
    } else {
      handlePgnSubmit(event);
    }
  };

  return (
    <div
      className="fixed inset-0 z-[70] flex items-center justify-center bg-slate-950/80 p-4 backdrop-blur-sm"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <section
        aria-labelledby="explore-position-title"
        aria-modal="true"
        className="w-full max-w-xl rounded-2xl border border-slate-700 bg-slate-900 p-5 shadow-2xl sm:p-7"
        role="dialog"
      >
        <div className="mb-5 flex items-start justify-between gap-4">
          <div>
            <h2 id="explore-position-title" className="text-xl font-bold text-white">
              Explore a position
            </h2>
            <p className="mt-1 text-sm text-slate-400">
              Provide a FEN or choose a position from a PGN game.
            </p>
          </div>
          <button
            aria-label="Close dialog"
            className="rounded-lg px-2 py-1 text-xl leading-none text-slate-400 hover:bg-slate-800 hover:text-white"
            onClick={onClose}
            type="button"
          >
            ×
          </button>
        </div>

        <div aria-label="Position input type" className="mb-5 flex gap-2" role="tablist">
          {(['fen', 'pgn'] as const).map((mode) => (
            <button
              aria-selected={inputMode === mode}
              className={`rounded-lg px-4 py-2 text-sm font-semibold transition ${
                inputMode === mode
                  ? 'bg-emerald-600 text-white'
                  : 'bg-slate-800 text-slate-300 hover:bg-slate-700'
              }`}
              key={mode}
              onClick={() => handleModeChange(mode)}
              role="tab"
              type="button"
            >
              {mode.toUpperCase()}
            </button>
          ))}
        </div>

        {inputMode === 'fen' ? (
          <form className="space-y-4" onSubmit={handleFenSubmit}>
            <label className="block text-sm font-medium text-slate-200" htmlFor="position-fen">
              FEN
            </label>
            <textarea
              autoFocus
              className="min-h-24 w-full resize-y rounded-xl border border-slate-700 bg-slate-950 p-3 font-mono text-sm text-slate-100 placeholder:text-slate-500 focus:border-emerald-500 focus:outline-none"
              id="position-fen"
              onChange={(event) => {
                setFenInput(event.target.value);
                setError(null);
              }}
              placeholder="Paste a FEN position"
              value={fenInput}
            />
            {error && <p className="text-sm text-rose-300" role="alert">{error}</p>}
            <div className="flex justify-end">
              <button
                className="rounded-xl bg-emerald-600 px-5 py-2.5 text-sm font-bold text-white transition hover:bg-emerald-500"
                type="submit"
              >
                Continue from FEN
              </button>
            </div>
          </form>
        ) : (
          <form className="space-y-4" onSubmit={handlePgnFormSubmit}>
            <label className="block text-sm font-medium text-slate-200" htmlFor="position-pgn">
              PGN
            </label>
            <textarea
              className="min-h-36 w-full resize-y rounded-xl border border-slate-700 bg-slate-950 p-3 font-mono text-sm text-slate-100 placeholder:text-slate-500 focus:border-emerald-500 focus:outline-none"
              id="position-pgn"
              onChange={(event) => {
                setPgnInput(event.target.value);
                setPlies([]);
                setPgnStartFen(null);
                setError(null);
              }}
              placeholder="Paste one game's PGN"
              value={pgnInput}
            />
            {error && <p className="text-sm text-rose-300" role="alert">{error}</p>}
            {plies.length > 0 && (
              <div>
                <label
                  className="mb-1.5 block text-sm font-medium text-slate-200"
                  htmlFor="position-pgn-ply"
                >
                  Choose a position
                </label>
                <select
                  className="w-full rounded-xl border border-slate-700 bg-slate-950 p-3 text-sm text-slate-100 focus:border-emerald-500 focus:outline-none"
                  id="position-pgn-ply"
                  onChange={(event) => setSelectedPly(event.target.value)}
                  value={selectedPly}
                >
                  {plies.map((ply) => (
                    <option key={ply.ply} value={ply.ply}>
                      {ply.label}
                    </option>
                  ))}
                </select>
              </div>
            )}
            <div className="flex justify-end gap-2">
              <button
                className="rounded-xl bg-emerald-600 px-5 py-2.5 text-sm font-bold text-white transition hover:bg-emerald-500"
                type="submit"
              >
                {plies.length === 0 ? 'Load PGN' : 'Continue from this position'}
              </button>
            </div>
          </form>
        )}
      </section>
    </div>
  );
};
