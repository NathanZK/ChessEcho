import { Chess, DEFAULT_POSITION, validateFen } from 'chess.js';

export interface PgnPly {
  ply: number;
  moveNumber: number;
  color: 'w' | 'b';
  san: string;
  sanHistory: string[];
  fen: string;
  label: string;
}

export type FenParseResult =
  | { ok: true; fen: string }
  | { ok: false; error: string };

export type PgnParseResult =
  | { ok: true; startFen: string; plies: PgnPly[] }
  | { ok: false; error: string };

const INVALID_PGN = "Couldn't read that PGN — check the move text.";
const MULTIPLE_GAMES = "Only one game is supported — paste a single game's PGN.";
const VARIATIONS_UNSUPPORTED = "PGN variations aren't supported — paste just the main line.";
const TAG_PAIR = /^\[\s*[A-Za-z0-9_]+\s+"(?:\\.|[^"\\])*"\s*\]/;
const TAG_PAIR_ANYWHERE = /\[\s*[A-Za-z0-9_]+\s+"(?:\\.|[^"\\])*"\s*\]/;

export function parseFenInput(input: string): FenParseResult {
  const fen = input.trim();
  if (!fen) {
    return { ok: false, error: 'Enter a FEN position.' };
  }

  const validation = validateFen(fen);
  if (!validation.ok) {
    return { ok: false, error: validation.error ?? 'This FEN is invalid.' };
  }

  try {
    new Chess(fen);
    return { ok: true, fen };
  } catch {
    return { ok: false, error: 'This FEN cannot be used as a chess position.' };
  }
}

export function parsePgnMainLine(input: string): PgnParseResult {
  const pgn = input.trim();
  if (!pgn) {
    return { ok: false, error: 'Paste a PGN game to continue.' };
  }

  const scanned = scanPgn(pgn);
  if (scanned.hasVariation) {
    return { ok: false, error: VARIATIONS_UNSUPPORTED };
  }
  if (scanned.unclosedComment) {
    return { ok: false, error: INVALID_PGN };
  }
  if (hasTagPairAfterInitialHeaders(scanned.visibleText)) {
    return { ok: false, error: MULTIPLE_GAMES };
  }

  try {
    const game = new Chess();
    game.loadPgn(pgn);

    const headers = game.getHeaders();
    const startFen = headers.SetUp === '1' && headers.FEN
      ? headers.FEN
      : DEFAULT_POSITION;
    const start = new Chess(startFen);
    const startFields = start.fen().split(' ');
    const verboseMoves = game.history({ verbose: true });
    const sanHistory: string[] = [];
    const plies: PgnPly[] = [
      {
        ply: 0,
        moveNumber: Number(startFields[5]),
        color: startFields[1] === 'b' ? 'b' : 'w',
        san: '',
        sanHistory: [],
        fen: start.fen(),
        label: 'Start',
      },
    ];

    for (const move of verboseMoves) {
      sanHistory.push(move.san);
      const moveNumber = Number(move.before.split(' ')[5]);
      plies.push({
        ply: sanHistory.length,
        moveNumber,
        color: move.color,
        san: move.san,
        sanHistory: [...sanHistory],
        fen: move.after,
        label: `${moveNumber}${move.color === 'b' ? '...' : '.'} ${move.san}`,
      });
    }

    return { ok: true, startFen: start.fen(), plies };
  } catch {
    return { ok: false, error: INVALID_PGN };
  }
}

function scanPgn(input: string): {
  visibleText: string;
  hasVariation: boolean;
  unclosedComment: boolean;
} {
  let visibleText = '';
  let inBraceComment = false;
  let inLineComment = false;
  let inTag = false;
  let inTagString = false;
  let escaped = false;
  let hasVariation = false;

  for (const char of input) {
    if (inBraceComment) {
      if (char === '}') inBraceComment = false;
      visibleText += ' ';
      continue;
    }
    if (inLineComment) {
      if (char === '\n') {
        inLineComment = false;
        visibleText += '\n';
      } else {
        visibleText += ' ';
      }
      continue;
    }
    if (inTagString) {
      visibleText += char;
      if (escaped) {
        escaped = false;
      } else if (char === '\\') {
        escaped = true;
      } else if (char === '"') {
        inTagString = false;
      }
      continue;
    }

    if (!inTag && char === '{') {
      inBraceComment = true;
      visibleText += ' ';
    } else if (!inTag && char === ';') {
      inLineComment = true;
      visibleText += ' ';
    } else if (!inTag && char === '(') {
      hasVariation = true;
      visibleText += char;
    } else {
      if (char === '[' && !inTag) inTag = true;
      if (char === '"' && inTag) inTagString = true;
      if (char === ']' && inTag) inTag = false;
      visibleText += char;
    }
  }

  return { visibleText, hasVariation, unclosedComment: inBraceComment };
}

function hasTagPairAfterInitialHeaders(pgn: string): boolean {
  let cursor = 0;
  let foundInitialHeader = false;

  while (cursor < pgn.length) {
    while (/\s/.test(pgn[cursor] ?? '')) cursor += 1;
    const match = TAG_PAIR.exec(pgn.slice(cursor));
    if (!match) break;
    foundInitialHeader = true;
    cursor += match[0].length;
  }

  return TAG_PAIR_ANYWHERE.test(pgn.slice(cursor)) || (!foundInitialHeader && TAG_PAIR_ANYWHERE.test(pgn));
}
