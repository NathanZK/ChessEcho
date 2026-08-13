'use client';

import React from 'react';
import { ExternalLink, X } from 'lucide-react';

interface HistoricalGamesModalProps {
  urls: string[];
  onClose: () => void;
}

export const HistoricalGamesModal: React.FC<HistoricalGamesModalProps> = ({
  urls,
  onClose,
}) => {
  if (!urls || urls.length === 0) return null;

  return (
    <div className="fixed inset-0 bg-slate-950/80 backdrop-blur-sm z-50 flex items-center justify-center p-4">
      <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 max-w-md w-full shadow-2xl space-y-4">
        <div className="flex items-center justify-between border-b border-slate-800 pb-3">
          <h3 className="text-base font-bold text-white flex items-center gap-2">
            <ExternalLink className="w-4 h-4 text-emerald-400" />
            Source Games
          </h3>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close modal"
            className="text-slate-400 hover:text-white p-1 rounded-lg hover:bg-slate-800 transition cursor-pointer"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        <p className="text-xs text-slate-400">
          The following source games contain this recurring position:
        </p>

        <div className="max-h-60 overflow-y-auto space-y-2 pr-1">
          {urls.map((url, idx) => (
            <a
              key={idx}
              href={url}
              target="_blank"
              rel="noreferrer"
              className="flex items-center justify-between p-3 rounded-xl bg-slate-950 hover:bg-slate-800 border border-slate-800 text-xs font-semibold text-slate-300 hover:text-emerald-400 transition"
            >
              <span className="truncate max-w-[280px]">
                Game #{idx + 1}: {url}
              </span>
              <ExternalLink className="w-3.5 h-3.5 shrink-0 ml-2 text-slate-400" />
            </a>
          ))}
        </div>

        <div className="pt-2 flex justify-end">
          <button
            type="button"
            onClick={onClose}
            className="px-4 py-2 bg-slate-800 hover:bg-slate-700 text-white text-xs font-bold rounded-xl transition cursor-pointer"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  );
};
