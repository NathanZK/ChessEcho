import React from 'react';

export interface StructuralAlphaControlProps {
  alpha: number;
  isLoading?: boolean;
  onAlphaChange: (value: number) => void;
}

export const StructuralAlphaControl: React.FC<StructuralAlphaControlProps> = ({
  alpha,
  isLoading = false,
  onAlphaChange,
}) => {
  return (
    <div className="rounded-xl border border-outline-variant bg-surface-container-low p-3.5 space-y-2">
      <div className="flex items-center justify-between text-xs font-mono">
        <label htmlFor="alpha-slider" className="font-semibold text-on-surface flex items-center gap-1.5">
          <span className="material-symbols-outlined text-primary text-sm" aria-hidden="true">tune</span>
          <span>Structural Weighting (&alpha; = {alpha.toFixed(2)})</span>
        </label>
        <span className="text-on-surface-variant text-[11px]">
          {alpha >= 0.8
            ? 'Pawn Structure Focus'
            : alpha <= 0.2
            ? 'Piece Placement Focus'
            : 'Balanced Structure & Placement'}
        </span>
      </div>
      <input
        id="alpha-slider"
        aria-label="Structural similarity weight"
        type="range"
        min="0"
        max="1"
        step="0.05"
        value={alpha}
        onChange={(e) => onAlphaChange(parseFloat(e.target.value))}
        disabled={isLoading}
        className="w-full accent-primary cursor-pointer disabled:opacity-50"
      />
      <div className="flex justify-between text-[11px] font-mono text-on-surface-variant">
        <span>Piece Placement (0.0)</span>
        <span>Balanced (0.6)</span>
        <span>Pawn Structure (1.0)</span>
      </div>
    </div>
  );
};
