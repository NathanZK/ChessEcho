export interface AcceptableMove {
  move: string;
  evalLoss: number;
}

export interface MoveBreakdown {
  move: string;
  timesPlayed: number;
  averageLoss: number;
  resultingFen?: string | null;
}

export interface Puzzle {
  puzzleId: string;
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


export const MOCK_PUZZLES: Puzzle[] = [
  {
    puzzleId: '8cf3ce9c-1081-4d66-8112-96ed82cc8b9b',
    fen: 'rnbqkbnr/pppppppp/8/8/3P4/8/PPP1PPPP/RNBQKBNR b KQkq d3 0 1',
    playerColor: 'BLACK',
    targetMove: 'Nf6',
    openingTitle: "Queen's Pawn Opening (1.d4)",
    acceptableMoves: [
      { move: 'd5', evalLoss: 0.0 },
      { move: 'Nf6', evalLoss: 0.0 },
      { move: 'e6', evalLoss: 0.05 },
      { move: 'c6', evalLoss: 0.14 },
      { move: 'd6', evalLoss: 0.21 },
      { move: 'g6', evalLoss: 0.25 },
    ],
    movesPlayed: [
      { move: 'e5', timesPlayed: 18, averageLoss: 1.04 },
      { move: 'h5', timesPlayed: 1, averageLoss: 0.85 },
    ],
    priority: 1.39,
    timesReached: 175,
    mistakeCount: 19,
    mistakeRate: 10.86,
    evalCp: 35,
    gameUrls: [
      'https://www.chess.com/game/live/3787250756',
      'https://www.chess.com/game/live/3787254949',
      'https://www.chess.com/game/live/3788769793',
      'https://www.chess.com/game/live/3798480666',
      'https://www.chess.com/game/live/93787704823',
    ],
  },
  {
    puzzleId: 'c1cfe952-f033-490f-ba2e-7af93506da8d',
    fen: 'rnbqkbnr/pppp1ppp/8/4P3/8/8/PPP1PPPP/RNBQKBNR b KQkq - 0 2',
    playerColor: 'BLACK',
    targetMove: 'Nc6',
    openingTitle: 'Englund Gambit Accepted (1.d4 e5 2.dxe5)',
    acceptableMoves: [
      { move: 'Nc6', evalLoss: 0.0 },
      { move: 'Qe7', evalLoss: 0.15 },
    ],
    movesPlayed: [
      { move: 'd6', timesPlayed: 12, averageLoss: 1.45 },
      { move: 'f6', timesPlayed: 5, averageLoss: 1.82 },
    ],
    priority: 1.25,
    timesReached: 17,
    mistakeCount: 17,
    mistakeRate: 100.0,
    evalCp: 120,
    gameUrls: [
      'https://www.chess.com/game/live/171843515308',
      'https://www.chess.com/game/live/171855210941',
    ],
  },
  {
    puzzleId: '8a4f6475-3cc9-4768-b69f-d8b9d285053c',
    fen: 'rnbqkbnr/pp2pppp/2p5/3p4/2PP4/2N5/PP2PPPP/R1BQKBNR b KQkq - 1 3',
    playerColor: 'BLACK',
    targetMove: 'Nf6',
    openingTitle: 'Slav Defense: 3.Nc3 (1.d4 d5 2.c4 c6 3.Nc3)',
    acceptableMoves: [
      { move: 'Nf6', evalLoss: 0.0 },
      { move: 'dxc4', evalLoss: 0.03 },
      { move: 'e6', evalLoss: 0.09 },
      { move: 'a6', evalLoss: 0.19 },
      { move: 'g6', evalLoss: 0.21 },
    ],
    movesPlayed: [
      { move: 'Bf5', timesPlayed: 3, averageLoss: 0.8 },
      { move: 'g6', timesPlayed: 1, averageLoss: 0.21 },
    ],
    priority: 0.58,
    timesReached: 12,
    mistakeCount: 3,
    mistakeRate: 25.0,
    evalCp: 25,
    gameUrls: [
      'https://www.chess.com/game/live/171743093334',
      'https://www.chess.com/game/live/171943665078',
      'https://www.chess.com/game/live/172296989934',
    ],
  },
  {
    puzzleId: '7d48e608-d905-4149-9007-92a24be59a6d',
    fen: 'rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2',
    playerColor: 'BLACK',
    targetMove: 'Nc6',
    openingTitle: "King's Pawn Game (1.e4 e5 2.Nf3)",
    acceptableMoves: [
      { move: 'Nc6', evalLoss: 0.0 },
      { move: 'Nf6', evalLoss: 0.0 },
      { move: 'd6', evalLoss: 0.26 },
    ],
    movesPlayed: [
      { move: 'Bc5', timesPlayed: 3, averageLoss: 1.23 },
      { move: 'f5', timesPlayed: 1, averageLoss: 0.97 },
    ],
    priority: 0.21,
    timesReached: 9,
    mistakeCount: 4,
    mistakeRate: 44.44,
    evalCp: 15,
    gameUrls: [
      'https://www.chess.com/game/live/3794176975',
      'https://www.chess.com/game/live/3794651569',
    ],
  },
];
