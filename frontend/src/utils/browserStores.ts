import { JobStatusResponse } from '../services/api';
import { soundService } from '../services/soundService';

type Listener = () => void;

export interface BrowserStore<T> {
  getSnapshot: () => T;
  getServerSnapshot: () => T;
  subscribe: (listener: Listener) => () => void;
  set: (value: T) => void;
  invalidate: () => void;
}

interface StoreDefinition<T> {
  read: () => T;
  fallback: () => T;
  persist?: (value: T) => void;
  watch?: (onExternalChange: () => void) => () => void;
}

export function createStore<T>(definition: StoreDefinition<T>): BrowserStore<T> {
  let snapshot: T | undefined;
  let loaded = false;
  let stopWatching: (() => void) | null = null;
  const listeners = new Set<Listener>();

  const clear = () => {
    loaded = false;
    snapshot = undefined;
  };

  const notify = () => {
    listeners.forEach((listener) => listener());
  };

  const getSnapshot = (): T => {
    if (!loaded) {
      try {
        snapshot = definition.read();
      } catch {
        snapshot = definition.fallback();
      }
      loaded = true;
    }
    return snapshot as T;
  };

  return {
    getSnapshot,
    getServerSnapshot: definition.fallback,
    subscribe(listener) {
      listeners.add(listener);
      if (listeners.size === 1 && definition.watch) {
        stopWatching = definition.watch(() => {
          clear();
          notify();
        });
      }
      return () => {
        listeners.delete(listener);
        if (listeners.size === 0) {
          stopWatching?.();
          stopWatching = null;
          clear();
        }
      };
    },
    set(value) {
      try {
        definition.persist?.(value);
      } catch {
        // Storage unavailable; the snapshot still moves.
      }
      snapshot = value;
      loaded = true;
      notify();
    },
    invalidate() {
      clear();
      notify();
    },
  };
}

export type TabValue = 'puzzles' | 'weaknesses' | 'import';

const TAB_KEY = 'chessecho_active_tab';
const USERNAME_KEY = 'chessecho_username';
const JOB_KEY = 'chessecho_active_job';

const isTab = (value: string | null): value is TabValue =>
  value === 'puzzles' || value === 'weaknesses' || value === 'import';

const readHashTab = (): TabValue | null => {
  const hash = window.location.hash.replace('#', '');
  return isTab(hash) ? hash : null;
};

export const activeTabStore = createStore<TabValue>({
  read: () => {
    const hashTab = readHashTab();
    if (hashTab) return hashTab;
    const savedTab = window.localStorage.getItem(TAB_KEY);
    return isTab(savedTab) ? savedTab : 'puzzles';
  },
  fallback: () => 'puzzles',
  persist: (value) => window.localStorage.setItem(TAB_KEY, value),
  watch: (onExternalChange) => {
    const syncHash = () => {
      const hash = readHashTab();
      if (!hash) return;
      window.localStorage.setItem(TAB_KEY, hash);
      onExternalChange();
    };
    window.addEventListener('hashchange', syncHash);
    window.addEventListener('popstate', syncHash);
    return () => {
      window.removeEventListener('hashchange', syncHash);
      window.removeEventListener('popstate', syncHash);
    };
  },
});

export const activeUsernameStore = createStore<string | undefined>({
  read: () => window.localStorage.getItem(USERNAME_KEY) || undefined,
  fallback: () => undefined,
  persist: (value) => {
    if (value) {
      window.localStorage.setItem(USERNAME_KEY, value);
    } else {
      window.localStorage.removeItem(USERNAME_KEY);
    }
  },
});

export const activeJobStore = createStore<JobStatusResponse | null>({
  read: () => {
    const savedUser = window.localStorage.getItem(USERNAME_KEY);
    if (!savedUser) {
      window.localStorage.removeItem(JOB_KEY);
      return null;
    }
    const saved = window.localStorage.getItem(JOB_KEY);
    if (!saved) return null;
    try {
      return JSON.parse(saved) as JobStatusResponse;
    } catch {
      // Invalid JSON: the stored value is deliberately retained.
      return null;
    }
  },
  fallback: () => null,
  persist: (value) => {
    if (value) {
      window.localStorage.setItem(JOB_KEY, JSON.stringify(value));
    } else {
      window.localStorage.removeItem(JOB_KEY);
    }
  },
});

export interface PuzzleSettings {
  colorFilter: 'BOTH' | 'WHITE' | 'BLACK';
  minEvalLoss: number;
  minMistakeCount: number;
  soundEnabled: boolean;
}

const DEFAULT_PUZZLE_SETTINGS: PuzzleSettings = {
  colorFilter: 'BOTH',
  minEvalLoss: 0.8,
  minMistakeCount: 3,
  soundEnabled: true,
};

export const puzzleSettingsStore = createStore<PuzzleSettings>({
  read: () => {
    const savedColor = window.localStorage.getItem('chessecho_puzzle_color_filter');
    const savedEvalLoss = window.localStorage.getItem('chessecho_min_eval_loss');
    const savedMistakes = window.localStorage.getItem('chessecho_min_mistake_count');
    return {
      colorFilter:
        savedColor === 'BOTH' || savedColor === 'WHITE' || savedColor === 'BLACK'
          ? savedColor
          : DEFAULT_PUZZLE_SETTINGS.colorFilter,
      minEvalLoss:
        savedEvalLoss && !isNaN(Number(savedEvalLoss))
          ? Number(savedEvalLoss)
          : DEFAULT_PUZZLE_SETTINGS.minEvalLoss,
      minMistakeCount:
        savedMistakes && !isNaN(Number(savedMistakes))
          ? Number(savedMistakes)
          : DEFAULT_PUZZLE_SETTINGS.minMistakeCount,
      soundEnabled: soundService.isSoundEnabled(),
    };
  },
  fallback: () => DEFAULT_PUZZLE_SETTINGS,
});
