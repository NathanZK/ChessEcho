import React from 'react';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { vi, describe, it, expect, beforeEach, afterEach } from 'vitest';
import Home from '../app/page';
import { ImportGamesView } from '../components/ImportGamesView';
import * as api from '../services/api';

const SHORT_MONTH_NAMES = [
  'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
  'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec',
];

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

describe('Account Context and Import Games MVP Behavior', () => {
  beforeEach(() => {
    localStorage.clear();
    window.location.hash = '';
    vi.resetAllMocks();
    vi.mocked(api.fetchPuzzles).mockResolvedValue([]);
    vi.mocked(api.fetchWeaknesses).mockResolvedValue([]);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  describe('1. Active Username Persistence', () => {
    it('sets the entered username as active in localStorage upon starting an import', async () => {
      vi.mocked(api.startImportJob).mockResolvedValue({
        jobId: 'job-123',
        status: 'QUEUED',
      });

      render(<Home />);

      // Switch to Import tab using the nav tab button
      const importTabBtn = screen.getByRole('button', { name: /^import games$/i });
      fireEvent.click(importTabBtn);

      const usernameInput = screen.getByPlaceholderText(/e\.g\. Hikaru/i);
      fireEvent.change(usernameInput, { target: { value: 'gothamchess' } });

      const startBtn = screen.getByRole('button', { name: /start import/i });
      fireEvent.click(startBtn);

      await waitFor(() => {
        expect(api.startImportJob).toHaveBeenCalledWith(
          'gothamchess',
          'CHESS_COM',
          ['BLITZ', 'RAPID'],
          'BOTH',
          undefined,
          undefined
        );
      });

      expect(localStorage.getItem('chessecho_username')).toBe('gothamchess');
    });

    it('restores active username from localStorage on mount and passes it to Puzzles API', async () => {
      localStorage.setItem('chessecho_username', 'magnuscarlsen');

      render(<Home />);

      await waitFor(() => {
        expect(api.fetchPuzzles).toHaveBeenCalledWith(
          'magnuscarlsen',
          'CHESS_COM',
          'BOTH',
          expect.any(Number),
          expect.any(Number),
          10,
          0
        );
      });
    });
  });

  describe('2. Connected State & Form Access', () => {
    it('shows connected account badge in header while keeping the import form active and usable', async () => {
      localStorage.setItem('chessecho_username', 'hikaru');

      render(<Home />);

      // Header should display Connected indicator
      expect(screen.getByText(/Chess\.com Connected/i)).toBeInTheDocument();
      expect(screen.getAllByText(/hikaru/i).length).toBeGreaterThan(0);

      // Switch to Import tab
      const importTabBtn = screen.getByRole('button', { name: /^import games$/i });
      fireEvent.click(importTabBtn);

      // The import form should NOT be disabled or replaced with a static card
      const usernameInput = screen.getByPlaceholderText(/e\.g\. Hikaru/i) as HTMLInputElement;
      expect(usernameInput).toBeInTheDocument();
      expect(usernameInput.value).toBe('hikaru');

      const startBtn = screen.getByRole('button', { name: /start import/i });
      expect(startBtn).not.toBeDisabled();
    });
  });

  describe('3. Re-import / Changing Username', () => {
    it('allows starting another import for a new username without disconnecting first', async () => {
      localStorage.setItem('chessecho_username', 'player1');

      vi.mocked(api.startImportJob).mockResolvedValue({
        jobId: 'job-456',
        status: 'QUEUED',
      });

      render(<Home />);

      const importTabBtn = screen.getByRole('button', { name: /^import games$/i });
      fireEvent.click(importTabBtn);

      const usernameInput = screen.getByPlaceholderText(/e\.g\. Hikaru/i);
      fireEvent.change(usernameInput, { target: { value: 'player2' } });

      const startBtn = screen.getByRole('button', { name: /start import/i });
      fireEvent.click(startBtn);

      await waitFor(() => {
        expect(api.startImportJob).toHaveBeenCalledWith(
          'player2',
          'CHESS_COM',
          ['BLITZ', 'RAPID'],
          'BOTH',
          undefined,
          undefined
        );
      });

      // Active username updates immediately to player2
      expect(localStorage.getItem('chessecho_username')).toBe('player2');
    });

    it('renders visual MonthPicker controls, displays formatted labels, and passes YYYY-MM strings to startImportJob', async () => {
      vi.mocked(api.startImportJob).mockResolvedValue({
        jobId: 'job-month-1',
        status: 'QUEUED',
      });

      render(<Home />);

      const importTabBtn = screen.getByRole('button', { name: /^import games$/i });
      fireEvent.click(importTabBtn);

      // Verify heading and labels
      expect(screen.getByText('Advanced Date Range')).toBeInTheDocument();
      expect(screen.getByText('From month')).toBeInTheDocument();
      expect(screen.getByText('To month')).toBeInTheDocument();

      const usernameInput = screen.getByPlaceholderText(/e\.g\. Hikaru/i);
      fireEvent.change(usernameInput, { target: { value: 'dateplayer' } });

      // Click "From month" picker trigger to open popover
      const fromBtn = screen.getByRole('button', { name: 'From month' });
      expect(fromBtn).toHaveTextContent('Select month...');
      fireEvent.click(fromBtn);

      // Popover should open showing current year (e.g. 2026) and 12 month buttons
      expect(screen.getByText('Aug')).toBeInTheDocument();

      // Click "Aug" in the month grid
      fireEvent.click(screen.getByText('Aug'));

      // Check formatted human-readable label "August 2026"
      expect(fromBtn).toHaveTextContent(/August \d{4}/);

      // Open "To month" picker and select "Sep"
      const toBtn = screen.getByRole('button', { name: 'To month' });
      fireEvent.click(toBtn);
      fireEvent.click(screen.getByText('Sep'));

      expect(toBtn).toHaveTextContent(/September \d{4}/);

      const startBtn = screen.getByRole('button', { name: /start import/i });
      fireEvent.click(startBtn);

      const currentYear = new Date().getFullYear();
      await waitFor(() => {
        expect(api.startImportJob).toHaveBeenCalledWith(
          'dateplayer',
          'CHESS_COM',
          ['BLITZ', 'RAPID'],
          'BOTH',
          `${currentYear}-08`,
          `${currentYear}-09`
        );
      });

      // Clear "From month" via clear button
      const clearBtns = screen.getAllByLabelText('Clear month selection');
      fireEvent.click(clearBtns[0]);

      expect(fromBtn).toHaveTextContent('Select month...');

      // Verify container uses max-w-6xl (deliberate middle-ground desktop workspace)
      const importContainer = screen.getByText('Chess.com Game Importer').closest('.max-w-6xl');
      expect(importContainer).toBeInTheDocument();
    });

    it('selecting a month produces the exact YYYY-MM payload sent to the import API', async () => {
      vi.mocked(api.startImportJob).mockResolvedValue({
        jobId: 'job-payload-1',
        status: 'QUEUED',
      });

      render(<Home />);

      const importTabBtn = screen.getByRole('button', { name: /^import games$/i });
      fireEvent.click(importTabBtn);

      const usernameInput = screen.getByPlaceholderText(/e\.g\. Hikaru/i);
      fireEvent.change(usernameInput, { target: { value: 'payloadtest' } });

      // Select From month = August 2026
      const fromBtn = screen.getByRole('button', { name: 'From month' });
      fireEvent.click(fromBtn);
      fireEvent.click(screen.getByText('Aug'));
      expect(fromBtn).toHaveTextContent(/August 2026/);

      // Select To month = December 2026
      const toBtn = screen.getByRole('button', { name: 'To month' });
      fireEvent.click(toBtn);
      fireEvent.click(screen.getByText('Dec'));
      expect(toBtn).toHaveTextContent(/December 2026/);

      // Start import and verify exact API payload
      const startBtn = screen.getByRole('button', { name: /start import/i });
      fireEvent.click(startBtn);

      await waitFor(() => {
        const callArgs = vi.mocked(api.startImportJob).mock.calls[0];
        expect(callArgs).toBeDefined();
        expect(callArgs![0]).toBe('payloadtest'); // username
        expect(callArgs![1]).toBe('CHESS_COM'); // platform
        expect(callArgs![4]).toBe('2026-08'); // fromDate
        expect(callArgs![5]).toBe('2026-12'); // toDate
      });
    });

    it('empty month selection sends undefined dates, not empty strings or labels', async () => {
      vi.mocked(api.startImportJob).mockResolvedValue({
        jobId: 'job-empty-1',
        status: 'QUEUED',
      });

      render(<Home />);

      const importTabBtn = screen.getByRole('button', { name: /^import games$/i });
      fireEvent.click(importTabBtn);

      const usernameInput = screen.getByPlaceholderText(/e\.g\. Hikaru/i);
      fireEvent.change(usernameInput, { target: { value: 'emptytest' } });

      // Do NOT select any months — leave them as "Select month..."
      const startBtn = screen.getByRole('button', { name: /start import/i });
      fireEvent.click(startBtn);

      await waitFor(() => {
        const callArgs = vi.mocked(api.startImportJob).mock.calls[0];
        expect(callArgs).toBeDefined();
        // fromDate and toDate should be undefined (not empty strings, not labels)
        expect(callArgs![4]).toBeUndefined();
        expect(callArgs![5]).toBeUndefined();
      });
    });

    it('renders the month picker popover as a fixed overlay with high z-index, preventing ancestor overflow clipping', async () => {
      render(<Home />);

      const importTabBtn = screen.getByRole('button', { name: /^import games$/i });
      fireEvent.click(importTabBtn);

      const fromBtn = screen.getByRole('button', { name: 'From month' });
      fireEvent.click(fromBtn);

      // Popover should be in the document
      const popover = screen.getByRole('dialog', { name: /From month picker/i });
      expect(popover).toBeInTheDocument();

      // Wait for React to apply the style prop
      await waitFor(() => {
        // Popover should use fixed positioning (not absolute) to bypass ancestor overflow
        expect(popover.style.position).toBe('fixed');
        // Popover should have a high z-index to appear above cards and surrounding content
        expect(popover.style.zIndex).toBe('9999');
      });
    });

    it('positions the popover above the trigger when there is insufficient space below', async () => {
      render(<Home />);

      const importTabBtn = screen.getByRole('button', { name: /^import games$/i });
      fireEvent.click(importTabBtn);

      const fromBtn = screen.getByRole('button', { name: 'From month' });

      // Mock getBoundingClientRect to simulate trigger near bottom of viewport
      const originalGetBoundingClientRect = Element.prototype.getBoundingClientRect;
      Element.prototype.getBoundingClientRect = function () {
        const original = originalGetBoundingClientRect.call(this);
        if (this === fromBtn) {
          return {
            ...original,
            bottom: window.innerHeight - 10, // Only 10px below trigger
            top: window.innerHeight - 50,
          };
        }
        return original;
      };

      fireEvent.click(fromBtn);

      const popover = screen.getByRole('dialog', { name: /From month picker/i });

      // Wait for requestAnimationFrame to fire and position to be calculated
      await waitFor(() => {
        const popoverTop = parseFloat(popover.style.top);
        // Popover should be positioned above the trigger (top < trigger top)
        expect(popoverTop).toBeLessThan(window.innerHeight - 50);
      });

      // Restore original
      Element.prototype.getBoundingClientRect = originalGetBoundingClientRect;
    });

    it('allows selecting months independently for From and To fields', async () => {
      render(<Home />);

      const importTabBtn = screen.getByRole('button', { name: /^import games$/i });
      fireEvent.click(importTabBtn);

      const fromBtn = screen.getByRole('button', { name: 'From month' });
      const toBtn = screen.getByRole('button', { name: 'To month' });

      // Select From month
      fireEvent.click(fromBtn);
      fireEvent.click(screen.getByText('Jan'));
      expect(fromBtn).toHaveTextContent(/January \d{4}/);

      // Select To month
      fireEvent.click(toBtn);
      fireEvent.click(screen.getByText('Dec'));
      expect(toBtn).toHaveTextContent(/December \d{4}/);

      // Verify both are set independently
      expect(fromBtn).toHaveTextContent(/January/);
      expect(toBtn).toHaveTextContent(/December/);
    });

    it('prevents arbitrary date text input by only allowing selection from the picker', async () => {
      render(<Home />);

      const importTabBtn = screen.getByRole('button', { name: /^import games$/i });
      fireEvent.click(importTabBtn);

      const fromBtn = screen.getByRole('button', { name: 'From month' });

      // The button should not be an input field - it should only display formatted text
      expect(fromBtn.tagName).toBe('BUTTON');

      // Open picker and verify only valid month buttons are present
      fireEvent.click(fromBtn);
      const monthButtons = screen.getAllByRole('button').filter((btn) =>
        SHORT_MONTH_NAMES.includes(btn.textContent || '')
      );
      expect(monthButtons.length).toBe(12);

      // No text input should be present for arbitrary date entry
      const textInputs = screen.queryAllByRole('textbox');
      const dateInputs = textInputs.filter((input) =>
        (input as HTMLInputElement).type === 'text' &&
        (input as HTMLInputElement).getAttribute('aria-label')?.toLowerCase().includes('month')
      );
      expect(dateInputs.length).toBe(0);
    });
  });

  describe('4. Import in Progress & Polling', () => {
    it('persists active job in localStorage and stops polling when COMPLETED', async () => {
      vi.useFakeTimers();

      vi.mocked(api.pollJobStatus)
        .mockResolvedValueOnce({
          jobId: 'job-999',
          status: 'PROCESSING',
          gamesImported: 50,
          gamesSkipped: 2,
        })
        .mockResolvedValueOnce({
          jobId: 'job-999',
          status: 'COMPLETED',
          gamesImported: 120,
          gamesSkipped: 5,
        });

      const activeJob = {
        jobId: 'job-999',
        status: 'QUEUED' as const,
        gamesImported: 0,
        gamesSkipped: 0,
      };
      localStorage.setItem('chessecho_username', 'testuser');
      localStorage.setItem('chessecho_active_job', JSON.stringify(activeJob));

      render(
        <ImportGamesView
          connectedUsername="testuser"
          onImportStarted={vi.fn()}
          onNavigateTab={vi.fn()}
          onDisconnect={vi.fn()}
        />
      );

      expect(screen.getByText(/Import Progress/i)).toBeInTheDocument();

      // Advance timers asynchronously
      await act(async () => {
        await vi.advanceTimersByTimeAsync(2000);
      });

      expect(api.pollJobStatus).toHaveBeenCalledWith('job-999');

      await act(async () => {
        await vi.advanceTimersByTimeAsync(2000);
      });

      expect(screen.getByText(/Import Completed Successfully!/i)).toBeInTheDocument();

      const callCount = vi.mocked(api.pollJobStatus).mock.calls.length;

      // Advance more time and verify polling stopped
      await act(async () => {
        await vi.advanceTimersByTimeAsync(4000);
      });

      expect(vi.mocked(api.pollJobStatus).mock.calls.length).toBe(callCount);

      vi.useRealTimers();
    });

    it('automatically invokes onImportStarted with the imported username when job reaches COMPLETED', async () => {
      vi.useFakeTimers();

      vi.mocked(api.pollJobStatus).mockResolvedValueOnce({
        jobId: 'job-auto-1',
        status: 'COMPLETED',
        gamesImported: 42,
        gamesSkipped: 0,
      });

      const activeJob = {
        jobId: 'job-auto-1',
        status: 'PROCESSING' as const,
        gamesImported: 10,
        gamesSkipped: 0,
      };
      localStorage.setItem('chessecho_username', 'newplayer');
      localStorage.setItem('chessecho_active_job', JSON.stringify(activeJob));

      const onImportStartedMock = vi.fn();

      render(
        <ImportGamesView
          connectedUsername="newplayer"
          onImportStarted={onImportStartedMock}
          onNavigateTab={vi.fn()}
          onDisconnect={vi.fn()}
        />
      );

      // Advance time to trigger polling completion
      await act(async () => {
        await vi.advanceTimersByTimeAsync(2000);
      });

      expect(onImportStartedMock).toHaveBeenCalledWith('newplayer');

      vi.useRealTimers();
    });

    it('clears stale activeJob from localStorage, stops polling, and displays a message when pollJobStatus returns 404', async () => {
      vi.useFakeTimers();

      vi.mocked(api.pollJobStatus).mockRejectedValue(new Error('Failed to poll job status: 404'));

      const staleJob = {
        jobId: 'stale-job-999',
        status: 'PROCESSING' as const,
        gamesImported: 0,
        gamesSkipped: 0,
      };
      localStorage.setItem('chessecho_username', 'player1');
      localStorage.setItem('chessecho_active_job', JSON.stringify(staleJob));

      const onJobStatusUpdateMock = vi.fn();

      render(
        <ImportGamesView
          connectedUsername="player1"
          onJobStatusUpdate={onJobStatusUpdateMock}
        />
      );

      // Advance timers to trigger polling
      await act(async () => {
        await vi.advanceTimersByTimeAsync(2000);
      });

      // Assert active job was cleared from localStorage and state
      expect(localStorage.getItem('chessecho_active_job')).toBeNull();
      expect(onJobStatusUpdateMock).toHaveBeenCalledWith(null);
      expect(screen.getByText(/Previous import job is no longer available/i)).toBeInTheDocument();

      // Advance timers further to ensure polling stopped
      const pollCount = vi.mocked(api.pollJobStatus).mock.calls.length;
      await act(async () => {
        await vi.advanceTimersByTimeAsync(4000);
      });
      expect(vi.mocked(api.pollJobStatus).mock.calls.length).toBe(pollCount);

      vi.useRealTimers();
    });
  });


  describe('5. Puzzles and Weaknesses Username Usage & Fallback Behavior', () => {
    it('shows empty state when no active username exists and does not fetch with hardcoded defaults', async () => {
      render(<Home />);

      // Default tab is puzzles
      expect(screen.getByText(/No Practice Puzzles Available/i)).toBeInTheDocument();
      expect(screen.getByText(/Import your games using the Import Games tab/i)).toBeInTheDocument();

      // api.fetchPuzzles should not have been called with empty username
      expect(api.fetchPuzzles).not.toHaveBeenCalled();
    });

    it('passes the active username to Weaknesses view when navigated', async () => {
      localStorage.setItem('chessecho_username', 'hikaru');

      render(<Home />);

      const weaknessesTabBtn = screen.getByRole('button', { name: /^weaknesses library$/i });
      fireEvent.click(weaknessesTabBtn);

      expect(screen.getByText(/Recurring Opening Weaknesses Library/i)).toBeInTheDocument();
    });
  });
});
