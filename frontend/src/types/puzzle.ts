export interface AcceptableMove {
  move: string;
  evalLoss: number;
}

export interface MoveBreakdown {
  move: string;
  timesPlayed: number;
  averageLoss: number;
}

export interface Puzzle {
  puzzleId: string;
  source?: 'puzzle' | 'weakness' | 'supplied';
  fen: string;
  playerColor: 'WHITE' | 'BLACK';
  targetMove: string;
  openingTitle: string;
  acceptableMoves: AcceptableMove[];
  movesPlayed: MoveBreakdown[];
  priority: number;
  timesReached: number;
  mistakeCount: number;
  mistakeRate: number;
  gameUrls?: string[];
  evalCp?: number; // Stockfish evaluation in centipawns (e.g. +50 = +0.5 pawns)
}
