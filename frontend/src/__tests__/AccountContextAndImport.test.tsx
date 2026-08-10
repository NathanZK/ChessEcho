import React from 'react';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { vi, describe, it, expect, beforeEach, afterEach } from 'vitest';
import Home from '../app/page';
import { ImportGamesView } from '../components/ImportGamesView';
import * as api from '../services/api';

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
          'WHITE',
          expect.any(Number),
          expect.any(Number),
          10,
          0
        );
        expect(api.fetchPuzzles).toHaveBeenCalledWith(
          'magnuscarlsen',
          'CHESS_COM',
          'BLACK',
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

      expect(screen.getByText(/Job ID: job-999/i)).toBeInTheDocument();

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
