'use client';

import React from 'react';
import { Sliders, Clock3 } from 'lucide-react';
import type { DecisionSeverityCategory } from '../utils/gameAnalysisFilters';

export interface GameAnalysisFilterBarProps {
  evalLossThreshold: number;
  onEvalLossThresholdChange: (value: number) => void;
  timingFilters: DecisionSeverityCategory[];
  onToggleTimingFilter: (category: DecisionSeverityCategory) => void;
}

const TIMING_CATEGORIES: DecisionSeverityCategory[] = ['All', 'Minor', 'Significant', 'Critical', 'None'];

export const GameAnalysisFilterBar: React.FC<GameAnalysisFilterBarProps> = ({
  evalLossThreshold,
  onEvalLossThresholdChange,
  timingFilters,
  onToggleTimingFilter,
}) => {
  return (
    <div className="bg-surface-container-high border border-outline-variant rounded-lg p-4 mb-6">
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
        {/* Filter 1: Independent Eval Loss Threshold */}
        <div className="flex-1 max-w-md">
          <div className="flex items-center justify-between mb-1.5">
            <label htmlFor="eval-loss-threshold" className="font-semibold text-primary flex items-center gap-1.5">
              <Sliders className="w-3.5 h-3.5" />
              <span>Eval Loss Threshold</span>
            </label>
            <span className="text-on-surface-variant font-bold">
              {evalLossThreshold.toFixed(2)} pawns
            </span>
          </div>
          <input
            id="eval-loss-threshold"
            aria-label="Eval Loss Threshold"
            type="range"
            min="0.0"
            max="3.0"
            step="0.1"
            value={evalLossThreshold}
            onChange={(e) => onEvalLossThresholdChange(parseFloat(e.target.value))}
            className="w-full h-1.5 bg-surface-container rounded-lg appearance-none cursor-pointer accent-primary"
          />
        </div>

        {/* Filter 2: Independent Decision Timing Categories */}
        <div>
          <div className="font-semibold text-primary mb-1.5 flex items-center gap-1.5">
            <Clock3 className="w-3.5 h-3.5" />
            <span>Decision Timing</span>
          </div>
          <div className="flex flex-wrap items-center gap-1.5">
            {TIMING_CATEGORIES.map((cat) => {
              const isActive = timingFilters.includes(cat);
              return (
                <button
                  key={cat}
                  type="button"
                  onClick={() => onToggleTimingFilter(cat)}
                  className={`px-2.5 py-1 rounded text-[11px] font-semibold border transition cursor-pointer ${
                    isActive
                      ? 'bg-primary/20 border-primary text-primary'
                      : 'bg-surface-container border-outline-variant text-on-surface-variant hover:text-on-surface'
                  }`}
                >
                  {cat}
                </button>
              );
            })}
          </div>
        </div>
      </div>
    </div>
  );
};
