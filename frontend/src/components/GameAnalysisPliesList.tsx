'use client';

import React from 'react';
import { ChevronRight } from 'lucide-react';
import type { AnalyzedPly } from '../services/api';

export interface GameAnalysisPliesListProps {
  totalPliesCount: number;
  qualifyingPlies: AnalyzedPly[];
  selectedPlyNumber: number | null;
  onSelectPly: (plyNumber: number) => void;
}

export const GameAnalysisPliesList: React.FC<GameAnalysisPliesListProps> = ({
  totalPliesCount,
  qualifyingPlies,
  selectedPlyNumber,
  onSelectPly,
}) => {
  return (
    <div className="lg:col-span-5 bg-surface-container-high border border-outline-variant rounded-lg p-4 max-h-[600px] overflow-y-auto">
      <div className="flex items-center justify-between mb-3 pb-2 border-b border-outline-variant">
        <span className="font-semibold text-on-surface">Qualifying Mistake Moves</span>
        <span className="text-on-surface-variant text-[11px] font-bold">
          {qualifyingPlies.length} / {totalPliesCount} moves
        </span>
      </div>

      {qualifyingPlies.length === 0 ? (
        <div className="text-center py-10 text-on-surface-variant text-xs">
          No moves qualify under the current evaluation loss and timing filters.
        </div>
      ) : (
        <div className="space-y-1.5">
          {qualifyingPlies.map((ply) => {
            const isSelected = selectedPlyNumber === ply.plyNumber;
            return (
              <div
                key={ply.plyNumber}
                onClick={() => onSelectPly(ply.plyNumber)}
                className={`p-2.5 rounded border transition cursor-pointer flex items-center justify-between ${
                  isSelected
                    ? 'bg-primary/10 border-primary text-on-surface'
                    : 'bg-surface-container/60 border-outline-variant/60 hover:border-outline-variant text-on-surface-variant hover:text-on-surface'
                }`}
              >
                <div className="flex items-center gap-2">
                  <span className="font-bold text-primary">#{ply.plyNumber}</span>
                  <span className="font-bold text-on-surface">{ply.movePlayed}</span>
                  <span className="text-[10px] uppercase text-on-surface-variant/80">
                    ({ply.playerColor.toLowerCase()})
                  </span>
                </div>

                <div className="flex items-center gap-3">
                  <div className="text-right">
                    <span className="text-error font-semibold block">
                      -{ply.evalLoss !== null && ply.evalLoss !== undefined
                        ? ply.evalLoss.toFixed(2)
                        : '0.00'}
                    </span>
                    <span className="text-[10px] text-on-surface-variant">
                      {ply.decisionSeverity || 'None'}
                    </span>
                  </div>
                  <ChevronRight className="w-3.5 h-3.5 text-on-surface-variant" />
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
};
