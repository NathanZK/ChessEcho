'use client';

import React, { useMemo } from 'react';
import { Chess } from 'chess.js';
import { Swords } from 'lucide-react';
import { ChessBoardView } from './ChessBoardView';
import { ExternalGameLink } from './ExternalGameLink';
import { buildExactPlyChessComUrl } from '../utils/urlUtils';
import {
  resolveGameTimeControl,
  buildExternalGameUrl,
  type GameTimeControlCategory,
} from '../utils/timeControl';
import type { GameSummary } from '../services/api';

export interface GameCardProps {
  game: GameSummary;
  onSelectGame: (gameId: string) => void;
}

const DEFAULT_START_FEN = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';

function computePreviewFen(pgn?: string | null): string {
  if (!pgn || !pgn.trim()) return DEFAULT_START_FEN;
  try {
    const chess = new Chess();
    chess.loadPgn(pgn);
    return chess.fen();
  } catch {
    return DEFAULT_START_FEN;
  }
}

function getResultBadge(result?: string | null) {
  if (!result) return null;
  const normalized = result.trim().toLowerCase();
  if (normalized === 'win' || normalized.startsWith('won') || normalized === '1-0') {
    return {
      label: 'win',
      className: 'bg-primary/15 text-primary border border-primary/30',
    };
  }
  if (normalized === 'loss' || normalized.startsWith('lost') || normalized === '0-1' || normalized === 'checkmated') {
    return {
      label: 'loss',
      className: 'bg-error-container/20 text-rose-400 border border-error-container/40',
    };
  }
  return {
    label: result,
    className: 'bg-amber-500/15 text-amber-400 border border-amber-500/30',
  };
}

function getTimeControlBadgeStyle(category: Exclude<GameTimeControlCategory, 'ALL'>) {
  switch (category) {
    case 'BULLET':
      return 'bg-amber-500/15 text-amber-400 border-amber-500/30';
    case 'BLITZ':
      return 'bg-yellow-500/15 text-yellow-400 border-yellow-500/30';
    case 'RAPID':
      return 'bg-cyan-500/15 text-cyan-400 border-cyan-500/30';
    case 'CLASSICAL':
      return 'bg-purple-500/15 text-purple-400 border-purple-500/30';
  }
}

export const GameCard: React.FC<GameCardProps> = ({ game, onSelectGame }) => {
  const previewFen = useMemo(() => computePreviewFen(game.pgn), [game.pgn]);
  const timeCategory = useMemo(() => resolveGameTimeControl(game.timeControl), [game.timeControl]);
  const externalUrl = useMemo(
    () => buildExactPlyChessComUrl(game.platformGameId, 0, game.timeControl) ?? buildExternalGameUrl(game.platformGameId),
    [game.platformGameId, game.timeControl]
  );
  const resultBadge = useMemo(() => getResultBadge(game.result), [game.result]);

  const playedDate = game.playedAt
    ? new Date(game.playedAt).toLocaleDateString(undefined, {
        year: 'numeric',
        month: 'short',
        day: 'numeric',
      })
    : null;

  return (
    <div className="bg-surface-container-high border border-outline-variant hover:border-primary/40 rounded-xl p-4 transition-all duration-150 flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4">
      {/* Left section: Board preview + Game summary */}
      <div className="flex items-center gap-4 w-full sm:w-auto">
        <div className="w-20 h-20 sm:w-24 sm:h-24 shrink-0 rounded-lg overflow-hidden border border-outline-variant shadow-inner">
          <ChessBoardView fen={previewFen} />
        </div>

        <div className="space-y-1.5 min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <span
              className={`text-[10px] font-mono font-bold uppercase tracking-wider px-2 py-0.5 rounded-full border ${getTimeControlBadgeStyle(
                timeCategory
              )}`}
            >
              {timeCategory}
            </span>
            {resultBadge && (
              <span
                className={`text-[10px] font-mono font-bold uppercase tracking-wider px-2 py-0.5 rounded-full border ${resultBadge.className}`}
              >
                {resultBadge.label}
              </span>
            )}
            {playedDate && (
              <span className="text-[11px] font-mono text-on-surface-variant">
                {playedDate}
              </span>
            )}
          </div>

          <h3 className="font-bold text-sm text-on-surface truncate">
            <span>{game.whiteUsername}</span>
            <span className="text-on-surface-variant font-normal mx-1.5">vs</span>
            <span>{game.blackUsername}</span>
          </h3>

          {externalUrl && (
            <div className="pt-0.5">
              <ExternalGameLink url={externalUrl} platform="chesscom" />
            </div>
          )}
        </div>
      </div>

      {/* Right section: Action button */}
      <div className="w-full sm:w-auto flex sm:flex-col justify-end items-center sm:items-end shrink-0 pt-2 sm:pt-0 border-t sm:border-t-0 border-outline-variant/40">
        <button
          type="button"
          onClick={() => onSelectGame(game.id)}
          className="w-full sm:w-auto px-4 py-2 bg-primary text-on-primary font-bold rounded-lg hover:bg-primary/90 transition flex items-center justify-center gap-1.5 text-xs cursor-pointer shadow-sm"
        >
          <Swords className="w-3.5 h-3.5" aria-hidden="true" />
          <span>Analyze</span>
        </button>
      </div>
    </div>
  );
};
