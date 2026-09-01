import React from 'react';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import '@testing-library/jest-dom';
import { vi, describe, it, expect, beforeEach, afterEach } from 'vitest';
import Home from '../app/page';
import * as api from '../services/api';
import { Puzzle } from '../mock/mockData';

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

type TabValue = 'puzzles' | 'weaknesses' | 'import';

type MockHeaderProps = {
  activeTab: TabValue;
  setActiveTab: (tab: TabValue) => void;
  username?: string;
  weaknessCount?: number;
  onDisconnect?: () => void;
};

type HeaderRender = {
  activeTab: TabValue;
  username?: string;
  weaknessCount?: number;
};

const headerRenders: HeaderRender[] = [];

vi.mock('../components/Header', () => ({
  Header: (props: MockHeaderProps) => {
    headerRenders.push({
      activeTab: props.activeTab,
      username: props.username,
      weaknessCount: props.weaknessCount,
    });
    return (
      <div data-testid="mock-header">
        <button data-testid="mock-tab-puzzles" onClick={() => props.setActiveTab('puzzles')}>
          Puzzles
        </button>
        <button data-testid="mock-tab-weaknesses" onClick={() => props.setActiveTab('weaknesses')}>
          Weaknesses
        </button>
        <button data-testid="mock-tab-import" onClick={() => props.setActiveTab('import')}>
          Import
        </button>
        <button data-testid="mock-disconnect" onClick={() => props.onDisconnect?.()}>
          Disconnect
        </button>
      </div>
    );
  },
}));

/**
 * Structural view of the not-yet-existing store module (plan §5.1). Declaring the
 * shape locally keeps the store contract explicit without importing a module that
 * the production phase has not created yet.
 */
type BrowserStoreLike<T> = {
  getSnapshot: () => T;
  getServerSnapshot: () => T;
  subscribe: (listener: () => void) => () => void;
  set: (value: T) => void;
  invalidate: () => void;
};

type StoreDefinitionLike<T> = {
  read: () => T;
  fallback: () => T;
  persist?: (value: T) => void;
  watch?: (onExternalChange: () => void) => () => void;
};

type BrowserStoresModule = {
  createStore: <T>(definition: StoreDefinitionLike<T>) => BrowserStoreLike<T>;
  activeUsernameStore: BrowserStoreLike<string | undefined>;
};

const loadBrowserStores = (): Promise<BrowserStoresModule> =>
  vi.importActual<BrowserStoresModule>('../utils/browserStores');

const UsernameProbe = ({
  store,
  testId = 'store-value',
}: {
  store: BrowserStoreLike<string | undefined>;
  testId?: string;
}) => {
  const value = React.useSyncExternalStore(
    store.subscribe,
    store.getSnapshot,
    store.getServerSnapshot
  );
  return <div data-testid={testId}>{value ?? 'none'}</div>;
};

/**
 * Issue 98 — persisted-state store contract and hydration timing (plan §7.1, T1–T9).
 */
describe('Issue 98 — persisted state store and page hydration', () => {
  const mockPuzzle: Puzzle = {
    puzzleId: 'puzzle-lint-baseline-1',
    fen: 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1',
    playerColor: 'WHITE',
    targetMove: 'e4',
    openingTitle: 'Lint Baseline Opening',
    acceptableMoves: [],
    movesPlayed: [],
    priority: 1.0,
    timesReached: 4,
    mistakeCount: 2,
    mistakeRate: 50.0,
    evalCp: 35,
  };

  const lastHeaderRender = (): HeaderRender => headerRenders[headerRenders.length - 1];

  beforeEach(() => {
    localStorage.clear();
    window.location.hash = '';
    headerRenders.length = 0;
    vi.resetAllMocks();
    vi.mocked(api.fetchPuzzles).mockResolvedValue([]);
    vi.mocked(api.fetchWeaknesses).mockResolvedValue([]);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('T1 — a store reads on its first mount and re-reads after the last unsubscribe', async () => {
    const { createStore } = await loadBrowserStores();

    const store = createStore<string | undefined>({
      read: () => window.localStorage.getItem('chessecho_username') || undefined,
      fallback: () => undefined,
    });

    localStorage.setItem('chessecho_username', 'magnuscarlsen');
    const firstMount = render(<UsernameProbe store={store} />);
    expect(screen.getByTestId('store-value').textContent).toBe('magnuscarlsen');
    firstMount.unmount();

    localStorage.setItem('chessecho_username', 'hikaru');
    render(<UsernameProbe store={store} />);
    expect(screen.getByTestId('store-value').textContent).toBe('hikaru');
  });

  it('T2 — every mount of the username store observes its own seed', async () => {
    const { activeUsernameStore } = await loadBrowserStores();

    const seeds: Array<string | undefined> = [
      undefined,
      'magnuscarlsen',
      'hikaru',
      'player1',
      undefined,
      undefined,
      undefined,
      undefined,
      'testuser',
      'newplayer',
      'player1',
      undefined,
      'hikaru',
    ];

    expect(seeds).toHaveLength(13);

    for (const seed of seeds) {
      if (seed) {
        localStorage.setItem('chessecho_username', seed);
      } else {
        localStorage.removeItem('chessecho_username');
      }

      const mounted = render(<UsernameProbe store={activeUsernameStore} />);
      expect(screen.getByTestId('store-value').textContent).toBe(seed ?? 'none');
      mounted.unmount();
    }
  });

  it('T3 — simultaneous consumers share one snapshot and ignore post-mount seeding', async () => {
    const { activeUsernameStore } = await loadBrowserStores();

    localStorage.setItem('chessecho_username', 'player1');

    const mounted = render(
      <>
        <UsernameProbe store={activeUsernameStore} testId="probe-a" />
        <UsernameProbe store={activeUsernameStore} testId="probe-b" />
      </>
    );

    expect(screen.getByTestId('probe-a').textContent).toBe('player1');
    expect(screen.getByTestId('probe-b').textContent).toBe('player1');

    await act(async () => {
      localStorage.setItem('chessecho_username', 'seeded-after-mount');
    });

    expect(screen.getByTestId('probe-a').textContent).toBe('player1');
    expect(screen.getByTestId('probe-b').textContent).toBe('player1');

    mounted.unmount();

    render(<UsernameProbe store={activeUsernameStore} />);
    expect(screen.getByTestId('store-value').textContent).toBe('seeded-after-mount');
  });

  it('T4 — set() persists before notifying and set(undefined) removes the key', async () => {
    const { activeUsernameStore } = await loadBrowserStores();

    localStorage.setItem('chessecho_username', 'player1');

    const observedDuringNotification: Array<string | null> = [];
    const unsubscribe = activeUsernameStore.subscribe(() => {
      observedDuringNotification.push(window.localStorage.getItem('chessecho_username'));
    });

    activeUsernameStore.set('newplayer');
    expect(observedDuringNotification).toEqual(['newplayer']);
    expect(activeUsernameStore.getSnapshot()).toBe('newplayer');
    expect(window.localStorage.getItem('chessecho_username')).toBe('newplayer');

    activeUsernameStore.set(undefined);
    expect(observedDuringNotification).toEqual(['newplayer', null]);
    expect(activeUsernameStore.getSnapshot()).toBeUndefined();
    expect(window.localStorage.getItem('chessecho_username')).toBeNull();

    unsubscribe();
  });

  it('T5 — a persisted username is connected in the first committed render', () => {
    localStorage.setItem('chessecho_username', 'magnuscarlsen');

    render(<Home />);

    expect(headerRenders.length).toBeGreaterThan(0);
    expect(headerRenders[0].username).toBe('magnuscarlsen');
  });

  it('T6 — the persisted tab is applied in the first committed render and a valid hash wins', () => {
    localStorage.setItem('chessecho_active_tab', 'import');

    const persistedOnly = render(<Home />);
    expect(headerRenders.length).toBeGreaterThan(0);
    expect(headerRenders[0].activeTab).toBe('import');
    persistedOnly.unmount();

    headerRenders.length = 0;
    window.location.hash = '#weaknesses';

    render(<Home />);
    expect(headerRenders.length).toBeGreaterThan(0);
    expect(headerRenders[0].activeTab).toBe('weaknesses');
  });

  it('T7 — persisted puzzle settings are applied before the first gated puzzle fetch', async () => {
    localStorage.setItem('chessecho_username', 'hikaru');
    localStorage.setItem('chessecho_puzzle_color_filter', 'WHITE');
    localStorage.setItem('chessecho_min_eval_loss', '1.5');
    localStorage.setItem('chessecho_min_mistake_count', '7');

    const committedRendersAtFetch: number[] = [];
    vi.mocked(api.fetchPuzzles).mockImplementation(async () => {
      committedRendersAtFetch.push(headerRenders.length);
      return [];
    });

    render(<Home />);

    await waitFor(() => {
      expect(api.fetchPuzzles).toHaveBeenCalled();
    });

    // P-5: the isSettingsInitialized gate still prevents a default-settings fetch.
    expect(api.fetchPuzzles).toHaveBeenCalledTimes(1);
    expect(api.fetchPuzzles).toHaveBeenCalledWith('hikaru', 'CHESS_COM', 'WHITE', 1.5, 7, 10, 0);

    // B-1: the settings were already applied by the first committed render.
    expect(committedRendersAtFetch[0]).toBe(1);
  });

  it('T8 — hashchange and popstate move the tab and persist it, and an invalid hash is ignored', async () => {
    render(<Home />);

    expect(lastHeaderRender().activeTab).toBe('puzzles');

    await act(async () => {
      window.location.hash = '#weaknesses';
      window.dispatchEvent(new HashChangeEvent('hashchange'));
    });

    expect(lastHeaderRender().activeTab).toBe('weaknesses');
    expect(localStorage.getItem('chessecho_active_tab')).toBe('weaknesses');

    await act(async () => {
      window.location.hash = '#import';
      window.dispatchEvent(new PopStateEvent('popstate'));
    });

    expect(lastHeaderRender().activeTab).toBe('import');
    expect(localStorage.getItem('chessecho_active_tab')).toBe('import');

    await act(async () => {
      window.location.hash = '#nonsense';
      window.dispatchEvent(new HashChangeEvent('hashchange'));
    });

    expect(lastHeaderRender().activeTab).toBe('import');
    expect(localStorage.getItem('chessecho_active_tab')).toBe('import');
  });

  it('T9 — disconnecting clears the account context, the puzzle state and both persisted keys', async () => {
    localStorage.setItem('chessecho_username', 'hikaru');
    localStorage.setItem(
      'chessecho_active_job',
      JSON.stringify({
        jobId: 'job-disconnect-1',
        status: 'COMPLETED',
        gamesImported: 12,
        gamesSkipped: 1,
      })
    );
    vi.mocked(api.fetchPuzzles).mockResolvedValue([mockPuzzle]);

    render(<Home />);

    await waitFor(() => {
      expect(screen.getByText('Lint Baseline Opening')).toBeInTheDocument();
    });
    expect(lastHeaderRender().weaknessCount).toBe(1);

    await act(async () => {
      fireEvent.click(screen.getByTestId('mock-tab-import'));
    });
    expect(screen.getByText(/Import Progress/i)).toBeInTheDocument();

    await act(async () => {
      fireEvent.click(screen.getByTestId('mock-disconnect'));
    });

    expect(localStorage.getItem('chessecho_username')).toBeNull();
    expect(localStorage.getItem('chessecho_active_job')).toBeNull();
    expect(lastHeaderRender().username).toBeUndefined();
    expect(lastHeaderRender().weaknessCount).toBe(0);

    await act(async () => {
      fireEvent.click(screen.getByTestId('mock-tab-puzzles'));
    });

    expect(screen.getByText(/No Practice Puzzles Available/i)).toBeInTheDocument();
    expect(screen.queryByText('Lint Baseline Opening')).not.toBeInTheDocument();
  });
});
