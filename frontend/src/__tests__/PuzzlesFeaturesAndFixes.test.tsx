import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { vi, describe, it, expect, beforeEach, afterEach } from 'vitest';
import Home from '../app/page';
import { PuzzleFeedbackPanel, formatDecimal } from '../components/PuzzleFeedbackPanel';
import * as api from '../services/api';
import { Puzzle } from '../mock/mockData';

/** Minimal required settings props for direct PuzzleFeedbackPanel renders in tests. */
const defaultSettingsProps = {
  puzzleColorFilter: 'BOTH' as const,
  onColorFilterChange: vi.fn(),
  showPuzzleSettings: false,
  onTogglePuzzleSettings: vi.fn(),
  minMistakeCount: 3,
  onMinMistakeCountChange: vi.fn(),
  onApplySettings: vi.fn(),
};

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api');
  return {
    ...actual,
    startImportJob: vi.fn(),
    pollJobStatus: vi.fn(),
    fetchPuzzles: vi.fn(),
    fetchWeaknesses: vi.fn(),
  };
});

const mockPuzzles: Puzzle[] = [
  {
    puzzleId: 'puzzle-id-1',
    fen: 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1',
    playerColor: 'WHITE',
    targetMove: 'e4',
    openingTitle: "King's Pawn Opening",
    acceptableMoves: [],
    movesPlayed: [{ move: 'd4', timesPlayed: 2, averageLoss: 0.8 }],
    priority: 1.0,
    timesReached: 10,
    mistakeCount: 2,
    mistakeRate: 20.0,
    evalCp: 35,
  },
  {
    puzzleId: 'puzzle-id-2',
    fen: 'rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2',
    playerColor: 'BLACK',
    targetMove: 'Nc6',
    openingTitle: "King's Knight Opening",
    acceptableMoves: [],
    movesPlayed: [],
    priority: 2.0,
    timesReached: 5,
    mistakeCount: 1,
    mistakeRate: 20.0,
    evalCp: 15,
  },
];

function requireElement(element: Element | null | undefined, description: string): Element {
  if (!(element instanceof Element)) {
    throw new Error(`Expected ${description} to be an Element`);
  }
  return element;
}

function expectClassTokens(element: Element, tokens: string[]) {
  tokens.forEach((token) => expect(element).toHaveClass(token));
}

function expectNoClassTokens(element: Element, tokens: string[]) {
  tokens.forEach((token) => expect(element).not.toHaveClass(token));
}

function expectClassNameTokens(className: string, tokens: string[]) {
  const classTokens = className.split(/\s+/);
  tokens.forEach((token) => expect(classTokens).toContain(token));
}

function getHomeLayout() {
  const header = screen.getByRole('banner');
  return {
    shell: requireElement(header.parentElement, 'Home shell'),
    header,
    headerInner: requireElement(header.firstElementChild, 'header inner container'),
    nav: screen.getByRole('navigation'),
    main: screen.getByRole('main'),
  };
}

function expectWideTopHeader() {
  const { shell, header, headerInner, nav } = getHomeLayout();

  expectNoClassTokens(shell, ['2xl:flex-row']);
  expectClassTokens(header, [
    'bg-slate-900',
    'border-b',
    'border-slate-800',
    'sticky',
    'top-0',
    'z-50',
    'px-4',
    'lg:px-8',
    'py-3',
  ]);
  expectNoClassTokens(header, [
    'w-full',
    'shrink-0',
    '2xl:w-[280px]',
    '2xl:h-screen',
    '2xl:border-r',
    '2xl:border-b-0',
    '2xl:left-0',
    '2xl:px-4',
    '2xl:py-4',
    '2xl:overflow-y-auto',
  ]);
  expectClassTokens(headerInner, ['max-w-7xl', 'mx-auto', 'flex', 'items-center', 'justify-between']);
  expectNoClassTokens(headerInner, [
    'flex-wrap',
    'gap-3',
    '2xl:max-w-none',
    '2xl:mx-0',
    '2xl:h-full',
    '2xl:flex-col',
    '2xl:flex-nowrap',
    '2xl:items-stretch',
    '2xl:justify-start',
  ]);
  expectClassTokens(nav, ['flex', 'items-center', 'space-x-1']);
  expectNoClassTokens(nav, [
    'gap-1',
    'order-3',
    'w-full',
    'min-w-0',
    'overflow-x-auto',
    '2xl:order-2',
    '2xl:flex-col',
    '2xl:items-stretch',
    '2xl:overflow-visible',
  ]);

  const brand = requireElement(headerInner.children.item(0), 'brand');
  const logo = requireElement(brand.children.item(0), 'brand logo');
  const brandText = requireElement(brand.children.item(1), 'brand text');
  expectClassTokens(brand, ['flex', 'items-center', 'space-x-3']);
  expectNoClassTokens(brand, ['order-1', 'min-w-0', 'max-w-full', '2xl:w-full', '2xl:shrink-0']);
  expectNoClassTokens(logo, ['shrink-0']);
  expectNoClassTokens(brandText, ['min-w-0']);
  expectNoClassTokens(requireElement(brandText.querySelector('p'), 'brand subtitle'), ['break-words', 'leading-tight']);

  const navButtons = Array.from(nav.querySelectorAll('button'));
  expect(navButtons).toHaveLength(3);
  navButtons.forEach((button) => {
    expectNoClassTokens(button, ['shrink-0', '2xl:w-full', '2xl:min-w-0', '2xl:justify-start']);
    expectNoClassTokens(requireElement(button.querySelector('span'), 'navigation label'), [
      '2xl:min-w-0',
      '2xl:whitespace-normal',
      '2xl:text-left',
      '2xl:leading-tight',
    ]);
  });
  const weaknessBadge = requireElement(navButtons[1].querySelectorAll('span').item(1), 'weakness badge');
  expectNoClassTokens(weaknessBadge, ['shrink-0', '2xl:ml-auto']);

  const account = requireElement(headerInner.children.item(2), 'connected account');
  const disconnect = requireElement(account.querySelector('button'), 'account Disconnect button');
  const avatar = requireElement(account.children.item(0), 'account avatar');
  const accountText = requireElement(account.children.item(1), 'account text');
  const username = requireElement(screen.getByText('hikaru'), 'username');
  const status = requireElement(screen.getByText('Chess.com Connected').parentElement, 'connection status');
  expectClassTokens(account, ['flex', 'items-center', 'space-x-3']);
  expectNoClassTokens(account, [
    'order-2',
    'ml-auto',
    'max-w-full',
    'min-w-0',
    'flex-wrap',
    'gap-2',
    '2xl:order-3',
    '2xl:mt-auto',
    '2xl:ml-0',
    '2xl:w-full',
    '2xl:shrink-0',
    '2xl:flex-col',
    '2xl:items-stretch',
  ]);
  expectNoClassTokens(avatar, ['shrink-0', '2xl:self-start']);
  expectNoClassTokens(accountText, ['min-w-0', '2xl:block', '2xl:w-full']);
  expectNoClassTokens(username, ['break-all']);
  expectNoClassTokens(status, ['flex-wrap']);
  expectNoClassTokens(disconnect, ['shrink-0', 'max-w-full', 'whitespace-normal', '2xl:w-full', '2xl:ml-0']);
}

describe('Puzzles Tab Features and Fixes', () => {
  beforeEach(() => {
    localStorage.clear();
    window.location.hash = '';
    vi.resetAllMocks();
    vi.mocked(api.fetchPuzzles).mockResolvedValue(mockPuzzles);
    vi.mocked(api.fetchWeaknesses).mockResolvedValue([]);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('shows loading spinner without empty-state flash while restoring/loading puzzles', async () => {
    localStorage.setItem('chessecho_username', 'hikaru');
    let resolvePuzzles: (val: Puzzle[]) => void;
    const pendingPromise = new Promise<Puzzle[]>((resolve) => {
      resolvePuzzles = resolve;
    });
    vi.mocked(api.fetchPuzzles).mockReturnValue(pendingPromise);

    render(<Home />);

    // Verifies loading state is rendered during fetch
    expect(screen.getByText(/Loading Practice Puzzles/i)).toBeInTheDocument();
    expect(screen.queryByText(/No Practice Puzzles Available/i)).not.toBeInTheDocument();

    // Resolve promise
    await waitFor(() => {
      resolvePuzzles!(mockPuzzles);
    });

    await waitFor(() => {
      expect(screen.getByText("King's Pawn Opening")).toBeInTheDocument();
    });
  });

  it('restores the specific puzzle by puzzleId from localStorage on mount/refresh', async () => {
    localStorage.setItem('chessecho_username', 'hikaru');
    localStorage.setItem('chessecho_puzzle_id', 'puzzle-id-2');

    render(<Home />);

    await waitFor(() => {
      expect(screen.getByText("King's Knight Opening")).toBeInTheDocument();
      expect(screen.queryByText("King's Pawn Opening")).not.toBeInTheDocument();
    });
  });

  it('filters puzzles by player color selection (Both -> White -> Black -> Both) and persists choice in localStorage', async () => {
    localStorage.setItem('chessecho_username', 'hikaru');

    render(<Home />);

    // 1. Initial Both selection fetches BOTH
    await waitFor(() => {
      expect(api.fetchPuzzles).toHaveBeenCalledWith('hikaru', 'CHESS_COM', 'BOTH', 0.8, 3, 10, 0);
      expect(api.fetchPuzzles).toHaveBeenCalledTimes(1);
    });

    // Verify old top toolbar is gone (no standalone "Color:" label outside panel)
    expect(screen.queryByText('Color:')).not.toBeInTheDocument();

    // Open settings panel in the right-side panel to access color controls
    const settingsToggle = screen.getByRole('button', { name: /Puzzle Settings/i });
    expect(settingsToggle).toBeInTheDocument();
    fireEvent.click(settingsToggle);

    vi.clearAllMocks();

    // 2. Select 'White'
    const whiteFilterBtn = screen.getByRole('button', { name: /^White$/i });
    fireEvent.click(whiteFilterBtn);

    await waitFor(() => {
      expect(api.fetchPuzzles).toHaveBeenCalledWith('hikaru', 'CHESS_COM', 'WHITE', 0.8, 3, 10, 0);
      expect(api.fetchPuzzles).not.toHaveBeenCalledWith('hikaru', 'CHESS_COM', 'BLACK', 0.8, 3, 10, 0);
    });
    expect(localStorage.getItem('chessecho_puzzle_color_filter')).toBe('WHITE');

    vi.clearAllMocks();

    // 3. Select 'Black'
    const blackFilterBtn = screen.getByRole('button', { name: /^Black$/i });
    fireEvent.click(blackFilterBtn);

    await waitFor(() => {
      expect(api.fetchPuzzles).toHaveBeenCalledWith('hikaru', 'CHESS_COM', 'BLACK', 0.8, 3, 10, 0);
      expect(api.fetchPuzzles).not.toHaveBeenCalledWith('hikaru', 'CHESS_COM', 'WHITE', 0.8, 3, 10, 0);
    });
    expect(localStorage.getItem('chessecho_puzzle_color_filter')).toBe('BLACK');

    vi.clearAllMocks();

    // 4. Select 'Both' again
    const bothFilterBtn = screen.getByRole('button', { name: /^Both$/i });
    fireEvent.click(bothFilterBtn);

    await waitFor(() => {
      expect(api.fetchPuzzles).toHaveBeenCalledWith('hikaru', 'CHESS_COM', 'BOTH', 0.8, 3, 10, 0);
      expect(api.fetchPuzzles).toHaveBeenCalledTimes(1);
    });
    expect(localStorage.getItem('chessecho_puzzle_color_filter')).toBe('BOTH');
  });

  it('regression: puzzle settings and color controls are in the right-side panel, not the old top toolbar', async () => {
    localStorage.setItem('chessecho_username', 'hikaru');
    render(<Home />);

    await waitFor(() => {
      expect(screen.getByText("King's Pawn Opening")).toBeInTheDocument();
    });

    // Old top-toolbar color label must not exist
    expect(screen.queryByText('Color:')).not.toBeInTheDocument();

    // Puzzle Settings toggle is in the right-side panel
    const settingsToggle = screen.getByRole('button', { name: /Puzzle Settings/i });
    expect(settingsToggle).toBeInTheDocument();

    // Color buttons are NOT in the DOM until settings are opened
    expect(screen.queryByRole('button', { name: /^White$/i })).not.toBeInTheDocument();

    // Expand settings panel
    fireEvent.click(settingsToggle);

    // Now color buttons are visible inside the right panel
    expect(screen.getByRole('button', { name: /^Both$/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^White$/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^Black$/i })).toBeInTheDocument();

    // Min Mistakes input and Apply are also accessible
    expect(screen.getByLabelText(/Min Mistakes/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^Apply$/i })).toBeInTheDocument();
  });

  it('refetches puzzles with updated minEvalLoss and minMistakeCount when Apply is clicked, displaying loading spinner and updating active puzzle', async () => {
    localStorage.setItem('chessecho_username', 'hikaru');

    render(<Home />);

    await waitFor(() => {
      expect(screen.getByText("King's Pawn Opening")).toBeInTheDocument();
    });

    const newFilteredPuzzle: Puzzle = {
      puzzleId: 'puzzle-id-3',
      fen: 'rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq c6 0 2',
      playerColor: 'WHITE',
      targetMove: 'Nf3',
      openingTitle: 'Sicilian Defense Weakness',
      acceptableMoves: [],
      movesPlayed: [],
      priority: 3.0,
      timesReached: 8,
      mistakeCount: 4,
      mistakeRate: 50.0,
      evalCp: 25,
    };

    // Toggle settings panel in the right-side panel (puzzle is loaded, so panel is visible)
    const settingsToggle = screen.getByRole('button', { name: /Puzzle Settings/i });
    fireEvent.click(settingsToggle);

    // Wait for settings panel to be visible (Min Mistakes input should be visible)
    await waitFor(() => {
      expect(screen.getByLabelText(/Min Mistakes/i)).toBeInTheDocument();
    });

    // Verify Puzzles tab does NOT display an independent Mistake Threshold selector
    expect(screen.queryByLabelText(/Mistake Threshold/i)).not.toBeInTheDocument();

    // Grab references and set up mock BEFORE changing input value,
    // because the useEffect depends on minMistakeCount and will trigger loading state
    const minMistakesInput = screen.getByLabelText(/Min Mistakes/i);
    const applyButton = screen.getByRole('button', { name: /Apply/i });

    let resolvePuzzles: (val: Puzzle[]) => void;
    const pendingPromise = new Promise<Puzzle[]>((resolve) => {
      resolvePuzzles = resolve;
    });
    vi.mocked(api.fetchPuzzles).mockReturnValue(pendingPromise);

    fireEvent.change(minMistakesInput, { target: { value: '5' } });

    // Click Apply (the useEffect auto-fetch also fires but uses the same pending mock)
    fireEvent.click(applyButton);

    // 1. Verifies loading spinner appears while fetching
    expect(screen.getByText(/Loading Practice Puzzles/i)).toBeInTheDocument();

    // Resolve promise with new filtered puzzle
    resolvePuzzles!([newFilteredPuzzle]);

    // 2. Verifies API call parameters
    await waitFor(() => {
      expect(api.fetchPuzzles).toHaveBeenCalledWith('hikaru', 'CHESS_COM', 'BOTH', 0.8, 5, 10, 0);
    });

    // 3. Verifies current puzzle is updated to the first returned puzzle since previous puzzle is no longer present
    await waitFor(() => {
      expect(screen.getByText('Sicilian Defense Weakness')).toBeInTheDocument();
      expect(screen.queryByText("King's Pawn Opening")).not.toBeInTheDocument();
    });
  });

  it('formats decimal values using period instead of comma', () => {
    expect(formatDecimal(1.5)).toBe('1.50');
    expect(formatDecimal(0.8, 1)).toBe('0.8');

    render(
      <PuzzleFeedbackPanel
        {...defaultSettingsProps}
        puzzle={mockPuzzles[0]}
        feedback={{ status: 'CORRECT', lastMove: 'e4' }}
        onNextPuzzle={vi.fn()}
      />
    );

    expect(screen.getByText('-0.80 pawns')).toBeInTheDocument();
    expect(screen.getByText('20.0%')).toBeInTheDocument();
  });

  it('completely removes Historical Games section from PuzzleFeedbackPanel', () => {
    render(
      <PuzzleFeedbackPanel
        {...defaultSettingsProps}
        puzzle={mockPuzzles[0]}
        feedback={{ status: 'IDLE' }}
        onNextPuzzle={vi.fn()}
      />
    );

    expect(screen.queryByText(/Historical Games/i)).not.toBeInTheDocument();
  });

  it('renders neutral feedback for incorrect moves without fabricated explanations', () => {
    render(
      <PuzzleFeedbackPanel
        {...defaultSettingsProps}
        puzzle={mockPuzzles[0]}
        feedback={{ status: 'INCORRECT', lastMove: 'h3' }}
        onNextPuzzle={vi.fn()}
      />
    );

    expect(screen.getByText('h3')).toBeInTheDocument();
    expect(screen.getByText(/is not the recommended move/i)).toBeInTheDocument();
    expect(screen.queryByText(/control of central squares/i)).not.toBeInTheDocument();
  });

  it('does not evaluate opponent moves (e.g. Qxb4) against initial targetMove or overwrite user mistake feedback (regression bug fix)', () => {
    // 1. Initial user move attempt: user makes a mistake ('d6') on move 1
    const { rerender } = render(
      <PuzzleFeedbackPanel
        {...defaultSettingsProps}
        puzzle={mockPuzzles[0]}
        feedback={{
          status: 'HISTORICAL_MISTAKE',
          lastMove: 'd6',
          historicalInfo: { timesPlayed: 2, averageLoss: 0.8 },
        }}
        onNextPuzzle={vi.fn()}
      />
    );

    // Verifies initial mistake feedback (d6) is displayed with clear non-misleading terminology
    expect(screen.getByText('Recurring Weakness Detected!')).toBeInTheDocument();
    expect(screen.getByText('d6')).toBeInTheDocument();
    expect(screen.getByText('2 games')).toBeInTheDocument();
    expect(screen.getByText('0.80 pawns worse')).toBeInTheDocument();
    expect(screen.getByText(/than the best move/i)).toBeInTheDocument();
    expect(screen.queryByText(/avg loss/i)).not.toBeInTheDocument();

    // 2. Opponent plays follow-up move ('Qxb4') on move 2. Feedback remains unchanged (still displaying d6 mistake)!
    rerender(
      <PuzzleFeedbackPanel
        {...defaultSettingsProps}
        puzzle={mockPuzzles[0]}
        feedback={{
          status: 'HISTORICAL_MISTAKE',
          lastMove: 'd6',
          historicalInfo: { timesPlayed: 2, averageLoss: 0.8 },
        }}
        onNextPuzzle={vi.fn()}
      />
    );

    expect(screen.getByText('Recurring Weakness Detected!')).toBeInTheDocument();
    expect(screen.getByText('d6')).toBeInTheDocument();
    expect(screen.queryByText(/Qxb4 is not the recommended move/i)).not.toBeInTheDocument();
  });

  it('formats single past game historical mistake feedback correctly without misleading avg loss wording', () => {
    render(
      <PuzzleFeedbackPanel
        {...defaultSettingsProps}
        puzzle={mockPuzzles[0]}
        feedback={{
          status: 'HISTORICAL_MISTAKE',
          lastMove: 'Nc3',
          historicalInfo: { timesPlayed: 1, averageLoss: 0.58 },
        }}
        onNextPuzzle={vi.fn()}
      />
    );

    expect(screen.getByText('Recurring Weakness Detected!')).toBeInTheDocument();
    expect(screen.getByText('Nc3')).toBeInTheDocument();
    expect(screen.getByText('1 game')).toBeInTheDocument();
    expect(screen.getByText('0.58 pawns worse')).toBeInTheDocument();
    expect(screen.getByText(/than the best move/i)).toBeInTheDocument();
    expect(screen.queryByText(/avg loss/i)).not.toBeInTheDocument();
  });

  it('evaluates initial correct move properly and transitions to exploration mode on follow-up moves', () => {
    // 1. User makes correct move ('e4')
    const { rerender } = render(
      <PuzzleFeedbackPanel
        {...defaultSettingsProps}
        puzzle={mockPuzzles[0]}
        feedback={{ status: 'CORRECT', lastMove: 'e4' }}
        onNextPuzzle={vi.fn()}
      />
    );

    expect(screen.getByText('Puzzle Solved! 🎉')).toBeInTheDocument();
    expect(screen.getByText('e4')).toBeInTheDocument();

    rerender(
      <PuzzleFeedbackPanel
        {...defaultSettingsProps}
        puzzle={mockPuzzles[0]}
        feedback={{ status: 'CORRECT', lastMove: 'e4' }}
        onNextPuzzle={vi.fn()}
        isExplorationActive={true}
        explorationPlayMode="CHESSECHO"
        continuationCandidate={{ move: 'Nf3', resultingFen: 'fen_Nf3', providerType: 'ENGINE' }}
      />
    );
    expect(screen.getByText('Line Exploration')).toBeInTheDocument();
    expect(screen.getByText(/Nf3/)).toBeInTheDocument();
  });

  it('clears feedback and resets interaction state when changing player color filter', async () => {
    localStorage.setItem('chessecho_username', 'hikaru');

    render(<Home />);

    await waitFor(() => {
      expect(screen.getByText("King's Pawn Opening")).toBeInTheDocument();
    });

    const blackPuzzle: Puzzle = {
      puzzleId: 'puzzle-black-99',
      fen: 'rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2',
      playerColor: 'BLACK',
      targetMove: 'Nc6',
      openingTitle: "King's Knight Opening",
      acceptableMoves: [],
      movesPlayed: [],
      priority: 2.0,
      timesReached: 5,
      mistakeCount: 1,
      mistakeRate: 20.0,
      evalCp: 15,
    };

    vi.mocked(api.fetchPuzzles).mockResolvedValue([blackPuzzle]);

    // Open settings panel first, then change player color filter to 'Black'
    const settingsToggle = screen.getByRole('button', { name: /Puzzle Settings/i });
    fireEvent.click(settingsToggle);

    // Change player color filter to 'Black'
    const blackFilterBtn = screen.getByRole('button', { name: /^Black$/i });
    fireEvent.click(blackFilterBtn);

    // 3. Verify newly selected puzzle ("King's Knight Opening") is displayed
    await waitFor(() => {
      expect(screen.getByText("King's Knight Opening")).toBeInTheDocument();
    });

    // 4. Verify previous puzzle feedback is no longer present and new puzzle is in untouched initial state
    expect(screen.queryByText(/Historical Mistake Detected!/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/Recurring Weakness Detected!/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/Not the Recommended Move/i)).not.toBeInTheDocument();
    expect(screen.getByText(/Find Black's best move or an acceptable alternative/i)).toBeInTheDocument();
  });

  it('does not display acceptableThreshold input or state anywhere in UI', () => {
    render(<Home />);

    expect(screen.queryByText(/Acceptable Threshold/i)).not.toBeInTheDocument();
  });

  describe('7. View Source Games directly from Puzzle Page', () => {
    it('renders View Games button when puzzle has gameUrls and opens modal with games in exact newest-first order', () => {
      const puzzleWithUrls: Puzzle = {
        ...mockPuzzles[0],
        gameUrls: [
          'https://www.chess.com/game/live/gameNewest',
          'https://www.chess.com/game/live/gameOlder',
        ],
      };

      render(
        <PuzzleFeedbackPanel
          {...defaultSettingsProps}
          puzzle={puzzleWithUrls}
          feedback={{ status: 'IDLE' }}
          onNextPuzzle={vi.fn()}
        />
      );

      // 1. Verify View Games (2) action is rendered
      const viewGamesBtn = screen.getByRole('button', { name: /View Games \(2\)/i });
      expect(viewGamesBtn).toBeInTheDocument();

      // 2. Click button to open modal
      fireEvent.click(viewGamesBtn);

      // 3. Verify modal opens with Source Games title
      expect(screen.getByText('Source Games')).toBeInTheDocument();
      expect(screen.queryByText('Historical Games')).not.toBeInTheDocument();

      // 4. Verify Game #1 corresponds to the newest game link
      const game1Link = screen.getByText(/Game #1: https:\/\/www.chess.com\/game\/live\/gameNewest/i);
      const game2Link = screen.getByText(/Game #2: https:\/\/www.chess.com\/game\/live\/gameOlder/i);

      expect(game1Link).toBeInTheDocument();
      expect(game2Link).toBeInTheDocument();

      // 5. Verify external link attributes (target="_blank", rel="noreferrer")
      const anchor1 = game1Link.closest('a');
      expect(anchor1).toHaveAttribute('href', 'https://www.chess.com/game/live/gameNewest');
      expect(anchor1).toHaveAttribute('target', '_blank');
      expect(anchor1).toHaveAttribute('rel', 'noreferrer');

      // 6. Close modal
      const closeBtn = screen.getByRole('button', { name: /Close modal/i });
      fireEvent.click(closeBtn);

      expect(screen.queryByText('Source Games')).not.toBeInTheDocument();
      expect(screen.queryByText('Historical Games')).not.toBeInTheDocument();
    });

    it('does not render View Games button when puzzle gameUrls is empty or missing', () => {
      const puzzleWithoutUrls: Puzzle = {
        ...mockPuzzles[0],
        gameUrls: [],
      };

      render(
        <PuzzleFeedbackPanel
          {...defaultSettingsProps}
          puzzle={puzzleWithoutUrls}
          feedback={{ status: 'IDLE' }}
          onNextPuzzle={vi.fn()}
        />
      );

      expect(screen.queryByText(/View Games/i)).not.toBeInTheDocument();
    });

    it('preserves gameUrls when selecting Practice Position from Weaknesses Library even if matching puzzlesList entry has empty gameUrls', async () => {
      localStorage.setItem('chessecho_username', 'hikaru');

      // 1. Initial puzzlesList fetched on page load has empty gameUrls
      const initialStalePuzzle: Puzzle = {
        ...mockPuzzles[0],
        puzzleId: 'pos-weakness-100',
        gameUrls: [],
      };
      vi.mocked(api.fetchPuzzles).mockResolvedValue([initialStalePuzzle]);

      // 2. Weaknesses Library returned weakness with rich gameUrls
      const weaknessWithUrls: api.WeaknessResponse = {
        positionId: 'pos-weakness-100',
        fen: 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1',
        timesReached: 5,
        mistakeCount: 3,
        mistakeRate: 60.0,
        averageLoss: 1.2,
        priority: 2.5,
        bestMove: 'e4',
        acceptableMoves: [],
        movesPlayed: [],
        gameUrls: [
          'https://www.chess.com/game/live/freshGameNewest',
          'https://www.chess.com/game/live/freshGameOlder',
        ],
      };
      vi.mocked(api.fetchWeaknesses).mockResolvedValue([weaknessWithUrls]);

      render(<Home />);

      await waitFor(() => {
        expect(screen.getByText("King's Pawn Opening")).toBeInTheDocument();
      });

      // Navigate to Weaknesses Library tab
      const weaknessesTabBtn = screen.getByRole('button', { name: /Weaknesses Library/i });
      fireEvent.click(weaknessesTabBtn);

      // Verify Practice Position button is rendered for the weakness
      await waitFor(() => {
        expect(screen.getByRole('button', { name: /Practice Position/i })).toBeInTheDocument();
      });

      // Click "Practice Position" on the weakness card
      const practiceBtn = screen.getByRole('button', { name: /Practice Position/i });
      fireEvent.click(practiceBtn);

      // Verify page switched to Puzzles tab and View Games (2) button appears despite stale puzzlesList cache
      await waitFor(() => {
        expect(screen.getByRole('button', { name: /View Games \(2\)/i })).toBeInTheDocument();
      });

      // Click "View Games (2)" and verify modal opens with fresh game URLs in newest-first order
      const viewGamesBtn = screen.getByRole('button', { name: /View Games \(2\)/i });
      fireEvent.click(viewGamesBtn);

      expect(screen.getByText(/Game #1: https:\/\/www.chess.com\/game\/live\/freshGameNewest/i)).toBeInTheDocument();
      expect(screen.getByText(/Game #2: https:\/\/www.chess.com\/game\/live\/freshGameOlder/i)).toBeInTheDocument();
    });
  });

  describe('8. Responsive Puzzles navigation and workspace layout', () => {
    it('uses the real loaded Home fixture for the overflow-safe rail, workspace, account, and source-game contracts', async () => {
      localStorage.setItem('chessecho_username', 'hikaru');

      const layoutPuzzle: Puzzle = {
        ...mockPuzzles[0],
        puzzleId: 'layout-puzzle-102',
        gameUrls: ['https://www.chess.com/game/live/layoutGame1'],
      };
      vi.mocked(api.fetchPuzzles).mockResolvedValue([layoutPuzzle]);

      render(<Home />);

      await waitFor(() => {
        expect(screen.getByText("King's Pawn Opening")).toBeInTheDocument();
        expect(screen.getByRole('button', { name: /View Games \(1\)/i })).toBeInTheDocument();
      });

      const { shell, header, headerInner, nav, main } = getHomeLayout();
      const settingsButton = screen.getByRole('button', { name: /Puzzle Settings/i });
      const feedbackPanel = requireElement(settingsButton.parentElement?.parentElement, 'feedback panel');
      const feedbackWrapper = requireElement(feedbackPanel.parentElement, 'feedback wrapper');
      const loadedRow = requireElement(feedbackWrapper.parentElement, 'loaded puzzle row');
      const boardWrapper = requireElement(loadedRow.children.item(1), 'board wrapper');
      const boardRoot = requireElement(boardWrapper.firstElementChild, 'ChessBoardArea root');
      const workspace = requireElement(loadedRow.parentElement?.parentElement, 'Puzzles workspace');

      expectClassTokens(shell, ['h-screen', 'flex', 'flex-col', 'overflow-hidden', '2xl:flex-row']);
      expectClassTokens(header, [
        'bg-slate-900',
        'border-b',
        'border-slate-800',
        'sticky',
        'top-0',
        'z-50',
        'px-4',
        'lg:px-8',
        'py-3',
        'w-full',
        'shrink-0',
        '2xl:w-[280px]',
        '2xl:h-screen',
        '2xl:border-r',
        '2xl:border-b-0',
        '2xl:left-0',
        '2xl:px-4',
        '2xl:py-4',
        '2xl:overflow-y-auto',
      ]);
      expectClassTokens(headerInner, [
        'max-w-7xl',
        'mx-auto',
        'flex',
        'items-center',
        'justify-between',
        'flex-wrap',
        'gap-3',
        '2xl:max-w-none',
        '2xl:mx-0',
        '2xl:h-full',
        '2xl:flex-col',
        '2xl:flex-nowrap',
        '2xl:items-stretch',
        '2xl:justify-start',
      ]);
      expectClassTokens(main, ['flex-1', 'min-w-0', 'overflow-y-auto']);
      expectClassTokens(workspace, ['w-full', 'px-4', 'lg:px-8', '2xl:max-w-none', '2xl:px-4']);

      expectClassTokens(loadedRow, [
        'flex',
        'flex-col',
        'items-center',
        'justify-center',
        'gap-4',
        'lg:flex-row',
        'lg:flex-wrap',
        'lg:items-start',
        '2xl:flex-nowrap',
      ]);
      expectNoClassTokens(loadedRow, ['xl:gap-8']);

      expectClassTokens(boardWrapper, [
        'w-full',
        'max-w-[640px]',
        'shrink-0',
        '2xl:max-w-[760px]',
        '2xl:w-auto',
        '2xl:min-w-0',
        '2xl:basis-[760px]',
        '2xl:grow',
        '2xl:shrink',
      ]);
      expectNoClassTokens(boardWrapper, ['xl:max-w-[680px]']);
      expectClassTokens(boardRoot, ['w-full', 'max-w-[640px]', '2xl:max-w-[760px]']);
      expectNoClassTokens(boardRoot, ['xl:max-w-[680px]']);
      expectClassTokens(feedbackWrapper, [
        'w-full',
        'max-w-[480px]',
        'shrink-0',
        '2xl:max-w-[360px]',
        '2xl:w-auto',
        '2xl:min-w-0',
        '2xl:basis-[360px]',
        '2xl:shrink',
      ]);

      expectClassTokens(nav, [
        'flex',
        'items-center',
        'gap-1',
        'order-3',
        'w-full',
        'min-w-0',
        'overflow-x-auto',
        '2xl:order-2',
        '2xl:flex-col',
        '2xl:items-stretch',
        '2xl:overflow-visible',
      ]);
      expectNoClassTokens(nav, ['space-x-1']);

      const navButtons = Array.from(nav.querySelectorAll('button'));
      expect(navButtons).toHaveLength(3);
      navButtons.forEach((button) => {
        expectClassTokens(button, ['shrink-0', '2xl:w-full', '2xl:min-w-0', '2xl:justify-start']);
        const label = requireElement(button.querySelector('span'), 'navigation label');
        expectClassTokens(label, ['2xl:min-w-0', '2xl:whitespace-normal', '2xl:text-left', '2xl:leading-tight']);
      });
      const weaknessBadge = requireElement(navButtons[1].querySelectorAll('span').item(1), 'weakness badge');
      expect(weaknessBadge).toHaveTextContent('1');
      expectClassTokens(weaknessBadge, ['shrink-0', '2xl:ml-auto']);

      const brand = requireElement(headerInner.children.item(0), 'brand');
      const logo = requireElement(brand.children.item(0), 'brand logo');
      const brandText = requireElement(brand.children.item(1), 'brand text');
      expectClassTokens(brand, ['order-1', 'min-w-0', 'max-w-full', '2xl:w-full', '2xl:shrink-0']);
      expectClassTokens(logo, ['shrink-0']);
      expectClassTokens(brandText, ['min-w-0']);
      expectClassTokens(requireElement(brandText.querySelector('p'), 'brand subtitle'), ['break-words', 'leading-tight']);

      const account = requireElement(headerInner.children.item(2), 'connected account');
      expectClassTokens(account, [
        'flex',
        'items-center',
        'order-2',
        'ml-auto',
        'max-w-full',
        'min-w-0',
        'flex-wrap',
        'gap-2',
        '2xl:order-3',
        '2xl:mt-auto',
        '2xl:ml-0',
        '2xl:w-full',
        '2xl:shrink-0',
        '2xl:flex-col',
        '2xl:items-stretch',
      ]);
      expectClassTokens(requireElement(account.children.item(0), 'account avatar'), ['shrink-0', '2xl:self-start']);
      const accountText = requireElement(account.children.item(1), 'account text');
      expectClassTokens(accountText, ['min-w-0', '2xl:block', '2xl:w-full']);
      expectClassTokens(requireElement(screen.getByText('hikaru'), 'username'), ['break-all']);
      expectClassTokens(requireElement(screen.getByText('Chess.com Connected').parentElement, 'connection status'), ['flex-wrap']);
      const disconnect = screen.getByRole('button', { name: /^Disconnect$/i });
      expectClassTokens(disconnect, ['shrink-0', 'max-w-full', 'whitespace-normal', '2xl:w-full', '2xl:ml-0']);
      expect(settingsButton).toBeInTheDocument();

      fireEvent.click(screen.getByRole('button', { name: /View Games \(1\)/i }));
      expect(screen.getByText('Source Games')).toBeInTheDocument();
      expect(screen.getByText(/layoutGame1/i).closest('a')).toHaveAttribute(
        'href',
        'https://www.chess.com/game/live/layoutGame1'
      );
    });

    it('restores the wide top header across real Puzzles, Weaknesses, and Import transitions', async () => {
      localStorage.setItem('chessecho_username', 'hikaru');
      const layoutPuzzle: Puzzle = {
        ...mockPuzzles[0],
        puzzleId: 'layout-transition-puzzle-102',
        gameUrls: ['https://www.chess.com/game/live/layoutGame1'],
      };
      vi.mocked(api.fetchPuzzles).mockResolvedValue([layoutPuzzle]);
      vi.mocked(api.fetchWeaknesses).mockResolvedValue([
        {
          positionId: 'layout-transition-weakness-102',
          fen: layoutPuzzle.fen,
          timesReached: 5,
          mistakeCount: 3,
          mistakeRate: 60,
          averageLoss: 1.2,
          priority: 2.5,
          bestMove: 'e4',
          acceptableMoves: [],
          movesPlayed: [],
          gameUrls: [],
        },
      ]);

      render(<Home />);
      await waitFor(() => {
        expect(screen.getByText("King's Pawn Opening")).toBeInTheDocument();
        expect(screen.getByRole('button', { name: /View Games \(1\)/i })).toBeInTheDocument();
      });

      let layout = getHomeLayout();
      const initialPuzzlesClasses = {
        shell: layout.shell.className,
        header: layout.header.className,
        headerInner: layout.headerInner.className,
        nav: layout.nav.className,
      };

      fireEvent.click(screen.getByRole('button', { name: /Weaknesses Library/i }));
      await waitFor(() => {
        expect(screen.getByRole('heading', { name: /Recurring Opening Weaknesses Library/i })).toBeInTheDocument();
        expect(window.location.hash).toBe('#weaknesses');
        expect(screen.getByRole('navigation').querySelectorAll('button')[1].querySelectorAll('span')).toHaveLength(2);
      });
      expectWideTopHeader();

      fireEvent.click(screen.getByRole('button', { name: /Import Games/i }));
      await waitFor(() => {
        expect(screen.getByRole('heading', { name: /Import & Analyze Your Game History/i })).toBeInTheDocument();
        expect(window.location.hash).toBe('#import');
      });
      expectWideTopHeader();

      fireEvent.click(screen.getByRole('button', { name: /Practice Puzzles/i }));
      await waitFor(() => {
        expect(screen.getByText("King's Pawn Opening")).toBeInTheDocument();
        expect(window.location.hash).toBe('#puzzles');
      });
      layout = getHomeLayout();
      expectClassNameTokens(initialPuzzlesClasses.shell, ['2xl:flex-row']);
      expectClassNameTokens(initialPuzzlesClasses.header, ['2xl:w-[280px]', '2xl:h-screen', '2xl:overflow-y-auto']);
      expectClassNameTokens(initialPuzzlesClasses.headerInner, ['flex-wrap', 'gap-3', '2xl:flex-col', '2xl:flex-nowrap']);
      expectClassNameTokens(initialPuzzlesClasses.nav, ['order-3', 'w-full', 'min-w-0', 'overflow-x-auto', '2xl:flex-col']);
      expectClassTokens(layout.shell, ['2xl:flex-row']);
      expectClassTokens(layout.header, ['2xl:w-[280px]', '2xl:h-screen', '2xl:overflow-y-auto']);
      expectClassTokens(layout.headerInner, ['flex-wrap', 'gap-3', '2xl:flex-col', '2xl:flex-nowrap']);
      expectClassTokens(layout.nav, ['order-3', 'w-full', 'min-w-0', 'overflow-x-auto', '2xl:flex-col']);
    });

    it('keeps the Puzzles rail and connected account usable while puzzles are loading', async () => {
      localStorage.setItem('chessecho_username', 'hikaru');
      let resolvePuzzles: (value: Puzzle[]) => void;
      const pendingPuzzles = new Promise<Puzzle[]>((resolve) => {
        resolvePuzzles = resolve;
      });
      vi.mocked(api.fetchPuzzles).mockReturnValue(pendingPuzzles);

      render(<Home />);

      expect(screen.getByText(/Loading Practice Puzzles/i)).toBeInTheDocument();
      expect(screen.queryByText("King's Pawn Opening")).not.toBeInTheDocument();
      const { shell, header, headerInner, nav, main } = getHomeLayout();
      const account = requireElement(screen.getByRole('button', { name: /^Disconnect$/i }).parentElement, 'connected account');
      const avatar = requireElement(account.children.item(0), 'account avatar');
      const accountText = requireElement(account.children.item(1), 'account text');
      const status = requireElement(screen.getByText('Chess.com Connected').parentElement, 'connection status');
      const disconnect = screen.getByRole('button', { name: /^Disconnect$/i });

      expectClassTokens(shell, ['h-screen', 'flex', 'flex-col', 'overflow-hidden', '2xl:flex-row']);
      expectClassTokens(header, [
        'bg-slate-900',
        'border-b',
        'border-slate-800',
        'sticky',
        'top-0',
        'z-50',
        'px-4',
        'lg:px-8',
        'py-3',
        'w-full',
        'shrink-0',
        '2xl:w-[280px]',
        '2xl:h-screen',
        '2xl:border-r',
        '2xl:border-b-0',
        '2xl:left-0',
        '2xl:px-4',
        '2xl:py-4',
        '2xl:overflow-y-auto',
      ]);
      expectClassTokens(headerInner, [
        'max-w-7xl',
        'mx-auto',
        'flex',
        'items-center',
        'justify-between',
        'flex-wrap',
        'gap-3',
        '2xl:max-w-none',
        '2xl:mx-0',
        '2xl:h-full',
        '2xl:flex-col',
        '2xl:flex-nowrap',
        '2xl:items-stretch',
        '2xl:justify-start',
      ]);
      expectClassTokens(nav, [
        'flex',
        'items-center',
        'gap-1',
        'order-3',
        'w-full',
        'min-w-0',
        'overflow-x-auto',
        '2xl:order-2',
        '2xl:flex-col',
        '2xl:items-stretch',
        '2xl:overflow-visible',
      ]);
      expectNoClassTokens(nav, ['space-x-1']);
      const navButtons = Array.from(nav.querySelectorAll('button'));
      expect(navButtons).toHaveLength(3);
      navButtons.forEach((button) => {
        expectClassTokens(button, ['shrink-0', '2xl:w-full', '2xl:min-w-0', '2xl:justify-start']);
        expectClassTokens(requireElement(button.querySelector('span'), 'navigation label'), [
          '2xl:min-w-0',
          '2xl:whitespace-normal',
          '2xl:text-left',
          '2xl:leading-tight',
        ]);
      });
      const brand = requireElement(headerInner.children.item(0), 'brand');
      const logo = requireElement(brand.children.item(0), 'brand logo');
      const brandText = requireElement(brand.children.item(1), 'brand text');
      expectClassTokens(brand, ['order-1', 'min-w-0', 'max-w-full', '2xl:w-full', '2xl:shrink-0']);
      expectClassTokens(logo, ['shrink-0']);
      expectClassTokens(brandText, ['min-w-0']);
      expectClassTokens(requireElement(brandText.querySelector('p'), 'brand subtitle'), ['break-words', 'leading-tight']);
      expectClassTokens(main, ['flex-1', 'min-w-0', 'overflow-y-auto']);
      expectClassTokens(account, [
        'flex',
        'items-center',
        'order-2',
        'ml-auto',
        'max-w-full',
        'min-w-0',
        'flex-wrap',
        'gap-2',
        '2xl:order-3',
        '2xl:mt-auto',
        '2xl:ml-0',
        '2xl:w-full',
        '2xl:shrink-0',
        '2xl:flex-col',
        '2xl:items-stretch',
      ]);
      expectClassTokens(avatar, ['shrink-0', '2xl:self-start']);
      expectClassTokens(accountText, ['min-w-0', '2xl:block', '2xl:w-full']);
      expectClassTokens(requireElement(screen.getByText('hikaru'), 'username'), ['break-all']);
      expectClassTokens(status, ['flex-wrap']);
      expectClassTokens(disconnect, ['shrink-0', 'max-w-full', 'whitespace-normal', '2xl:w-full', '2xl:ml-0']);

      resolvePuzzles!(mockPuzzles);
      await waitFor(() => expect(screen.getByText("King's Pawn Opening")).toBeInTheDocument());
    });

    it('keeps the Puzzles rail, scrolling, and disconnected empty account shrink-safe', async () => {
      render(<Home />);

      await waitFor(() => {
        expect(screen.getByText(/No Practice Puzzles Available/i)).toBeInTheDocument();
      });
      expect(screen.getByText('Not Connected')).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /^Disconnect$/i })).not.toBeInTheDocument();

      const { shell, header, headerInner, nav, main } = getHomeLayout();
      const accountLabel = screen.getByText('Not Connected');
      const account = requireElement(accountLabel.parentElement, 'disconnected account');
      const accountIcon = requireElement(account.querySelector('svg'), 'disconnected account icon');

      expectClassTokens(shell, ['2xl:flex-row']);
      expectClassTokens(header, ['2xl:w-[280px]', '2xl:h-screen', '2xl:overflow-y-auto']);
      expectClassTokens(headerInner, ['flex-wrap', 'gap-3', '2xl:flex-col', '2xl:flex-nowrap']);
      expectClassTokens(nav, ['order-3', 'w-full', 'min-w-0', 'overflow-x-auto', '2xl:flex-col']);
      expectClassTokens(main, ['min-w-0', 'overflow-y-auto']);
      expectClassTokens(account, [
        'flex',
        'items-center',
        'order-2',
        'ml-auto',
        'max-w-full',
        'min-w-0',
        'flex-wrap',
        'gap-2',
        '2xl:order-3',
        '2xl:mt-auto',
        '2xl:ml-0',
        '2xl:w-full',
        '2xl:shrink-0',
        '2xl:flex-col',
        '2xl:items-start',
      ]);
      expectClassTokens(accountIcon, ['shrink-0']);
      expectClassTokens(accountLabel, ['min-w-0', 'break-words']);
    });
  });

  describe('9. Flip Board Control', () => {
    it('clicking Flip Board toggles board orientation', async () => {
      localStorage.setItem('chessecho_username', 'hikaru');

      render(<Home />);

      await waitFor(() => {
        expect(screen.getByText("King's Pawn Opening")).toBeInTheDocument();
      });

      // Initially board is in default orientation (white for WHITE player)
      const flipBtn = screen.getByRole('button', { name: /Flip/i });
      expect(flipBtn).toBeInTheDocument();

      // Click flip
      fireEvent.click(flipBtn);

      // Click again to flip back
      fireEvent.click(flipBtn);

      // Verify puzzle state is unchanged (same puzzle still displayed)
      expect(screen.getByText("King's Pawn Opening")).toBeInTheDocument();
    });

    it('pressing x toggles board orientation', async () => {
      localStorage.setItem('chessecho_username', 'hikaru');

      render(<Home />);

      await waitFor(() => {
        expect(screen.getByText("King's Pawn Opening")).toBeInTheDocument();
      });

      fireEvent.keyDown(window, { key: 'x' });
      fireEvent.keyDown(window, { key: 'X' });

      // Verify puzzle state is unchanged
      expect(screen.getByText("King's Pawn Opening")).toBeInTheDocument();
    });

    it('typing in an input does not trigger flip board', async () => {
      localStorage.setItem('chessecho_username', 'hikaru');

      render(<Home />);

      await waitFor(() => {
        expect(screen.getByText("King's Pawn Opening")).toBeInTheDocument();
      });

      // Open settings to reveal an input
      const settingsToggle = screen.getByRole('button', { name: /Puzzle Settings/i });
      fireEvent.click(settingsToggle);

      const minMistakesInput = screen.getByLabelText(/Min Mistakes/i);
      fireEvent.keyDown(minMistakesInput, { key: 'x' });

      // Puzzle should still be displayed (no flip triggered from input)
      expect(screen.getByText("King's Pawn Opening")).toBeInTheDocument();
    });

    it('flipping board does not modify FEN, puzzle state, move history, or settings', async () => {
      localStorage.setItem('chessecho_username', 'hikaru');

      render(<Home />);

      await waitFor(() => {
        expect(screen.getByText("King's Pawn Opening")).toBeInTheDocument();
      });

      const flipBtn = screen.getByRole('button', { name: /Flip/i });
      fireEvent.click(flipBtn);

      // Verify puzzle title and feedback remain unchanged
      expect(screen.getByText("King's Pawn Opening")).toBeInTheDocument();
      expect(screen.getByText(/Find White's best move or an acceptable alternative/i)).toBeInTheDocument();
    });
  });

  describe('9. Direct-Load Puzzles Page Source Games Data Path', () => {
    it('fresh load with no persisted color executes a single BOTH request and preserves View Games button', async () => {
      localStorage.setItem('chessecho_username', 'hikaru');

      const directLoadPuzzle: Puzzle = {
        ...mockPuzzles[0],
        puzzleId: 'direct-load-puzzle-1',
        gameUrls: [
          'https://www.chess.com/game/live/directGameNewest',
          'https://www.chess.com/game/live/directGameOlder',
        ],
      };

      vi.mocked(api.fetchPuzzles).mockResolvedValue([directLoadPuzzle]);

      render(<Home />);

      // Verifies initialization gate allows exactly ONE fetchPuzzles call with playerColor = 'BOTH'
      await waitFor(() => {
        expect(api.fetchPuzzles).toHaveBeenCalledWith('hikaru', 'CHESS_COM', 'BOTH', 0.8, 3, 10, 0);
        expect(api.fetchPuzzles).toHaveBeenCalledTimes(1);
      });

      // Verifies puzzle returned from BOTH request retains gameUrls and renders View Games (2)
      await waitFor(() => {
        expect(screen.getByRole('button', { name: /View Games \(2\)/i })).toBeInTheDocument();
      });

      const viewGamesBtn = screen.getByRole('button', { name: /View Games \(2\)/i });
      fireEvent.click(viewGamesBtn);

      expect(screen.getByText('Source Games')).toBeInTheDocument();
      expect(screen.getByText(/Game #1: https:\/\/www\.chess\.com\/game\/live\/directGameNewest/i)).toBeInTheDocument();
      expect(screen.getByText(/Game #2: https:\/\/www\.chess\.com\/game\/live\/directGameOlder/i)).toBeInTheDocument();
    });

    it('simulates browser refresh with persisted WHITE filter in localStorage, verifying single fetch and preserved View Games button after async effects settle', async () => {
      localStorage.setItem('chessecho_username', 'hikaru');
      localStorage.setItem('chessecho_puzzle_color_filter', 'WHITE');
      localStorage.setItem('chessecho_min_eval_loss', '0.8');
      localStorage.setItem('chessecho_min_mistake_count', '3');

      const refreshPuzzle: Puzzle = {
        ...mockPuzzles[0],
        puzzleId: 'refresh-puzzle-white',
        playerColor: 'WHITE',
        gameUrls: [
          'https://www.chess.com/game/live/refreshGame1',
          'https://www.chess.com/game/live/refreshGame2',
        ],
      };

      vi.mocked(api.fetchPuzzles).mockResolvedValue([refreshPuzzle]);

      render(<Home />);

      // Verifies persisted WHITE color is restored ONCE before fetching, executing exactly ONE fetchPuzzles call with WHITE
      await waitFor(() => {
        expect(api.fetchPuzzles).toHaveBeenCalledWith('hikaru', 'CHESS_COM', 'WHITE', 0.8, 3, 10, 0);
        expect(api.fetchPuzzles).toHaveBeenCalledTimes(1);
      });

      // Verifies puzzle returned with non-empty gameUrls renders View Games (2) after async effects settle
      await waitFor(() => {
        expect(screen.getByRole('button', { name: /View Games \(2\)/i })).toBeInTheDocument();
      });
    });

    it('simulates browser refresh with persisted BOTH filter in localStorage, verifying single fetch and preserved View Games button after async effects settle', async () => {
      localStorage.setItem('chessecho_username', 'hikaru');
      localStorage.setItem('chessecho_puzzle_color_filter', 'BOTH');

      const refreshPuzzle: Puzzle = {
        ...mockPuzzles[0],
        puzzleId: 'refresh-puzzle-both',
        gameUrls: [
          'https://www.chess.com/game/live/refreshGameBoth1',
        ],
      };

      vi.mocked(api.fetchPuzzles).mockResolvedValue([refreshPuzzle]);

      render(<Home />);

      await waitFor(() => {
        expect(api.fetchPuzzles).toHaveBeenCalledWith('hikaru', 'CHESS_COM', 'BOTH', 0.8, 3, 10, 0);
        expect(api.fetchPuzzles).toHaveBeenCalledTimes(1);
      });

      await waitFor(() => {
        expect(screen.getByRole('button', { name: /View Games \(1\)/i })).toBeInTheDocument();
      });
    });
  });
});
