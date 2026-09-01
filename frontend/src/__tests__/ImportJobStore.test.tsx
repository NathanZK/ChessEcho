import React from 'react';
import { render, screen, fireEvent, act } from '@testing-library/react';
import '@testing-library/jest-dom';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { ImportGamesView } from '../components/ImportGamesView';
import * as api from '../services/api';

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api');
  return {
    ...actual,
    startImportJob: vi.fn(),
    pollJobStatus: vi.fn(),
  };
});

const monthPickerRenders: string[] = [];

vi.mock('../components/MonthPicker', () => ({
  MonthPicker: (props: { label: string; value: string; onChange: (value: string) => void }) => {
    monthPickerRenders.push(props.label);
    return <div data-testid="mock-month-picker" />;
  },
}));

/**
 * Structural view of the not-yet-existing store module (plan §5.1). Declaring the
 * shape locally keeps the contract explicit without importing a module that the
 * production phase has not created yet.
 */
type BrowserStoreLike<T> = {
  getSnapshot: () => T;
  getServerSnapshot: () => T;
  subscribe: (listener: () => void) => () => void;
  set: (value: T) => void;
  invalidate: () => void;
};

type BrowserStoresModule = {
  activeJobStore: BrowserStoreLike<api.JobStatusResponse | null>;
};

const loadBrowserStores = (): Promise<BrowserStoresModule> =>
  vi.importActual<BrowserStoresModule>('../utils/browserStores');

/**
 * Issue 98 — persisted import-job restore guards (plan §7.4, T24–T27).
 *
 * `ImportGamesView` renders exactly two `MonthPicker` instances, so the mocked
 * render tally divided by two is the number of committed renders.
 */
describe('Issue 98 — import job restore and polling', () => {
  const completedJob: api.JobStatusResponse = {
    jobId: 'job-restored-1',
    status: 'COMPLETED',
    gamesImported: 120,
    gamesSkipped: 5,
  };

  const committedRenders = () => monthPickerRenders.length / 2;

  beforeEach(() => {
    localStorage.clear();
    monthPickerRenders.length = 0;
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('T24 — a persisted job is restored in the first committed render and dropped once the user is gone', () => {
    localStorage.setItem('chessecho_username', 'hikaru');
    localStorage.setItem('chessecho_active_job', JSON.stringify(completedJob));

    const view = render(<ImportGamesView connectedUsername="hikaru" />);

    expect(screen.getByText(/Import Progress/i)).toBeInTheDocument();
    expect(screen.getByText('COMPLETED')).toBeInTheDocument();
    expect(screen.getByText('120')).toBeInTheDocument();
    expect(committedRenders()).toBe(1);

    view.unmount();
    localStorage.removeItem('chessecho_username');
    monthPickerRenders.length = 0;

    render(<ImportGamesView />);

    expect(screen.getByText(/No Active Import Job/i)).toBeInTheDocument();
    expect(localStorage.getItem('chessecho_active_job')).toBeNull();
  });

  it('T25 — malformed job JSON yields no active job and the stored value is left in place', async () => {
    const malformed = '{ "jobId": "job-broken"';
    localStorage.setItem('chessecho_username', 'hikaru');
    localStorage.setItem('chessecho_active_job', malformed);

    render(<ImportGamesView connectedUsername="hikaru" />);

    expect(screen.getByText(/No Active Import Job/i)).toBeInTheDocument();
    expect(localStorage.getItem('chessecho_active_job')).toBe(malformed);

    const { activeJobStore } = await loadBrowserStores();

    expect(activeJobStore.getSnapshot()).toBeNull();
    expect(localStorage.getItem('chessecho_active_job')).toBe(malformed);
  });

  it('T26 — the connectedUsername prop overwrites the input, and local edits survive until it changes again', () => {
    const { rerender } = render(<ImportGamesView connectedUsername="hikaru" />);

    const usernameInput = screen.getByPlaceholderText(/e\.g\. Hikaru/i) as HTMLInputElement;
    expect(usernameInput.value).toBe('hikaru');

    fireEvent.change(usernameInput, { target: { value: 'locally-edited' } });
    expect(usernameInput.value).toBe('locally-edited');

    rerender(<ImportGamesView connectedUsername="hikaru" />);
    expect(usernameInput.value).toBe('locally-edited');

    rerender(<ImportGamesView connectedUsername="magnuscarlsen" />);
    expect(usernameInput.value).toBe('magnuscarlsen');
  });

  it('T27 — polling keeps its 2 s cadence, reports status, and a 404 clears the job', async () => {
    vi.useFakeTimers();
    vi.spyOn(console, 'error').mockImplementation(() => {});

    const processingJob: api.JobStatusResponse = {
      jobId: 'job-polled-1',
      status: 'PROCESSING',
      gamesImported: 0,
      gamesSkipped: 0,
    };
    localStorage.setItem('chessecho_username', 'player1');
    localStorage.setItem('chessecho_active_job', JSON.stringify(processingJob));

    const onJobStatusUpdate = vi.fn();

    vi.mocked(api.pollJobStatus).mockResolvedValueOnce({
      ...processingJob,
      gamesImported: 42,
    });

    render(<ImportGamesView connectedUsername="player1" onJobStatusUpdate={onJobStatusUpdate} />);

    expect(screen.getByText(/Import Progress/i)).toBeInTheDocument();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });

    expect(api.pollJobStatus).toHaveBeenCalledTimes(1);
    expect(api.pollJobStatus).toHaveBeenCalledWith('job-polled-1');
    expect(onJobStatusUpdate).toHaveBeenCalledWith(
      expect.objectContaining({ jobId: 'job-polled-1', gamesImported: 42 })
    );

    vi.mocked(api.pollJobStatus).mockRejectedValue(new Error('Failed to poll job status: 404'));

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });

    expect(screen.getByText(/Previous import job is no longer available/i)).toBeInTheDocument();
    expect(screen.getByText(/No Active Import Job/i)).toBeInTheDocument();
    expect(localStorage.getItem('chessecho_active_job')).toBeNull();
    expect(onJobStatusUpdate).toHaveBeenCalledWith(null);

    const pollCount = vi.mocked(api.pollJobStatus).mock.calls.length;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(4000);
    });
    expect(vi.mocked(api.pollJobStatus).mock.calls.length).toBe(pollCount);

    vi.useRealTimers();
  });

  it('keeps polling through analysis and stops when analysis becomes terminal', async () => {
    vi.useFakeTimers();
    const processingJob: api.JobStatusResponse = {
      jobId: 'job-analysis-1',
      status: 'PROCESSING',
      gamesImported: 10,
      gamesSkipped: 0,
    };
    localStorage.setItem('chessecho_username', 'player1');
    localStorage.setItem('chessecho_active_job', JSON.stringify(processingJob));

    vi.mocked(api.pollJobStatus)
      .mockResolvedValueOnce({
        ...processingJob,
        status: 'COMPLETED',
        gamesImported: 20,
        analysisStatus: 'ANALYZING',
      })
      .mockResolvedValueOnce({
        ...processingJob,
        status: 'COMPLETED',
        gamesImported: 20,
        analysisStatus: 'ANALYZING',
      })
      .mockResolvedValueOnce({
        ...processingJob,
        status: 'COMPLETED',
        gamesImported: 20,
        analysisStatus: 'COMPLETED',
      });

    render(<ImportGamesView connectedUsername="player1" />);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });
    expect(api.pollJobStatus).toHaveBeenCalledTimes(1);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });
    expect(api.pollJobStatus).toHaveBeenCalledTimes(2);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });
    expect(api.pollJobStatus).toHaveBeenCalledTimes(3);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(4000);
    });
    expect(api.pollJobStatus).toHaveBeenCalledTimes(3);
  });

  it('resumes polling a persisted completed import while analysis is running', async () => {
    vi.useFakeTimers();
    const analyzingJob = {
      jobId: 'job-analysis-resume',
      status: 'COMPLETED' as const,
      gamesImported: 120,
      gamesSkipped: 5,
      analysisStatus: 'ANALYZING' as const,
    };
    localStorage.setItem('chessecho_username', 'hikaru');
    localStorage.setItem('chessecho_active_job', JSON.stringify(analyzingJob));
    vi.mocked(api.pollJobStatus).mockResolvedValue({
      ...analyzingJob,
      analysisStatus: 'COMPLETED',
    });

    render(<ImportGamesView connectedUsername="hikaru" />);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });

    expect(api.pollJobStatus).toHaveBeenCalledTimes(1);
    expect(api.pollJobStatus).toHaveBeenCalledWith('job-analysis-resume');
  });

  it('notifies import completion exactly once while analysis polling continues', async () => {
    vi.useFakeTimers();
    const processingJob: api.JobStatusResponse = {
      jobId: 'job-exactly-once',
      status: 'PROCESSING',
      gamesImported: 0,
      gamesSkipped: 0,
    };
    localStorage.setItem('chessecho_username', 'newplayer');
    localStorage.setItem('chessecho_active_job', JSON.stringify(processingJob));
    const onImportStarted = vi.fn();

    vi.mocked(api.pollJobStatus)
      .mockResolvedValueOnce({
        ...processingJob,
        status: 'COMPLETED',
        gamesImported: 42,
        analysisStatus: 'ANALYZING',
      })
      .mockResolvedValueOnce({
        ...processingJob,
        status: 'COMPLETED',
        gamesImported: 42,
        analysisStatus: 'ANALYZING',
      })
      .mockResolvedValueOnce({
        ...processingJob,
        status: 'COMPLETED',
        gamesImported: 42,
        analysisStatus: 'COMPLETED',
      });

    render(
      <ImportGamesView
        connectedUsername="newplayer"
        onImportStarted={onImportStarted}
      />
    );

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });
    expect(onImportStarted).toHaveBeenCalledTimes(1);
    expect(onImportStarted).toHaveBeenCalledWith('newplayer');

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });
    expect(onImportStarted).toHaveBeenCalledTimes(1);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });
    expect(onImportStarted).toHaveBeenCalledTimes(1);
  });

  it('notifies import completion once for a legacy response without analysisStatus', async () => {
    vi.useFakeTimers();
    const processingJob: api.JobStatusResponse = {
      jobId: 'job-legacy-completion',
      status: 'PROCESSING',
      gamesImported: 10,
      gamesSkipped: 0,
    };
    localStorage.setItem('chessecho_username', 'legacyplayer');
    localStorage.setItem('chessecho_active_job', JSON.stringify(processingJob));
    const onImportStarted = vi.fn();
    vi.mocked(api.pollJobStatus).mockResolvedValueOnce({
      ...processingJob,
      status: 'COMPLETED',
      gamesImported: 42,
    });

    render(
      <ImportGamesView
        connectedUsername="legacyplayer"
        onImportStarted={onImportStarted}
      />
    );

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });
    expect(onImportStarted).toHaveBeenCalledTimes(1);
    expect(onImportStarted).toHaveBeenCalledWith('legacyplayer');

    await act(async () => {
      await vi.advanceTimersByTimeAsync(4000);
    });
    expect(onImportStarted).toHaveBeenCalledTimes(1);
    expect(api.pollJobStatus).toHaveBeenCalledTimes(1);
  });

  it('does not repeat completion notification after reload during analysis', async () => {
    vi.useFakeTimers();
    const analyzingJob = {
      jobId: 'job-reloaded-analysis',
      status: 'COMPLETED' as const,
      gamesImported: 42,
      gamesSkipped: 0,
      analysisStatus: 'ANALYZING' as const,
    };
    localStorage.setItem('chessecho_username', 'reloadedplayer');
    localStorage.setItem('chessecho_active_job', JSON.stringify(analyzingJob));
    const onImportStarted = vi.fn();
    vi.mocked(api.pollJobStatus).mockResolvedValueOnce({
      ...analyzingJob,
      analysisStatus: 'COMPLETED',
    });

    render(
      <ImportGamesView
        connectedUsername="reloadedplayer"
        onImportStarted={onImportStarted}
      />
    );

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });

    expect(api.pollJobStatus).toHaveBeenCalledTimes(1);
    expect(onImportStarted).not.toHaveBeenCalled();
  });

  it('renders meaningful totals-free progress with processed outcome distinctions', () => {
    const job = {
      jobId: 'job-unknown-total',
      status: 'PROCESSING' as const,
      gamesImported: 1200,
      gamesSkipped: 200,
      gamesProcessed: 1470,
      gamesFilteredOut: 70,
      analysisStatus: 'NOT_STARTED' as const,
    };
    localStorage.setItem('chessecho_username', 'hikaru');
    localStorage.setItem('chessecho_active_job', JSON.stringify(job));

    render(<ImportGamesView connectedUsername="hikaru" />);

    expect(screen.getByText(/Imported 1,?200 games/i)).toBeInTheDocument();
    expect(screen.getByText(/Already imported/i)).toBeInTheDocument();
    expect(screen.getByText(/Processed/i)).toBeInTheDocument();
    expect(screen.getByText(/Filtered/i)).toBeInTheDocument();
    expect(screen.getByText('200')).toBeInTheDocument();
    expect(screen.getByText('1,470')).toBeInTheDocument();
    expect(screen.getByText('70')).toBeInTheDocument();
    expect(screen.queryByText(/1,?470\s*\/\s*\d+/)).not.toBeInTheDocument();
  });

  it('distinguishes analyzing, completed, and failed analysis from completed import', async () => {
    const analyzingJob = {
      jobId: 'job-analysis-ui',
      status: 'COMPLETED' as const,
      gamesImported: 120,
      gamesSkipped: 5,
      analysisStatus: 'ANALYZING' as const,
    };
    localStorage.setItem('chessecho_username', 'hikaru');
    localStorage.setItem('chessecho_active_job', JSON.stringify(analyzingJob));
    const { activeJobStore } = await loadBrowserStores();

    render(<ImportGamesView connectedUsername="hikaru" />);

    expect(screen.getByText(/Import Completed Successfully!/i)).toBeInTheDocument();
    expect(screen.getByText(/Stockfish is analyzing/i)).toBeInTheDocument();

    act(() => {
      activeJobStore.set({ ...analyzingJob, analysisStatus: 'COMPLETED' });
    });
    expect(screen.getByText(/Analysis complete/i)).toBeInTheDocument();

    act(() => {
      activeJobStore.set({
        ...analyzingJob,
        analysisStatus: 'FAILED',
      });
    });
    expect(screen.getByText(/Analysis failed/i)).toBeInTheDocument();
    expect(screen.getByText(/Import Completed Successfully!/i)).toBeInTheDocument();
  });
});
