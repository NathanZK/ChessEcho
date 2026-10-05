import { fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Header } from '../components/Header';

const onExplorePosition = vi.fn();

function renderHeader(activeTab: 'puzzles' | 'weaknesses' | 'import' = 'weaknesses') {
  return render(
    <Header
      activeTab={activeTab}
      setActiveTab={vi.fn()}
      accountStatus="unconnected"
      onExplorePosition={onExplorePosition}
    />,
  );
}

afterEach(() => {
  vi.clearAllMocks();
});

describe('shared Explore a position entry', () => {
  it.each([375, 1280])('is available at %i pixels wide', (width) => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: width });
    renderHeader();

    expect(screen.getByRole('button', { name: 'Explore a position' })).toBeInTheDocument();
  });

  it('opens the position form from a non-puzzle tab and submits a valid FEN', () => {
    renderHeader('import');

    fireEvent.click(screen.getByRole('button', { name: 'Explore a position' }));
    const dialog = screen.getByRole('dialog', { name: 'Explore a position' });
    fireEvent.change(within(dialog).getByLabelText('FEN'), {
      target: { value: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Continue from FEN' }));

    expect(onExplorePosition).toHaveBeenCalledWith({
      fen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
      sanHistory: [],
    });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('displays an actionable error and does not submit an invalid FEN', () => {
    renderHeader();
    fireEvent.click(screen.getByRole('button', { name: 'Explore a position' }));
    const dialog = screen.getByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('FEN'), {
      target: { value: 'not a FEN' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Continue from FEN' }));

    expect(within(dialog).getByRole('alert')).toHaveTextContent(/Invalid FEN/i);
    expect(onExplorePosition).not.toHaveBeenCalled();
  });

  it('submits the selected PGN ply with its move history', () => {
    renderHeader();
    fireEvent.click(screen.getByRole('button', { name: 'Explore a position' }));
    const dialog = screen.getByRole('dialog');
    fireEvent.click(within(dialog).getByRole('tab', { name: 'PGN' }));
    fireEvent.change(within(dialog).getByLabelText('PGN'), {
      target: { value: '1. e4 e5 2. Nf3' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Load PGN' }));
    fireEvent.change(within(dialog).getByLabelText('Choose a position'), {
      target: { value: '3' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Continue from this position' }));

    expect(onExplorePosition).toHaveBeenCalledWith({
      fen: 'rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2',
      sanHistory: ['e4', 'e5', 'Nf3'],
      historyStartFen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
    });
  });

  it('displays a PGN error without submitting a position', () => {
    renderHeader();
    fireEvent.click(screen.getByRole('button', { name: 'Explore a position' }));
    const dialog = screen.getByRole('dialog');
    fireEvent.click(within(dialog).getByRole('tab', { name: 'PGN' }));
    fireEvent.change(within(dialog).getByLabelText('PGN'), {
      target: { value: 'not a PGN' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Load PGN' }));

    expect(within(dialog).getByRole('alert')).toHaveTextContent(/couldn't read that PGN/i);
    expect(onExplorePosition).not.toHaveBeenCalled();
  });

  it('closes without changing the position session', () => {
    renderHeader();
    fireEvent.click(screen.getByRole('button', { name: 'Explore a position' }));
    fireEvent.click(screen.getByRole('button', { name: 'Close dialog' }));

    expect(onExplorePosition).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});
