'use client';

import React, { useState } from 'react';
import { Download, CheckCircle2, Clock, Calendar, ShieldCheck, Play } from 'lucide-react';
import { JobProgress } from '../mock/mockData';

export const ImportGamesView: React.FC = () => {
  const [username, setUsername] = useState<string>('NathanZele');
  const [timeControls, setTimeControls] = useState<string[]>(['blitz', 'rapid']);
  const [playerColor, setPlayerColor] = useState<'white' | 'black' | 'both'>('both');
  const [fromDate, setFromDate] = useState<string>('2025-01');
  const [toDate, setToDate] = useState<string>('2026-08');

  const [activeJob, setActiveJob] = useState<JobProgress | null>(null);

  const handleTimeControlToggle = (tc: string) => {
    if (timeControls.includes(tc)) {
      if (timeControls.length > 1) {
        setTimeControls(timeControls.filter((item) => item !== tc));
      }
    } else {
      setTimeControls([...timeControls, tc]);
    }
  };

  const handleStartImport = () => {
    // Simulate starting an async import job and updating progress
    const jobId = 'job_' + Math.random().toString(36).substring(2, 9);
    setActiveJob({
      jobId,
      status: 'PROCESSING',
      progressPercentage: 15,
      gamesImported: 45,
      positionsDetected: 12,
      analyzedPositions: 3,
      totalPositionsToAnalyze: 18,
    });

    // Simulate progress animation over 4 seconds
    setTimeout(() => {
      setActiveJob({
        jobId,
        status: 'PROCESSING',
        progressPercentage: 60,
        gamesImported: 150,
        positionsDetected: 34,
        analyzedPositions: 12,
        totalPositionsToAnalyze: 25,
      });
    }, 2000);

    setTimeout(() => {
      setActiveJob({
        jobId,
        status: 'COMPLETED',
        progressPercentage: 100,
        gamesImported: 150,
        positionsDetected: 34,
        analyzedPositions: 25,
        totalPositionsToAnalyze: 25,
      });
    }, 4500);
  };

  return (
    <div className="max-w-5xl mx-auto space-y-6 px-4 py-6 text-slate-200">
      {/* Header */}
      <div className="bg-slate-900 p-6 rounded-2xl border border-slate-800 shadow-xl flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <div className="flex items-center space-x-2 text-xs font-bold text-emerald-400 uppercase tracking-wider">
            <Download className="w-4 h-4" />
            <span>Chess.com Game Importer</span>
          </div>
          <h2 className="text-xl font-bold text-white mt-1">
            Import & Analyze Your Game History
          </h2>
          <p className="text-xs text-slate-400 mt-1 max-w-xl">
            Import your Chess.com games to automatically detect your recurring opening positions and compute Stockfish evaluations.
          </p>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
        {/* Form Settings Card */}
        <div className="bg-slate-900 p-6 rounded-2xl border border-slate-800 shadow-xl space-y-5">
          <h3 className="text-base font-bold text-white border-b border-slate-800 pb-3 flex items-center gap-2">
            <span>Import Configuration</span>
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
              placeholder="e.g. NathanZele"
              className="w-full px-3.5 py-2.5 bg-slate-950 border border-slate-800 focus:border-emerald-500 rounded-xl text-sm font-medium text-slate-200 outline-none transition"
            />
          </div>

          {/* Multi-Select Time Controls */}
          <div className="space-y-1.5">
            <label className="text-xs font-semibold text-slate-300 flex items-center justify-between">
              <span>Time Controls (Select one or more)</span>
              <span className="text-[11px] text-emerald-400 font-normal">
                {timeControls.length} selected
              </span>
            </label>
            <div className="grid grid-cols-2 gap-2">
              {[
                { id: 'blitz', label: '⚡ Blitz' },
                { id: 'rapid', label: '⏱️ Rapid' },
                { id: 'bullet', label: '🚀 Bullet' },
                { id: 'classical', label: '♟️ Classical' },
              ].map((tc) => {
                const isSelected = timeControls.includes(tc.id);
                return (
                  <button
                    type="button"
                    key={tc.id}
                    onClick={() => handleTimeControlToggle(tc.id)}
                    className={`flex items-center justify-between px-3 py-2 rounded-xl text-xs font-semibold border transition ${
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
              {(['white', 'black', 'both'] as const).map((color) => (
                <button
                  type="button"
                  key={color}
                  onClick={() => setPlayerColor(color)}
                  className={`py-1.5 text-xs font-bold rounded-lg uppercase tracking-wider transition ${
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

          {/* Advanced Date Range Controls */}
          <div className="pt-2 border-t border-slate-800 space-y-3">
            <div className="flex items-center space-x-2 text-xs font-bold text-slate-300">
              <Calendar className="w-3.5 h-3.5 text-emerald-400" />
              <span>Advanced Date Range (YYYY-MM)</span>
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

          {/* Submit Action Button */}
          <button
            onClick={handleStartImport}
            disabled={activeJob?.status === 'PROCESSING'}
            className="w-full py-3 bg-emerald-600 hover:bg-emerald-500 disabled:opacity-50 text-white font-bold text-sm rounded-xl transition shadow-lg shadow-emerald-900/40 flex items-center justify-center space-x-2"
          >
            <Play className="w-4 h-4 fill-white" />
            <span>
              {activeJob?.status === 'PROCESSING'
                ? 'Import & Analysis in Progress...'
                : 'Start Import & Engine Analysis'}
            </span>
          </button>
        </div>

        {/* Live Job Status Monitor Card */}
        <div className="bg-slate-900 p-6 rounded-2xl border border-slate-800 shadow-xl flex flex-col justify-between space-y-6">
          <div>
            <h3 className="text-base font-bold text-white border-b border-slate-800 pb-3 flex items-center justify-between">
              <span>Live Job Status Monitor</span>
              <Clock className="w-4 h-4 text-emerald-400" />
            </h3>

            {!activeJob ? (
              <div className="py-16 text-center space-y-3">
                <div className="w-12 h-12 rounded-full bg-slate-950 border border-slate-800 flex items-center justify-center mx-auto text-slate-500">
                  <Download className="w-6 h-6" />
                </div>
                <h4 className="text-sm font-semibold text-slate-300">No Active Import Job</h4>
                <p className="text-xs text-slate-500 max-w-xs mx-auto">
                  Fill in your Chess.com username and click "Start Import & Engine Analysis" to monitor live progress.
                </p>
              </div>
            ) : (
              <div className="space-y-5 pt-3">
                <div className="flex items-center justify-between text-xs font-bold">
                  <span className="text-slate-300 flex items-center gap-2">
                    {activeJob.status === 'PROCESSING' && (
                      <span className="w-2 h-2 rounded-full bg-amber-400 animate-ping"></span>
                    )}
                    {activeJob.status === 'COMPLETED' && (
                      <CheckCircle2 className="w-4 h-4 text-emerald-400" />
                    )}
                    Job ID: {activeJob.jobId}
                  </span>
                  <span
                    className={`px-2 py-0.5 rounded text-[10px] uppercase font-bold ${
                      activeJob.status === 'COMPLETED'
                        ? 'bg-emerald-500/20 text-emerald-300 border border-emerald-500/30'
                        : 'bg-amber-500/20 text-amber-300 border border-amber-500/30'
                    }`}
                  >
                    {activeJob.status}
                  </span>
                </div>

                {/* Progress Bar */}
                <div className="space-y-1">
                  <div className="flex justify-between text-xs font-semibold">
                    <span className="text-slate-400">Analysis Progress</span>
                    <span className="text-emerald-400">{activeJob.progressPercentage}%</span>
                  </div>
                  <div className="w-full h-3 bg-slate-950 rounded-full overflow-hidden border border-slate-800 p-0.5">
                    <div
                      className="h-full bg-gradient-to-r from-emerald-500 to-emerald-400 rounded-full transition-all duration-500 ease-out"
                      style={{ width: `${activeJob.progressPercentage}%` }}
                    />
                  </div>
                </div>

                {/* Metrics Grid */}
                <div className="grid grid-cols-2 gap-3 pt-2">
                  <div className="bg-slate-950 p-3 rounded-xl border border-slate-800">
                    <span className="text-[11px] text-slate-400">Games Imported</span>
                    <div className="text-base font-bold text-slate-100 mt-0.5">
                      {activeJob.gamesImported}
                    </div>
                  </div>

                  <div className="bg-slate-950 p-3 rounded-xl border border-slate-800">
                    <span className="text-[11px] text-slate-400">Positions Detected</span>
                    <div className="text-base font-bold text-slate-100 mt-0.5">
                      {activeJob.positionsDetected}
                    </div>
                  </div>
                </div>

                <div className="bg-slate-950 p-3.5 rounded-xl border border-slate-800">
                  <span className="text-[11px] text-slate-400">Stockfish Engine Progress</span>
                  <div className="text-sm font-bold text-emerald-400 mt-0.5">
                    {activeJob.analyzedPositions} / {activeJob.totalPositionsToAnalyze} Positions Evaluated (Depth 16)
                  </div>
                </div>
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
