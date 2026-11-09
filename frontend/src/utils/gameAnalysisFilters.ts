export type DecisionSeverityCategory = 'All' | 'Minor' | 'Significant' | 'Critical' | 'None';

export const DEFAULT_EVAL_LOSS_THRESHOLD = 0.8;
export const DEFAULT_TIMING_FILTERS: DecisionSeverityCategory[] = ['All'];

export interface AnalyzedPly {
  plyNumber: number;
  movePlayed: string;
  playerColor: 'WHITE' | 'BLACK';
  preMoveFen: string;
  decisionTimeMs?: number | null;
  decisionSeverity?: 'NO_CONCERN' | 'MINOR' | 'SIGNIFICANT' | 'CRITICAL' | 'UNKNOWN' | null;
  bestMove?: string | null;
  bestEvalCp?: number | null;
  evalCp?: number | null;
  evalLoss?: number | null;
  weaknessSeverity?: 'NO_CONCERN' | 'MINOR' | 'SIGNIFICANT' | 'CRITICAL' | null;
}

export function matchesDecisionTimingFilter(
  severity: string | null | undefined,
  selectedFilters: DecisionSeverityCategory[]
): boolean {
  if (selectedFilters.length === 0 || selectedFilters.includes('All')) {
    return true;
  }
  const normalized = severity?.toUpperCase();
  return selectedFilters.some((f) => {
    if (f === 'Minor' && normalized === 'MINOR') return true;
    if (f === 'Significant' && normalized === 'SIGNIFICANT') return true;
    if (f === 'Critical' && normalized === 'CRITICAL') return true;
    if (f === 'None' && (normalized === 'NO_CONCERN' || normalized === 'UNKNOWN' || !normalized)) return true;
    return false;
  });
}

export function matchesEvalLossThreshold(
  evalLoss: number | null | undefined,
  threshold: number
): boolean {
  if (evalLoss === null || evalLoss === undefined) return false;
  return evalLoss >= threshold;
}

export function filterQualifyingPlies(
  plies: AnalyzedPly[],
  evalLossThreshold: number,
  selectedTimingFilters: DecisionSeverityCategory[]
): AnalyzedPly[] {
  return plies.filter(
    (ply) =>
      matchesEvalLossThreshold(ply.evalLoss, evalLossThreshold) &&
      matchesDecisionTimingFilter(ply.decisionSeverity, selectedTimingFilters)
  );
}

export function toggleTimingCategory(
  current: DecisionSeverityCategory[],
  category: DecisionSeverityCategory
): DecisionSeverityCategory[] {
  if (category === 'All') {
    return ['All'];
  }
  const withoutAll = current.filter((c) => c !== 'All');
  if (withoutAll.includes(category)) {
    const updated = withoutAll.filter((c) => c !== category);
    return updated.length === 0 ? ['All'] : updated;
  } else {
    return [...withoutAll, category];
  }
}

export function formatEvaluation(evalCp?: number | null): string {
  if (evalCp === null || evalCp === undefined) return '-';
  const val = evalCp / 100.0;
  return val > 0 ? `+${val.toFixed(2)}` : val.toFixed(2);
}
