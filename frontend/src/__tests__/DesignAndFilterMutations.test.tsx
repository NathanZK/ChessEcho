import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { Header } from '../components/Header';
import { WeaknessesList } from '../components/WeaknessesList';
import LoginPage from '../app/login/page';
import RegisterPage from '../app/register/page';
import * as api from '../services/api';
import type { WeaknessResponse } from '../services/api';

vi.mock('next/navigation', () => ({
  useRouter: () => ({
    push: vi.fn(),
    replace: vi.fn(),
    prefetch: vi.fn(),
  }),
}));

vi.mock('../services/api', () => ({
  fetchWeaknesses: vi.fn(),
  fetchPositionProgress: vi.fn(),
  fetchPuzzles: vi.fn(),
}));

const mockWeakness: WeaknessResponse = {
  positionId: 'mut-pos-1',
  fen: 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1',
  timesReached: 10,
  mistakeCount: 4,
  mistakeRate: 40.0,
  averageLoss: 1.25,
  priority: 3.0,
  bestMove: 'c5',
  acceptableMoves: [],
  movesPlayed: [{ move: 'e5', timesPlayed: 4, averageLoss: 1.25 }],
  gameUrls: ['https://www.chess.com/game/live/101'],
  openingContext: {
    eco: 'B22',
    ecoUrl: 'https://www.chess.com/openings/B22-Sicilian-Defense-Alapin-Variation',
  },
};

describe('Targeted Mutation & Invariant Guards', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe('1. Brand Logo Consistency Mutants', () => {
    it('kills mutant: Header renders unified SVG knight icon and no placeholder pawn', () => {
      const { container } = render(
        <Header
          activeTab="weaknesses"
          setActiveTab={vi.fn()}
          accountStatus="unconnected"
        />
      );

      const heading = screen.getByRole('heading', { level: 1, name: 'ChessProbe' });
      expect(heading).toBeInTheDocument();
      expect(heading).toHaveClass('font-sans', 'font-extrabold', 'text-primary');

      // Assert SVG knight icon presence
      const svg = container.querySelector('svg');
      expect(svg).toBeInTheDocument();
      const path = svg?.querySelector('path');
      expect(path?.getAttribute('d')).toContain('M19 22H5v-2h14v2');

      // Assert no unicode pawn anywhere in header
      expect(container.textContent).not.toContain('♟');
    });

    it('kills mutant: Login and Register pages share identical SVG knight logo', () => {
      const { container: loginContainer } = render(<LoginPage />);
      expect(loginContainer.querySelector('svg path')?.getAttribute('d')).toContain('M19 22H5v-2h14v2');
      expect(loginContainer.textContent).not.toContain('♟');
      expect(loginContainer.textContent).toContain('ChessProbe');

      const { container: registerContainer } = render(<RegisterPage />);
      expect(registerContainer.querySelector('svg path')?.getAttribute('d')).toContain('M19 22H5v-2h14v2');
      expect(registerContainer.textContent).not.toContain('♟');
      expect(registerContainer.textContent).toContain('ChessProbe');
    });
  });

  describe('2. Navigation Layout & Desktop Sidebar Rail Mutants', () => {
    it('kills mutant: Puzzles layout enforces 2xl sidebar tokens and preserves item icons', () => {
      const { container } = render(
        <Header
          activeTab="puzzles"
          setActiveTab={vi.fn()}
          accountStatus="unconnected"
        />
      );

      const header = container.querySelector('header');
      expect(header).toHaveClass('2xl:w-[280px]', '2xl:h-screen', '2xl:border-r');

      // Confirm all navigation items render with their labels and icons
      expect(screen.getByRole('button', { name: /practice puzzles/i })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /weaknesses library/i })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /long decisions/i })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /similar positions/i })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /import games/i })).toBeInTheDocument();
    });

    it('kills mutant: Non-puzzles views enforce 60px top header and omit 2xl sidebar tokens', () => {
      const { container } = render(
        <Header
          activeTab="weaknesses"
          setActiveTab={vi.fn()}
          accountStatus="unconnected"
        />
      );

      const header = container.querySelector('header');
      expect(header).toHaveClass('h-[60px]', 'border-b');
      expect(header).not.toHaveClass('2xl:w-[280px]', '2xl:h-screen');
    });
  });

  describe('3. Weakness Library Heading & Typography Mutants', () => {
    it('kills mutant: Must display Recurring Weakness Library and suppress Opening from heading', async () => {
      vi.mocked(api.fetchWeaknesses).mockResolvedValue([mockWeakness]);

      render(<WeaknessesList username="hikaru" onSelectPractice={vi.fn()} />);

      const heading = await screen.findByRole('heading', { name: /Recurring Weakness Library/i });
      expect(heading).toBeInTheDocument();
      expect(heading.textContent).toBe('Recurring Weakness Library');
      expect(heading.textContent).not.toContain('Opening');
    });
  });

  describe('4. Prototype Filters Invariant Mutants', () => {
    it('kills mutant: Color filter renders Both Sides, White, Black (not raw ALL/WHITE/BLACK)', async () => {
      vi.mocked(api.fetchWeaknesses).mockResolvedValue([mockWeakness]);

      render(<WeaknessesList username="hikaru" onSelectPractice={vi.fn()} />);

      expect(await screen.findByRole('button', { name: 'Both Sides' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'White' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Black' })).toBeInTheDocument();

      // Ensure no raw "ALL" button exists
      expect(screen.queryByRole('button', { name: 'ALL' })).not.toBeInTheDocument();
    });

    it('kills mutant: Phase filter is a styled select dropdown with all prototype options', async () => {
      vi.mocked(api.fetchWeaknesses).mockResolvedValue([mockWeakness]);

      render(<WeaknessesList username="hikaru" onSelectPractice={vi.fn()} />);

      const phaseSelect = await screen.findByLabelText('Filter by Phase');
      expect(phaseSelect.tagName).toBe('SELECT');

      const options = Array.from(phaseSelect.querySelectorAll('option')).map((o) => o.value);
      expect(options).toEqual(['ALL', 'OPENING', 'MIDDLEGAME', 'ENDGAME']);
    });

    it('kills mutant: Speed filter is a styled select dropdown with all prototype options', async () => {
      vi.mocked(api.fetchWeaknesses).mockResolvedValue([mockWeakness]);

      render(<WeaknessesList username="hikaru" onSelectPractice={vi.fn()} />);

      const speedSelect = await screen.findByLabelText('Filter by Speed');
      expect(speedSelect.tagName).toBe('SELECT');

      const options = Array.from(speedSelect.querySelectorAll('option')).map((o) => o.value);
      expect(options).toEqual(['ALL', 'BLITZ', 'RAPID', 'BULLET', 'CLASSICAL']);
    });

    it('kills mutant: Threshold filter is a styled select dropdown with prototype pawns labels', async () => {
      vi.mocked(api.fetchWeaknesses).mockResolvedValue([mockWeakness]);

      render(<WeaknessesList username="hikaru" onSelectPractice={vi.fn()} />);

      const thresholdSelect = await screen.findByLabelText('Filter by Threshold');
      expect(thresholdSelect.tagName).toBe('SELECT');

      const values = Array.from(thresholdSelect.querySelectorAll('option')).map((o) => o.value);
      expect(values).toEqual(['0.3', '0.5', '0.8', '1.2', '2']);
    });

    it('kills mutant: Min Mistakes filter is a styled select dropdown with prototype recurrence options', async () => {
      vi.mocked(api.fetchWeaknesses).mockResolvedValue([mockWeakness]);

      render(<WeaknessesList username="hikaru" onSelectPractice={vi.fn()} />);

      const mistakesSelect = await screen.findByLabelText('Filter by Min Mistakes');
      expect(mistakesSelect.tagName).toBe('SELECT');

      const values = Array.from(mistakesSelect.querySelectorAll('option')).map((o) => o.value);
      expect(values).toEqual(['1', '3', '5']);
    });
  });

  describe('5. View Mode Switching Mutants', () => {
    it('kills mutant: Toggle between Grid and List switches views and preserves loaded count', async () => {
      vi.mocked(api.fetchWeaknesses).mockResolvedValue([mockWeakness]);

      render(<WeaknessesList username="hikaru" onSelectPractice={vi.fn()} />);

      await screen.findByText('Recurring Weakness Library');
      const countIndicator = await screen.findByTestId('weakness-count-indicator');
      expect(countIndicator.textContent).toBe('1');

      // Initially in grid view (PositionCard elements exist)
      expect(await screen.findByTestId('weakness-grid-container')).toBeInTheDocument();
      expect(screen.queryByTestId('weakness-list-container')).not.toBeInTheDocument();

      // Switch to List view
      const listBtn = screen.getByRole('button', { name: 'List' });
      fireEvent.click(listBtn);

      // Verify list container is rendered and grid is removed
      expect(await screen.findByTestId('weakness-list-container')).toBeInTheDocument();
      expect(screen.queryByTestId('weakness-grid-container')).not.toBeInTheDocument();

      // Switch back to Grid view
      const gridBtn = screen.getByRole('button', { name: 'Grid' });
      fireEvent.click(gridBtn);

      expect(await screen.findByTestId('weakness-grid-container')).toBeInTheDocument();
      expect(screen.queryByTestId('weakness-list-container')).not.toBeInTheDocument();
    });
  });
});
