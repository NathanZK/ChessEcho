'use client';

import React, { useState } from 'react';
import { Download, CheckCircle2, Clock, Calendar, Play } from 'lucide-react';
import { startImportJob, pollJobStatus, JobStatusResponse } from '../services/api';

interface ImportGamesViewProps {
  connectedUsername?: string;
  onImportStarted?: (username: string) => void;
  onNavigateTab?: (tab: 'puzzles' | 'weaknesses') => void;
  onDisconnect?: () => void;
}

export const ImportGamesView: React.FC<ImportGamesViewProps> = ({
  connectedUsername,
  onImportStarted,
  onNavigateTab,
  onDisconnect,
}) => {
  const [username, setUsername] = useState<string>(connectedUsername || '');
  const [timeControls, setTimeControls] = useState<string[]>(['BLITZ', 'RAPID']);
  const [playerColor, setPlayerColor] = useState<'WHITE' | 'BLACK' | 'BOTH'>('BOTH');
  const [fromDate, setFromDate] = useState<string>('');
  const [toDate, setToDate] = useState<string>('');

  const [activeJob, setActiveJob] = useState<JobStatusResponse | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [pollingError, setPollingError] = useState<string | null>(null);

  // Sync username input if connectedUsername changes
  React.useEffect(() => {
    if (connectedUsername) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setUsername(connectedUsername);
    }
  }, [connectedUsername]);

  // Restore saved activeJob from localStorage after client mount to prevent SSR hydration error
  React.useEffect(() => {
    if (typeof window !== 'undefined') {
      const savedUser = localStorage.getItem('chessecho_username');
      if (savedUser) {
        const saved = localStorage.getItem('chessecho_active_job');
        if (saved) {
          try {
            // eslint-disable-next-line react-hooks/set-state-in-effect
            setActiveJob(JSON.parse(saved));
          } catch {
            // invalid JSON
          }
        }
      } else {
        localStorage.removeItem('chessecho_active_job');
      }
    }
  }, []);

  const updateActiveJob = (job: JobStatusResponse | null) => {
    setActiveJob(job);
    if (typeof window !== 'undefined') {
      if (job) {
        localStorage.setItem('chessecho_active_job', JSON.stringify(job));
      } else {
        localStorage.removeItem('chessecho_active_job');
      }
    }
  };

  // Clear activeJob if user explicitly disconnects
  const prevUserRef = React.useRef(connectedUsername);
  React.useEffect(() => {
    if (prevUserRef.current && !connectedUsername) {
      updateActiveJob(null);
    }
    prevUserRef.current = connectedUsername;
  }, [connectedUsername]);

  // Resume polling on mount or tab change if activeJob is still processing
  React.useEffect(() => {
    if (!activeJob || (activeJob.status !== 'QUEUED' && activeJob.status !== 'PROCESSING')) {
      return;
    }
    const interval = setInterval(async () => {
      try {
        const statusUpdate = await pollJobStatus(activeJob.jobId);
        updateActiveJob(statusUpdate);
        setPollingError(null);
        if (statusUpdate.status === 'COMPLETED' || statusUpdate.status === 'FAILED') {
          clearInterval(interval);
        }
      } catch (err: unknown) {
        const msg = err instanceof Error ? err.message : 'Failed to poll job status';
        console.error('Error polling import job status:', err);
        setPollingError(msg);
      }
    }, 2000);

    return () => clearInterval(interval);
  }, [activeJob]);

  const handleTimeControlToggle = (tc: string) => {
    if (timeControls.includes(tc)) {
      if (timeControls.length > 1) {
        setTimeControls(timeControls.filter((item) => item !== tc));
      }
    } else {
      setTimeControls([...timeControls, tc]);
    }
  };

  const handleStartImport = async () => {
    const trimmedUser = username.trim();
    if (!trimmedUser) {
      setErrorMessage('Please enter a Chess.com username');
      return;
    }
    setErrorMessage(null);
    try {
      const response = await startImportJob(
        trimmedUser,
        'CHESS_COM',
        timeControls,
        playerColor,
        fromDate || undefined,
        toDate || undefined
      );

      if (onImportStarted) {
        onImportStarted(trimmedUser);
      }

      const initialStatus: JobStatusResponse = {
        jobId: response.jobId,
        status: response.status,
        gamesImported: 0,
        gamesSkipped: 0,
      };
      updateActiveJob(initialStatus);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to start import job';
      setErrorMessage(msg);
    }
  };

  return (
    <div className="max-w-5xl mx-auto space-y-3.5 px-4 py-2 text-slate-200">
      {/* Header */}
      <div className="bg-slate-900 p-4 rounded-2xl border border-slate-800 shadow-xl flex flex-col md:flex-row md:items-center justify-between gap-3">
        <div>
          <div className="flex items-center space-x-2 text-[11px] font-bold text-emerald-400 uppercase tracking-wider">
            <Download className="w-3.5 h-3.5" />
            <span>Chess.com Game Importer</span>
          </div>
          <h2 className="text-lg font-bold text-white mt-0.5">
            Import & Analyze Your Game History
          </h2>
          <p className="text-xs text-slate-400 mt-0.5 max-w-xl">
            Import your Chess.com games to automatically detect your recurring opening positions and compute Stockfish evaluations.
          </p>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {/* Form Settings Card */}
        <div className="bg-slate-900 p-4 rounded-2xl border border-slate-800 shadow-xl space-y-3.5">
          <h3 className="text-sm font-bold text-white border-b border-slate-800 pb-2.5 flex items-center justify-between">
            <span>Import Configuration</span>
            {connectedUsername && (
              <div className="flex items-center gap-2">
                <span className="text-xs text-emerald-400 font-medium">● Connected: {connectedUsername}</span>
                {onDisconnect && (
                  <button
                    type="button"
                    onClick={onDisconnect}
                    className="text-[11px] font-semibold text-slate-400 hover:text-rose-400 underline cursor-pointer"
                  >
                    Disconnect
                  </button>
                )}
              </div>
            )}
          </h3>

          {/* Username Input */}
          <div className="space-y-1.5">
            <label className="text-xs font-semibold text-slate-300">
              Chess.com Username
            </label>
            <input
              type="text"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              placeholder="e.g. Hikaru"
              className="w-full px-3.5 py-2.5 bg-slate-950 border border-slate-800 focus:border-emerald-500 rounded-xl text-sm font-medium text-slate-200 outline-none transition"
            />
          </div>

          {/* Multi-Select Time Controls (Order: Blitz, Rapid, Bullet, Classical) */}
          <div className="space-y-1.5">
            <label className="text-xs font-semibold text-slate-300 flex items-center justify-between">
              <span>Time Controls (Select one or more)</span>
              <span className="text-[11px] text-emerald-400 font-normal">
                {timeControls.length} selected
              </span>
            </label>
            <div className="grid grid-cols-2 gap-2">
              {[
                { id: 'BLITZ', label: '⚡ Blitz' },
                { id: 'RAPID', label: '⏱️ Rapid' },
                { id: 'BULLET', label: '🚀 Bullet' },
                { id: 'CLASSICAL', label: '♟️ Classical' },
              ].map((tc) => {
                const isSelected = timeControls.includes(tc.id);
                return (
                  <button
                    type="button"
                    key={tc.id}
                    onClick={() => handleTimeControlToggle(tc.id)}
                    className={`flex items-center justify-between px-3 py-2 rounded-xl text-xs font-semibold border transition cursor-pointer ${
                      isSelected
                        ? 'bg-emerald-600/20 border-emerald-500 text-emerald-300'
                        : 'bg-slate-950 border-slate-800 text-slate-400 hover:text-slate-200'
                    }`}
                  >
                    <span>{tc.label}</span>
                    <input
                      type="checkbox"
                      checked={isSelected}
                      readOnly
                      className="accent-emerald-500 pointer-events-none"
                    />
                  </button>
                );
              })}
            </div>
          </div>

          {/* Player Color Selection */}
          <div className="space-y-1.5">
            <label className="text-xs font-semibold text-slate-300">
              Player Color
            </label>
            <div className="grid grid-cols-3 gap-2 bg-slate-950 p-1 rounded-xl border border-slate-800">
              {(['WHITE', 'BLACK', 'BOTH'] as const).map((color) => (
                <button
                  type="button"
                  key={color}
                  onClick={() => setPlayerColor(color)}
                  className={`py-1.5 text-xs font-bold rounded-lg uppercase tracking-wider transition cursor-pointer ${
                    playerColor === color
                      ? 'bg-emerald-600 text-white shadow-sm'
                      : 'text-slate-400 hover:text-slate-200'
                  }`}
                >
                  {color}
                </button>
              ))}
            </div>
          </div>

          {/* Advanced Date Range Controls (Optional) */}
          <div className="pt-2 border-t border-slate-800 space-y-3">
            <div className="flex items-center space-x-2 text-xs font-bold text-slate-300">
              <Calendar className="w-3.5 h-3.5 text-emerald-400" />
              <span>Advanced Date Range (Optional, YYYY-MM)</span>
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1">
                <span className="text-[11px] text-slate-400">From Month</span>
                <input
                  type="month"
                  value={fromDate}
                  onChange={(e) => setFromDate(e.target.value)}
                  className="w-full px-3 py-1.5 bg-slate-950 border border-slate-800 focus:border-emerald-500 rounded-lg text-xs font-mono text-slate-200 outline-none"
                />
              </div>

              <div className="space-y-1">
                <span className="text-[11px] text-slate-400">To Month</span>
                <input
                  type="month"
                  value={toDate}
                  onChange={(e) => setToDate(e.target.value)}
                  className="w-full px-3 py-1.5 bg-slate-950 border border-slate-800 focus:border-emerald-500 rounded-lg text-xs font-mono text-slate-200 outline-none"
                />
              </div>
            </div>
          </div>

          {errorMessage && (
            <div className="p-3.5 bg-rose-500/10 border border-rose-500/30 rounded-xl text-xs font-semibold text-rose-300 whitespace-pre-line">
              ⚠️ {errorMessage}
            </div>
          )}

          {/* Submit Action Button */}
          <button
            type="button"
            onClick={handleStartImport}
            disabled={activeJob?.status === 'QUEUED' || activeJob?.status === 'PROCESSING'}
            className="w-full py-3 bg-emerald-600 hover:bg-emerald-500 disabled:opacity-50 text-white font-bold text-sm rounded-xl transition shadow-lg shadow-emerald-900/40 flex items-center justify-center space-x-2 cursor-pointer disabled:cursor-not-allowed"
          >
            <Play className="w-4 h-4 fill-white" />
            <span>
              {activeJob?.status === 'QUEUED' || activeJob?.status === 'PROCESSING'
                ? 'Import Job Active...'
                : 'Start Import & Engine Analysis'}
            </span>
          </button>
        </div>

        {/* Live Job Status Monitor Card */}
        <div className="bg-slate-900 p-4 rounded-2xl border border-slate-800 shadow-xl flex flex-col justify-between space-y-4">
          <div>
            <h3 className="text-sm font-bold text-white border-b border-slate-800 pb-2.5 flex items-center justify-between">
              <span>Live Job Status Monitor</span>
              <Clock className="w-4 h-4 text-emerald-400" />
            </h3>

            {!activeJob ? (
              <div className="py-8 text-center space-y-2.5">
                <div className="w-10 h-10 rounded-full bg-slate-950 border border-slate-800 flex items-center justify-center mx-auto text-slate-500">
                  <Download className="w-5 h-5" />
                </div>
                <h4 className="text-xs font-semibold text-slate-300">No Active Import Job</h4>
                <p className="text-[11px] text-slate-500 max-w-xs mx-auto">
                  Fill in your Chess.com username and click &quot;Start Import &amp; Engine Analysis&quot; to monitor live progress.
                </p>
              </div>
            ) : (
              <div className="space-y-5 pt-3">
                <div className="flex items-center justify-between text-xs font-bold">
                  <span className="text-slate-300 flex items-center gap-2 truncate max-w-[200px]">
                    {(activeJob.status === 'QUEUED' || activeJob.status === 'PROCESSING') && (
                      <span className="w-2 h-2 rounded-full bg-amber-400 animate-ping"></span>
                    )}
                    {activeJob.status === 'COMPLETED' && (
                      <CheckCircle2 className="w-4 h-4 text-emerald-400" />
                    )}
                    Job ID: {activeJob.jobId.slice(0, 8)}…
                  </span>
                  <span
                    className={`px-2.5 py-1 rounded-md text-[10px] uppercase font-bold tracking-wide ${
                      activeJob.status === 'COMPLETED'
                        ? 'bg-emerald-500/20 text-emerald-300 border border-emerald-500/30'
                        : activeJob.status === 'FAILED'
                        ? 'bg-rose-500/20 text-rose-300 border border-rose-500/30'
                        : 'bg-amber-500/20 text-amber-300 border border-amber-500/30'
                    }`}
                  >
                    {activeJob.status}
                  </span>
                </div>

                {/* Metrics Grid */}
                <div className="grid grid-cols-2 gap-3 pt-2">
                  <div className="bg-slate-950 p-3 rounded-xl border border-slate-800">
                    <span className="text-[11px] text-slate-400">Games Imported</span>
                    <div className="text-base font-bold text-slate-100 mt-0.5 font-mono">
                      {activeJob.gamesImported}
                    </div>
                  </div>

                  <div className="bg-slate-950 p-3 rounded-xl border border-slate-800">
                    <span className="text-[11px] text-slate-400">Games Skipped</span>
                    <div className="text-base font-bold text-slate-100 mt-0.5 font-mono">
                      {activeJob.gamesSkipped}
                    </div>
                  </div>
                </div>

                {activeJob.status === 'COMPLETED' && (
                  <div className="p-4 bg-emerald-950/40 border border-emerald-500/40 rounded-xl space-y-3">
                    <div className="text-xs font-bold text-emerald-300 flex items-center gap-1.5">
                      <CheckCircle2 className="w-4 h-4 text-emerald-400" />
                      <span>Import Completed Successfully!</span>
                    </div>
                    <p className="text-[11px] text-slate-300">
                      Your games have been imported into the database and board positions extracted. Stockfish is continuously analyzing candidate positions in the background.
                    </p>
                    <div className="flex items-center gap-2 pt-1">
                      <button
                        type="button"
                        onClick={() => {
                          const effectiveUsername = connectedUsername || username;
                          if (onImportStarted && effectiveUsername) {
                            onImportStarted(effectiveUsername);
                          }
                          if (onNavigateTab) {
                            onNavigateTab('puzzles');
                          }
                        }}
                        className="px-3 py-1.5 bg-emerald-600 hover:bg-emerald-500 text-white font-bold text-xs rounded-lg transition shadow-sm cursor-pointer"
                      >
                        Practice Puzzles
                      </button>
                      <button
                        type="button"
                        onClick={() => {
                          const effectiveUsername = connectedUsername || username;
                          if (onImportStarted && effectiveUsername) {
                            onImportStarted(effectiveUsername);
                          }
                          if (onNavigateTab) {
                            onNavigateTab('weaknesses');
                          }
                        }}
                        className="px-3 py-1.5 bg-slate-800 hover:bg-slate-700 text-slate-200 font-bold text-xs rounded-lg transition shadow-sm border border-slate-700 cursor-pointer"
                      >
                        View Weaknesses
                      </button>
                    </div>
                  </div>
                )}

                {activeJob.errorMessage && (
                  <div className="bg-rose-950/40 p-3 rounded-xl border border-rose-800/60 text-xs text-rose-300 whitespace-pre-line">
                    Error: {activeJob.errorMessage}
                  </div>
                )}

                {pollingError && (
                  <div className="bg-rose-950/40 p-3 rounded-xl border border-rose-800/60 text-xs text-rose-300 whitespace-pre-line">
                    ⚠️ {pollingError}
                  </div>
                )}
              </div>
            )}
          </div>

          <div className="text-[11px] text-slate-500 bg-slate-950/60 p-3 rounded-xl border border-slate-800/80">
            💡 Import jobs fetch PGN archives asynchronously and queue unique opening positions for background Stockfish analysis.
          </div>
        </div>
      </div>
    </div>
  );
};
